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
        val adaptiveBitrateControl: Boolean = false,
        val aiOptimizerControl: Boolean = false,
        val disconnectResumeTimeoutControl: Boolean = false
    )

    data class ModeOption(
        val value: String = "",
        val label: String = "",
        val available: Boolean = true,
        val restartRequired: Boolean = true,
        val reason: String = ""
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

        @JvmStatic
        fun labelForMode(mode: String): String = when (mode) {
            MODE_HEADLESS_STREAM -> "Headless Stream"
            MODE_HOST_VIRTUAL_DISPLAY -> "Host Virtual Display"
            MODE_GPU_NATIVE_TEST -> "GPU-Native Test"
            MODE_DESKTOP_DISPLAY -> "Desktop Display"
            "headless" -> "Headless Stream"
            "virtual_display" -> "Host Virtual Display"
            else -> ""
        }
    }
}
