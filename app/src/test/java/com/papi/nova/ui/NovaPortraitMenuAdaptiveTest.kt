package com.papi.nova.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.NovaControlSizeHost
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-port")
class NovaPortraitMenuAdaptiveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun scene(rtl: Boolean, camera: Boolean) {
        val width = mutableStateOf(400)
        val cameras = mutableStateOf<List<Rect>>(emptyList())
        var pixels = 1f
        var menus = 0
        var backs = 0
        val keys = rule.setPanelContent {
            val density = LocalDensity.current
            pixels = density.density
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.3f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalNovaCameraWindow provides NovaCameraWindow(Rect(0f, 0f, 400 * pixels, 800 * pixels), cameras.value),
            ) {
                NovaControlSizeHost(NovaControlSize.Large) {
                    Column(Modifier.width(width.value.dp)) {
                        NovaPortraitMenuBar("Settings", false, { menus++ }, onBack = { backs++ })
                    }
                }
            }
        }
        val menu = rule.onNodeWithTag("nova-portrait-menu-toggle")
        val back = rule.onNode(hasClickAction() and hasText("Back"))
        fun bounds() = listOf(rule.onNodeWithText("Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot,
            menu.assertIsDisplayed().fetchSemanticsNode().boundsInRoot, back.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        fun complete() {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(1, results.size)
            assertFalse("Actual header text remains complete", results.single().hasVisualOverflow)
            val bar = rule.onNodeWithTag("nova-portrait-menu-bar").fetchSemanticsNode().boundsInRoot
            for (b in bounds()) assertTrue("Real title/action remains within the header: $b / $bar",
                b.left >= bar.left && b.right <= bar.right && b.top >= bar.top && b.bottom <= bar.bottom)
        }
        rule.waitForIdle()
        val ordinary = bounds()
        assertEquals("Ordinary title stays on the actions' one row", ordinary[1].center.y, ordinary[0].center.y, 0.5f)
        val threshold = ceil((ordinary[0].width + ordinary[1].width + ordinary[2].width) / pixels + 16).toInt()
        rule.runOnIdle { width.value = threshold + 2 }
        rule.waitForIdle()
        val fitted = bounds()
        assertEquals("Title fits just above the actual measured threshold", fitted[1].center.y, fitted[0].center.y, 0.5f)
        menu.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val notch = Rect(0f, 0f, 400 * pixels, 68 * pixels)
        if (camera) rule.runOnIdle { cameras.value = listOf(notch) }
        repeat(3) {
            rule.runOnIdle { width.value = threshold - 2 }
            rule.waitForIdle()
            complete()
            val stacked = bounds()
            assertTrue("Insufficient title width gives it a full wrapping row", stacked[0].top >= maxOf(stacked[1].bottom, stacked[2].bottom))
            if (camera) for (b in stacked) assertFalse("Actual glyph/target clears the camera", b.overlaps(notch))
            menu.assertIsFocused()
            rule.runOnIdle { width.value = threshold + 2 }
            rule.waitForIdle()
            complete()
            if (camera) for (b in bounds()) assertFalse(b.overlaps(notch))
            menu.assertIsFocused()
        }
        keys.press(KeyEvent.KEYCODE_BUTTON_A)
        menu.assertIsFocused()
        assertEquals("The original Menu focus owner still handles A", 1, menus)
        back.performClick()
        assertEquals("The original Back callback remains active", 1, backs)
        rule.runOnIdle { cameras.value = emptyList(); width.value = 400 }
        rule.waitForIdle()
        assertEquals("Removing camera and restoring width preserves ordinary geometry", ordinary, bounds())
        println("adaptive-header rtl=$rtl camera=$camera threshold=$threshold ordinary=$ordinary fitted=$fitted")
    }

    @Test fun measuredTitleThresholdKeepsOrdinaryGeometryAndFocus() = scene(rtl = false, camera = false)
    @Test fun largeTextAndCameraSettleAcrossTheMeasuredTitleThreshold() = scene(rtl = false, camera = true)
    @Test fun rtlLargeTextAndCameraKeepTheSameFocusOwnersAcrossTitleRows() = scene(rtl = true, camera = true)
}
