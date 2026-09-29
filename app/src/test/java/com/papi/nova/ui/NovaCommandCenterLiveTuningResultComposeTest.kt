package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.api.LiveTuningStatus
import com.papi.nova.api.PolarisApiClient
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
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review findings 5 and 7, through the Command Center's own save ([NovaQuickMenu.liveTuningSave])
 * against a fake host behind the client's API. A Live Tuning switch the host did not confirm said
 * "Try again" beside a chip that could already show the state asked for; it was silent for
 * TalkBack; a status refresh that failed too left only "Reconnecting" and disabled the row, so
 * focus left it as the failure arrived. The row now says the state the host reports, which its
 * chip shows, offers nothing that undoes the change, keeps focus, and lets the line go after its
 * time. The save asks the host for the state the split offered, even when another device switched
 * it while the split was armed; a flip of the host's state turned Turn Off into On.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterLiveTuningResultComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()

    // The fake host: Live Tuning On or Off, whether it takes a save, and whether it answers.
    private var hostOn = true
    private var confirms = false
    private var answers = true
    private var afterSave: (Boolean) -> Unit = {}
    /** What the host was asked for, in order, and the status each ask was made against. */
    private val sent = mutableListOf<Pair<Boolean, PolarisSessionStatus>>()
    /** The client's copy of the host's status, which a status read replaces, as PolarisApiClient's does. */
    private var store: PolarisSessionStatus? = null

    // The page: the status it was last handed, and whether the host answered.
    private var shown: PolarisSessionStatus? = null
    private var unavailable = false
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private lateinit var save: NovaLiveTuningSave
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun live(name: String): LiveTuningStatus {
        val fixtures = org.json.JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText())
        return (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
            .first { it.getString("name") == name }
            .let { LiveTuningStatus.parse(it.getJSONObject("live_tuning"))!! }
    }

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

    @Suppress("UNCHECKED_CAST")
    private val api: PolarisApiClient = Mockito.mock(PolarisApiClient::class.java, Answer<Any?> { call ->
        when (call.method.name) {
            "setLiveTuningEnabled" -> {
                val enable = call.arguments[0] as Boolean
                sent += enable to call.arguments[1] as PolarisSessionStatus
                if (confirms) hostOn = enable
                afterSave(enable)
                confirms
            }
            "getSessionStatus" -> host().also { store = it }
            "withCurrentSessionStatus" -> (call.arguments[0] as (PolarisSessionStatus?) -> Any?)(store)
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
    })

    /** Runs at once on the test's thread, and keeps what waits for later with its delay. */
    private val runtime = object : NovaCommandCenterRuntime {
        override fun launchIo(name: String, block: suspend () -> Unit) = runBlocking { block() }
        override suspend fun onMain(block: () -> Unit) = block()
        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            timers += delayMs to block
        }
    }

    private fun build(): NovaQuickMenuUiState = NovaQuickMenuUiState.from(
        context = rule.activity,
        status = shown,
        apiAvailable = true,
        hostStateUnavailable = unavailable,
        liveTuningPending = save.pending,
        liveTuningUnconfirmed = save.unconfirmed,
        adaptiveSupported = true,
        aiSupported = true,
        adaptiveEnabled = shown?.liveTuning?.enabled == true,
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

    /** What the Command Center does with a status read: hands it to the page, and says whether there was one. */
    private fun publish(): Boolean = api.withCurrentSessionStatus { current ->
        shown = current
        current != null
    }

    private fun open(): NovaTestKeys {
        store = host()
        shown = store
        save = NovaQuickMenu.liveTuningSave(
            runtime = runtime,
            api = api,
            current = { true },
            publish = ::publish,
            answered = { unavailable = !it },
            changed = { state.value = build() },
        )
        state = MutableStateFlow(build())
        // As the Command Center's onLiveTuning: the state the split offered, against the status shown.
        val callbacks = NovaQuickMenuCallbacks(
            onLiveTuning = { enable ->
                val observed = shown
                if (observed?.canAdjustHostTuning == true && !unavailable) save.request(enable, observed)
            },
        )
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

    // The row itself: the Sync card's caption can name Live Tuning too.
    private val row = hasAnyAncestor(hasTestTag("nova-cc-live-tuning")) and hasClickAction()

    /** A, Right, A on the Live Tuning row, past the split's guard; [armed] happens while it is armed. */
    private fun switchLiveTuning(keys: NovaTestKeys, armed: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.onNode(row).requestFocus()
        rule.frames(2)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        armed()
        rule.frames(2)
        keys.press(NovaTestKeys.RIGHT)
        rule.advance(450)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    private fun string(id: Int) = rule.activity.getString(id)

    /** The caption's own Text announces it, and the row carries no description to say it twice. */
    private fun assertAnnounced(caption: String) {
        rule.onNodeWithText(caption, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText(caption).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun aSwitchTheHostKeptIsSaidOnTheFocusedRowThenGoesAfterItsTime() {
        val keys = open()
        switchLiveTuning(keys)

        assertEquals("the split offered Off and the host was asked for Off", listOf(false), sent.map { it.first })
        val kept = string(R.string.nova_cc_live_tuning_kept_on)
        rule.onNodeWithText(kept)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.nova_quick_menu_on)))
        rule.onNodeWithText("Try again", substring = true).assertDoesNotExist()
        assertAnnounced(kept)
        rule.onNode(row).assertIsFocused()

        assertEquals("one result, waiting its time", listOf(NovaLiveTuningSave.SHOWN_MS), timers.map { it.first })
        rule.onNodeWithText(kept).assertExists()
        timers.toList().forEach { it.second() }
        rule.waitForIdle()
        rule.onNodeWithText(kept).assertDoesNotExist()
        rule.onNodeWithText("Steady. 20 Mbps applied, 20 Mbps limit.").assertExists()
        rule.onNode(row).assertIsFocused()
    }

    @Test
    fun aRefreshThatFailedTooStillShowsTheFailureOnTheFocusedRow() {
        afterSave = { answers = false }
        val keys = open()
        switchLiveTuning(keys)

        val unanswered = string(R.string.nova_cc_live_tuning_unconfirmed)
        rule.onNodeWithText(unanswered).assertExists()
        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_reconnecting)).assertDoesNotExist()
        assertAnnounced(unanswered)
        rule.onNode(row).assertIsFocused().assertIsEnabled()
    }

    @Test
    fun aSwitchTheHostAppliedButDidNotConfirmSaysNothingWrong() {
        // The host applied Off and its answer was lost: the chip says Off, and so does the row.
        afterSave = { enable -> hostOn = enable }
        val keys = open()
        switchLiveTuning(keys)

        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_kept_on)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_kept_off)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.nova_cc_live_tuning_off_caption)).assertExists()
    }

    /**
     * Review finding 7's gap: the call that reaches the host. Armed at On, the split offers Turn
     * Off; another device turns Live Tuning off before the confirm. The host is asked for Off, the
     * state offered, against the status that says Off; a flip of that status asked for On.
     */
    @Test
    fun theHostIsAskedForTheStateTheSplitOfferedAfterAnotherDeviceSwitchedIt() {
        confirms = true
        val keys = open()
        switchLiveTuning(keys) {
            hostOn = false
            store = host()
            publish()
            state.value = build()
        }

        assertEquals(listOf(false), sent.map { it.first })
        assertEquals("asked against the status that said Off", false, sent.single().second.liveTuning?.enabled)
    }
}
