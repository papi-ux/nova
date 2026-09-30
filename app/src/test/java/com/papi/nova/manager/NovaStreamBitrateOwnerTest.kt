package com.papi.nova.manager

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.*
import com.papi.nova.preferences.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real controller and API dispatch; the interceptor records every HTTP method and body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamBitrateOwnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun stop() { scope.cancel() }
    private var connection: Any? = Any()
    private var currentApi: PolarisApiClient? = null
    private var inputs = NovaLiveStreamInputs(1920, 1080, 120, NovaDistance.HAND)
    private var streamActive = true
    private val calls = mutableListOf<String>()
    private val posts = mutableListOf<Int>()
    private val caps = PolarisCapabilities("Polaris", "fixture", PolarisCapabilities.Features(
        bitrateUnitsV1 = true, pyrowaveAdviceV1 = true, manualBitrateMaxKbps = 500000),
        PolarisCapabilities.CaptureInfo())
    private fun json(codec: String = "hevc", adviceWidth: Int = 1920, generation: Int = 7): JSONObject =
        JSONObject().put("state", "streaming").put("streaming_active", true)
            .put("app_session_id", "session-a").put("session_generation", generation)
            .put("client_role", "owner").put("owned_by_client", true)
            .put("controls", JSONObject().put("host_tuning_allowed", true))
            .put("encoder", JSONObject().put("codec", codec))
            .put("live_tuning", JSONObject().put("supported", true).put("enabled", false)
                .put("requested_bitrate_kbps", 25987).put("applied_bitrate_kbps", 25987)
                .put("sequence", 1).put("host_instance", "host-a").put("configuration_revision", "a".repeat(64))
                .put("version", 1).put("scope", "host").put("state", "stable").put("quality_limit_kbps", 300000)
                .put("session_generation", generation).put("app_session_id", "session-a"))
            .put("bitrate_units", JSONObject().put("version", 1).put("formula", "stream_bitrate_v1")
                .put("requested_kbps", 30000).put("split_kbps", 30000).put("warp_factor", 1)
                .put("cap_kbps", JSONObject.NULL).put("cap_source", JSONObject.NULL)
                .put("encoder_kbps", 25987).put("live_encoder_kbps", 25987)
                .put("audio_kbps", 512).put("fec_percentage", 10))
            .put("pyrowave_bitrate", JSONObject().put("version", 1).put("width", adviceWidth)
                .put("height", 1080).put("fps", 120).put("raise_goal_kbps", 210000)
                .put("cap_kbps", 300000).put("raise_goal_limited_by", "advice")
                .put("assumes", JSONObject().put("audio_kbps", 512).put("fec_percentage", 10)))
    private fun parsed(value: JSONObject) = PolarisApiClient.parseSessionStatusResponse(value)
    private fun api(read: () -> JSONObject): PolarisApiClient {
        val api = PolarisApiClient(ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.method
            val body = if (request.method == "GET") read().toString() else {
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val target = JSONObject(buffer.readUtf8()).getInt("bitrate_kbps")
                posts += target
                JSONObject().put("status", true).put("bitrate_kbps", target).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(null, body)).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, http)
        return api
    }
    private fun owner() = NovaStreamBitrateOwner(scope, { currentApi }, { connection },
        { streamActive }, { inputs })

    @Test fun malformedViewerIsReadOnlyButCannotPoisonTheValidOwnerIdentity() {
        currentApi = api { json() }; val owner = owner()
        owner.observe(currentApi, connection, parsed(json()), caps)
        assertTrue(owner.state.value.rate.canChange)
        val malformed=json().put("client_role","viewer").put("owned_by_client",false)
            .put("app_session_id","").put("session_generation",0)
        owner.observe(currentApi, connection, parsed(malformed), caps)
        assertFalse(owner.state.value.rate.canChange)
        owner.observe(currentApi, connection, parsed(json()), caps)
        assertTrue("the genuine owner can recover after malformed telemetry",owner.state.value.rate.canChange)
        assertTrue(posts.isEmpty())
    }

    @Test fun lateObservationFromPreviousApiCannotRetireTheNewStream() {
        val previous = api { json() }; val previousConnection = connection
        currentApi = api { json() }; connection = Any()
        val owner = owner(); owner.observe(currentApi, connection, parsed(json()), caps)
        val token = owner.state.value.token
        assertTrue(owner.state.value.rate.canChange)
        owner.observe(previous, previousConnection, null, caps)
        assertEquals(token, owner.state.value.token)
        assertTrue(owner.state.value.rate.canChange)
    }

    @Test fun changedStreamInputsRejectThePreviouslyRenderedCallbackBeforeAnyRead() = runBlocking {
        currentApi = api { json() }; val owner = owner()
        owner.observe(currentApi, connection, parsed(json()), caps)
        val token = owner.state.value.token
        inputs = inputs.copy(fps = 60)
        assertEquals(NovaBitrateChange.UNAVAILABLE, owner.change(token, { true }, kbps = 40000))
        assertTrue(calls.isEmpty()); assertTrue(posts.isEmpty())
    }

    @Test fun mismatchedPyrowaveAdviceNeverBecomesAnActionableRecommendation() = runBlocking {
        currentApi = api { json("pyrowave", 1280) }; val owner = owner()
        owner.observe(currentApi, connection, parsed(json("pyrowave", 1280)), caps)
        assertTrue(owner.state.value.rate.canChange)
        assertNull(owner.state.value.rate.recommendedKbps)
        assertEquals(NovaBitrateChange.UNAVAILABLE, owner.change(owner.state.value.token, { true }))
        assertTrue(posts.isEmpty())
    }

    @Test fun closingTheMenuDuringTheApisSecondReadProducesNoPost() = runBlocking {
        var showing = true
        var reads = 0
        currentApi = api { if (++reads == 2) showing = false; json() }
        val owner = owner(); owner.observe(currentApi, connection, parsed(json()), caps)
        owner.change(owner.state.value.token, { showing }, kbps = 40000)
        assertEquals(listOf("GET", "GET"), calls)
        assertTrue(posts.isEmpty()); assertFalse(owner.state.value.rate.busy)
    }

    @Test fun aCurrentManualWriteUsesOnlyTheScopedEndpointAndKeepsReceivedUnknown() = runBlocking {
        currentApi = api { json() }; val owner = owner()
        owner.observe(currentApi, connection, parsed(json()), caps)
        assertEquals(NovaBitrateChange.APPLIED, owner.change(owner.state.value.token, { true }, kbps = 40000))
        assertEquals(listOf(34988), posts)
        assertEquals(40000, owner.state.value.rate.requestedKbps)
        assertNull(owner.state.value.rate.receivedKbps)
    }
}
