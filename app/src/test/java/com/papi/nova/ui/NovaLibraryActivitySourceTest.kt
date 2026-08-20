package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibraryActivitySourceTest {
    private fun readLibraryActivitySource(): String =
        File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()

    private fun sourceBetween(source: String, startMarker: String, endMarker: String): String {
        val startIndex = source.indexOf(startMarker)
        val endIndex = source.indexOf(endMarker, startIndex + startMarker.length)
        assertTrue("Missing source marker: $startMarker", startIndex >= 0)
        assertTrue("Missing source marker after $startMarker: $endMarker", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    @Test
    fun landscapeLibraryControlsAreDrawerFirstInsteadOfPermanentRail() {
        val source = readLibraryActivitySource()

        assertTrue(source.contains("val showLandscapeControlRail = NovaLibraryUiStateMapper.showLandscapeControlRail()"))
        assertTrue(source.contains("NovaLibraryLandscapeToolbar("))
        assertFalse(source.contains("controllerHintBarLandscapeStartPadding"))
        assertTrue(source.contains("NovaLibraryCinematicControllerHints("))
        val landscapeBranch = source.substring(
            source.indexOf("if (isLandscape) {"),
            source.indexOf("} else {", source.indexOf("if (isLandscape) {"))
        )
        assertTrue(landscapeBranch.contains("NovaLibraryLandscapeToolbar("))
    }

    @Test
    fun libraryOptionsDrawerOwnsFiltersRefreshAndGridCustomization() {
        val source = readLibraryActivitySource()
        val optionsSheet = sourceBetween(
            source,
            "private fun NovaLibraryOptionsSheet(",
            "@OptIn(ExperimentalMaterial3Api::class)"
        )

        assertTrue(optionsSheet.contains("onRefresh: () -> Unit"))
        assertTrue(optionsSheet.contains("align(Alignment.CenterStart)"))
        assertTrue(optionsSheet.contains("NovaSearchField("))
        assertTrue(optionsSheet.contains("NovaLibraryPrimaryFilter.entries.forEach"))
        assertTrue(optionsSheet.contains("stringResource(R.string.nova_refresh)"))
        assertTrue(optionsSheet.contains("NovaLibrarySortMode.entries.forEach"))
        assertTrue(optionsSheet.contains("NovaLibraryLayoutMode.entries.forEach"))
    }

    @Test
    fun selectableChipsReserveVisibleLabelSpaceBeforeLongDetails() {
        val source = readLibraryActivitySource()
        val chip = sourceBetween(
            source,
            "private fun NovaSelectableChip(",
            "@Composable\n    private fun NovaLibraryPanel("
        )
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(chip.contains("modifier = Modifier.weight(0.34f)"))
        assertTrue(chip.contains("modifier = Modifier.weight(0.66f)"))
        assertTrue(chip.windowed("overflow = TextOverflow.Ellipsis".length).count { it == "overflow = TextOverflow.Ellipsis" } >= 2)
        assertTrue(
            strings.contains(
                "name=\"nova_library_options_layout_stage_hint\">Artwork-first home with hero environment, icon identity, and immediate actions."
            )
        )
    }

    @Test
    fun yButtonCyclesLibraryLayoutWithoutOpeningADrawer() {
        val source = readLibraryActivitySource()
        val hints = sourceBetween(
            source,
            "private fun novaLibraryControllerHints(",
            "@Composable\n    private fun NovaLibraryHomeHero("
        )

        assertTrue(source.contains("KeyEvent.KEYCODE_BUTTON_Y"))
        assertTrue(source.contains("cycleLibraryLayoutMode()"))
        assertTrue(source.contains("activeOptionsSheet || activeSystemMenu || activeFilterSheet != null"))
        assertTrue(source.contains("selectLibraryLayoutMode(nextMode)"))
        assertTrue(source.contains("revealControllerHints(NovaControllerHintChromeEvent.LAYOUT_CHANGED)"))
        assertTrue(hints.contains("R.string.nova_controller_hint_y"))
        assertTrue(hints.contains("R.string.nova_controller_hint_layout"))
    }

    @Test
    fun portraitHeaderWiresSharedRightAlignedToolbarWithoutASecondMetadataRow() {
        val source = readLibraryActivitySource()
        val header = sourceBetween(
            source,
            "private fun NovaLibraryTopHeader(",
            "private fun NovaLibraryCompactMetaRow(",
        )

        assertTrue(header.contains("NovaLibraryPortraitToolbarContent("))
        assertTrue(header.contains("hostLabel = serverName?.takeIf { it.isNotBlank() } ?: serverHost"))
        assertTrue(header.contains("resultCount = model.resultCount"))
        assertTrue(header.contains("layoutLabel = layoutModeLabel(model.optionsState.layoutMode)"))
        assertTrue(header.contains("identityStatus = {"))
        assertTrue(header.contains("NovaLibraryCompactMetaRow("))
        assertTrue(header.contains("onOpenOptions = onOpenOptions"))
        assertTrue(header.contains("onOpenSystemMenu = onOpenSystemMenu"))
        assertTrue(
            "portrait Activity should delegate action sizing/order to the internal shared toolbar",
            !header.contains("NovaActionButton(") && !header.contains("minHeight = 36.dp"),
        )
    }

    @Test
    fun systemMenuIsRightDrawerAndOwnsHostLevelActions() {
        val source = readLibraryActivitySource()
        val systemMenu = sourceBetween(
            source,
            "private fun NovaSystemMenuSheet(",
            "@OptIn(ExperimentalFoundationApi::class)"
        )

        assertTrue(systemMenu.contains("DialogProperties(usePlatformDefaultWidth = false)"))
        assertTrue(systemMenu.contains("align(Alignment.CenterEnd)"))
        assertTrue(systemMenu.contains("onSwitchHost: () -> Unit"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_switch_host"))
        assertTrue(systemMenu.contains("onOpenSettings: () -> Unit"))
        assertTrue(systemMenu.contains("onOpenPolarisSync: () -> Unit"))
        assertTrue(systemMenu.contains("onManageServer: () -> Unit"))
        assertTrue(systemMenu.contains("onOpenHelpDiagnostics: () -> Unit"))
        assertTrue(systemMenu.contains("onOpenAbout: () -> Unit"))
        assertTrue(systemMenu.contains("onOpenMatrixCommunity: () -> Unit"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_matrix"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_matrix_hint"))
        assertTrue(systemMenu.contains("onOpenMatrixCommunity()"))
        assertTrue(systemMenu.contains("onOpenSponsor: () -> Unit"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_sponsor"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_sponsor_hint"))
        assertTrue(systemMenu.contains("onOpenSponsor()"))
        val matrixAction = sourceBetween(
            systemMenu,
            "text = stringResource(R.string.nova_system_menu_matrix)",
            "text = stringResource(R.string.nova_system_menu_sponsor)"
        )
        val sponsorAction = sourceBetween(
            systemMenu,
            "text = stringResource(R.string.nova_system_menu_sponsor)",
            "Spacer(modifier = Modifier.height(4.dp))"
        )
        assertTrue(matrixAction.contains("onOpenMatrixCommunity()"))
        assertFalse(matrixAction.contains("onOpenSponsor()"))
        assertTrue(sponsorAction.contains("onOpenSponsor()"))
        assertFalse(sponsorAction.contains("onOpenMatrixCommunity()"))
        assertTrue(matrixAction.contains("minHeight = 48.dp"))
        assertTrue(sponsorAction.contains("minHeight = 48.dp"))
        assertTrue(systemMenu.contains("fontSize = 9.sp"))
        assertTrue(source.contains("onOpenSponsor = ::openSponsor"))
        assertTrue(source.contains("onOpenMatrixCommunity = ::openMatrixCommunity"))
        assertTrue(source.contains("private fun openMatrixCommunity()"))
        assertTrue(source.contains("HelpLauncher.launchMatrixCommunity(this)"))
        assertTrue(source.contains("private fun openSponsor()"))
        assertTrue(source.contains("HelpLauncher.launchSponsor(this)"))
    }
}
