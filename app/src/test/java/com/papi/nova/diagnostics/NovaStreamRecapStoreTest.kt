package com.papi.nova.diagnostics

import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-slot, process-local store and the Game sequences around it: a Game takes its
 * generation as it begins, and only the newest begun Game publishes, once.
 */
class NovaStreamRecapStoreTest {
    private val store = NovaStreamRecapStore()

    /** One Game instance as the source drives it: begin, launch, connect, samples, stop. */
    private inner class GameRun(host: String? = "host-entry-1", reconnect: Boolean = false) {
        val generation = store.begin()
        val accumulator = NovaStreamRecap.Accumulator(generation).apply { launch(launch(host, reconnect)) }
        fun connect(at: Long = 1_000L) = accumulator.connected(at)
        fun sampled(at: Long) = accumulator.accept(sample(at, expected = at / 10), at)
        /** stopConnection's guard runs this once; a second stop is a no-op there and here. */
        fun stop(end: End, at: Long = 50_000L, code: Int? = null): Boolean =
            accumulator.finish(end, code, at, "2026-10-08T14:00:00Z")?.let(store::publish) ?: false
    }

    @Test fun aFinishedConnectionIsShownForItsRequestedHostOnly() {
        val game = GameRun()
        game.connect(); game.sampled(2_000L)
        assertTrue(game.stop(End.END_REQUESTED))
        assertEquals(End.END_REQUESTED, store.forHost("host-entry-1")!!.end)
        assertEquals("the same identity with stray spaces", game.generation, store.forHost("  host-entry-1 ")!!.generation)
        assertNull(store.forHost("host-entry-2"))
        assertNull(store.forHost(null))
        assertNull(store.forHost("   "))
    }

    @Test fun aRecapWithoutARequestedHostMatchesNoLibrary() {
        val game = GameRun(host = null)
        game.connect()
        assertTrue(game.stop(End.CONNECTION_ENDED))
        assertNull(store.forHost("host-entry-1"))
        assertNull(store.forHost(""))
    }

    @Test fun aLatePublicationFromAnOlderGameNeverReplacesTheNewerOne() {
        val older = GameRun(); older.connect()
        val newer = GameRun(); newer.connect()
        assertTrue(newer.stop(End.LEFT_STREAM))
        assertFalse("the older Game stopped after the newer one began", older.stop(End.CONNECTION_ENDED))
        assertEquals(newer.generation, store.forHost("host-entry-1")!!.generation)
    }

    @Test fun anOlderGameFinishingBeforeTheNewerPublishesIsStillFencedOnceTheNewerBegan() {
        val older = GameRun(); older.connect()
        val first = GameRun() // began, never connected (a failed launch)
        assertFalse(older.stop(End.SURFACE_LOST))
        assertNull(store.forHost("host-entry-1"))
        assertFalse(first.stop(End.ERROR, code = -1))
    }

    @Test fun aReconnectPublishesTheReplacedConnectionThenTheReplacementReplacesIt() {
        val original = GameRun(); original.connect(); original.sampled(2_000L)
        // reconnectAfterStreamError stops this connection before the replacement Game exists.
        assertTrue(original.stop(End.RECONNECT_REQUESTED))
        assertEquals(End.RECONNECT_REQUESTED, store.forHost("host-entry-1")!!.end)
        val replacement = GameRun(reconnect = true); replacement.connect(3_000L); replacement.sampled(4_000L)
        assertTrue(replacement.stop(End.END_REQUESTED))
        val shown = store.forHost("host-entry-1")!!
        assertEquals(replacement.generation, shown.generation)
        assertTrue("a requested reconnect, not a recovered one", shown.launch.reconnectRequested)
    }

    @Test fun aLaunchHandoffRecreationPublishesNothingFromTheRetiringInstance() {
        val retiring = GameRun() // recreated before its stream connected; onStop returns before stopping
        val replacement = GameRun(); replacement.connect(); replacement.sampled(2_000L)
        assertTrue(replacement.stop(End.LEFT_STREAM))
        assertFalse(retiring.stop(End.UNKNOWN))
        assertEquals(replacement.generation, store.forHost("host-entry-1")!!.generation)
    }

    @Test fun oneConnectionPublishesOnceEvenIfStoppedAgain() {
        val game = GameRun(); game.connect()
        assertTrue(game.stop(End.ERROR, code = 22))
        val shown = store.forHost("host-entry-1")
        assertFalse(game.stop(End.LEFT_STREAM))
        assertSame(shown, store.forHost("host-entry-1"))
        assertEquals(End.ERROR, shown!!.end)
    }

    @Test fun dismissingRemovesOnlyThatRecap() {
        val first = GameRun(); first.connect(); first.stop(End.END_REQUESTED)
        val second = GameRun(); second.connect(); second.stop(End.END_REQUESTED)
        assertFalse("a stale card can't remove the newer recap", store.dismiss(first.generation))
        assertEquals(second.generation, store.forHost("host-entry-1")!!.generation)
        assertTrue(store.dismiss(second.generation))
        assertNull(store.forHost("host-entry-1"))
        assertFalse(store.dismiss(second.generation))
    }

    @Test fun readingNeverConsumesTheRecap() {
        val game = GameRun(); game.connect(); game.stop(End.CONNECTION_ENDED)
        repeat(3) { assertEquals(game.generation, store.forHost("host-entry-1")!!.generation) }
    }

    @Test fun aNewProcessStartsWithNothing() {
        val game = GameRun(); game.connect(); game.stop(End.CONNECTION_ENDED)
        assertNull("memory only: a new process's store is empty", NovaStreamRecapStore().forHost("host-entry-1"))
    }

    @Test fun samplesArriveWhetherOrNotTheHudShows() {
        // The feed is onPerfSample itself, which runs with the HUD hidden; nothing here knows the HUD.
        val game = GameRun(); game.connect()
        (2..5).forEach { game.sampled(it * 1_000L) }
        assertTrue(game.stop(End.END_REQUESTED))
        assertEquals(4, store.forHost("host-entry-1")!!.acceptedSamples)
    }
}
