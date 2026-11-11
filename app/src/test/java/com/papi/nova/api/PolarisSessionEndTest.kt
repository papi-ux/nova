package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.ui.NovaHapticCue
import com.papi.nova.utils.ServerHelper
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
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
 * How a captured End comes out when its stop gets no answer, and what the Hosts pill then says
 * (#487). Only a stop with no response at all is read again; any answer is the answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisSessionEndTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Asked { var stops = 0; var reads = 0 }

    private fun api(asked: Asked, stop: (Request) -> Response, status: (Request) -> Response): PolarisApiClient {
        val api = PolarisApiClient(context, "127.0.0.1", 47984)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "POST") { asked.stops++; stop(request) } else { asked.reads++; status(request) }
        }.build()
        PolarisApiClient::class.java.getDeclaredField("perCallClientCache").apply { isAccessible = true }.set(api, client)
        return api
    }

    private fun reply(request: Request, code: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(ResponseBody.create(null, body)).build()

    private fun status(state: String, token: String = "session-A", shutdown: Boolean = false) =
        JSONObject().put("state", state).put("session_token", token).put("game_id", 7).put("shutdown_requested", shutdown).toString()

    private fun parsed(state: String, token: String = "session-A", shutdown: Boolean = false) =
        PolarisApiClient.parseSessionStatusResponse(JSONObject(status(state, token, shutdown)))

    private val noAnswer: (Request) -> Response = { throw SocketTimeoutException("timeout") }

    // What a status read says of the captured session.

    @Test fun anIdleHostOrAnotherSessionMeansTheCapturedSessionIsGone() {
        assertEquals(PolarisCapturedSession.GONE, capturedSessionAfterStop(parsed("idle", token = ""), "session-A"))
        assertEquals(PolarisCapturedSession.GONE, capturedSessionAfterStop(parsed("idle"), "session-A"))
        assertEquals(PolarisCapturedSession.GONE, capturedSessionAfterStop(parsed("streaming", token = "session-B"), "session-A"))
    }

    @Test fun theSameSessionClosingOrAliveIsStillThere() {
        assertEquals(PolarisCapturedSession.CLOSING, capturedSessionAfterStop(parsed("tearing_down"), "session-A"))
        assertEquals(PolarisCapturedSession.CLOSING, capturedSessionAfterStop(parsed("streaming", shutdown = true), "session-A"))
        assertEquals(PolarisCapturedSession.RUNNING, capturedSessionAfterStop(parsed("paused"), "session-A"))
        assertEquals(PolarisCapturedSession.RUNNING, capturedSessionAfterStop(parsed("streaming"), "session-A"))
        // A live session with no token could be this one: it is not taken as gone.
        assertEquals(PolarisCapturedSession.RUNNING, capturedSessionAfterStop(parsed("streaming", token = ""), "session-A"))
    }

    // The stop and what follows it.

    /** The RP6: no answer in time, the host still closing at the first read, then idle. */
    @Test fun aStopWithNoAnswerThatTheHostCarriesOutIsAnEnd() {
        val asked = Asked()
        val reads = ArrayDeque(listOf(status("tearing_down", shutdown = true), status("idle", token = "")))
        val api = api(asked, noAnswer) { reply(it, 200, reads.removeFirstOrNull() ?: status("idle", token = "")) }
        assertEquals(PolarisSessionEnd.ENDED, api.endCapturedSession("session-A", confirmWithinMs = 3_000))
        assertEquals("the stop is sent once", 1, asked.stops)
        assertEquals("read until it was gone", 2, asked.reads)
    }

    @Test fun aStopWithNoAnswerAndTheSessionStillClosingSaysSo() {
        val asked = Asked()
        val api = api(asked, noAnswer) { reply(it, 200, status("tearing_down", shutdown = true)) }
        assertEquals(PolarisSessionEnd.STILL_CLOSING, api.endCapturedSession("session-A", confirmWithinMs = 600))
        assertEquals(1, asked.stops)
    }

    @Test fun aStopWithNoAnswerAndTheSessionStillRunningIsNotAnEnd() {
        val asked = Asked()
        val api = api(asked, noAnswer) { reply(it, 200, status("paused")) }
        assertEquals(PolarisSessionEnd.STILL_RUNNING, api.endCapturedSession("session-A", confirmWithinMs = 600))
        assertEquals(1, asked.stops)
    }

    @Test fun aStopWithNoAnswerAndNoStatusEitherIsNoAnswer() {
        val asked = Asked()
        val api = api(asked, noAnswer) { throw IOException("no route") }
        assertEquals(PolarisSessionEnd.NO_ANSWER, api.endCapturedSession("session-A", confirmWithinMs = 600))
        assertEquals(1, asked.stops)
        assertTrue("status was asked within the time given", asked.reads >= 1)
    }

    /** Any answer is the answer: nothing is read again, whatever it said. */
    @Test fun anAnsweredStopIsTakenAsItWasAnswered() {
        val cases = listOf(200 to "{\"status\":true}", 200 to "{\"status\":false}", 200 to "not JSON", 470 to "{}", 503 to "{}")
        for ((code, body) in cases) {
            val asked = Asked()
            val api = api(asked, { reply(it, code, body) }) { error("an answered stop is not read again") }
            val expected = if (code == 200 && body == "{\"status\":true}") PolarisSessionEnd.ENDED else PolarisSessionEnd.REFUSED
            assertEquals("$code $body", expected, api.endCapturedSession("session-A", confirmWithinMs = 600))
            assertEquals(1, asked.stops)
            assertEquals("$code $body", 0, asked.reads)
        }
    }

    // What the pill then says.

    @Test fun eachOutcomeSaysWhatHappenedAndOnlyAnEndIsConfirmed() {
        val resources = context.resources
        val refusal = { end: PolarisSessionEnd -> ServerHelper.capturedEndRefusal(resources, end, "Hades") }
        assertNull(refusal(PolarisSessionEnd.ENDED))
        assertEquals(NovaHapticCue.END_CONFIRMED, ServerHelper.quitCue(refusal(PolarisSessionEnd.ENDED)))

        val didNotEnd = context.getString(R.string.hosts_end_failed_message, "Hades")
        for (end in listOf(PolarisSessionEnd.REFUSED, PolarisSessionEnd.STILL_RUNNING)) {
            assertEquals("$end", didNotEnd, refusal(end)!!.reason)
            assertEquals("$end", NovaHapticCue.REFUSED, ServerHelper.quitCue(refusal(end)))
        }

        val closing = refusal(PolarisSessionEnd.STILL_CLOSING)!!
        assertEquals(context.getString(R.string.nova_library_end_still_closing), closing.reason)
        assertTrue(closing.stillClosing)
        assertNull("still closing plays no refusal, as on the other Ends", ServerHelper.quitCue(closing))

        val unreachable = refusal(PolarisSessionEnd.NO_ANSWER)!!
        assertEquals(context.getString(R.string.nova_library_end_unreachable), unreachable.reason)
        assertFalse(unreachable.stillClosing)
        assertEquals(NovaHapticCue.REFUSED, ServerHelper.quitCue(unreachable))
    }
}
