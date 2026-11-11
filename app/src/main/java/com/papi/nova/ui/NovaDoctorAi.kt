package com.papi.nova.ui

import com.papi.nova.api.DoctorAiReading
import com.papi.nova.api.DoctorAiRequest
import com.papi.nova.api.DoctorAiResponse
import com.papi.nova.api.DoctorAiText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart

internal data class DoctorAiContext(val api: Any, val connection: Any, val reading: DoctorAiReading) {
    fun sameFinding(other: DoctorAiContext) = api === other.api && connection === other.connection &&
        reading.scope != null && reading.scope == other.reading.scope
    fun valid(now: Long) = reading.supported && reading.fresh(now) && reading.scope != null &&
        reading.descriptor?.enabled == true && reading.descriptor.permitted
}

enum class NovaDoctorAiMessage {
    UNAVAILABLE, DISABLED, READY, PENDING, FALLBACK, EXPLAINED,
    // The host's own reasons for refusing (#476): each says what stands in the way.
    STOPPING, IN_A_SPACE, NOT_ALLOWED, OWNER_ONLY, PROVIDER_NOT_OFFERED, NOT_ELIGIBLE, BUSY, RATE_LIMITED,
}

/**
 * The message for the reason a host's paired descriptor gives for refusing an explanation
 * (blocked_reason, Polaris src/doctor_ai_explanation.cpp), or null for none or one this build does
 * not know. Every refusal read "unavailable for this reading", which pointed the player at the
 * reading whatever the reason was (#476).
 */
internal fun novaDoctorAiRefusal(blockedReason: String): NovaDoctorAiMessage? = when (blockedReason) {
    "server_stopping" -> NovaDoctorAiMessage.STOPPING
    "unsupported_scope" -> NovaDoctorAiMessage.IN_A_SPACE
    "permission_required" -> NovaDoctorAiMessage.NOT_ALLOWED
    "active_owner_required" -> NovaDoctorAiMessage.OWNER_ONLY
    "host_ai_disabled" -> NovaDoctorAiMessage.DISABLED
    "provider_not_ready" -> NovaDoctorAiMessage.PROVIDER_NOT_OFFERED
    "evidence_unavailable" -> NovaDoctorAiMessage.NOT_ELIGIBLE
    "request_busy" -> NovaDoctorAiMessage.BUSY
    "rate_limited" -> NovaDoctorAiMessage.RATE_LIMITED
    else -> null
}

data class NovaDoctorAiState(
    val enabled: Boolean = false,
    val message: NovaDoctorAiMessage = NovaDoctorAiMessage.UNAVAILABLE,
    val text: DoctorAiText? = null,
    val ageSeconds: Long = 0,
)

/** One details-page lifetime. Observation can retire a request but can never start one. */
internal class NovaDoctorAi(
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val fetch: suspend (DoctorAiRequest, () -> Boolean) -> DoctorAiResponse,
) {
    private val mutable = MutableStateFlow(NovaDoctorAiState())
    val state: StateFlow<NovaDoctorAiState> = mutable
    private var context: DoctorAiContext? = null
    private var visible = false
    private var capturedAtMs = 0L
    private var generation = 0L
    private var request: DoctorAiRequest? = null
    private var job: Job? = null
    private var freshness: Job? = null
    private var expiry: Job? = null

    @Synchronized fun observe(next: DoctorAiContext?) {
        val old = context
        if (next == null || !next.valid(now()) || (old != null && !old.sameFinding(next))) retireLocked()
        context = next
        freshness?.cancel()
        freshness = null
        if (visible && next?.valid(now()) == true) {
            val remaining = 5_000 - (now() - next.reading.observedAtMs)
            freshness = coroutineScope.launch { delay(remaining.coerceAtLeast(1)); stale(next.reading.observedAtMs) }
        }
        redrawLocked()
    }

    @Synchronized fun pageVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) { retireLocked(); freshness?.cancel(); freshness = null }
        // A page change only observes the most recent reading.
        observe(context)
    }

    @Synchronized fun retire() { retireLocked(); context = null; redrawLocked() }

    @Synchronized private fun stale(observedAtMs: Long) {
        if (context?.reading?.observedAtMs == observedAtMs && context?.valid(now()) != true) {
            retireLocked(); redrawLocked()
        }
    }

    private fun retireLocked() {
        generation++
        request = null
        job?.cancel(); job = null
        expiry?.cancel(); expiry = null
        mutable.value = NovaDoctorAiState()
    }

    private fun redrawLocked() {
        val c = context
        val valid = visible && c?.valid(now()) == true
        val ready = valid && c?.reading?.descriptor?.available == true
        // The host's first reason, which it checks in order; it names a stopping host even where AI is off.
        val refusal = c?.reading?.takeIf { it.supported }?.descriptor?.let { novaDoctorAiRefusal(it.blockedReason) }
        val previous = mutable.value
        mutable.value = previous.copy(enabled = ready && request == null,
            ageSeconds = if (previous.text != null) ((now() - capturedAtMs) / 1000).coerceAtLeast(0) else 0,
            message = when {
                !valid && refusal != null -> refusal
                !valid && c?.reading?.supported == true && c.reading.descriptor?.enabled == false -> NovaDoctorAiMessage.DISABLED
                !valid -> NovaDoctorAiMessage.UNAVAILABLE
                // Nova's own request is what makes the host busy: it is explaining, not refusing.
                request != null -> NovaDoctorAiMessage.PENDING
                previous.text != null -> NovaDoctorAiMessage.EXPLAINED
                previous.message == NovaDoctorAiMessage.FALLBACK -> NovaDoctorAiMessage.FALLBACK
                ready -> NovaDoctorAiMessage.READY
                refusal != null -> refusal
                else -> NovaDoctorAiMessage.UNAVAILABLE
            })
    }

    /** Called only by the explicit enabled button; replayed clicks while pending do nothing. */
    fun explain() {
        val start = synchronized(this) {
            redrawLocked()
            if (!mutable.value.enabled) return
            val captured = context ?: return
            val scope = captured.reading.scope ?: return
            retireLocked()
            val currentGeneration = generation
            val sent = DoctorAiRequest(UUID.randomUUID().toString(), scope)
            request = sent
            val started = now()
            redrawLocked()
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                val response = try {
                    withTimeout(35_000) { fetch(sent) { current(currentGeneration, captured, sent) } }
                } catch (_: TimeoutCancellationException) { DoctorAiResponse()
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { DoctorAiResponse() }
                currentCoroutineContext().ensureActive()
                complete(currentGeneration, captured, sent, started, response)
            }.also { job = it }
        }
        // Main.immediate can run fetch inline: never start it while holding the model monitor.
        start.start()
    }

    @Synchronized private fun current(g: Long, captured: DoctorAiContext, sent: DoctorAiRequest): Boolean =
        generation == g && visible && request == sent && context?.let { it.valid(now()) && it.sameFinding(captured) } == true

    @Synchronized private fun complete(g: Long, captured: DoctorAiContext, sent: DoctorAiRequest, started: Long, response: DoctorAiResponse) {
        if (!current(g, captured, sent)) return
        request = null; job = null
        // Network time counts against the captured reading even if a peer overstates its lease.
        val remaining = minOf(response.expiresInMs, 60_000 - (now() - started))
        if (response.text == null || remaining <= 0) {
            mutable.value = NovaDoctorAiState(message = NovaDoctorAiMessage.FALLBACK)
        } else {
            capturedAtMs = started
            mutable.value = NovaDoctorAiState(message = NovaDoctorAiMessage.EXPLAINED, text = response.text)
            expiry = coroutineScope.launch { delay(remaining); expire(g) }
        }
        redrawLocked()
    }

    @Synchronized private fun expire(g: Long) {
        if (generation == g) { retireLocked(); redrawLocked() }
    }
}
