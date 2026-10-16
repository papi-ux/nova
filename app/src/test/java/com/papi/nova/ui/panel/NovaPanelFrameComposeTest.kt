package com.papi.nova.ui.panel

import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
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
        television: Boolean = false,
    ) {
        rule.setPanelContent {
            val configuration = LocalConfiguration.current.let { current ->
                windowHeightDp?.let { height -> Configuration(current).apply { screenHeightDp = height } } ?: current
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalNovaPanelCovered provides covered,
                LocalConfiguration provides configuration,
                LocalNovaFormFactor provides if (television) NovaFormFactor.Television else LocalNovaFormFactor.current,
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
    fun aFullPanelHasTheWindowsWholeWidth() {
        frame(landscape = true, width = NovaPanelWidth.Full)
        val panel = rule.onNodeWithTag("content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("Full at 800dp", 800f, (panel.right - panel.left).value, 0.5f)
    }

    @Test
    fun aSheetStopsAt88PercentOfTheWindowsHeight() {
        frame()
        assertEquals(windowHeight() * 0.88f, sheetHeight(), 1f)
    }

    @Test
    fun aFullPagesSheetHasTheWindowsWholeHeight() {
        frame(width = NovaPanelWidth.Full)
        assertEquals(windowHeight(), sheetHeight(), 0.5f)
    }

    private fun windowHeight() = rule.onRoot().getUnclippedBoundsInRoot().let { it.bottom - it.top }.value

    private fun sheetHeight() = rule.onNodeWithTag("content", useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.bottom - it.top }.value

    // Expanded, Host Console is a Full page: it reaches every edge of the window, so it keeps the
    // margins a page that fills the window keeps (#317).
    @Test
    fun aFullPanelOnATelevisionKeepsTheTitleSafeMarginOnBothSides() {
        frame(landscape = true, width = NovaPanelWidth.Full, television = true)
        val (frame, content) = frameAndContent()
        assertEquals("on the edge it is attached to", 48f, (frame.right - content.right).value, 0.5f)
        assertEquals("and on the far edge it reaches", 48f, (content.left - frame.left).value, 0.5f)
    }

    @Test
    fun aFullSheetOnATelevisionKeepsTheTitleSafeMarginAtTheTopItReaches() {
        frame(width = NovaPanelWidth.Full, television = true)
        val (window, content) = rule.onRoot().getUnclippedBoundsInRoot() to contentBounds()
        assertEquals(27f, (content.top - window.top).value, 0.5f)
    }

    // A web page cannot step around a camera the way the frame's own controls do.
    @Test
    fun aFullPanelClearsACameraOnEitherShortEdgeWithTheBarsHidden() {
        frame(landscape = true, width = NovaPanelWidth.Full)
        camera(Insets.of(80, 0, 0, 0), Rect(0, 160, 80, 240))
        frameAndContent().let { (frame, content) -> assertEquals("camera on the left", 80f, (content.left - frame.left).value, 0.5f) }
        camera(Insets.of(0, 0, 80, 0), Rect(720, 160, 800, 240))
        frameAndContent().let { (frame, content) ->
            assertEquals("camera on the right, the phone turned the other way", 80f, (frame.right - content.right).value, 0.5f)
            assertEquals(0f, (content.left - frame.left).value, 0.5f)
        }
    }

    @Test
    fun aFullSheetClearsATopCameraWithTheBarsHidden() {
        frame(width = NovaPanelWidth.Full)
        camera(Insets.of(0, 80, 0, 0), Rect(140, 0, 180, 80))
        val (window, content) = rule.onRoot().getUnclippedBoundsInRoot() to contentBounds()
        assertEquals(80f, (content.top - window.top).value, 0.5f)
    }

    @Test
    fun aPanelThatIsNotFullStillLeavesACameraToTheControlsNearIt() {
        // Nova's rule for every other panel: a camera keeps the controls near it clear, not the whole surface.
        frame(landscape = true, width = NovaPanelWidth.Wide)
        camera(Insets.of(0, 0, 80, 0), Rect(720, 160, 800, 240))
        val (frame, content) = frameAndContent()
        assertEquals(0f, (frame.right - content.right).value, 0.5f)
    }

    private fun contentBounds() = rule.onNodeWithTag("content", useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun frameAndContent() = rule.onNodeWithTag("frame").getUnclippedBoundsInRoot() to contentBounds()

    /** The window's insets with the bars hidden and a camera cut out of [camera], as a phone reports them. */
    private fun camera(safe: Insets, camera: Rect) {
        rule.runOnUiThread {
            val cutout = DisplayCutout(safe, camera.takeIf { safe.left > 0 }, camera.takeIf { safe.top > 0 },
                camera.takeIf { safe.right > 0 }, camera.takeIf { safe.bottom > 0 })
            rule.activity.findViewById<View>(android.R.id.content).dispatchApplyWindowInsets(
                WindowInsets.Builder()
                    .setDisplayCutout(cutout)
                    .setInsets(WindowInsets.Type.displayCutout(), safe)
                    .setVisible(WindowInsets.Type.displayCutout(), true)
                    .setInsets(WindowInsets.Type.systemBars(), Insets.NONE)
                    .setVisible(WindowInsets.Type.systemBars(), false)
                    .build(),
            )
        }
        rule.waitForIdle()
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
