package com.papi.nova.api

import com.papi.nova.LimeLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-Sent Events (SSE) client for Polaris session lifecycle events.
 * Connects to /api/polaris/events and dispatches events to a listener.
 *
 * Runs on a background thread. Auto-reconnects on connection loss.
 */
class PolarisEventSource(
    private val baseUrl: String,
    private val listener: EventListener,
    sharedClient: OkHttpClient? = null
) {
    interface EventListener {
        fun onSessionEvent(event: String, state: String, message: String)
        fun onStateUpdate(sessionState: String, cageRunning: Boolean, screenLocked: Boolean)
        fun onConnectionLost()
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // Derive from shared client to reuse connection pool and TLS config,
    // but override read timeout for SSE long-polling
    private val client = (sharedClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for SSE
        .build()

    fun start() {
        if (running.getAndSet(true)) return

        thread = Thread({
            while (running.get()) {
                try {
                    connect()
                } catch (e: Exception) {
                    LimeLog.warning("Nova SSE: Connection error: ${e.message}")
                }

                if (running.get()) {
                    listener.onConnectionLost()
                    // Reconnect after 2 seconds
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                }
            }
        }, "nova-sse").apply { isDaemon = true; start() }

        LimeLog.info("Nova SSE: Started")
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        LimeLog.info("Nova SSE: Stopped")
    }

    private fun connect() {
        // Use the web UI port (47990) for SSE, not nvhttp
        val url = "https://$baseUrl:${PolarisApiClient.WEB_UI_HTTPS_PORT}/api/polaris/events"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()

        val response: Response = client.newCall(request).execute()
        if (response.code != 200) {
            LimeLog.warning("Nova SSE: HTTP ${response.code}")
            response.close()
            return
        }

        LimeLog.info("Nova SSE: Connected to $url")
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        var eventType = ""
        val dataBuffer = StringBuilder()

        while (running.get()) {
            val line = reader.readLine() ?: break

            when {
                line.startsWith("event: ") -> {
                    eventType = line.substring(7).trim()
                }
                line.startsWith("data: ") -> {
                    dataBuffer.append(line.substring(6))
                }
                line.isEmpty() -> {
                    // End of event — dispatch
                    if (dataBuffer.isNotEmpty()) {
                        try {
                            val json = org.json.JSONObject(dataBuffer.toString())
                            when (eventType) {
                                "session" -> {
                                    listener.onSessionEvent(
                                        json.optString("event", ""),
                                        json.optString("state", ""),
                                        json.optString("message", "")
                                    )
                                }
                                "state" -> {
                                    listener.onStateUpdate(
                                        json.optString("session_state", "idle"),
                                        json.optBoolean("cage_running", false),
                                        json.optBoolean("screen_locked", false)
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            LimeLog.warning("Nova SSE: Parse error: ${e.message}")
                        }
                    }
                    eventType = ""
                    dataBuffer.clear()
                }
            }
        }

        reader.close()
        response.close()
    }
}
