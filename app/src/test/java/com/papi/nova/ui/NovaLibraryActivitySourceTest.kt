package com.papi.nova.ui

import java.io.File
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
        assertTrue(source.contains("controllerHintBarLandscapeStartPadding = if (isLandscape && showLandscapeControlRail)"))
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
    fun yButtonCyclesLibraryLayoutWithoutOpeningADrawer() {
        val source = readLibraryActivitySource()
        val hints = sourceBetween(
            source,
            "private fun novaLibraryControllerHints(",
            "@Composable\n    private fun NovaLibraryFocusedBackdrop"
        )

        assertTrue(source.contains("KeyEvent.KEYCODE_BUTTON_Y"))
        assertTrue(source.contains("cycleLibraryLayoutMode()"))
        assertTrue(source.contains("activeOptionsSheet || activeSystemMenu || activeFilterSheet != null"))
        assertTrue(source.contains("optionsState.copy(layoutMode = nextMode)"))
        assertTrue(hints.contains("R.string.nova_controller_hint_y"))
        assertTrue(hints.contains("R.string.nova_controller_hint_layout"))
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
    }
}
