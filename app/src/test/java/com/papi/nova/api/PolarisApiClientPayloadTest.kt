package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisApiClientPayloadTest {

    @Test
    fun buildClientSettingsBody_includesClientRuntimeProfile() {
        val runtime = JSONObject()
            .put("device_model", "Retroid Pocket")
            .put("profile", JSONObject().put("source", "polaris_cached"))

        val body = PolarisApiClient.buildClientSettingsBodyForTest(
            syncMode = "auto_safe",
            manualOverride = false,
            deviceCapabilities = null,
            clientRuntime = runtime,
            appliedStreamSettings = null,
            clientPresentation = null
        )

        assertEquals("auto_safe", body.getString("sync_mode"))
        assertEquals("Retroid Pocket", body.getJSONObject("client_runtime").getString("device_model"))
        assertEquals("polaris_cached", body.getJSONObject("client_runtime").getJSONObject("profile").getString("source"))
    }

    @Test
    fun buildClientSettingsBody_preservesAllOptionalContracts() {
        val capabilities = JSONObject().put("model", "Retroid Pocket")
        val runtime = JSONObject().put("device_model", "Retroid Pocket")
        val applied = JSONObject().put("target_bitrate_kbps", 24000)
        val presentation = JSONObject().put("status", "synced")

        val body = PolarisApiClient.buildClientSettingsBodyForTest(
            syncMode = "auto_safe",
            manualOverride = true,
            deviceCapabilities = capabilities,
            clientRuntime = runtime,
            appliedStreamSettings = applied,
            clientPresentation = presentation
        )

        assertEquals(true, body.getBoolean("manual_override"))
        assertEquals("Retroid Pocket", body.getJSONObject("device_capabilities").getString("model"))
        assertEquals(24000, body.getJSONObject("applied_stream_settings").getInt("target_bitrate_kbps"))
        assertEquals("synced", body.getJSONObject("client_presentation").getString("status"))
    }
}
