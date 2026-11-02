package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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

    @Test fun observationReceivesTheActuallyComposedPictureIncludingUnknownAndChangedToken() {
        val observed = mutableListOf<NovaLiveBitratePresentation>()
        val initial = NovaQuickMenuUiState.preview(rule.activity)
        val flow = MutableStateFlow(initial)
        val panel = NovaPanelState().apply { open(CommandCenterPage.Root(initial.title)) }
        rule.setPanelContent {
            NovaPageStackHost(panel) {
                NovaQuickMenuContent(flow, NovaQuickMenuCallbacks(onBitrateDiagnostic = { observed += it }))
            }
        }
        rule.frames(8)
        rule.onNodeWithText("Picture").performScrollTo().assertExists()
        rule.runOnIdle {
            assertEquals(initial.liveBitrate, observed.last())
            assertNull(observed.last().rate.requestedKbps)
            assertFalse(observed.last().rate.canChange)
            flow.value = initial.copy(liveBitrate = initial.liveBitrate.copy(token = NovaLiveBitrateToken(7, 9)))
        }
        rule.frames(4)
        rule.runOnIdle { assertEquals(NovaLiveBitrateToken(7, 9), observed.last().token) }
        rule.onNodeWithText("Recommendation unavailable", substring = true).assertExists()
    }
}
