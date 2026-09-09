package com.papi.nova.api

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisEventSourceTest {
    private open class Listener : PolarisEventSource.EventListener {
        override fun onSessionEvent(event: String, state: String, message: String) {}
        override fun onStateUpdate(sessionState: String, cageRunning: Boolean, screenLocked: Boolean) {}
        override fun onConnectionLost() {}
    }
    @Test fun stopCancelsABlockedReadWithoutDeliveringLateCallbacks() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val connected = CountDownLatch(1)
            val eof = CountDownLatch(1)
            val callbacks = AtomicInteger()
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    if (input.read() == -1) eof.countDown()
                }
            }.apply { isDaemon = true; start() }
            val source = PolarisEventSource("http://127.0.0.1:${server.localPort}/events", object : Listener() {
                override fun onResyncNeeded() { callbacks.incrementAndGet(); connected.countDown() }
                override fun onConnectionLost() { callbacks.incrementAndGet() }
            }, OkHttpClient())
            try {
                source.start()
                assertTrue(connected.await(5, TimeUnit.SECONDS))
                source.stop()
                val stoppedCount = callbacks.get()
                assertFalse(source.isRunning)
                assertTrue("stop must close the blocked socket", eof.await(5, TimeUnit.SECONDS))
                worker.join(1000)
                assertEquals(stoppedCount, callbacks.get())
            } finally { source.stop() }
        }
    }
    @Test fun redirectCannotSendThePinnedClientToAnotherEndpoint() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { target ->
            target.soTimeout = 500
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { origin ->
                origin.soTimeout = 5000
                val lost = CountDownLatch(1)
                val worker = Thread {
                    origin.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${target.localPort}/elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }.apply { isDaemon = true; start() }
                val source = PolarisEventSource("http://127.0.0.1:${origin.localPort}/events", object : Listener() {
                    override fun onConnectionLost() { lost.countDown() }
                }, OkHttpClient())
                try {
                    source.start()
                    assertTrue(lost.await(5, TimeUnit.SECONDS))
                    source.stop()
                    try { target.accept().use { fail("redirect was followed") } }
                    catch (_: SocketTimeoutException) { /* No redirected connection. */ }
                    worker.join(1000)
                } finally { source.stop() }
            }
        }
    }
    @Test fun unchangedStreamingHeartbeatReportsMissingAndInvalidTuning() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val received = CountDownLatch(3)
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val fixture = org.json.JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText()).getJSONObject(0).getJSONObject("live_tuning")
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val output = socket.getOutputStream().bufferedWriter()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                    for (live in listOf(fixture, null, org.json.JSONObject(fixture.toString()).put("version", 99))) {
                        val payload = org.json.JSONObject().put("session_state", "streaming")
                        if (live != null) payload.put("live_tuning", live)
                        output.write("event: state\ndata: $payload\n\n")
                    }
                    output.flush()
                    input.read()
                }
            }.apply { isDaemon = true; start() }
            val source = PolarisEventSource("http://127.0.0.1:${server.localPort}/events", object : Listener() {
                override fun onLiveTuning(state: LiveTuningStatus) { events.add("valid"); received.countDown() }
                override fun onInvalidLiveTuning() { events.add("invalid"); received.countDown() }
            }, OkHttpClient())
            try {
                source.start()
                assertTrue(received.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("valid", "invalid", "invalid"), events.toList())
            } finally { source.stop(); worker.join(1000) }
        }
    }

}
