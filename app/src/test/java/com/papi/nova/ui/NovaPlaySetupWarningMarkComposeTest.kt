package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A warning on a Play Setup page keeps its mark beside the first line of its words. Centred on the
 * whole note, a warning that wrapped onto a second line, as the 2x size's PyroWave warning does on
 * a television, had its mark between the two lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupWarningMarkComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // Robolectric does not wrap a line for its width, so the note breaks where a device wraps it.
    private val note = "PyroWave needs about 800 Mbps at this size.\nPick HEVC in Video Codec."

    @Test
    fun aWarningsMarkSitsOnTheFirstLineOfItsWords() {
        rule.setPanelContent {
            Box(Modifier.width(400.dp)) {
                NovaPlaySetupOptionRow(
                    option = NovaPlaySetupOption(
                        label = "2x",
                        consequence = note,
                        value = "3840×2160",
                        warning = true,
                        onSelect = {},
                    ),
                    onPick = {},
                )
            }
        }
        rule.waitForIdle()

        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(note, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.first()
        assertEquals("the note takes two lines", 2, layout.lineCount)
        val words = rule.onNodeWithText(note, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val mark = rule.onNodeWithTag(NOVA_PLAY_SETUP_WARNING_MARK_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val firstLine = with(rule.density) { ((layout.getLineTop(0) + layout.getLineBottom(0)) / 2f).toDp() }
        val markCentre = (mark.top + mark.bottom) / 2f
        assertEquals(
            "the mark is centred on the first line, not between the lines",
            (words.top + firstLine).value,
            markCentre.value,
            1f,
        )
    }
}
