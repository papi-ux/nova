package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaCommandCenterHudPositionComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun cornersAndResetAreReachableWithoutTouch() {
        val preview = NovaQuickMenuUiState.preview(rule.activity)
        val state = MutableStateFlow(preview.copy(
            hudMode = preview.hudMode.copy(enabled = true), hudPositionCorner = NovaHudCorner.TOP_LEFT
        ))
        val choices = mutableListOf<NovaHudCorner>()
        var resets = 0
        val callbacks = NovaQuickMenuCallbacks(
            onHudPositionSelect = {
                choices += it
                state.value = state.value.copy(hudPositionCorner = it)
            },
            onHudPositionReset = {
                resets++
                state.value = state.value.copy(hudPositionCorner = NovaHudCorner.TOP_LEFT)
            },
        )
        val panel = NovaPanelState().apply { open(CommandCenterPage.Root("Command Center")) }
        val keys = rule.setPanelContent {
            NovaPageStackHost(panel, containFocus = false) {
                NovaQuickMenuContent(state, callbacks)
            }
        }
        rule.onNodeWithTag("nova-cc-hud-position").requestFocus().assertIsFocused()
        repeat(3) { keys.press(NovaTestKeys.RIGHT) }
        assertEquals(listOf(NovaHudCorner.TOP_RIGHT, NovaHudCorner.BOTTOM_LEFT, NovaHudCorner.BOTTOM_RIGHT), choices)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(NovaHudCorner.TOP_LEFT, choices.last())
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithTag("nova-cc-hud-position-reset").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, resets)
        assertEquals(NovaHudCorner.TOP_LEFT, state.value.hudPositionCorner)
    }
}
