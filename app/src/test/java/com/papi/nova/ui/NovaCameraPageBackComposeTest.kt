package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.NovaChevronBackTag
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageBackTag
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
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
class NovaCameraPageBackComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private class Page(override val key: String, override val title: String) : NovaPage

    private fun pushedPage(rtl: Boolean) {
        val state = NovaPanelState().apply { open(Page("root", "Root")); push(Page("detail", "Advanced streaming controls")) }
        val cameras = mutableStateOf<List<Rect>>(emptyList())
        val width = mutableStateOf(320)
        var pixels = 1f
        var minimumTouchWidth = 0f
        var minimumTouchHeight = 0f
        rule.setPanelContent {
            pixels = LocalDensity.current.density
            minimumTouchWidth = with(LocalDensity.current) { LocalViewConfiguration.current.minimumTouchTargetSize.width.toPx() }
            minimumTouchHeight = with(LocalDensity.current) { LocalViewConfiguration.current.minimumTouchTargetSize.height.toPx() }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalNovaCameraWindow provides NovaCameraWindow(Rect(0f, 0f, 400 * pixels, 800 * pixels), cameras.value),
            ) {
                Box(Modifier.width(width.value.dp).height(600.dp)) {
                    NovaPageStackHost(state = state) { page ->
                        NovaRow("${page.key} action", {}, modifier = Modifier.novaInitialFocus().testTag("${page.key}-action"))
                    }
                }
            }
        }
        fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun title() = rule.onNodeWithText("Advanced streaming controls", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.waitForIdle()
        val originalTarget = bounds(NovaPageBackTag)
        val originalGlyph = bounds(NovaChevronBackTag)
        val originalTitle = title()
        println("pageback-initial rtl=$rtl glyph=$originalGlyph title=$originalTitle target=$originalTarget floor=$minimumTouchWidth/$minimumTouchHeight")
        assertTrue("wide real title prevents horizontal minimum-touch expansion from masking the hanging glyph", originalTarget.width > minimumTouchWidth)
        assertTrue("Back keeps its real48dp target height", originalTarget.height >= minimumTouchHeight - 0.5f)
        rule.onNodeWithTag("detail-action").assertIsFocused()
        rule.onNodeWithTag(NovaPageBackTag).assertIsNotFocused()
        val gapEdge = if (rtl) (originalTarget.right + originalGlyph.right) / 2f else (originalTarget.left + originalGlyph.left) / 2f
        val camera = if (rtl) Rect(gapEdge, 1f, 400 * pixels, originalGlyph.bottom + 1f)
            else Rect(0f, 1f, gapEdge, originalGlyph.bottom + 1f)
        assertTrue("the camera starts under the actual hanging icon", originalGlyph.overlaps(camera))
        assertTrue("the original Back pointer envelope and48dp floor do not overlap this camera", !originalTarget.overlaps(camera))
        // Exercise the original real target edge, then rebuild the pushed page. This checks the
        // normal callback envelope independently of the visible glyph's hanging placement.
        rule.onRoot().performTouchInput { click(Offset(originalTarget.center.x, originalTarget.top + 0.5f)) }
        rule.waitForIdle()
        assertEquals("original Back edge invokes the real page pop", "root", state.topEntry?.page?.key)
        rule.runOnIdle { state.push(Page("detail", "Advanced streaming controls")) }
        rule.waitForIdle()
        assertEquals(originalGlyph, bounds(NovaChevronBackTag))
        assertEquals(originalTitle, title())
        assertEquals(originalTarget, bounds(NovaPageBackTag))
        rule.runOnIdle { cameras.value = listOf(camera) }
        rule.waitForIdle()
        val protectedGlyph = bounds(NovaChevronBackTag)
        val protectedTitle = title()
        val protectedTarget = bounds(NovaPageBackTag)
        val glyphClear = !protectedGlyph.overlaps(camera)
        assertTrue("the real Back pointer envelope remains outside the camera", !protectedTarget.overlaps(camera))
        assertTrue("the actual title remains outside the camera", !protectedTitle.overlaps(camera))
        assertTrue("camera-on Back keeps its48dp target", protectedTarget.height >= minimumTouchHeight - 0.5f)
        rule.onNodeWithTag("detail-action").assertIsFocused()
        rule.onNodeWithTag(NovaPageBackTag).assertIsNotFocused()
        repeat(4) {
            rule.runOnIdle { width.value = if (width.value == 320) 321 else 320 }
            rule.waitForIdle()
            // Leading edge moves with the parent only in RTL. Both directions must settle after
            // a real width change; returning to320 restores the same protected positions.
            if (width.value == 320) {
                assertEquals(protectedGlyph, bounds(NovaChevronBackTag))
                assertEquals(protectedTarget, bounds(NovaPageBackTag))
            }
        }
        rule.runOnIdle { cameras.value = emptyList() }
        rule.waitForIdle()
        assertEquals("removal restores actual no-camera glyph bounds", originalGlyph, bounds(NovaChevronBackTag))
        assertEquals("removal restores actual no-camera title bounds", originalTitle, title())
        assertEquals("removal restores original pointer/semantics envelope", originalTarget, bounds(NovaPageBackTag))
        rule.runOnIdle { cameras.value = listOf(camera) }
        rule.waitForIdle()
        val back = bounds(NovaPageBackTag)
        rule.onRoot().performTouchInput { click(Offset(back.center.x, back.top + 0.5f)) }
        rule.waitForIdle()
        assertEquals("camera-on Back edge invokes the real page pop", "root", state.topEntry?.page?.key)
        println("pageback rtl=$rtl originalGlyph=$originalGlyph originalTarget=$originalTarget camera=$camera protectedGlyph=$protectedGlyph protectedTarget=$protectedTarget")
        assertTrue("the actual hanging chevron clears the side camera", glyphClear)
    }

    @Test fun pushedPageBackClearsItsHangingLeadingGlyph() = pushedPage(rtl = false)
    @Test fun pushedPageBackClearsItsHangingGlyphInRtl() = pushedPage(rtl = true)
}
