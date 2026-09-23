package com.papi.nova.ui

import com.papi.nova.manager.PolarisProfileSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard Every Game never had. What it holds still: the three deterministic host rows and their
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
            onSelectScreenToAdd = { mode, scale -> calls += "screen:$mode@$scale" },
            onSelectScreenScale = { calls += "scale:$it" },
            onMatchNova = { calls += "match" },
            onSendNova = { calls += "send" },
            onUsePolaris = { calls += "pull" },
            onClearProfile = { calls += "clear" },
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
        screenToAddMode: String = "",
        deviceScreenMode: String = "",
        screenToAddScale: Double = 0.0,
        deviceScreenScale: Double = 0.0,
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
        screenToAddMode = screenToAddMode,
        deviceScreenMode = deviceScreenMode,
        screenToAddScale = screenToAddScale,
        deviceScreenScale = deviceScreenScale,
    )


    @Test
    fun theScreenToAddRowAppearsOnlyWhenThisDeviceKnowsItsOwnPanel() {
        // Blank means the panel could not be measured, and a row offering to match nothing would be
        // a row that cannot be used. Every other release sized the screen from the stream, so that
        // is what it keeps doing.
        val recorded = RecordedActions()
        val withoutPanel = rows(sync(), recorded)
        assertNull(withoutPanel.firstOrNull { it.row == NovaPlaySetupRow.HOST_SCREEN_TO_ADD })

        val withPanel = rows(sync(deviceScreenMode = "2560x1600x60"), recorded)
        val row = withPanel.firstOrNull { it.row == NovaPlaySetupRow.HOST_SCREEN_TO_ADD }
        assertNotNull(row)
    }

    @Test
    fun theScreenToAddRowSaysWhatTheHostWillMakeAndSetsIt() {
        val recorded = RecordedActions()
        val following = rows(sync(deviceScreenMode = "2560x1600x60"), recorded)
            .first { it.row == NovaPlaySetupRow.HOST_SCREEN_TO_ADD }
        // Nothing set: the host follows the stream, which is what every release before this did, so
        // the row must not read as though a size had been chosen.
        assertFalse(following.overridden)
        assertFalse(following.value.contains("2560"))
        assertEquals(2, following.options.size)
        // The device option carries the measured panel as its consequence, so the row says what it
        // will actually ask for rather than only naming itself.
        assertEquals("2560x1600x60", following.options[0].consequence)
        assertFalse(following.options[0].current)
        assertTrue(following.options[1].current)

        following.options[0].onSelect?.invoke()
        assertEquals(listOf("screen:2560x1600x60@0.0"), recorded.calls)

        val set = rows(
            sync(deviceScreenMode = "2560x1600x60", screenToAddMode = "2560x1600x60"),
            recorded,
        ).first { it.row == NovaPlaySetupRow.HOST_SCREEN_TO_ADD }
        // Set: the row reads as the screen the host will make, and says it is not the default.
        assertEquals("2560x1600x60", set.value)
        assertTrue(set.overridden)
        assertTrue(set.options[0].current)
        assertFalse(set.options[1].current)

        recorded.calls.clear()
        set.options[1].onSelect?.invoke()
        // Clearing sends an empty value, which is what the host reads as "follow the stream".
        assertEquals(listOf("screen:@0.0"), recorded.calls)
    }

    @Test
    fun matchingThisDeviceSendsItsScaleWithItsSize() {
        // The two halves of one answer. A screen the shape of this panel drawn at scale 1 has the
        // same pixels the panel has and none of the size, which is where papi ended up: a 2560x1600
        // desktop on ten inches, technically correct and unusable.
        val recorded = RecordedActions()
        rows(sync(deviceScreenMode = "2560x1600x60", deviceScreenScale = 2.0), recorded)
            .first { it.row == NovaPlaySetupRow.HOST_SCREEN_TO_ADD }
            .options[0].onSelect?.invoke()

        assertEquals(listOf("screen:2560x1600x60@2.0"), recorded.calls)
    }

    @Test
    fun theScreenScaleRowSaysWhatDesktopEachChoiceLeaves() {
        val recorded = RecordedActions()
        val row = rows(
            sync(
                deviceScreenMode = "2560x1600x60",
                screenToAddMode = "2560x1600x60",
                deviceScreenScale = 2.0,
            ),
            recorded,
        ).first { it.row == NovaPlaySetupRow.HOST_SCREEN_SCALE }

        // Nothing saved yet, so the row reads as what the host will actually do, which is scale 1.
        assertEquals("1x", row.value)
        assertFalse(row.overridden)

        // This device first, then the fixed steps that are not the same answer twice: 2x is what
        // this tablet reports, so it appears once, as Match This Device.
        assertEquals(3, row.options.size)
        assertEquals("1280x800", row.options[0].consequence)
        assertEquals("1x", row.options[1].label)
        assertEquals("2560x1600", row.options[1].consequence)
        assertEquals("1.5x", row.options[2].label)
        assertTrue(row.options[1].current)
        assertFalse(row.options[0].current)

        row.options[0].onSelect?.invoke()
        assertEquals(listOf("scale:2.0"), recorded.calls)

        val saved = rows(
            sync(
                deviceScreenMode = "2560x1600x60",
                screenToAddMode = "2560x1600x60",
                screenToAddScale = 2.0,
                deviceScreenScale = 2.0,
            ),
            recorded,
        ).first { it.row == NovaPlaySetupRow.HOST_SCREEN_SCALE }
        assertEquals("2x", saved.value)
        assertTrue(saved.overridden)
        assertTrue(saved.options[0].current)
        assertFalse(saved.options[1].current)
    }

    @Test
    fun theScreenScaleRowNeedsAPanelToTalkAbout() {
        // Same gate as the size it belongs to: a scale for a screen this device cannot describe
        // would be a row with nothing behind it.
        val recorded = RecordedActions()
        assertNull(
            rows(sync(), recorded).firstOrNull { it.row == NovaPlaySetupRow.HOST_SCREEN_SCALE }
        )
    }

    private fun rows(sync: NovaPolarisSyncUiState, recorded: RecordedActions) =
        buildNovaPlaySetupHostRows(sync, "profile", getString, recorded.actions)

    @Test
    fun buildsTheSheetSectionsAsThreeRowsInOrder() {
        val rows = rows(sync(), RecordedActions())
        assertEquals(
            listOf(
                NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
                NovaPlaySetupRow.HOST_PROFILE,
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
    fun onlyKeepInStepRemainsAsAHostToggle() {
        val recorded = RecordedActions()
        val sync = sync(aiChecked = true, autoSyncChecked = false)
        val built = rows(sync, recorded)
        advanceNovaPlaySetupHostRow(NovaPlaySetupRow.HOST_KEEP_IN_STEP, built, sync, recorded.actions)
        assertEquals(listOf("step:true"), recorded.calls)
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
    NovaPlaySetupRow.HOST_KEEP_IN_STEP -> 2
    else -> error("not a host row: $this")
}
