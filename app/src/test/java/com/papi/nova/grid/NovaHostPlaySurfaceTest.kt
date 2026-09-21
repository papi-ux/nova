package com.papi.nova.grid

import com.papi.nova.nvstream.http.ComputerDetails.LibraryState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Someone else's game does not stand between a device and its library.
 *
 * papi, 2026-09-21, with Alan Wake 2 left open on the host by his Deck and nobody streaming it:
 * "i also am stuck at Watch Stream it wont just go to the library". Every other device's card
 * had become Watch Stream, and there was nothing to watch.
 */
class NovaHostPlaySurfaceTest {

    @Test
    fun thisDevicesOwnGameComesFirst() {
        assertEquals(NovaHostPlaySurface.RESUME, novaHostPlaySurface(true, ownedByThisDevice = true, library = LibraryState.AVAILABLE))
        // An older host does not say whose the game is; resuming was the behaviour then and stays.
        assertEquals(NovaHostPlaySurface.RESUME, novaHostPlaySurface(true, ownedByThisDevice = null, library = LibraryState.AVAILABLE))
    }

    @Test
    fun someoneElsesGameLeavesTheLibraryOnePressAway() {
        assertEquals(NovaHostPlaySurface.LIBRARY, novaHostPlaySurface(true, ownedByThisDevice = false, library = LibraryState.AVAILABLE))
        // With no library to open, watching is still the only thing this host offers the device.
        assertEquals(NovaHostPlaySurface.WATCH, novaHostPlaySurface(true, ownedByThisDevice = false, library = LibraryState.UNAVAILABLE))
        assertEquals(NovaHostPlaySurface.WATCH, novaHostPlaySurface(true, ownedByThisDevice = false, library = LibraryState.UNKNOWN))
    }

    @Test
    fun anIdleHostOpensWhatItHas() {
        assertEquals(NovaHostPlaySurface.LIBRARY, novaHostPlaySurface(false, null, LibraryState.AVAILABLE))
        assertEquals(NovaHostPlaySurface.CHECK_LIBRARY, novaHostPlaySurface(false, null, LibraryState.UNKNOWN))
        assertEquals(NovaHostPlaySurface.APP_LIST, novaHostPlaySurface(false, null, LibraryState.UNAVAILABLE))
        assertEquals(NovaHostPlaySurface.APP_LIST, novaHostPlaySurface(false, null, null))
    }

    @Test
    fun theCardItsPillAndItsSheetAskTheSameQuestion() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        val sheet = File("src/main/java/com/papi/nova/ui/NovaHostSheet.kt").readText()
        val press = pcView.substringAfter("private fun openBestPlaySurface(").substringBefore("private fun syncComputerList()")
        assertTrue(
            "a press on the card",
            press.contains("val surface = novaHostPlaySurface(") && press.contains("NovaHostPlaySurface.LIBRARY -> doNovaLibrary(computer)")
        )
        assertTrue(
            "the pill that says where the press leads, and the words beside it",
            adapter.contains("if (surface == NovaHostPlaySurface.LIBRARY) {") &&
                adapter.contains("statusText.setText(R.string.pcview_card_status_in_use)") &&
                adapter.contains("setStatusHint(statusHint, R.string.pcview_card_hint_in_use)")
        )
        assertTrue(
            "the sheet offers the library first then, so its primary is the one the card leads to, and watching is a tile",
            pcView.contains("if (libraryFirst) offerLibrary()") && pcView.contains("if (!libraryFirst) offerLibrary()") &&
                sheet.contains("statusRes = R.string.pcview_card_status_in_use,")
        )
    }

    @Test
    fun theDpadGoesInToManageAndBackOut() {
        assertEquals(NovaHostRowFocusMove.TO_MANAGE, novaHostRowFocusMove(right = true, left = false, onRow = true, onManage = false))
        assertEquals(NovaHostRowFocusMove.TO_ROW, novaHostRowFocusMove(right = false, left = true, onRow = false, onManage = true))
        // Left from the row still leaves the list for the rail, and Right from Manage goes nowhere.
        assertEquals(NovaHostRowFocusMove.NONE, novaHostRowFocusMove(right = false, left = true, onRow = true, onManage = false))
        assertEquals(NovaHostRowFocusMove.NONE, novaHostRowFocusMove(right = true, left = false, onRow = false, onManage = true))
        assertEquals(NovaHostRowFocusMove.NONE, novaHostRowFocusMove(right = false, left = false, onRow = true, onManage = false))

        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        assertTrue(
            "Manage sits inside the row that holds focus, and a focus search never looks inside what it is leaving: " +
                "a controller's only way to a host's sheet was a long press",
            pcView.contains("if (event.action == KeyEvent.ACTION_DOWN && moveFocusWithinHostRow(event.keyCode)) {") &&
                pcView.contains("serverGridView?.findContainingItemView(focus)")
        )
    }

    @Test
    fun aFocusedRowKeepsItsRingWhole() {
        val generic = File("src/main/java/com/papi/nova/grid/GenericGridAdapter.kt").readText()
        val hosts = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        assertTrue(
            "a host's row is as wide as its list, so the poster grid's focus zoom pushed both its ends past the " +
                "list's edge and cut the sides off its focus ring",
            generic.contains("protected open val focusedScale: Float get() = FOCUSED_SCALE") &&
                generic.contains("val scale = if (hasFocus) focusedScale else 1f") &&
                hosts.contains("override val focusedScale: Float get() = 1f")
        )
    }
}
