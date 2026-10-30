package com.papi.nova.network

import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps each paired host's own answer about how this device reaches it, for the connection chips on
 * the Hosts screen, and only while that screen watches. Each host is asked on its own, so a slow one
 * holds up nobody else, and an answer drops out when it ages past its window, whether or not the
 * next has arrived. A host that confirmed something is asked again just before its answer ages out;
 * any other answer, which shows no chip, waits much longer.
 */
internal class HostConnectionWatch(
    private val scope: CoroutineScope,
    /** Opens a host for asking, once per [Target], off the main thread. */
    private val asker: (Target) -> () -> HostConnectionObservation,
    /** The current answers by host UUID: fresh, and only for hosts still being watched. */
    private val onChange: (Map<String, HostConnectionEvidence>) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val wallNowMs: () -> Long = System::currentTimeMillis,
    private val elapsedNowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    data class Target(val uuid: String, val address: String, val httpsPort: Int, val serverCert: X509Certificate)

    private val following = HashMap<Target, Job>()
    private var answers: Map<String, HostConnectionEvidence> = emptyMap()

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
        var ask: (() -> HostConnectionObservation)? = null
        var expiry: Job? = null
        while (true) {
            val observation = withContext(io) {
                runCatching { (ask ?: asker(target).also { ask = it })() }.getOrNull()
            }
            expiry?.cancel()
            val freshFor = observation?.freshForMs(wallNowMs(), elapsedNowMs())
            show(target.uuid, observation?.evaluate(wallNowMs(), elapsedNowMs()))
            expiry = freshFor?.let { scope.launch { pause(it); show(target.uuid, null) } }
            pause(freshFor?.let { (it - REFRESH_LEAD_MS).coerceAtLeast(MIN_ASK_GAP_MS) } ?: UNCONFIRMED_ASK_AGAIN_MS)
        }
    }

    private fun show(uuid: String, evidence: HostConnectionEvidence?) {
        publish(if (evidence == null) answers - uuid else answers + (uuid to evidence))
    }

    private fun publish(next: Map<String, HostConnectionEvidence>) {
        if (next == answers) return
        answers = next
        onChange(next)
    }

    companion object {
        /** How far ahead of an answer's ageing out the next one is asked for. */
        const val REFRESH_LEAD_MS = 3_000L
        const val MIN_ASK_GAP_MS = 2_000L
        const val UNCONFIRMED_ASK_AGAIN_MS = 30_000L
    }
}
