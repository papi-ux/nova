package com.papi.nova.network

import com.papi.nova.ui.NovaHostNowPlaying
import java.io.IOException
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pill follows what each host reports, while watched. Time is the test's, so nothing sleeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostNowPlayingWatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val cert = Mockito.mock(X509Certificate::class.java)
    private val host = HostNowPlayingWatch.Target("host-1", "100.99.34.59", 47984, cert)
    private val other = HostNowPlayingWatch.Target("host-2", "100.99.34.60", 47984, cert)
    private val hades = NovaHostNowPlaying(7, "Hades", host = NovaHostNowPlaying.Host(host.uuid, host.address, host.httpsPort, cert))
    private val published = ArrayList<Map<String, NovaHostNowPlaying>>()
    private val pauses = ArrayList<Pause>()
    private var asks = 0
    private var opened = 0
    private var answer: () -> NovaHostNowPlaying? = { hades }

    private class Pause(val ms: Long) { val done = Channel<Unit>(1) }

    private val watch = HostNowPlayingWatch(
        scope = scope,
        asker = { opened++; { asks++; answer() } },
        onChange = { published += it },
        io = Dispatchers.Unconfined,
        pause = { ms -> val pause = Pause(ms); pauses += pause; pause.done.receive() },
    )

    @After fun tearDown() = scope.cancel()

    private fun next() = pauses.last().done.trySend(Unit)

    @Test fun aHostsAnswerShowsAndItIsAskedAgainOnItsTime() {
        watch.watch(listOf(host))
        assertEquals(mapOf("host-1" to hades), published.last())
        assertEquals(HostNowPlayingWatch.ASK_EVERY_MS, pauses.last().ms)
        next()
        assertEquals(2, asks)
        assertEquals("the host is only opened once", 1, opened)
    }

    @Test fun theSameAnswerDoesNotPublishAgain() {
        watch.watch(listOf(host))
        next()
        assertEquals(1, published.size)
    }

    @Test fun aSessionThatEndedClearsThePill() {
        watch.watch(listOf(host))
        answer = { null }
        next()
        assertEquals(emptyMap<String, NovaHostNowPlaying>(), published.last())
    }

    @Test fun aFailedAskClearsUnknownSessionAuthority() {
        watch.watch(listOf(host))
        answer = { throw IOException("no answer") }
        next()
        assertEquals(emptyMap<String, NovaHostNowPlaying>(), published.last())
        assertEquals("still asking", 2, asks)
    }

    @Test fun aHostLeavingTheWatchClearsThePillAndStopsAsking() {
        watch.watch(listOf(host))
        watch.watch(emptyList())
        assertEquals(emptyMap<String, NovaHostNowPlaying>(), published.last())
        val before = asks
        next()
        assertEquals(before, asks)
    }

    @Test fun stoppingClearsEverythingAndStopsAsking() {
        watch.watch(listOf(host, other))
        watch.stop()
        assertEquals(emptyMap<String, NovaHostNowPlaying>(), published.last())
        val before = asks
        pauses.forEach { it.done.trySend(Unit) }
        assertEquals(before, asks)
    }

    @Test fun oneHostSlowOrSilentDoesNotHoldUpAnother() {
        answer = { throw IOException("no answer") }
        watch.watch(listOf(host))
        answer = { hades }
        watch.watch(listOf(host, other))
        assertEquals(mapOf("host-2" to hades.copy(host = NovaHostNowPlaying.Host(other.uuid, other.address, other.httpsPort, cert))), published.last())
    }

    @Test fun switchingGamesOnTheSameHostRestartsTheObservation() {
        watch.watch(listOf(host.copy(gameId = 7)))
        answer = { null }
        watch.watch(listOf(host.copy(gameId = 8)))
        assertEquals(emptyMap<String, NovaHostNowPlaying>(), published.last())
        assertEquals(2, opened)
        answer = { hades }
        next()
        assertEquals("an A answer cannot label current B", emptyMap<String, NovaHostNowPlaying>(), published.last())
    }

    @Test fun aCancelledInFlightHostCannotPublishAfterItsReplacementOrStop() {
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            val queue = java.util.ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
            fun run() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        }
        val results = ArrayList<Map<String, NovaHostNowPlaying>>()
        val pending = HostNowPlayingWatch(scope, asker = { target -> { if (target.gameId == 7) hades else null } },
            onChange = { results += it }, io = dispatcher, pause = { Channel<Unit>().receive() })
        pending.watch(listOf(host.copy(gameId = 7)))
        pending.watch(listOf(host.copy(gameId = 8)))
        dispatcher.run()
        assertTrue("cancelled A never published", results.none { it.isNotEmpty() })
        pending.watch(listOf(host.copy(gameId = 7)))
        pending.stop()
        dispatcher.run()
        assertTrue("stopped observations never published", results.none { it.isNotEmpty() })
    }

    @Test fun anAlreadyRunningAskCannotOverwriteTheReplacementWhenItReturnsLate() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val replaced = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.CopyOnWriteArrayList<Map<String, NovaHostNowPlaying>>()
        val replacement = NovaHostNowPlaying(8, "Portal", host = hades.host)
        val pending = HostNowPlayingWatch(scope, asker = { target -> {
            if (target.gameId == 7) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                hades
            } else replacement
        } }, onChange = {
            results += it
            if (it[host.uuid] == replacement) replaced.countDown()
        }, io = dispatcher, pause = { Channel<Unit>().receive() })
        try {
            pending.watch(listOf(host.copy(gameId = 7)))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            pending.watch(listOf(host.copy(gameId = 8)))
            assertTrue(replaced.await(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("late A cannot overwrite B", results.none { it[host.uuid] == hades })
            assertEquals(replacement, results.last()[host.uuid])
            pending.stop()
            assertEquals(emptyMap<String, NovaHostNowPlaying>(), results.last())
        } finally {
            release.countDown()
            pending.stop()
            dispatcher.close()
        }
    }

    @Test fun aHostThatStaysKeepsItsPlace() {
        watch.watch(listOf(host))
        val before = asks
        watch.watch(listOf(host))
        assertEquals(before, asks)
    }
}
