package com.papi.nova.ui

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 1, through the class the Command Center keeps each opening's host status in
 * ([NovaCommandCenterHostStatus]), against a client whose status a test sets. The Doctor card
 * tests hand [NovaQuickMenuUiState.from] a kept reading and a Polaris flag of their own; these
 * check the Command Center's own: a failed read keeps the last reading the host sent, and only a
 * host that says it is Polaris has a Doctor card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterHostStatusTest {
    /** The client's copy of the host's status: what a read got, none after a failed one. */
    private var store: PolarisSessionStatus? = null

    @Suppress("UNCHECKED_CAST")
    private val api: PolarisApiClient = Mockito.mock(PolarisApiClient::class.java, Answer<Any?> { call ->
        when (call.method.name) {
            "withCurrentSessionStatus" -> (call.arguments[0] as (PolarisSessionStatus?) -> Any?)(store)
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
    })

    private val registry = DoctorMenuRefreshRegistry()
    private val generation = registry.open()
    private var redraws = 0
    private var derived = 0

    /** Nothing here switches Live Tuning; NovaCommandCenterLiveTuningResultComposeTest does. */
    private val runtime = object : NovaCommandCenterRuntime {
        override fun launchIo(name: String, block: suspend () -> Unit) = error("no work here")
        override suspend fun onMain(block: () -> Unit) = error("no work here")
        override fun postDelayed(delayMs: Long, block: () -> Unit) = error("no work here")
    }

    private fun host(api: PolarisApiClient? = this.api, polarisServer: Boolean = true) =
        NovaCommandCenterHostStatus(api, registry, generation, polarisServer = { polarisServer }, runtime = runtime).also {
            it.derive = { derived++ }
            it.redraw = { redraws++ }
        }

    private val reading = PolarisSessionStatus(
        state = "streaming",
        doctor = PolarisSessionStatus.DoctorStatus(available = true, likelyCause = "Network jitter is delaying frames."),
    )

    @Test
    fun aFailedReadKeepsTheLastReadingAndSaysTheHostDidNotAnswer() {
        val host = host()
        store = reading
        assertTrue("the host answered", host.publish())
        assertSame(reading, host.status)
        assertSame(reading, host.last)
        assertFalse(host.unavailable)

        store = null
        assertFalse("the read failed", host.publish())
        assertNull("no status now", host.status)
        assertSame("the Doctor card keeps the last reading", reading, host.last)
        assertTrue(host.unavailable)
        assertEquals("the page derives its state after every read", 2, derived)

        // The page's own refresh lands the same way, and hands the page a new state.
        host.refresh()
        assertSame(reading, host.last)
        assertTrue(host.unavailable)
        assertEquals(1, redraws)
        store = reading
        host.refresh()
        assertSame(reading, host.status)
        assertFalse(host.unavailable)
        assertEquals(2, redraws)
    }

    @Test
    fun aReadLandsOnlyWhileTheOpeningStands() {
        val host = host()
        store = reading
        registry.close(generation)
        assertFalse(host.publish())
        assertNull(host.status)
        assertNull(host.last)
    }

    /**
     * A stream builds a client for every host, Sunshine's included, so a client alone does not
     * make a host Polaris: it has a Doctor card when its capabilities or its status say so. That
     * is decided when the Command Center opens and kept for the opening.
     */
    @Test
    fun aHostIsPolarisOnlyWhenItSaysSo() {
        store = null
        assertFalse("a client, and a host that says nothing", host(polarisServer = false).polaris)
        assertTrue("capabilities that say Polaris", host(polarisServer = true).polaris)
        assertFalse("no client", host(api = null, polarisServer = true).polaris)
        store = reading
        assertTrue("a status only Polaris sends", host(polarisServer = false).polaris)

        store = null
        val sunshine = host(polarisServer = false)
        store = reading
        sunshine.publish()
        assertFalse("kept for the opening", sunshine.polaris)
    }
}
