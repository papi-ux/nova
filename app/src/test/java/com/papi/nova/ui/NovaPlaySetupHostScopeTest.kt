package com.papi.nova.ui

import com.papi.nova.manager.PolarisProfileSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard Every Game never had. What it holds still: the four host rows and their
 * order, desired and effective as separate axes on the mode cards, the arrow summary
 * finally rendering when they differ, and — the one that bites — A on the Profile row
 * firing nothing but Match Nova, because cycling four different verbs would push and
 * pull profiles nobody sequenced.
 */
class NovaPlaySetupHostScopeTest {

    private val getString = { resId: Int -> "s:$resId" }

    private class RecordedActions {
        val calls = mutableListOf<String>()
        val actions = NovaPlaySetupHostActions(
            onSelectMode = { calls += "mode:$it" },
            onMatchNova = { calls += "match" },
            onSendNova = { calls += "send" },
            onUsePolaris = { calls += "pull" },
            onClearProfile = { calls += "clear" },
            onAutoQuality = { calls += "ai:$it" },
            onKeepInStep = { calls += "step:$it" },
        )
    }

    private fun mode(
        mode: String,
        desired: Boolean = false,
        effective: Boolean = false,
        enabled: Boolean = true,
    ) = NovaPolarisModeUiState(
        mode = mode,
        label = mode,
        selected = desired,
        selectedDesired = desired,
        selectedEffective = effective,
        enabled = enabled,
        available = enabled,
        reason = "",
        restartRequired = false,
        statusLabel = "",
    )

    private fun sync(
        desiredLabel: String = "Private Stream",
        effectiveLabel: String = "Private Stream",
        modes: List<NovaPolarisModeUiState> = listOf(
            mode("headless_stream", desired = true, effective = true),
            mode("host_virtual_display"),
        ),
        matchNovaEnabled: Boolean = true,
        aiChecked: Boolean = false,
        autoSyncChecked: Boolean = false,
        relaunchRequired: Boolean = false,
    ) = NovaPolarisSyncUiState(
        status = NovaPolarisSyncStatus.SYNCED,
        desiredModeLabel = desiredLabel,
        effectiveModeLabel = effectiveLabel,
        modes = modes,
        profileState = PolarisProfileSync.ProfileState.DIFFERENT,
        matchNovaVisible = matchNovaEnabled,
        matchNovaEnabled = matchNovaEnabled,
        sendNovaEnabled = true,
        usePolarisEnabled = true,
        clearProfileEnabled = true,
        aiChecked = aiChecked,
        aiEnabled = true,
        autoSyncChecked = autoSyncChecked,
        autoSyncEnabled = true,
        relaunchRequired = relaunchRequired,
        modeSummary = if (desiredLabel == effectiveLabel) desiredLabel else "$desiredLabel → $effectiveLabel",
    )

    private fun rows(sync: NovaPolarisSyncUiState, recorded: RecordedActions) =
        buildNovaPlaySetupHostRows(sync, "profile", getString, recorded.actions)

    @Test
    fun buildsTheSheetSectionsAsFourRowsInOrder() {
        val rows = rows(sync(), RecordedActions())
        assertEquals(
            listOf(
                NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
                NovaPlaySetupRow.HOST_PROFILE,
                NovaPlaySetupRow.HOST_AUTO_QUALITY,
                NovaPlaySetupRow.HOST_KEEP_IN_STEP,
            ),
            rows.map { it.row },
        )
        assertEquals(4, rows[NovaPlaySetupRow.HOST_PROFILE.hostIndex()].options.size)
    }

    @Test
    fun defaultDisplayReadsTheArrowWhenDesiredAndEffectiveDiffer() {
        val recorded = RecordedActions()
        val differing = sync(desiredLabel = "Host Virtual Display", effectiveLabel = "Private Stream")
        assertEquals(
            "Host Virtual Display → Private Stream",
            rows(differing, recorded).first().value,
        )
        assertEquals("Private Stream", rows(sync(), recorded).first().value)
    }

    @Test
    fun modeCardsCarryDesiredAndEffectiveAsSeparateAxes() {
        val fellBack = sync(
            modes = listOf(
                mode("host_virtual_display", desired = true, effective = false),
                mode("headless_stream", desired = false, effective = true),
            ),
        )
        val cards = rows(fellBack, RecordedActions()).first().options
        assertTrue(cards[0].current)
        assertEquals(false, cards[0].active)
        assertEquals(false, cards[1].current)
        assertTrue(cards[1].active)
    }

    @Test
    fun advancingProfileFiresOnlyMatchNova() {
        val recorded = RecordedActions()
        val sync = sync()
        advanceNovaPlaySetupHostRow(NovaPlaySetupRow.HOST_PROFILE, rows(sync, recorded), sync, recorded.actions)
        assertEquals(listOf("match"), recorded.calls)

        val disabled = RecordedActions()
        val matchedAlready = sync(matchNovaEnabled = false)
        advanceNovaPlaySetupHostRow(
            NovaPlaySetupRow.HOST_PROFILE,
            rows(matchedAlready, disabled),
            matchedAlready,
            disabled.actions,
        )
        assertEquals(emptyList<String>(), disabled.calls)
    }

    @Test
    fun advancingDefaultDisplayCyclesToTheNextEnabledMode() {
        val recorded = RecordedActions()
        val threeModes = sync(
            modes = listOf(
                mode("headless_stream", desired = true, effective = true),
                mode("desktop_display", enabled = false),
                mode("host_virtual_display"),
            ),
        )
        advanceNovaPlaySetupHostRow(
            NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
            rows(threeModes, recorded),
            threeModes,
            recorded.actions,
        )
        assertEquals(listOf("mode:host_virtual_display"), recorded.calls)
    }

    @Test
    fun togglesFlipOnAdvance() {
        val recorded = RecordedActions()
        val sync = sync(aiChecked = true, autoSyncChecked = false)
        val built = rows(sync, recorded)
        advanceNovaPlaySetupHostRow(NovaPlaySetupRow.HOST_AUTO_QUALITY, built, sync, recorded.actions)
        advanceNovaPlaySetupHostRow(NovaPlaySetupRow.HOST_KEEP_IN_STEP, built, sync, recorded.actions)
        assertEquals(listOf("ai:false", "step:true"), recorded.calls)
    }

    @Test
    fun hostPlanWarnsWhenEffectiveLagsDesired() {
        val differing = sync(desiredLabel = "Host Virtual Display", effectiveLabel = "Private Stream")
        val plan = novaPlaySetupHostPlan(differing, "profile", getString)
        assertEquals("Host Virtual Display → Private Stream", plan.mode)
        assertEquals(NovaPlaySetupTone.WARN, plan.facts[1].tone)

        val matching = novaPlaySetupHostPlan(sync(), "profile", getString)
        assertEquals("Private Stream", matching.mode)
        assertEquals(NovaPlaySetupTone.GOOD, matching.facts[1].tone)
    }
}

private fun NovaPlaySetupRow.hostIndex(): Int = when (this) {
    NovaPlaySetupRow.HOST_DEFAULT_DISPLAY -> 0
    NovaPlaySetupRow.HOST_PROFILE -> 1
    NovaPlaySetupRow.HOST_AUTO_QUALITY -> 2
    NovaPlaySetupRow.HOST_KEEP_IN_STEP -> 3
    else -> error("not a host row: $this")
}
