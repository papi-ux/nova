package com.papi.nova.grid

import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails.LibraryState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * That same game had no stream at all, and Watch was offered for it on every card and sheet,
     * because a host said only that it was busy. Polaris now says whether anyone is streaming.
     */
    @Test
    fun aGameNobodyIsStreamingIsNotOfferedToWatch() {
        assertEquals(NovaHostPlaySurface.LIBRARY, novaHostPlaySurface(true, false, LibraryState.AVAILABLE, watchable = false))
        assertEquals(
            "with nothing to watch, the host is opened for what it has, as if it were idle",
            NovaHostPlaySurface.CHECK_LIBRARY,
            novaHostPlaySurface(true, false, LibraryState.UNKNOWN, watchable = false),
        )
        assertEquals(NovaHostPlaySurface.APP_LIST, novaHostPlaySurface(true, false, LibraryState.UNAVAILABLE, watchable = false))
        // A host that says it is being streamed, and one that says nothing: watching stays on offer.
        assertEquals(NovaHostPlaySurface.WATCH, novaHostPlaySurface(true, false, LibraryState.UNAVAILABLE, watchable = true))
        assertEquals(NovaHostPlaySurface.WATCH, novaHostPlaySurface(true, false, LibraryState.UNAVAILABLE, watchable = null))
        // Whatever the host says about watching, this device's own game is resumed.
        assertEquals(NovaHostPlaySurface.RESUME, novaHostPlaySurface(true, true, LibraryState.AVAILABLE, watchable = false))
    }

    @Test
    fun theCardSaysWhoseGameItIsAndOnlyPointsAtAWatchThereIs() {
        val idle = novaHostInUse("Steam Deck", watchable = false)
        assertEquals(R.string.pcview_card_status_in_use_named, idle.statusRes)
        assertEquals("Steam Deck", idle.owner)
        assertEquals(R.string.pcview_card_hint_in_use_idle, idle.cardHintRes)
        assertEquals(R.string.pcview_sheet_hint_in_use_idle, idle.sheetHintRes)
        assertFalse("a tile that can only answer that there is nothing to watch is not an offer", idle.offersWatch)

        val streaming = novaHostInUse("Steam Deck", watchable = true)
        assertEquals(R.string.pcview_card_status_watchable_named, streaming.statusRes)
        assertEquals(R.string.pcview_card_hint_in_use, streaming.cardHintRes)
        assertTrue(streaming.offersWatch)

        val olderHost = novaHostInUse(null, watchable = null)
        assertEquals("a host that says nothing reads exactly as it did", R.string.pcview_card_status_in_use, olderHost.statusRes)
        assertNull(olderHost.owner)
        assertEquals(R.string.pcview_card_hint_in_use, olderHost.cardHintRes)
        assertTrue(olderHost.offersWatch)
        assertEquals(R.string.pcview_card_status_watchable, novaHostInUse(" ", watchable = true).statusRes)

        // The pill beside those words says where the press leads, in the words an idle host uses.
        assertEquals(R.string.pcview_card_action_open_library, novaHostOwnWayInLabel(NovaHostPlaySurface.LIBRARY))
        assertEquals(R.string.pcview_card_action_checking_library, novaHostOwnWayInLabel(NovaHostPlaySurface.CHECK_LIBRARY))
        assertEquals(R.string.pcview_card_action_open_apps, novaHostOwnWayInLabel(NovaHostPlaySurface.APP_LIST))
    }

    @Test
    fun anIdIsNeverShownAsIfItWereAName() {
        assertEquals("Steam Deck", novaHostOwnerLabel("  Steam Deck \n"))
        assertNull("a Desktop session's owner field is the owner's id", novaHostOwnerLabel("028454EC-5E39-B32D-2B87-83132A127653"))
        assertNull(novaHostOwnerLabel(null))
        assertNull(novaHostOwnerLabel("   "))
        assertEquals(40, novaHostOwnerLabel("x".repeat(90))!!.length)
        assertEquals("90", novaWatchRate(90f))
        assertEquals("59.94", novaWatchRate(59.94f))
        assertEquals("60", novaWatchRate(60.000004f))
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
            adapter.contains("if (surface != NovaHostPlaySurface.RESUME && surface != NovaHostPlaySurface.WATCH) {") &&
                adapter.contains("val inUse = novaHostInUse(obj.details.currentGameOwnerDeviceName, obj.details.currentGameWatchable)") &&
                adapter.contains("setStatusHint(statusHint, inUse.cardHintRes)")
        )
        assertTrue(
            "the sheet offers the library first then, so its primary is the one the card leads to, and watching is a tile",
            pcView.contains("if (libraryFirst) offerLibrary()") && pcView.contains("if (!libraryFirst) offerLibrary()") &&
                sheet.contains("statusRes = inUse.statusRes,") && sheet.contains("statusArg = inUse.owner,") &&
                pcView.contains("getString(copy.statusRes, copy.statusArg ?: address)")
        )
    }

    @Test
    fun everyPlaceThatAsksPassesWhatTheHostSaysAboutWatching() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        val sheet = File("src/main/java/com/papi/nova/ui/NovaHostSheet.kt").readText()
        assertEquals("the card's press and the sheet's order", 2, pcView.split("watchable = computer.currentGameWatchable,").size - 1 +
            (pcView.split("watchable = details.currentGameWatchable,").size - 1))
        assertTrue("the card's pill", adapter.contains("watchable = obj.details.currentGameWatchable,"))
        assertTrue(
            "the sheet's header",
            sheet.contains("novaHostPlaySurface(true, details.currentGameOwnedByClient, details.libraryState, details.currentGameWatchable)")
        )
        assertTrue(
            "the Watch tile is there only where there is a stream, and says the mode it will be watched at",
            pcView.contains("if (novaHostInUse(details.currentGameOwnerDeviceName, details.currentGameWatchable).offersWatch) {") &&
                pcView.contains("getString(R.string.pcview_sheet_caption_watch_mode, mode.width, mode.height, novaWatchRate(mode.fps))")
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
