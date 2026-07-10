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
            val headerStart = xml.indexOf("@+id/pcViewHeader")
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
            val expectedActionHeight = if (layout.path.contains("layout-land")) "34dp" else "44dp"
            val expectedActionRadius = if (layout.path.contains("layout-land")) "17dp" else "22dp"
            assertTrue("${layout.path} should use compact pilled top actions", headerXml.contains("""android:layout_height="$expectedActionHeight"""") && headerXml.contains("""app:cornerRadius="$expectedActionRadius""""))
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
    fun laneTwoPortraitUsesVisibleActionGridNotHorizontalScroller() {
        val portrait = File("src/main/res/layout/activity_pc_view.xml").readText()
        val headerStart = portrait.indexOf("@+id/pcViewHeader")
        val selectorStart = portrait.indexOf("@+id/modeServers")
        assertTrue("portrait should keep header before mode cards", headerStart > 0 && selectorStart > headerStart)
        val headerXml = portrait.substring(headerStart, selectorStart)
        val railXml = headerXml.substring(headerXml.indexOf("@+id/dashboardPillRail"), headerXml.indexOf("@+id/topActionFocusLabel"))

        assertFalse("portrait top actions should not hide behind a horizontal scroller", headerXml.contains("@+id/dashboardPillRailScroll"))
        assertTrue("portrait should expose dashboardPillRail", headerXml.contains("@+id/dashboardPillRail"))
        assertFalse("portrait should not reserve a landscape cockpit rail", portrait.contains("@+id/dashboardCockpitRail"))
        assertTrue("portrait action rail should stack rows so every top action is visible on the first screen", railXml.contains("@+id/dashboardPillRailPrimaryRow") && railXml.contains("@+id/dashboardPillRailUtilityRow") && railXml.contains("@+id/dashboardPillRailSystemRow"))
        assertTrue("portrait action rail rows should use full-width equal targets", railXml.contains("""android:layout_width="match_parent"""") && railXml.contains("""android:layout_width="0dp"""") && railXml.contains("android:layout_weight"))
        listOf(
            "@+id/actionStartPolaris",
            "@+id/profilesButton",
            "@+id/actionTheme",
            "@+id/actionGithub",
            "@+id/actionSettings",
            "@+id/actionNovaUpdate",
        ).forEach { id ->
            assertTrue("portrait one-screen action grid should keep $id visible before mode controls", railXml.contains(id))
        }
        assertTrue("portrait update label should remain bounded inside its grid cell", headerXml.contains("""android:maxWidth="128dp"""") && headerXml.contains("""android:ellipsize="end""""))
        assertTrue("portrait controls should start close under the visible action grid to keep hosts on-screen", headerXml.contains("""android:id="@+id/dashboardHomeControls"""") && headerXml.contains("""android:layout_marginTop="10dp"""))
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
        assertTrue("landscape rail should contain top actions before content", landscape.indexOf("@+id/actionStartPolaris") > railIndex && landscape.indexOf("@+id/actionNovaUpdate") < contentIndex)
        assertTrue("mode controls should live in the cockpit rail after Lane 3 polish", landscape.indexOf("@+id/modeServers") in railIndex until contentIndex && landscape.indexOf("@+id/modeLibrary") in railIndex until contentIndex)
    }


    @Test
    fun laneThreeServerLibraryModesRenderAsCompactSelector() {
        val layouts = listOf(
            File("src/main/res/layout/activity_pc_view.xml"),
            File("src/main/res/layout-land/activity_pc_view.xml")
        )

        layouts.forEach { layout ->
            val xml = layout.readText()
            val selectorIndex = xml.indexOf("@+id/dashboardModeSelector")
            val hostsIndex = xml.indexOf("@+id/pcViewHostsLabel")
            assertTrue("${layout.path} should define compact dashboardModeSelector before host content", selectorIndex > 0 && hostsIndex > selectorIndex)
            assertTrue("${layout.path} should expose a compact mode status line", xml.contains("@+id/dashboardModeStatus"))

            val selectorXml = xml.substring(selectorIndex, hostsIndex)
            assertTrue("${layout.path} compact selector should keep Servers mode target", selectorXml.contains("@+id/modeServers"))
            assertTrue("${layout.path} compact selector should keep Library mode target", selectorXml.contains("@+id/modeLibrary"))
            assertTrue("${layout.path} compact selector should use shared pill radius", selectorXml.contains("@dimen/nova_dashboard_pill_radius"))
            assertFalse("${layout.path} should remove hero-mode CURRENT badge copy from dashboard XML", selectorXml.contains("pcview_destination_current"))
            assertFalse("${layout.path} should remove hero-mode OPEN badge copy from dashboard XML", selectorXml.contains("pcview_destination_open"))
            assertFalse("${layout.path} should remove hero-mode summary paragraphs from compact selector", selectorXml.contains("pcview_destination_servers_summary") || selectorXml.contains("pcview_destination_library_summary"))
            assertFalse("${layout.path} mode selector should not be the old 18dp hero card pair", selectorXml.contains("""app:cardCornerRadius="18dp""""))
        }
    }

    @Test
    fun laneThreeSetupActionsAreSecondaryPills() {
        val layouts = listOf(
            File("src/main/res/layout/activity_pc_view.xml"),
            File("src/main/res/layout-land/activity_pc_view.xml")
        )

        layouts.forEach { layout ->
            val xml = layout.readText()
            val setupIndex = xml.indexOf("@+id/setupActionRow")
            val hostsIndex = xml.indexOf("@+id/pcViewHostsLabel")
            assertTrue("${layout.path} should put setup actions in a named secondary row before hosts", setupIndex > 0 && hostsIndex > setupIndex)
            val setupEnd = if (layout.path.contains("layout-land")) {
                xml.indexOf("@+id/topActionFocusLabel")
            } else {
                hostsIndex
            }
            val setupXml = xml.substring(setupIndex, setupEnd)

            assertTrue("${layout.path} should keep Add Server in setupActionRow", setupXml.contains("@+id/actionAddServer"))
            assertTrue("${layout.path} should keep Scan Pair in setupActionRow", setupXml.contains("@+id/actionScanPair"))
            if (layout.path.contains("layout-land")) {
                assertTrue("${layout.path} setup row should fill the compact left rail", setupXml.contains("""android:layout_width="match_parent"""))
                assertTrue("${layout.path} expanded setup actions should split into compact paired rail pills", setupXml.contains("""android:layout_width="0dp""") && setupXml.contains("android:layout_weight"))
                assertTrue("${layout.path} setup actions should use compact rail pill sizing", setupXml.contains("""android:layout_height="34dp""") && setupXml.contains("""app:cornerRadius="17dp"""))
            } else {
                assertTrue("${layout.path} setup row should fill portrait width for large touch targets", setupXml.contains("""android:layout_width="match_parent"""))
                assertTrue("${layout.path} setup actions should use shared pill tokens", setupXml.contains("@dimen/nova_dashboard_pill_height") && setupXml.contains("@dimen/nova_dashboard_pill_radius"))
                assertTrue("${layout.path} setup actions should split into equal-width portrait targets", setupXml.contains("""android:layout_width="0dp""") && setupXml.contains("android:layout_weight"))
            }
        }
    }

    @Test
    fun laneThreePcViewStylesCompactModesAndLiveStatus() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        assertTrue("PcView should style compact mode segments directly", source.contains("private fun styleModeSegment"))
        assertTrue("PcView should update the live dashboard mode status", source.contains("R.id.dashboardModeStatus"))
        assertTrue("PcView should use the compact mode status format string", source.contains("pcview_dashboard_mode_status_format"))
        assertFalse("The old destination hero-card styler should be gone after Lane 3", source.contains("styleDestinationCard"))
    }


    @Test
    fun laneThreePointOneLandscapeGlobalRailIsRaisedNarrowAndOrdered() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val dimens = File("src/main/res/values/dimens.xml").readText()
        val railStart = landscape.indexOf("@+id/dashboardCockpitRail")
        val contentStart = landscape.indexOf("@+id/dashboardContent")
        assertTrue("landscape rail should exist before content", railStart > 0 && contentStart > railStart)
        val railXml = landscape.substring(railStart, contentStart)

        assertTrue("landscape rail should use the compact rail width token", dimens.contains("""<dimen name="nova_dashboard_rail_width">224dp</dimen>"""))
        assertTrue("landscape rail should reserve a collapsed icon width token", dimens.contains("nova_dashboard_rail_collapsed_width"))
        assertTrue("landscape rail top padding should be visually tighter than the current gappy 12dp", railXml.contains("""android:paddingTop="6dp"""))
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        assertTrue("landscape header padding should not add the full status-bar inset", source.contains("headerTopPadding") && source.contains("ORIENTATION_LANDSCAPE") && source.contains("UiHelper.dpToPx(this, 4f).toInt()") && source.contains("topInset + UiHelper.dpToPx(this, 16f).toInt()"))

        val expectedOrder = listOf(
            "@+id/actionStartPolaris",
            "@+id/profilesButton",
            "@+id/actionTheme",
            "@+id/actionGithub",
            "@+id/actionSettings",
            "@+id/actionNovaUpdate",
        )
        expectedOrder.zipWithNext().forEach { (first, second) ->
            assertTrue("$first should appear before $second in the landscape rail", railXml.indexOf(first) in 1 until railXml.indexOf(second))
        }
    }

    @Test
    fun laneThreePointOneLandscapeMovesModeAndSetupControlsIntoLeftRail() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val railStart = landscape.indexOf("@+id/dashboardCockpitRail")
        val contentStart = landscape.indexOf("@+id/dashboardContent")
        val railXml = landscape.substring(railStart, contentStart)
        val contentXml = landscape.substring(contentStart)

        listOf(
            "@+id/dashboardModeSelector",
            "@+id/dashboardModeStatus",
            "@+id/setupActionRow",
            "@+id/modeServers",
            "@+id/modeLibrary",
            "@+id/actionAddServer",
            "@+id/actionScanPair",
        ).forEach { id ->
            assertTrue("$id should move into the landscape left rail", railXml.contains(id))
            assertFalse("$id should not float in landscape dashboardContent", contentXml.contains(id))
        }

        assertFalse("landscape should not show the clipped/redundant host summary", landscape.contains("@+id/pcViewHostsSummary"))
        assertTrue("landscape should keep only the Hosts label over the content list", contentXml.contains("@+id/pcViewHostsLabel"))
    }


    @Test
    fun laneThreePointTwoLandscapeRailHasCollapsibleIconDrawerAnatomy() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val railStart = landscape.indexOf("@+id/dashboardCockpitRail")
        val contentStart = landscape.indexOf("@+id/dashboardContent")
        val railXml = landscape.substring(railStart, contentStart)

        assertTrue("landscape rail should expose a drawer toggle", railXml.contains("@+id/dashboardRailToggle"))
        assertTrue("landscape rail should tag label-bearing actions for collapse", railXml.contains("dashboardRailLabel"))
        assertTrue("collapsed rail needs a dedicated GitHub icon", railXml.contains("@drawable/ic_github"))
        assertTrue("PcView should wire dashboard rail collapse", source.contains("private fun setDashboardRailCollapsed"))
        assertTrue("PcView should use the collapsed rail width token", source.contains("R.dimen.nova_dashboard_rail_collapsed_width"))
        assertTrue("collapsed rail should keep focus labels discoverable", source.contains("topActionFocusLabel"))
    }


    @Test
    fun laneThreePointTwoRailToggleIconStaysCentered() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val collapseIcon = File("src/main/res/drawable/ic_menu_collapse.xml").readText()
        val toggleStart = landscape.indexOf("@+id/dashboardRailToggle")
        val titleStart = landscape.indexOf("@+id/pcViewTitle")
        val toggleXml = landscape.substring(toggleStart, titleStart)

        assertTrue("collapse toggle icon-only button should center its icon", toggleXml.contains("""android:gravity="center"""))
        assertTrue("collapse toggle should not offset the icon with padding", toggleXml.contains("""app:iconPadding="0dp"""))
        assertTrue("collapse toggle should pin a consistent centered icon size", toggleXml.contains("""app:iconSize="20dp"""))
        assertFalse(
            "old collapse icon path rendered visibly left-of-center inside the round toggle",
            collapseIcon.contains("M4,6h16v2H4V6zM4,11h10v2H4v-2zM4,16h16v2H4v-2zM17,10l-4,2l4,2v-4z"),
        )
        assertTrue("runtime collapse icon swaps should preserve centered icon-only gravity", source.contains("toggle.gravity = Gravity.CENTER") && source.contains("toggle.iconPadding = 0"))
    }


    @Test
    fun laneThreePointThreeLandscapeRefinementsStayPolished() {
        val landscape = File("src/main/res/layout-land/activity_pc_view.xml").readText()
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val railXml = landscape.substring(
            landscape.indexOf("@+id/dashboardCockpitRail"),
            landscape.indexOf("@+id/dashboardContent"),
        )
        val setupXml = railXml.substring(railXml.indexOf("@+id/setupActionRow"), railXml.indexOf("@+id/topActionFocusLabel"))
        val modeSelectorXml = railXml.substring(railXml.indexOf("@+id/dashboardHomeControls"), railXml.indexOf("@+id/setupActionRow"))

        assertTrue("Browse controls need slightly more breathing room under the BROWSE label", modeSelectorXml.contains("""android:layout_marginTop="6dp"""))
        assertTrue("setup controls should sit off the live status with a little more air", setupXml.contains("""android:layout_marginTop="8dp"""))
        assertTrue("collapsed setup actions should stack vertically instead of becoming tiny side-by-side batteries", source.contains("private fun setDashboardRailSetupActionsCollapsed") && source.contains("setupRow.orientation = if (collapsed) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL"))
        assertTrue("collapsed setup stack should remove the Scan Pair start margin and add vertical rhythm", source.contains("scanParams.marginStart = if (collapsed) 0 else") && source.contains("scanParams.topMargin = if (collapsed)"))
        assertTrue("rail collapse should animate width instead of hard-jumping", source.contains("private fun animateDashboardRailWidth") && source.contains("ValueAnimator.ofInt") && source.contains("DASHBOARD_RAIL_ANIMATION_MS"))
        assertTrue("landscape rail collapsed state should persist across dashboard recreates", source.contains("PREF_DASHBOARD_RAIL_COLLAPSED") && source.contains("loadDashboardRailCollapsedPreference()") && source.contains("saveDashboardRailCollapsedPreference(collapsed)"))
    }


    @Test
    fun laneThreePointFourPortraitCockpitPrioritizesPrimaryActionsAndFullWidthControls() {
        val portrait = File("src/main/res/layout/activity_pc_view.xml").readText()
        val headerStart = portrait.indexOf("@+id/pcViewHeader")
        val hostsStart = portrait.indexOf("@+id/pcViewHostsLabel")
        val headerXml = portrait.substring(headerStart, hostsStart)
        val railXml = headerXml.substring(headerXml.indexOf("@+id/dashboardPillRail"), headerXml.indexOf("@+id/topActionFocusLabel"))
        val controlsXml = headerXml.substring(headerXml.indexOf("@+id/dashboardHomeControls"))
        val selectorXml = controlsXml.substring(controlsXml.indexOf("@+id/dashboardModeSelector"), controlsXml.indexOf("@+id/dashboardModeStatus"))
        val setupXml = controlsXml.substring(controlsXml.indexOf("@+id/setupActionRow"))

        val expectedOrder = listOf(
            "@+id/actionStartPolaris",
            "@+id/profilesButton",
            "@+id/actionTheme",
            "@+id/actionGithub",
            "@+id/actionSettings",
            "@+id/actionNovaUpdate",
        )
        expectedOrder.zipWithNext().forEach { (first, second) ->
            assertTrue("$first should appear before $second in the portrait quick rail", railXml.indexOf(first) in 1 until railXml.indexOf(second))
        }
        val focusLabelXml = headerXml.substring(headerXml.indexOf("@+id/topActionFocusLabel"), headerXml.indexOf("@+id/dashboardHomeControls"))
        assertTrue("portrait GitHub pill should use the same icon treatment as landscape", railXml.contains("@drawable/ic_github"))
        assertTrue("portrait focus label should stay visually attached to the quick rail instead of drifting to the right edge", focusLabelXml.contains("""android:gravity="center"""))
        assertTrue("portrait mode selector should use full-width equal targets", selectorXml.contains("""android:layout_width="match_parent"""") && selectorXml.contains("""android:layout_width="0dp""") && selectorXml.contains("android:layout_weight"))
        assertTrue("portrait setup actions should use full-width equal touch targets", setupXml.contains("""android:layout_width="match_parent"""") && setupXml.contains("""android:layout_width="0dp""") && setupXml.contains("android:layout_weight"))
    }

}
