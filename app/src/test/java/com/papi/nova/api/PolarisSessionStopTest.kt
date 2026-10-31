package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import java.io.IOException
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

/** The real paired stop request, intercepted before any network; no legacy cancel or retry. */
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
        val broken = api { asks++; throw IOException("ambiguous transport") }
        assertFalse(broken.stopCapturedSession("session-A"))
        assertEquals(1, asks)
        asks = 0
        val redirect = api { request ->
            asks++
            response(request, 307, "{}").newBuilder().header("Location", "https://127.0.0.1:47984/cancel").build()
        }
        assertFalse(redirect.stopCapturedSession("session-A"))
        assertEquals(1, asks)
    }

    @Test fun unknownCapturedIdentityMakesNoRequest() {
        val api = api { error("unknown identity must not make a request") }
        assertFalse(api.stopCapturedSession(""))
        assertFalse(api.stopCapturedSession(" "))
        assertFalse(api.stopCapturedSession("a".repeat(2049)))
    }
}
