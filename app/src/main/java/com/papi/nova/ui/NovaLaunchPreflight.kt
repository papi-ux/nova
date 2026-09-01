package com.papi.nova.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.preferences.PreferenceConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * Retained, latest-intent owner for one game's Steam launch-mode mutation.
 *
 * The HTTP call is synchronous below OkHttp's retry wrapper, so destroying an Activity
 * cannot retract a request already delivered to Polaris. Keeping this worker in a
 * ViewModel-owned scope lets the replacement Activity join the same mutation and queue
 * its newer intent after it, instead of starting a fresh queue that can be overwritten.
 */
internal class NovaSteamLaunchModeCoordinator(
    private val scope: CoroutineScope,
    initialConfirmedMode: String,
    private val settleDelayMs: Long = NOVA_PLAY_SETUP_SETTLE_MS,
    private val write: suspend (String) -> String?,
) {
    data class Snapshot(
        val generation: Long,
        val displayMode: String,
        val pending: Boolean,
        val failed: Boolean,
    )

    private val stateLock = Any()
    private val writeQueue = NovaSteamLaunchModeWriteQueue()
    private val intents = NovaSteamLaunchModeIntentTracker(initialConfirmedMode)
    private var state = Snapshot(
        generation = 0,
        displayMode = initialConfirmedMode,
        pending = false,
        failed = false,
    )
    private var worker: Job? = null

    fun snapshot(): Snapshot = synchronized(stateLock) { state }

    fun select(mode: String): Long = synchronized(stateLock) {
        intents.select(mode)
        val commit = checkNotNull(intents.snapshot())
        state = Snapshot(
            generation = commit.generation,
            displayMode = mode,
            pending = true,
            failed = false,
        )
        if (worker?.isActive != true) {
            worker = scope.launch { drain() }
        }
        commit.generation
    }

    suspend fun awaitLatest(): Snapshot {
        while (true) {
            val observed = synchronized(stateLock) { worker }
                ?: return snapshot()
            observed.join()
            synchronized(stateLock) {
                if (worker === observed && !state.pending) return state
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val commit = synchronized(stateLock) { intents.snapshot() } ?: return
            delay(settleDelayMs)
            val stillLatest = synchronized(stateLock) { intents.owns(commit) }
            if (!stillLatest) continue

            val confirmedMode = try {
                writeQueue.commit { write(commit.mode) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            val ownsLatest = synchronized(stateLock) {
                val resolution = intents.complete(commit, confirmedMode)
                if (resolution.ownsLatest) {
                    state = Snapshot(
                        generation = commit.generation,
                        displayMode = resolution.displayMode,
                        pending = false,
                        failed = confirmedMode == null,
                    )
                    true
                } else {
                    val replacement = checkNotNull(intents.snapshot())
                    state = Snapshot(
                        generation = replacement.generation,
                        displayMode = replacement.mode,
                        pending = true,
                        failed = false,
                    )
                    false
                }
            }
            if (ownsLatest) return
        }
    }
}

/** Retains the Steam mutation authority across Game Detail configuration recreation. */
internal class NovaGameDetailLaunchViewModel(
    apiClient: PolarisApiClient,
    gameId: String,
    initialSteamLaunchMode: String,
) : ViewModel() {
    private val steamLaunchMode = NovaSteamLaunchModeCoordinator(
        scope = viewModelScope,
        initialConfirmedMode = initialSteamLaunchMode,
        write = { mode ->
            withContext(Dispatchers.IO) {
                apiClient.setSteamLaunchMode(gameId, mode)
            }
        },
    )

    fun steamLaunchModeSnapshot(): NovaSteamLaunchModeCoordinator.Snapshot =
        steamLaunchMode.snapshot()

    fun selectSteamLaunchMode(mode: String): Long = steamLaunchMode.select(mode)

    suspend fun awaitLatestSteamLaunchMode(): NovaSteamLaunchModeCoordinator.Snapshot =
        steamLaunchMode.awaitLatest()

    internal class Factory(
        context: Context,
        private val serverAddress: String,
        private val httpsPort: Int,
        serverCertDer: ByteArray?,
        private val gameId: String,
        private val initialSteamLaunchMode: String,
    ) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext
        private val serverCertificate = serverCertDer?.copyOf()

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NovaGameDetailLaunchViewModel::class.java))
            val client = PolarisApiClient(
                applicationContext,
                serverAddress,
                httpsPort,
                serverCertificate,
            )
            return NovaGameDetailLaunchViewModel(
                apiClient = client,
                gameId = gameId,
                initialSteamLaunchMode = initialSteamLaunchMode,
            ) as T
        }
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
