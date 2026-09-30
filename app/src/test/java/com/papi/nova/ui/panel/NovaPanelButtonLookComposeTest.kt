package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaSurfaceLook
import com.papi.nova.ui.compose.NovaSurfaceLookKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A primary rests as a tile with its label in the accent, and takes the accent fill, and the
 * on-accent label, only while it has focus: Resume, Close and Save rested as solid accent and read
 * as a second focus beside the real one (R9). Such a primary rings in its label colour, where the
 * accent ring would vanish on its fill. A full-screen state page's recovery action is the one
 * thing on its page and keeps its fill, and so does an armed split's confirm half in either tone
 * (NovaSplitConfirmToneComposeTest); a surface filled at rest takes the accent ring every control
 * has, with its fill stood off the ring (in-game #14, review finding 4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelButtonLookComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var colors: NovaComposeColors
    private lateinit var surfaces: NovaLibrarySurfaces

    private fun labelColour(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.color
    }

    @Test
    fun aPrimaryButtonRestsAsATileWithAnAccentLabelAndFillsUnderFocus() {
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            Column {
                Box(Modifier.size(48.dp).testTag("other").focusable())
                NovaPanelButton(text = "Save", onClick = {}, primary = true)
            }
        }
        rule.onNodeWithTag("other").requestFocus()
        rule.waitForIdle()
        assertEquals("at rest the label is the accent", colors.accentText, labelColour("Save"))
        assertNotEquals("and not the label of a fill", colors.onAccent, labelColour("Save"))

        rule.onNodeWithText("Save").requestFocus()
        rule.waitForIdle()
        assertEquals("under focus it wears the fill's label", colors.onAccent, labelColour("Save"))
        assertEquals(
            "and its fill, ringed in the label colour",
            NovaSurfaceLook(colors.accent, colors.onAccent, false),
            rule.onNodeWithText("Save").fetchSemanticsNode().config[NovaSurfaceLookKey],
        )
    }

    @Test
    fun aStatePagesRecoveryKeepsItsFillWhileAnotherActionHasFocus() {
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            surfaces = LocalNovaLibrarySurfaces.current
            NovaStateScreen(
                NovaStatePage.Problem(
                    key = "lost",
                    title = "Connection Lost",
                    message = "The host stopped answering.",
                    primary = NovaAction("Reconnect") {},
                    secondary = listOf(NovaAction("Close") {}),
                    back = NovaProblemBack.Absorb,
                ),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Close").requestFocus()
        rule.waitForIdle()
        assertEquals("the recovery keeps its fill at rest", colors.onAccent, labelColour("Reconnect"))
        assertEquals("the other actions rest as tiles", colors.textPrimary, labelColour("Close"))

        rule.onNodeWithText("Reconnect").requestFocus()
        rule.waitForIdle()
        assertEquals(
            "under focus it keeps its fill and takes the accent ring every control has, stood off the fill",
            NovaSurfaceLook(colors.accent, surfaces.focusRing, true),
            rule.onNodeWithText("Reconnect").fetchSemanticsNode().config[NovaSurfaceLookKey],
        )
    }

    @Test
    fun aMenusPrimaryRowRestsWithItsTitleInTheAccent() {
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            Column {
                Box(Modifier.size(48.dp).testTag("other").focusable())
                NovaRow(title = "Resume Stream", onClick = {}, emphasis = true)
            }
        }
        rule.onNodeWithTag("other").requestFocus()
        rule.waitForIdle()
        assertEquals(colors.accentText, labelColour("Resume Stream"))

        rule.onNodeWithText("Resume Stream").requestFocus()
        rule.waitForIdle()
        assertEquals(colors.onAccent, labelColour("Resume Stream"))
    }
}
