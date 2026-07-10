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
        "relaunch_recommended",
        "diagnosis_classification",
        "diagnosis_likely_cause",
        "diagnosis_try_first",
        "diagnosis_confidence"
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
        lines += "Nova stream diagnostics"
        lines += "Observed: ${formatFps(summary["avg_fps"])} / target ${formatFps(summary["target_fps"])}"
        suggestedLine(summary)?.let { lines += it }
        lines += "Video: ${formatBitrate(summary["avg_bitrate_kbps"])} / ${safeString(summary["codec"], "unknown codec")}"
        lines += "Network: ${formatMs(summary["avg_latency_ms"])} RTT / ${formatPercent(summary["packet_loss_pct"])} loss"
        lines += "Health: ${safeString(summary["health_grade"], "unknown")} / ${safeString(summary["primary_issue"], "none")}"
        diagnosisLine(summary)?.let { lines += it }
        tryFirstLine(summary)?.let { lines += it }
        issuesLine(summary)?.let { lines += it }
        return lines.joinToString("\n")
    }

    private fun diagnosisLine(summary: Map<String, Any?>): String? {
        val classification = safeString(summary["diagnosis_classification"], "").takeIf { it.isNotBlank() }
            ?: return null
        val cause = safeString(summary["diagnosis_likely_cause"], "stream evidence available")
        val confidence = safeString(summary["diagnosis_confidence"], "").takeIf { it.isNotBlank() }
            ?.let { " ($it)" }
            ?: ""
        return "Diagnosis: $classification / $cause$confidence"
    }

    private fun tryFirstLine(summary: Map<String, Any?>): String? {
        val tryFirst = safeString(summary["diagnosis_try_first"], "").takeIf { it.isNotBlank() }
            ?: return null
        return "Try first: $tryFirst"
    }

    private fun suggestedLine(summary: Map<String, Any?>): String? {
        val relaunch = summary["relaunch_recommended"] as? Boolean ?: false
        val safeTarget = (summary["safe_target_fps"] as? Number)?.toDouble() ?: 0.0
        return when {
            relaunch && safeTarget > 0.0 -> "Suggested: relaunch at ${formatNumber(safeTarget)} FPS"
            safeTarget > 0.0 -> "Suggested: ${formatNumber(safeTarget)} FPS recovery profile"
            relaunch -> "Suggested: relaunch with recovery profile"
            else -> null
        }
    }

    private fun issuesLine(summary: Map<String, Any?>): String? {
        val issues = (summary["issues"] as? Iterable<*>)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return "Issues: ${issues.joinToString(", ")}"
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
