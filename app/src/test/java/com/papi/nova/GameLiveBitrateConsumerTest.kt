package com.papi.nova

import android.os.Looper
import com.papi.nova.api.*
import com.papi.nova.binding.input.capture.InputCaptureProvider
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The factory used by NovaQuickMenu, through actual Game inputs and the real scoped API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
class GameLiveBitrateConsumerTest {
    private fun field(game: Game, key: String, value: Any?) = Game::class.java.getDeclaredField(key)
        .apply { isAccessible = true }.set(game, value)
    @Test fun retainedMenuActionCannotFollowAReplacedConnectionAndUsesActualStreamInputs() {
        val activity = Robolectric.buildActivity(Game::class.java)
        val game = activity.get()
        field(game, "inputCaptureProvider", mock(InputCaptureProvider::class.java))
        field(game, "conn", mock(NvConnection::class.java)); field(game, "connected", true)
        field(game, "displayWidth", 1920); field(game, "displayHeight", 1080)
        field(game, "configuredStreamFrameRateFps", 120f)
        Game.isStreamActive = true
        activity.visible()
        val json = JSONObject().put("state", "streaming").put("streaming_active", true)
            .put("app_session_id", "a").put("session_generation", 7).put("client_role", "owner")
            .put("owned_by_client", true).put("controls", JSONObject().put("host_tuning_allowed", true))
            .put("encoder", JSONObject().put("codec", "hevc"))
            .put("live_tuning", JSONObject().put("version", 1).put("scope", "host").put("supported", true)
                .put("enabled", false).put("state", "stable").put("quality_limit_kbps", 300000)
                .put("requested_bitrate_kbps", 25987).put("applied_bitrate_kbps", 25987).put("sequence", 1)
                .put("host_instance", "fixture").put("configuration_revision", "a".repeat(64))
                .put("app_session_id", "a").put("session_generation", 7))
        val written = CountDownLatch(1)
        val posts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val api = PolarisApiClient(game, "127.0.0.1", 47984)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.method == "GET") json.toString() else {
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val target = JSONObject(buffer.readUtf8()).getInt("bitrate_kbps")
                posts += target; written.countDown()
                JSONObject().put("status", true).put("bitrate_kbps", target).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(null, body)).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, http)
        game.novaApiClient = api
        val caps = PolarisCapabilities("Polaris", "fixture", PolarisCapabilities.Features(pyrowaveAdviceV1 = true), PolarisCapabilities.CaptureInfo())
        game.novaLiveBitrate.observe(api, game.conn, PolarisApiClient.parseSessionStatusResponse(json), caps)
        assertTrue(game.novaLiveBitrate.state.value.numbers.contains("120 fps"))
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(game)
        prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 201124).commit()
        val saved = HashMap(prefs.all)
        val oldConnection = game.conn
        val action = game.novaBitrateAction { true }
        val token = game.novaLiveBitrate.state.value.token
        field(game, "conn", mock(NvConnection::class.java))
        action(token, 1, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(posts.isEmpty())
        field(game, "conn", oldConnection)
        action(token, 1, null)
        assertTrue("the actual Game runtime dispatched the current action", written.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(30987), posts)
        assertEquals(saved, prefs.all)
        game.novaLiveBitrate.retire(); Game.isStreamActive = false
    }
}
