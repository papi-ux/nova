package com.papi.nova

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hosts screen uses the screen it has.
 *
 * papi, 2026-09-21: "yes to the Nova Host menu, apply the UI / UX improvements there as well". On
 * a Retroid Pocket 6 the screen kept an empty band across its top for a status bar that was
 * hidden, its host card stood 20dp inside the label above it, and a host's sheet listed eight
 * actions one to a row and hid the last two below the fold.
 */
class NovaHostsScreenLayoutTest {

    @Test
    fun aHostsSheetLaysItsActionsInTwoColumnsWhereTheyFit() {
        assertEquals("a 16:9 handheld's sheet is 660dp", 2, novaHostSheetColumns(landscape = true, sheetWidthDp = 660f, fontScale = 0.85f))
        assertEquals("a 4:3 handheld's is 588dp", 2, novaHostSheetColumns(landscape = true, sheetWidthDp = 588f, fontScale = 1f))
        assertEquals("upright the sheet is the phone's width and the actions stay in a column", 1, novaHostSheetColumns(landscape = false, sheetWidthDp = 900f, fontScale = 1f))
        assertEquals(1, novaHostSheetColumns(landscape = true, sheetWidthDp = 500f, fontScale = 1f))
        assertEquals("larger type would put the labels on second lines", 1, novaHostSheetColumns(landscape = true, sheetWidthDp = 660f, fontScale = 1.3f))
        assertEquals(2, novaHostSheetColumns(landscape = true, sheetWidthDp = NOVA_HOST_SHEET_TWO_COLUMN_MIN_DP, fontScale = 1f))
    }

    @Test
    fun theSheetAsksTheChromeHowWideItWillStand() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val chrome = File("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt").readText()
        assertTrue(
            "the columns are planned before the sheet is shown, from the width the chrome will give it",
            pcView.contains("sheetWidthDp = NovaSheetChrome.landscapeSheetWidth(this) / resources.displayMetrics.density,") &&
                chrome.contains("val landscapeWidth = landscapeSheetWidth(context, widthFraction, minLandscapeWidthDp, maxLandscapeWidthDp)")
        )
        assertTrue(
            "every action goes through the one builder, Delete PC included, and the last row is closed",
            pcView.contains("menu.action(getString(R.string.pcview_menu_delete_pc), destructive = true) {") &&
                pcView.contains("        menu.finish()\n") &&
                !pcView.contains("addPcSheetAction(")
        )
    }

    @Test
    fun aLoneActionKeepsItsRowStanding() {
        val actions = File("src/main/java/com/papi/nova/PcView.kt").readText()
            .substringAfter("private inner class PcSheetActions(")
            .substringBefore("private fun launchQrScanner()")
        assertTrue(
            "a row takes its height from its children only while every one of them fills it: with a spacer of " +
                "no height beside it, Open Library, alone in its section, measured to nothing and disappeared",
            actions.contains("LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap },") &&
                !actions.contains("LinearLayout.LayoutParams(0, 0, 1f)")
        )
        assertTrue(
            "a label that wraps makes its row taller, and its neighbour grows to match",
            actions.contains("opened.isBaselineAligned = false") &&
                actions.contains("LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {\n                    if (filled > 0) marginStart = gap")
        )
    }

    @Test
    fun aRootKeepsNoRoomForBarsNovaHasHidden() {
        val helper = File("src/main/java/com/papi/nova/utils/UiHelper.kt").readText()
        val insets = helper.substringAfter("private fun rootInsets(").substringBefore("fun padContentForSystemBars(")
        assertTrue(
            "the tappable insets are reported as if the bars were showing, which left a 24dp band across the " +
                "top of every screen built on this while Hide System Bars had taken the bars away",
            helper.contains("val tappableInsets: Insets = rootInsets(activity, windowInsets)") &&
                insets.contains("NovaSystemBars.isManaged(activity) && NovaSystemBars.isHidden(activity)") &&
                insets.contains("windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())")
        )
        assertTrue(
            "a screen whose bars are showing keeps clear of them exactly as it did",
            insets.contains("windowInsets.tappableElementInsets")
        )
    }

    @Test
    fun theLandscapeListStandsOnTheColumnsEdge() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val content = landscape.substringAfter("android:id=\"@+id/dashboardContent\"").substringBefore("android:id=\"@+id/pcViewHostsLabel\"")
        val list = landscape.substringAfter("android:id=\"@+id/pcFragmentContainer\"").substringBefore("android:id=\"@+id/no_pc_found_layout\"")
        assertTrue(
            "the column keeps a right margin to match the rail's left one",
            content.contains("android:paddingEnd=\"10dp\"")
        )
        assertFalse(
            "padded again inside the column, the host card stood 20dp in from the label and the filters above it",
            list.contains("android:paddingStart") || list.contains("android:paddingEnd")
        )
    }
}
