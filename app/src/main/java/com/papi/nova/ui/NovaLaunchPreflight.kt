package com.papi.nova.ui

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.preferences.PreferenceConfiguration

/**
 * The one place a launch pushes its display intent to the host before starting.
 *
 * Three surfaces launch games (game detail, the library result path, and shortcut
 * trampolines) and each used to carry its own copy of this POST; a change to how
 * the mode travels had to find all three. Mirror wins outright because it is a
 * session-scoped request, not a persistent host mode; everything else resolves
 * through preflightModeForLaunch so a private-family host default survives the
 * launch untouched.
 */
object NovaLaunchPreflight {

    fun push(
        apiClient: PolarisApiClient,
        clientSettings: PolarisClientSettings?,
        usesVirtualDisplay: Boolean,
        mirrorDesktop: Boolean = false,
        width: Int,
        height: Int,
        fps: Float,
        bitrateKbps: Int?,
    ): PolarisClientSettings? = apiClient.updateClientSettings(
        streamDisplayMode = if (mirrorDesktop) {
            PolarisClientSettings.MODE_DESKTOP_DISPLAY
        } else {
            PolarisStreamDisplayMode.preflightModeForLaunch(usesVirtualDisplay, clientSettings)
        },
        displayMode = PreferenceConfiguration.formatStreamingDisplayMode(width, height, fps),
        targetBitrateKbps = bitrateKbps?.takeIf { it > 0 },
    )
}
