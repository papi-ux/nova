package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibraryActivitySourceTest {
    private fun readLibraryActivitySource(): String =
        File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()

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
    fun libraryOptionsDrawerOwnsFiltersAndGridCustomization() {
        val source = readLibraryActivitySource()

        assertTrue(source.contains("NovaLibraryOptionsSheet("))
        assertTrue(source.contains("filterState = filterState"))
        assertTrue(source.contains("onPrimaryFilter = onPrimaryFilter"))
        assertTrue(source.contains("NovaLibraryPrimaryFilter.entries.forEach"))
        assertTrue(source.contains("NovaLibraryUiStateMapper.gridColumnsForScreen(\n            configuration.screenWidthDp,\n            isLandscape,\n            model.optionsState.layoutMode\n        )"))
    }
}
