package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import com.papi.nova.ui.compose.NovaActionSurface
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp")
class NovaCameraModifierComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun scene(rtl: Boolean = false, button: Boolean = false) {
        val camera = mutableStateOf(false)
        var pixels = 1f
        var calls = 0
        rule.setPanelContent {
            pixels = LocalDensity.current.density
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalNovaCameraWindow provides NovaCameraWindow(Rect(0f, 0f, 240f * pixels, 800f * pixels),
                    if (camera.value) listOf(if (rtl) Rect(208f * pixels, 1f * pixels, 240f * pixels, 48f * pixels)
                        else Rect(0f, 0f, 32f * pixels, 48f * pixels)) else emptyList()),
            ) {
                Column(Modifier.width(240.dp)) {
                    if (button) NovaPanelButton("Nearby", { calls++ }, modifier = Modifier.width(240.dp))
                    else NovaRow("Nearby", { calls++ })
                    NovaRow("Below", {})
                }
            }
        }
        fun textLeft(label: String) = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val nearbyBeforeBounds = rule.onNodeWithText("Nearby", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val nearbyBefore = nearbyBeforeBounds.left
        val belowBefore = textLeft("Below")
        rule.runOnIdle { camera.value = true }
        rule.waitForIdle()
        val nearbyAfter = textLeft("Nearby")
        val label = rule.onNodeWithText("Nearby", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        if (rtl) {
            assertTrue("RTL fixture begins with its label under the physical-right camera", nearbyBeforeBounds.overlaps(Rect(208f * pixels, 1f * pixels, 240f * pixels, 48f * pixels)))
            assertTrue("the actual RTL label clears the physical-right camera", label.right <= 208f * pixels + 1)
            assertTrue("RTL clearance changes the actual label position", nearbyAfter < nearbyBefore)
        } else assertTrue("the actual row/button label stays outside the camera", nearbyAfter >= 32f * pixels)
        assertEquals("the row below the camera does not inherit a side gutter", belowBefore, textLeft("Below"), 0.5f)
        repeat(3) { rule.waitForIdle(); assertEquals("clearance does not feed back into placement", nearbyAfter, textLeft("Nearby"), 0.5f) }
        rule.onNodeWithText("Nearby").performClick()
        assertEquals("the real row/button callback still executes", 1, calls)
        rule.runOnIdle { camera.value = false }
        rule.waitForIdle()
        assertEquals("removing the camera restores normal placement", nearbyBefore, textLeft("Nearby"), 0.5f)
    }

    private fun smallAction(oddPixels: Boolean = false, fractionalDensity: Float = 1.25f, minHeight: Int = 32) {
        val cameras = mutableStateOf<List<Rect>>(emptyList())
        var density = 1f
        var calls = 0
        val placements = mutableListOf<Float>()
        val width = mutableStateOf(240)
        rule.setPanelContent {
            val physicalDensity = if (oddPixels) Density(fractionalDensity, LocalDensity.current.fontScale) else LocalDensity.current
            density = physicalDensity.density
            CompositionLocalProvider(LocalDensity provides physicalDensity,
                LocalNovaCameraWindow provides NovaCameraWindow(
                Rect(0f, 0f, 240 * density, 800 * density), cameras.value)) {
                Row(Modifier.width(width.value.dp)
                    .heightIn(min = if (oddPixels) 80.dp else 0.dp, max = 200.dp)
                    .padding(top = if (oddPixels) 0.dp else 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    NovaActionSurface(onClick = { calls++ }, modifier = Modifier.width(160.dp).testTag("small-camera-action"),
                        minHeight = minHeight.dp, contentPadding = PaddingValues(0.dp)) { _, _ ->
                        Box(Modifier.size(2.dp).background(Color.White).testTag("small-camera-glyph")
                            .onGloballyPositioned { coordinates ->
                                synchronized(placements) {
                                    placements.add(coordinates.positionInWindow().y)
                                    if (placements.size > 32) placements.removeAt(0)
                                }
                            })
                    }
                }
            }
        }
        val cameraBottom = if (oddPixels) 96f else 18 * density
        try {
            rule.runOnIdle { cameras.value = listOf(Rect(0f, 0f, 240 * density, cameraBottom)) }
            rule.waitForIdle()
            val bounds = rule.onNodeWithTag("small-camera-action").fetchSemanticsNode().boundsInRoot
            val top = bounds.center.y - 23 * density
            assertTrue("camera clearance covers the actual expanded touch floor, not just the small visual surface: $bounds / $top", top >= cameraBottom)
            val glyph = rule.onNodeWithTag("small-camera-glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("visible content remains nonzero below the camera", glyph.height > 0 && glyph.top >= cameraBottom)
            repeat(4) {
                rule.runOnIdle { width.value = if (width.value == 240) 241 else 240 }
                rule.waitForIdle()
                assertEquals("odd-pixel clearance settles across forced layouts", bounds,
                    rule.onNodeWithTag("small-camera-action").fetchSemanticsNode().boundsInRoot)
            }
            rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(bounds.center.x, top)) }
            rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(bounds.center.x, bounds.center.y + 23 * density)) }
            assertEquals(2, calls)
        } finally {
            cameras.value = emptyList()
            println("camera-small-action density=$density minHeight=$minHeight actualGlyphPlacements=${synchronized(placements) { placements.toList() }}")
        }
    }

    @Test fun smallActionProtectsItsInflated48dpTouchEdges() = smallAction()
    @Test(timeout = 15000) fun oddPhysicalPixelsDoNotAlternateCameraPadding() = smallAction(oddPixels = true)

    @Test(timeout = 15000) fun default38dpActionSettlesInCenteredParent() =
        smallAction(oddPixels = true, fractionalDensity = 1f, minHeight = 38)

    @Test fun menuRowClearsOnlyItsIntersectingCameraRegion() = scene()
    @Test fun menuRowUsesPhysicalCameraCoordinatesInRtl() = scene(rtl = true)
    @Test fun sharedActionButtonKeepsItsCallbackAndLocalizedClearance() = scene(button = true)
}
