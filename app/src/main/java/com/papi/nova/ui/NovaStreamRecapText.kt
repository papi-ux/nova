package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import java.util.Locale

/**
 * The After-stream recap in words: a one-line card summary and the read-only page. Descriptive
 * only: every reading carries its own count and scope, unknown stays unknown, and nothing reads
 * as a grade, a verdict or a claim about the host, the scene or the network path.
 */
internal object NovaStreamRecapText {
    fun card(context: Context, recap: NovaStreamRecap.Recap): String {
        val reason = short(context, recap)
        return if (recap.mediaSampled) {
            context.getString(R.string.nova_recap_card_summary, recap.endedAtUtc, reason, recap.acceptedSamples)
        } else {
            context.getString(R.string.nova_recap_card_summary_no_samples, recap.endedAtUtc, reason)
        }
    }

    fun page(context: Context, recap: NovaStreamRecap.Recap): String = buildString {
        val unknown = context.getString(R.string.nova_recap_unknown)
        appendLine(context.getString(R.string.nova_recap_ended, recap.endedAtUtc, reason(context, recap)))
        if (recap.launch.reconnectRequested) appendLine(context.getString(R.string.nova_recap_reconnect_launch))
        appendLine(context.getString(R.string.nova_recap_requested, requested(recap.launch, unknown)))
        appendLine(recap.connectedForMs?.let { context.getString(R.string.nova_recap_connected_for, duration(it)) }
            ?: context.getString(R.string.nova_recap_connected_unknown))
        appendLine()
        if (!recap.mediaSampled) {
            appendLine(context.getString(R.string.nova_recap_no_samples))
        } else {
            val covered = recap.samplesCoveredMs
            appendLine(
                if (covered != null) context.getString(R.string.nova_recap_samples, recap.acceptedSamples, duration(covered))
                else context.getString(R.string.nova_recap_samples_one)
            )
        }
        if (recap.rejectedSamples > 0) appendLine(context.getString(R.string.nova_recap_rejected, recap.rejectedSamples))
        if (recap.mediaSampled) {
            appendLine()
            appendLine(context.getString(R.string.nova_recap_averages))
            stat(context, R.string.nova_recap_rendered_fps, recap.renderedFps, ::fps)
            stat(context, R.string.nova_recap_received_fps, recap.receivedFps, ::fps)
            stat(context, R.string.nova_recap_decode_time, recap.decodeTimeMs, ::ms)
            stat(context, R.string.nova_recap_host_processing, recap.hostProcessingLatencyMs, ::ms)
            stat(context, R.string.nova_recap_control_rtt, recap.controlRttMs, ::ms)
            appendLine(recap.lossBetweenSamples?.let {
                context.getString(R.string.nova_recap_loss_between, pct(it.pct), it.framesExpected, it.intervals)
            } ?: context.getString(R.string.nova_recap_loss_between_unavailable))
            appendLine(recap.connectionLoss?.let {
                context.getString(R.string.nova_recap_loss_connection, pct(it.pct), it.framesExpected)
            } ?: context.getString(R.string.nova_recap_loss_connection_unavailable))
            if (recap.codecs.size > 1) appendLine(context.getString(R.string.nova_recap_codecs_mixed, recap.codecs.joinToString(", ")))
            if (recap.dimensionsChanged) appendLine(context.getString(R.string.nova_recap_dimensions_changed))
            recap.last?.let { last ->
                appendLine()
                val age = last.ageAtEndMs
                appendLine(
                    if (age != null) context.getString(R.string.nova_recap_last, duration(age), lastSample(context, last, unknown))
                    else context.getString(R.string.nova_recap_last_age_unknown, lastSample(context, last, unknown))
                )
            }
        }
        appendLine()
        appendLine(context.getString(R.string.nova_recap_not_measured))
        append(context.getString(R.string.nova_recap_lifetime))
    }

    private fun StringBuilder.stat(context: Context, label: Int, stat: NovaStreamRecap.Stat?, format: (Double) -> String) {
        val name = context.getString(label)
        appendLine(
            if (stat == null) context.getString(R.string.nova_recap_stat_unavailable, name)
            else context.getString(R.string.nova_recap_stat, name, format(stat.mean), stat.count, format(stat.min), format(stat.max))
        )
    }

    private fun reason(context: Context, recap: NovaStreamRecap.Recap): String = when (recap.end) {
        NovaStreamRecap.End.CONNECTION_ENDED -> context.getString(R.string.nova_recap_end_connection_ended)
        NovaStreamRecap.End.HOST_END_REPORTED -> context.getString(R.string.nova_recap_end_host_end_reported)
        NovaStreamRecap.End.END_REQUESTED -> context.getString(R.string.nova_recap_end_end_requested)
        NovaStreamRecap.End.LEFT_STREAM -> context.getString(R.string.nova_recap_end_left_stream)
        NovaStreamRecap.End.ERROR -> context.getString(R.string.nova_recap_end_error, code(recap.errorCode))
        NovaStreamRecap.End.SURFACE_LOST -> context.getString(R.string.nova_recap_end_surface_lost)
        NovaStreamRecap.End.RECONNECT_REQUESTED -> context.getString(R.string.nova_recap_end_reconnect_requested)
        NovaStreamRecap.End.UNKNOWN -> context.getString(R.string.nova_recap_end_unknown)
    }

    private fun short(context: Context, recap: NovaStreamRecap.Recap): String = when (recap.end) {
        NovaStreamRecap.End.CONNECTION_ENDED -> context.getString(R.string.nova_recap_short_connection_ended)
        NovaStreamRecap.End.HOST_END_REPORTED -> context.getString(R.string.nova_recap_short_host_end_reported)
        NovaStreamRecap.End.END_REQUESTED -> context.getString(R.string.nova_recap_short_end_requested)
        NovaStreamRecap.End.LEFT_STREAM -> context.getString(R.string.nova_recap_short_left_stream)
        NovaStreamRecap.End.ERROR -> context.getString(R.string.nova_recap_short_error, code(recap.errorCode))
        NovaStreamRecap.End.SURFACE_LOST -> context.getString(R.string.nova_recap_short_surface_lost)
        NovaStreamRecap.End.RECONNECT_REQUESTED -> context.getString(R.string.nova_recap_short_reconnect_requested)
        NovaStreamRecap.End.UNKNOWN -> context.getString(R.string.nova_recap_short_unknown)
    }

    /** The same form the error dialog uses: large codes in hex. */
    private fun code(errorCode: Int?): String = when {
        errorCode == null -> "?"
        Math.abs(errorCode.toLong()) > 1000 -> Integer.toHexString(errorCode)
        else -> errorCode.toString()
    }

    private fun requested(launch: NovaStreamRecap.Launch, unknown: String): String {
        val r = launch.requested
        val size = if (r.width != null && r.height != null) "${r.width}x${r.height}" else unknown
        val rate = r.fps?.let { String.format(Locale.ROOT, "%.0f fps", it) } ?: unknown
        val bitrate = r.bitrateKbps?.let { "$it kbps" } ?: unknown
        return "$size · $rate · $bitrate · ${r.codecPreference} · ${launch.role.wire}"
    }

    private fun lastSample(context: Context, last: NovaStreamRecap.LastSample, unknown: String): String {
        val size = if (last.width != null && last.height != null) "${last.width}x${last.height}" else unknown
        return listOf(
            last.codec,
            size,
            "${context.getString(R.string.nova_recap_rendered_fps)} ${last.renderedFps?.let(::fps) ?: unknown}",
            "${context.getString(R.string.nova_recap_received_fps)} ${last.receivedFps?.let(::fps) ?: unknown}",
            "${context.getString(R.string.nova_recap_decode_time)} ${last.decodeTimeMs?.let(::ms) ?: unknown}",
            "${context.getString(R.string.nova_recap_control_rtt)} ${last.controlRttMs?.let(::ms) ?: unknown}",
        ).joinToString(" · ")
    }

    internal fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%d h %02d min", h, m)
            m > 0 -> String.format(Locale.ROOT, "%d min %02d s", m, s)
            else -> String.format(Locale.ROOT, "%d s", s)
        }
    }

    private fun fps(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun ms(value: Double) = String.format(Locale.ROOT, "%.1f ms", value)
    private fun pct(value: Double) = String.format(Locale.ROOT, "%.2f%%", value)
}

/** The recap's read-only page. Leaving it changes nothing. */
internal object NovaStreamRecapPages {
    fun page(context: Context, recap: NovaStreamRecap.Recap) = com.papi.nova.ui.panel.NovaCommonPage.Notice(
        key = "nova-last-stream-${recap.generation}",
        title = context.getString(R.string.nova_recap_page_title),
        message = NovaStreamRecapText.page(context, recap),
        closeLabel = context.getString(R.string.nova_recap_close),
        monospace = true,
    )
}
