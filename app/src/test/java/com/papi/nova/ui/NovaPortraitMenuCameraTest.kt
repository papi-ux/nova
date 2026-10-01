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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-port")
class NovaPortraitMenuCameraTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun scene(fullNotch: Boolean) {
        val cameras = mutableStateOf(emptyList<Rect>())
        val width = mutableStateOf(400)
        val title = "Edit saved setup: Weekend games on my handheld"
        var pixels = 1f
        var menus = 0
        var backs = 0
        val keys = rule.setPanelContent {
            pixels = LocalDensity.current.density
            CompositionLocalProvider(LocalNovaCameraWindow provides NovaCameraWindow(
                Rect(0f, 0f, 400f * pixels, 800f * pixels), cameras.value,
            )) {
                Column(Modifier.width(width.value.dp)) {
                    NovaPortraitMenuBar(title, expanded = false, onToggle = { menus++ }, onBack = { backs++ })
                }
            }
        }
        val menu = rule.onNodeWithTag("nova-portrait-menu-toggle")
        val back = rule.onNode(hasClickAction() and hasText("Back"))
        fun contentBounds() = listOf(
            rule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot,
            menu.fetchSemanticsNode().boundsInRoot,
            back.fetchSemanticsNode().boundsInRoot,
        )
        fun same(expected: List<Rect>, actual: List<Rect>) {
            expected.zip(actual).forEach { (a, b) ->
                assertEquals(a.left, b.left, 0.5f)
                assertEquals(a.top, b.top, 0.5f)
                assertEquals(a.right, b.right, 0.5f)
                assertEquals(a.bottom, b.bottom, 0.5f)
            }
        }
        val normal = contentBounds()
        val camera = if (fullNotch) Rect(0f, 0f, 400f * pixels, 68f * pixels)
            else Rect(182f * pixels, 0f, 218f * pixels, 68f * pixels)
        assertTrue("The unprotected actual title initially intersects this camera", normal.first().overlaps(camera))
        rule.runOnIdle { cameras.value = listOf(camera) }
        rule.waitForIdle()
        val cleared = contentBounds()
        assertFalse("The actual title glyph region clears the camera", cleared.first().overlaps(camera))
        for (target in cleared.drop(1)) {
            assertFalse("The actual action target clears the camera", target.overlaps(camera))
            assertTrue("Menu and Back keep their 48dp target height", target.height >= 48f * pixels - 0.5f)
        }
        // Force actual parent and sibling remeasurement; idle-only checks cannot catch feedback.
        repeat(3) {
            rule.runOnIdle { width.value = 399 }
            rule.waitForIdle()
            rule.runOnIdle { width.value = 400 }
            rule.waitForIdle()
            same(cleared, contentBounds())
        }
        menu.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keys.press(KeyEvent.KEYCODE_BUTTON_A)
        menu.assertIsFocused()
        assertEquals("Controller A still opens the actual Menu", 1, menus)
        back.performClick()
        assertEquals("Touch still activates the actual Back action", 1, backs)
        rule.runOnIdle { cameras.value = emptyList() }
        rule.waitForIdle()
        same(normal, contentBounds())
    }

    @Test fun centeredHeaderClearsACentralCameraAndSettlesAfterRemeasure() = scene(fullNotch = false)
    @Test fun centeredHeaderClearsATopNotchWithoutShrinkingEitherAction() = scene(fullNotch = true)
}
