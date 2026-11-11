package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.api.DoctorAiDescriptor
import com.papi.nova.api.DoctorAiReading
import com.papi.nova.api.DoctorAiResponse
import com.papi.nova.api.DoctorAiScope
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Doctor details says under "Explain this reading with AI" for each reason the host gives
 * for refusing it (#476). Every refusal read "AI explanation is unavailable for this reading.",
 * which pointed at the reading; on papi's host the reason was that the PC's provider (OpenAI
 * through a Codex sign-in) is not offered to paired clients, while Mission Control's Explain
 * with AI works.
 *
 * The reasons are the ones Polaris 88030abf sends in the paired descriptor's blocked_reason
 * (src/doctor_ai_explanation.cpp, request_descriptor_json), in the host's own order. Each test
 * drives the real model with that descriptor and draws the details as the page does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h720dp")
class NovaDoctorAiCaptionComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun stop() = jobs.cancel()

    private fun text(id: Int) = rule.activity.getString(id)

    private val fingerprint = "a".repeat(64)
    private val scope = DoctorAiScope("session", 7, "doctor-network", fingerprint, 9)

    /** A fresh reading of a supported finding, with the host's descriptor as given. */
    private fun shown(reason: String, enabled: Boolean = true, permitted: Boolean = true, available: Boolean = false): NovaDoctorAiState {
        val model = NovaDoctorAi(jobs, now = { 1_000L }) { _, _ -> DoctorAiResponse() }
        val descriptor = DoctorAiDescriptor(enabled, permitted, available, reason, scope.sourceResultId,
            if (permitted) fingerprint else "", if (permitted) 9 else 0)
        model.observe(DoctorAiContext(Any(), Any(), DoctorAiReading(true, descriptor, scope, 1_000L)))
        model.pageVisible(true)
        return model.state.value
    }

    private fun draw(state: NovaDoctorAiState) {
        rule.setPanelContent { Column { NovaDoctorAiDetails(state, onExplain = {}) } }
        rule.frames(4)
    }

    /** The caption is [expected], and the button stays off. */
    private fun assertRefused(state: NovaDoctorAiState, expected: Int) {
        draw(state)
        rule.onNodeWithText(text(expected)).assertIsDisplayed()
        rule.onNodeWithTag("nova-doctor-ai-explain").assertIsNotEnabled()
        if (expected != R.string.nova_doctor_ai_unavailable) {
            rule.onNodeWithText(text(R.string.nova_doctor_ai_unavailable)).assertDoesNotExist()
        }
    }

    // The host's reasons, in its order.

    /** The host checks this first; it says so even where AI is also off. */
    @Test fun aStoppingStreamSaysSo() =
        assertRefused(shown("server_stopping", enabled = false), R.string.nova_doctor_ai_stopping)

    @Test fun aStreamInASpaceSaysSo() =
        assertRefused(shown("unsupported_scope", permitted = false), R.string.nova_doctor_ai_in_a_space)

    @Test fun aDeviceWithoutPermissionSaysSo() =
        assertRefused(shown("permission_required", permitted = false), R.string.nova_doctor_ai_not_allowed)

    @Test fun aDeviceThatIsNotTheOnlyOwnerSaysSo() =
        assertRefused(shown("active_owner_required", permitted = false), R.string.nova_doctor_ai_owner_only)

    @Test fun aHostWithAiOffSaysToEnableIt() =
        assertRefused(shown("host_ai_disabled", enabled = false), R.string.nova_doctor_ai_disabled)

    /** papi's host: OpenAI through a Codex sign-in, which the host offers only to Mission Control. */
    @Test fun aProviderNotOfferedToNovaNamesMissionControlsExplainWithAi() {
        val state = shown("provider_not_ready")
        assertRefused(state, R.string.nova_doctor_ai_provider_not_offered)
        val words = text(R.string.nova_doctor_ai_provider_not_offered)
        listOf("Mission Control", "Explain with AI").forEach { assertEquals("$it in: $words", true, words.contains(it)) }
    }

    @Test fun aReadingTheHostCannotExplainSaysSo() =
        assertRefused(shown("evidence_unavailable"), R.string.nova_doctor_ai_not_eligible)

    @Test fun aBusyHostSaysToTryAgain() =
        assertRefused(shown("request_busy"), R.string.nova_doctor_ai_busy)

    @Test fun aRateLimitedHostSaysToTryAgainLater() =
        assertRefused(shown("rate_limited"), R.string.nova_doctor_ai_rate_limited)

    // What is not a host refusal is as it was.

    @Test fun noReasonGivenIsTheGeneralCaption() =
        assertRefused(shown(""), R.string.nova_doctor_ai_unavailable)

    @Test fun anOfferedExplanationIsReadyAndItsButtonOn() {
        draw(shown("", available = true))
        rule.onNodeWithText(text(R.string.nova_doctor_ai_ready)).assertIsDisplayed()
        rule.onNodeWithTag("nova-doctor-ai-explain").assertIsEnabled()
    }
}
