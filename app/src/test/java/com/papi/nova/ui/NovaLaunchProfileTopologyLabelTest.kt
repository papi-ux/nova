package com.papi.nova.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovaLaunchProfileTopologyLabelTest {
    private fun optimization(requested: String, resolved: String): JSONObject = JSONObject()
        .put("source", "deterministic_preset_v1")
        .put(
            "resolved_profile",
            JSONObject()
                .put("policy_version", 1)
                .put("preset", "auto")
                .put("preset_label", "Auto")
                .put(
                    "fields",
                    JSONObject()
                        .put("display_width", JSONObject().put("value", 1920))
                        .put("display_height", JSONObject().put("value", 1080))
                        .put("target_fps", JSONObject().put("value", 60.0))
                        .put("target_bitrate_kbps", JSONObject().put("value", 20000)),
                ),
        )
        .put(
            "topology_resolution",
            JSONObject()
                .put("requested", requested)
                .put("resolved", resolved)
                .put("locked", true)
                .put("normalized", !resolved.equals(requested, ignoreCase = true))
                .put("source", "app_configuration")
                .put("reason_code", "app_desktop_mirror_semantics"),
        )

    @Test
    fun aNormalizedTopologyIsNamedAsWhatTheHostWillDo() {
        // Desktop asks for a Host Virtual Display and Polaris answers Mirror Desktop, because its
        // own semantics are the desktop. The page showed the request, so it promised a new screen
        // and then took over the one already there.
        val summary = buildNovaLaunchProfileSummary(
            optimization = optimization("host_virtual_display", "desktop_display"),
            clientAskedFps = 60.0,
        )

        assertEquals("Mirror Desktop", summary?.resolvedTopologyLabel)
    }

    @Test
    fun anOrdinaryLaunchAddsNothing() {
        // The host resolved exactly what was asked, so repeating it back would be noise, and the
        // page keeps saying what this client chose.
        val summary = buildNovaLaunchProfileSummary(
            optimization = optimization("host_virtual_display", "host_virtual_display"),
            clientAskedFps = 60.0,
        )

        assertEquals("", summary?.resolvedTopologyLabel)
    }
}
