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

    private var capabilities: PolarisCapabilities? = null

    /** True if the connected server is a Polaris server */
    val isPolarisServer: Boolean get() = capabilities != null

    /** Server version string (e.g., "1.0.0") */
    val serverVersion: String get() = capabilities?.version ?: ""

    // Feature flags — all false when connected to non-Polaris server
    val hasAiOptimizer: Boolean get() = capabilities?.features?.aiOptimizer == true
    val hasGameLibrary: Boolean get() = capabilities?.features?.gameLibrary == true
    val hasSessionLifecycle: Boolean get() = capabilities?.features?.sessionLifecycle == true
    val hasDeviceProfiles: Boolean get() = capabilities?.features?.deviceProfiles == true
    val hasLockScreenControl: Boolean get() = capabilities?.features?.lockScreenControl == true
    val hasCursorVisibilityControl: Boolean get() = capabilities?.features?.cursorVisibilityControl == true

    // Capture info
    val captureBackend: String get() = capabilities?.capture?.backend ?: ""
    val supportedCodecs: List<String> get() = capabilities?.capture?.codecs ?: emptyList()

    /**
     * Probe the server for Polaris capabilities.
     * Call this after the standard Moonlight NVHTTP handshake succeeds.
     * Safe to call from any thread (OkHttp handles threading).
     */
    fun probe(client: PolarisApiClient) {
        capabilities = client.getCapabilities()

        if (isPolarisServer) {
            LimeLog.info("Nova: Polaris server detected — v$serverVersion")
            LimeLog.info("Nova: Features: AI=${hasAiOptimizer} GameLib=${hasGameLibrary} " +
                "Session=${hasSessionLifecycle} Devices=${hasDeviceProfiles} Lock=${hasLockScreenControl} " +
                "Cursor=${hasCursorVisibilityControl}")
            LimeLog.info("Nova: Capture: ${captureBackend}, codecs: ${supportedCodecs}")
        } else {
            LimeLog.info("Nova: Standard Sunshine/Apollo server (no Polaris features)")
        }
    }

    /** Reset state when disconnecting from a server */
    fun reset() {
        capabilities = null
    }
}
