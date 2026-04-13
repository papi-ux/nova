package com.papi.nova.preferences

/**
 * Streaming quality presets — quick-switch between resolution+bitrate+codec+FPS combos.
 * Inspired by Steam Link's Fast/Balanced/Beautiful and GeForce NOW's mode system.
 */
data class StreamPreset(
    val key: String,
    val label: String,
    val resolution: String,    // e.g. "1280x720"
    val fps: String,           // e.g. "60"
    val bitrateKbps: Int,      // e.g. 20000
    val codec: String          // e.g. "auto", "forceh265", "neverh265"
) {
    companion object {
        val PERFORMANCE = StreamPreset(
            key = "performance",
            label = "Performance",
            resolution = "1280x720",
            fps = "60",
            bitrateKbps = 10000,
            codec = "auto"
        )

        val BALANCED = StreamPreset(
            key = "balanced",
            label = "Balanced",
            resolution = "1920x1080",
            fps = "60",
            bitrateKbps = 20000,
            codec = "auto"
        )

        val QUALITY = StreamPreset(
            key = "quality",
            label = "Quality",
            resolution = "1920x1080",
            fps = "60",
            bitrateKbps = 50000,
            codec = "forceh265"
        )

        val PRESETS = listOf(PERFORMANCE, BALANCED, QUALITY)

        fun fromKey(key: String): StreamPreset? = PRESETS.find { it.key == key }
    }
}
