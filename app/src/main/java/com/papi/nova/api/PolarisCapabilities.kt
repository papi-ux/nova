package com.papi.nova.api

data class PolarisCapabilities(
    val server: String,
    val version: String,
    val features: Features,
    val capture: CaptureInfo,
    val hostPower: HostPower = HostPower()
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
        val resolvedProfileProvenance: Boolean = false,
        val expectedTopologyAssertion: Boolean = false,
        val encoderBackendSelection: Boolean = false,
        val lockScreenControl: Boolean = false,
        val cursorVisibilityControl: Boolean = false,
        val liveMediaTelemetry: Boolean = false,
        val doctorV2Shadow: Boolean = false,
        val doctorV2ShadowEnabled: Boolean = false,
        val doctorTrials: Boolean = false,
        val doctorTrialsEnabled: Boolean = false,
        val hostSleep: Boolean = false
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
            aiOptimizer = aiOptimizer,
            aiAutoQuality = aiOptimizer,
            aiAutoQualityControl = aiOptimizerControl,
            aiOptimizerControl = aiOptimizerControl,
            adaptiveBitrateControl = adaptiveBitrateControl,
            gameLibrary = gameLibrary,
            sessionLifecycle = sessionLifecycle,
            deviceProfiles = deviceProfiles,
            lockScreenControl = lockScreenControl,
            cursorVisibilityControl = cursorVisibilityControl
        )
    }

    /**
     * Whether this host will let this client put it to sleep, and why not when
     * it will not. All three of supported, enabled and permitted have to be
     * true before a sleep control is worth showing: they are the host's own
     * answer, its owner's opt in, and whether this client may control the host
     * rather than only watch it.
     */
    data class HostPower(
        val sleepSupported: Boolean = false,
        val sleepEnabled: Boolean = false,
        val sleepPermitted: Boolean = false,
        val sleepBlockedReason: String = "",
        val sleepBlockedMessage: String = "",
        val sleepEndpoint: String = "",
        // What became of the last request, which is a different fact from
        // whether the host accepted it: a suspend can be aborted after logind
        // has said yes, and then the host is still awake.
        val lastSleepOutcome: String = "",
        val lastSleepReason: String = "",
        val lastSleepMessage: String = "",
        val lastSleepAt: Long = 0L
    )

    data class CaptureInfo(
        val backend: String = "",
        val compositor: String = "",
        val maxResolution: String = "",
        val maxFps: Int = 0,
        val codecs: List<String> = emptyList()
    )
}
