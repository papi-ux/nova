package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.papi.nova.R
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.papi.nova.ui.compose.NovaRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelFrameComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)
    private var closedCount = 0
    private var covered by mutableStateOf(false)

    private fun frame(
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        landscape: Boolean = false,
        scrim: NovaScrim = NovaScrim.None,
        width: NovaPanelWidth = NovaPanelWidth.Standard,
        windowHeightDp: Int? = null,
    ) {
        rule.setPanelContent {
            val configuration = LocalConfiguration.current.let { current ->
                windowHeightDp?.let { height -> Configuration(current).apply { screenHeightDp = height } } ?: current
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalNovaPanelCovered provides covered,
                LocalConfiguration provides configuration,
            ) {
                Box(if (landscape) Modifier.requiredSize(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT).testTag("frame") else Modifier.fillMaxSize()) {
                    NovaPanelFrame(
                        edge = NovaEdge.End,
                        width = width,
                        open = open,
                        onDismissRequest = { open = false },
                        onClosed = { closedCount++ },
                        scrim = scrim,
                    ) {
                        Box(Modifier.fillMaxSize().testTag("content"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aDragOnAClosingPanelNeverStopsItsExit() {
        frame()
        rule.mainClock.autoAdvance = false
        open = false
        rule.frames(2)

        rule.onNodeWithTag("content").performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f))
            up()
        }
        rule.advance(2_000)

        assertEquals("the exit landed, so the window can go", 1, closedCount)
    }

    @Test
    fun aPanelClosedUnderTheFingerFinishesItsExitOnRelease() {
        frame()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("content").performTouchInput {
            down(center)
            moveBy(Offset(0f, 30f))
        }
        rule.frames(2)
        // B or Start closes the panel while the finger is still down.
        open = false
        rule.frames(2)
        rule.onNodeWithTag("content").performTouchInput {
            moveBy(Offset(0f, 10f))
            up()
        }
        rule.advance(2_000)

        assertEquals(1, closedCount)
    }

    @Test
    fun aCoveredPanelIgnoresItsScrimAndDragsUntilItUncovers() {
        covered = true
        frame(scrim = NovaScrim.Stream)
        val closePanel = rule.activity.getString(R.string.nova_panel_close_panel)

        val scrim = rule.onNodeWithContentDescription(closePanel)
        // A tap above the portrait sheet, and the scrim's accessibility action.
        scrim.performTouchInput { click(Offset(centerX, 8f)) }
        scrim.performSemanticsAction(SemanticsActions.OnClick)
        // A drag down the sheet's whole height would close it.
        rule.onNodeWithTag("content").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue("a state page above the panel owns the scrim and the drag", open)

        covered = false
        rule.waitForIdle()
        scrim.performTouchInput { click(Offset(centerX, 8f)) }
        rule.waitForIdle()
        assertFalse("uncovered, a tap on the scrim closes it again", open)
    }

    @Test
    fun aGridPanelIsWideOnACompactWindow() {
        frame(landscape = true, width = NovaPanelWidth.Grid, windowHeightDp = 468)
        val compact = rule.onNodeWithTag("content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("Wide at 800dp: 60%", 480f, (compact.right - compact.left).value, 0.5f)
    }

    @Test
    fun aGridPanelOnATallerWindowStaysStandard() {
        frame(landscape = true, width = NovaPanelWidth.Grid, windowHeightDp = 900)
        val regular = rule.onNodeWithTag("content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("Standard at 800dp: 48%", 384f, (regular.right - regular.left).value, 0.5f)
    }

    @Test
    fun aClosedPanelSitsOffScreenInEitherLayoutDirection() {
        open = false
        frame(LayoutDirection.Rtl, landscape = true)
        rule.advance(2_000)

        val frame = rule.onNodeWithTag("frame").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "in RTL the End panel is attached on the left and slides out to the left: $panel in $frame",
            panel.right <= frame.left,
        )
    }

    @Test
    fun aLandscapePanelSaysItIsAttachedToItsEdgeAndRoundedOnlyOnItsInnerEdge() {
        frame(landscape = true)

        val (side, corners) = placement()
        assertEquals("an End panel sits on the right in a left to right layout", NovaPanelSide.Right, side)
        val drawer = with(rule.density) { NovaRadius.drawer.toPx() }
        assertEquals("the inner edge carries the drawer radius", listOf(drawer, 0f, 0f, drawer), corners)
    }

    @Test
    fun thePortraitSheetSaysItIsAttachedToTheBottomAndRoundedOnlyOnTop() {
        frame()

        val (side, corners) = placement()
        assertEquals(NovaPanelSide.Bottom, side)
        val drawer = with(rule.density) { NovaRadius.drawer.toPx() }
        assertEquals("the top carries the drawer radius, the bottom meets the screen", listOf(drawer, drawer, 0f, 0f), corners)
    }

    /** The published side, and the corners of the published shape at the panel's size: top start, top end, bottom end, bottom start. */
    private fun placement(): Pair<NovaPanelSide, List<Float>> {
        val node = rule.onNode(SemanticsMatcher.keyIsDefined(NovaPanelPlacementKey), useUnmergedTree = true).fetchSemanticsNode()
        val placement = node.config[NovaPanelPlacementKey]
        val shape = placement.shape as CornerBasedShape
        val size = node.size.toSize()
        val corners = listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).map { it.toPx(size, rule.density) }
        return placement.side to corners
    }

    private companion object {
        val LANDSCAPE_WIDTH = 800.dp
        val LANDSCAPE_HEIGHT = 400.dp
    }
}
