package com.papi.nova.ui

import com.google.gson.Gson

internal object NovaHudSessionSummaryLog {
    private val allowedKeys = listOf(
        "avg_fps",
        "target_fps",
        "low_1_percent_fps",
        "min_fps",
        "frame_pacing_bad_pct",
        "safe_target_fps",
        "avg_latency_ms",
        "avg_bitrate_kbps",
        "packet_loss_pct",
        "codec",
        "duration_s",
        "samples",
        "optimization_source",
        "optimization_confidence",
        "recommendation_version",
        "health_grade",
        "primary_issue",
        "issues",
        "decoder_risk",
        "hdr_risk",
        "network_risk",
        "capture_path",
        "safe_bitrate_kbps",
        "safe_codec",
        "safe_display_mode",
        "safe_hdr",
        "relaunch_recommended"
    )

    fun format(summary: Map<String, Any?>): String {
        val values = linkedMapOf<String, Any>()
        allowedKeys.forEach { key ->
            val value = summary[key] ?: return@forEach
            when (value) {
                is String -> if (value.isNotBlank()) values[key] = value
                is Number -> values[key] = value
                is Boolean -> values[key] = value
                is Iterable<*> -> values[key] = value.filterNotNull()
            }
        }
        return Gson().toJson(values)
    }
}
