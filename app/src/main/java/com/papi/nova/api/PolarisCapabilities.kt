package com.papi.nova.api

data class PolarisCapabilities(
    val server: String,
    val version: String,
    val features: Features,
    val capture: CaptureInfo
) {
    data class Features(
        val aiOptimizer: Boolean = false,
        val aiAutoQuality: Boolean = false,
        val aiAutoQualityControl: Boolean = false,
        val aiOptimizerControl: Boolean = false,
        val adaptiveBitrateControl: Boolean = false,
        val gameLibrary: Boolean = false,
        val sessionLifecycle: Boolean = false,
        val deviceProfiles: Boolean = false,
        val streamPolicy: Boolean = false,
        val clientSettings: Boolean = false,
        val optimizerSync: Boolean = false,
        val lockScreenControl: Boolean = false,
        val cursorVisibilityControl: Boolean = false
    ) {
        constructor(
            aiOptimizer: Boolean,
            aiOptimizerControl: Boolean,
            adaptiveBitrateControl: Boolean,
            gameLibrary: Boolean,
            sessionLifecycle: Boolean,
            deviceProfiles: Boolean,
            lockScreenControl: Boolean,
            cursorVisibilityControl: Boolean
        ) : this(
            aiOptimizer,
            aiOptimizer,
            aiOptimizerControl,
            aiOptimizerControl,
            adaptiveBitrateControl,
            gameLibrary,
            sessionLifecycle,
            deviceProfiles,
            false,
            false,
            false,
            lockScreenControl,
            cursorVisibilityControl
        )
    }

    data class CaptureInfo(
        val backend: String = "",
        val compositor: String = "",
        val maxResolution: String = "",
        val maxFps: Int = 0,
        val codecs: List<String> = emptyList()
    )
}
