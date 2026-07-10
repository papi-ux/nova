package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaDashboardRevampContractTest {
    @Test
    fun laneOneGlobalActionsAreExplicitPillsInTheTopCockpit() {
        val layouts = listOf(
            File("src/main/res/layout/activity_pc_view.xml"),
            File("src/main/res/layout-land/activity_pc_view.xml")
        )

        layouts.forEach { layout ->
            val xml = layout.readText()
            val headerStart = xml.indexOf("@+id/profilesButton")
            val selectorStart = xml.indexOf("@+id/modeServers")
            assertTrue("${layout.path} should have a header before mode cards", headerStart > 0 && selectorStart > headerStart)
            val headerXml = xml.substring(headerStart, selectorStart)
            val lowerToolsXml = xml.substring(selectorStart)

            listOf(
                "@+id/profilesButton",
                "@+id/actionNovaUpdate",
                "@+id/actionStartPolaris",
                "@+id/actionTheme",
                "@+id/actionGithub",
                "@+id/actionSettings",
            ).forEach { id ->
                assertTrue("${layout.path} should place $id in the top cockpit", headerXml.contains(id))
            }

            assertFalse("${layout.path} should not keep the ambiguous old Polaris sync id", xml.contains("@+id/actionPolarisSync"))
            assertFalse("${layout.path} should not keep a duplicate lower Theme chip", lowerToolsXml.contains("@+id/actionTheme"))
            assertFalse("${layout.path} should not keep a duplicate lower GitHub/help chip", lowerToolsXml.contains("@+id/actionHelp"))
            assertFalse("${layout.path} should not keep an empty-state GitHub duplicate after GitHub moved to the top cockpit", xml.contains("@+id/emptyHelp"))

            assertTrue("${layout.path} should make Start Polaris a labeled pill", headerXml.contains("@string/pcview_quick_start_polaris"))
            assertTrue("${layout.path} should make Theme a labeled pill", headerXml.contains("@string/pcview_quick_theme"))
            assertTrue("${layout.path} should make GitHub a labeled pill", headerXml.contains("@string/pcview_quick_github"))
            assertTrue("${layout.path} should use pilled 44dp/22dp top actions", headerXml.contains("""android:layout_height="44dp"""") && headerXml.contains("""app:cornerRadius="22dp""""))
        }
    }

    @Test
    fun laneOnePcViewWiresExplicitGlobalActionsAndFocusLabels() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()

        assertTrue(
            "Start Polaris should be explicit and still launch the Polaris startup flow",
            source.contains("val startPolarisAction = findViewById<View>(R.id.actionStartPolaris)") &&
                source.contains("startPolarisAction?.setOnClickListener { launchPolarisStartupForPreferredHost() }")
        )
        assertFalse("Old polarisSyncAction local should be gone", source.contains("polarisSyncAction"))
        assertTrue(
            "GitHub top pill should open Nova GitHub directly",
            source.contains("val githubAction = findViewById<View>(R.id.actionGithub)") &&
                source.contains("githubAction?.setOnClickListener { HelpLauncher.launchGithub(this@PcView) }")
        )
        assertTrue(
            "Top focus label should include all pilled global actions",
            source.contains("profilesButton to R.string.pcview_quick_profiles") &&
                source.contains("updateAction to R.string.pcview_quick_update_check") &&
                source.contains("startPolarisAction to R.string.pcview_quick_start_polaris") &&
                source.contains("themeAction to R.string.pcview_quick_theme") &&
                source.contains("githubAction to R.string.pcview_quick_github") &&
                source.contains("settingsAction to R.string.pcview_quick_settings")
        )
        assertTrue(
            "Header focus fallback should include the new pilled actions",
            source.contains("focus.id == R.id.actionStartPolaris") &&
                source.contains("focus.id == R.id.actionTheme") &&
                source.contains("focus.id == R.id.actionGithub")
        )
    }

    @Test
    fun updatePillFocusStylingPreservesSharedTopFocusLabel() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val updateStyler = source.substringAfter("private fun styleDashboardUpdatePill")
            .substringBefore("private fun updateDashboardUpdatePill")
        val focusBinder = source.substringAfter("private fun bindTopActionFocusLabel")
            .substringBefore("private fun showThemePicker")

        assertFalse(
            "Update Pill styling must not install its own focus listener after bindTopActionFocusLabel; that overwrites the shared top action label listener",
            updateStyler.contains("setOnFocusChangeListener")
        )
        assertTrue(
            "The shared top action focus listener should refresh Update Pill chrome so its focus stroke still updates",
            focusBinder.contains("R.id.actionNovaUpdate") &&
                focusBinder.contains("updateDashboardUpdatePill()")
        )
    }

    @Test
    fun updateAvailableStatusCopyUsesAvailableNotUpdated() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(
            "Update-available status should say Available; Updated sounds like the app already changed",
            strings.contains("""<string name="pcview_update_status_available">Available</string>""")
        )
    }


    @Test
    fun laneTwoDefinesAdaptiveDashboardShellTokens() {
        val values = File("src/main/res/values/dimens.xml").readText()
        assertTrue(values.contains("nova_dashboard_pill_height"))
        assertTrue(values.contains("nova_dashboard_pill_radius"))
        assertTrue(values.contains("nova_dashboard_rail_width"))
        assertTrue(values.contains("nova_dashboard_rail_gap"))
    }

    @Test
    fun laneTwoPortraitUsesHorizontalPillRailNotSidebar() {
        val portrait = File("src/main/res/layout/activity_pc_view.xml").readText()
        val headerStart = portrait.indexOf("@+id/pcViewHeader")
        val selectorStart = portrait.indexOf("@+id/modeServers")
        assertTrue("portrait should keep header before mode cards", headerStart > 0 && selectorStart > headerStart)
        val headerXml = portrait.substring(headerStart, selectorStart)

        assertTrue("portrait should expose a horizontal scroll container for action pills", headerXml.contains("@+id/dashboardPillRailScroll"))
        assertTrue("portrait should expose dashboardPillRail", headerXml.contains("@+id/dashboardPillRail"))
        assertFalse("portrait should not reserve a landscape cockpit rail", portrait.contains("@+id/dashboardCockpitRail"))
        assertTrue("portrait pill rail should still contain Profiles through Settings", headerXml.indexOf("@+id/profilesButton") < headerXml.indexOf("@+id/actionSettings"))
        assertTrue("portrait update label should be constrained so the rail can scroll cleanly", headerXml.contains("""android:maxWidth="128dp"""") && headerXml.contains("""android:ellipsize="end""""))
    }

    @Test
    fun laneTwoLandscapeUsesLeftCockpitRailBeforeContent() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val railIndex = landscape.indexOf("@+id/dashboardCockpitRail")
        val contentIndex = landscape.indexOf("@+id/dashboardContent")

        assertTrue("landscape should use a dashboardCockpit root", landscape.contains("@+id/dashboardCockpit"))
        assertTrue("landscape should place the cockpit rail before dashboard content", railIndex > 0 && contentIndex > railIndex)
        assertTrue("landscape cockpit rail should use shared rail width token", landscape.contains("@dimen/nova_dashboard_rail_width"))
        assertTrue("landscape shell should be horizontal", landscape.substring(landscape.indexOf("@+id/dashboardCockpit"), contentIndex).contains("""android:orientation="horizontal""""))
        assertFalse("landscape should not use the portrait horizontal pill rail", landscape.contains("@+id/dashboardPillRail"))
        assertTrue("landscape rail should contain top actions before content", landscape.indexOf("@+id/profilesButton") > railIndex && landscape.indexOf("@+id/actionSettings") < contentIndex)
        assertTrue("mode cards should stay in dashboard content for Lane 2", landscape.indexOf("@+id/modeServers") > contentIndex && landscape.indexOf("@+id/modeLibrary") > contentIndex)
    }

}
