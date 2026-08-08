package com.papi.nova.api

data class PolarisClientSettings(
    val version: Int = 1,
    val revision: String = "",
    val desired: Desired = Desired(),
    val effective: Effective = Effective(),
    val capabilities: Capabilities = Capabilities(),
    val relaunchRequired: Boolean = false
) {
    data class Desired(
        val streamDisplayMode: String = "",
        val streamDisplayModeLabel: String = "",
        val streamDisplayModeReason: String = "",
        val displayMode: String = "",
        val targetBitrateKbps: Int = 0,
        val aiAutoQualityEnabled: Boolean = false,
        val adaptiveBitrateEnabled: Boolean = false,
        val aiOptimizerEnabled: Boolean = false,
        val disconnectResumeTimeoutSeconds: Int = 300
    )

    data class Effective(
        val streamDisplayMode: String = "",
        val streamDisplayModeLabel: String = "",
        val streamDisplayModeReason: String = "",
        val displayMode: String = "",
        val targetBitrateKbps: Int = 0,
        val aiAutoQualityEnabled: Boolean = false,
        val adaptiveBitrateEnabled: Boolean = false,
        val adaptiveTargetBitrateKbps: Int = 0,
        val aiOptimizerEnabled: Boolean = false,
        val disconnectResumeTimeoutSeconds: Int = 300,
        val capturePath: String = "",
        val captureGpuNative: Boolean = false
    )

    data class Capabilities(
        val modes: List<ModeOption> = emptyList(),
        val displayModeOverride: Boolean = false,
        val targetBitrateOverride: Boolean = false,
        val aiAutoQualityControl: Boolean = false,
        val adaptiveBitrateControl: Boolean = false,
        val aiOptimizerControl: Boolean = false,
        val disconnectResumeTimeoutControl: Boolean = false
    )

    data class ModeOption(
        val value: String = "",
        val label: String = "",
        val available: Boolean = true,
        val restartRequired: Boolean = true,
        val reason: String = "",
        /** Registry grouping: "private" (desktop untouched) or "host" (uses/swaps the host screen). */
        val group: String = "",
        /** Host-supplied explanation served when available is false. */
        val unavailableReason: String = ""
    )

    val desiredModeLabel: String
        get() = desired.streamDisplayModeLabel.ifBlank { labelForMode(desired.streamDisplayMode) }

    val effectiveModeLabel: String
        get() = effective.streamDisplayModeLabel.ifBlank { labelForMode(effective.streamDisplayMode) }

    companion object {
        const val MODE_HEADLESS_STREAM = "headless_stream"
        const val MODE_DESKTOP_DISPLAY = "desktop_display"
        const val MODE_HOST_VIRTUAL_DISPLAY = "host_virtual_display"
        const val MODE_GPU_NATIVE_TEST = "windowed_stream"
        const val MODE_GAMESCOPE_STREAM = "gamescope_stream"
        const val MODE_HEADLESS_DONGLE = "headless_dongle"

        @JvmStatic
        fun labelForMode(mode: String): String = when (mode) {
            MODE_HEADLESS_STREAM -> "Private Stream"
            MODE_HOST_VIRTUAL_DISPLAY -> "Host Virtual Display"
            MODE_GPU_NATIVE_TEST -> "Private Stream (GPU-native)"
            MODE_DESKTOP_DISPLAY -> "Mirror Desktop"
            MODE_GAMESCOPE_STREAM -> "Gamescope Stream"
            MODE_HEADLESS_DONGLE -> "Headless Dongle"
            "headless" -> "Private Stream"
            "virtual_display" -> "Host Virtual Display"
            "desktop_display", "host_display" -> "Mirror Desktop"
            "windowed_stream", "gpu_native", "gpu-native" -> "Private Stream (GPU-native)"
            else -> ""
        }
    }
}
