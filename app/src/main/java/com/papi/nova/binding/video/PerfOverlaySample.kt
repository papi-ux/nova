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
)
