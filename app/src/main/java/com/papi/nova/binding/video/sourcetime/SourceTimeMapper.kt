/*
 * The Android side of Nova's shared source-clock mapper: the same state machine, policy and
 * reasons as the Linux client's clients/deck/src/runtime/deck_source_clock_mapper.cpp, checked by
 * the same replay fixture (shared/presentation/tests/source-time-replay-v1.json).
 *
 * Its design draws on Nonary's VRR fork of Moonlight PC (moonlight-qt), revision
 * 1ad5848bd2190ab9dd9eea00d4730740d530ec29, in particular the wrap-aware RTP cadence step of
 * VrrTimingController::observeCadence(), the bounded, time-credited lower-envelope offset of
 * VrrTimingController::observePlayoutOffset() and the tick conversion of
 * VrrTimingController::rtpTicksToUs() in
 *   https://github.com/Nonary/moonlight-qt/blob/1ad5848bd2190ab9dd9eea00d4730740d530ec29/app/streaming/video/ffmpeg-renderers/pacer/vrr/vrrtimingcontroller.cpp
 * moonlight-qt is free software under the GNU General Public License version 3, as is Nova
 * (LICENSE.txt). Nova's version is a much smaller state machine with no presenter, latch or cadence
 * smoothing, anchored on the streaming core's enqueue time. See
 * app/src/main/assets/licenses/NOTICE-Nonary-moonlight-qt.txt.
 */
package com.papi.nova.binding.video.sourcetime

/**
 * Maps the host's frame times onto this device's clock, one frame at a time, without ever using a
 * frame's own arrival as its time.
 *
 * observe() takes a frame's raw RTP timestamp and a local anchor taken before any decoder wait (on
 * Android the core's enqueue time; a callback can block behind MediaCodec input, so callback
 * ingress is diagnostic only). Source time is the RTP timestamp unwrapped in frame order. The
 * offset to local time is the lowest (anchor - source) of the last three seconds, so jitter and
 * queueing only ever make samples later; once warm, the applied offset follows that minimum by at
 * most a millisecond per second and a tenth of a millisecond per frame. That budget only smooths:
 * a correction it cannot fund yet waits as debt and is never by itself a reason to start again.
 *
 * Only sustained divergence starts a new epoch (the shared contract's instability revision 2), each
 * on its own confirming detector:
 * - the lower envelope moved from the oldest estimate still in the window by more than
 *   max(offsetDivergenceToleranceUs, 4 x anchorResolutionUs), with offsetDivergenceFrames
 *   consecutive raw samples confirming the same direction (so one low outlier never does);
 * - the post-slew debt stayed beyond offsetBacklogLimitUs in one direction for longer than
 *   offsetBacklogHoldUs;
 * - the raw sample stood more than offsetStepThresholdUs from the applied offset, in one direction,
 *   for offsetStepFrames consecutive frames (a genuine step the old low samples would hide).
 * That frame starts the new epoch, pending with clock_mapping_unstable, and the mapping warms up
 * again. A target is the mapped source time plus the current reserve, and targets never go
 * backwards within an epoch.
 *
 * ready() learns the reserve from decoded-ready lateness: the 98th percentile of the last three
 * seconds plus half a millisecond, rising half a millisecond per frame at most, falling only after
 * two clean seconds at a quarter millisecond per second, and never above min(12 ms, capacity x
 * source period). A single lateness beyond 25 ms is a local hitch: it is counted as pressure and
 * not learned as link jitter.
 *
 * Frames that cannot be placed are unavailable with a reason, and the caller falls back to arrival
 * for that frame: no first timestamp yet because it was zero (source_timestamp_missing), the same
 * timestamp again (duplicate_timestamp), an older or repeated frame identity (stale_frame).
 * Backwards or half-range RTP steps and forward gaps over a second start a new epoch
 * (source_discontinuity), as does the local clock running backwards (clock_mapping_unstable); the
 * first frame of the new epoch carries that reason.
 *
 * Every public method is synchronized: observe() runs on the decode-unit submission thread and
 * ready() on the renderer thread. All of it is a few comparisons over bounded arrays.
 */
class SourceTimeMapper(
    private val policy: SourceTimePolicy = SourceTimePolicy.DEFAULT,
    private val capacity: Int = 2,
    private val admittedPeriodUs: Long = 16_666,
) {
    /** One frame's place on the local clock, as judged when it arrived. */
    data class Point(
        val epoch: Long,
        val frame: Int,
        val rtp: Long,
        /** Source time since the epoch's first frame. */
        val sourceUs: Long,
        /** Source time on the local clock, before the reserve. */
        val baseUs: Long,
        /** When the frame is meant to be shown. */
        val targetUs: Long,
        /** False: no usable timing point; present this frame by arrival. */
        val valid: Boolean,
        /** True once warm; a valid but inactive point is pending. */
        val active: Boolean,
        /** The shared reason string; "none" when active and nothing happened. */
        val reason: String,
    ) {
        val state: String
            get() = if (!valid) STATE_UNAVAILABLE else if (active) STATE_SOURCE_TIME else STATE_PENDING
    }

    private val offsetAt = LongArray(policy.offsetSamples.toInt())
    private val offsetValue = LongArray(policy.offsetSamples.toInt())
    private var offsetHead = 0
    private var offsetSize = 0
    private val readyAt = LongArray(policy.readinessSamples.toInt())
    private val readyValue = LongArray(policy.readinessSamples.toInt())
    private val readyScratch = LongArray(policy.readinessSamples.toInt())
    private var readyHead = 0
    private var readySize = 0
    private val periods = LongArray(MAX_PERIODS)
    private val periodScratch = LongArray(MAX_PERIODS)
    private var periodHead = 0
    private var periodSize = 0
    // Lower-envelope estimates, one per observation, with the offset history's bounds: the oldest
    // still retained is the divergence detector's reference.
    private val envelopeAt = LongArray(policy.offsetSamples.toInt())
    private val envelopeValue = LongArray(policy.offsetSamples.toInt())
    private var envelopeHead = 0
    private var envelopeSize = 0
    private var backlogSince = -1L
    private var divergenceFrames = 0L
    private var stepFrames = 0L
    private var divergenceDirection = 0
    private var stepDirection = 0
    private var backlogDirection = 0

    private var period = admittedPeriodUs
    private var reserve = 0L
    private var offset = 0L
    private var lastAnchor = 0L
    private var lastTarget = 0L
    private var slewRemainder = 0L
    private var lastReady = 0L
    private var cleanSince = -1L
    private var releaseRemainder = 0L
    private var ticks = 0L
    private var lastFrame = 0L
    private var lastRtp = 0L
    private var observations = 0L
    private var have = false

    var epoch = 0L
        private set

    /** The last ready() wanted more reserve than the cap allows, or saw a hitch. */
    var pressure = false
        private set

    init {
        require(capacity == 1 || capacity == 2) { "capacity must be 1 or 2" }
        require(admittedPeriodUs in 1..1_000_000) { "admitted period out of range" }
        require(policy.isBounded()) { "invalid bounded source-clock policy" }
        resetLocked()
    }

    @get:Synchronized
    val reserveUs: Long
        get() = reserve

    @get:Synchronized
    val historySize: Int
        get() = offsetSize

    @Synchronized
    fun reserveCapUs(): Long = minOf(policy.reserveMaximumUs, capacity.toLong() * period)

    @Synchronized
    fun reset() = resetLocked()

    private fun resetLocked() {
        epoch++
        have = false
        ticks = 0
        observations = 0
        offset = 0
        lastAnchor = 0
        lastTarget = 0
        lastReady = 0
        slewRemainder = 0
        releaseRemainder = 0
        cleanSince = -1
        pressure = false
        offsetHead = 0
        offsetSize = 0
        envelopeHead = 0
        envelopeSize = 0
        backlogSince = -1
        divergenceFrames = 0
        stepFrames = 0
        divergenceDirection = 0
        stepDirection = 0
        backlogDirection = 0
        readyHead = 0
        readySize = 0
        periodHead = 0
        periodSize = 0
        period = admittedPeriodUs
        reserve = minOf(policy.reserveStartUs, reserveCapUs())
    }

    @Synchronized
    fun observe(frame: Int, rtpTimestamp: Long, anchorUs: Long): Point {
        val rtp = rtpTimestamp and U32
        val frameU = frame.toLong() and U32
        if (anchorUs < 0 || anchorUs > MAX_US - policy.reserveMaximumUs) {
            resetLocked()
            return invalid(frame, rtp, REASON_CLOCK_MAPPING_UNSTABLE)
        }

        var phase = PHASE_WARMUP
        if (have) {
            val df = (frameU - lastFrame) and U32
            val dt = (rtp - lastRtp) and U32
            if (df == 0L || df >= HALF_RANGE) {
                return invalid(frame, rtp, REASON_STALE_FRAME)
            }
            if (anchorUs < lastAnchor || dt >= HALF_RANGE || dt * 1000 / 90 > policy.discontinuityUs) {
                phase = if (anchorUs < lastAnchor) REASON_CLOCK_MAPPING_UNSTABLE else REASON_SOURCE_DISCONTINUITY
                resetLocked()
            } else if (dt == 0L) {
                lastFrame = frameU
                return invalid(frame, rtp, REASON_DUPLICATE_TIMESTAMP)
            } else {
                ticks += dt
                pushPeriod(dt * 1000 / 90 / df)
                period = maxOf(1L, medianPeriod())
                reserve = minOf(reserve, reserveCapUs())
            }
        }
        if (!have) {
            if (rtp == 0L) {
                return invalid(frame, rtp, REASON_SOURCE_TIMESTAMP_MISSING)
            }
            have = true
            ticks = 0
            offset = anchorUs
            lastAnchor = anchorUs
        }
        if (ticks > MAX_US * 90 / 1000) {
            resetLocked()
            return invalid(frame, rtp, REASON_CLOCK_MAPPING_UNSTABLE)
        }

        val sourceUs = ticks * 1000 / 90
        pushOffset(anchorUs, anchorUs - sourceUs)
        var minimum = Long.MAX_VALUE
        for (i in 0 until offsetSize) {
            val value = offsetValue[(offsetHead + i) % offsetValue.size]
            if (value < minimum) minimum = value
        }
        pushEnvelope(anchorUs, minimum)
        observations++
        if (observations <= policy.warmupFrames) {
            offset = minOf(offset, minimum)
        } else {
            // Elapsed-time credit, so the frame rate does not change the slew budget.
            val numerator = (anchorUs - lastAnchor) * policy.offsetSlewUsPerSecond + slewRemainder
            val step = minOf(policy.offsetMaxStepUs, numerator / 1_000_000)
            slewRemainder = numerator % 1_000_000
            val correction = minimum - offset
            val applied = correction.coerceIn(-step, step)

            // Resolution and jitter can move the estimate by much more than one frame's slew
            // credit; smoothing debt alone is never an instability signal.
            val tolerance = maxOf(policy.offsetDivergenceToleranceUs, 4 * policy.anchorResolutionUs)
            val reference = envelopeValue[envelopeHead]
            val movement = directionBeyond(minimum - reference, tolerance)
            val sampleMovement = directionBeyond(anchorUs - sourceUs - reference, tolerance)
            val divergence = if (movement == sampleMovement) movement else 0
            divergenceFrames = confirmed(divergence, divergenceDirection, divergenceFrames)
            divergenceDirection = divergence

            // A forward step would otherwise hide behind old low samples for the whole window:
            // samples in one direction, not one jitter spike, re-anchor it promptly.
            val displacement = directionBeyond(anchorUs - sourceUs - offset, policy.offsetStepThresholdUs)
            stepFrames = confirmed(displacement, stepDirection, stepFrames)
            stepDirection = displacement

            val backlog = directionBeyond(correction - applied, policy.offsetBacklogLimitUs)
            if (backlog == 0) {
                backlogSince = -1
            } else if (backlog != backlogDirection || backlogSince < 0) {
                backlogSince = anchorUs
            }
            backlogDirection = backlog

            if (divergenceFrames >= policy.offsetDivergenceFrames || stepFrames >= policy.offsetStepFrames ||
                (backlogSince >= 0 && anchorUs - backlogSince > policy.offsetBacklogHoldUs)
            ) {
                // Cancel the old epoch and its readiness history; exactly this forward sample
                // seeds the new warm-up, pending even with warmupFrames == 1.
                resetLocked()
                return observe(frame, rtpTimestamp, anchorUs)
                    .copy(active = false, reason = REASON_CLOCK_MAPPING_UNSTABLE)
            }
            offset += applied
        }

        val baseUs = sourceUs + offset
        if (baseUs < 0 || baseUs > MAX_US - reserve) {
            resetLocked()
            return invalid(frame, rtp, REASON_CLOCK_MAPPING_UNSTABLE)
        }
        val targetUs = maxOf(baseUs + reserve, lastTarget + 1)
        if (targetUs > MAX_US) {
            resetLocked()
            return invalid(frame, rtp, REASON_CLOCK_MAPPING_UNSTABLE)
        }
        val active = observations >= policy.warmupFrames
        // A discontinuity or clock reason stays on its first re-anchored frame, warm or not.
        val reason = if (phase != PHASE_WARMUP) phase else if (active) REASON_NONE else PHASE_WARMUP

        lastFrame = frameU
        lastRtp = rtp
        lastAnchor = anchorUs
        lastTarget = targetUs
        return Point(epoch, frame, rtp, sourceUs, baseUs, targetUs, true, active, reason)
    }

    /** Learn the reserve from one decoded frame. Points from an earlier epoch are ignored. */
    @Synchronized
    fun ready(point: Point, decodedUs: Long) {
        if (!point.valid || point.epoch != epoch || decodedUs < point.baseUs || decodedUs < lastReady ||
            decodedUs > MAX_US
        ) {
            return
        }
        val elapsed = if (lastReady != 0L) decodedUs - lastReady else 0L
        lastReady = decodedUs
        val lateness = decodedUs - point.baseUs
        if (lateness > policy.hitchExclusionUs) {
            pressure = true
            cleanSince = -1
            return
        }
        pushReady(decodedUs, lateness)
        for (i in 0 until readySize) {
            readyScratch[i] = readyValue[(readyHead + i) % readyValue.size]
        }
        java.util.Arrays.sort(readyScratch, 0, readySize)
        val rank = ((readySize.toLong() * policy.readinessPercentile + 99) / 100 - 1).toInt()
        val desired = readyScratch[rank] + policy.readinessMarginUs
        val cap = reserveCapUs()
        pressure = desired > cap
        val bounded = minOf(desired, cap)
        if (bounded > reserve) {
            reserve += minOf(policy.reserveAttackUs, bounded - reserve)
            cleanSince = -1
        } else {
            if (cleanSince < 0) cleanSince = decodedUs
            if (decodedUs - cleanSince >= policy.cleanHoldUs) {
                // Only time after the clean hold earns release credit.
                val releaseElapsed = minOf(elapsed, decodedUs - cleanSince - policy.cleanHoldUs)
                val credit = releaseElapsed * policy.reserveReleaseUsPerSecond + releaseRemainder
                reserve -= minOf(reserve - bounded, credit / 1_000_000)
                releaseRemainder = credit % 1_000_000
            }
        }
        reserve = reserve.coerceIn(0L, cap)
    }

    private fun invalid(frame: Int, rtp: Long, reason: String) =
        Point(epoch, frame, rtp, 0, 0, 0, false, false, reason)

    private fun pushOffset(at: Long, value: Long) {
        if (offsetSize == offsetAt.size) {
            offsetHead = (offsetHead + 1) % offsetAt.size
            offsetSize--
        }
        val tail = (offsetHead + offsetSize) % offsetAt.size
        offsetAt[tail] = at
        offsetValue[tail] = value
        offsetSize++
        while (offsetSize > 0 && at - offsetAt[offsetHead] > policy.offsetWindowUs) {
            offsetHead = (offsetHead + 1) % offsetAt.size
            offsetSize--
        }
    }

    private fun pushReady(at: Long, value: Long) {
        if (readySize == readyAt.size) {
            readyHead = (readyHead + 1) % readyAt.size
            readySize--
        }
        val tail = (readyHead + readySize) % readyAt.size
        readyAt[tail] = at
        readyValue[tail] = value
        readySize++
        while (readySize > 0 && at - readyAt[readyHead] > policy.readinessWindowUs) {
            readyHead = (readyHead + 1) % readyAt.size
            readySize--
        }
    }

    private fun pushEnvelope(at: Long, value: Long) {
        if (envelopeSize == envelopeAt.size) {
            envelopeHead = (envelopeHead + 1) % envelopeAt.size
            envelopeSize--
        }
        val tail = (envelopeHead + envelopeSize) % envelopeAt.size
        envelopeAt[tail] = at
        envelopeValue[tail] = value
        envelopeSize++
        while (envelopeSize > 0 && at - envelopeAt[envelopeHead] > policy.offsetWindowUs) {
            envelopeHead = (envelopeHead + 1) % envelopeAt.size
            envelopeSize--
        }
    }

    private fun pushPeriod(value: Long) {
        if (periodSize == periods.size) {
            periodHead = (periodHead + 1) % periods.size
            periodSize--
        }
        periods[(periodHead + periodSize) % periods.size] = value
        periodSize++
    }

    private fun medianPeriod(): Long {
        for (i in 0 until periodSize) {
            periodScratch[i] = periods[(periodHead + i) % periods.size]
        }
        java.util.Arrays.sort(periodScratch, 0, periodSize)
        return periodScratch[periodSize / 2]
    }

    companion object {
        const val STATE_UNAVAILABLE = "unavailable"
        const val STATE_PENDING = "pending"
        const val STATE_SOURCE_TIME = "source_time"

        const val REASON_NONE = "none"
        const val PHASE_WARMUP = "warmup"
        const val REASON_STALE_FRAME = "stale_frame"
        const val REASON_DUPLICATE_TIMESTAMP = "duplicate_timestamp"
        const val REASON_SOURCE_TIMESTAMP_MISSING = "source_timestamp_missing"
        const val REASON_SOURCE_DISCONTINUITY = "source_discontinuity"
        const val REASON_CLOCK_MAPPING_UNSTABLE = "clock_mapping_unstable"

        private const val U32 = 0xFFFF_FFFFL

        /** 1 above [bound], -1 below -[bound], 0 within it. */
        private fun directionBeyond(value: Long, bound: Long): Int = if (value > bound) 1 else if (value < -bound) -1 else 0

        /** Consecutive frames in [direction]: 0 when none, restarting at 1 when it changes. */
        private fun confirmed(direction: Int, previous: Int, frames: Long): Long =
            if (direction == 0) 0 else if (direction == previous) frames + 1 else 1
        private const val HALF_RANGE = 0x8000_0000L
        private const val MAX_PERIODS = 31
        private const val MAX_US = Long.MAX_VALUE / 1000
    }
}
