package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoctorAiPublicationTest {
    private fun status(finding: String = "a", sequence: Int = 2): String = """{
      "state":"streaming","streaming_active":true,"shutdown_requested":false,"owned_by_client":true,
      "client_role":"owner","viewer_count":0,"app_session_id":"session","session_generation":7,
      "controls":{"host_tuning_allowed":true},"doctor":{"result_id":"result",
        "ai_explanation_request":{"schema":1,"enabled":true,"permitted":true,"available":true,"blocked_reason":"","source_result_id":"result","finding_fingerprint":"${finding.repeat(64)}","controller_revision":9}},
      "live_tuning":{"version":1,"enabled":true,"supported":true,"scope":"host","state":"stable","reason":"fixture",
        "quality_limit_kbps":20000,"requested_bitrate_kbps":20000,"applied_bitrate_kbps":20000,"configuration_revision":"${"c".repeat(64)}",
        "host_instance":"host-fixture","sequence":$sequence,"session_generation":7,"app_session_id":"session"}
    }"""
    private fun client(read: () -> String): PolarisApiClient {
        val api = PolarisApiClient(ApplicationProvider.getApplicationContext(), "fixture.invalid")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            val body = when (r.url.encodedPath) {
                "/polaris/v1/capabilities" -> """{"features":{"doctor_ai_explanation_v1":true}}"""
                "/polaris/v1/session/status" -> read()
                else -> "{}"
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, http)
        api.getCapabilities()
        return api
    }
    @Test fun rejectedLiveTuningPublicationStillRetiresAChangedAiFindingWithoutPublishingIt() {
        var next = status()
        val api = client { next }
        val seen = mutableListOf<DoctorAiScope?>()
        val remove = api.doctorAiReadings.listen { seen += it.scope }
        try {
            val first = api.getSessionStatus()!!
            assertNotNull(api.doctorAiReadings.current.scope)
            next = status("b", 1) // Reducer must reject this status, but AI observed a different finding.
            assertSame(first, api.getSessionStatus())
            assertNull(api.doctorAiReadings.current.scope)
            assertEquals(0, api.doctorAiReadings.current.observedAtMs)
            next = status("a", 3)
            api.getSessionStatus()
            assertNotNull(api.doctorAiReadings.current.scope)
            assertTrue(seen.drop(1).contains(null))
            assertEquals("result", api.sessionStatusUpdates.value?.doctor?.resultId)
        } finally { remove() }
    }
    @Test fun rejectedPublicationCannotHideMissingChangedScopeControllerOrDisabledDescriptor() {
        var next = status()
        val api = client { next }
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("app_session_id", "other") }, { it.put("session_generation", 8) },
            { it.getJSONObject("doctor").remove("ai_explanation_request") },
            { it.getJSONObject("doctor").getJSONObject("ai_explanation_request").put("controller_revision", 10) },
            { it.getJSONObject("doctor").getJSONObject("ai_explanation_request").put("enabled", false).put("available", false) },
        )
        for ((index, mutate) in mutations.withIndex()) {
            next = status(sequence = index + 2)
            val first = api.getSessionStatus()
            assertNotNull(api.doctorAiReadings.current.scope)
            next = JSONObject(status(sequence = 1)).also(mutate).toString()
            assertSame(first, api.getSessionStatus())
            assertNull(api.doctorAiReadings.current.scope)
            assertEquals(0, api.doctorAiReadings.current.observedAtMs)
        }
    }
    @Test fun resettingTheSameApiTlsCacheRetiresOptionalWorkImmediately() {
        val api = client { status() }; val first = api.getSessionStatus()
        assertNotNull(api.doctorAiReadings.current.scope)
        PolarisApiClient::class.java.getDeclaredMethod("resetCallClient").apply { isAccessible = true }.invoke(api)
        assertNull(api.doctorAiReadings.current.scope)
        assertSame(first, api.sessionStatusUpdates.value)
    }
    @Test fun hostManualOperationsRetireBeforeDispatchButAutomaticReportsDoNot() {
        var sequence = 0
        val api = client { status(sequence = ++sequence) }
        val operations: List<Pair<String, () -> Unit>> = listOf(
            "display/defaults" to { api.updateClientSettings(displayMode = "1920x1080x60") },
            "profile clear" to { api.clearOptimizerProfile("fixture-device", "fixture-game") },
            "all profiles clear" to { api.clearOptimizerProfiles() },
            "Doctor intent refused before dispatch" to { api.runDoctorAction("lower_bitrate", "session") },
            "bitrate" to { api.setBitrateResult(20000, api.sessionStatusUpdates.value) },
            "Live Tuning" to { api.setLiveTuningEnabled(false, api.sessionStatusUpdates.value) },
            "MangoHud" to { api.setMangoHud("fixture-game", true) },
            "Steam launch mode" to { api.setSteamLaunchMode("fixture-game", "auto") },
            "cursor" to { api.setCursorVisibility(true) },
            "manual profile sync" to { api.reportClientSettings(manualOverride = true) },
            "launch" to { api.launchGame("fixture-game") },
            "stop" to { api.stopCapturedSession("session") },
            "select Space" to { api.selectSpace("space", "previous") },
            "host sleep" to { api.sleepHost() },
            "host Steam" to { api.controlDesktopSteam("open", "revision") },
            "unlock" to { api.unlockScreen() },
        )
        for ((name, operation) in operations) {
            api.getSessionStatus(); assertNotNull(name, api.doctorAiReadings.current.scope)
            runCatching(operation)
            assertNull(name, api.doctorAiReadings.current.scope)
        }
        api.getSessionStatus(); val before = api.doctorAiReadings.current
        api.reportClientSettings()
        assertEquals(before, api.doctorAiReadings.current)
    }
}
