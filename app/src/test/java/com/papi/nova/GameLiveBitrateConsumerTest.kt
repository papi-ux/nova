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
    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun rendererCallbackBindsLocalEpochsToItsCapturedConnectionAndHostScope() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        val connection = mock(NvConnection::class.java)
        field(game, "conn", connection); field(game, "connected", true)
        val api = PolarisApiClient(game, "127.0.0.1", 47984)
        game.novaApiClient = api
        val live = LiveTuningStatus(true, "stable", true, "supported", 30000, 20000, 20000,
            "a".repeat(64), "fixture", 1, 41, "session")
        val status = PolarisSessionStatus("streaming", streamingActive = true, appSessionId = "session",
            sessionGeneration = 41, liveTuning = live, liveTuningPresent = true)
        @Suppress("UNCHECKED_CAST")
        val updates = PolarisApiClient::class.java.getDeclaredField("mutableSessionStatus")
            .apply { isAccessible = true }.get(api) as kotlinx.coroutines.flow.MutableStateFlow<PolarisSessionStatus?>
        updates.value = status
        val boundField = Game::class.java.getDeclaredField("lastCommandCenterMedia").apply { isAccessible = true }
        Game.isStreamActive = true
        try {
            for (epoch in listOf(1L, 0L)) {
                val sample = com.papi.nova.binding.video.PerfOverlaySample(60.0, 60.0, 60.0, 1920, 1080,
                    if (epoch == 0L) "PyroWave" else "HEVC", 2, 1, 3.0, 0.0,
                    monotonicTimestampMs = 1000, sessionGeneration = epoch)
                game.onPerfSample(sample)
                shadowOf(Looper.getMainLooper()).idle()
                val bound = boundField.get(game) as com.papi.nova.ui.NovaCommandCenterMediaSample?
                assertSame(sample, com.papi.nova.ui.currentCommandCenterMedia(bound, api, connection, status))
                assertEquals(epoch, sample.sessionGeneration)
            }
            val late = com.papi.nova.binding.video.PerfOverlaySample(60.0, 60.0, 60.0, 1920, 1080,
                "HEVC", 2, 1, 3.0, 0.0, monotonicTimestampMs = 1000, sessionGeneration = 1)
            boundField.set(game, null)
            val callback = Thread { game.onPerfSample(late) }.apply { start() }
            callback.join(5000)
            assertFalse(callback.isAlive)
            field(game, "conn", mock(NvConnection::class.java))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("A queued old callback must not bind to the replacement connection", boundField.get(game))
        } finally {
            Game.isStreamActive = false
        }
    }
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
