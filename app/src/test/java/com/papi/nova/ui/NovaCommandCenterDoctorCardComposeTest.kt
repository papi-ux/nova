package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 1, with real readings built through [NovaQuickMenuUiState.from]: the Doctor card
 * keeps its one place under the strip for a Polaris host, the same size whatever it holds, so a
 * reading landing or flipping never moves a row under the player, and focus stays where it is.
 * Before the first reading it says it is checking, and why A does nothing yet, and it keeps focus
 * though it looks disabled; A does nothing there, and the hint bar offers none. A Space shows its
 * own verdict. While the strip warns, the card says what the strip says, never "Nothing to fix"
 * under a warning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterDoctorCardComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun session(extra: String) = PolarisApiClient.parseSessionStatusResponse(
        JSONObject(
            """{"state":"streaming","streaming_active":true,"owned_by_client":true,"client_role":"owner",
            "viewer_count":0,"game":"Portal","app_session_id":"app-session-1","session_generation":41,
            "controls":{"host_tuning_allowed":true,"quit_allowed":true,"stop_allowed":true},$extra}"""
        )
    )

    /** A network reading with a first try, evidence and a confidence, and nothing Nova can run. */
    private val jitter get() = session(
        """"health":{"grade":"watch","summary":"Network jitter is delaying frames."},
        "doctor":{"version":2,"result_id":"doctor-net-1","status":"watch","severity":"warning",
        "traffic_light":"amber","primary_issue":"network_jitter","summary":"Network jitter is delaying frames",
        "evidence":[{"id":"stream_active","status":"pass","source":"session","detail":"A stream is active"},
        {"id":"network_jitter","status":"warning","source":"media_transport","value":18,
        "detail":"Jitter 18 ms over the last 10 s"}],"confidence":{"level":"medium"},
        "recommendation":{"body":"Network jitter is delaying frames. Move the device closer to the router."}}"""
    )

    /** [jitter] with advice on two lines, as a host's can be. */
    private val twoLines get() = session(
        """"health":{"grade":"watch","summary":"Network jitter is delaying frames."},
        "doctor":{"version":2,"result_id":"doctor-net-2","status":"watch","severity":"warning",
        "traffic_light":"amber","primary_issue":"network_jitter","summary":"Network jitter is delaying frames",
        "evidence":[{"id":"network_jitter","status":"warning","source":"media_transport","value":18,
        "detail":"Jitter 18 ms over the last 10 s"}],"confidence":{"level":"medium"},
        "recommendation":{"body":"Network jitter is delaying frames. Move the device closer to the router.\nUse 5 GHz, or a cable for the host."}}"""
    )

    /** N28's observation: the strip warns about it, and there is nothing to run. */
    private val observation get() = session(
        """"health":{"grade":"watch","summary":"Control-channel retries were observed."},
        "doctor":{"version":2,"result_id":"doctor-obs-1","status":"watch","severity":"warning",
        "traffic_light":"amber","primary_issue":"control_channel_observation",
        "summary":"Control-channel retries were observed, but video packet loss is not confirmed"}"""
    )

    /** A healthy reading: the strip does not warn. */
    private val healthy get() = session(
        """"health":{"grade":"good","summary":"Streaming telemetry looks ready."},
        "doctor":{"version":2,"result_id":"doctor-ok-1","status":"ok","severity":"info","traffic_light":"green",
        "primary_issue":"none","summary":"Streaming telemetry looks ready"}"""
    )

    /** What Polaris sends for a Space: a health summary, no Doctor object (nvhttp.cpp profile_session_status). */
    private val space get() = PolarisApiClient.parseSessionStatusResponse(
        JSONObject(
            """{"source":"worker_profile_v1","state":"streaming","streaming_active":true,
            "owned_by_client":true,"client_role":"owner","viewer_count":0,"game":"papi - heroic",
            "controls":{"host_tuning_allowed":false,"quit_allowed":true,"stop_allowed":true},
            "display_mode":{"selection":"gamescope_stream","label":"papi - heroic"},
            "encoder":{"codec":"h264","bitrate_kbps":0,"bitrate_ceiling_kbps":8000,"session_target_fps":120},
            "health":{"grade":"unknown","summary":"Profile performance diagnostics are not available yet."},
            "live_tuning":null}"""
        )
    )

    private fun build(status: PolarisSessionStatus?): NovaQuickMenuUiState = NovaQuickMenuUiState.from(
        context = rule.activity,
        status = status,
        apiAvailable = true,
        adaptiveSupported = true,
        aiSupported = true,
        adaptiveEnabled = false,
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

    /** The root page, [width] wide when given, as a panel is, and the window's width otherwise. */
    private fun open(
        first: NovaQuickMenuUiState,
        callbacks: NovaQuickMenuCallbacks = NovaQuickMenuCallbacks(),
        width: Dp? = null,
    ): NovaTestKeys {
        state = MutableStateFlow(first)
        panel.open(CommandCenterPage.Root("Command Center"))
        val keys = rule.setPanelContent {
            Box(if (width != null) Modifier.width(width).fillMaxHeight() else Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    if (page is CommandCenterPage.Root) {
                        NovaQuickMenuContent(state = state, callbacks = callbacks, place = NovaQuickMenuPlace())
                    }
                }
            }
        }
        rule.waitForIdle()
        return keys
    }

    private fun show(next: NovaQuickMenuUiState) {
        state.value = next
        rule.waitForIdle()
    }

    @Test
    fun aReceiptKeepsItsFocusAndHeightWhenAReadFails() {
        val initial = build(healthy).copy(doctorReceiptAction = NovaQuickMenuAction(
            NovaQuickMenuActionId.DOCTOR_UNDO, "Undo Doctor", caption = "Bitrate lowered.",
            chip = NovaQuickMenuChip("Verified", NovaQuickMenuTone.ACTIVE)
        ))
        var undos = 0
        val keys = open(initial, NovaQuickMenuCallbacks(onDoctorUndo = { undos++ }))
        rule.onNodeWithText("Undo Doctor").requestFocus()
        val before = place(rule.onNodeWithText("Undo Doctor"))
        show(initial.copy(doctorReceiptAction = initial.doctorReceiptAction.copy(
            label = "Doctor receipt", enabled = false,
            caption = "Last confirmed: Bitrate lowered. Waiting for the host."
        )))
        val receipt = rule.onNodeWithText("Doctor receipt")
        receipt.assertIsFocused()
        receipt.assertIsNotEnabled()
        assertSamePlace("receipt through a failed read", before, place(receipt))
        keys.press(NovaTestKeys.A)
        assertEquals(0, undos)
    }

    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    /** The Doctor card, by its finding: the strip can say the same words, and it is not clickable. */
    private fun card(finding: String): SemanticsNodeInteraction = rule.onNode(hasText(finding) and hasClickAction())

    private val hud get() = string(R.string.nova_quick_menu_nova_hud)
    private val copies get() = string(R.string.nova_quick_menu_doctor_capability_manual)
    // The card's own words, written out: what A does before the first reading, and the strip's
    // words ahead of what A does.
    private val checkingWhy = "Nothing to copy until the host answers"
    private fun stripSays(verdict: String) = "$verdict · $copies"

    private fun place(node: SemanticsNodeInteraction): Rect = node.getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun assertSamePlace(message: String, before: Rect, now: Rect) {
        assertEquals("$message: top", before.top, now.top, 0.5f)
        assertEquals("$message: height", before.height, now.height, 0.5f)
    }

    /** How many lines the Text that says [words] takes. */
    private fun lines(words: String): Int {
        val node = rule.onNode(hasText(words, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    @Test
    fun theFirstReadingLandsInTheCheckingCardsPlaceAndKeepsItsFocus() {
        open(build(null))
        val checking = card(string(R.string.nova_quick_menu_health_checking))
        checking.assert(hasText(checkingWhy))
        checking.assertIsNotEnabled()
        checking.requestFocus()
        rule.waitForIdle()
        checking.assertIsFocused()
        val before = place(checking)
        val row = place(rule.onNodeWithText(hud))

        show(build(jitter))
        val reading = card("Network jitter is delaying frames")
        reading.assertIsFocused()
        reading.assertIsEnabled()
        assertSamePlace("the reading takes the checking card's place and size", before, place(reading))
        assertSamePlace("nothing under it moves", row, place(rule.onNodeWithText(hud)))
    }

    /**
     * The checking card keeps focus though it looks disabled, and says why A does nothing yet. A
     * there does nothing: it had copied the diagnostics and shown Copied under "Nothing to copy
     * until the host answers". The hint bar offers no A on it. Once the reading lands, A copies
     * it and the hint bar offers A again.
     */
    @Test
    fun aOnTheCheckingCardDoesNothingAndTheHintBarOffersNoA() {
        val pressed = mutableListOf<NovaQuickMenuActionId>()
        val keys = open(build(null), NovaQuickMenuCallbacks(onOverlayAction = { pressed += it }))
        val select = string(R.string.nova_panel_select)
        val checking = card(string(R.string.nova_quick_menu_health_checking))
        checking.assert(hasText(checkingWhy))
        checking.requestFocus()
        rule.waitForIdle()
        checking.assertIsFocused()
        rule.onNodeWithText(select).assertDoesNotExist()

        keys.press(NovaTestKeys.A)
        keys.press(NovaTestKeys.CENTER)
        assertEquals("A on the checking card", emptyList<NovaQuickMenuActionId>(), pressed.toList())
        checking.assertIsFocused()

        show(build(jitter))
        card("Network jitter is delaying frames").assertIsFocused()
        rule.onNodeWithText(select).assertExists()
        keys.press(NovaTestKeys.A)
        assertEquals(listOf(NovaQuickMenuActionId.DIAGNOSE_STREAM), pressed.toList())
    }

    /**
     * On a handheld, at the Command Center's width on the RP6, a reading's details take the two
     * lines the card keeps for them where they wrap. The checking card, which has no details,
     * keeps both lines as well, so the reading landing moves nothing. Robolectric lays any text on
     * one line whatever its width, so the details take their second line from a line break in the
     * host's advice, where a device would wrap them.
     */
    @Test
    @Config(qualifiers = "w833dp-h468dp")
    fun onAHandheldDetailsOnTwoLinesLandInTheCheckingCardsPlace() {
        open(build(null), width = NovaPanelMetrics.panelWidth(NovaPanelWidth.Standard, 833.dp))
        val checking = card(string(R.string.nova_quick_menu_health_checking))
        val before = place(checking)
        val row = place(rule.onNodeWithText(hud))

        show(build(twoLines))
        assertEquals("the reading's details take the card's two lines", 2, lines("Move the device closer to the router."))
        assertSamePlace("the reading takes the checking card's place and size", before, place(card("Network jitter is delaying frames")))
        assertSamePlace("nothing under it moves", row, place(rule.onNodeWithText(hud)))
    }

    @Test
    fun realReadingsFlippingMoveNothingUnderThePlayer() {
        open(build(jitter))
        rule.onNodeWithText(hud).requestFocus()
        rule.waitForIdle()
        val row = place(rule.onNodeWithText(hud))
        val first = place(card("Network jitter is delaying frames"))

        show(build(observation))
        rule.onNodeWithText(hud).assertIsFocused()
        assertSamePlace("an observation", first, place(card("Control-channel retries were observed, but video packet loss is not confirmed")))
        assertSamePlace("the focused row", row, place(rule.onNodeWithText(hud)))

        show(build(healthy))
        rule.onNodeWithText(hud).assertIsFocused()
        assertSamePlace("a healthy reading", first, place(card("Streaming telemetry looks ready")))
        assertSamePlace("the focused row", row, place(rule.onNodeWithText(hud)))
    }

    @Test
    fun aSpaceShowsItsOwnVerdictInTheCard() {
        open(build(space))
        val verdict = card("Profile performance diagnostics are not available yet")
        verdict.assert(hasText(string(R.string.nova_cc_doctor_nothing_to_fix, copies)))
        verdict.requestFocus()
        rule.waitForIdle()
        verdict.assertIsFocused()
    }

    @Test
    fun underAWarningStripTheCardSaysWhatTheStripSays() {
        val warned = build(observation)
        assertEquals("the strip warns about this observation", NovaQuickMenuTone.WARNING, warned.healthTone)
        open(warned)
        val finding = card("Control-channel retries were observed, but video packet loss is not confirmed")
        finding.assert(hasText(stripSays(warned.healthSummary.trimEnd('.'))))
        rule.onNodeWithText(string(R.string.nova_cc_doctor_nothing_to_fix, copies)).assertDoesNotExist()

        show(build(healthy))
        card("Streaming telemetry looks ready").assert(hasText(string(R.string.nova_cc_doctor_nothing_to_fix, copies)))
    }
}
