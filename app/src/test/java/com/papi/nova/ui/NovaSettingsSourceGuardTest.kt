package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for Modern Settings.
 *
 * Moved verbatim out of NovaComposeSourceGuardTest, so each migration group owns the guards
 * on its own files.
 */
class NovaSettingsSourceGuardTest {
    @Test
    fun settingsRowsUseSharedFocusMotionAndHighContrastOutline() {
        val settings = readNovaSettingsScreen()
        // The settings search field is NovaSearchTextField now, so its share of this
        // contract is checked where the code actually lives.
        val searchField = readSource("src/main/java/com/papi/nova/ui/compose/NovaSearchTextField.kt")
        val quickPill = settings.section(
            "private fun NovaSettingPill(",
            "@Composable\nprivate fun NovaSettingsCategoryRail("
        )
        val categoryRow = settings.section(
            "private fun NovaCategoryRow(",
            "@Composable\nprivate fun NovaSettingsRows("
        )
        val settingRow = settings.section(
            "private fun NovaSettingRow(",
            "@Composable\nprivate fun NovaSettingApplyBadge("
        )

        listOf(searchField, quickPill, categoryRow, settingRow).forEach { section ->
            assertTrue(
                "settings focus surfaces should use Nova focus motion",
                section.contains(".novaFocusMotion(focused = focused, pressed = false)")
            )
            assertTrue(
                "settings focus surfaces should match the stronger 3dp controller outline",
                // Matched ".border(if (focused) 3.dp" before, which is one spelling of the
                // rule. The shared search field passes the same widths as named arguments and
                // was no less compliant for it, so the check is on the widths themselves.
                section.contains("if (focused) 3.dp else 1.dp")
            )
        }
    }

    @Test
    fun settingsWideLayoutUsesRetroidCompactHierarchyMetrics() {
        val settings = readNovaSettingsScreen()
        val content = settings.section(
            "fun NovaSettingsContent(",
            "@Composable\nprivate fun novaSettingsControllerHints()"
        )
        val quickStrip = settings.section(
            "private fun NovaSettingsQuickStrip(",
            "@Composable\nprivate fun NovaSettingPill("
        )
        val quickPill = settings.section(
            "private fun NovaSettingPill(",
            "@Composable\nprivate fun NovaSettingsCategoryRail("
        )
        val categoryRail = settings.section(
            "private fun NovaSettingsCategoryRail(",
            "@Composable\nprivate fun NovaSettingsCategoryChips("
        )
        val categoryRow = settings.section(
            "private fun NovaCategoryRow(",
            "@Composable\nprivate fun NovaSettingsRows("
        )
        val rows = settings.section(
            "private fun NovaSettingsRows(",
            "@Composable\nprivate fun NovaSettingRow("
        )
        val settingRow = settings.section(
            "private fun NovaSettingRow(",
            "@Composable\nprivate fun NovaSettingApplyBadge("
        )
        val applyBadge = settings.section(
            "private fun NovaSettingApplyBadge(",
            "@Composable\nprivate fun NovaSettingValueChip("
        )
        val valueChip = settings.section(
            "private fun NovaSettingValueChip(",
            "@Composable\nprivate fun NovaSettingDialog("
        )

        assertTrue(
            "settings should centralize Retroid landscape sizing knobs instead of scattering magic dp constants",
            settings.contains("private object NovaSettingsMetrics") &&
                settings.contains("fun categoryRailWidthDp(): Int = 196") &&
                settings.contains("fun wideColumnSpacingDp(): Int = 14") &&
                settings.contains("fun quickStripHeightDp(): Int = 52") &&
                settings.contains("fun quickPillWidthDp(): Int = 168") &&
                settings.contains("fun headerToQuickStripSpacingDp(): Int = 6") &&
                settings.contains("fun quickStripToContentSpacingDp(): Int = 6") &&
                settings.contains("fun contentToHintSpacingDp(): Int = 4") &&
                settings.contains("fun settingsRowSpacingDp(): Int = 6") &&
                settings.contains("fun categoryRowVerticalPaddingDp(): Int = 6") &&
                settings.contains("fun settingsRowVerticalPaddingDp(): Int = 6") &&
                settings.contains("fun valueChipMinHeightDp(): Int = 28")
        )
        assertTrue(
            "wide Settings should give browsing rows more room by narrowing the category rail and spacing",
            content.contains(".width(NovaSettingsMetrics.categoryRailWidthDp().dp)") &&
                content.contains("Arrangement.spacedBy(NovaSettingsMetrics.wideColumnSpacingDp().dp)") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.headerToQuickStripSpacingDp().dp))") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.quickStripToContentSpacingDp().dp))") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.contentToHintSpacingDp().dp))")
        )
        assertTrue(
            "quick settings should stay useful but stop dominating Retroid first paint height",
            quickStrip.contains(".height(NovaSettingsMetrics.quickStripHeightDp().dp)") &&
                quickPill.contains(".width(NovaSettingsMetrics.quickPillWidthDp().dp)") &&
                quickPill.contains(".heightIn(min = NovaSettingsMetrics.quickStripHeightDp().dp)")
        )
        assertTrue(
            "category rail and rows should use compact spacing/padding so more settings are visible above the hint bar",
            categoryRail.contains("Arrangement.spacedBy(NovaSettingsMetrics.categoryRailSpacingDp().dp)") &&
                categoryRow.contains("vertical = NovaSettingsMetrics.categoryRowVerticalPaddingDp().dp") &&
                rows.contains("Arrangement.spacedBy(NovaSettingsMetrics.settingsRowSpacingDp().dp)") &&
                rows.contains("PaddingValues(bottom = NovaSettingsMetrics.rowsBottomPaddingDp().dp)") &&
                settingRow.contains("vertical = NovaSettingsMetrics.settingsRowVerticalPaddingDp().dp")
        )
        assertTrue(
            "Settings text should declare compact line heights instead of inheriting oversized Material body metrics on RP6",
            quickPill.contains("lineHeight = 11.sp") &&
                quickPill.contains("lineHeight = 14.sp") &&
                categoryRow.contains("lineHeight = 16.sp") &&
                categoryRow.contains("lineHeight = 12.sp") &&
                settingRow.contains("lineHeight = 16.sp") &&
                settingRow.contains("lineHeight = 13.sp") &&
                applyBadge.contains("lineHeight = 11.sp") &&
                valueChip.contains("lineHeight = 14.sp")
        )
        assertTrue(
            "value chips should be compact enough to preserve title/summary room in the right column",
            valueChip.contains(".widthIn(min = 92.dp, max = 220.dp)") &&
                valueChip.contains(".heightIn(min = NovaSettingsMetrics.valueChipMinHeightDp().dp)")
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
