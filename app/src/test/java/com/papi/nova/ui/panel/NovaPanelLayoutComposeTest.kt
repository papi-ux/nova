package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.NovaControllerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How panel pieces make room: nothing is cut, squeezed or scrolled sideways (R13). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelLayoutComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun pair(width: Int) = rule.setPanelContent {
        Box(Modifier.width(width.dp)) {
            NovaPanelButtonPair(
                first = { NovaPanelButton(text = "Keep", onClick = {}) },
                second = { NovaPanelButton(text = "Delete PC", onClick = {}, destructive = true) },
            )
        }
    }

    @Test
    fun aConfirmsButtonsSitSideBySideWhenBothHalvesFit() {
        pair(width = 300)
        val keep = rule.onNodeWithText("Keep").getUnclippedBoundsInRoot()
        val delete = rule.onNodeWithText("Delete PC").getUnclippedBoundsInRoot()
        assertEquals(keep.top, delete.top)
        assertTrue("Stay at the start, the action after it", delete.left > keep.right)
    }

    @Test
    fun aConfirmsButtonsStackAtFullWidthWhenAHalfWouldBeTooNarrow() {
        pair(width = 150)
        val keep = rule.onNodeWithText("Keep").getUnclippedBoundsInRoot()
        val delete = rule.onNodeWithText("Delete PC").getUnclippedBoundsInRoot()
        assertTrue("Stay above the action", delete.top >= keep.bottom)
        assertEquals(150.dp, keep.right - keep.left)
        assertEquals(150.dp, delete.right - delete.left)
    }

    @Test
    fun hintsWrapOntoAnotherLineRatherThanScrollingOffThePanel() {
        val state = NovaPanelState().apply { open(TestPage("options")) }
        val hints = listOf(
            NovaControllerHint("L1", "Options"),
            NovaControllerHint("R1", "System"),
            NovaControllerHint("X", "Search"),
            NovaControllerHint("Y", "Sort"),
        )
        rule.setPanelContent {
            Box(Modifier.width(140.dp)) {
                NovaPageStackHost(state = state, hints = hints) { page ->
                    NovaRow(title = page.title, onClick = {}, modifier = Modifier.novaInitialFocus())
                }
            }
        }
        val first = rule.onNodeWithText("Select").getUnclippedBoundsInRoot()
        val last = rule.onNodeWithText("Sort").getUnclippedBoundsInRoot()
        assertTrue("the last hint is on a later line, inside the panel", last.top > first.top && last.right <= 140.dp)
    }

    @Test
    fun aRowValueTooWideToSitBesideItsTitleGoesUnderIt() {
        rule.setPanelContent {
            Box(Modifier.width(120.dp)) {
                NovaRow(
                    title = "Display",
                    onClick = {},
                    trailing = NovaRowTrailing.Value("3840 x 2160 at 120 Hz, HDR10, native panel"),
                )
            }
        }
        val title = rule.onNodeWithText("Display", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = rule.onNodeWithText("3840 x 2160 at 120 Hz, HDR10, native panel", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("the value moved under the title instead of squeezing it", value.top >= title.bottom)
        assertTrue(title.right > title.left)
    }

    @Test
    fun aShortRowValueStaysBesideItsTitle() {
        rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaRow(title = "Frame rate", onClick = {}, trailing = NovaRowTrailing.Value("60 FPS"))
            }
        }
        val title = rule.onNodeWithText("Frame rate", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = rule.onNodeWithText("60 FPS", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(value.left > title.right)
        assertTrue(value.top < title.bottom)
    }
}
