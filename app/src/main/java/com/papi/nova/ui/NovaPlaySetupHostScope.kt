package com.papi.nova.ui

import com.papi.nova.R

/**
 * Every Game's four rows, built pure so the panel's host scope has a guard.
 *
 * The activity supplies strings and the engine's verbs; everything about which rows
 * exist, what they read, and what A is allowed to do lives here where a unit test can
 * hold it still. The shape mirrors the game scope on purpose: the pill changes the
 * subject, not how the panel is read.
 */
internal class NovaPlaySetupHostActions(
    val onSelectMode: (String) -> Unit,
    val onMatchNova: () -> Unit,
    val onSendNova: () -> Unit,
    val onUsePolaris: () -> Unit,
    val onClearProfile: () -> Unit,
    val onKeepInStep: (Boolean) -> Unit,
)

internal fun buildNovaPlaySetupHostRows(
    sync: NovaPolarisSyncUiState,
    polarisProfileValue: String,
    getString: (Int) -> String,
    actions: NovaPlaySetupHostActions,
): List<NovaPlaySetupRowState> {
    val ready = sync.status == NovaPolarisSyncStatus.SYNCED ||
        sync.status == NovaPolarisSyncStatus.SYNCING
    fun onOff(checked: Boolean) =
        getString(if (checked) R.string.nova_play_setup_on else R.string.nova_play_setup_off)

    val rows = mutableListOf<NovaPlaySetupRowState>()
    rows += NovaPlaySetupRowState(
        row = NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
        label = getString(R.string.nova_play_setup_host_default_display),
        caption = getString(R.string.nova_play_setup_host_default_display_caption),
        // The never-rendered summary finally has its surface: a host that fell back
        // or is waiting on a relaunch reads "desired → effective" here.
        value = if (sync.desiredModeLabel != sync.effectiveModeLabel) {
            sync.modeSummary
        } else {
            sync.desiredModeLabel
        },
        stripTitle = getString(R.string.nova_play_setup_strip_default_display),
        options = sync.modes.map { mode ->
            NovaPlaySetupOption(
                label = mode.label,
                consequence = listOf(mode.statusLabel, mode.reason)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                current = mode.selectedDesired,
                active = mode.selectedEffective && !mode.selectedDesired,
                enabled = mode.enabled,
                onSelect = if (mode.enabled) {
                    { actions.onSelectMode(mode.mode) }
                } else {
                    null
                },
            )
        },
        enabled = ready,
        overridden = sync.relaunchRequired,
    )
    rows += NovaPlaySetupRowState(
        row = NovaPlaySetupRow.HOST_PROFILE,
        label = getString(R.string.nova_play_setup_host_profile_row),
        caption = getString(R.string.nova_play_setup_host_profile_caption),
        value = polarisProfileValue,
        stripTitle = getString(R.string.nova_play_setup_strip_host_profile),
        options = listOf(
            NovaPlaySetupOption(
                label = getString(R.string.nova_polaris_sync_match_nova),
                consequence = getString(R.string.nova_play_setup_consequence_match_nova),
                enabled = sync.matchNovaEnabled,
                onSelect = if (sync.matchNovaEnabled) actions.onMatchNova else null,
            ),
            NovaPlaySetupOption(
                label = getString(R.string.nova_polaris_sync_send_nova),
                consequence = getString(R.string.nova_play_setup_consequence_send_nova),
                enabled = sync.sendNovaEnabled,
                onSelect = if (sync.sendNovaEnabled) actions.onSendNova else null,
            ),
            NovaPlaySetupOption(
                label = getString(R.string.nova_polaris_sync_use_polaris),
                consequence = getString(R.string.nova_play_setup_consequence_use_polaris),
                enabled = sync.usePolarisEnabled,
                onSelect = if (sync.usePolarisEnabled) actions.onUsePolaris else null,
            ),
            NovaPlaySetupOption(
                label = getString(R.string.nova_polaris_sync_clear_profile),
                consequence = getString(R.string.nova_play_setup_consequence_clear_profile),
                enabled = sync.clearProfileEnabled,
                onSelect = if (sync.clearProfileEnabled) actions.onClearProfile else null,
            ),
        ),
        enabled = ready,
    )
    rows += NovaPlaySetupRowState(
        row = NovaPlaySetupRow.HOST_KEEP_IN_STEP,
        label = getString(R.string.nova_play_setup_host_keep_in_step),
        caption = getString(R.string.nova_play_setup_host_keep_in_step_caption),
        value = onOff(sync.autoSyncChecked),
        stripTitle = getString(R.string.nova_play_setup_strip_keep_in_step),
        options = listOf(
            NovaPlaySetupOption(
                label = onOff(true),
                consequence = getString(R.string.nova_play_setup_consequence_keep_on),
                current = sync.autoSyncChecked,
                enabled = sync.autoSyncEnabled,
                onSelect = if (sync.autoSyncEnabled) {
                    { actions.onKeepInStep(true) }
                } else {
                    null
                },
            ),
            NovaPlaySetupOption(
                label = onOff(false),
                consequence = getString(R.string.nova_play_setup_consequence_keep_off),
                current = !sync.autoSyncChecked,
                enabled = sync.autoSyncEnabled,
                onSelect = if (sync.autoSyncEnabled) {
                    { actions.onKeepInStep(false) }
                } else {
                    null
                },
            ),
        ),
        enabled = sync.autoSyncEnabled,
    )
    return rows
}

/**
 * A on a host row. Default Display cycles like the game rows do and the toggles flip,
 * but Profile does not cycle: its four cards are four different verbs, and stepping
 * through them would fire pushes and pulls nobody sequenced. A performs the one its
 * caption names — match this handheld — and only when that verb is enabled.
 */
internal fun advanceNovaPlaySetupHostRow(
    row: NovaPlaySetupRow,
    rows: List<NovaPlaySetupRowState>,
    sync: NovaPolarisSyncUiState,
    actions: NovaPlaySetupHostActions,
) {
    if (row == NovaPlaySetupRow.HOST_PROFILE) {
        if (sync.matchNovaEnabled) {
            actions.onMatchNova()
        }
        return
    }
    val options = rows.firstOrNull { it.row == row }?.options.orEmpty()
    val selectable = options.filter { it.enabled && it.onSelect != null }
    if (selectable.size < 2) return
    val currentIndex = selectable.indexOfFirst { it.current }
    selectable[(currentIndex + 1).mod(selectable.size)].onSelect?.invoke()
}

/** What the host defaults to, with Desired and Effective as the two why rows. */
internal fun novaPlaySetupHostPlan(
    sync: NovaPolarisSyncUiState,
    polarisProfileValue: String,
    getString: (Int) -> String,
): NovaPlaySetupPlan {
    val differs = sync.desiredModeLabel != sync.effectiveModeLabel
    return NovaPlaySetupPlan(
        mode = if (differs) sync.modeSummary else sync.desiredModeLabel,
        lines = listOf(getString(R.string.nova_play_setup_host_intro)),
        facts = listOf(
            NovaPlaySetupFact(
                key = getString(R.string.nova_play_setup_fact_desired),
                value = sync.desiredModeLabel,
            ),
            NovaPlaySetupFact(
                key = getString(R.string.nova_play_setup_fact_effective),
                value = sync.effectiveModeLabel,
                detail = getString(
                    if (differs) {
                        R.string.nova_play_setup_host_effective_pending
                    } else {
                        R.string.nova_play_setup_host_effective_matches
                    }
                ),
                tone = if (differs) NovaPlaySetupTone.WARN else NovaPlaySetupTone.GOOD,
            ),
            NovaPlaySetupFact(
                key = getString(R.string.nova_play_setup_fact_host_profile),
                value = polarisProfileValue,
            ),
        ),
    )
}
