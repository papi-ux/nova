package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class LaunchOptimizationPreflightPolicyTest {
    @Test
    fun rejectedSameAppPreflightCannotReplaceCurrentResolutionOrFps() {
        val current = LaunchOptimizationRequestEnvelope(
            width = 2560,
            height = 1440,
            fps = 120f,
            displayLocked = true,
            bitrateKbps = 40_000,
            bitrateLocked = true,
        )
        val stalePreflight = JSONObject()
            .put("display_width", 1920)
            .put("display_height", 1080)
            .put("target_fps", 60)
            .put("target_bitrate_kbps", 20_000)

        val selection = LaunchOptimizationPreflightPolicy.select(
            callerRequest = current,
            candidate = stalePreflight,
            accepted = false,
        )

        assertNull(selection.trustedPreflight)
        assertSame(current, selection.resolverRequest)
        assertEquals(2560, selection.resolverRequest?.width)
        assertEquals(1440, selection.resolverRequest?.height)
        assertEquals(120f, selection.resolverRequest?.fps)
        assertEquals(40_000, selection.resolverRequest?.bitrateKbps)
    }

    @Test
    fun onlyAnAcceptedPreflightSuppressesTheReplacementRequest() {
        val current = LaunchOptimizationRequestEnvelope(2560, 1440, 120f, true, 40_000, true)
        val acceptedPreflight = JSONObject().put("source", "deterministic")

        val selection = LaunchOptimizationPreflightPolicy.select(
            callerRequest = current,
            candidate = acceptedPreflight,
            accepted = true,
        )

        assertSame(acceptedPreflight, selection.trustedPreflight)
        assertNull(selection.resolverRequest)
    }
}
