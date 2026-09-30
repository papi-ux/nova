package com.papi.nova.manager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaPolarisSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class])
class PolarisKeepInStepRequestBoundaryTest {
    @Test fun syncedProfileAndPyrowaveRequestKeepTheirSeparateBitratesAndLaunchLock() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertTrue(PreferenceConfiguration.applyPolarisStreamingProfile(context, "1920x1080x60", 28000))
        val saved = PreferenceConfiguration.readPreferences(context)
        val requests = mutableListOf<Request>()
        var syncedBitrate = 0
        val api = PolarisApiClient(context, "127.0.0.1", 47984)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val json = when {
                request.url.encodedPath.endsWith("/session/status") -> "{\"state\":\"idle\",\"streaming_active\":false}"
                request.url.encodedPath.endsWith("/client-settings") -> {
                    val body = Buffer(); request.body!!.writeTo(body)
                    syncedBitrate = JSONObject(body.readUtf8()).getInt("target_bitrate_kbps")
                    "{\"desired\":{\"target_bitrate_kbps\":$syncedBitrate},\"effective\":{\"target_bitrate_kbps\":$syncedBitrate}}"
                }
                request.url.encodedPath.endsWith("/optimize") -> "{}"
                else -> error("unexpected request ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(null, json)).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, http)
        val engine = NovaPolarisSyncEngine(context, api, "new-host", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined)
        engine.sendNova()
        assertEquals(saved.bitrate, syncedBitrate)
        assertEquals(28000, syncedBitrate)
        val locked = NovaTierLaunchPolicy.bitrateLocked(PreferenceConfiguration.FormatOption.FORCE_PYROWAVE, false, false)
        assertNotNull(api.getOptimization("handheld", "game", bitrateKbps = 220000, bitrateLocked = locked,
            manualBitrateMaximumKbps = 300000))
        val launch = requests.single { it.url.encodedPath.endsWith("/optimize") }
        assertEquals("220000", launch.url.queryParameter("bitrate_kbps"))
        assertEquals("1", launch.url.queryParameter("bitrate_locked"))
        assertEquals(saved.bitrate, PreferenceConfiguration.readPreferences(context).bitrate)
        engine.close()
    }
}
