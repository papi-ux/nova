package com.papi.nova.ui.panel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.NovaRadius
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NovaPanelTokensTest {

    @Test
    fun standardWidthIsClampedBetween360And440() {
        assertDp(360f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Standard, 640.dp))
        // The RP6 at its default display size.
        assertDp(368.64f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Standard, 768.dp))
        assertDp(440f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Standard, 960.dp))
        assertDp(440f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Standard, 1280.dp))
    }

    @Test
    fun wideWidthIsClampedBetween440And560() {
        assertDp(440f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Wide, 640.dp))
        assertDp(460.8f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Wide, 768.dp))
        assertDp(560f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Wide, 960.dp))
        assertDp(560f, NovaPanelMetrics.panelWidth(NovaPanelWidth.Wide, 1280.dp))
    }

    @Test
    fun aPortraitOrNarrowWindowGetsTheSheet() {
        assertFalse(NovaPanelMetrics.usesSheet(768.dp, 432.dp))
        assertFalse(NovaPanelMetrics.usesSheet(960.dp, 540.dp))
        assertTrue("portrait", NovaPanelMetrics.usesSheet(412.dp, 915.dp))
        assertTrue("square counts as portrait", NovaPanelMetrics.usesSheet(600.dp, 600.dp))
        assertTrue("landscape but under 480dp wide", NovaPanelMetrics.usesSheet(470.dp, 300.dp))
    }

    @Test
    fun televisionRowsAndPaddingAreLarger() {
        val regular = NovaPanelDensity.Regular
        assertDp(52f, NovaPanelMetrics.rowMinHeight(NovaFormFactor.Handheld, regular))
        assertDp(56f, NovaPanelMetrics.rowMinHeight(NovaFormFactor.Television, regular))
        assertDp(16f, NovaPanelMetrics.panelPadding(NovaFormFactor.Handheld, regular))
        assertDp(20f, NovaPanelMetrics.panelPadding(NovaFormFactor.Television, regular))
    }

    @Test
    fun aHandheldWindowUnder560dpTallIsCompactAndATelevisionNeverIs() {
        val handheld = NovaFormFactor.Handheld
        assertDp(560f, NovaPanelMetrics.CompactBelowHeight)
        assertEquals("the RP6 in landscape", NovaPanelDensity.Compact, NovaPanelMetrics.density(handheld, 468.dp))
        assertEquals(NovaPanelDensity.Compact, NovaPanelMetrics.density(handheld, 559.dp))
        assertEquals("560dp is tall enough", NovaPanelDensity.Regular, NovaPanelMetrics.density(handheld, 560.dp))
        assertEquals("a phone in portrait", NovaPanelDensity.Regular, NovaPanelMetrics.density(handheld, 915.dp))
        assertEquals("a television at any height", NovaPanelDensity.Regular, NovaPanelMetrics.density(NovaFormFactor.Television, 468.dp))
    }

    @Test
    fun theRegularScaleKeepsTodaysSizes() {
        val handheld = NovaFormFactor.Handheld
        val regular = NovaPanelDensity.Regular
        assertDp(6f, NovaPanelMetrics.rowGap(regular))
        assertDp(44f, NovaPanelMetrics.valueControlHeight(regular))
        assertDp(44f, NovaPanelMetrics.buttonMinHeight(regular))
        assertDp(48f, NovaPanelMetrics.ArrowTarget)
        assertDp("a value row is as tall as a plain row", 52f, NovaPanelMetrics.valueControlHeight(regular) + NovaPanelMetrics.SpaceXs * 2)
        assertDp("the header sits a panel padding down", 16f, NovaPanelMetrics.headerTopPadding(handheld, regular))
        assertDp("the hint bar keeps a panel padding all round", 16f, NovaPanelMetrics.hintBarMargin(handheld, regular))
        assertType(NovaPanelType.of(handheld, regular), listOf(20, 18, 16, 15, 13, 11), hintKey = 11)
        assertType(NovaPanelType.of(NovaFormFactor.Television, regular), listOf(22, 20, 18, 17, 15, 13), hintKey = 13)
    }

    @Test
    fun theCompactScaleIsTheApprovedOne() {
        val handheld = NovaFormFactor.Handheld
        val compact = NovaPanelDensity.Compact
        assertDp(44f, NovaPanelMetrics.rowMinHeight(handheld, compact))
        assertDp(4f, NovaPanelMetrics.rowGap(compact))
        assertDp(12f, NovaPanelMetrics.panelPadding(handheld, compact))
        assertDp(36f, NovaPanelMetrics.valueControlHeight(compact))
        assertDp(40f, NovaPanelMetrics.buttonMinHeight(compact))
        assertDp("the arrows keep their 48dp wide target", 48f, NovaPanelMetrics.ArrowTarget)
        assertDp("a value row is as tall as a plain row", 44f, NovaPanelMetrics.valueControlHeight(compact) + NovaPanelMetrics.SpaceXs * 2)
        assertDp("one header line for the root and a pushed page", 40f, NovaPanelMetrics.HeaderHeightCompact)
        assertDp(4f, NovaPanelMetrics.headerTopPadding(handheld, compact))
        assertDp("8dp above and below the hint bar; its sides keep the 12dp padding", 8f, NovaPanelMetrics.hintBarMargin(handheld, compact))
        val type = NovaPanelType.of(handheld, compact)
        assertType(type, listOf(18, 16, 14, 13, 12, 10), hintKey = 12)
        val regular = NovaPanelType.of(handheld, NovaPanelDensity.Regular)
        assertEquals("a state page's title keeps its size", regular.stateTitle, type.stateTitle)
        assertEquals("a pairing code keeps its size", regular.code, type.code)
    }

    @Test
    fun aTelevisionKeepsItsScaleEvenWhenCompactIsAskedFor() {
        val tv = NovaFormFactor.Television
        val compact = NovaPanelDensity.Compact
        assertDp(56f, NovaPanelMetrics.rowMinHeight(tv, compact))
        assertDp(20f, NovaPanelMetrics.panelPadding(tv, compact))
        assertDp(20f, NovaPanelMetrics.headerTopPadding(tv, compact))
        assertDp(20f, NovaPanelMetrics.hintBarMargin(tv, compact))
        assertSame(NovaPanelType.of(tv, NovaPanelDensity.Regular), NovaPanelType.of(tv, compact))
    }

    @Test
    fun theViewTokensMirrorTheComposeTokens() {
        val dimens = viewDimens()
        mapOf(
            "nova_radius_chip" to NovaRadius.chip,
            "nova_radius_row" to NovaRadius.row,
            "nova_radius_hero" to NovaRadius.hero,
            "nova_radius_pill" to NovaRadius.pill,
            "nova_radius_drawer" to NovaRadius.drawer,
            "nova_focus_ring_width" to NovaPanelMetrics.FocusRingWidth,
            "nova_row_min_height" to NovaPanelMetrics.RowMinHeight,
            "nova_row_min_height_tv" to NovaPanelMetrics.RowMinHeightTv,
            "nova_row_min_height_compact" to NovaPanelMetrics.RowMinHeightCompact,
        ).forEach { (name, expected) ->
            assertEquals("$name in nova_tokens.xml", "${expected.value.toInt()}dp", dimens[name])
        }
    }

    private fun viewDimens(): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/nova_tokens.xml"))
        val nodes = document.getElementsByTagName("dimen")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }

    private fun assertDp(expected: Float, actual: Dp) {
        assertEquals(expected, actual.value, 0.01f)
    }

    private fun assertDp(message: String, expected: Float, actual: Dp) {
        assertEquals(message, expected, actual.value, 0.01f)
    }

    /** Panel title, page title, row title, value, caption and section label, in sp, then the hint key. */
    private fun assertType(type: NovaPanelType, sizes: List<Int>, hintKey: Int) {
        val styles = listOf(type.panelTitle, type.pageTitle, type.rowTitle, type.value, type.caption, type.sectionLabel)
        listOf("panel title", "page title", "row title", "value", "caption", "section label").forEachIndexed { index, name ->
            assertEquals(name, sizes[index].sp, styles[index].fontSize)
        }
        assertEquals("hint key", hintKey.sp, type.hintKey.fontSize)
    }
}
