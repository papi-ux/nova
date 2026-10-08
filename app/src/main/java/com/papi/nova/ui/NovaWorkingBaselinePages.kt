package com.papi.nova.ui

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaWorkingBaseline
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow
import com.papi.nova.diagnostics.NovaWorkingBaselineStore
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuHeader
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPage

/**
 * Working Baseline's pages in the Command Center: a menu read when it opens (Compare, then Mark or
 * Replace, then Clear), the confirmation that is the user's own statement, and the read-only
 * comparison. [reopen] puts a freshly read menu in place of the open one after a Save or Clear,
 * and [redraw] refreshes the row under it.
 */
internal class NovaWorkingBaselinePages(
    private val context: Context,
    private val flow: NovaWorkingBaselineFlow,
    private val feedback: (NovaWorkingBaselineFlow.Outcome) -> Unit,
    private val reopen: (NovaPage) -> Unit,
    private val redraw: () -> Unit,
) {
    fun menu(): NovaCommonPage.Menu {
        val state = flow.state()
        val saved = state.saved
        val unreadable = state.loaded == NovaWorkingBaselineStore.Loaded.Unreadable
        val needsStream = context.getString(R.string.nova_working_baseline_needs_stream)
        val items = buildList<NovaMenuItem> {
            if (saved != null) add(
                if (state.canCompare) NovaMenuItem.Opens(
                    key = COMPARE,
                    label = context.getString(R.string.nova_working_baseline_compare),
                    caption = context.getString(R.string.nova_working_baseline_compare_caption),
                    // Built when Compare is pressed, from a snapshot of that instant.
                    page = ::comparison,
                ) else NovaMenuItem.Action(
                    key = COMPARE,
                    label = context.getString(R.string.nova_working_baseline_compare),
                    disabledReason = needsStream,
                    onClick = {},
                )
            )
            val replacing = saved != null || unreadable
            val markLabel = context.getString(
                if (replacing) R.string.nova_working_baseline_replace else R.string.nova_working_baseline_mark)
            add(
                if (state.canSave) NovaMenuItem.Opens(
                    key = SAVE,
                    label = markLabel,
                    caption = context.getString(R.string.nova_working_baseline_mark_caption),
                    page = { confirmation(saved, replacing) },
                ) else NovaMenuItem.Action(
                    key = SAVE,
                    label = markLabel,
                    disabledReason = needsStream,
                    onClick = {},
                )
            )
            if (state.canClear) add(
                NovaMenuItem.Destructive(
                    key = CLEAR,
                    label = context.getString(R.string.nova_working_baseline_clear),
                    confirmLabel = context.getString(R.string.nova_working_baseline_clear_confirm),
                    consequence = context.getString(R.string.nova_working_baseline_clear_consequence),
                    stayLabel = context.getString(R.string.nova_working_baseline_keep),
                    onConfirm = { answered(flow.clear()) },
                )
            )
        }
        return NovaCommonPage.Menu(
            key = MENU,
            title = context.getString(R.string.nova_working_baseline_title),
            items = items,
            header = NovaMenuHeader(
                title = context.getString(R.string.nova_working_baseline_title),
                status = when {
                    saved != null -> context.getString(R.string.nova_working_baseline_status_saved, saved.markedAtUtc)
                    unreadable -> context.getString(R.string.nova_working_baseline_status_unreadable)
                    else -> context.getString(R.string.nova_working_baseline_status_none)
                },
                hint = context.getString(R.string.nova_working_baseline_hint),
            ),
        )
    }

    /**
     * The user's statement, for a first save and a replacement alike. Keep Playing is focused and
     * is what B does. Nothing is read until Save or Replace is pressed, so a long wait here never
     * saves readings from before it.
     */
    fun confirmation(saved: NovaWorkingBaseline.Baseline?, replacing: Boolean) = NovaCommonPage.Confirm(
        key = CONFIRM,
        title = context.getString(
            if (replacing) R.string.nova_working_baseline_confirm_replace_title else R.string.nova_working_baseline_confirm_save_title),
        message = AnnotatedString(
            context.getString(R.string.nova_working_baseline_confirm_message) +
                (saved?.let { "\n\n" + context.getString(R.string.nova_working_baseline_confirm_replace_note, it.markedAtUtc) } ?: "")),
        stayLabel = context.getString(R.string.nova_working_baseline_cancel),
        actionLabel = context.getString(
            if (replacing) R.string.nova_working_baseline_replace_action else R.string.nova_working_baseline_save),
        destructive = false,
        onConfirm = { answered(flow.save()) },
    )

    /** Read-only, whatever is or isn't stored. */
    fun comparison() = NovaCommonPage.Notice(
        key = COMPARISON,
        title = context.getString(R.string.nova_working_baseline_compare_title),
        message = NovaWorkingBaselineText.compared(context, flow.compare()),
        closeLabel = context.getString(R.string.nova_working_baseline_done),
        monospace = true,
    )

    private fun answered(outcome: NovaWorkingBaselineFlow.Outcome) {
        feedback(outcome)
        redraw()
        reopen(menu())
    }

    companion object {
        const val MENU = "nova-working-baseline"
        const val CONFIRM = "nova-working-baseline-confirm"
        const val COMPARISON = "nova-working-baseline-compare"
        const val COMPARE = "compare"
        const val SAVE = "save"
        const val CLEAR = "clear"
    }
}
