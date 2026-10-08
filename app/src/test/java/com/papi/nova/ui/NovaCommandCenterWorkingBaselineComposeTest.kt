package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaMomentReport
import com.papi.nova.diagnostics.NovaWorkingBaseline
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow.Outcome
import com.papi.nova.diagnostics.NovaWorkingBaselineStore
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.NovaSurfacesWindowContent
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.NovaWindowPlacement
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Working Baseline in the Command Center: its session row, the page it opens, the confirmation
 * that is the user's own statement (Keep Playing focused, B keeps everything as it was), and
 * Compare, which changes nothing. Report This Moment's row stays where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaCommandCenterWorkingBaselineComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var surfaces: NovaSurfaces
    private val pressed = ArrayList<NovaQuickMenuActionId>()

    @After
    fun tearDown() {
        if (::surfaces.isInitialized) rule.runOnUiThread { surfaces.dispose() }
    }

    private val prefs by lazy {
        rule.activity.getSharedPreferences(NovaWorkingBaselineStore.FILE, Context.MODE_PRIVATE)
    }
    private var active = true
    private var liveFps = 59.8
    private var snapshots = 0
    private val outcomes = ArrayList<Outcome>()
    private var redraws = 0
    private val state = NovaPanelState()

    private fun snapshot(): NovaMomentReport.Snapshot? {
        snapshots++
        if (!active) return null
        return NovaMomentReport.capture(
            markedAtUtc = "2026-10-08T13:00:00Z", nowElapsedMs = 10_000L, novaVersion = "1.4.15", buildType = "release",
            androidRelease = "15", androidSdk = 35, device = "Google Pixel 9", watching = false, space = false,
            requestedWidth = 1920, requestedHeight = 1080, requestedFps = 60f, requestedBitrateKbps = 20_000,
            requestedCodec = "FORCE_HEVC",
            sample = NovaMomentReport.Sample("c2.qti.hevc.decoder", 1920, 1080, liveFps, 60.0, 3.0, 4, 1, 0.5, 2.0,
                120_000, 12, 9_800L),
        )
    }

    private val flow by lazy {
        NovaWorkingBaselineFlow(
            store = NovaWorkingBaselineStore(prefs),
            entry = { NovaWorkingBaseline.RequestedEntry.of("pc-entry", "app-entry") },
            isActive = { active },
            snapshot = ::snapshot,
        )
    }

    private fun pages() = NovaWorkingBaselinePages(
        context = rule.activity,
        flow = flow,
        feedback = { outcomes += it },
        reopen = { page -> if (state.contains(page.key)) state.replaceTop(page) },
        redraw = { redraws++ },
    )

    private class Root : NovaPage {
        override val key = "root"
        override val title = "Command Center"
    }

    private fun openPage(): NovaTestKeys {
        state.open(Root())
        state.push(pages().menu())
        return rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = {}) {
                NovaRow(title = "Command Center", onClick = {}, modifier = Modifier.novaInitialFocus())
            }
        }
    }

    private fun text(id: Int) = rule.activity.getString(id)

    @Test fun theRowSitsRightAfterReportThisMomentAndReachesTheSessionAction() {
        val game = Mockito.mock(Game::class.java)
        Mockito.`when`(game.isFinishing).thenReturn(true)
        surfaces = NovaSurfaces(NovaWindowPlacement.Stream(game))
        val preview = NovaQuickMenuUiState.preview(rule.activity)
        assertEquals(
            listOf(NovaQuickMenuActionId.REPORT_MOMENT, NovaQuickMenuActionId.WORKING_BASELINE),
            preview.sessionRows.take(2).map { it.id })
        rule.setPanelContent { NovaSurfacesWindowContent(surfaces) }
        rule.runOnUiThread {
            surfaces.open(CommandCenterPage.Root("Command Center"), NovaEdge.Start) { page ->
                if (page is CommandCenterPage.Root) NovaQuickMenuContent(
                    state = MutableStateFlow(preview),
                    callbacks = NovaQuickMenuCallbacks(onSessionAction = { pressed += it }),
                )
            }
        }
        rule.waitForIdle()
        val row = rule.onNodeWithTag(WORKING_BASELINE_TAG, useUnmergedTree = true).performScrollTo()
        row.assertIsEnabled()
        val description = row.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, description.startsWith(text(R.string.nova_working_baseline_action)))
        row.performClick()
        rule.waitForIdle()
        assertEquals(listOf(NovaQuickMenuActionId.WORKING_BASELINE), pressed)
        // Report This Moment's row is unchanged beside it.
        rule.onNodeWithTag(REPORT_MOMENT_TAG, useUnmergedTree = true).performScrollTo().assertIsEnabled()
    }

    @Test fun theRowSaysWhatIsSavedAndIsUnavailableWithoutARequestedEntry() {
        val context = rule.activity
        val none = NovaQuickMenuUiState.workingBaselineAction(context, NovaWorkingBaselineRow(hasEntry = true))
        assertTrue(none.enabled)
        assertEquals(text(R.string.nova_working_baseline_none_caption), none.caption)
        val saved = NovaQuickMenuUiState.workingBaselineAction(context,
            NovaWorkingBaselineRow(hasEntry = true, savedAtUtc = "2026-10-08T13:00:00Z"))
        assertTrue(saved.caption, saved.caption.contains("2026-10-08T13:00:00Z"))
        val unreadable = NovaQuickMenuUiState.workingBaselineAction(context, NovaWorkingBaselineRow(hasEntry = true, unreadable = true))
        assertEquals(text(R.string.nova_working_baseline_unreadable_caption), unreadable.caption)
        val noEntry = NovaQuickMenuUiState.workingBaselineAction(context, NovaWorkingBaselineRow(hasEntry = false))
        assertFalse(noEntry.enabled)
        assertEquals(text(R.string.nova_working_baseline_no_entry_caption), noEntry.caption)
    }

    @Test fun aFirstSaveAsksFirstWithKeepPlayingFocusedAndBSavesNothing() {
        val keys = openPage()
        rule.onNodeWithText(text(R.string.nova_working_baseline_mark)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.nova_working_baseline_confirm_save_title)).assertExists()
        rule.onNodeWithText(text(R.string.nova_working_baseline_cancel)).assertIsFocused()
        keys.back()
        rule.waitForIdle()
        assertEquals(0, snapshots)
        assertTrue(outcomes.isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun keepPlayingCancelsAndSaveFreezesTheReadingsAtItsOwnPress() {
        openPage()
        rule.onNodeWithText(text(R.string.nova_working_baseline_mark)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.nova_working_baseline_cancel)).performClick()
        rule.waitForIdle()
        assertEquals(0, snapshots)
        assertTrue(prefs.all.isEmpty())

        rule.onNodeWithText(text(R.string.nova_working_baseline_mark)).performClick()
        rule.waitForIdle()
        liveFps = 33.0 // the stream changes while the confirmation is open
        rule.onNodeWithText(text(R.string.nova_working_baseline_save)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(Outcome.SAVED), outcomes)
        assertEquals(1, snapshots)
        assertEquals(33.0, flow.state().saved!!.readings.renderedFps!!, 0.0)
        assertEquals(1, redraws)
        // The page is read again in place: it now offers Compare and Replace.
        rule.onNodeWithText(text(R.string.nova_working_baseline_compare)).assertExists()
        rule.onNodeWithText(text(R.string.nova_working_baseline_replace)).assertExists()
    }

    @Test fun aReplacementAlsoAsksAndNamesTheBaselineItReplaces() {
        assertEquals(Outcome.SAVED, flow.save())
        val confirm = pages().confirmation(flow.state().saved, replacing = true)
        assertEquals(text(R.string.nova_working_baseline_confirm_replace_title), confirm.title)
        assertEquals(text(R.string.nova_working_baseline_replace_action), confirm.actionLabel)
        assertEquals(text(R.string.nova_working_baseline_cancel), confirm.stayLabel)
        assertTrue(confirm.message.text, confirm.message.text.contains("2026-10-08T13:00:00Z"))
        assertFalse(confirm.destructive)
        liveFps = 50.0
        confirm.onConfirm()
        assertEquals(listOf(Outcome.REPLACED), outcomes)
        assertEquals(50.0, flow.state().saved!!.readings.renderedFps!!, 0.0)
    }

    @Test fun aStreamThatStopsWhileTheConfirmationIsOpenSavesNothing() {
        val confirm = pages().confirmation(null, replacing = false)
        active = false
        confirm.onConfirm()
        assertEquals(listOf(Outcome.NOT_ACTIVE), outcomes)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun compareShowsBothSidesWithTheirScopesAndChangesNothing() {
        flow.save()
        val before = prefs.all
        liveFps = 41.0
        val notice = pages().comparison()
        assertEquals(before, prefs.all)
        val message = notice.message
        assertTrue(message, message.contains("59.8 fps → 41.0 fps"))
        assertTrue(message, message.contains(text(R.string.nova_working_baseline_reading_frame_loss)))
        assertTrue(message, message.contains(text(R.string.nova_working_baseline_not_compared)))
        assertTrue(message, message.contains(text(R.string.nova_working_baseline_context_all_same)))
        listOf("better", "worse", "regression", "healthy", "like for like", "same scene").forEach {
            assertFalse("$it in $message", message.contains(it, ignoreCase = true))
        }
        assertTrue(notice.monospace)
    }

    @Test fun compareWithACorruptBaselineSaysSoAndLeavesItForSaveOrClear() {
        flow.save()
        val name = prefs.all.keys.single { it.startsWith(NovaWorkingBaselineStore.ENTRY_PREFIX) }
        prefs.edit().putString(name, "{}").commit()
        val before = prefs.all
        assertEquals(text(R.string.nova_working_baseline_compare_unreadable), pages().comparison().message)
        assertEquals(before, prefs.all)
        val menu = pages().menu()
        assertTrue(menu.items.none { it.key == NovaWorkingBaselinePages.COMPARE })
        assertTrue(menu.items.any { it.key == NovaWorkingBaselinePages.CLEAR })
        assertEquals(text(R.string.nova_working_baseline_replace), (menu.items.single { it.key == NovaWorkingBaselinePages.SAVE } as NovaMenuItem.Opens).label)
    }

    @Test fun withNoStreamUpOnlyClearIsOffered() {
        flow.save()
        active = false
        val menu = pages().menu()
        val compare = menu.items.single { it.key == NovaWorkingBaselinePages.COMPARE } as NovaMenuItem.Action
        val save = menu.items.single { it.key == NovaWorkingBaselinePages.SAVE } as NovaMenuItem.Action
        assertEquals(text(R.string.nova_working_baseline_needs_stream), compare.disabledReason)
        assertEquals(text(R.string.nova_working_baseline_needs_stream), save.disabledReason)
        val clear = menu.items.single { it.key == NovaWorkingBaselinePages.CLEAR } as NovaMenuItem.Destructive
        assertEquals(text(R.string.nova_working_baseline_keep), clear.stayLabel)
        clear.onConfirm()
        assertEquals(listOf(Outcome.CLEARED), outcomes)
        assertEquals(NovaWorkingBaselineStore.Loaded.None, flow.state().loaded)
    }

    @Test fun theMenuSaysTheGroupingIsNotProofOfTheSameSceneHostOrPath() {
        val header = pages().menu().header!!
        assertEquals(text(R.string.nova_working_baseline_status_none), header.status)
        assertTrue(header.hint!!.contains("isn't proof"))
        val confirm: NovaCommonPage.Confirm = pages().confirmation(null, replacing = false)
        assertTrue(confirm.message.text.contains("picture, audio and controls"))
    }
}
