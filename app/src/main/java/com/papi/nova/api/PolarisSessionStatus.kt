package com.papi.nova.api

data class PolarisSessionStatus(
    val state: String,
    val game: String = "",
    val gameId: Int = 0,
    val gameUuid: String = "",
    val sessionToken: String = "",
    val ownerUniqueId: String = "",
    val ownerDeviceName: String = "",
    val clientRole: String = "none",
    val viewerCount: Int = 0,
    val ownedByClient: Boolean = false,
    val cagePid: Int = 0,
    val screenLocked: Boolean = false,
    val cursorVisible: Boolean = false,
    val dynamicRange: Int = 0,
    val capture: CaptureStatus = CaptureStatus(),
    val encoder: EncoderStatus = EncoderStatus()
) {
    data class CaptureStatus(
        val backend: String = "",
        val resolution: String = "",
        val transport: String = "",
        val residency: String = "",
        val format: String = ""
    )

    data class EncoderStatus(
        val codec: String = "",
        val bitrateKbps: Int = 0,
        val fps: Double = 0.0,
        val requestedClientFps: Double = 0.0,
        val sessionTargetFps: Double = 0.0,
        val encodeTargetFps: Double = 0.0,
        val targetDevice: String = "",
        val targetResidency: String = "",
        val targetFormat: String = ""
    )

    val isStreaming get() = state == "streaming"
    val isSessionAlive get() = state in listOf("initializing", "cage_starting", "game_launching", "streaming")
    val isTenBitActive get() = dynamicRange > 0 || encoder.targetFormat.equals("p010", ignoreCase = true)
    val isGpuPath get() = encoder.targetResidency.equals("gpu", ignoreCase = true)
    val isViewer get() = clientRole.equals("viewer", ignoreCase = true)
}
