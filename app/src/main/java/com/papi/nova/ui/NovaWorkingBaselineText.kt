package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaWorkingBaseline
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow
import java.util.Locale

/**
 * Working Baseline's words: the comparison page, set out baseline beside now with each reading's
 * scope, and what each press did. Descriptive only: no score, no verdict on the readings, and no
 * claim about the game scene, the host or the network path.
 */
internal object NovaWorkingBaselineText {
    fun compared(context: Context, result: NovaWorkingBaselineFlow.Compared): String = when (result) {
        is NovaWorkingBaselineFlow.Compared.Shown -> comparison(context, result.comparison)
        NovaWorkingBaselineFlow.Compared.NoEntry -> context.getString(R.string.nova_working_baseline_no_entry)
        NovaWorkingBaselineFlow.Compared.NotActive -> context.getString(R.string.nova_working_baseline_compare_not_active)
        NovaWorkingBaselineFlow.Compared.NoBaseline -> context.getString(R.string.nova_working_baseline_compare_none)
        NovaWorkingBaselineFlow.Compared.Unreadable -> context.getString(R.string.nova_working_baseline_compare_unreadable)
    }

    fun comparison(context: Context, c: NovaWorkingBaseline.Comparison): String = buildString {
        val unknown = context.getString(R.string.nova_working_baseline_unknown)
        appendLine(context.getString(R.string.nova_working_baseline_compare_baseline, c.baselineMarkedAtUtc, c.baselineSampleAgeMs))
        val nowAge = c.nowSampleAgeMs
        appendLine(
            if (c.nowCurrent && nowAge != null) context.getString(R.string.nova_working_baseline_compare_now, c.nowMarkedAtUtc, nowAge)
            else context.getString(R.string.nova_working_baseline_compare_now_unavailable, c.nowMarkedAtUtc)
        )
        appendLine(context.getString(R.string.nova_working_baseline_compare_freshness))
        appendLine()
        appendLine(context.getString(R.string.nova_working_baseline_compare_context))
        c.context.forEach { line ->
            val match = context.getString(when (line.match) {
                NovaWorkingBaseline.Match.SAME -> R.string.nova_working_baseline_match_same
                NovaWorkingBaseline.Match.DIFFERENT -> R.string.nova_working_baseline_match_different
                NovaWorkingBaseline.Match.UNKNOWN -> R.string.nova_working_baseline_unknown
            })
            appendLine("${context.getString(label(line.item))}: ${line.baseline ?: unknown} → ${line.now ?: unknown} · $match")
        }
        appendLine(context.getString(when (c.knownClientContextMatches) {
            true -> R.string.nova_working_baseline_context_all_same
            false -> R.string.nova_working_baseline_context_differs
            null -> R.string.nova_working_baseline_context_unknown
        }))
        appendLine()
        appendLine(context.getString(R.string.nova_working_baseline_compare_readings))
        c.readings.forEach { line ->
            appendLine("${context.getString(label(line.item))}: ${value(line.item, line.baseline) ?: unknown} → " +
                (value(line.item, line.now) ?: unknown))
        }
        appendLine()
        append(context.getString(R.string.nova_working_baseline_not_compared))
    }

    fun outcome(context: Context, outcome: NovaWorkingBaselineFlow.Outcome): String = context.getString(when (outcome) {
        NovaWorkingBaselineFlow.Outcome.SAVED -> R.string.nova_working_baseline_saved
        NovaWorkingBaselineFlow.Outcome.REPLACED -> R.string.nova_working_baseline_replaced
        NovaWorkingBaselineFlow.Outcome.CLEARED -> R.string.nova_working_baseline_cleared
        NovaWorkingBaselineFlow.Outcome.NOT_ACTIVE -> R.string.nova_working_baseline_not_active
        NovaWorkingBaselineFlow.Outcome.NO_ENTRY -> R.string.nova_working_baseline_no_entry
        NovaWorkingBaselineFlow.Outcome.READINGS_UNAVAILABLE -> R.string.nova_working_baseline_readings_unavailable
        NovaWorkingBaselineFlow.Outcome.STORAGE_FAILED -> R.string.nova_working_baseline_storage_failed
        NovaWorkingBaselineFlow.Outcome.BUSY -> R.string.nova_working_baseline_busy
    })

    fun succeeded(outcome: NovaWorkingBaselineFlow.Outcome): Boolean = outcome == NovaWorkingBaselineFlow.Outcome.SAVED ||
        outcome == NovaWorkingBaselineFlow.Outcome.REPLACED || outcome == NovaWorkingBaselineFlow.Outcome.CLEARED

    private fun label(item: NovaWorkingBaseline.ContextItem): Int = when (item) {
        NovaWorkingBaseline.ContextItem.ROLE -> R.string.nova_working_baseline_item_role
        NovaWorkingBaseline.ContextItem.REQUESTED_RESOLUTION -> R.string.nova_working_baseline_item_requested_resolution
        NovaWorkingBaseline.ContextItem.REQUESTED_FPS -> R.string.nova_working_baseline_item_requested_fps
        NovaWorkingBaseline.ContextItem.REQUESTED_BITRATE -> R.string.nova_working_baseline_item_requested_bitrate
        NovaWorkingBaseline.ContextItem.REQUESTED_CODEC -> R.string.nova_working_baseline_item_requested_codec
        NovaWorkingBaseline.ContextItem.DECODER_CODEC -> R.string.nova_working_baseline_item_decoder_codec
        NovaWorkingBaseline.ContextItem.DECODER_COMPONENT -> R.string.nova_working_baseline_item_decoder_component
        NovaWorkingBaseline.ContextItem.DECODED_RESOLUTION -> R.string.nova_working_baseline_item_decoded_resolution
        NovaWorkingBaseline.ContextItem.NOVA_VERSION -> R.string.nova_working_baseline_item_nova_version
        NovaWorkingBaseline.ContextItem.DEVICE -> R.string.nova_working_baseline_item_device
    }

    private fun label(item: NovaWorkingBaseline.ReadingItem): Int = when (item) {
        NovaWorkingBaseline.ReadingItem.RENDERED_FPS -> R.string.nova_working_baseline_reading_rendered_fps
        NovaWorkingBaseline.ReadingItem.RECEIVED_FPS -> R.string.nova_working_baseline_reading_received_fps
        NovaWorkingBaseline.ReadingItem.DECODE_TIME_MS -> R.string.nova_working_baseline_reading_decode_time
        NovaWorkingBaseline.ReadingItem.HOST_PROCESSING_LATENCY_MS -> R.string.nova_working_baseline_reading_host_processing
        NovaWorkingBaseline.ReadingItem.FRAME_LOSS_PCT -> R.string.nova_working_baseline_reading_frame_loss
        NovaWorkingBaseline.ReadingItem.CONTROL_RTT_MS -> R.string.nova_working_baseline_reading_control_rtt
        NovaWorkingBaseline.ReadingItem.CONTROL_RTT_VARIANCE_MS -> R.string.nova_working_baseline_reading_control_rtt_variance
    }

    private fun value(item: NovaWorkingBaseline.ReadingItem, value: Double?): String? = value?.let {
        when (item) {
            NovaWorkingBaseline.ReadingItem.RENDERED_FPS,
            NovaWorkingBaseline.ReadingItem.RECEIVED_FPS -> String.format(Locale.ROOT, "%.1f fps", it)
            NovaWorkingBaseline.ReadingItem.FRAME_LOSS_PCT -> String.format(Locale.ROOT, "%.2f%%", it)
            else -> String.format(Locale.ROOT, "%.1f ms", it)
        }
    }
}
