package com.papi.nova.manager

import org.json.JSONObject

/** Exact topology identity carried by a deterministic /optimize response. */
internal object LaunchTopologyEnvelope {
    private val supportedTopologies = setOf(
        "desktop_display",
        "desktop_takeover",
        "host_virtual_display",
        "headless_stream",
        "windowed_stream",
        "gamescope_stream",
        "headless_dongle",
    )
    private val authoritativeSources = setOf(
        "client_launch_request",
        "paired_client_settings",
        "app_configuration",
        "host_capability",
        "host_configuration",
    )

    /**
     * The one substitution a locked topology accepts, as reason code to the source allowed to give it.
     *
     * A lock stops the host handing back a topology nobody asked for. It must not stop an app whose
     * own semantics are the desktop: Desktop resolves any request into desktop_display, so every
     * client that had set an explicit display mode locked its topology and was refused here, before
     * the host was ever asked to launch anything. Polaris is built for the losing request. Its launch
     * resolver defers that mode's availability and still refuses the launch if the app turns out not
     * to override it, so the exception costs the lock nothing it was protecting.
     *
     * Both halves have to agree. A reason on its own, or a source on its own, does not open it.
     */
    private val lockedDesktopMirrorNormalizations = mapOf(
        "app_desktop_mirror_semantics" to "app_configuration",
        "explicit_mirror_desktop" to "client_launch_request",
    )

    /**
     * Canonical topology assertion from a trusted deterministic response.
     * This value never selects host policy; it is echoed on /launch so Polaris
     * can reject the request if its final resolver no longer agrees.
     */
    fun resolvedSelection(optimization: JSONObject?): String? {
        val topologyResolution = optimization?.optJSONObject("topology_resolution") ?: return null
        val resolved = topologyResolution.opt("resolved") as? String ?: return null
        return resolved.trim().lowercase().takeIf { it in supportedTopologies }
    }

    fun matches(
        optimization: JSONObject,
        appIdentity: String,
        requestedTopology: String,
        topologyLocked: Boolean,
        mirrorDesktopRequested: Boolean,
        forcePrivateRequested: Boolean,
    ): Boolean {
        if (optimization.opt("source") == WorkerLaunchContract.SOURCE) {
            return WorkerLaunchContract.parse(optimization) != null &&
                WorkerLaunchContract.isProfileApp(appIdentity) &&
                !mirrorDesktopRequested && !forcePrivateRequested
        }
        val topologyResolution = optimization.optJSONObject("topology_resolution") ?: return false
        val topologyRequest = topologyResolution.opt("requested") as? String ?: return false
        val resolvedTopology = topologyResolution.opt("resolved") as? String ?: return false
        val contractLocked = topologyResolution.opt("locked") as? Boolean ?: return false
        val contractSource = topologyResolution.opt("source") as? String ?: return false
        val contractReason = topologyResolution.opt("reason_code") as? String ?: return false
        val contractNormalized = topologyResolution.opt("normalized") as? Boolean ?: return false
        val contractMirror = topologyResolution.opt("mirror_desktop_requested") as? Boolean ?: return false
        val contractForcePrivate = topologyResolution.opt(
            "force_private_after_steam_close_requested"
        ) as? Boolean ?: return false
        val contractAppUuid = topologyResolution.opt("app_uuid") as? String ?: return false
        val contractAppId = topologyResolution.opt("app_id") as? String ?: return false
        val expectedRequest = requestedTopology.ifBlank { "host_default" }
        val expectedApp = appIdentity.trim()
        val normalizedResolved = resolvedSelection(optimization) ?: return false
        val appMatches = expectedApp.isNotEmpty() &&
            (contractAppUuid.equals(expectedApp, ignoreCase = true) ||
                contractAppId.equals(expectedApp, ignoreCase = true))
        val desktopMirrorNormalization = normalizedResolved == "desktop_display" &&
            lockedDesktopMirrorNormalizations[contractReason] == contractSource
        val lockedResolutionMatches = !topologyLocked || expectedRequest == "host_default" ||
            resolvedTopology.equals(expectedRequest, ignoreCase = true) ||
            desktopMirrorNormalization
        val expectedNormalized = expectedRequest != "host_default" &&
            !resolvedTopology.equals(expectedRequest, ignoreCase = true)

        return topologyRequest.equals(expectedRequest, ignoreCase = true) &&
            normalizedResolved in supportedTopologies &&
            lockedResolutionMatches &&
            contractLocked == topologyLocked &&
            contractSource in authoritativeSources &&
            contractReason.isNotBlank() &&
            contractNormalized == expectedNormalized &&
            contractMirror == mirrorDesktopRequested &&
            contractForcePrivate == forcePrivateRequested &&
            appMatches
    }
}
