package com.papi.nova.ui

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow
import com.papi.nova.diagnostics.NovaWorkingBaselineStore
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuHeader
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPage

/**
 * Working Baseline's pages in the Command Center: a menu read when it opens (Compare, then Mark or
 * Replace, then Clear), the confirmation that is the user's own statement, and the read-only
 * comparison. [reopen] puts a freshly read menu in place of the open one when a Save or Clear
 * starts (it then shows the change as under way) and again when it finishes, and [redraw]
 * refreshes the row under it.
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
        val busy = context.getString(R.string.nova_working_baseline_busy)
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
                    page = { confirmation(state) },
                ) else NovaMenuItem.Action(
                    key = SAVE,
                    label = markLabel,
                    disabledReason = if (state.busy) busy else needsStream,
                    onClick = {},
                )
            )
            val clearLabel = context.getString(R.string.nova_working_baseline_clear)
            if (state.canClear) add(
                NovaMenuItem.Destructive(
                    key = CLEAR,
                    label = clearLabel,
                    confirmLabel = context.getString(R.string.nova_working_baseline_clear_confirm),
                    consequence = context.getString(
                        if (state.unrecoverableOthers) R.string.nova_working_baseline_clear_consequence_unrecoverable
                        else R.string.nova_working_baseline_clear_consequence),
                    stayLabel = context.getString(R.string.nova_working_baseline_keep),
                    onConfirm = { change(flow::clear) },
                )
            ) else if (state.busy && state.loaded != NovaWorkingBaselineStore.Loaded.None) add(
                NovaMenuItem.Action(key = CLEAR, label = clearLabel, disabledReason = busy, onClick = {})
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
     * saves readings from before it. It says when the save would also remove other baselines:
     * ones that can no longer be read, or the oldest, to stay within the limit.
     */
    fun confirmation(state: NovaWorkingBaselineFlow.State): NovaCommonPage.Confirm {
        val saved = state.saved
        val replacing = saved != null || state.loaded == NovaWorkingBaselineStore.Loaded.Unreadable
        val notes = listOfNotNull(
            saved?.let { context.getString(R.string.nova_working_baseline_confirm_replace_note, it.markedAtUtc) },
            if (state.loaded == NovaWorkingBaselineStore.Loaded.Unreadable)
                context.getString(R.string.nova_working_baseline_confirm_unreadable_note) else null,
            if (state.unrecoverableOthers) context.getString(R.string.nova_working_baseline_confirm_unrecoverable_note) else null,
            if (state.saveEvictsOldest) context.getString(R.string.nova_working_baseline_confirm_evicts_note) else null,
        )
        return NovaCommonPage.Confirm(
            key = CONFIRM,
            title = context.getString(
                if (replacing) R.string.nova_working_baseline_confirm_replace_title else R.string.nova_working_baseline_confirm_save_title),
            message = AnnotatedString(
                (listOf(context.getString(R.string.nova_working_baseline_confirm_message)) + notes).joinToString("\n\n")),
            stayLabel = context.getString(R.string.nova_working_baseline_cancel),
            actionLabel = context.getString(
                if (replacing) R.string.nova_working_baseline_replace_action else R.string.nova_working_baseline_save),
            destructive = false,
            onConfirm = { change(flow::save) },
        )
    }

    /** Read-only, whatever is or isn't stored. */
    fun comparison() = NovaCommonPage.Notice(
        key = COMPARISON,
        title = context.getString(R.string.nova_working_baseline_compare_title),
        message = NovaWorkingBaselineText.compared(context, flow.compare()),
        closeLabel = context.getString(R.string.nova_working_baseline_done),
        monospace = true,
    )

    /**
     * Starts a Save or Clear. One that needs no write answers at once; otherwise the menu is read
     * again to show the change under way, and again when its answer comes back. An answer for an
     * opening that has closed never arrives (the flow drops it).
     */
    private fun change(start: ((NovaWorkingBaselineFlow.Outcome) -> Unit) -> Unit) {
        var answered = false
        start { outcome ->
            answered = true
            feedback(outcome)
            redraw()
            reopen(menu())
        }
        if (!answered) {
            redraw()
            reopen(menu())
        }
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
