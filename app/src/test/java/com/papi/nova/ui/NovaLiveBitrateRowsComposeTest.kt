package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presence/read-only checks; the separate attachment/API tests prove writes and authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaLiveBitrateRowsComposeTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private fun show():NovaQuickMenuUiState {
        val state=NovaQuickMenuUiState.preview(rule.activity)
        val panel=NovaPanelState().apply { open(CommandCenterPage.Root(state.title)) }
        val flow=MutableStateFlow(state)
        rule.setPanelContent { NovaPageStackHost(panel) { NovaQuickMenuContent(flow,NovaQuickMenuCallbacks()) } }
        rule.frames(8)
        return state
    }
    @Test fun commandCenterKeepsARecommendationRowWhenUnitsAreUnavailable() {
        show()
        rule.onNodeWithText("Use recommended").performScrollTo().assertExists()
        rule.onNodeWithText("Recommendation unavailable",substring=true).assertExists()
    }
    @Test fun pictureControlsReplaceTheNextLaunchPresetCallback() {
        val state=show()
        rule.onNodeWithText("Picture").performScrollTo().assertExists()
        rule.onNodeWithText(state.stability.profileTitle).assertDoesNotExist()
    }
}
