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
import org.junit.Assert.assertTrue
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
     * Session's red. It rests as the rows around it; armed, only the half with focus fills, in the
     * accent. Ending a session and clearing a game's profile stay destructive.
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
        assertEquals("Stay has focus and fills in the accent", colors.onAccent, labelColour("Stay"))
        assertEquals("its confirm rests as a tile, its label in the accent", colors.accentText, labelColour("Turn Off"))
    }

    /**
     * Review finding 6: the reopen rule kept End Session and Alt + F4 out, because their A arms a
     * split, but not Live Tuning or Clear Game Profile, which split as well. Each split takes focus
     * first, checked, so a focus request that went nowhere cannot pass on the Close the previous
     * opening left focused; an armed pair counts as its split.
     */
    @Test
    fun noSplitIsWhereTheCommandCenterReopens() {
        val place = NovaQuickMenuPlace()
        val keys = open(place = place, adjust = liveTuning(on = true))
        val altF4 = rule.activity.getString(com.papi.nova.R.string.game_menu_send_keys_alt_f4)
        fun reopensOnClose() {
            panel.close()
            rule.frames(16)
            panel.open(CommandCenterPage.Root("Command Center"))
            rule.waitForIdle()
            rule.frames(4)
            rule.onNodeWithText("Close").assertIsFocused()
        }
        listOf("Live Tuning", "Clear Game Profile", "End Session", altF4).forEach { split ->
            focus(split)
            rule.onNodeWithText(split).assertIsFocused()
            reopensOnClose()
        }
        focus("Live Tuning")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        rule.onNodeWithText("Stay").assertIsFocused()
        reopensOnClose()
    }

    /**
     * Review finding 6, for splits still to come: every split on the root passes reopenHere =
     * false, in its own arguments or, where it takes its caller's modifier, in every call that
     * hands it one. A new split without it fails here, before an opening can start one A from it.
     */
    @Test
    fun everyRootSplitPassesReopenHereFalse() {
        val content = java.io.File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        fun argumentsAt(open: Int): String {
            var depth = 0
            for (i in open until content.length) {
                when (content[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return content.substring(open + 1, i)
                }
            }
            error("unbalanced call at $open")
        }
        // Where each call of [name] opens its arguments, leaving out its declaration.
        fun callsOf(name: String) = Regex("""(?<![A-Za-z.])$name\(""").findAll(content)
            .filter { !content.substring(0, it.range.first).endsWith("fun ") }
            .map { it.range.last }.toList()
        val splits = callsOf("NovaSplitConfirm")
        assertTrue("End Session, Alt + F4, Clear Game Profile and Live Tuning at least", splits.size >= 4)
        splits.forEach { open ->
            val modifier = Regex("""(?m)^\s*modifier = (.+),$""").find(argumentsAt(open))?.groupValues?.get(1)
                ?: error("a root split at $open names no modifier")
            if (modifier.trim() == "modifier") {
                val holder = Regex("""fun (?:[A-Za-z]+\.)?([A-Za-z]+)\(""").findAll(content.substring(0, open)).last().groupValues[1]
                val handing = callsOf(holder)
                assertTrue("$holder is handed its modifier somewhere", handing.isNotEmpty())
                handing.forEach { call ->
                    assertTrue("every call of $holder hands it reopenHere = false", argumentsAt(call).contains("reopenHere = false"))
                }
            } else {
                assertTrue("the split at $open passes reopenHere = false: $modifier", modifier.contains("reopenHere = false"))
            }
        }
    }

    /**
     * Review finding 7: armed, Live Tuning offered Turn Off, and a change from another device while
     * it stayed armed turned the offer, and the switch, the other way. It offers what it showed.
     */
    @Test
    fun liveTuningOffersWhatItShowedWhenArmedWhateverTheHostDoesNext() {
        val keys = open(adjust = liveTuning(on = true))
        focus("Live Tuning")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        rule.onNodeWithText("Turn Off").assertExists()

        state.value = liveTuning(on = false)(state.value)
        rule.waitForIdle()
        rule.onNodeWithText("Turn Off").assertExists()
        rule.onNodeWithText("Turn On").assertDoesNotExist()
    }
}
