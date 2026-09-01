package com.papi.nova.ui

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.preferences.PreferenceConfiguration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Main-thread ownership fence for Game Detail launch preflights.
 *
 * A response is allowed to publish state or replay a held Play press only while it owns
 * the newest generation. Cancellation avoids wasted work; this generation check is the
 * authority boundary when a blocking HTTP call cannot be interrupted immediately.
 */
internal class NovaLaunchPreflightRequestFence {
    private var generation: Long = 0

    fun begin(): Long = ++generation

    fun invalidate() {
        generation += 1
    }

    fun owns(requestGeneration: Long): Boolean = requestGeneration == generation
}

/**
 * Serializes Steam launch-mode writes so a newer choice always commits after an older
 * request whose blocking HTTP exchange was already on the wire.
 */
internal class NovaSteamLaunchModeWriteQueue {
    private val mutex = Mutex()

    suspend fun <T> commit(write: suspend () -> T): T = mutex.withLock { write() }
}

/** Main-thread latest-intent state for the serialized Steam launch-mode worker. */
internal class NovaSteamLaunchModeIntentTracker(initialConfirmedMode: String) {
    data class Commit(val generation: Long, val mode: String)
    data class Resolution(val ownsLatest: Boolean, val displayMode: String)

    private var generation: Long = 0
    private var pendingMode: String? = null
    private var confirmedMode: String = initialConfirmedMode

    fun select(mode: String) {
        pendingMode = mode
        generation += 1
    }

    fun snapshot(): Commit? = pendingMode?.let { Commit(generation, it) }

    fun owns(commit: Commit): Boolean = commit.generation == generation

    fun complete(commit: Commit, hostConfirmedMode: String?): Resolution {
        if (hostConfirmedMode != null) confirmedMode = hostConfirmedMode
        if (!owns(commit)) return Resolution(ownsLatest = false, displayMode = confirmedMode)
        pendingMode = null
        return Resolution(
            ownsLatest = true,
            displayMode = hostConfirmedMode ?: confirmedMode,
        )
    }
}

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
 * display still travel session-scoped on the /launch URL. An explicit per-game mode
 * rides the streamMode parameter; every trusted result separately echoes its resolved
 * topology as expectedTopology so the host can detect drift without treating that
 * assertion as a client-authored override. The retained arguments also feed the
 * /optimize intent and the final launch URL.
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
