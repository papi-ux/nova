package com.papi.nova.manager

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface NovaLiveBitrateTransport {
    fun status(): PolarisSessionStatus?
    fun confirmedKbps(): Int? = null
    fun setBitrate(kbps: Int, observed: PolarisSessionStatus): Boolean
}
data class NovaLiveBitrateState(val requestedKbps: Int? = null, val recommendedKbps: Int? = null,
    val receivedKbps: Int? = null, val codec: String = "", val maximumKbps: Int = 300000, val canChange: Boolean = false, val busy: Boolean = false)
enum class NovaBitrateChange { APPLIED, UNAVAILABLE, SESSION_CHANGED, FAILED }

/** One controller per stream. It never writes Settings or a host's saved profile. */
class NovaLiveBitrateController(private val transport: NovaLiveBitrateTransport,
    private val sessionId: String, private val generation: Long) {
    constructor(api: PolarisApiClient, observed: PolarisSessionStatus) : this(object : NovaLiveBitrateTransport {
        private var confirmed: Int? = null
        override fun status() = api.getSessionStatus()
        override fun confirmedKbps() = confirmed
        override fun setBitrate(kbps: Int, observed: PolarisSessionStatus): Boolean {
            confirmed = null
            return api.setBitrate(kbps, observed) { confirmed = it }
        }
    }, observed.appSessionId, observed.sessionGeneration)

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(NovaLiveBitrateState())
    val state = mutableState.asStateFlow()
    private fun same(status: PolarisSessionStatus) = sessionId.isNotBlank() && generation > 0 &&
        status.appSessionId == sessionId && status.sessionGeneration == generation
    private fun allowed(status: PolarisSessionStatus) = same(status) && status.streamingActive &&
        !status.shutdownRequested && !status.isViewer && status.canAdjustHostTuning && !status.liveTuningUnavailable

    private var preflightHostMaximum: Int? = null
    private fun maximum(status: PolarisSessionStatus): Int = minOf(300000,
        preflightHostMaximum ?: 300000,
        status.pyrowaveBitrate?.hostMaximumKbps ?: 300000,
        status.pyrowaveBitrate?.capKbps ?: 300000)

    fun observe(status: PolarisSessionStatus?, receivedKbps: Int? = null, tableRecommendedKbps: Int? = null,
        hostMaximumKbps: Int? = null) {
        val valid = status?.takeIf(::same)
        if (valid != null && hostMaximumKbps != null) preflightHostMaximum = hostMaximumKbps.takeIf { it in 1000..300000 }
        mutableState.value = NovaLiveBitrateState(
            requestedKbps = valid?.liveTuning?.requestedBitrateKbps?.takeIf { it in 1000..300000 },
            recommendedKbps = valid?.pyrowaveBitrate?.raiseGoalKbps ?:
                tableRecommendedKbps?.takeIf { valid != null && it in 1000..300000 },
            receivedKbps = receivedKbps?.takeIf { valid?.streamingActive == true && it > 0 },
            codec = valid?.encoder?.codec.orEmpty(),
            maximumKbps = valid?.let(::maximum) ?: 300000,
            canChange = valid?.let(::allowed) == true, busy = mutableState.value.busy)
    }

    suspend fun useRecommended(): NovaBitrateChange = change(null)
    suspend fun setBitrate(kbps: Int): NovaBitrateChange = change(kbps)
    suspend fun step(direction: Int): NovaBitrateChange {
        if (direction !in listOf(-1, 1) || state.value.requestedKbps == null) return NovaBitrateChange.UNAVAILABLE
        return change(null, direction)
    }

    private suspend fun change(request: Int?, direction: Int? = null): NovaBitrateChange = mutex.withLock {
        mutableState.value = state.value.copy(busy = true)
        try {
            withContext(Dispatchers.IO) {
                val observed = transport.status() ?: return@withContext NovaBitrateChange.UNAVAILABLE
                if (!same(observed)) { observe(null); return@withContext NovaBitrateChange.SESSION_CHANGED }
                if (!allowed(observed)) { observe(observed); return@withContext NovaBitrateChange.UNAVAILABLE }
                val wanted = if (direction != null) {
                    val current = state.value.requestedKbps ?: return@withContext NovaBitrateChange.UNAVAILABLE
                    when (observed.encoder.codec.lowercase()) {
                        "pyrowave" -> (kotlin.math.round(current * (1.0 + direction * 0.1) / 500.0) * 500).toInt()
                        "h264", "h.264", "avc", "hevc", "h265", "h.265", "av1" -> current + direction * 5000
                        else -> return@withContext NovaBitrateChange.UNAVAILABLE
                    }
                } else request ?: observed.pyrowaveBitrate?.raiseGoalKbps ?: state.value.recommendedKbps
                if (wanted == null || (direction == null && wanted !in 1000..300000)) return@withContext NovaBitrateChange.UNAVAILABLE
                val maximum = maximum(observed)
                if (maximum < 1000) return@withContext NovaBitrateChange.UNAVAILABLE
                val kbps = wanted.coerceIn(1000, maximum)
                if (observed.liveTuningPresent && observed.liveTuning == null) return@withContext NovaBitrateChange.UNAVAILABLE
                // The host endpoint pauses Live Tuning only for this stream. Its saved switch is untouched.
                if (!transport.setBitrate(kbps, observed)) return@withContext NovaBitrateChange.FAILED
                val confirmed = transport.confirmedKbps()?.takeIf { it in 1000..300000 } ?: kbps
                if (confirmed < kbps) preflightHostMaximum = minOf(preflightHostMaximum ?: 300000, confirmed)
                mutableState.value = state.value.copy(requestedKbps = confirmed, maximumKbps = maximum(observed))
                NovaBitrateChange.APPLIED
            }
        } finally { mutableState.value = state.value.copy(busy = false) }
    }
}
