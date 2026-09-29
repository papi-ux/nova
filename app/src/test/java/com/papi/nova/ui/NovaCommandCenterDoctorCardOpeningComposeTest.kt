package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
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
 * Review finding 1, across one opening, with real readings built through
 * [NovaQuickMenuUiState.from]. Any failed status read publishes no status, and the card went back
 * to its placeholder, or in a Space appeared at the top, and every row under it moved. A failed
 * read now keeps the last reading, a few seconds old, in the same place and with the same focus.
 * A host that is not Polaris, known when the Command Center opens, has no card for that whole
 * opening, even if a status arrives later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterDoctorCardOpeningComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun reading(cause: String, issue: String) = PolarisApiClient.parseSessionStatusResponse(
        JSONObject(
            """{"state":"streaming","streaming_active":true,"owned_by_client":true,"client_role":"owner",
            "viewer_count":0,"game":"Portal","app_session_id":"app-session-1","session_generation":41,
            "controls":{"host_tuning_allowed":true,"quit_allowed":true,"stop_allowed":true},
            "health":{"grade":"watch","summary":"$cause."},
            "doctor":{"version":2,"result_id":"doctor-$issue","status":"watch","severity":"warning",
            "traffic_light":"amber","primary_issue":"$issue","summary":"$cause",
            "evidence":[{"id":"$issue","status":"warning","source":"media_transport","value":18,
            "detail":"Jitter 18 ms over the last 10 s"}],"confidence":{"level":"medium"},
            "recommendation":{"body":"$cause. Move the device closer to the router."}}}"""
        )
    )

    private val jitter get() = reading("Network jitter is delaying frames", "network_jitter")

    /**
     * What the Command Center hands the page: the status it has now, none after a failed read,
     * the last one this opening got, and whether the host is Polaris.
     */
    private fun build(
        status: PolarisSessionStatus?,
        last: PolarisSessionStatus? = null,
        polaris: Boolean = true,
    ): NovaQuickMenuUiState = NovaQuickMenuUiState.from(
        context = rule.activity,
        status = status,
        apiAvailable = true,
        hostStateUnavailable = status == null && last != null,
        polarisHost = polaris,
        lastStatus = last,
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

    private fun open(first: NovaQuickMenuUiState) {
        state = MutableStateFlow(first)
        panel.open(CommandCenterPage.Root("Command Center"))
        rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    if (page is CommandCenterPage.Root) {
                        NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks(), place = NovaQuickMenuPlace())
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun show(next: NovaQuickMenuUiState) {
        state.value = next
        rule.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun card(finding: String): SemanticsNodeInteraction = rule.onNode(hasText(finding) and hasClickAction())

    private val hud get() = string(R.string.nova_quick_menu_nova_hud)
    private val streamCard get() = string(R.string.nova_quick_menu_stream_card)
    private val copies get() = string(R.string.nova_quick_menu_doctor_capability_manual)

    private fun top(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().top.value
    private fun height(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }

    @Test
    fun aFailedStatusReadKeepsTheLastReadingAFewSecondsOld() {
        open(build(jitter))
        val finding = card("Network jitter is delaying frames")
        finding.requestFocus()
        rule.waitForIdle()
        val cardTop = top(finding)
        val cardHeight = height(finding)
        val under = top(rule.onNodeWithText(streamCard))

        show(build(status = null, last = jitter))
        val kept = card("Network jitter is delaying frames")
        kept.assertIsFocused()
        kept.assertIsEnabled()
        kept.assert(hasText(string(R.string.nova_cc_doctor_stale, copies)))
        rule.onNode(hasText(string(R.string.nova_quick_menu_health_checking)) and hasClickAction()).assertDoesNotExist()
        assertEquals("in the same place", cardTop, top(kept), 0.5f)
        assertEquals("at the same size", cardHeight, height(kept), 0.5f)
        // The card is where it was and its size, so the card under it stays too. (That card
        // then grows by its own "host state unavailable" line; that is the Stream card's, not
        // the Doctor's.)
        assertEquals("the card under it stays", under, top(rule.onNodeWithText(streamCard)), 0.5f)

        show(build(jitter))
        card("Network jitter is delaying frames").assertIsFocused()
        rule.onNode(hasText(string(R.string.nova_cc_doctor_stale, copies))).assertDoesNotExist()
        assertEquals(under, top(rule.onNodeWithText(streamCard)), 0.5f)
    }

    @Test
    fun aFailedReadBeforeAnyReadingStaysChecking() {
        open(build(null))
        val checking = card(string(R.string.nova_quick_menu_health_checking))
        checking.requestFocus()
        rule.waitForIdle()
        val cardTop = top(checking)

        // The host did not answer: still checking, still focused, still where it was.
        show(build(status = null, last = null))
        card(string(R.string.nova_quick_menu_health_checking)).assertIsFocused()
        assertEquals(cardTop, top(card(string(R.string.nova_quick_menu_health_checking))), 0.5f)
    }

    @Test
    fun aHostThatIsNotPolarisHasNoCardForTheWholeOpening() {
        open(build(null, polaris = false))
        rule.onNode(hasTestTag("nova-cc-doctor")).assertDoesNotExist()
        rule.onNodeWithText(hud).requestFocus()
        rule.waitForIdle()
        val row = top(rule.onNodeWithText(hud))

        // A status arriving later does not bring the card in under the player.
        show(build(jitter, polaris = false))
        rule.onNode(hasTestTag("nova-cc-doctor")).assertDoesNotExist()
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals(row, top(rule.onNodeWithText(hud)), 0.5f)
    }

    @Test
    fun aPolarisHostHasTheCardFromTheFirstFrame() {
        open(build(null))
        rule.onNode(hasTestTag("nova-cc-doctor")).assertExists()
    }
}
