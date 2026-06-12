package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class ClientRuntimeSnapshotTest {

    @Test
    fun profileProvenanceDefaultsToLocalWhenOptimizationIsMissing() {
        val provenance = ClientProfileProvenance.fromOptimization(null, manualOverride = false)

        assertEquals(ClientProfileSource.LOCAL_DEFAULT, provenance.source)
        assertEquals("local_default", provenance.sourceValue)
        assertFalse(provenance.manualOverride)
        assertEquals("Local defaults", provenance.displayLabel)
    }

    @Test
    fun profileProvenanceUsesPolarisCachedSourceAndVersion() {
        val optimization = JSONObject(
            "{\"source\":\"ai_cached\",\"recommendation_version\":4," +
                "\"confidence\":\"high\",\"cache_status\":\"hit\"}"
        )

        val provenance = ClientProfileProvenance.fromOptimization(optimization, manualOverride = false)

        assertEquals(ClientProfileSource.POLARIS_CACHED, provenance.source)
        assertEquals(4, provenance.version)
        assertEquals("high", provenance.confidence)
        assertEquals("hit", provenance.cacheStatus)
    }

    @Test
    fun profileProvenanceManualOverridePreservesRecommendationVersion() {
        val optimization = JSONObject("{\"source\":\"ai_cached\",\"recommendation_version\":2}")

        val provenance = ClientProfileProvenance.fromOptimization(optimization, manualOverride = true)

        assertEquals(ClientProfileSource.MANUAL_OVERRIDE, provenance.source)
        assertEquals("ai_cached", provenance.sourceValue)
        assertEquals(2, provenance.version)
    }

    @Test
    fun runtimeSnapshotSerializesStableJsonKeys() {
        val snapshot = ClientRuntimeSnapshot(
            deviceModel = "Retroid Pocket",
            androidSdk = 35,
            decoder = "c2.qti.hevc.decoder.low_latency",
            targetRefreshRateHz = 120.0,
            appliedRefreshRateHz = 120.0,
            displayMode = "refresh_rate:120.0",
            refreshRatePolicy = "exact_match_internal",
            profile = ClientProfileProvenance(
                source = ClientProfileSource.POLARIS_LIVE,
                version = 7,
                hash = "abc123",
                confidence = "high",
                cacheStatus = "miss",
                manualOverride = false
            )
        )

        val json = snapshot.toJson()

        assertEquals("Retroid Pocket", json.getString("device_model"))
        assertEquals(35, json.getInt("android_sdk"))
        assertEquals("c2.qti.hevc.decoder.low_latency", json.getString("decoder"))
        assertEquals(120.0, json.getDouble("target_refresh_rate_hz"), 0.01)
        assertEquals(120.0, json.getDouble("applied_refresh_rate_hz"), 0.01)
        assertEquals("refresh_rate:120.0", json.getString("display_mode"))
        assertEquals("exact_match_internal", json.getString("refresh_rate_policy"))
        assertEquals("polaris_live", json.getJSONObject("profile").getString("source"))
        assertEquals(7, json.getJSONObject("profile").getInt("version"))
        assertEquals("abc123", json.getJSONObject("profile").getString("hash"))
    }

    @Test
    fun fromAppliedStreamBuildsRuntimeSnapshot() {
        val provenance = ClientProfileProvenance(
            source = ClientProfileSource.HISTORY_SAFE,
            confidence = "medium",
            manualOverride = false
        )

        val snapshot = ClientRuntimeSnapshot.fromAppliedStream(
            deviceModel = "Retroid Pocket",
            androidSdk = 35,
            decoder = "c2.qti.hevc.decoder.low_latency",
            targetRefreshRateHz = 60.0,
            appliedRefreshRateHz = 120.0,
            displayMode = "1920x1080x120",
            refreshRatePolicy = "whole_multiple",
            profile = provenance
        )

        val json = snapshot.toJson()

        assertEquals("Retroid Pocket", json.getString("device_model"))
        assertEquals("whole_multiple", json.getString("refresh_rate_policy"))
        assertEquals("history_safe", json.getJSONObject("profile").getString("source"))
    }
}
