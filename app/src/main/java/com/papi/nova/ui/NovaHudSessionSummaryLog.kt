package com.papi.nova.ui

import com.google.gson.Gson

internal object NovaHudSessionSummaryLog {
    private val allowedKeys = listOf(
        "contract",
        "observational",
        "avg_fps",
        "target_fps",
        "low_1_percent_fps",
        "min_fps",
        "frame_pacing_bad_pct",
        "avg_latency_ms",
        "avg_bitrate_kbps",
        "packet_loss_pct",
        "codec",
        "duration_s",
        "samples",
        "monotonic_timestamp_ms",
        "frames_expected",
        "frames_received",
        "frames_rendered",
        "frames_lost",
        "received_fps",
        "rendered_fps",
        "decode_latency_ms",
        "host_processing_latency_ms",
        "session_generation"
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

internal object NovaHudDiagnosticReport {
    fun format(summary: Map<String, Any?>): String {
        val lines = mutableListOf<String>()
        lines += "Nova stream evidence"
        lines += "Observed: ${formatFps(summary["avg_fps"])} / target ${formatFps(summary["target_fps"])}"
        lines += "Video: ${formatBitrate(summary["avg_bitrate_kbps"])} / ${safeString(summary["codec"], "unknown codec")}"
        lines += "Network: ${formatMs(summary["avg_latency_ms"])} RTT / ${formatPercent(summary["packet_loss_pct"])} loss"
        lines += "Counters: ${safeString(summary["frames_received"], "--")} received / " +
            "${safeString(summary["frames_rendered"], "--")} rendered / ${safeString(summary["frames_lost"], "--")} lost"
        lines += "Observational only: no launch setting or action is derived by Nova"
        return lines.joinToString("\n")
    }

    private fun safeString(value: Any?, fallback: String): String {
        return value?.toString()?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun formatFps(value: Any?): String {
        val fps = (value as? Number)?.toDouble() ?: return "-- FPS"
        return "${formatNumber(fps)} FPS"
    }

    private fun formatBitrate(value: Any?): String {
        val kbps = (value as? Number)?.toInt() ?: return "-- Mbps"
        if (kbps <= 0) return "-- Mbps"
        return "${formatNumber(kbps / 1000.0)} Mbps"
    }

    private fun formatMs(value: Any?): String {
        val ms = (value as? Number)?.toDouble() ?: return "-- ms"
        return "${formatNumber(ms)} ms"
    }

    private fun formatPercent(value: Any?): String {
        val pct = (value as? Number)?.toDouble() ?: return "--%"
        val formatted = when {
            kotlin.math.abs(pct - pct.toInt()) < 0.01 -> pct.toInt().toString()
            kotlin.math.abs((pct * 10.0) - kotlin.math.round(pct * 10.0)) < 0.01 ->
                String.format(java.util.Locale.US, "%.1f", pct)
            else -> String.format(java.util.Locale.US, "%.2f", pct)
                .trimEnd('0')
                .trimEnd('.')
        }
        return "$formatted%"
    }

    private fun formatNumber(value: Double): String {
        return if (kotlin.math.abs(value - value.toInt()) < 0.01) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
    }
}
