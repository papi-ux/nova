package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.NovaPlaySetupPanel
import com.papi.nova.ui.PlaySetupPage
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every panel host draws at the density for its window: compact on a landscape handheld under
 * 560dp tall, as the RP6 is at 833 x 468dp, and regular in a taller window or on a television.
 * The hosts are the frame, which every panel is drawn in (the library's, a host's menu, the
 * Command Center, Play Setup, and the keyboard and form pages inside them), a state page and
 * Settings (NovaSettingsPaneComposeTest). A compact page header is one line, so a pushed page's
 * rows start where the root's did.
 *
 * Robolectric lays a line of text out taller than a device does, so a one line row here is taller
 * than its least height at either density; the sizes are read where the pages read them instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp")
class NovaPanelDensityComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class TestPage(override val key: String, override val title: String) : NovaPage

    private val state = NovaPanelState()
    private var seen: NovaPanelDensity? = null
    private var seenRowMinHeight: Dp? = null

    /** A panel in its frame, filling the window, with one row on each page. */
    private fun panel(television: Boolean = false) {
        state.open(TestPage("root", "Root"))
        rule.setPanelContent {
            val formFactor = if (television) NovaFormFactor.Television else LocalNovaFormFactor.current
            CompositionLocalProvider(LocalNovaFormFactor provides formFactor) {
                NovaPanelFrame(
                    edge = NovaEdge.End,
                    width = NovaPanelWidth.Standard,
                    open = true,
                    onDismissRequest = {},
                    onClosed = {},
                    scrim = NovaScrim.None,
                ) {
                    NovaPageStackHost(state = state) { page ->
                        seen = LocalNovaPanelDensity.current
                        seenRowMinHeight = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current)
                        NovaRow(title = "${page.title} row", onClick = {}, modifier = Modifier.testTag("row-${page.key}"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** The size [text] is laid out at. */
    private fun fontSize(text: String): TextUnit {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.fontSize
    }

    private fun top(tag: String): Dp = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    private fun assertDp(message: String, expected: Dp, actual: Dp) {
        assertEquals(message, expected.value, actual.value, 0.5f)
    }

    @Test
    fun aPanelOnAShortLandscapeHandheldIsCompact() {
        panel()

        assertEquals(NovaPanelDensity.Compact, seen)
        assertEquals("a compact row", NovaPanelMetrics.RowMinHeightCompact, seenRowMinHeight)
        assertEquals("a compact row title", 14.sp, fontSize("Root row"))
        assertEquals("a compact root title", 18.sp, fontSize("Root"))
    }

    @Test
    fun aCompactPushedPageStartsItsRowsWhereTheRootDid() {
        panel()
        val rootTop = top("row-root")

        rule.runOnIdle { state.push(TestPage("pushed", "Pushed")) }
        rule.waitForIdle()

        rule.onNode(androidx.compose.ui.test.hasTestTag(com.papi.nova.ui.panel.NovaPageBackTag) and androidx.compose.ui.test.hasText("Pushed")).assertExists()
        assertDp("the one line header is as tall as the root's, so nothing moves", rootTop, top("row-pushed"))
        val back = rule.onNode(androidx.compose.ui.test.hasTestTag(com.papi.nova.ui.panel.NovaPageBackTag) and androidx.compose.ui.test.hasText("Pushed")).getUnclippedBoundsInRoot()
        assertTrue(
            "the back target keeps the compact header's height",
            back.bottom - back.top >= NovaPanelMetrics.HeaderHeightCompact - 0.5.dp,
        )
    }

    /** Pushes a page over the root and checks the rows start where the root's did. */
    private fun assertAPushedPageStartsItsRowsWhereTheRootDid(expectedHeader: Dp) {
        val rootTop = top("row-root")

        rule.runOnIdle { state.push(TestPage("pushed", "Pushed")) }
        rule.waitForIdle()

        val back = rule.onNode(androidx.compose.ui.test.hasTestTag(com.papi.nova.ui.panel.NovaPageBackTag) and androidx.compose.ui.test.hasText("Pushed"))
        back.assertExists()
        assertDp("the one line header is as tall as the root's, so nothing moves", rootTop, top("row-pushed"))
        rule.onNodeWithText("Root").assertDoesNotExist()
        val bounds = back.getUnclippedBoundsInRoot()
        assertTrue("the back target keeps the header's height", bounds.bottom - bounds.top >= expectedHeader - 0.5.dp)
    }

    @Test
    fun aTelevisionPushedPageStartsItsRowsWhereTheRootDid() {
        panel(television = true)
        assertAPushedPageStartsItsRowsWhereTheRootDid(NovaPanelMetrics.HeaderHeight)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aTallWindowsPushedPageStartsItsRowsWhereTheRootDid() {
        panel()
        assertAPushedPageStartsItsRowsWhereTheRootDid(NovaPanelMetrics.HeaderHeight)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aTallWindowKeepsTheRegularScale() {
        panel()

        assertEquals(NovaPanelDensity.Regular, seen)
        assertEquals("a regular row", NovaPanelMetrics.RowMinHeight, seenRowMinHeight)
        assertEquals("a regular row title", 16.sp, fontSize("Root row"))
    }

    @Test
    fun aTelevisionIsNeverCompactHoweverShortItsWindow() {
        panel(television = true)

        assertEquals(NovaPanelDensity.Regular, seen)
        assertEquals("a television row", NovaPanelMetrics.RowMinHeightTv, seenRowMinHeight)
        assertEquals("a television row title", 18.sp, fontSize("Root row"))
    }

    @Test
    fun aStatePageIsCompactToo() {
        rule.setPanelContent {
            NovaStateScreen(
                NovaStatePage.Problem(
                    key = "lost",
                    title = "Connection Lost",
                    message = "The host stopped answering.",
                    primary = NovaAction("Reconnect") {},
                    back = NovaProblemBack.Absorb,
                ),
            )
        }
        rule.waitForIdle()

        assertEquals("a state page's message in the compact row title size", 14.sp, fontSize("The host stopped answering."))
        assertEquals("a state page's button label in the compact value size", 13.sp, fontSize("Reconnect"))
    }

    @Test
    fun playSetupIsCompact() {
        val setup = NovaPanelState().apply { open(PlaySetupPage.Root("Play Setup")) }
        rule.setPanelContent {
            Box(Modifier.requiredSize(833.dp, 468.dp)) {
                NovaPlaySetupPanel(panel = setup, onClose = {}) { seen = LocalNovaPanelDensity.current }
            }
        }
        rule.waitForIdle()

        assertEquals(NovaPanelDensity.Compact, seen)
    }
}
