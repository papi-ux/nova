package com.papi.nova.api

import com.papi.nova.LimeLog
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Session events from the selected host's authenticated, advertised endpoint. */
class PolarisEventSource(
    private val endpoint: String,
    private val listener: EventListener,
    sharedClient: OkHttpClient
) {
    interface EventListener {
        fun onSessionEvent(event: String, state: String, message: String)
        fun onStateUpdate(sessionState: String, cageRunning: Boolean, screenLocked: Boolean)
        fun onConnectionLost()
        fun onResyncNeeded() {}
        fun onLiveTuning(state: LiveTuningStatus) {}
        fun onInvalidLiveTuning() {}
    }
    private val generation = AtomicLong(0)
    private var thread: Thread? = null
    @Volatile private var activeCall: Call? = null
    private val client = sharedClient.newBuilder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    @Synchronized fun start() {
        if (thread != null) return
        val epoch = generation.incrementAndGet()
        thread = Thread({
            var delay = 1000L
            while (generation.get() == epoch) {
                try { connect(epoch); delay = 1000L }
                catch (e: Exception) { if (generation.get() == epoch) LimeLog.warning("Nova SSE: ${e.javaClass.simpleName}") }
                if (generation.get() != epoch) break
                deliver(epoch) { listener.onConnectionLost() }
                try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                delay = (delay * 2).coerceAtMost(15000L)
            }
        }, "nova-sse").apply { isDaemon = true; start() }
    }
    val isRunning: Boolean @Synchronized get() = thread != null

    @Synchronized fun stop() {
        generation.incrementAndGet()
        activeCall?.cancel() // Interrupt a blocked socket read, not just the Java thread.
        activeCall = null
        thread?.interrupt()
        thread = null
    }
    private inline fun deliver(epoch: Long, callback: () -> Unit) = synchronized(this) {
        if (generation.get() == epoch) callback()
    }
    private fun connect(epoch: Long) {
        val call = client.newCall(Request.Builder().url(endpoint).header("Accept", "text/event-stream").build())
        synchronized(this) {
            if (generation.get() != epoch) return
            activeCall = call
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return
                if (generation.get() != epoch) return
                // The server sends snapshots, not a retained replay log.
                deliver(epoch) { listener.onResyncNeeded() }
                val source = response.body?.source() ?: return
                var event = ""
                var id = ""
                var lastId = ""
                val data = StringBuilder()
                while (generation.get() == epoch && !source.exhausted()) {
                    val line = source.readUtf8LineStrict(65536)
                    if (generation.get() != epoch) break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("id:") -> id = line.substring(3).trim()
                        line.startsWith("data:") -> {
                            if (data.length + line.length > 65536) throw java.io.IOException("SSE event too large")
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                if (id.isNotEmpty() && lastId.isNotEmpty() && !consecutiveEventIds(lastId, id)) deliver(epoch) { listener.onResyncNeeded() }
                                val json = JSONObject(data.toString())
                                when (event) {
                                    "session" -> deliver(epoch) { listener.onSessionEvent(json.optString("event"), json.optString("state"), json.optString("message")) }
                                    "state" -> {
                                        deliver(epoch) {
                                            listener.onStateUpdate(json.optString("session_state", "idle"), json.optBoolean("cage_running"), json.optBoolean("screen_locked"))
                                        }
                                        val live = LiveTuningStatus.parse(json.optJSONObject("live_tuning"))
                                        deliver(epoch) {
                                            if (live != null) listener.onLiveTuning(live)
                                            else listener.onInvalidLiveTuning()
                                        }
                                    }
                                }
                                if (id.isNotEmpty()) lastId = id
                            }
                            event = ""; id = ""; data.clear()
                        }
                    }
                }
            }
        } finally { synchronized(this) { if (activeCall === call) activeCall = null } }
    }
    companion object {
        fun consecutiveEventIds(previous: String, next: String): Boolean {
            val a = previous.substringBeforeLast(':', "")
            val b = next.substringBeforeLast(':', "")
            val x = previous.substringAfterLast(':').toLongOrNull()
            val y = next.substringAfterLast(':').toLongOrNull()
            return a.isNotEmpty() && a == b && x != null && x < Long.MAX_VALUE && y == x + 1
        }
    }
}
