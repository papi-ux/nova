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
    fun aHostsMenuIsOneColumnWithEveryActionInIt() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val menu = File("src/main/java/com/papi/nova/ui/NovaHostSheet.kt").readText()
        // The two columns measured against the sheet's width went with the sheet: a panel menu is
        // one column (spec R4), so the D-pad moves up and down only, and the panel's list scrolls
        // the focused row into view with a row of context, so nothing waits below the fold.
        assertFalse(
            "the host menu plans no columns from a sheet width",
            pcView.contains("novaHostSheetColumns(") || pcView.contains("landscapeSheetWidth(")
        )
        assertTrue(
            "every action goes through the one menu, Delete PC included",
            menu.contains("menu.remove(\n        action(\"delete\", R.string.pcview_menu_delete_pc") &&
                !pcView.contains("addPcSheetAction(")
        )
    }

    @Test
    fun aBottomSheetStandsOnTheBottomOfTheScreen() {
        // The Material sheets are gone; a portrait panel is the panel frame's own sheet, in a window
        // drawn behind the bars.
        val frame = File("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt").readText()
        val window = File("src/main/java/com/papi/nova/ui/panel/NovaPanelWindow.kt").readText()
        val sheet = frame.substringAfter("private fun BoxScope.NovaPanelSheet(")
        assertTrue(
            "measured on a Retroid Pocket 6: Material's container kept padB=55 for a navigation bar reported with " +
                "vis=false, so every sheet stood 24dp above the glass with its square end showing; the panel window " +
                "draws behind the bars and its sheet stands on the bottom edge",
            window.contains("WindowCompat.setDecorFitsSystemWindows(window, false)") &&
                sheet.contains(".align(Alignment.BottomCenter)")
        )
        assertTrue(
            "the sheet keeps clear of a bar that is showing inside its own surface, from the insets there are now, " +
                "so a hidden bar leaves no band under it",
            sheet.contains(".windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))")
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
