package com.papi.nova.binding.video

data class PerfOverlaySample(
    val fps: Double,
    val incomingFps: Double,
    val renderedFps: Double,
    val width: Int,
    val height: Int,
    val codec: String,
    val rttMs: Int,
    val rttVarianceMs: Int,
    val decodeTimeMs: Double,
    val packetLossPct: Double,
    val monotonicTimestampMs: Long = 0L,
    val framesExpected: Long = 0L,
    val framesReceived: Long = 0L,
    val framesRendered: Long = 0L,
    val framesLost: Long = 0L,
    val hostProcessingLatencyMs: Double? = null,
    val sessionGeneration: Long = 0L
) {
    /** The legacy rendered fields count accepted present requests for Android PyroWave. */
    val frameOutputSource: String
        get() = frameOutputSource(codec)

    val decodeLatencyAvailable: Boolean
        get() = measuredDecodeLatencyMs(codec, decodeTimeMs) != null

    /** Required numeric wire field: zero is an unknown placeholder when availability is false. */
    val wireDecodeLatencyMs: Double
        get() = measuredDecodeLatencyMs(codec, decodeTimeMs) ?: 0.0

    companion object {
        fun frameOutputSource(codec: String): String =
            if (codec.trim().equals("pyrowave", ignoreCase = true)) "present_request" else "renderer_output"

        // This renderer exposes no GPU-completion measurement, including in older saved samples
        // that mislabeled CPU API return time. Non-PyroWave measurements keep their existing meaning.
        fun measuredDecodeLatencyMs(codec: String, ms: Double): Double? =
            ms.takeIf { frameOutputSource(codec) != "present_request" && it.isFinite() && it >= 0.0 }
    }
}
