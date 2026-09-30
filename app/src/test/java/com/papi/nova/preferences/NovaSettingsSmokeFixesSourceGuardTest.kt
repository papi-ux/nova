package com.papi.nova.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings fixes from the 2026-09-29 smoke test, pinned where the Compose screen is too large to
 * host under a unit test.
 */
class NovaSettingsSmokeFixesSourceGuardTest {
    private val screen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
    private val settings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()

    @Test
    fun resetsThatCannotBeUndoneSplitInTheirRowWithKeepFocused() {
        // Reset Stream UI Defaults reset every stream UI setting on one A.
        assertTrue(screen.contains("private val SPLIT_CONFIRM_KEYS = setOf(RESET_STREAM_UI_DEFAULTS_KEY, RESET_ON_SCREEN_CONTROLS_KEY)"))
        val split = screen.substringAfter("} else if (definition.key in SPLIT_CONFIRM_KEYS) {").substringBefore("} else {")
        assertTrue(split.contains("NovaSplitConfirm(") && split.contains("stayLabel = stringResource(R.string.nova_settings_keep)"))
        assertTrue(split.contains("onConfirm = { onSetting(definition) }"))
    }

    @Test
    fun clearOnScreenControlsIsHandledInPlaceAndBFromLegacyGoesBackToCompose() {
        val action = settings.substringAfter("private fun handleComposeAction(").substringBefore("private fun checkForNovaUpdate")
        assertTrue(action.contains("\"option_reset_osc_preference\" ->"))
        assertFalse("no floating snackbar on the way to legacy", action.contains("NovaSnackbar"))
        // The row that opened the legacy screen is remembered, and B goes back to Compose on that row.
        assertTrue(action.contains("legacyFallbackRow = definition.key"))
        val back = settings.substringAfter("private val leaveCallback").substringBefore("fun reloadSettings")
        assertTrue(back.contains("if (legacyMode && row != null)") && back.contains("showComposeSettings(returnToRow = row)"))
    }

    @Test
    fun searchTypesInPlaceAndItsClearIsReachable() {
        val field = screen.substringAfter("private fun NovaSettingsSearchField(").substringBefore("private fun NovaSettingsQuickStrip(")
        assertTrue(field.indexOf("NovaInPlaceKeyboard {") in 0 until field.indexOf("NovaSearchTextField("))
        assertTrue("B clears a query first", field.contains("NovaBackHandler(active = query.isNotBlank()) { onClear() }"))
        val decoration = field.substringAfter("{ innerTextField ->").substringBefore("// Beside the field")
        assertFalse("Clear sits beside the field, not inside its decoration", decoration.contains("nova_settings_search_clear"))
    }

    @Test
    fun theShouldersStopAtTheFirstAndLastCategory() {
        assertFalse(screen.contains("Math.floorMod(from + delta, categories.size)"))
        assertTrue(screen.contains("val to = (from + delta).coerceIn(0, categories.lastIndex)"))
    }

    @Test
    fun theThemeRowOpensTheSamePickerAsHostsAndATelevisionKeepsTheStripToOneLine() {
        val select = screen.substringAfter("private fun openSelect(").substringBefore("private fun openSlider(")
        assertTrue(select.contains("com.papi.nova.novaThemePickerPage("))
        assertTrue(screen.contains("maxLines = if (LocalNovaFormFactor.current == NovaFormFactor.Television) 1 else Int.MAX_VALUE,"))
        val availability = File("src/main/java/com/papi/nova/preferences/NovaSettingsAvailability.kt").readText()
        assertTrue(
            "Touchscreen Mode is hidden where there is no touchscreen",
            availability.substringAfter("private val touchOnlyKeys = setOf(").substringBefore(")").contains("\"mouse_mode_list\""),
        )
    }

    @Test
    fun customBitrateIsLeftToTheExactBitratePage() {
        assertTrue(settings.contains("it.key == PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING"))
        assertTrue(screen.contains("exactDivisor = if (definition.isBitrateKbps()) 1000 else 1,"))
    }
}
