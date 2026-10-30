package com.papi.nova.network

import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Hosts screen's chips follow each host's own answer: only while watched, only while fresh. Time
 * is the test's: each pause waits until the test lets it end, so nothing here sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostConnectionWatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var wallMs = 1_000_000L
    private var elapsedMs = 5_000L
    private val cert = Mockito.mock(X509Certificate::class.java)
    private val host = HostConnectionWatch.Target("host-1", "100.99.34.59", 47984, cert)
    private val answers = ArrayList<Map<String, HostConnectionEvidence>>()
    private val pauses = ArrayList<Pause>()
    private var asks = 0
    private var opened = 0
    private var answer: () -> HostConnectionObservation = { tailscale() }

    private class Pause(val ms: Long) { val done = Channel<Unit>(1) }

    private val watch = HostConnectionWatch(
        scope = scope,
        asker = { opened++; { asks++; answer() } },
        onChange = { answers += it },
        io = Dispatchers.Unconfined,
        pause = { ms -> Pause(ms).also { pauses += it }.done.receive() },
        wallNowMs = { wallMs },
        elapsedNowMs = { elapsedMs },
    )

    @After fun close() = scope.cancel()

    private fun body(status: String, confirmed: Boolean, transport: String, reason: String) = """
        {"schema_version":1,"status":"$status","tailscale":{"confirmed":$confirmed,"scope":"control_connection",
        "source":"tailscaled_localapi","transport":"$transport","reason":"$reason",
        "observed_at_ms":$wallMs,"max_age_ms":10000}}
    """.trimIndent()

    private fun tailscale() = HostConnectionObservation(body("tailscale", true, "direct", "observed"), true, elapsedMs)
    private fun unknown() = HostConnectionObservation(body("unknown", false, "unknown", "peer_not_confirmed"), true, elapsedMs)

    private fun latest() = answers.lastOrNull()?.get("host-1")

    /** Ends the pause asked for after [ms], moving the clock on by it first. */
    private fun pass(ms: Long) {
        wallMs += ms
        elapsedMs += ms
        val pause = pauses.first { it.ms == ms }
        pauses -= pause
        pause.done.trySend(Unit)
    }

    @Test fun aConfirmedHostIsShownAndAskedAgainBeforeItsAnswerAgesOut() {
        watch.watch(listOf(host))
        assertEquals(HostConnectionEvidence.Status.TAILSCALE, latest()?.status)
        assertEquals(HostConnectionEvidence.Transport.DIRECT, latest()?.transport)
        assertEquals(1, asks)

        val askAgain = HostConnectionEvidence.MAX_AGE_MS + 1 - HostConnectionWatch.REFRESH_LEAD_MS
        pass(askAgain)
        assertEquals(2, asks)
        assertEquals("the same client asks each time", 1, opened)
        assertEquals(HostConnectionEvidence.Status.TAILSCALE, latest()?.status)
    }

    @Test fun anAnswerThatAgesOutIsDroppedWhileTheNextIsStillComing() {
        watch.watch(listOf(host))
        assertEquals(HostConnectionEvidence.Status.TAILSCALE, latest()?.status)
        // The ask made before it aged out has not come back; the expiry doesn't wait for it.
        pass(HostConnectionEvidence.MAX_AGE_MS + 1)
        assertEquals(null, latest())
        assertEquals(1, asks)
    }

    @Test fun anUnconfirmedHostWaitsLongerAndAnUnconfirmedAnswerIsKeptOutOfTheChipByTheCopy() {
        answer = { unknown() }
        watch.watch(listOf(host))
        assertEquals(HostConnectionEvidence.Status.UNKNOWN, latest()?.status)
        assertTrue("it has no window to keep fresh", pauses.none { it.ms != HostConnectionWatch.UNCONFIRMED_ASK_AGAIN_MS })
        assertEquals(1, pauses.size)
        pass(HostConnectionWatch.UNCONFIRMED_ASK_AGAIN_MS)
        assertEquals(2, asks)
    }

    @Test fun aHostThatCannotBeAskedShowsNothingAndIsNotFatal() {
        answer = { throw IllegalStateException("no") }
        watch.watch(listOf(host))
        assertEquals(emptyList<Map<String, HostConnectionEvidence>>(), answers)
        pass(HostConnectionWatch.UNCONFIRMED_ASK_AGAIN_MS)
        assertEquals(2, asks)
    }

    @Test fun watchingTheSameHostsAgainAsksNothingNew() {
        watch.watch(listOf(host))
        watch.watch(listOf(host))
        assertEquals(1, asks)
        assertEquals(1, opened)
    }

    @Test fun aHostThatLeavesOrChangesAddressLosesItsAnswer() {
        watch.watch(listOf(host))
        watch.watch(emptyList())
        assertEquals(null, latest())

        watch.watch(listOf(host))
        watch.watch(listOf(host.copy(address = "10.0.0.232")))
        assertEquals("a new address is asked afresh", 3, opened)
    }

    @Test fun stoppingForgetsEverythingAndAsksNoMore() {
        watch.watch(listOf(host))
        watch.stop()
        assertEquals(emptyMap<String, HostConnectionEvidence>(), answers.last())
        val asked = asks
        pauses.toList().forEach { it.done.trySend(Unit) }
        assertEquals(asked, asks)
    }
}
