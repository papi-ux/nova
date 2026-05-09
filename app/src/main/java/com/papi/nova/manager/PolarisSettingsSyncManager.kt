package com.papi.nova.manager

import android.os.Handler
import android.os.Looper
import com.papi.nova.LimeLog
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps Nova's Polaris-facing settings views bound to the host-confirmed state.
 * Polaris is canonical when reachable; Nova only presents pending UI until the
 * host confirms the write through the client-settings response.
 */
class PolarisSettingsSyncManager(
    private val apiClient: PolarisApiClient,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val onSettings: (PolarisClientSettings?) -> Unit
) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NovaPolarisSettingsSync").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    @Volatile
    var lastSettings: PolarisClientSettings? = null
        private set

    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            if (running.get()) {
                mainHandler.postDelayed(this, pollMs)
            }
        }
    }

    fun start(immediate: Boolean = true) {
        if (!running.compareAndSet(false, true)) return
        if (immediate) refresh() else mainHandler.postDelayed(pollRunnable, pollMs)
        mainHandler.postDelayed(pollRunnable, pollMs)
    }

    fun refresh() {
        submit {
            apiClient.getClientSettings()
        }
    }

    fun update(
        streamDisplayMode: String? = null,
        displayMode: String? = null,
        clearDisplayMode: Boolean = false,
        targetBitrateKbps: Int? = null,
        clearTargetBitrate: Boolean = false,
        adaptiveBitrateEnabled: Boolean? = null,
        aiOptimizerEnabled: Boolean? = null
    ) {
        submit {
            apiClient.updateClientSettings(
                streamDisplayMode = streamDisplayMode,
                displayMode = displayMode,
                clearDisplayMode = clearDisplayMode,
                targetBitrateKbps = targetBitrateKbps,
                clearTargetBitrate = clearTargetBitrate,
                adaptiveBitrateEnabled = adaptiveBitrateEnabled,
                aiOptimizerEnabled = aiOptimizerEnabled
            )
        }
    }

    private fun submit(fetch: () -> PolarisClientSettings?) {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            executor.execute {
                val settings = try {
                    fetch()
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Polaris settings sync failed: ${e.message}")
                    null
                } finally {
                    inFlight.set(false)
                }

                if (settings != null) {
                    lastSettings = settings
                }
                if (running.get()) {
                    mainHandler.post { onSettings(settings ?: lastSettings) }
                }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.set(false)
        }
    }

    override fun close() {
        running.set(false)
        mainHandler.removeCallbacks(pollRunnable)
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_POLL_MS = 3000L
    }
}
