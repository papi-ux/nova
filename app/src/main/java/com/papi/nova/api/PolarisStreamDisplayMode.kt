package com.papi.nova.api

object PolarisStreamDisplayMode {
    val ORDER = listOf(
        PolarisClientSettings.MODE_HEADLESS_STREAM,
        PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
        PolarisClientSettings.MODE_DESKTOP_DISPLAY,
        PolarisClientSettings.MODE_GPU_NATIVE_TEST
    )

    fun normalize(mode: String?): String = when (mode?.trim()?.lowercase().orEmpty()) {
        "headless", "headless_stream", "private", "private_stream" -> PolarisClientSettings.MODE_HEADLESS_STREAM
        "virtual_display", "host_virtual_display" -> PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY
        "desktop", "desktop_display", "host_display" -> PolarisClientSettings.MODE_DESKTOP_DISPLAY
        "windowed", "windowed_stream", "gpu_native", "gpu-native", "gpu_native_test" -> PolarisClientSettings.MODE_GPU_NATIVE_TEST
        else -> mode?.trim().orEmpty()
    }

    fun labelForMode(mode: String?): String = when (normalize(mode)) {
        PolarisClientSettings.MODE_HEADLESS_STREAM -> "Private Stream"
        PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY -> "Host Virtual Display"
        PolarisClientSettings.MODE_DESKTOP_DISPLAY -> "Mirror Desktop"
        PolarisClientSettings.MODE_GPU_NATIVE_TEST -> "Private Stream (GPU-native)"
        else -> ""
    }

    fun isVirtual(mode: String?): Boolean = normalize(mode) == PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY

    fun isPrivateFamily(mode: String?): Boolean = normalize(mode) in setOf(
        PolarisClientSettings.MODE_HEADLESS_STREAM,
        PolarisClientSettings.MODE_DESKTOP_DISPLAY,
        PolarisClientSettings.MODE_GPU_NATIVE_TEST
    )

    fun preflightModeForLaunch(usesVirtualDisplay: Boolean, settings: PolarisClientSettings?): String {
        if (usesVirtualDisplay) return PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY

        val desired = normalize(settings?.desired?.streamDisplayMode)
        if (isPrivateFamily(desired)) return desired

        val effective = normalize(settings?.effective?.streamDisplayMode)
        if (isPrivateFamily(effective)) return effective

        return PolarisClientSettings.MODE_HEADLESS_STREAM
    }
}
