package com.papi.nova.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUpdateDashboardAffordanceTest {
    @Test
    fun mainDashboardPlacesUpdatePillNextToProfilesInHeader() {
        val layouts = listOf(
            File("src/main/res/layout/activity_pc_view.xml"),
            File("src/main/res/layout-land/activity_pc_view.xml")
        )

        layouts.forEach { layout ->
            val xml = layout.readText()
            assertFalse(
                "${layout.path} should not use a separate update rail/card pop-out",
                xml.contains("@+id/dashboardUpdateRail")
            )

            val headerStart = xml.indexOf("@+id/pcViewHeader")
            val selectorStart = xml.indexOf("@+id/modeServers")
            assertTrue("${layout.path} should contain a top header before the Server/Library selector", headerStart > 0 && selectorStart > headerStart)
            val headerXml = xml.substring(headerStart, selectorStart)
            val selectorXml = xml.substring(selectorStart, xml.indexOf("@+id/pcViewHostsLabel"))

            val approvedOrder = if (layout.path.contains("layout-land")) {
                listOf("@+id/actionStartPolaris", "@+id/profilesButton", "@+id/actionTheme", "@+id/actionGithub", "@+id/actionSettings", "@+id/actionNovaUpdate")
            } else {
                listOf("@+id/actionStartPolaris", "@+id/profilesButton", "@+id/actionTheme", "@+id/actionGithub", "@+id/actionSettings", "@+id/actionNovaUpdate")
            }
            approvedOrder.zipWithNext().forEach { (first, second) ->
                assertTrue("${layout.path} should keep the approved dashboard action order", headerXml.indexOf(first) in 1 until headerXml.indexOf(second))
            }
            assertFalse(
                "${layout.path} should not leave the update control in the Server/Library selector cluster",
                selectorXml.contains("@+id/actionNovaUpdate")
            )
            val expectedActionHeight = if (layout.path.contains("layout-land")) "34dp" else "44dp"
            val expectedActionRadius = if (layout.path.contains("layout-land")) "17dp" else "22dp"
            val expectedUpdateRadius = if (layout.path.contains("layout-land")) "19dp" else "22dp"
            assertTrue(
                "${layout.path} should make top actions pilled instead of squat rounded-square buttons",
                headerXml.contains("@+id/profilesButton") &&
                    headerXml.contains("""android:layout_height="$expectedActionHeight"""") &&
                    headerXml.contains("""app:cornerRadius="$expectedActionRadius"""")
            )
            assertTrue(
                "${layout.path} should expose an Update Pill with a status light and version label",
                headerXml.contains("""android:id="@+id/actionNovaUpdate"""") &&
                    headerXml.contains("""android:id="@+id/updateStatusLight"""") &&
                    headerXml.contains("""android:id="@+id/updateStatusLabel"""") &&
                    headerXml.contains("""android:id="@+id/updateVersionLabel"""") &&
                    headerXml.contains("""app:cardCornerRadius="$expectedUpdateRadius"""")
            )
        }
    }

    @Test
    fun mainDashboardUpdatePillRunsSecureUpdaterAndShowsCurrentOrAvailableVersion() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        assertTrue(
            "Dashboard update pill should invoke the same in-app GitHub release check instead of opening a browser",
            source.contains("val updateAction = findViewById<View>(R.id.actionNovaUpdate)") &&
                source.contains("updateAction?.setOnClickListener { checkNovaUpdateFromDashboard() }") &&
                source.contains("private fun checkNovaUpdateFromDashboard()") &&
                source.contains("NovaUpdateChecker.checkLatest()") &&
                source.contains("showNovaUpdateDashboardResult(updateResult)") &&
                source.contains("startNovaUpdateInstall(release)")
        )
        assertTrue(
            "Dashboard update pill should keep current/latest version state and update the status light",
            source.contains("private enum class DashboardUpdatePillStatus") &&
                source.contains("updateDashboardUpdatePill(DashboardUpdatePillStatus.CHECKING") &&
                source.contains("updateDashboardUpdatePill(DashboardUpdatePillStatus.AVAILABLE, updateResult.release)") &&
                source.contains("updateDashboardUpdatePill(DashboardUpdatePillStatus.CURRENT, updateResult.release)") &&
                source.contains("setUpdateStatusLight(") &&
                source.contains("R.string.pcview_update_pill_available_version") &&
                source.contains("R.string.pcview_update_pill_current_version") &&
                source.contains("updateAction to R.string.pcview_quick_update_check") &&
                source.contains("setFocusable(R.id.actionNovaUpdate, focusable)") &&
                source.contains("focus.id == R.id.actionNovaUpdate")
        )
    }

    @Test
    fun mainDashboardCurrentAndErrorResultsStayInlineInsteadOfShowingCardDialogs() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val currentHandler = source.substringAfter("private fun showNovaUpdateDashboardCurrent")
            .substringBefore("private fun showNovaUpdateDashboardError")
        val errorHandler = source.substringAfter("private fun showNovaUpdateDashboardError")
            .substringBefore("private fun maybeRunAutomaticNovaUpdateCheck")

        assertFalse(
            "Up-to-date dashboard checks should not spawn a centered card dialog; the Update Pill already carries that state",
            currentHandler.contains("AlertDialog.Builder") || currentHandler.contains("NovaSheetChrome.applyAlertDialogChrome")
        )
        assertFalse(
            "Up-to-date dashboard checks should not show snackbar/toast overlays; the pill itself should carry current state",
            currentHandler.contains("NovaSnackbar") || currentHandler.contains("Toast.makeText")
        )
        assertFalse(
            "Dashboard check failures should not spawn a centered card dialog; keep the pill in Retry state",
            errorHandler.contains("AlertDialog.Builder") || errorHandler.contains("NovaSheetChrome.applyAlertDialogChrome")
        )
        assertTrue(
            "Dashboard check failures may use lightweight NovaSnackbar feedback while leaving the pill in Retry state",
            errorHandler.contains("NovaSnackbar.showError") &&
                errorHandler.contains("R.string.pcview_update_pill_retry_snackbar")
        )
        val checkMethod = source.substringAfter("private fun checkNovaUpdateFromDashboard")
            .substringBefore("private fun showNovaUpdateDashboardResult")
        assertFalse(
            "Dashboard tap should not spawn a checking Toast; the Update Pill should switch to Checking inline",
            checkMethod.contains("Toast.makeText")
        )
    }

}
