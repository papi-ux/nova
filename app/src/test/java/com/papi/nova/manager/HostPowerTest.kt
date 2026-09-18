package com.papi.nova.manager

import com.papi.nova.api.PolarisCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPowerPolicyTest {
    private fun capabilities(
        hostSleep: Boolean = true,
        supported: Boolean = true,
        enabled: Boolean = true,
        permitted: Boolean = true,
        blockedMessage: String = "",
    ) = PolarisCapabilities(
        server = "polaris",
        version = "1.4.9",
        features = PolarisCapabilities.Features(hostSleep = hostSleep),
        capture = PolarisCapabilities.CaptureInfo(),
        hostPower = PolarisCapabilities.HostPower(
            sleepSupported = supported,
            sleepEnabled = enabled,
            sleepPermitted = permitted,
            sleepBlockedMessage = blockedMessage,
        ),
    )

    @Test
    fun anUnreachableHostOffersWake() {
        assertEquals(
            HostPowerAction.WAKE,
            HostPowerPolicy.resolve(reachable = false, capabilities = capabilities()),
        )
    }

    @Test
    fun aHostThatCanSleepOffersSleep() {
        assertEquals(
            HostPowerAction.SLEEP,
            HostPowerPolicy.resolve(reachable = true, capabilities = capabilities()),
        )
    }

    @Test
    fun aHostWithoutTheFeatureOffersWake() {
        // Sunshine, or a Polaris older than host sleep: it serves no host_power
        // block, and a sleep button on it would only ever fail.
        assertEquals(
            HostPowerAction.WAKE,
            HostPowerPolicy.resolve(reachable = true, capabilities = capabilities(hostSleep = false)),
        )
    }

    @Test
    fun sleepTurnedOffOnTheHostOffersWake() {
        assertEquals(
            HostPowerAction.WAKE,
            HostPowerPolicy.resolve(reachable = true, capabilities = capabilities(enabled = false)),
        )
    }

    @Test
    fun aHostWhoseLogindWillRefuseOffersWake() {
        assertEquals(
            HostPowerAction.WAKE,
            HostPowerPolicy.resolve(reachable = true, capabilities = capabilities(supported = false)),
        )
    }

    @Test
    fun aWatchOnlyClientOffersWake() {
        assertEquals(
            HostPowerAction.WAKE,
            HostPowerPolicy.resolve(reachable = true, capabilities = capabilities(permitted = false)),
        )
    }

    @Test
    fun aHostNovaHasNotAskedYetOffersWake() {
        assertEquals(
            HostPowerAction.WAKE,
            HostPowerPolicy.resolve(reachable = true, capabilities = null),
        )
    }
}

class HostSleepUnavailableTest {
    private fun capabilities(
        hostSleep: Boolean = true,
        supported: Boolean = true,
        enabled: Boolean = true,
        permitted: Boolean = true,
        blockedMessage: String = "",
    ) = PolarisCapabilities(
        server = "polaris",
        version = "1.4.9",
        features = PolarisCapabilities.Features(hostSleep = hostSleep),
        capture = PolarisCapabilities.CaptureInfo(),
        hostPower = PolarisCapabilities.HostPower(
            sleepSupported = supported,
            sleepEnabled = enabled,
            sleepPermitted = permitted,
            sleepBlockedMessage = blockedMessage,
        ),
    )

    private fun reason(reachable: Boolean = true, capabilities: PolarisCapabilities? = capabilities()) =
        HostPowerPolicy.unavailableReason(reachable = reachable, capabilities = capabilities)

    @Test
    fun nothingToExplainWhenTheHostCanSleepOrNovaCannotTell() {
        assertEquals(null, reason())
        // Unreachable: Wake is the whole story. Not asked yet: nothing is known.
        assertEquals(null, reason(reachable = false, capabilities = capabilities(enabled = false)))
        assertEquals(null, reason(capabilities = null))
        // Sunshine or an older Polaris has no Sleep Host to explain.
        assertEquals(null, reason(capabilities = capabilities(hostSleep = false, enabled = false)))
    }

    @Test
    fun theOwnersSwitchComesFirst() {
        assertEquals(
            HostSleepUnavailable.TurnedOff,
            reason(capabilities = capabilities(enabled = false, supported = false, permitted = false)),
        )
    }

    @Test
    fun aHostThatCannotSuspendSaysWhyInItsOwnWords() {
        assertEquals(
            HostSleepUnavailable.HostCannot("polkit wants interactive authentication"),
            reason(capabilities = capabilities(supported = false, blockedMessage = "  polkit wants interactive authentication ")),
        )
        assertEquals(HostSleepUnavailable.HostCannot(""), reason(capabilities = capabilities(supported = false)))
    }

    @Test
    fun aWatchOnlyDeviceIsToldItMayOnlyWatch() {
        assertEquals(HostSleepUnavailable.WatchOnly, reason(capabilities = capabilities(permitted = false)))
    }

    @Test
    fun everyReasonMeansTheButtonOffersWake() {
        listOf(
            capabilities(enabled = false),
            capabilities(supported = false),
            capabilities(permitted = false),
        ).forEach { caps ->
            assertEquals(HostPowerAction.WAKE, HostPowerPolicy.resolve(reachable = true, capabilities = caps))
            assertTrue(reason(capabilities = caps) != null)
        }
    }
}

class HostSleepSequenceTest {
    @Test
    fun aSecondRequestIsRefusedWhileOneCountsDownOrIsOut() {
        val sequence = HostSleepSequence()
        assertFalse(sequence.isBusy)
        assertTrue(sequence.startCountdown())
        assertTrue(sequence.isBusy)
        assertFalse(sequence.startCountdown())

        assertTrue(sequence.countdownElapsed())
        assertEquals(HostSleepSequence.Phase.REQUESTING, sequence.phase)
        assertTrue(sequence.isBusy)
        assertFalse(sequence.startCountdown())
        // A request already out cannot be called off; only the host's answer ends it.
        assertFalse(sequence.cancelCountdown())

        sequence.finish()
        assertFalse(sequence.isBusy)
        assertTrue(sequence.startCountdown())
    }

    @Test
    fun aCancelledCountdownSendsNothing() {
        val sequence = HostSleepSequence()
        assertTrue(sequence.startCountdown())
        assertTrue(sequence.cancelCountdown())
        assertFalse(sequence.isBusy)
        // The countdown's callback firing late must not send the request.
        assertFalse(sequence.countdownElapsed())
        assertEquals(HostSleepSequence.Phase.IDLE, sequence.phase)
        assertFalse(sequence.cancelCountdown())
    }
}

class HoldToConfirmTest {
    @Test
    fun aTapDoesNotConfirm() {
        val hold = HoldToConfirm(holdMillis = 1000L)
        hold.press(0L)

        // The stray tap on the way into the library. This is the case the
        // control exists for, so it must not confirm.
        assertFalse(hold.isComplete(120L))
        assertFalse(hold.release(120L))
    }

    @Test
    fun aHoldConfirms() {
        val hold = HoldToConfirm(holdMillis = 1000L)
        hold.press(0L)

        assertTrue(hold.isComplete(1000L))
        assertTrue(hold.release(1200L))
    }

    @Test
    fun progressTracksTheHold() {
        val hold = HoldToConfirm(holdMillis = 1000L)

        assertEquals(0f, hold.progress(500L), 0.001f)
        hold.press(0L)
        assertEquals(0.5f, hold.progress(500L), 0.001f)
        assertEquals(1f, hold.progress(4000L), 0.001f)
    }

    @Test
    fun cancelForgetsThePress() {
        val hold = HoldToConfirm(holdMillis = 1000L)
        hold.press(0L)
        hold.cancel()

        assertFalse(hold.isHolding)
        assertFalse(hold.isComplete(5000L))
    }

    @Test
    fun theGraceWindowIsLongEnoughToReadAndCancel() {
        // The undo has to sit in front of the request: once the host is down
        // there is no cancelling it from the couch.
        assertTrue(HoldToConfirm.SLEEP_GRACE_MILLIS >= 3000L)
    }
}
