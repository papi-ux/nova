package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for Modern Settings.
 *
 * Moved verbatim out of NovaComposeSourceGuardTest, so each migration group owns the guards
 * on its own files. Group 4 moved Settings onto the panel foundation: its rows, rail and strip
 * now take the one focus look, and its pane is a page stack.
 */
class NovaSettingsSourceGuardTest {
    @Test
    fun settingsFocusSurfacesUseTheOneFocusLook() {
        val settings = readNovaSettingsScreen()
        // The settings search field is NovaSearchTextField, so its share of this contract is
        // checked where the code actually lives.
        val searchField = readSource("src/main/java/com/papi/nova/ui/compose/NovaSearchTextField.kt")
        val quickPill = settings.section(
            "private fun NovaSettingPill(",
            "@Composable\nprivate fun NovaSettingsCategoryRail("
        )
        val categoryRow = settings.section(
            "private fun NovaCategoryRow(",
            "@Composable\nprivate fun NovaPageScope.NovaSettingsRowsPage("
        )
        val settingRow = settings.section(
            "private fun NovaSettingRow(",
            "@Composable\nprivate fun NovaSettingResetButton("
        )

        assertTrue(
            "the settings search field keeps the stronger 3dp controller outline",
            searchField.contains(".novaFocusMotion(focused = focused)") &&
                searchField.contains("if (focused) 3.dp else 1.dp")
        )
        listOf(quickPill, categoryRow).forEach { section ->
            assertTrue(
                "settings focus surfaces take the one focus look: the 3dp ring inside the shape, no scale, no halo",
                section.contains(".novaFocusRing(") && section.contains(".novaClickable(")
            )
        }
        assertTrue(
            "settings rows are the foundation's rows, which carry the one focus look themselves",
            settingRow.contains("NovaValueRow(") &&
                settingRow.contains("NovaStepperRow(") &&
                settingRow.contains("NovaRow(")
        )
        assertFalse(
            "the settings rows' scale 1.025 and halo went with the one focus look",
            settings.contains("novaFocusMotion") || settings.contains("if (focused) 3.dp")
        )
    }

    @Test
    fun settingsWideLayoutUsesRetroidCompactHierarchyMetrics() {
        val settings = readNovaSettingsScreen()
        val content = settings.section(
            "fun NovaSettingsContent(",
            "@Composable\nprivate fun NovaSettingsCompactHeader("
        )
        val quickStrip = settings.section(
            "private fun NovaSettingsQuickStrip(",
            "@Composable\nprivate fun NovaSettingPill("
        )
        val categoryRail = settings.section(
            "private fun NovaSettingsCategoryRail(",
            "@Composable\nprivate fun NovaSettingsCategoryChips("
        )

        assertTrue(
            "settings should centralize Retroid landscape sizing knobs instead of scattering magic dp constants",
            settings.contains("private object NovaSettingsMetrics") &&
                settings.contains("fun categoryRailWidthDp(): Int = 196") &&
                settings.contains("fun wideColumnSpacingDp(): Int = 14") &&
                settings.contains("fun headerToQuickStripSpacingDp(): Int = 6") &&
                settings.contains("fun quickStripToContentSpacingDp(): Int = 6")
        )
        assertTrue(
            "wide Settings should give browsing rows more room by narrowing the category rail and spacing",
            content.contains(".width(NovaSettingsMetrics.categoryRailWidthDp().dp)") &&
                content.contains("Arrangement.spacedBy(NovaSettingsMetrics.wideColumnSpacingDp().dp)") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.headerToQuickStripSpacingDp().dp))") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.quickStripToContentSpacingDp().dp))")
        )
        assertTrue(
            "the pane is a page stack whose own hint row sits under the rows, so rows never clip the controls",
            content.contains("NovaPageStackHost(") && content.contains("hints = hints")
        )
        // The strip was a fixed 52dp row that scrolled sideways and cut its last pill at the edge,
        // with a gradient to say so. R13: it wraps instead, so every pill is whole.
        assertTrue(
            "the quick strip wraps onto a second line rather than scrolling sideways",
            quickStrip.contains("FlowRow(") && !quickStrip.contains("horizontalScroll")
        )
        assertFalse(
            "no settings text is cut to one line with an ellipsis",
            settings.contains("TextOverflow.Ellipsis") || settings.contains("maxLines = 1")
        )
        // The rail's rows were 44dp at 15sp, 6dp apart, beside 52dp pane rows at 16sp; they are the
        // pane's rows now, so the rail keeps the pane's compact gap rather than one of its own.
        assertTrue(
            "the category rail keeps the pane's compact row gap and pads its ends so no category is cut at rest",
            categoryRail.contains("Arrangement.spacedBy(NovaPanelMetrics.RowGap)") &&
                categoryRail.contains("contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm)")
        )
        val categoryRow = settings.section(
            "private fun NovaCategoryRow(",
            "@Composable\nprivate fun NovaPageScope.NovaSettingsRowsPage("
        )
        assertTrue(
            "a rail category is a pane row: the row tile, a row's least height and the row title type",
            categoryRow.contains(".heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))") &&
                categoryRow.contains(".novaFocusRing(shape, rest = novaRowRest)") &&
                categoryRow.contains("style = novaPanelType.rowTitle")
        )
    }

    @Test
    fun settingsOpensNothingInAWindowOfItsOwn() {
        val settings = readNovaSettingsScreen()
        val composer = readSource("src/main/java/com/papi/nova/preferences/NovaDisplayRoleComposer.kt")

        listOf(settings, composer).forEach { source ->
            assertFalse(
                "a Settings list, slider or text field is a page in the pane, never a dialog (R2, R4)",
                Regex("""(?<!\w)(Alert)?Dialog\(""").containsMatchIn(source) ||
                    source.contains("NovaDialogWindow()") ||
                    source.contains("NovaSelectDialogShell")
            )
        }
        assertTrue(
            "the pane pushes the host's own pages for lists, exact values and text",
            settings.contains("novaSelectChoicePage(") &&
                settings.contains("NovaCommonPage.Slider(") &&
                settings.contains("novaTextFormPage(") &&
                settings.contains("SettingsPage.DisplayRole(")
        )
    }

    private fun readNovaSettingsScreen(): String =
        readSource("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt")

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
