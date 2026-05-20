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
    val packetLossPct: Double
)
