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
    ) = PolarisCapabilities(
        server = "polaris",
        version = "1.4.9",
        features = PolarisCapabilities.Features(hostSleep = hostSleep),
        capture = PolarisCapabilities.CaptureInfo(),
        hostPower = PolarisCapabilities.HostPower(
            sleepSupported = supported,
            sleepEnabled = enabled,
            sleepPermitted = permitted,
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
