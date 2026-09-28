package com.papi.nova.ui.panel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.NovaRadius
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertDp(52f, NovaPanelMetrics.rowMinHeight(NovaFormFactor.Handheld))
        assertDp(56f, NovaPanelMetrics.rowMinHeight(NovaFormFactor.Television))
        assertDp(16f, NovaPanelMetrics.panelPadding(NovaFormFactor.Handheld))
        assertDp(20f, NovaPanelMetrics.panelPadding(NovaFormFactor.Television))
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
}
