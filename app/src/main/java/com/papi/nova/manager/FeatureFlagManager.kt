package com.papi.nova.manager

import com.papi.nova.LimeLog
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisCapabilities

/**
 * Probes the server for Polaris capabilities and gates features accordingly.
 * When connected to a stock Sunshine/Apollo server, all Polaris features are disabled
 * and Nova behaves identically to Artemis/Moonlight.
 */
object FeatureFlagManager {

    @Volatile
    private var capabilities: PolarisCapabilities? = null
    @Volatile
    private var activeScope: Long = 0L
    private var scopeSequence: Long = 0L

    /** True if the connected server is a Polaris server */
    val isPolarisServer: Boolean get() = capabilities != null

    /** Server version string (e.g., "1.0.0") */
    val serverVersion: String get() = capabilities?.version ?: ""

    // Feature flags — all false when connected to non-Polaris server
    val hasAiOptimizer: Boolean get() = capabilities?.features?.aiOptimizer == true
    val hasAiOptimizerControl: Boolean get() = capabilities?.features?.aiOptimizerControl == true
    val hasAdaptiveBitrateControl: Boolean get() = capabilities?.features?.adaptiveBitrateControl == true
    val hasGameLibrary: Boolean get() = capabilities?.features?.gameLibrary == true
    val hasSessionLifecycle: Boolean get() = capabilities?.features?.sessionLifecycle == true
    val hasDeviceProfiles: Boolean get() = capabilities?.features?.deviceProfiles == true
    val hasStreamPolicy: Boolean get() = capabilities?.features?.streamPolicy == true
    val hasClientSettings: Boolean get() = capabilities?.features?.clientSettings == true
    val hasOptimizerSync: Boolean get() = capabilities?.features?.optimizerSync == true
    val hasResolvedProfileProvenance: Boolean get() = capabilities?.features?.resolvedProfileProvenance == true
    val hasExpectedTopologyAssertion: Boolean get() = capabilities?.features?.expectedTopologyAssertion == true
    val hasLockScreenControl: Boolean get() = capabilities?.features?.lockScreenControl == true
    val hasCursorVisibilityControl: Boolean get() = capabilities?.features?.cursorVisibilityControl == true
    val hasDoctorV2Shadow: Boolean get() = capabilities?.features?.doctorV2Shadow == true
    val isDoctorV2ShadowEnabled: Boolean get() = capabilities?.features?.doctorV2ShadowEnabled == true
    val hasDoctorTrials: Boolean get() = capabilities?.features?.doctorTrials == true
    val areDoctorTrialsEnabled: Boolean get() = capabilities?.features?.doctorTrialsEnabled == true

    // Capture info
    val captureBackend: String get() = capabilities?.capture?.backend ?: ""
    val supportedCodecs: List<String> get() = capabilities?.capture?.codecs ?: emptyList()

    /**
	 * Probe the server for Polaris capabilities.
	 * Call this after the standard Moonlight NVHTTP handshake succeeds.
	 * Performs network I/O; call from a background thread.
	 */
    fun probe(client: PolarisApiClient, scope: Long = activeScope): Boolean {
        var discovered = client.getCapabilities()
        if (discovered == null) {
            discovered = client.getSessionStatus()?.let {
                LimeLog.info("Nova: Polaris session API detected; using session-status feature fallback")
                PolarisCapabilities(
                    server = "polaris",
                    version = "",
                    features = PolarisCapabilities.Features(
                        aiOptimizer = it.aiOptimizerEnabled || it.tuning.aiOptimizerEnabled,
                        aiAutoQuality = it.aiAutoQualityEnabled || it.tuning.aiAutoQualityEnabled ||
                            it.aiOptimizerEnabled || it.adaptiveBitrateEnabled,
                        aiAutoQualityControl = true,
                        aiOptimizerControl = true,
                        adaptiveBitrateControl = true,
                        sessionLifecycle = true,
                        streamPolicy = true,
                        clientSettings = true,
                        optimizerSync = true,
                        cursorVisibilityControl = true,
                        lockScreenControl = true
                    ),
                    capture = PolarisCapabilities.CaptureInfo(
                        backend = it.capture.backend,
                        compositor = it.displayMode.selection,
                        codecs = listOfNotNull(it.encoder.codec.takeIf { codec -> codec.isNotBlank() })
                    )
                )
            }
        }

        if (!publishForScope(scope, discovered)) {
            LimeLog.info("Nova: Discarded capabilities from an obsolete Game scope")
            return false
        }

        if (discovered != null) {
            val features = discovered.features
            LimeLog.info("Nova: Polaris server detected — v${discovered.version}")
            LimeLog.info("Nova: Features: AI=${features.aiOptimizer} GameLib=${features.gameLibrary} " +
                "AIControl=${features.aiOptimizerControl} Adaptive=${features.adaptiveBitrateControl} " +
                "Session=${features.sessionLifecycle} Devices=${features.deviceProfiles} Lock=${features.lockScreenControl} " +
                "Cursor=${features.cursorVisibilityControl} Sync=${features.optimizerSync} " +
                "ResolvedProfile=${features.resolvedProfileProvenance} " +
                "TopologyAssertion=${features.expectedTopologyAssertion} " +
                "DoctorV2=${features.doctorV2Shadow}/${features.doctorV2ShadowEnabled} " +
                "Trials=${features.doctorTrials}/${features.doctorTrialsEnabled}")
            LimeLog.info("Nova: Capture: ${discovered.capture.backend}, codecs: ${discovered.capture.codecs}")
        } else {
            LimeLog.info("Nova: Standard Sunshine/Apollo server (no Polaris features)")
        }
        return true
    }

    @Synchronized
    fun beginScope(): Long {
        activeScope = ++scopeSequence
        capabilities = null
        return activeScope
    }

    fun capabilitiesForScope(scope: Long): PolarisCapabilities? = synchronized(this) {
        capabilities.takeIf { scope != 0L && scope == activeScope }
    }

    @Synchronized
    internal fun publishForScope(scope: Long, value: PolarisCapabilities?): Boolean {
        if (scope == 0L || scope != activeScope) return false
        capabilities = value
        return true
    }

    /** Reset state when disconnecting from a specific Game instance. */
    @Synchronized
    fun reset(scope: Long) {
        if (scope == 0L || scope != activeScope) return
        activeScope = ++scopeSequence
        capabilities = null
    }

    /** Unscoped reset retained for application-level cleanup and tests. */
    @Synchronized
    fun reset() {
        activeScope = ++scopeSequence
        capabilities = null
    }
}
