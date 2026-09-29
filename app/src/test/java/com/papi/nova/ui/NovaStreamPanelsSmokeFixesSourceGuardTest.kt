package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixes from the 2026-09-29 smoke test on the Command Center, the HUD and Polaris Sync. */
class NovaStreamPanelsSmokeFixesSourceGuardTest {
    private fun read(path: String) = File("src/main/java/com/papi/nova/$path").readText()

    @Test
    fun theHudStepsAwayWhileTheCommandCenterIsOpen() {
        val menu = read("ui/NovaQuickMenu.kt")
        assertTrue(menu.contains("game.setNovaHudCovered(true)"))
        assertTrue(menu.substringAfter("fun onMenuClosed() {").substringBefore("}").contains("game.setNovaHudCovered(false)"))
        assertTrue(read("ui/NovaStreamHud.kt").contains("fun setCovered(covered: Boolean)"))
    }

    @Test
    fun theDoctorCardSaysCopiedInPlaceOfAToast() {
        val game = read("Game.kt")
        assertFalse(
            game.substringAfter("fun copyNovaHudDiagnostics() {").substringBefore("fun showNovaHud()").contains("Toast.makeText"),
        )
        val menu = read("ui/NovaQuickMenu.kt")
        assertTrue(menu.contains("state.copy(diagnosis = state.diagnosis.copy(copied = true))"))
        assertTrue(read("ui/NovaQuickMenuContent.kt").contains("if (diagnosis.copied) stringResource(R.string.nova_quick_menu_doctor_copied)"))
    }

    @Test
    fun polarisSyncOpensOnItsReadOnlySummary() {
        val sync = read("ui/NovaLibraryPanels.kt").substringAfter("internal fun NovaPageScope.NovaPolarisSyncPage(")
        assertTrue(sync.contains("modifier = Modifier.novaInitialFocus().novaRestorableFocus(\"plan\"),"))
        assertTrue(sync.contains("rowModifier = { row, _ -> Modifier.novaRestorableFocus(row.name) },"))
    }
}
