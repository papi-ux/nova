package com.papi.nova.diagnostics

import com.papi.nova.binding.video.PerfOverlaySample

/**
 * After-stream recap: what this device measured of one stream connection, frozen once when that
 * connection ends, for the Library's optional Last Stream card.
 *
 * It describes a single client connection (one Game instance), not the host application's
 * session, which can outlive it (Nova left the screen, a reconnect replaced it, or a watcher
 * left). Every reading comes from accepted client samples, independent of the HUD. Nothing here
 * is a health grade, a regression verdict, Doctor authority or a tuning input, and nothing is
 * stored, logged, exported or shared.
 */
object NovaStreamRecap {
    /** Why this client connection ended, as its call site knows it. Descriptive only. */
    enum class End {
        /** The connection ended normally. Says nothing about whether the host app quit. */
        CONNECTION_ENDED,
        /** The host reported a graceful end. Not proof the host app quit. */
        HOST_END_REPORTED,
        /** The user asked to end the stream; whether the host acknowledged it isn't known here. */
        END_REQUESTED,
        /** Nova left the stream screen; the host session may still be running. */
        LEFT_STREAM,
        /** The connection ended with an error code. */
        ERROR,
        /** The video surface went away while connected. */
        SURFACE_LOST,
        /** A reconnect was requested, which replaces this connection. Not proof media recovered. */
        RECONNECT_REQUESTED,
        UNKNOWN,
    }

    /** Fixed at a safe launch point, before anything is torn down. */
    data class Launch(
        val role: NovaMomentReport.StreamRole,
        val requested: NovaMomentReport.Requested,
        /** This connection was launched as a requested reconnect (manual or automatic). */
        val reconnectRequested: Boolean,
        /** The requested host (PC entry) identity, in memory only, for matching a Library. */
        val requestedHost: String?,
    )

    /** One metric over the samples where it was valid. [count] is its own denominator. */
    data class Stat(val count: Int, val mean: Double, val min: Double, val max: Double)

    /** The newest accepted client sample before the end, validated like every other. */
    data class LastSample(
        val ageAtEndMs: Long,
        val codec: String,
        val width: Int?,
        val height: Int?,
        val renderedFps: Double?,
        val receivedFps: Double?,
        val decodeTimeMs: Double?,
        val controlRttMs: Double?,
    )

    /** Video frames missing between accepted samples: deltas of cumulative decoder counters. */
    data class Loss(val intervals: Int, val framesExpected: Long, val framesLost: Long) {
        val pct: Double get() = framesLost.toDouble() / framesExpected.toDouble() * 100.0
    }

    data class Recap(
        /** The process-local generation of the Game instance that measured it. */
        val generation: Long,
        val endedAtUtc: String,
        val end: End,
        val errorCode: Int?,
        val launch: Launch,
        /** From connectionStarted to the end, on elapsedRealtime. Null if the end preceded it. */
        val connectedForMs: Long?,
        val acceptedSamples: Int,
        val rejectedSamples: Int,
        /** First to last accepted sample time. Null with fewer than two. Not the connection length. */
        val samplesCoveredMs: Long?,
        val renderedFps: Stat?,
        val receivedFps: Stat?,
        val decodeTimeMs: Stat?,
        val hostProcessingLatencyMs: Stat?,
        val controlRttMs: Stat?,
        /** Loss summed over valid intervals only; null when no interval qualified. */
        val lossBetweenSamples: Loss?,
        /**
         * The last sample's cumulative counters for the whole connection, only when every accepted
         * sample kept one positive generation and non-decreasing counters. Null otherwise.
         */
        val connectionLoss: Loss?,
        /** Codec families seen across accepted samples; more than one means the aggregates mix them. */
        val codecs: List<String>,
        /** The decoded dimensions changed during the connection. */
        val dimensionsChanged: Boolean,
        val last: LastSample?,
    ) {
        val mediaSampled: Boolean get() = acceptedSamples > 0
    }

    /**
     * Constant memory: running counts, sums and extremes, the first and last accepted sample, and
     * the counter and generation continuity. Main thread only. [finish] freezes it once.
     */
    class Accumulator(private val generation: Long) {
        private var launch: Launch? = null
        private var connectedAt = 0L
        private var finished = false
        private var accepted = 0
        private var rejected = 0
        private var firstAt = 0L
        private var lastAt = 0L
        private var lastSample: PerfOverlaySample? = null
        private val rendered = Running()
        private val received = Running()
        private val decode = Running()
        private val hostLatency = Running()
        private val rtt = Running()
        private var lossIntervals = 0
        private var lossExpected = 0L
        private var lossLost = 0L
        private var continuous = true
        private var connectionGeneration = 0L
        private var firstGeneration = 0L
        private val codecs = LinkedHashSet<String>()
        private var dimensions: Pair<Int, Int>? = null
        private var dimensionsChanged = false

        /** At a safe launch point, while the requested settings and role are still the launch's. */
        fun launch(value: Launch) {
            if (!finished && launch == null) launch = value
        }

        /** connectionStarted, on elapsedRealtime. Only the first counts. */
        fun connected(elapsedNow: Long) {
            if (!finished && connectedAt == 0L && elapsedNow > 0L) connectedAt = elapsedNow
        }

        /**
         * One delivered client sample. A sample whose time isn't positive, is in the future, or
         * doesn't advance past the last accepted one is rejected whole and breaks no interval.
         */
        fun accept(sample: PerfOverlaySample, elapsedNow: Long) {
            if (finished) return
            val at = sample.monotonicTimestampMs
            if (at <= 0L || at > elapsedNow || (lastAt != 0L && at <= lastAt)) {
                rejected++
                return
            }
            val previous = lastSample
            accepted++
            if (firstAt == 0L) firstAt = at
            lastAt = at
            lastSample = sample

            val renderedFps = fps(sample.renderedFps)
            renderedFps?.let(rendered::add)
            fps(sample.incomingFps)?.let(received::add)
            // A decode time means something only for a window that rendered frames.
            if (renderedFps != null && renderedFps > 0.0) decodeTime(sample.decodeTimeMs)?.let(decode::add)
            sample.hostProcessingLatencyMs?.let { latency(it) }?.let(hostLatency::add)
            controlRtt(sample.rttMs)?.let(rtt::add)

            codecs += NovaMomentReport.codecFamily(sample.codec)
            val size = dimensions(sample)
            if (size != null) {
                if (dimensions != null && dimensions != size) dimensionsChanged = true
                dimensions = size
            }
            counters(previous, sample)
        }

        private fun counters(previous: PerfOverlaySample?, sample: PerfOverlaySample) {
            val generation = sample.sessionGeneration
            val countersValid = sample.framesExpected >= 0 && sample.framesLost >= 0 && sample.framesLost <= sample.framesExpected
            if (generation <= 0L || !countersValid) {
                continuous = false
                return
            }
            if (previous == null) {
                connectionGeneration = generation
                firstGeneration = generation
                return
            }
            if (generation != connectionGeneration || previous.sessionGeneration != generation) {
                // A new decoder generation restarts the counters: no interval, no whole-connection ratio.
                continuous = false
                connectionGeneration = generation
                return
            }
            val deltaExpected = sample.framesExpected - previous.framesExpected
            val deltaLost = sample.framesLost - previous.framesLost
            if (deltaExpected < 0 || deltaLost < 0) {
                continuous = false
                return
            }
            if (deltaExpected > 0 && deltaLost <= deltaExpected) {
                lossIntervals++
                lossExpected += deltaExpected
                lossLost += deltaLost
            }
        }

        /**
         * The recap, once: null if the connection never started or it was already finished. Reads
         * only what was accumulated; nothing from the stream after its teardown.
         */
        fun finish(end: End, errorCode: Int?, endElapsed: Long, endedAtUtc: String): Recap? {
            if (finished) return null
            finished = true
            val launched = launch ?: return null
            if (connectedAt == 0L) return null
            val last = lastSample
            // The renderer's counters start with the connection at generation 1 and restart with
            // each later generation, so only an unbroken generation-1 run covers the whole of it.
            val connectionLoss = last?.takeIf {
                continuous && firstGeneration == FIRST_GENERATION && it.sessionGeneration == FIRST_GENERATION &&
                    it.framesExpected > 0L &&
                    it.framesLost in 0..it.framesExpected
            }?.let { Loss(intervals = 0, framesExpected = it.framesExpected, framesLost = it.framesLost) }
            return Recap(
                generation = generation,
                endedAtUtc = endedAtUtc,
                end = end,
                errorCode = errorCode.takeIf { end == End.ERROR },
                launch = launched,
                connectedForMs = (endElapsed - connectedAt).takeIf { it >= 0L },
                acceptedSamples = accepted,
                rejectedSamples = rejected,
                samplesCoveredMs = (lastAt - firstAt).takeIf { accepted >= 2 },
                renderedFps = rendered.stat(),
                receivedFps = received.stat(),
                decodeTimeMs = decode.stat(),
                hostProcessingLatencyMs = hostLatency.stat(),
                controlRttMs = rtt.stat(),
                lossBetweenSamples = Loss(lossIntervals, lossExpected, lossLost).takeIf { lossIntervals > 0 && lossExpected > 0 },
                connectionLoss = connectionLoss,
                codecs = codecs.toList(),
                dimensionsChanged = dimensionsChanged,
                last = last?.let {
                    LastSample(
                        ageAtEndMs = (endElapsed - it.monotonicTimestampMs).coerceAtLeast(0L),
                        codec = NovaMomentReport.codecFamily(it.codec),
                        width = it.width.takeIf { w -> w in 1..16_384 },
                        height = it.height.takeIf { h -> h in 1..16_384 },
                        renderedFps = fps(it.renderedFps),
                        receivedFps = fps(it.incomingFps),
                        decodeTimeMs = if ((fps(it.renderedFps) ?: 0.0) > 0.0) decodeTime(it.decodeTimeMs) else null,
                        controlRttMs = controlRtt(it.rttMs),
                    )
                },
            )
        }
    }

    /** The renderer's sessionGeneration for a connection whose counters never restarted. */
    const val FIRST_GENERATION = 1L

    private class Running {
        var count = 0
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY

        fun add(value: Double) {
            count++
            sum += value
            if (value < min) min = value
            if (value > max) max = value
        }

        fun stat(): Stat? = if (count == 0) null else Stat(count, sum / count, min, max)
    }

    /** A sampled frame rate, including a genuine 0 (static or paused content). */
    internal fun fps(value: Double): Double? = value.takeIf { it.isFinite() && it >= 0.0 && it <= 1000.0 }

    internal fun decodeTime(value: Double): Double? = value.takeIf { it.isFinite() && it >= 0.0 && it <= 10_000.0 }

    internal fun latency(value: Double): Double? = value.takeIf { it.isFinite() && it >= 0.0 && it <= 10_000.0 }

    /** MoonBridge reports 0 before it has an estimate: unknown, not 0 ms. */
    internal fun controlRtt(value: Int): Double? = value.takeIf { it in 1..60_000 }?.toDouble()

    private fun dimensions(sample: PerfOverlaySample): Pair<Int, Int>? =
        if (sample.width in 1..16_384 && sample.height in 1..16_384) sample.width to sample.height else null
}
