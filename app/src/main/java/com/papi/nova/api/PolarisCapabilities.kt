package com.papi.nova.api

data class PolarisCapabilities(
    val server: String,
    val version: String,
    val features: Features,
    val capture: CaptureInfo
) {
    data class Features(
        val aiOptimizer: Boolean = false,
        val gameLibrary: Boolean = false,
        val sessionLifecycle: Boolean = false,
        val deviceProfiles: Boolean = false,
        val lockScreenControl: Boolean = false,
        val cursorVisibilityControl: Boolean = false
    )

    data class CaptureInfo(
        val backend: String = "",
        val compositor: String = "",
        val maxResolution: String = "",
        val maxFps: Int = 0,
        val codecs: List<String> = emptyList()
    )
}
