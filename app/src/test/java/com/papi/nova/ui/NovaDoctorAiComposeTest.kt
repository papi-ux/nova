package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.papi.nova.api.*
import com.papi.nova.ui.panel.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h720dp")
class NovaDoctorAiComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun actualDetailsButtonSendsNothingUntilClickAndPreservesDeterministicCardOnRefusal() {
        val panel = NovaPanelState()
        val state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity).copy(doctorAi = NovaDoctorAiState(true, NovaDoctorAiMessage.READY)))
        val original = state.value.diagnosis
        var calls = 0
        val callbacks = NovaQuickMenuCallbacks(onDoctorDetails = { panel.push(CommandCenterPage.DoctorDetails) }, onDoctorExplain = {
            calls++; state.value = state.value.copy(doctorAi = NovaDoctorAiState(message = NovaDoctorAiMessage.PENDING))
        })
        panel.open(CommandCenterPage.Root("Command Center"))
        rule.setPanelContent {
            NovaPageStackHost(panel, containFocus = false) { page ->
                when (page) {
                    is CommandCenterPage.Root -> NovaQuickMenuContent(state, callbacks)
                    CommandCenterPage.DoctorDetails -> NovaCommandCenterDetails(CommandCenterPage.DoctorDetails, state, callbacks)
                    else -> Unit
                }
            }
        }
        rule.frames(8); assertEquals(0, calls)
        rule.onNodeWithText("Doctor details").performScrollTo().performClick()
        rule.frames(16); assertEquals(0, calls)
        rule.onNodeWithTag("nova-doctor-ai-explain").performScrollTo().assertIsEnabled().performClick()
        rule.frames(8); assertEquals(1, calls)
        rule.onNodeWithTag("nova-doctor-ai-explain").assertIsNotEnabled().performClick()
        assertEquals(1, calls)
        rule.runOnIdle { state.value = state.value.copy(doctorAi = NovaDoctorAiState(message = NovaDoctorAiMessage.FALLBACK)) }
        rule.frames(8)
        rule.onNodeWithText("AI explanation was unavailable.", substring = true).assertExists()
        assertEquals(original, state.value.diagnosis)
    }
    @Test fun leavingTheActualPageCancelsAndCannotDisplayALateReply() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val panel = NovaPanelState()
        val reply = CompletableDeferred<DoctorAiResponse>()
        var calls = 0
        val model = NovaDoctorAi(scope, fetch = { _, _ -> calls++; withContext(NonCancellable) { reply.await() } })
        val descriptor = DoctorAiDescriptor(true, true, true, "", "result", "a".repeat(64), 9)
        val reading = DoctorAiReading(true, descriptor, DoctorAiScope("session", 7, "result", "a".repeat(64), 9), System.nanoTime() / 1_000_000)
        val state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity))
        val callbacks = NovaQuickMenuCallbacks(onDoctorExplain = model::explain)
        panel.open(CommandCenterPage.Root("Command Center")); panel.push(CommandCenterPage.DoctorDetails)
        try {
            model.observe(DoctorAiContext(Any(), Any(), reading))
            val keys = rule.setPanelContent {
                LaunchedEffect(Unit) { model.state.collect { state.value = state.value.copy(doctorAi = it) } }
                LaunchedEffect(panel.top) { model.pageVisible(panel.top == CommandCenterPage.DoctorDetails) }
                NovaPageStackHost(panel, containFocus = false) { page ->
                    when (page) {
                        is CommandCenterPage.Root -> NovaQuickMenuContent(state, callbacks)
                        CommandCenterPage.DoctorDetails -> NovaCommandCenterDetails(CommandCenterPage.DoctorDetails, state, callbacks)
                        else -> Unit
                    }
                }
            }
            rule.frames(16); assertEquals(0, calls)
            rule.onNodeWithTag("nova-doctor-ai-explain").performScrollTo().performClick()
            rule.frames(8); assertEquals(1, calls)
            keys.back(); rule.frames(16)
            rule.runOnIdle { reply.complete(DoctorAiResponse(DoctorAiText("Late optional text", emptyList(), emptyList(), ""), 1000)) }
            rule.frames(16)
            assertNull(state.value.doctorAi.text)
            rule.onNodeWithText("Late optional text").assertDoesNotExist()
            assertEquals(1, calls)
        } finally { reply.complete(DoctorAiResponse()); scope.cancel() }
    }
}
