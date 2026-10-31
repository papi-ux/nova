package com.papi.nova.grid

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.NovaHostNowPlaying
import com.papi.nova.ui.novaHostNowPlaying
import com.papi.nova.ui.novaNowPlayingGame
import com.papi.nova.ui.novaNowPlayingSpoken
import com.papi.nova.ui.novaNowPlayingWords
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pill on a Hosts card: what it says, when it shows, and what each input reaches (#419). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostCardNowPlayingTest {
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)

    // Its words.

    @Test fun itNamesTheGameAndWhatAPressDoes() {
        val pill = playing()
        assertEquals("Hades · Resume", novaNowPlayingWords(context, pill))
        assertEquals("Hades. Resume", novaNowPlayingSpoken(context, pill))
    }

    /** ROOT, from the host's contract: session status has no start or pause time, so nothing may imply one. */
    @Test fun itClaimsNoTimeBecauseTheHostGivesNone() {
        val words = novaNowPlayingWords(context, playing())
        assertFalse(words, Regex("""\d|min|\bh\b""").containsMatchIn(words))
    }

    @Test fun aGameWithNoNameStillReads() {
        val words = novaNowPlayingWords(context, playing(name = ""))
        assertEquals("${context.getString(R.string.applist_menu_watch_active_name)} · Resume", words)
    }

    // Where it comes from: the Resume state that already exists.

    private fun status(state: String = "paused", owned: Boolean = true, shuttingDown: Boolean = false, gameId: Int = 7) =
        PolarisSessionStatus(state = state, ownedByClient = owned, shutdownRequested = shuttingDown, gameId = gameId, game = "Hades", sessionToken = "session-A")

    private val cert = org.mockito.Mockito.mock(java.security.cert.X509Certificate::class.java)

    private fun playing(name: String = "Hades", game: PolarisGame? = null, token: String = "session-A") =
        novaHostNowPlaying(status().copy(game = name, sessionToken = token))!!.copy(game = game, host = NovaHostNowPlaying.Host("host-1", "127.0.0.1", 47984, cert))

    @Test fun onlyThisDevicesResumableSessionMakesOne() {
        assertEquals(playing().copy(host = null), novaHostNowPlaying(status()))
        assertNull("someone else's game", novaHostNowPlaying(status(owned = false)))
        assertNull("no status", novaHostNowPlaying(null))
        assertNull("unknown session", novaHostNowPlaying(status().copy(sessionToken = "")))
        assertNull("invalid authority", novaHostNowPlaying(status().copy(authorityContractValid = false)))
        assertNull("viewer", novaHostNowPlaying(status().copy(clientRole = "viewer")))
        assertNull("no game", novaHostNowPlaying(status(gameId = 0)))
        assertNull("closing", novaHostNowPlaying(status(shuttingDown = true)))
        assertNull("gone", novaHostNowPlaying(status(state = "idle")))
    }

    @Test fun itsArtIsTheLibrarysEntryForThatGame() {
        val other = PolarisGame(id = "a", appId = 1, name = "Portal")
        val byId = PolarisGame(id = "b", appId = 7, name = "Renamed")
        val byName = PolarisGame(id = "c", appId = 2, name = "HADES")
        val pill = playing()
        assertSame(byId, novaNowPlayingGame(listOf(other, byName, byId), pill))
        assertSame(byName, novaNowPlayingGame(listOf(other, byName), pill))
        assertNull(novaNowPlayingGame(listOf(other), pill))
    }

    // The card.

    private class Card(val adapter: PcGridAdapter, val row: View, val computer: PcViewModel.ComputerObject) {
        val pill: View get() = row.findViewById(R.id.host_now_playing_row)
        val words: TextView get() = row.findViewById(R.id.host_now_playing_text)
        val end: View get() = row.findViewById(R.id.host_end_session_button)
        val primary: TextView get() = row.findViewById(R.id.primary_action_text)
        val manage: View get() = row.findViewById(R.id.server_actions_button)
    }

    private fun details(state: ComputerDetails.State = ComputerDetails.State.ONLINE, owned: Boolean? = true) =
        ComputerDetails().apply {
            uuid = "host-1"
            name = "PC"
            this.state = state
            pairState = PairingManager.PairState.PAIRED
            serverCert = cert
            activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
            httpsPort = 47984
            runningGameId = 7
            currentGameOwnedByClient = owned
        }

    private fun card(details: ComputerDetails = details()): Card {
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val computer = PcViewModel.ComputerObject(details)
        adapter.setItems(listOf(computer))
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        return Card(adapter, holder.itemView, computer).also { bound = holder to it }
    }

    private var bound: Pair<GenericGridAdapter.ViewHolder, Card>? = null

    private fun rebind(card: Card) = card.adapter.onBindViewHolder(bound!!.first, 0)

    @Test fun aCardWithNoSessionHasNoPillAndKeepsItsPrimary() {
        val card = card()
        assertEquals(View.GONE, card.pill.visibility)
        assertEquals(View.VISIBLE, card.primary.visibility)
    }

    @Test fun theSessionPutsThePillAndEndOnTheCardInThePrimarysPlace() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        assertEquals(View.VISIBLE, card.pill.visibility)
        assertEquals("Hades · Resume", card.words.text.toString())
        assertEquals("Hades. Resume", card.words.contentDescription.toString())
        assertEquals(View.VISIBLE, card.end.visibility)
        assertEquals("one Resume on the card, not two", View.GONE, card.primary.visibility)
        assertEquals(View.VISIBLE, card.manage.visibility)
    }

    @Test fun itClearsOnEndAndThePrimaryComesBack() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        card.adapter.setNowPlaying(emptyMap())
        rebind(card)
        assertEquals(View.GONE, card.pill.visibility)
        assertEquals(View.VISIBLE, card.primary.visibility)
    }

    @Test fun cachedGameACannotLabelCurrentGameBOrOfferItsEnd() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        card.computer.details.runningGameId = 8
        rebind(card)
        assertEquals("cached A must not label current B", View.GONE, card.pill.visibility)
        var ends = 0
        card.adapter.setOnEndSessionListener { ends++ }
        card.end.performClick()
        assertEquals("even a retained End callback must refuse B", 0, ends)
    }

    @Test fun aReplacementBeforeRebindInvalidatesTheDisplayedEndCallback() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        var ends = 0
        card.adapter.setOnEndSessionListener { ends++ }
        card.computer.details.runningGameId = 8
        card.end.performClick()
        assertEquals("A's displayed End cannot be invoked for B", 0, ends)
    }

    @Test fun anOfflineHostOrSomeoneElsesGameNeverWearsIt() {
        val pills = mapOf("host-1" to playing())
        listOf(details(state = ComputerDetails.State.OFFLINE), details(owned = false), details(owned = null)).forEach {
            val card = card(it)
            card.adapter.setNowPlaying(pills)
            rebind(card)
            assertEquals(View.GONE, card.pill.visibility)
        }
    }

    @Test fun endGoesToItsListenerAndTalkBackHearsWhatItEnds() {
        val card = card()
        val ended = ArrayList<com.papi.nova.ui.NovaHostSessionAction>()
        card.adapter.setOnEndSessionListener { ended += it }
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        card.end.performClick()
        assertEquals(1, ended.size)
        assertEquals("session-A", ended.single().playing.sessionToken)
        assertEquals(card.computer.details.uuid, ended.single().hostUuid)
        assertEquals("End session: Hades", card.end.contentDescription.toString())
        assertTrue(card.end.isFocusable)
    }

    @Test fun bothDisplayedCallbacksRefuseSameGameReplacementOwnershipOrHostChanges() {
        val changes: List<(Card) -> Unit> = listOf(
            { it.adapter.setNowPlaying(mapOf("host-1" to playing(token = "session-B"))) },
            { it.computer.details.currentGameOwnedByClient = false },
            { it.computer.details.currentGameOwnedByClient = null },
            { it.computer.details.uuid = "host-2" },
            { it.computer.details.activeAddress = ComputerDetails.AddressTuple("127.0.0.2", 47989) },
            { it.adapter.setItems(emptyList()) },
        )
        for (change in changes) {
            val card = card()
            card.adapter.setNowPlaying(mapOf("host-1" to playing()))
            rebind(card)
            var callbacks = 0
            card.adapter.setOnEndSessionListener { callbacks++ }
            card.adapter.setOnResumeSessionListener { callbacks++ }
            change(card)
            card.end.performClick()
            card.row.performClick()
            assertEquals("the displayed action is no longer current", 0, callbacks)
        }
    }

    @Test fun rebindingACachedSessionCannotReassignItsObservedHostRoute() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        card.computer.details.activeAddress = ComputerDetails.AddressTuple("127.0.0.2", 47989)
        rebind(card)
        assertEquals("the answer belongs to the original route", View.GONE, card.pill.visibility)
        var calls = 0
        card.adapter.setOnEndSessionListener { calls++ }
        card.end.performClick()
        assertEquals(0, calls)
    }

    @Test fun theDisplayedResumeCapturesItsSessionAndGame() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        val resumed = ArrayList<com.papi.nova.ui.NovaHostSessionAction>()
        card.adapter.setOnResumeSessionListener { resumed += it }
        card.row.performClick()
        assertEquals("session-A", resumed.single().playing.sessionToken)
        assertEquals(7, resumed.single().computer().runningGameId)
    }

    @Test fun aTouchOnThePillIsATouchOnTheCard() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        assertFalse(
            "the card's own click resumes; the pill must not swallow it",
            card.row.findViewById<View>(R.id.host_now_playing).isClickable,
        )
    }

    @Test fun thePillFillsOnlyWithTheCardsFocus() {
        val card = card()
        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        val pill = card.row.findViewById<View>(R.id.host_now_playing)
        assertFalse(pill.isSelected)
        card.adapter.onItemFocusChanged(card.row, true)
        assertTrue(pill.isSelected)
        card.adapter.onItemFocusChanged(card.row, false)
        assertFalse(pill.isSelected)
    }

    @Test fun anArtlessSessionDrawsNoEmptyArtAndARealOneIsAskedForOnce() {
        val card = card()
        val game = PolarisGame(id = "hades", appId = 7, name = "Hades")
        val asked = ArrayList<Pair<String, PolarisGame>>()
        card.adapter.setNowPlayingArtwork { _, uuid, g -> asked += uuid to g }

        card.adapter.setNowPlaying(mapOf("host-1" to playing()))
        rebind(card)
        assertEquals(View.GONE, card.row.findViewById<View>(R.id.host_now_playing_art).visibility)

        card.adapter.setNowPlaying(mapOf("host-1" to playing(game = game)))
        rebind(card)
        rebind(card)
        assertEquals(View.VISIBLE, card.row.findViewById<View>(R.id.host_now_playing_art).visibility)
        assertEquals(listOf("host-1" to game), asked)
    }

    @Test fun recyclingTheHolderAcrossHostsCancelsAndReloadsEqualGameArtwork() {
        val card = card()
        val game = PolarisGame(id = "hades", appId = 7, name = "Hades")
        val asked = ArrayList<String>()
        val job = kotlinx.coroutines.Job()
        card.adapter.setNowPlayingArtwork { view, uuid, _ ->
            asked += uuid
            if (uuid == "host-1") {
                view.setTag(R.id.nova_artwork_request_key, "host-1-request")
                view.setTag(R.id.nova_artwork_job, job)
            }
        }
        card.adapter.setNowPlaying(mapOf("host-1" to playing(game = game)))
        rebind(card)
        val other = details().apply { uuid = "host-2" }
        card.adapter.setItems(listOf(PcViewModel.ComputerObject(other)))
        card.adapter.setNowPlaying(mapOf("host-2" to playing(game = game).copy(host = NovaHostNowPlaying.Host("host-2", "127.0.0.1", 47984, cert))))
        rebind(card)
        assertEquals(listOf("host-1", "host-2"), asked)
        assertTrue("the first host's in-flight artwork was retired", job.isCancelled)
        assertNull(card.row.findViewById<View>(R.id.host_now_playing_art).getTag(R.id.nova_artwork_request_key))
    }

    // A controller's way around the card.

    @Test fun endStandsBetweenTheRowAndManageAsItDoesOnScreen() {
        assertEquals(NovaHostRowFocusMove.TO_END, novaHostRowFocusMove(right = true, left = false, onRow = true, onManage = false, endShown = true))
        assertEquals(NovaHostRowFocusMove.TO_MANAGE, novaHostRowFocusMove(right = true, left = false, onRow = false, onManage = false, onEnd = true, endShown = true))
        assertEquals(NovaHostRowFocusMove.TO_END, novaHostRowFocusMove(right = false, left = true, onRow = false, onManage = true, endShown = true))
        assertEquals(NovaHostRowFocusMove.TO_ROW, novaHostRowFocusMove(right = false, left = true, onRow = false, onManage = false, onEnd = true, endShown = true))
    }

    @Test fun withNoEndOnTheCardTheMovesAreAsTheyWere() {
        assertEquals(NovaHostRowFocusMove.TO_MANAGE, novaHostRowFocusMove(right = true, left = false, onRow = true, onManage = false))
        assertEquals(NovaHostRowFocusMove.TO_ROW, novaHostRowFocusMove(right = false, left = true, onRow = false, onManage = true))
        assertEquals(NovaHostRowFocusMove.NONE, novaHostRowFocusMove(right = true, left = false, onRow = false, onManage = true))
    }

    /** End goes through its own confirm, and the screen stops asking when it stops watching. */
    @Test fun endConfirmsAndWatchingStopsWithTheScreen() {
        val hosts = File("src/main/java/com/papi/nova/PcView.kt").readText()
        assertTrue(hosts.contains("pcGridAdapter.setOnEndSessionListener { action -> confirmEndRunningGame(action) }"))
        assertTrue(hosts.contains("hostNowPlaying.stop()"))
        // End sits in a row that hides as one, so a look at End's own visibility would always say yes.
        assertTrue(hosts.contains("endShown = row.findViewById<View>(R.id.host_now_playing_row)?.visibility == View.VISIBLE"))
        val confirm = hosts.substringAfter("private fun confirmEndRunningGame").substringBefore("private fun resumeOrWatchRunningGame")
        assertTrue(confirm.contains("stayLabel = getString(R.string.nova_panel_stay)") && confirm.contains("destructive = true"))
    }
}
