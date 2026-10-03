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
    private val hostStatus = File("src/main/java/com/papi/nova/ui/NovaCommandCenterHostStatus.kt").readText()
    private val state = File("src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt").readText()

    private fun String.section(start: String, end: String): String {
        assertTrue("$start is still there", contains(start))
        return substringAfter(start).substringBefore(end)
    }

    /** NovaCommandCenterLiveTuningResultComposeTest runs the save through to the row. */
    @Test
    fun aFailedLiveTuningSaveIsSaidOnItsRow() {
        val confirm = menu.section("onLiveTuning = ", "onToggleAdvanced = {")
        assertFalse("nothing about the save floats", confirm.contains("NovaSnackbar"))
        assertTrue("the confirm goes through the Command Center's own switch", confirm.contains("host::switchLiveTuning"))
        val save = hostStatus.section("fun switchLiveTuning(", "private fun take(")
        assertTrue("the save goes through the one that says its result on the row", save.contains("liveTuning?.request(enable, observed)"))
        assertFalse(hostStatus.contains("NovaSnackbar"))
        assertFalse(File("src/main/java/com/papi/nova/ui/NovaLiveTuningSave.kt").readText().contains("NovaSnackbar"))
        assertTrue(state.section("private fun liveTuningAction(", "private fun liveTuningCaption(").contains("result != null -> result"))
    }

    @Test
    fun livePictureResultsBelongToTheStreamAndNeverWriteTheNextLaunchPreset() {
        assertTrue(menu.contains("game.novaBitrateAction(::bitrateMenuCurrent)"))
        assertFalse(menu.contains("AutoQualityProfilePreferences.save("))
        val content=File("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt").readText()
        val picture=content.section("private fun NovaPageScope.NovaQuickMenuStabilityCard(", "private fun NovaQuickMenuStaticCard(")
        assertTrue(picture.contains("picture.result ?: picture.reason"))
        assertFalse(picture.contains("NovaSnackbar"))
    }
}
