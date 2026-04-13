package com.papi.nova.api

data class PolarisSessionStatus(
    val state: String,
    val game: String = "",
    val cagePid: Int = 0,
    val screenLocked: Boolean = false,
    val capture: CaptureStatus = CaptureStatus(),
    val encoder: EncoderStatus = EncoderStatus()
) {
    data class CaptureStatus(
        val backend: String = "",
        val resolution: String = ""
    )

    data class EncoderStatus(
        val codec: String = "",
        val bitrateKbps: Int = 0,
        val fps: Double = 0.0
    )

    val isStreaming get() = state == "streaming"
    val isSessionAlive get() = state in listOf("initializing", "cage_starting", "game_launching", "streaming")
}
