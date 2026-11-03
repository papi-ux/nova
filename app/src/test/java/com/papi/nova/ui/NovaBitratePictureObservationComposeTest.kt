package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.papi.nova.manager.NovaLiveBitratePresentation
import com.papi.nova.manager.NovaLiveBitrateToken
import com.papi.nova.ui.panel.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaBitratePictureObservationComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The Command Center's own page routing: the root's Bitrate row only opens the shared Stream
     * details page, where the actual Picture controls compose. Only those controls report, and
     * nothing keeps reporting for them once the page has left the stack.
     */
    @Test fun observationReceivesTheActuallyComposedPictureFromSharedStreamDetailsOnly() {
        val observed = mutableListOf<NovaLiveBitratePresentation>()
        val initial = NovaQuickMenuUiState.preview(rule.activity)
        val flow = MutableStateFlow(initial)
        val panel = NovaPanelState().apply { open(CommandCenterPage.Root(initial.title)) }
        val callbacks = NovaQuickMenuCallbacks(
            onStreamDetails = { panel.push(CommandCenterPage.StreamDetails(it)) },
            onBitrateDiagnostic = { observed += it },
        )
        rule.setPanelContent {
            NovaPageStackHost(panel, containFocus = false) { page ->
                when (page) {
                    is CommandCenterPage.Root -> NovaQuickMenuContent(flow, callbacks)
                    is CommandCenterPage.StreamDetails -> NovaCommandCenterDetails(page, flow, callbacks)
                    else -> Unit
                }
            }
        }
        rule.frames(8)
        // The root's entry row is not the Picture controls, so composing it reports nothing.
        rule.onNodeWithText("Bitrate").performScrollTo().assertExists()
        rule.onNodeWithText("Request budget").assertDoesNotExist()
        rule.runOnIdle { assertTrue(observed.isEmpty()) }

        rule.onNodeWithText("Bitrate").performScrollTo().performClick()
        rule.frames(16)
        assertEquals(CommandCenterPage.StreamDetails(true), panel.top)
        rule.onNodeWithText("Request budget").performScrollTo().assertExists()
        rule.onNodeWithText("Recommendation unavailable", substring = true).assertExists()
        rule.runOnIdle {
            assertEquals(initial.liveBitrate, observed.last())
            assertNull(observed.last().rate.requestedKbps)
            assertFalse(observed.last().rate.canChange)
            flow.value = initial.copy(liveBitrate = initial.liveBitrate.copy(token = NovaLiveBitrateToken(7, 9)))
        }
        rule.frames(4)
        rule.runOnIdle { assertEquals(NovaLiveBitrateToken(7, 9), observed.last().token) }

        // Back on the root, the controls have left composition and leave no observer behind.
        rule.runOnIdle { assertTrue(panel.pop()) }
        rule.frames(16)
        rule.onNodeWithText("Request budget").assertDoesNotExist()
        val settled = rule.runOnIdle { observed.size }
        rule.runOnIdle {
            flow.value = flow.value.copy(liveBitrate = flow.value.liveBitrate.copy(token = NovaLiveBitrateToken(8, 10)))
        }
        rule.frames(8)
        rule.runOnIdle { assertEquals(settled, observed.size) }
    }
}
