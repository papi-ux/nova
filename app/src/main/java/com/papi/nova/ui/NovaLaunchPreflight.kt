package com.papi.nova.ui

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.preferences.PreferenceConfiguration

/**
 * The one place a launch pushes its per-client display intent to the host before starting.
 *
 * Three surfaces launch games (game detail, the library result path, and shortcut
 * trampolines) and each used to carry its own copy of this POST.
 *
 * Staged fix (step 1): a per-game launch must NOT rewrite the host-wide stream mode.
 * This helper no longer pushes stream_display_mode: the host durably persists that field
 * (apply_stream_display_mode_selection), so a per-game override — or a stale per-game
 * cache — silently flipped the host's use_cage_compositor/headless flags, stopped the
 * private compositor from spawning, and made capture fall through to the desktop and
 * hard-fail. Only per-client display/bitrate are pushed here now; mirror and virtual
 * display still travel session-scoped on the /launch URL. The per-session stream mode
 * travels the same way (step 2): the resolved override rides the /launch streamMode
 * param via ServerHelper -> Game -> NvHTTP, which is why usesVirtualDisplay,
 * mirrorDesktop, resolvedMode and clientSettings are retained in the signature (the
 * call sites also use them for the /optimize mode hint and the /launch URL).
 */
@Suppress("UNUSED_PARAMETER")
object NovaLaunchPreflight {

    fun push(
        apiClient: PolarisApiClient,
        clientSettings: PolarisClientSettings?,
        usesVirtualDisplay: Boolean,
        mirrorDesktop: Boolean = false,
        resolvedMode: String = "",
        width: Int,
        height: Int,
        fps: Float,
        bitrateKbps: Int?,
    ): PolarisClientSettings? = apiClient.updateClientSettings(
        // No stream_display_mode: see the class doc — a launch must not rewrite the
        // host-wide stream mode. Only per-client display/bitrate travel here.
        displayMode = PreferenceConfiguration.formatStreamingDisplayMode(width, height, fps),
        targetBitrateKbps = bitrateKbps?.takeIf { it > 0 },
    )
}
