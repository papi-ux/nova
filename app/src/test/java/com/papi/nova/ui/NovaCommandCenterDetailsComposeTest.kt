package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import com.papi.nova.ui.panel.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h720dp")
class NovaCommandCenterDetailsComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val panel = NovaPanelState()
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>
    private val writes = mutableListOf<Boolean>()
    private fun open(): NovaTestKeys {
        val preview = NovaQuickMenuUiState.preview(rule.activity)
        state = MutableStateFlow(preview.copy(liveTuningAction = preview.liveTuningAction.copy(enabled = false),
            stream = NovaCommandCenterStream("Video feedback unavailable or stale", listOf("Encoder-confirmed video target" to "Unavailable"))))
        val callbacks = NovaQuickMenuCallbacks(
            onStreamDetails = { panel.push(CommandCenterPage.StreamDetails(it)) },
            onDoctorDetails = { panel.push(CommandCenterPage.DoctorDetails) },
            onLiveTuning = { writes += it },
        )
        panel.open(CommandCenterPage.Root("Command Center"))
        val keys = rule.setPanelContent {
            NovaPageStackHost(panel, containFocus = false) { page ->
                when (page) {
                    is CommandCenterPage.Root -> NovaQuickMenuContent(state, callbacks)
                    is CommandCenterPage.StreamDetails, CommandCenterPage.DoctorDetails -> NovaCommandCenterDetails(page as CommandCenterPage, state, callbacks)
                    else -> Unit
                }
            }
        }
        rule.frames(8)
        return keys
    }
    @Test fun unavailableWritesStillAllowReadOnlyLiveTuningDetailsAndBackRestoresEntry() {
        val keys = open()
        rule.onNodeWithText("Live Tuning").requestFocus()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        assertEquals(CommandCenterPage.StreamDetails(), panel.top)
        assertTrue(writes.isEmpty())
        rule.onNodeWithText("Encoder-confirmed video target").performScrollTo().assertExists()
        rule.onNodeWithText("Turn Off").assertDoesNotExist()
        rule.onNodeWithText("Turn On").assertDoesNotExist()
        keys.back()
        rule.frames(16)
        rule.onNodeWithText("Live Tuning").assertIsFocused()
        assertTrue(writes.isEmpty())
    }
    @Test fun directBitrateUsesTheSharedPageAndFocusesItsReadOnlySection() {
        open()
        rule.onNodeWithText("Bitrate").performScrollTo().performClick()
        rule.frames(16)
        assertEquals(CommandCenterPage.StreamDetails(true), panel.top)
        rule.onNodeWithTag("nova-cc-bitrate-details-heading").assertIsFocused()
        assertTrue(writes.isEmpty())
    }
    @Test fun doctorEvidenceAndExistingOptInExplanationAreOneTapDeeper() {
        open()
        rule.runOnIdle {
            state.value = state.value.copy(diagnosis = state.value.diagnosis.copy(
                evidence = listOf("Fresh media loss is 2%"), confidence = "MEDIUM", aiExplanation = "Existing opted-in explanation",
                visible = true, available = true))
        }
        rule.onNodeWithText("Fresh media loss is 2%", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Existing opted-in explanation", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Doctor details").performScrollTo().performClick()
        rule.frames(16)
        rule.onNodeWithText("Fresh media loss is 2%", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("MEDIUM", substring = true).assertExists()
        rule.onNodeWithText("Existing opted-in explanation", substring = true).assertExists()
        assertTrue(writes.isEmpty())
    }
}
