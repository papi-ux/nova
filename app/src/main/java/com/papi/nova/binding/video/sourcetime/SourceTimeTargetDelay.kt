package com.papi.nova.binding.video.sourcetime

/**
 * The presentation delay Android adds to every mapped target before it is scheduled (#242, review
 * W26 F2; DESIGN-242-ANDROID addendum 2): how far after the host's mapped time a frame is shown, so
 * that the refresh it is due at is one the display can still reach.
 *
 * The shared mapper's target is source time on this clock plus the reserve that covers decode
 * lateness, so a frame is ready at about its target. A released MediaCodec buffer can only be shown
 * at a refresh the compositor can still take, though: the earliest frame timeline whose deadline has
 * not passed, typically a refresh or more after "now" (the display's pipeline lead). Without a delay
 * every target is already behind that refresh, every vsync shows the newest queued frame, and
 * presentation collapses into arrival order whatever the host's cadence was.
 *
 * The delay is learned per stream, never a constant:
 * - Floor: the decode-to-release latency plus one refresh. The decode-to-release latency of a frame
 *   is the lead the last vsync measured (its earliest reachable refresh minus the moment it ran),
 *   less how early the frame was decoded against its own target. A frame later than its target is
 *   the reserve's business (bounded by the shared contract), so lateness is never learned here and
 *   this cannot grow past the contract's bounded reserve. The refresh covers the wait from decode
 *   to the next vsync callback, which is anywhere from nothing to one period. With capacity 1 there
 *   is no room to hold a frame across a refresh, so only half of it is added: the scheduler's
 *   half-refresh rounding still keeps every frame on time, and nothing has to wait for a second
 *   vsync behind a frame that is already queued.
 * - The 98th percentile of that floor over the last three seconds (at most 1024 frames), like the
 *   reserve. A rise is taken at once: a delay that is too small is arrival pacing. A fall waits for
 *   two clean seconds and then releases at most a millisecond per second, so frames that start to
 *   arrive early let it shrink back without a visible step.
 * - Always within [[MINIMUM_US], [MAXIMUM_US]]. Wanting more than the maximum is [limited], which the
 *   renderer reports as buffer_limit.
 *
 * Pure and synchronized: [onVsync] runs on the vsync thread and [onAdmitted] on the renderer thread.
 */
class SourceTimeTargetDelay(private val capacity: Int) {
    private val sampleAt = LongArray(SAMPLES)
    private val sampleValue = LongArray(SAMPLES)
    private val scratch = LongArray(SAMPLES)
    private var head = 0
    private var size = 0
    private var leadNs = -1L
    private var periodNs = 0L
    private var delayNs = MINIMUM_US * 1000
    private var cleanSinceNs = -1L
    private var lastAdmittedNs = -1L
    private var releaseRemainder = 0L
    private var learned = false

    /** The last admission wanted more than [MAXIMUM_US]. */
    @get:Synchronized
    var limited = false
        private set

    init {
        require(capacity >= 1) { "capacity must be at least one frame" }
    }

    /** The delay to add to the next mapped target. */
    @get:Synchronized
    val currentNs: Long
        get() = delayNs

    /** The delay in microseconds, or null before any frame has measured it. */
    @get:Synchronized
    val reportUs: Long?
        get() = if (learned) delayNs / 1000 else null

    /** One vsync: [earliestSlotNs] is the earliest refresh still reachable at [nowNs]. */
    @Synchronized
    fun onVsync(nowNs: Long, earliestSlotNs: Long, vsyncPeriodNs: Long) {
        if (vsyncPeriodNs <= 0) return
        leadNs = maxOf(0L, earliestSlotNs - nowNs)
        periodNs = vsyncPeriodNs
    }

    /**
     * One decoded frame with a mapped target ([rawTargetNs], the mapper's own target). Learns from it
     * and returns the delay to add to it.
     */
    @Synchronized
    fun onAdmitted(decodedNs: Long, rawTargetNs: Long): Long {
        if (leadNs < 0 || periodNs <= 0) return delayNs
        val early = minOf(0L, decodedNs - rawTargetNs)
        push(decodedNs, maxOf(0L, leadNs + early))
        for (i in 0 until size) {
            scratch[i] = sampleValue[(head + i) % SAMPLES]
        }
        java.util.Arrays.sort(scratch, 0, size)
        val rank = ((size.toLong() * PERCENTILE + 99) / 100 - 1).toInt()
        val wait = if (capacity >= 2) periodNs else periodNs / 2
        val desired = scratch[rank] + wait
        limited = desired > MAXIMUM_US * 1000
        val bounded = desired.coerceIn(MINIMUM_US * 1000, MAXIMUM_US * 1000)
        if (bounded > delayNs) {
            delayNs = bounded
            cleanSinceNs = -1
            releaseRemainder = 0
        } else {
            if (cleanSinceNs < 0) cleanSinceNs = decodedNs
            if (lastAdmittedNs >= 0 && decodedNs - cleanSinceNs >= CLEAN_HOLD_NS) {
                // Only time after the clean hold earns release credit, as for the reserve.
                val elapsed = minOf(decodedNs - lastAdmittedNs, decodedNs - cleanSinceNs - CLEAN_HOLD_NS)
                val credit = maxOf(0L, elapsed) * RELEASE_NS_PER_SECOND + releaseRemainder
                delayNs -= minOf(delayNs - bounded, credit / 1_000_000_000L)
                releaseRemainder = credit % 1_000_000_000L
            }
        }
        lastAdmittedNs = decodedNs
        learned = true
        return delayNs
    }

    /** A new output or display timing: learn the delay again from nothing. */
    @Synchronized
    fun reset() {
        head = 0
        size = 0
        leadNs = -1
        periodNs = 0
        delayNs = MINIMUM_US * 1000
        cleanSinceNs = -1
        lastAdmittedNs = -1
        releaseRemainder = 0
        learned = false
        limited = false
    }

    private fun push(at: Long, value: Long) {
        if (size == SAMPLES) {
            head = (head + 1) % SAMPLES
            size--
        }
        val tail = (head + size) % SAMPLES
        sampleAt[tail] = at
        sampleValue[tail] = value
        size++
        while (size > 1 && at - sampleAt[head] > WINDOW_NS) {
            head = (head + 1) % SAMPLES
            size--
        }
    }

    companion object {
        /** No delay: a display whose earliest refresh is already at the mapped target needs none. */
        const val MINIMUM_US = 0L

        /**
         * 100 ms: six refreshes at 60 Hz. It covers the deepest compositor lead expected on a
         * 24-60 Hz output (about three refreshes) plus the refresh of wait, and bounds what a broken
         * lead measurement could hold a decoded buffer for.
         */
        const val MAXIMUM_US = 100_000L

        const val PERCENTILE = 98L
        const val SAMPLES = 1024
        const val WINDOW_NS = 3_000_000_000L
        const val CLEAN_HOLD_NS = 2_000_000_000L

        /** Release at most a millisecond of delay per second once clean. */
        const val RELEASE_NS_PER_SECOND = 1_000_000L
    }
}
