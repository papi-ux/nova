package com.papi.nova.network

import com.papi.nova.ui.NovaHostNowPlaying
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps what each host reports of this device's resumable session, for the now-playing pill on the
 * Hosts screen, and only while that screen watches. Only a host already saying this device has a
 * game running is asked, on its own, so a slow one holds up nobody else. An answer of no session,
 * or the host leaving the watch, clears its pill. A failed ask clears it, since an action can no longer be bound to a known session.
 */
internal class HostNowPlayingWatch(
    private val scope: CoroutineScope,
    /** Opens a host for asking, once per [Target], off the main thread. An ask throws if it got no answer. */
    private val asker: (Target) -> () -> NovaHostNowPlaying?,
    /** The current sessions by host UUID, only for hosts still being watched. */
    private val onChange: (Map<String, NovaHostNowPlaying>) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    data class Target(val uuid: String, val address: String, val httpsPort: Int, val serverCert: X509Certificate, val gameId: Int = 0, val gameUuid: String? = null)

    private val following = HashMap<Target, Job>()
    private var answers: Map<String, NovaHostNowPlaying> = emptyMap()

    /** Follows exactly [targets] from now on. Hosts that stay the same keep their place. */
    fun watch(targets: List<Target>) {
        val wanted = targets.toSet()
        following.keys.filter { it !in wanted }.forEach { target ->
            following.remove(target)?.cancel()
            show(target.uuid, null)
        }
        wanted.filter { it !in following }.forEach { target ->
            following[target] = scope.launch { follow(target) }
        }
    }

    /** Stops asking. What was known goes with it, since nothing will keep it fresh. */
    fun stop() {
        following.values.forEach { it.cancel() }
        following.clear()
        publish(emptyMap())
    }

    private suspend fun follow(target: Target) {
        var ask: (() -> NovaHostNowPlaying?)? = null
        while (true) {
            val answer = withContext(io) { runCatching { (ask ?: asker(target).also { ask = it })() } }
            show(target.uuid, answer.getOrNull()?.takeIf {
                (target.gameId == 0 || it.gameId == target.gameId) &&
                    (target.gameUuid.isNullOrBlank() || it.gameUuid == target.gameUuid)
            }?.copy(host = NovaHostNowPlaying.Host(target.uuid, target.address, target.httpsPort, target.serverCert)))
            pause(ASK_EVERY_MS)
        }
    }

    private fun show(uuid: String, playing: NovaHostNowPlaying?) {
        publish(if (playing == null) answers - uuid else answers + (uuid to playing))
    }

    private fun publish(next: Map<String, NovaHostNowPlaying>) {
        if (next == answers) return
        answers = next
        onChange(next)
    }

    companion object {
        const val ASK_EVERY_MS = 15_000L
    }
}
