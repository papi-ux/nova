package com.papi.nova.manager

import org.json.JSONObject

/** Exact topology identity carried by a deterministic /optimize response. */
internal object LaunchTopologyEnvelope {
    private val supportedTopologies = setOf(
        "desktop_display",
        "host_virtual_display",
        "headless_stream",
        "windowed_stream",
        "gamescope_stream",
        "headless_dongle",
    )

    fun matches(
        optimization: JSONObject,
        appIdentity: String,
        requestedTopology: String,
        topologyLocked: Boolean,
        mirrorDesktopRequested: Boolean,
        forcePrivateRequested: Boolean,
    ): Boolean {
        val topologyResolution = optimization.optJSONObject("topology_resolution") ?: return false
        val topologyRequest = topologyResolution.opt("requested") as? String ?: return false
        val resolvedTopology = topologyResolution.opt("resolved") as? String ?: return false
        val contractLocked = topologyResolution.opt("locked") as? Boolean ?: return false
        val contractMirror = topologyResolution.opt("mirror_desktop_requested") as? Boolean ?: return false
        val contractForcePrivate = topologyResolution.opt(
            "force_private_after_steam_close_requested"
        ) as? Boolean ?: return false
        val contractAppUuid = topologyResolution.opt("app_uuid") as? String ?: return false
        val contractAppId = topologyResolution.opt("app_id") as? String ?: return false
        val expectedRequest = requestedTopology.ifBlank { "host_default" }
        val expectedApp = appIdentity.trim()
        val normalizedResolved = resolvedTopology.lowercase()
        val appMatches = expectedApp.isNotEmpty() &&
            (contractAppUuid.equals(expectedApp, ignoreCase = true) ||
                contractAppId.equals(expectedApp, ignoreCase = true))
        val lockedResolutionMatches = !topologyLocked || expectedRequest == "host_default" ||
            resolvedTopology.equals(expectedRequest, ignoreCase = true)

        return topologyRequest.equals(expectedRequest, ignoreCase = true) &&
            normalizedResolved in supportedTopologies &&
            lockedResolutionMatches &&
            contractLocked == topologyLocked &&
            contractMirror == mirrorDesktopRequested &&
            contractForcePrivate == forcePrivateRequested &&
            appMatches
    }
}
