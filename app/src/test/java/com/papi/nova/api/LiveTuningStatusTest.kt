package com.papi.nova.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveTuningStatusTest {
    private fun fixtures() = JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText())
    private fun value() = fixtures().getJSONObject(0).getJSONObject("live_tuning")
    @Test fun sharedFixturesParseWithoutAiCredentials() {
        val cases = fixtures()
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val parsed = LiveTuningStatus.parse(row.getJSONObject("live_tuning"))!!
            assertEquals(row.getString("name"), parsed.state)
        }
    }
    @Test fun malformedPreferenceAndRevisionAreUnknown() {
        assertNull(LiveTuningStatus.parse(value().put("enabled", "false")))
        assertNull(LiveTuningStatus.parse(value().put("configuration_revision", "")))
        assertNull(LiveTuningStatus.parse(value().put("version", 2)))
        for (key in listOf("sequence", "session_generation", "quality_limit_kbps", "requested_bitrate_kbps", "applied_bitrate_kbps")) {
            assertNull(LiveTuningStatus.parse(value().put(key, "42")))
            assertNull(LiveTuningStatus.parse(value().put(key, -1)))
            assertNull(LiveTuningStatus.parse(value().put(key, 1.5)))
        }
        val status = PolarisApiClient.parseSessionStatusResponse(JSONObject().put("live_tuning", value().put("version", 2)))
        assertTrue(status.liveTuningPresent)
        assertNull(status.liveTuning)
    }
    @Test fun reducerRejectsDelayedResponsesAcrossRestart() {
        val reducer = LiveTuningReducer()
        val first = LiveTuningStatus.parse(value())!!
        assertNotNull(reducer.accept(first.copy(sequence = 5)))
        assertNull(reducer.accept(first.copy(sequence = 4)))
        assertNotNull(reducer.accept(first.copy(hostInstance = "restarted", sequence = 1)))
        assertNull(reducer.accept(first.copy(sequence = 100)))
    }
    @Test fun sseIdsDetectGapsAndRestart() {
        assertTrue(PolarisEventSource.consecutiveEventIds("connection:2", "connection:3"))
        assertFalse(PolarisEventSource.consecutiveEventIds("connection:2", "connection:4"))
        assertFalse(PolarisEventSource.consecutiveEventIds("connection:2", "restarted:3"))
        assertFalse(PolarisEventSource.consecutiveEventIds("connection:2", "connection:2"))
    }
    @Test fun delayedMenuDeliveryUsesTheCurrentOwnerSessionAndClearedState() {
        val client = PolarisApiClient(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val publish = PolarisApiClient::class.java.getDeclaredMethod("publishStatus", PolarisSessionStatus::class.java).apply { isAccessible = true }
        val first = PolarisApiClient.parseSessionStatusResponse(JSONObject().put("live_tuning", value()))
        publish.invoke(client, first)
        val captured = client.sessionStatusUpdates.value
        var delivered: PolarisSessionStatus? = null
        val queuedDelivery = { client.withCurrentSessionStatus { delivered = it } }
        val second = first.copy(appSessionId = "new-owner-session", sessionGeneration = 99,
            liveTuning = first.liveTuning!!.copy(sequence = first.liveTuning.sequence + 1, appSessionId = "new-owner-session", sessionGeneration = 99))
        publish.invoke(client, second)
        queuedDelivery()
        assertNotSame(captured, delivered)
        assertEquals("new-owner-session", delivered!!.appSessionId)
        assertEquals(99L, delivered!!.sessionGeneration)
        publish.invoke(client, null)
        queuedDelivery()
        assertNull(delivered)
    }
    @Test fun invalidEventRemovesConfirmedTuningWithoutRevivingLegacyState() {
        val client = PolarisApiClient(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val publish = PolarisApiClient::class.java.getDeclaredMethod("publishStatus", PolarisSessionStatus::class.java).apply { isAccessible = true }
        publish.invoke(client, PolarisApiClient.parseSessionStatusResponse(JSONObject().put("live_tuning", value())))
        client.invalidateLiveTuningEvent()
        assertTrue(client.sessionStatusUpdates.value!!.liveTuningPresent)
        assertNull(client.sessionStatusUpdates.value!!.liveTuning)
        assertEquals("Live Tuning: Unknown", com.papi.nova.ui.AutoQualityUiState.from(client.sessionStatusUpdates.value).label)
    }

    @Test fun statusReadAndMutationSerializeTheirActualHttpDispatch() {
        val client = PolarisApiClient(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val posted = java.util.concurrent.CountDownLatch(1)
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val intercepted = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods.add(request.method)
            if (request.method == "GET") {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } else posted.countDown()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, "{\"status\":true}")).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(client, intercepted)
        val status = PolarisSessionStatus(state = "streaming", ownedByClient = true, appSessionId = "owner", sessionGeneration = 1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val get = pool.submit<PolarisSessionStatus?> { client.getSessionStatus() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val save = pool.submit<Boolean> { client.setLiveTuningEnabled(true, status) }
            assertFalse("POST must wait for the in-flight GET", posted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            get.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(save.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("GET", "POST"), methods.toList())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun menuConsumerDoesNotHoldTheApiMonitorWhileWaitingForDoctorState() {
        val client = PolarisApiClient(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val publish = PolarisApiClient::class.java.getDeclaredMethod("publishStatus", PolarisSessionStatus::class.java).apply { isAccessible = true }
        val consumerEntered = java.util.concurrent.CountDownLatch(1)
        val published = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val ui = pool.submit<Boolean> {
                client.withCurrentSessionStatus {
                    consumerEntered.countDown()
                    published.await(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            assertTrue(consumerEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val io = pool.submit { publish.invoke(client, null); published.countDown() }
            assertTrue("UI consumers must release the API monitor before other locks", ui.get(5, java.util.concurrent.TimeUnit.SECONDS))
            io.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally { published.countDown(); pool.shutdownNow() }
    }

}
