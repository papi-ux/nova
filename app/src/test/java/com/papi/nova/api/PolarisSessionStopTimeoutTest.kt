package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stop the host answers only after Nova has stopped waiting (#487). On the RP6 the host's private
 * app stop took 10.26 s; Nova's read timeout is 10 s, so it showed "Could not end the session" with
 * the refusal haptic 0.44 s before the host logged the session idle. The host answers no paired
 * request while it ends a session (Polaris #644), which is what the one-at-a-time server here does:
 * a status read sent during the stop waits until the stop is over.
 *
 * The client's read timeout is shortened to [READ_TIMEOUT_MS] and the stop held [HOLD_MS], so the
 * stop is held past the timeout as on the device, in a fraction of the time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisSessionStopTimeoutTest {
    private class Host(private val status: () -> String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable>()
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        // One connection at a time: what arrives during the stop waits for it, as on the host.
        private val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use(::serve)
            } catch (error: SocketException) {
                if (!server.isClosed) failure.set(error)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }

        private fun serve(socket: Socket) {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val line = input.readLine() ?: return
            val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }
                .map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }.toMap()
            val body = headers["content-length"]?.toInt()?.let { input.readBody(it) }.orEmpty()
            val token = if (body.isEmpty()) "" else " " + JSONObject(body).optString("session_token")
            requests += line.substringBeforeLast(" HTTP/") + token
            val reply = if (line.startsWith("POST /polaris/v1/session/stop")) {
                // The host ends the session before it answers, longer than Nova waits.
                Thread.sleep(HOLD_MS)
                "{\"status\":true}"
            } else {
                status()
            }
            // The stop's answer goes to a socket Nova has already closed; that is the case under test.
            runCatching {
                val output = socket.getOutputStream()
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${reply.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                output.write(reply.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }

        private fun BufferedReader.readBody(length: Int): String {
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val count = read(chars, read, length - read)
                if (count <= 0) break
                read += count
            }
            return String(chars, 0, read)
        }

        fun client(): PolarisApiClient {
            val api = PolarisApiClient(ApplicationProvider.getApplicationContext(), "127.0.0.1", port)
            PolarisApiClient::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
                .set(api, "http://127.0.0.1:$port/polaris/v1")
            val client = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
            PolarisApiClient::class.java.getDeclaredField("perCallClientCache").apply { isAccessible = true }.set(api, client)
            return api
        }

        override fun close() {
            server.close()
            worker.join(5000)
        }
    }

    private fun stops(host: Host) = host.requests.filter { it.startsWith("POST /polaris/v1/session/stop") }

    /** The RP6: the stop outlasts the timeout, then the host is idle. That End succeeded. */
    @Test fun aStopAnsweredAfterTheTimeoutThatEndedTheSessionIsAnEnd() {
        val host = Host { "{\"state\":\"idle\"}" }
        val ended = host.use { it.client().stopCapturedSession("session-A") }
        assertNull("the synthetic host must serve what it was asked", host.failure.get())
        assertTrue("the host ended the session, so the End succeeded; requests: ${host.requests}", ended)
        assertEquals("the stop is sent once and never replayed", listOf("POST /polaris/v1/session/stop session-A"), stops(host))
        assertTrue("what happened is read from the host, after the stop", host.requests.drop(1).any { it.startsWith("GET /polaris/v1/session/status") })
    }

    /** The stop outlasts the timeout and the session is still there: that End did not happen. */
    @Test fun aStopAnsweredAfterTheTimeoutWithTheSessionStillThereIsNotAnEnd() {
        val host = Host {
            "{\"state\":\"paused\",\"session_token\":\"session-A\",\"game_id\":7,\"shutdown_requested\":false}"
        }
        val ended = host.use { it.client().stopCapturedSession("session-A") }
        assertNull(host.failure.get())
        assertFalse("the captured session is still running; requests: ${host.requests}", ended)
        assertEquals(listOf("POST /polaris/v1/session/stop session-A"), stops(host))
    }

    private companion object {
        const val READ_TIMEOUT_MS = 300L
        const val HOLD_MS = 1200L
    }
}
