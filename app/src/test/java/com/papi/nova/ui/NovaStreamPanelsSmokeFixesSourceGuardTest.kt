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
        // The card's chip says Copied in place; since N26 it says nothing else, only a state.
        val content = read("ui/NovaQuickMenuContent.kt")
        assertTrue(content.contains("val copiedLabel = stringResource(R.string.nova_quick_menu_doctor_copied)"))
        assertTrue(content.contains("chip = if (diagnosis.copied) NovaQuickMenuChip(copiedLabel, NovaQuickMenuTone.INFO) else null"))
    }

    @Test
    fun librarySearchKeepsShowResultsAboveTheKeyboard() {
        val search = read("ui/NovaLibraryPanels.kt")
            .substringAfter("internal fun NovaPageScope.NovaLibrarySearchPage(")
            .substringBefore("\n}\n")
        assertTrue(search.contains(".imePadding()"))
        assertTrue(search.contains(".bringIntoViewRequester(showResultsInView)"))
        assertTrue(search.contains("if (imeUp) {"))
    }

    @Test
    fun polarisSyncOpensOnItsReadOnlySummary() {
        val sync = read("ui/NovaLibraryPanels.kt").substringAfter("internal fun NovaPageScope.NovaPolarisSyncPage(")
        assertTrue(sync.contains("modifier = Modifier.novaInitialFocus().novaRestorableFocus(\"plan\"),"))
        assertTrue(sync.contains("rowModifier = { row, _ -> Modifier.novaRestorableFocus(row.name) },"))
    }
}
