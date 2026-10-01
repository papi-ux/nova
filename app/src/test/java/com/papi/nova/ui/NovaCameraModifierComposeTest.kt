package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
        val nearbyBefore = textLeft("Nearby")
        val belowBefore = textLeft("Below")
        rule.runOnIdle { camera.value = true }
        rule.waitForIdle()
        val nearbyAfter = textLeft("Nearby")
        val label = rule.onNodeWithText("Nearby", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        if (rtl) {
            assertTrue("RTL fixture begins with its label under the physical-right camera", nearbyBefore < 240f * pixels && label.right > 0)
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

    @Test fun menuRowClearsOnlyItsIntersectingCameraRegion() = scene()
    @Test fun menuRowUsesPhysicalCameraCoordinatesInRtl() = scene(rtl = true)
    @Test fun sharedActionButtonKeepsItsCallbackAndLocalizedClearance() = scene(button = true)
}
