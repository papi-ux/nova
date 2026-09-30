package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hosts screen fixes from the merged audit, pinned where PcView is too large to host under a unit
 * test: its views come up only after the GL probe and the host service.
 */
class NovaHostsRailAndNoticesSourceGuardTest {
    private val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
    private val rail = pcView.substringAfter("private fun setDashboardRailCollapsed(").substringBefore("private fun applyDashboardRailLabels(")

    // A on the rail toggle keeps focus on it, so no focus change came to redraw its caption, and
    // an expanded rail went on saying "Expand rail" (audit N3).
    @Test
    fun theToggleCaptionFollowsAWithoutAFocusChange() {
        val described = rail.indexOf("toggle.contentDescription = ")
        assertTrue(described >= 0 && rail.indexOf("refreshTopActionCaption()", described) > described)
        val binder = pcView.substringAfter("private fun bindTopActionFocusLabel(").substringBefore("private fun showThemePicker")
        assertTrue(binder.contains("refreshTopActionCaption = {"))
        val labels = pcView.substringAfter("private fun applyDashboardRailLabels(").substringBefore("private fun animateDashboardRailWidth(")
        assertTrue("a button that loses or regains its label asks for the caption again", labels.contains("refreshTopActionCaption()"))
    }

    // Laid out in a rail still widening, "Add Server" and "Scan Pair" wrapped to two lines and were
    // cut for a frame (audit P2).
    @Test
    fun expandingPutsTheLabelsBackOnceTheRailHasItsWidth() {
        assertTrue(
            rail.contains(
                "animateDashboardRailWidth(rail, targetWidth, animate) {\n" +
                    "                if (!dashboardRailCollapsed) applyDashboardRailLabels(rail, collapsed = false)",
            ),
        )
        assertTrue("collapsing takes the labels off first", rail.indexOf("applyDashboardRailLabels(rail, collapsed = true)") in 0 until rail.indexOf("animateDashboardRailWidth("))
        val animate = pcView.substringAfter("private fun animateDashboardRailWidth(").substringBefore("private fun setDashboardRailSetupActionsCollapsed(")
        assertTrue(animate.contains("dashboardRailAnimator?.cancel()"))
        assertTrue(animate.contains("override fun onAnimationEnd(animation: Animator)") && animate.contains("onEnd()"))
    }

    // Pairing, manager and removal results floated as Toasts that were gone before they could be
    // read (audit X2).
    @Test
    fun hostsResultsArePagesNotToasts() {
        assertFalse(pcView.contains("Toast.makeText"))
        assertFalse(pcView.contains("import android.widget.Toast"))
        val notice = pcView.substringAfter("private fun showHostsNotice(").substringBefore("\n    }\n")
        assertTrue(notice.contains("Dialog.displayDialog(this, title, message, false)"))
        val remove = pcView.substringAfter("private fun removeComputer(").substringBefore("private fun checkAutoNavigation(")
        assertFalse("the note that the host still lists this device stays until it is closed", remove.contains("NovaSnackbar"))
    }
}
