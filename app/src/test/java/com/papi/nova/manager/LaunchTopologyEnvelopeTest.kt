package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class LaunchTopologyEnvelopeTest {
    @Test
    fun hostDefaultIsDistinctFromAnExplicitDesktopRequest() {
        val optimization = optimization(
            requested = "host_default",
            resolved = "gamescope_stream",
            locked = false,
        )

        assertTrue(
            LaunchTopologyEnvelope.matches(
                optimization, "game-a", "", false, false, false
            )
        )
        assertTrue(
            LaunchTopologyEnvelope.matches(
                optimization, "1", "", false, false, false
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization, "game-a", "desktop_display", false, false, false
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization("desktop_display", "gamescope_stream", true),
                "game-a",
                "desktop_display",
                true,
                false,
                false,
            )
        )
    }

    @Test
    fun steamChoiceAndCanonicalAppIdentityMustMatchExactly() {
        val optimization = optimization(
            requested = "desktop_display",
            resolved = "desktop_display",
            locked = true,
            mirror = true,
        )

        assertTrue(
            LaunchTopologyEnvelope.matches(
                optimization, "game-a", "desktop_display", true, true, false
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization, "game-a", "desktop_display", true, false, false
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization, "duplicate-title-game-b", "desktop_display", true, true, false
            )
        )
    }

    @Test
    fun missingOrCoercedTopologyFieldsFailClosed() {
        val missing = JSONObject()
        val coerced = optimization("host_default", "desktop_display", false)
        coerced.getJSONObject("topology_resolution").put("locked", "false")

        assertFalse(LaunchTopologyEnvelope.matches(missing, "game-a", "", false, false, false))
        assertFalse(LaunchTopologyEnvelope.matches(coerced, "game-a", "", false, false, false))
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization("host_default", "invented_topology", false),
                "game-a",
                "",
                false,
                false,
                false,
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization("host_default", "desktop_display", false),
                "",
                "",
                false,
                false,
                false,
            )
        )
    }

    private fun optimization(
        requested: String,
        resolved: String,
        locked: Boolean,
        mirror: Boolean = false,
        forcePrivate: Boolean = false,
    ): JSONObject = JSONObject().put(
        "topology_resolution",
        JSONObject()
            .put("requested", requested)
            .put("resolved", resolved)
            .put("locked", locked)
            .put("mirror_desktop_requested", mirror)
            .put("force_private_after_steam_close_requested", forcePrivate)
            .put("app_uuid", "game-a")
            .put("app_id", "1"),
    )
}
