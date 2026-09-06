package com.papi.nova.manager

import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import java.io.IOException
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PolarisStartupCoordinatorTest {
    @Test
    fun offlineHostRequiresMacBeforeWake() {
        val result = coordinator().start(
            computer = pairedComputer(state = ComputerDetails.State.OFFLINE, mac = null),
            maxPollAttempts = 1,
            pollDelayMs = 0
        )

        assertEquals(PolarisStartupStatus.MISSING_MAC, result.status)
    }

    @Test
    fun offlineHostWakesPollsAndReportsPolarisLibraryReady() {
        var wakeCalled = false
        var pollCount = 0
        val online = pairedComputer(state = ComputerDetails.State.ONLINE)
        online.libraryState = ComputerDetails.LibraryState.UNKNOWN

        val result = coordinator(
            wake = {
                wakeCalled = true
            },
            poll = {
                pollCount++
                if (pollCount >= 2) online else it
            },
            probe = { true }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.OFFLINE),
            maxPollAttempts = 3,
            pollDelayMs = 0
        )

        assertTrue(wakeCalled)
        assertEquals(2, pollCount)
        assertEquals(PolarisStartupStatus.READY, result.status)
        assertEquals(ComputerDetails.LibraryState.AVAILABLE, result.computer?.libraryState)
    }

    @Test
    fun wakeFailureStopsBeforePolling() {
        var pollCalled = false

        val result = coordinator(
            wake = { throw IOException("blocked") },
            poll = {
                pollCalled = true
                it
            }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.OFFLINE),
            maxPollAttempts = 1,
            pollDelayMs = 0
        )

        assertEquals(PolarisStartupStatus.WAKE_FAILED, result.status)
        assertFalse(pollCalled)
    }

    @Test
    fun onlineHostDoesNotSendWakeBeforePolarisProbe() {
        var wakeCalled = false

        val result = coordinator(
            wake = {
                wakeCalled = true
            },
            probe = { true }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.ONLINE),
            maxPollAttempts = 1,
            pollDelayMs = 0
        )

        assertFalse(wakeCalled)
        assertEquals(PolarisStartupStatus.READY, result.status)
    }

    @Test
    fun onlineHostKeepsProbingUntilPolarisLibraryIsReady() {
        var probeCount = 0

        val result = coordinator(
            probe = {
                probeCount++
                probeCount >= 3
            }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.ONLINE),
            maxPollAttempts = 3,
            pollDelayMs = 0
        )

        assertEquals(3, probeCount)
        assertEquals(PolarisStartupStatus.READY, result.status)
        assertEquals(ComputerDetails.LibraryState.AVAILABLE, result.computer?.libraryState)
    }

    @Test
    fun onlineHostReportsUnavailableAfterPolarisProbeBudget() {
        var probeCount = 0

        val result = coordinator(
            probe = {
                probeCount++
                false
            }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.ONLINE),
            maxPollAttempts = 3,
            pollDelayMs = 0
        )

        assertEquals(3, probeCount)
        assertEquals(PolarisStartupStatus.POLARIS_UNAVAILABLE, result.status)
        assertEquals(ComputerDetails.LibraryState.UNAVAILABLE, result.computer?.libraryState)
    }

    @Test
    fun awakeHostWithPolarisDownIsToldSoInsteadOfBeingWoken() {
        var wakeCalled = false
        var pollCount = 0

        val result = coordinator(
            wake = { wakeCalled = true },
            poll = {
                pollCount++
                it
            },
            awake = { true }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.OFFLINE),
            maxPollAttempts = PolarisStartupCoordinator.DEFAULT_POLL_ATTEMPTS,
            pollDelayMs = 0
        )

        // The machine answered the connect, so a wake packet has nothing to do; the
        // honest answer is that Polaris is not running there, and it should not take
        // the full boot budget to say so.
        assertFalse(wakeCalled)
        assertEquals(PolarisStartupStatus.POLARIS_NOT_RUNNING, result.status)
        assertEquals(PolarisStartupCoordinator.AWAKE_POLL_ATTEMPTS, pollCount)
    }

    @Test
    fun awakeHostWhosePolarisComesUpDuringPollingIsReady() {
        var wakeCalled = false
        var pollCount = 0
        val online = pairedComputer(state = ComputerDetails.State.ONLINE)

        val result = coordinator(
            wake = { wakeCalled = true },
            poll = {
                pollCount++
                if (pollCount >= 2) online else it
            },
            probe = { true },
            awake = { true }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.OFFLINE),
            maxPollAttempts = 4,
            pollDelayMs = 0
        )

        assertFalse(wakeCalled)
        assertEquals(PolarisStartupStatus.READY, result.status)
    }

    @Test
    fun hostThatWentDownSinceTheLastPollIsReportedAsPolarisNotRunning() {
        var wakeCalled = false
        var probeCount = 0
        val offline = pairedComputer(state = ComputerDetails.State.OFFLINE)

        val result = coordinator(
            wake = { wakeCalled = true },
            // The list still says online; the first real poll says otherwise.
            poll = { offline },
            probe = {
                probeCount++
                false
            },
            awake = { true }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.ONLINE),
            maxPollAttempts = 3,
            pollDelayMs = 0
        )

        assertFalse(wakeCalled)
        assertEquals(1, probeCount)
        assertEquals(PolarisStartupStatus.POLARIS_NOT_RUNNING, result.status)
    }

    @Test
    fun unreachableHostStillGetsTheWakePacketAndTimesOut() {
        var wakeCalled = false

        val result = coordinator(
            wake = { wakeCalled = true },
            awake = { false }
        ).start(
            computer = pairedComputer(state = ComputerDetails.State.OFFLINE),
            maxPollAttempts = 2,
            pollDelayMs = 0
        )

        assertTrue(wakeCalled)
        assertEquals(PolarisStartupStatus.TIMEOUT, result.status)
    }

    private fun coordinator(
        wake: (ComputerDetails) -> Unit = {},
        poll: (ComputerDetails) -> ComputerDetails = { it },
        probe: (ComputerDetails) -> Boolean = { false },
        awake: (ComputerDetails) -> Boolean = { false }
    ) = PolarisStartupCoordinator(
        wakeSender = object : PolarisStartupCoordinator.WakeSender {
            override fun wake(computer: ComputerDetails) = wake(computer)
        },
        hostPoller = object : PolarisStartupCoordinator.HostPoller {
            override fun poll(computer: ComputerDetails): ComputerDetails = poll(computer)
        },
        polarisProbe = object : PolarisStartupCoordinator.PolarisProbe {
            override fun hasGameLibrary(computer: ComputerDetails): Boolean = probe(computer)
        },
        sleeper = object : PolarisStartupCoordinator.Sleeper {
            override fun sleep(delayMs: Long) = Unit
        },
        reachabilityProbe = object : PolarisStartupCoordinator.HostReachabilityProbe {
            override fun isAwake(computer: ComputerDetails): Boolean = awake(computer)
        }
    )

    private fun pairedComputer(
        state: ComputerDetails.State,
        mac: String? = "AA:BB:CC:DD:EE:FF"
    ) = ComputerDetails().apply {
        uuid = "pc-1"
        name = "Polaris Host"
        this.state = state
        pairState = PairingManager.PairState.PAIRED
        macAddress = mac
        serverCert = mock(X509Certificate::class.java)
        activeAddress = ComputerDetails.AddressTuple("10.0.0.2", 47989)
        httpsPort = 47984
    }
}
