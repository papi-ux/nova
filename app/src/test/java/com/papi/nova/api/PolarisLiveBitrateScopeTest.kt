package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisLiveBitrateScopeTest {
    private fun status(generation: Int = 7) = JSONObject().put("state","streaming").put("streaming_active",true)
        .put("app_session_id","session-a").put("session_generation",generation).put("client_role","owner")
        .put("owned_by_client",true).put("controls",JSONObject().put("host_tuning_allowed",true))
    private fun client(handler: (Request) -> Response): PolarisApiClient {
        val api = PolarisApiClient(ApplicationProvider.getApplicationContext(),"127.0.0.1",47984)
        val client = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible=true }.set(api,client)
        return api
    }
    private fun reply(request: Request, json: String, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(ResponseBody.create(null,json)).build()

    @Test fun replacementBetweenControllerObservationAndApiReadNeverPosts() {
        val calls = mutableListOf<String>()
        val api = client { calls += it.method;reply(it,status(8).toString()) }
        assertFalse(api.setBitrate(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(listOf("GET"),calls)
    }
    @Test fun writeCarriesObservedScopeAndIsNeverReplayedAfterFailure() {
        var posts=0;var body: JSONObject?=null
        val api = client { request ->
            if (request.method == "GET") reply(request,status().toString()) else {
                posts++;val buffer=Buffer();request.body!!.writeTo(buffer);body=JSONObject(buffer.readUtf8())
                reply(request,"{}",503)
            }
        }
        assertFalse(api.setBitrate(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(1,posts)
        assertEquals("session-a",body!!.getString("app_session_id"))
        assertEquals(7,body!!.getInt("session_generation"))
        assertEquals(40000,body!!.getInt("bitrate_kbps"))
    }
    @Test fun successRequiresFreshOwnerAndEnforcesEndpointMinimum() {
        var posts=0
        val api=client { request -> if (request.method=="GET") reply(request,status().toString()) else {
            posts++;reply(request,"{\"status\":true,\"bitrate_kbps\":40000}")
        } }
        assertFalse(api.setBitrate(500,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(0,posts)
        assertTrue(api.setBitrate(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(1,posts)
    }
    @Test fun serverCapIsReturnedToControllerAndFalseReceiptsNeverSucceed() {
        val api=client { request -> if (request.method=="GET") reply(request,status().toString()) else
            reply(request,"{\"status\":true,\"bitrate_kbps\":150000}") }
        var accepted=0
        assertTrue(api.setBitrate(180000,PolarisApiClient.parseSessionStatusResponse(status())) { accepted=it })
        assertEquals(150000,accepted)
        val rejected=client { request -> if (request.method=="GET") reply(request,status().toString()) else
            reply(request,"{\"status\":false,\"bitrate_kbps\":180000}") }
        assertFalse(rejected.setBitrate(180000,PolarisApiClient.parseSessionStatusResponse(status())))
    }

}
