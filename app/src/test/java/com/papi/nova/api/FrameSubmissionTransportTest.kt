package com.papi.nova.api

import com.papi.nova.binding.video.FrameSubmissionBinding
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.nvstream.jni.NativeFrameSubmissionSnapshot
import com.papi.nova.runtime.DoctorTelemetryUploadGate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FrameSubmissionTransportTest {
    private val snapshot = NativeFrameSubmissionSnapshot("1234567890abcdef1234567890abcdef", 7, 1, 42, 10, 10000, 1000, 999)
    private val launch = com.papi.nova.nvstream.FrameSubmissionLaunchIdentity("abcdef0123456789abcdef0123456789", "session")
    private val sample = PerfOverlaySample(60.0, 59.0, 58.0, 1920, 1080, "HEVC", 2, 1, 3.0, 0.0,
        monotonicTimestampMs = 1000, framesExpected = 100, framesReceived = 90, frameSubmission = snapshot)
    private val status = PolarisSessionStatus("streaming", streamingActive = true, ownedByClient = true,
        clientRole = "owner", appSessionId = "session", appSessionIdPresent = true, sessionGeneration = 55,
        sessionToken = "session", ownerUniqueId = "owner", frameSubmissionLaunchNonce = launch.nonce, frameSubmissionScopeTyped = true)

    private class Fixture(capability: Any? = true) {
        val requests = mutableListOf<JSONObject>()
        val api = PolarisApiClient(RuntimeEnvironment.getApplication(), "frame-submission.invalid")
        init {
            // No sockets: intercept the real production request immediately before transport.
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                requests += JSONObject(buffer.readUtf8())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"status\":true}".toResponseBody()).build()
            }.build()
            PolarisApiClient::class.java.getDeclaredField("perCallClientCache").apply { isAccessible = true }.set(api, http)
            val features = JSONObject()
            if (capability != null) features.put("live_media_frame_submission_v1", capability)
            PolarisApiClient::class.java.getDeclaredField("latestCapabilities").apply { isAccessible = true }
                .set(api, PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features", features)))
        }
        fun publish(status: PolarisSessionStatus?) {
            PolarisApiClient::class.java.getDeclaredMethod("publishStatus", PolarisSessionStatus::class.java)
                .apply { isAccessible = true }.invoke(api, status)
        }
    }

    private suspend fun send(f: Fixture, observation: () -> com.papi.nova.binding.video.FrameSubmissionReport?) =
        f.api.sendLiveMediaTelemetry(sample, "session", 55, 60.0, 120.0, 20000, "private", false, observation)

    @Test fun actualHttpBodyContainsOnlyTheValidatedNativePopulation() = runBlocking {
        val f = Fixture()
        f.publish(status)
        val connection = Any()
        val bound = FrameSubmissionBinding.capture(snapshot, f.api, connection, f.api.frameSubmissionStatus(), 7, true, true, launch)!!
        assertTrue(send(f) { bound.current(f.api, connection, f.api.frameSubmissionStatus(), 7, true, true, "session", 55, 1001) })
        val body = f.requests.single()
        val wire = body.getJSONObject("sample")
        assertEquals("session", body.getString("app_session_id"))
        assertEquals(55, body.getLong("session_generation"))
        assertEquals(100, wire.getLong("frames_expected"))
        assertEquals(10, wire.getJSONObject("frame_submission").getLong("decode_units_submitted_total"))
    }

    @Test fun absentFalseAndMalformedHostCapabilitiesNeverEvenReadTheOptionalProvider() = runBlocking {
        for (capability in listOf(null, false, "true", 1, JSONObject.NULL)) {
            val f = Fixture(capability)
            var invoked = false
            assertTrue(send(f) { invoked = true; com.papi.nova.binding.video.FrameSubmissionReport(snapshot, launch.nonce) })
            assertFalse(invoked)
            assertFalse(f.requests.single().getJSONObject("sample").has("frame_submission"))
        }
    }

    @Test fun realQueuedCoroutineAndLateCompletionCannotUseOrReleaseANewerLease() = runBlocking {
        val f = Fixture()
        f.publish(status)
        val connection = Any()
        val bound = FrameSubmissionBinding.capture(snapshot, f.api, connection, f.api.frameSubmissionStatus(), 7, true, true, launch)!!
        val gate = DoctorTelemetryUploadGate()
        val old = gate.tryAcquire()!!
        var released = true
        val queued = async(start = CoroutineStart.LAZY) {
            try {
                send(f) { bound.current(f.api, connection, f.api.frameSubmissionStatus(), 7,
                    true, gate.isCurrent(old), "session", 55, 1001) }
            } finally { released = gate.release(old) }
        }
        gate.invalidate()
        val fresh = gate.tryAcquire()!!
        assertTrue(queued.await())
        assertFalse(released)
        assertTrue(gate.isCurrent(fresh))
        assertFalse(f.requests.single().getJSONObject("sample").has("frame_submission"))
        assertTrue(gate.release(fresh))
    }

    @Test fun actualStatusPublisherRetiresAnOldScopeEvenWhenItReturnsBeforeTheCoroutineRuns() = runBlocking {
        val f = Fixture()
        f.publish(status)
        val connection = Any()
        val bound = FrameSubmissionBinding.capture(snapshot, f.api, connection, f.api.frameSubmissionStatus(), 7, true, true, launch)!!
        val queued = async(start = CoroutineStart.LAZY) {
            send(f) { bound.current(f.api, connection, f.api.frameSubmissionStatus(), 7, true, true, "session", 55, 1001) }
        }
        f.publish(status.copy(sessionGeneration = 56))
        f.publish(status)
        assertTrue(queued.await())
        assertFalse(f.requests.single().getJSONObject("sample").has("frame_submission"))
        assertEquals(100, f.requests.single().getJSONObject("sample").getLong("frames_expected"))
    }
}
