package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The paired stop request, with intercepted contracts and a real loopback retry-layer regression. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisSessionStopTest {
    private fun api(reply: (Request) -> Response): PolarisApiClient {
        val api = PolarisApiClient(ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val client = OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build()
        PolarisApiClient::class.java.getDeclaredField("perCallClientCache").apply { isAccessible = true }.set(api, client)
        return api
    }
    private fun response(request: Request, code: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(ResponseBody.create(null, body)).build()

    @Test fun replacementAfterTheLastReadGetsExactlyOneCapturedTokenPostAndCannotStopB() {
        val tokens = ArrayList<String>()
        val activeToken = "session-B" // replacement after the UI's final observation of A
        var stopped = false
        val api = api { request ->
            assertEquals("POST", request.method)
            assertEquals("/polaris/v1/session/stop", request.url.encodedPath)
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals(setOf("session_token"), body.keys().asSequence().toSet())
            val token = body.getString("session_token")
            tokens += token
            stopped = token == activeToken
            response(request, if (stopped) 200 else 470, "{\"status\":$stopped}")
        }
        assertFalse(api.stopCapturedSession("session-A"))
        assertFalse(stopped)
        assertEquals(listOf("session-A"), tokens)
    }

    @Test fun onlyAnExplicitSuccessfulResponseIsAccepted() {
        val cases = listOf(200 to "{\"status\":true}", 200 to "{\"status\":false}",
            200 to "{\"status\":\"true\"}", 200 to "{}", 200 to "not JSON",
            401 to "{}", 403 to "{}", 404 to "{}", 409 to "{}", 470 to "{}", 503 to "{}")
        for ((code, body) in cases) {
            var asks = 0
            val api = api { request -> asks++; response(request, code, body) }
            assertEquals("$code $body", code == 200 && body == "{\"status\":true}", api.stopCapturedSession("session-A"))
            assertEquals("no fallback or replay", 1, asks)
        }
    }

    @Test fun ambiguousTransportAndRedirectsAreNeverReplayed() {
        var asks = 0
        // Only the stop is counted: since #487 a stop with no answer is followed by status reads.
        val broken = api { request -> if (request.method == "POST") asks++; throw IOException("ambiguous transport") }
        assertFalse(broken.stopCapturedSession("session-A", confirmWithinMs = 300))
        assertEquals(1, asks)
        asks = 0
        val redirect = api { request ->
            asks++
            response(request, 307, "{}").newBuilder().header("Location", "https://127.0.0.1:47984/cancel").build()
        }
        assertFalse(redirect.stopCapturedSession("session-A"))
        assertEquals(1, asks)
    }

    @Test fun retryAfterZeroCannotReplayTheStopThroughOkHttpFollowUps() {
        // A real loopback exchange traverses RetryAndFollowUpInterceptor. Application
        // interceptors return before that layer and cannot prove this one-POST contract.
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val requests = CopyOnWriteArrayList<Pair<String, String>>()
            val serverFailure = AtomicReference<Throwable>()
            val worker = Thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                            val requestLine = checkNotNull(input.readLine())
                            val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }
                                .map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }.toMap()
                            val length = checkNotNull(headers["content-length"]).toInt()
                            check(length in 1..4096)
                            val body = CharArray(length)
                            var read = 0
                            while (read < length) {
                                val count = input.read(body, read, length - read)
                                check(count > 0)
                                read += count
                            }
                            requests += requestLine to JSONObject(String(body)).getString("session_token")
                            val first = requests.size == 1
                            val responseBody = if (first) "{\"status\":false}" else "{\"status\":true}"
                            val status = if (first) "503 Service Unavailable" else "200 OK"
                            val output = socket.getOutputStream()
                            output.write(("HTTP/1.1 $status\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}\r\n" +
                                "Retry-After: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            output.write(responseBody.toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                    }
                } catch (error: SocketException) {
                    if (!server.isClosed) serverFailure.set(error)
                } catch (error: Throwable) {
                    serverFailure.set(error)
                }
            }.apply { isDaemon = true; start() }
            val api = PolarisApiClient(ApplicationProvider.getApplicationContext(), "127.0.0.1", server.localPort)
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            PolarisApiClient::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
                .set(api, "http://127.0.0.1:${server.localPort}/polaris/v1")
            PolarisApiClient::class.java.getDeclaredField("perCallClientCache").apply { isAccessible = true }.set(api, client)
            val stopped = try {
                api.stopCapturedSession("session-A")
            } finally {
                server.close()
                worker.join(5000)
            }
            assertFalse("the synthetic server must settle", worker.isAlive)
            assertNull("the server must deliver the intended HTTP responses", serverFailure.get())
            println("Stop network requests: $requests; accepted=$stopped")
            assertEquals("no HTTP replay, token substitution or legacy fallback", listOf(
                "POST /polaris/v1/session/stop HTTP/1.1" to "session-A"), requests.toList())
            assertFalse("the first 503 remains a failed stop despite a success being available next", stopped)
        }
    }

    @Test fun unknownCapturedIdentityMakesNoRequest() {
        val api = api { error("unknown identity must not make a request") }
        assertFalse(api.stopCapturedSession(""))
        assertFalse(api.stopCapturedSession(" "))
        assertFalse(api.stopCapturedSession("a".repeat(2049)))
    }
}
