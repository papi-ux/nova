package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibraryActivitySourceTest {
    @Test
    fun portraitBackdropUsesTheVisibleLayoutAlongsideSavedStagePortraitRendering() {
        // The companion actual Activity test verifies saved Stage renders Regular in portrait.
        // This guard verifies the shared backdrop receives that visible mode as well.
        val source = readLibraryActivitySource()
        assertTrue(source.contains("val stageMode = isLandscape && model.optionsState.layoutMode == NovaLibraryLayoutMode.STAGE"))
        val backdrop = sourceBetween(source, "NovaLibraryCinematicBackdrop(", "if (surfaces.particlesEnabled)")
        assertTrue(backdrop.contains("strength = if (stageMode)"))
        assertFalse(backdrop.contains("model.optionsState.layoutMode"))
    }
    private fun readLibraryActivitySource(): String =
        File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()

    private fun readLibraryPanelsSource(): String =
        File("src/main/java/com/papi/nova/ui/NovaLibraryPanels.kt").readText()

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
        assertTrue(source.contains("NovaLibraryLandscapeShowcaseStripContent("))
        assertFalse(source.contains("controllerHintBarLandscapeStartPadding"))
        assertTrue(source.contains("NovaLibraryCinematicControllerHints("))
        val landscapeBranch = source.substring(
            source.indexOf("if (isLandscape) {"),
            source.indexOf("} else {", source.indexOf("if (isLandscape) {"))
        )
        assertTrue(landscapeBranch.contains("NovaLibraryLandscapeShowcaseStripContent("))
    }

    @Test
    fun libraryOptionsPanelOwnsFiltersRefreshAndGridCustomization() {
        val source = readLibraryActivitySource()
        val options = sourceBetween(
            readLibraryPanelsSource(),
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )

        // Library Options is a panel at the start edge (R6), no longer a Dialog aligned there.
        assertTrue(readLibraryPanelsSource().contains("get() = if (this is LibraryPage.Options) NovaEdge.Start else NovaEdge.End"))
        assertTrue(options.contains("onClick = { if (isTop) closeThen(action = actions.onRefresh) }"))
        assertTrue(options.contains("stringResource(R.string.nova_refresh)"))
        assertTrue(options.contains("R.string.nova_library_panel_search"))
        // The quick filters change in one row; Sources, More and Sort are pages pushed from it.
        assertTrue(options.contains("val filterOptions = QuickFilters.map"))
        assertTrue(options.contains("push(actions.sourcesPage)") && options.contains("push(actions.morePage)"))
        assertTrue(options.contains("push(actions.sortPage)"))
        assertTrue(source.contains("options = NovaLibrarySortMode.entries.map"))
        assertTrue(options.contains("NovaOption(NovaLibraryLayoutMode.GRID") && options.contains("NovaOption(NovaLibraryLayoutMode.STAGE"))
    }

    @Test
    fun optionRowsKeepLabelsWholeAndTheirDetailsAsCaptions() {
        // The selectable chips squeezed a detail beside each label and cut both with an ellipsis.
        // Panel rows put the detail under the label, where it wraps, so neither is ever cut (R13).
        val source = readLibraryActivitySource()
        val options = readLibraryPanelsSource()
        val rows = File("src/main/java/com/papi/nova/ui/panel/NovaRows.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(source.contains("NovaOption(it, sortModeLabel(it), caption = sortModeDetail(it))"))
        assertTrue(source.contains("layoutCaption = layoutModeDetail(optionsState.layoutMode)"))
        assertTrue(options.contains("caption = ui.layoutCaption"))
        assertFalse(source.contains("private fun NovaSelectableChip("))
        assertFalse(rows.contains("TextOverflow.Ellipsis"))
        assertTrue(
            strings.contains(
                "name=\"nova_library_options_layout_stage_hint\">One game up front with its art, the rest in a row beside it."
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
        // Y changes the grid behind the panels, so it does nothing while one is open.
        assertTrue(source.contains("if (libraryPanelOpen) {\n            return false"))
        assertTrue(source.contains("selectLibraryLayoutMode(nextMode)"))
        assertTrue(source.contains("revealControllerHints(NovaControllerHintChromeEvent.LAYOUT_CHANGED)"))
        assertTrue(hints.contains("R.string.nova_controller_hint_y"))
        assertTrue(hints.contains("R.string.nova_controller_hint_layout"))
        // The grid changing is the answer; a floating snackbar said it again (R6).
        assertFalse(source.substringAfter("private fun cycleLibraryLayoutMode()").substringBefore("override fun onDestroy()").contains("NovaSnackbar"))
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
    fun systemPanelIsAtTheEndEdgeAndOwnsHostLevelActions() {
        val source = readLibraryActivitySource()
        val systemMenu = sourceBetween(
            readLibraryPanelsSource(),
            "internal fun NovaPageScope.NovaLibrarySystemPage(",
            "internal fun NovaPageScope.NovaLibrarySearchPage("
        )

        // System is a panel at the end edge (R6), no longer a Dialog aligned there.
        assertTrue(source.contains("private fun openLibrarySystem()"))
        assertTrue(source.contains("LibraryPage.System(getString(R.string.nova_system_menu_title))"))
        assertTrue(readLibraryPanelsSource().contains("get() = if (this is LibraryPage.Options) NovaEdge.Start else NovaEdge.End"))
        assertTrue(systemMenu.contains("onClick = { leave(actions.onSwitchHost) }"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_switch_host"))
        assertTrue(systemMenu.contains("onClick = { leave(actions.onSettings) }"))
        assertTrue(systemMenu.contains("panel.push(actions.polarisSyncPage())"))
        // The host's console is a page pushed here, as Polaris Sync is, not a browser (N6).
        assertTrue(systemMenu.contains("onClick = { if (isTop) panel.push(actions.hostConsolePage()) }"))
        assertTrue(systemMenu.contains("onClick = { leave(actions.onHelp) }"))
        // About is read in place, on a page pushed over System, not in a Toast after it closes.
        assertTrue(systemMenu.contains("onClick = { if (isTop) panel.push(actions.aboutPage()) }"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_matrix"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_matrix_hint"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_sponsor"))
        assertTrue(systemMenu.contains("R.string.nova_system_menu_sponsor_hint"))
        // Each row names its title and its action together, so Matrix and Sponsor cannot swap.
        val matrixAction = sourceBetween(
            systemMenu,
            "title = R.string.nova_system_menu_matrix,",
            "title = R.string.nova_system_menu_sponsor,"
        )
        val sponsorAction = systemMenu.substring(systemMenu.indexOf("title = R.string.nova_system_menu_sponsor,"))
        assertTrue(matrixAction.contains("leave(actions.onMatrix)"))
        assertFalse(matrixAction.contains("leave(actions.onSponsor)"))
        assertTrue(sponsorAction.contains("leave(actions.onSponsor)"))
        assertFalse(sponsorAction.contains("leave(actions.onMatrix)"))
        assertTrue(source.contains("onSponsor = ::openSponsor"))
        assertTrue(source.contains("onMatrix = ::openMatrixCommunity"))
        assertTrue(source.contains("private fun openMatrixCommunity()"))
        assertTrue(source.contains("HelpLauncher.launchMatrixCommunity(this)"))
        assertTrue(source.contains("private fun openSponsor()"))
        assertTrue(source.contains("HelpLauncher.launchSponsor(this)"))
    }
}
