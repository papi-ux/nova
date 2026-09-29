package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command Center results are said in the row that asked for them, never in a snackbar that floats
 * over the stream and is gone before it can be read (X2, the in-game pass; the screens outside the
 * stream are held to the same rule by NoFloatingResultsSourceGuardTest). A failed Live Tuning save
 * floated an error, and a Launch Preset pick floated "Launch preset saved for next launch".
 */
class NovaCommandCenterResultsInPlaceTest {
    private val menu = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()
    private val state = File("src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt").readText()

    private fun String.section(start: String, end: String): String {
        assertTrue("$start is still there", contains(start))
        return substringAfter(start).substringBefore(end)
    }

    @Test
    fun aFailedLiveTuningSaveIsSaidOnItsRow() {
        val save = menu.section("onLiveTuning = {", "onToggleAdvanced = {")
        assertFalse("nothing about the save floats", save.contains("NovaSnackbar"))
        assertTrue("the failure is the row's caption", save.contains("liveTuningResult = game.getString(R.string.nova_cc_live_tuning_unconfirmed)"))
        assertTrue(state.section("private fun liveTuningAction(", "private fun liveTuningCaption(").contains("result != null -> result"))
    }

    @Test
    fun aSavedLaunchPresetIsSaidOnItsRow() {
        val pick = menu.section("onProfilePreference = {", "onQuickKey = {")
        assertFalse("nothing about the pick floats", pick.contains("NovaSnackbar"))
        assertTrue("the save is the row's caption", pick.contains("launchPresetSaved = true"))
        assertTrue(state.contains("launchPresetSaved -> context.getString(\n                        R.string.nova_quick_menu_profile_preference_saved"))
    }
}
