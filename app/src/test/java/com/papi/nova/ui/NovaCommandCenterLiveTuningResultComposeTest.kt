package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.api.LiveTuningStatus
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 5: a Live Tuning switch the host did not confirm said "The host did not confirm
 * the change. Try again." beside a chip that could already show the state asked for, and trying
 * again then offered the switch back. It was silent for TalkBack, and a status refresh that failed
 * too left only "Reconnecting". The row now says the state the host reports, which its chip
 * shows, and offers nothing that undoes the change; the line is a polite live region; it goes
 * after its time. The page is wired as the Command Center wires it: the split's confirm, the save,
 * the host's state, and the row built from them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterLiveTuningResultComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private val sent = mutableListOf<Boolean>()
    private val timers = mutableListOf<() -> Unit>()
    private var hostOn = true
    private var answers = true
    private lateinit var afterSave: (Boolean) -> Unit
    private lateinit var save: NovaLiveTuningSave
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun live(name: String): LiveTuningStatus {
        val fixtures = org.json.JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText())
        return (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
            .first { it.getString("name") == name }
            .let { LiveTuningStatus.parse(it.getJSONObject("live_tuning"))!! }
    }

    /** What the host reports: Live Tuning On or Off, or nothing when it does not answer. */
    private fun host(): PolarisSessionStatus? = if (!answers) null else PolarisSessionStatus(
        state = "streaming",
        streamingActive = true,
        game = "Portal",
        gameUuid = "game-1",
        ownedByClient = true,
        controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = true, quitAllowed = true),
        liveTuning = live(if (hostOn) "stable" else "off"),
        liveTuningPresent = true,
    )

    private fun build(): NovaQuickMenuUiState = NovaQuickMenuUiState.from(
        context = rule.activity,
        status = host(),
        apiAvailable = true,
        hostStateUnavailable = !answers,
        liveTuningPending = save.pending,
        liveTuningUnconfirmed = save.unconfirmed,
        adaptiveSupported = true,
        aiSupported = true,
        adaptiveEnabled = hostOn,
        aiEnabled = false,
        mangoHudEnabled = false,
        stabilityApplied = false,
        advancedExpanded = false,
        profileClearInProgress = false,
        currentGameName = "Portal",
        currentGameUuid = "game-1",
        profilePreference = "auto",
        hudShowing = false,
        perfOverlayEnabled = false,
        onscreenControllerEnabled = false,
        keyboardVisible = false,
        mouseModeLabel = "",
        allowChangeMouseMode = true,
        isOnExternalDisplay = false,
        fallbackBitrateKbps = 20000,
        fallbackTargetFps = 60.0,
    )

    /** The host takes [confirms] saves; [afterSave] is what happens on it meanwhile. */
    private fun open(confirms: Boolean, afterSave: (Boolean) -> Unit = {}): NovaTestKeys {
        this.afterSave = afterSave
        save = NovaLiveTuningSave(
            launch = { block -> runBlocking { block() } },
            onMain = { block -> block() },
            later = { _, block -> timers += block },
            save = { enable, _ ->
                sent += enable
                if (confirms) hostOn = enable
                this.afterSave(enable)
                confirms
            },
            fetch = {},
            publish = {},
            changed = { state.value = build() },
        )
        state = MutableStateFlow(build())
        val callbacks = NovaQuickMenuCallbacks(onLiveTuning = { enable -> host()?.let { save.request(enable, it) } })
        panel.open(CommandCenterPage.Root("Command Center"))
        val keys = rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    if (page is CommandCenterPage.Root) NovaQuickMenuContent(state = state, callbacks = callbacks)
                }
            }
        }
        rule.waitForIdle()
        return keys
    }

    /** A, Right, A on the Live Tuning row, past the split's guard. */
    private fun switchLiveTuning(keys: NovaTestKeys) {
        rule.mainClock.autoAdvance = false
        // The row itself: the Sync card's caption can name Live Tuning too.
        rule.onNode(hasAnyAncestor(hasTestTag("nova-cc-live-tuning")) and hasClickAction()).requestFocus()
        rule.frames(2)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        keys.press(NovaTestKeys.RIGHT)
        rule.advance(450)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private fun assertAnnounced(caption: String) {
        rule.onNodeWithText(caption)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(hasContentDescription(caption, substring = true))
    }

    @Test
    fun aSwitchTheHostKeptIsSaidOnTheRowThenClears() {
        val keys = open(confirms = false)
        switchLiveTuning(keys)

        assertEquals("the split offered Off and the save asked for Off", listOf(false), sent)
        val kept = string(R.string.nova_cc_live_tuning_kept_on)
        rule.onNodeWithText(kept)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.nova_quick_menu_on)))
        rule.onNodeWithText("Try again", substring = true).assertDoesNotExist()
        assertAnnounced(kept)

        timers.toList().forEach { it() }
        rule.waitForIdle()
        rule.onNodeWithText(kept).assertDoesNotExist()
        rule.onNodeWithText("Steady. 20 Mbps applied, 20 Mbps limit.").assertExists()
    }

    @Test
    fun aSwitchWhoseStatusRefreshFailedTooStillSaysSo() {
        val keys = open(confirms = false, afterSave = { answers = false })
        switchLiveTuning(keys)

        val unanswered = string(R.string.nova_cc_live_tuning_unconfirmed)
        rule.onNodeWithText(unanswered).assertExists()
        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_reconnecting)).assertDoesNotExist()
        assertAnnounced(unanswered)
    }

    @Test
    fun aSwitchTheHostAppliedButDidNotConfirmSaysNothingWrong() {
        // The host applied Off and its answer was lost: the chip says Off, and so does the row.
        val keys = open(confirms = false, afterSave = { enable -> hostOn = enable })
        switchLiveTuning(keys)

        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_kept_on)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_kept_off)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_off_caption)).assertExists()
    }
}
