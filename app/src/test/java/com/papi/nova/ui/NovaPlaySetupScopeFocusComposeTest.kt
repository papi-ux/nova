package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y swaps Play Setup's rows between This Game's and Every Game's. Focus stays with the rows (N19):
 * it had gone to the plan card, and flipping back landed on a different row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupScopeFocusComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var scope by mutableStateOf(NovaPlaySetupScope.THIS_GAME)

    private fun row(row: NovaPlaySetupRow) = NovaPlaySetupRowState(
        row = row,
        label = row.name,
        caption = "What ${row.name} does",
        value = "",
        options = emptyList(),
        opensPage = true,
    )

    private val gameRows = listOf(
        NovaPlaySetupRow.WHERE_IT_RUNS,
        NovaPlaySetupRow.RESOLUTION,
        NovaPlaySetupRow.FRAME_RATE,
        NovaPlaySetupRow.VIDEO_CODEC,
    ).map(::row)

    private val hostRows = listOf(
        NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
        NovaPlaySetupRow.HOST_SCREEN_TO_ADD,
        NovaPlaySetupRow.HOST_PROFILE,
    ).map(::row)

    private fun show(): NovaTestKeys {
        val state = NovaPanelState()
        state.open(PlaySetupPage.Root("Play Setup"), com.papi.nova.ui.panel.NovaEdge.End)
        return rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = {}) { page ->
                if (page is PlaySetupPage.Root) {
                    NovaPlaySetupRootPage(
                        scope = scope,
                        rows = if (scope == NovaPlaySetupScope.EVERY_GAME) hostRows else gameRows,
                        onAdvance = {},
                        setHereNote = null,
                        card = { NovaPlaySetupPlanCard(title = "What Will Happen", value = "Private Stream", line = "1920×1080", onOpen = {}) },
                    )
                }
            }
        }
    }

    private fun flip(to: NovaPlaySetupScope) {
        scope = to
        rule.waitForIdle()
    }

    @Test
    fun aFlipKeepsFocusOnTheRowInTheSamePlaceAndFlippingBackReturnsToTheSameRow() {
        val keys = show()
        rule.onNodeWithText("WHERE_IT_RUNS").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("FRAME_RATE").assertIsFocused()

        flip(NovaPlaySetupScope.EVERY_GAME)
        rule.onNodeWithText("HOST_PROFILE").assertIsFocused()
        keys.press(NovaTestKeys.UP)
        rule.onNodeWithText("HOST_SCREEN_TO_ADD").assertIsFocused()

        flip(NovaPlaySetupScope.THIS_GAME)
        rule.onNodeWithText("FRAME_RATE").assertIsFocused()

        flip(NovaPlaySetupScope.EVERY_GAME)
        rule.onNodeWithText("HOST_SCREEN_TO_ADD").assertIsFocused()
    }

    @Test
    fun focusOnThePlanCardStaysThereAcrossAFlip() {
        val keys = show()
        keys.press(NovaTestKeys.UP)
        rule.onNodeWithTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG).assertIsFocused()
        flip(NovaPlaySetupScope.EVERY_GAME)
        rule.onNodeWithTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG).assertIsFocused()
    }
}
