package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h600dp-land")
class NovaCameraLibraryToolbarComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun toolbar(kind: Int, notch: Boolean, rtl: Boolean = false) {
        val camera = mutableStateOf<List<Rect>>(emptyList())
        var density = 1f
        var origin = androidx.compose.ui.geometry.Offset.Zero
        var calls = 0
        val width = if (kind == 0) 360f else 900f
        rule.setPanelContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalNovaCameraWindow provides NovaCameraWindow(
                    Rect(origin.x, origin.y, origin.x + width * density, origin.y + 600 * density), camera.value),
            ) {
                Box(Modifier.width(width.dp).onGloballyPositioned { origin = it.positionInWindow() }) {
                    when (kind) {
                        0 -> NovaLibraryPortraitToolbarContent("PC", 12, "Grid", true,
                            onOpenOptions = { calls++ }, onOpenSystemMenu = { calls++ })
                        1 -> NovaLibraryLandscapeToolbarContent("PC", 12, "Grid", true,
                            onOpenOptions = { calls++ }, onOpenSystemMenu = { calls++ })
                        else -> NovaLibraryLandscapeShowcaseStripContent("Camera host", true,
                            onOpenOptions = { calls++ }, onOpenSystemMenu = { calls++ })
                    }
                }
            }
        }
        val options = rule.onNode(hasText("Options") and hasClickAction())
        val before = options.fetchSemanticsNode().boundsInRoot
        val host = rule.onNodeWithText(if (kind == 2) "Camera host" else "· PC", useUnmergedTree = true)
        val hostBefore = host.fetchSemanticsNode().boundsInRoot
        val rectangle = if (notch) Rect(origin.x, origin.y, origin.x + width * density, origin.y + 80 * density)
            else Rect(origin.x, origin.y, origin.x + 40 * density, origin.y + 80 * density)
        rule.runOnIdle { camera.value = listOf(rectangle) }
        rule.waitForIdle()
        val after = options.fetchSemanticsNode().boundsInRoot
        val hostAfter = host.fetchSemanticsNode().boundsInRoot
        if (notch) {
            assertTrue("actual Options target stays below a top notch: $after", after.top >= 80 * density - 1)
            assertTrue("fixed toolbar constraints cannot collapse the normal target: $before -> $after", after.height >= before.height - 1)
        } else {
            assertTrue("actual intrinsic host glyph clears a landscape side camera: $hostBefore -> $hostAfter",
                hostAfter.left >= 40 * density - 1 || hostAfter.top >= 80 * density - 1)
            assertEquals("an unrelated menu button does not inherit a side gutter", before.left, after.left, 1f)
        }
        val tag = if (kind == 0) "nova-library-portrait-toolbar" else "nova-library-landscape-toolbar"
        val surface = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("toolbar remains bounded rather than filling the screen", surface.height < 200 * density)
        repeat(3) { rule.waitForIdle(); assertEquals(after.top, options.fetchSemanticsNode().boundsInRoot.top, 1f) }
        options.performClick()
        assertEquals(1, calls)
        rule.runOnIdle { camera.value = emptyList() }
        rule.waitForIdle()
        assertEquals("removing camera restores toolbar target size", before.height, options.fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun actualPortraitToolbarKeepsTargetsBelowFullTopNotch() = toolbar(0, true)
    @Test fun actualLandscapeToolbarKeepsTargetsBelowFullTopNotch() = toolbar(1, true)
    @Test fun actualLandscapeShowcaseProtectsIntrinsicHostFromSideCamera() = toolbar(2, false)
    @Test fun actualLandscapeShowcaseUsesPhysicalSideInRtl() = toolbar(2, false, true)
}
