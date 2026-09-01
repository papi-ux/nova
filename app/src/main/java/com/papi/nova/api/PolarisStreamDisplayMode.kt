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

    fun labelForMode(mode: String?): String =
        PolarisClientSettings.labelForMode(normalize(mode))

    fun isVirtual(mode: String?): Boolean = normalize(mode) == PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY

    fun isPrivateFamily(mode: String?): Boolean = normalize(mode) in setOf(
        PolarisClientSettings.MODE_HEADLESS_STREAM,
        PolarisClientSettings.MODE_DESKTOP_DISPLAY,
        PolarisClientSettings.MODE_GPU_NATIVE_TEST
    )

    fun preflightModeForLaunch(
        usesVirtualDisplay: Boolean,
        settings: PolarisClientSettings?,
        resolvedMode: String = "",
    ): String {
        if (usesVirtualDisplay) return PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY

        // A launch that resolved to a concrete canonical mode pushes exactly that mode.
        // The boolean pair above and the family fallbacks below cannot express the
        // registry ids beyond the classic pair (gamescope_stream, headless_dongle), so
        // those selections silently collapsed to the private-family default before.
        val resolved = normalize(resolvedMode)
        if (resolved.isNotEmpty()) return resolved

        val desired = normalize(settings?.desired?.streamDisplayMode)
        if (isPrivateFamily(desired)) return desired

        val effective = normalize(settings?.effective?.streamDisplayMode)
        if (isPrivateFamily(effective)) return effective

        return PolarisClientSettings.MODE_HEADLESS_STREAM
    }
}
