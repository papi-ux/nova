package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class LaunchTopologyEnvelopeTest {
    @Test
    fun desktopTakeoverIsAcceptedAsAHostAuthoritativeTopology() {
        val optimization = optimization(
            requested = "desktop_takeover",
            resolved = "desktop_takeover",
            locked = true,
        )

        assertEquals("desktop_takeover", LaunchTopologyEnvelope.resolvedSelection(optimization))
        assertTrue(
            LaunchTopologyEnvelope.matches(
                optimization, "game-a", "desktop_takeover", true, false, false
            )
        )
    }

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
        assertEquals("gamescope_stream", LaunchTopologyEnvelope.resolvedSelection(optimization))
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
        val inventedSource = optimization("host_default", "desktop_display", false)
        inventedSource.getJSONObject("topology_resolution").put("source", "doctor_history")

        assertFalse(LaunchTopologyEnvelope.matches(missing, "game-a", "", false, false, false))
        assertNull(LaunchTopologyEnvelope.resolvedSelection(missing))
        assertFalse(LaunchTopologyEnvelope.matches(coerced, "game-a", "", false, false, false))
        assertFalse(LaunchTopologyEnvelope.matches(inventedSource, "game-a", "", false, false, false))
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
        assertNull(
            LaunchTopologyEnvelope.resolvedSelection(
                optimization("host_default", "invented_topology", false)
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

    @Test
    fun aLockedTopologyAcceptsAnAppWhoseSemanticsAreTheDesktop() {
        // Desktop resolves whatever was asked into desktop_display and names the reason. Every client
        // that sets an explicit display mode locks its topology, and a locked request used to demand
        // an exact match, so no such client could open Desktop at all: it was refused here, before the
        // host was ever asked to launch it. Polaris expects the losing request. Its launch resolver
        // defers that mode's availability and still refuses the launch if the app does not override it.
        val desktop = optimization(
            requested = "host_virtual_display",
            resolved = "desktop_display",
            locked = true,
            source = "app_configuration",
            reason = "app_desktop_mirror_semantics",
        )

        assertTrue(
            LaunchTopologyEnvelope.matches(
                desktop, "game-a", "host_virtual_display", true, false, false
            )
        )
        // The resolved topology is what Nova then asserts on /launch, so the host can disagree once more.
        assertEquals("desktop_display", LaunchTopologyEnvelope.resolvedSelection(desktop))

        // The same exception covers a client that asked for the mirror itself.
        assertTrue(
            LaunchTopologyEnvelope.matches(
                optimization(
                    requested = "host_virtual_display",
                    resolved = "desktop_display",
                    locked = true,
                    mirror = true,
                    source = "client_launch_request",
                    reason = "explicit_mirror_desktop",
                ),
                "game-a",
                "host_virtual_display",
                true,
                true,
                false,
            )
        )
    }

    @Test
    fun aLockedTopologyStillRefusesEverySubstitutionThatIsNotTheDesktopMirror() {
        // The lock is what stops a host quietly handing back an unrelated topology. Only the desktop
        // mirror is excused, and only when the host names it: the reason and the source have to agree,
        // so neither one alone opens the exception.
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization(
                    "host_virtual_display",
                    "gamescope_stream",
                    true,
                    source = "app_configuration",
                    reason = "app_desktop_mirror_semantics",
                ),
                "game-a",
                "host_virtual_display",
                true,
                false,
                false,
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization(
                    "host_virtual_display",
                    "desktop_display",
                    true,
                    source = "host_configuration",
                    reason = "app_desktop_mirror_semantics",
                ),
                "game-a",
                "host_virtual_display",
                true,
                false,
                false,
            )
        )
        assertFalse(
            LaunchTopologyEnvelope.matches(
                optimization(
                    "host_virtual_display",
                    "desktop_display",
                    true,
                    source = "app_configuration",
                    reason = "host_default_topology",
                ),
                "game-a",
                "host_virtual_display",
                true,
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
        source: String = "client_launch_request",
        reason: String = "test_topology_resolution",
    ): JSONObject = JSONObject().put(
        "topology_resolution",
        JSONObject()
            .put("requested", requested)
            .put("resolved", resolved)
            .put("locked", locked)
            .put("source", source)
            .put("reason_code", reason)
            .put(
                "normalized",
                requested != "host_default" && !resolved.equals(requested, ignoreCase = true),
            )
            .put("mirror_desktop_requested", mirror)
            .put("force_private_after_steam_close_requested", forcePrivate)
            .put("app_uuid", "game-a")
            .put("app_id", "1"),
    )
}
