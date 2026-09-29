package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Command Center's split rows other than End Session: Live Tuning, a host setting, and Clear
 * Game Profile. What they look like, where the panel reopens, and what a confirm does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterSplitRowsComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>
    private lateinit var colors: NovaComposeColors

    private fun open(
        place: NovaQuickMenuPlace? = null,
        adjust: (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { it },
    ): NovaTestKeys {
        state = MutableStateFlow(adjust(NovaQuickMenuUiState.preview(rule.activity)))
        panel.open(CommandCenterPage.Root("Command Center"))
        val keys = rule.setPanelContent {
            // The theme under test inks destructive words and both fills alike; each role gets its own.
            val theme = LocalNovaComposeColors.current
            val roles = theme.copy(
                destructive = Color(0xFFE5484D),
                destructiveFill = Color(0xFFE5484D),
                onDestructiveFill = Color(0xFFFFFFFF),
                onAccent = Color(0xFF101010),
            )
            CompositionLocalProvider(LocalNovaComposeColors provides roles) {
                colors = LocalNovaComposeColors.current
                Box(Modifier.fillMaxSize()) {
                    NovaPageStackHost(state = panel, containFocus = false) { page ->
                        if (page is CommandCenterPage.Root) {
                            NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks(), place = place)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return keys
    }

    private fun focus(text: String) {
        rule.onNodeWithText(text).requestFocus()
        rule.waitForIdle()
    }

    private fun labelColour(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.color
    }

    private fun liveTuning(on: Boolean): (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { s ->
        s.copy(
            liveTuningAction = NovaQuickMenuAction(
                id = NovaQuickMenuActionId.LIVE_TUNING,
                label = "Live Tuning",
                caption = "Steady.",
                chip = if (on) NovaQuickMenuChip("On", NovaQuickMenuTone.ACTIVE) else NovaQuickMenuChip("Off", NovaQuickMenuTone.INACTIVE),
                enabled = true,
            ),
            // The Sync card's chip can also read Live Tuning.
            sync = s.sync.copy(chip = NovaQuickMenuChip("Synced", NovaQuickMenuTone.ACTIVE)),
            advancedExpanded = true,
        )
    }

    /**
     * Review finding 4: Live Tuning is a host setting, second on the page, and it rested in End
     * Session's red. It rests as the rows around it and its confirm takes the accent; ending a
     * session and clearing a game's profile stay destructive.
     */
    @Test
    fun liveTuningRestsAsARowWhileEndingAndClearingStayDestructive() {
        val keys = open(adjust = liveTuning(on = true))
        assertEquals("Live Tuning's title is a row's title", colors.textPrimary, labelColour("Live Tuning"))
        assertEquals("End Session keeps the destructive words", colors.destructive, labelColour("End Session"))
        assertEquals("so does Clear Game Profile", colors.destructive, labelColour("Clear Game Profile"))

        focus("Live Tuning")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        assertEquals("its confirm is the accent fill", colors.onAccent, labelColour("Turn Off"))
    }

    /**
     * Review finding 6: the reopen rule kept End Session and Alt + F4 out, because their A arms a
     * split, but not Live Tuning or Clear Game Profile, which split as well.
     */
    @Test
    fun noSplitRowIsWhereTheCommandCenterReopens() {
        val place = NovaQuickMenuPlace()
        open(place = place, adjust = liveTuning(on = true))
        listOf("Live Tuning", "Clear Game Profile").forEach { row ->
            focus(row)
            panel.close()
            rule.frames(16)
            panel.open(CommandCenterPage.Root("Command Center"))
            rule.waitForIdle()
            rule.frames(4)
            rule.onNodeWithText("Close").assertIsFocused()
        }
    }
}
