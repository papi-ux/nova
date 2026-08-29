package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisApiClientParsingTest {

    @Test
    fun doctorActionBodyCarriesExactAppGenerationAndOmitsOnlyLegacyBlankIdentity() {
        val modern = PolarisApiClient.buildDoctorActionBody(
            actionId = "undo",
            appSessionId = "app-generation-123",
            appUuid = "game-uuid-123",
            sourceResultId = "doctor-v2",
            targetBitrateKbps = 16_000,
            runId = "doctor-run-7",
            confirmed = true
        )
        assertEquals("undo", modern.getString("action_id"))
        assertEquals("app-generation-123", modern.getString("app_session_id"))
        assertEquals("game-uuid-123", modern.getString("app_uuid"))
        assertEquals("doctor-v2", modern.getString("source_result_id"))
        assertEquals(16_000, modern.getInt("target_bitrate_kbps"))
        assertEquals("doctor-run-7", modern.getString("run_id"))
        assertTrue(modern.getBoolean("confirmed"))

        val legacy = PolarisApiClient.buildDoctorActionBody(
            actionId = "lower_bitrate",
            appSessionId = ""
        )
        assertFalse(legacy.has("app_session_id"))
    }

    @Test
    fun doctorActionUndoAvailabilityRequiresLiteralBoolean() {
        fun parsed(value: String): PolarisDoctorActionResult = PolarisApiClient.parseDoctorActionResponse(
            JSONObject("{\"status\":true,\"run_id\":\"run-1\",\"undo\":{\"available\":$value,\"action_id\":\"undo\"}}")
        )

        assertEquals(true, parsed("true").undoAvailable)
        assertEquals(false, parsed("false").undoAvailable)
        assertNull(parsed("null").undoAvailable)
        assertNull(parsed("\"false\"").undoAvailable)
        assertNull(
            PolarisApiClient.parseDoctorActionResponse(
                JSONObject("{\"status\":true,\"run_id\":\"run-1\",\"undo\":{}}")
            ).undoAvailable
        )
        assertFalse(
            PolarisApiClient.parseDoctorActionResponse(
                JSONObject("{\"status\":\"true\",\"undo\":{\"available\":true,\"action_id\":\"restore_quality\"}}")
            ).status
        )
    }

    @Test
    fun doctorActionHttpFailureIsPermanentAndSanitized() {
        val rejected = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 409,
            responseBody = "{\"status\":false,\"run_id\":\"run-1\",\"error\":\"expired\"," +
                "\"undo\":{\"available\":true,\"action_id\":\"restore_quality\"}}",
            actionId = "verify",
            requestedRunId = "run-1"
        )

        assertFalse(rejected.status)
        assertEquals("run-1", rejected.runId)
        assertEquals("expired", rejected.error)
        assertEquals(false, rejected.undoAvailable)
        assertEquals("", rejected.undoActionId)
        assertFalse(rejected.error.contains("409"))
    }

    @Test
    fun doctorActionHttpSuccessRequiresTheTypedActionAndRunContract() {
        val blankRunMutation = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"watching\"}",
            actionId = "lower_bitrate",
            requestedRunId = ""
        )
        val blankRunVerification = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":false,\"state\":\"resolved\"}",
            actionId = "verify",
            requestedRunId = "doctor-run-1"
        )
        val recheck = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":false,\"state\":\"observed\"}",
            actionId = "recheck_pacing",
            requestedRunId = ""
        )
        val incompleteNextStep = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"watching\"," +
                "\"run_id\":\"doctor-run-1\",\"undo\":{\"available\":true,\"action_id\":\"undo\"}}",
            actionId = "verify",
            requestedRunId = "doctor-run-1"
        )

        assertFalse(blankRunMutation.status)
        assertEquals("Invalid Doctor action response", blankRunMutation.error)
        assertFalse(blankRunVerification.status)
        assertFalse(incompleteNextStep.status)
        assertTrue(recheck.status)
    }

    @Test
    fun doctorActionHttpAcceptsCorrelatedMutationVerificationAndUndo() {
        val applied = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"watching\"," +
                "\"run_id\":\"doctor-run-1\",\"verification\":{\"action_id\":\"verify\"," +
                "\"delay_seconds\":8},\"undo\":{\"available\":true,\"action_id\":\"undo\"}}",
            actionId = "lower_bitrate",
            requestedRunId = ""
        )
        val verified = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":false,\"state\":\"resolved\"," +
                "\"run_id\":\"doctor-run-1\",\"undo\":{\"available\":true,\"action_id\":\"undo\"}}",
            actionId = "verify",
            requestedRunId = "doctor-run-1"
        )
        val undone = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"undone\"," +
                "\"run_id\":\"doctor-run-1\",\"undo\":{\"available\":false}}",
            actionId = "undo",
            requestedRunId = "doctor-run-1"
        )

        assertTrue(applied.status)
        assertTrue(verified.status)
        assertTrue(undone.status)
    }

    @Test
    fun doctorActionHttpAcceptsCorrelatedVerificationStillCollecting() {
        val collecting = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":false,\"state\":\"watching\"," +
                "\"run_id\":\"doctor-run-1\",\"undo\":{\"available\":true,\"action_id\":\"undo\"}}",
            actionId = "verify",
            requestedRunId = "doctor-run-1"
        )

        assertTrue(collecting.status)
        assertEquals("watching", collecting.state)
    }

    @Test
    fun doctorActionHttpAcceptsCorrelatedAutomaticRollback() {
        val rolledBack = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"rolled_back\"," +
                "\"run_id\":\"doctor-run-1\",\"undo\":{\"available\":false}}",
            actionId = "verify",
            requestedRunId = "doctor-run-1"
        )

        assertTrue(rolledBack.status)
    }

    @Test
    fun doctorActionHttpAcceptsOnlyTheExactLegacyRecoveryUndoReceipt() {
        val accepted = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"undone\"," +
                "\"run_id\":\"recovery-run-1\",\"undo\":{\"available\":false}}",
            actionId = "undo",
            requestedRunId = "recovery-run-1"
        )
        val mismatched = PolarisApiClient.parseDoctorActionHttpResponse(
            statusCode = 200,
            responseBody = "{\"status\":true,\"changed\":true,\"state\":\"undone\"," +
                "\"run_id\":\"recovery-run-2\",\"undo\":{\"available\":false}}",
            actionId = "undo",
            requestedRunId = "recovery-run-1"
        )

        assertTrue(accepted.status)
        assertFalse(mismatched.status)
    }

    @Test
    fun doctorActionHttpClientDisablesAutomaticConnectionReplay() {
        val base = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
        val nonRetryable = PolarisApiClient.buildNonRetryableHttpClient(base)

        assertTrue(base.retryOnConnectionFailure)
        assertFalse(nonRetryable.retryOnConnectionFailure)
        assertFalse(nonRetryable.followRedirects)
        assertFalse(nonRetryable.followSslRedirects)
    }

    @Test
    fun explicitHostTuningRevocationOverridesLegacyOwnershipFallback() {
        val explicitRevocation = PolarisApiClient.parseSessionStatusResponse(
            JSONObject(
                "{\"state\":\"streaming\",\"owned_by_client\":true,\"client_role\":\"owner\"," +
                    "\"controls\":{\"host_tuning_allowed\":false}}"
            )
        )
        val legacyOmission = PolarisApiClient.parseSessionStatusResponse(
            JSONObject("{\"state\":\"streaming\",\"owned_by_client\":true,\"client_role\":\"owner\"}")
        )

        assertFalse(explicitRevocation.canAdjustHostTuning)
        assertTrue(legacyOmission.canAdjustHostTuning)
    }

    @Test
    fun artworkHttpClientDisablesRedirectsAndAllowsBoundedProviderWorkflows() {
        val base = OkHttpClient.Builder().build()
        val artwork = PolarisApiClient.buildArtworkHttpClient(base)

        assertTrue(base.followRedirects)
        assertTrue(base.followSslRedirects)
        assertFalse(artwork.followRedirects)
        assertFalse(artwork.followSslRedirects)
        assertEquals(120_000, artwork.readTimeoutMillis)
        assertEquals(120_000, artwork.callTimeoutMillis)
    }

    @Test
    fun artworkHttpClientKeepsPolicyWhenPooled() {
        // Mirrors the production construction chain: newBuilder() copies the base's
        // no-keep-alive pool, so the pooled override must be applied explicitly.
        val base = OkHttpClient.Builder().connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS)).build()
        val pooled = PolarisApiClient.buildArtworkHttpClient(base)
            .newBuilder()
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()

        assertFalse(pooled.followRedirects)
        assertFalse(pooled.followSslRedirects)
        assertEquals(120_000, pooled.callTimeoutMillis)
        assertEquals(120_000, pooled.readTimeoutMillis)
    }

    @Test
    fun malformedArtworkJsonIsRejectedWithoutRetainingResponseBody() {
        val marker = "provider-response-must-not-reach-logs"
        val failure = try {
            PolarisApiClient.parseArtworkJsonBytes("{invalid:$marker".toByteArray())
            null
        } catch (e: IOException) {
            e
        }

        val sanitized = requireNotNull(failure)
        assertEquals("invalid artwork JSON", sanitized.message)
        assertFalse(sanitized.toString().contains(marker))
        assertNull(sanitized.cause)
    }

    @Test
    fun artworkPresentationKeyTracksRevisionAndLegacyCoverChanges() {
        val asset = PolarisGame.ArtworkAsset(url = "/polaris/v1/games/g/artwork/poster", cached = true)
        fun game(revision: String) = PolarisGame(id = "g", artwork = PolarisGame.ArtworkManifest(revision = revision, assets = PolarisGame.ArtworkAssets(poster = asset)))
        val first = PolarisApiClient.artworkPresentationKey(game("a"), PolarisGame.ARTWORK_KIND_POSTER)
        val second = PolarisApiClient.artworkPresentationKey(game("b"), PolarisGame.ARTWORK_KIND_POSTER)
        assertFalse(first == second)
        val legacyA = PolarisGame(id = "g", coverUrl = "/a")
        val legacyB = PolarisGame(id = "g", coverUrl = "/b")
        assertFalse(PolarisApiClient.artworkPresentationKey(legacyA, "poster") == PolarisApiClient.artworkPresentationKey(legacyB, "poster"))
    }

    @Test
    fun trustedCandidatePreviewUrlRequiresExactPairedOriginAndOpaquePath() {
        val good = "https://polaris.lan:47984/polaris/v1/games/game-1/artwork/candidate/0123456789abcdef0123456789abcdef/poster"
        assertTrue(PolarisApiClient.isTrustedCandidatePreviewUrl(good, "POLARIS.LAN", 47984))
        assertFalse(PolarisApiClient.isTrustedCandidatePreviewUrl("https://user@polaris.lan:47984/x", "polaris.lan", 47984))
        assertFalse(PolarisApiClient.isTrustedCandidatePreviewUrl("$good?token=secret", "polaris.lan", 47984))
        assertTrue(PolarisApiClient.isTrustedCandidatePreviewUrl(good.replace("/poster", "/hero"), "polaris.lan", 47984))
        assertTrue(PolarisApiClient.isTrustedCandidatePreviewUrl(good.replace("/poster", "/logo"), "polaris.lan", 47984))
        assertTrue(PolarisApiClient.isTrustedCandidatePreviewUrl(good.replace("/poster", "/icon"), "polaris.lan", 47984))
        assertFalse(PolarisApiClient.isTrustedCandidatePreviewUrl(good.replace("/poster", "/trailer"), "polaris.lan", 47984))
    }

    @Test
    fun artworkRequestLogLabelsNeverContainCandidatePreviewTokens() {
        val token = "0123456789abcdef0123456789abcdef"
        val candidateUrl = "https://polaris.lan:47984/polaris/v1/games/game-1/" +
            "artwork/candidate/$token/poster"
        val label = PolarisApiClient.artworkRequestLogLabel(candidateUrl)

        assertEquals("candidate-preview", label)
        assertFalse(label.contains(token))
        assertFalse(label.contains(candidateUrl))
        assertEquals(
            "manifest-artwork",
            PolarisApiClient.artworkRequestLogLabel("https://polaris.lan:47984/polaris/v1/games/game-1/artwork/poster"),
        )
        assertEquals("legacy-cover", PolarisApiClient.artworkRequestLogLabel("https://polaris.lan:47984/cover/game-1"))
    }

    @Test
    fun parseUnlockResponse_requiresSuccessFlag() {
        assertFalse(PolarisApiClient.parseUnlockResponse(JSONObject("{\"success\":false,\"was_locked\":true}")))
        assertTrue(PolarisApiClient.parseUnlockResponse(JSONObject("{\"success\":true,\"was_locked\":true}")))
        assertFalse(PolarisApiClient.parseUnlockResponse(JSONObject("{\"was_locked\":true}")))
    }

    @Test
    fun parseCapabilitiesResponse_includesCursorVisibilityControl() {
        val json = JSONObject(
            "{\"server\":\"polaris\",\"version\":\"1.0.0\"," +
                "\"features\":{\"ai_optimizer\":true,\"ai_optimizer_control\":true,\"cursor_visibility_control\":true," +
                "\"stream_policy_v1\":true,\"client_settings_v1\":true,\"optimizer_sync_v1\":true," +
                "\"resolved_profile_provenance_v1\":true}," +
                "\"capture\":{\"backend\":\"wayland\",\"codecs\":[\"hevc\"]}}"
        )

        val capabilities = PolarisApiClient.parseCapabilitiesResponse(json)

        assertEquals("polaris", capabilities.server)
        assertTrue(capabilities.features.aiOptimizer)
        assertTrue(capabilities.features.aiAutoQuality)
        assertTrue(capabilities.features.aiAutoQualityControl)
        assertTrue(capabilities.features.cursorVisibilityControl)
        assertTrue(capabilities.features.streamPolicy)
        assertTrue(capabilities.features.clientSettings)
        assertTrue(capabilities.features.optimizerSync)
        assertTrue(capabilities.features.resolvedProfileProvenance)
    }

    @Test
    fun parseSessionStatusResponse_includesHdrDowngradeTruth() {
        val health = JSONObject()
            .put("primary_issue", "hdr_downgraded")
            .put("safe_hdr", false)
            .put("hdr_effective_mode", "sdr_10bit")
            .put("hdr_downgrade_reason", "headless_hdr_unavailable")
            .put("hdr_downgrade_message", "Private Headless Stream is using a compositor output that does not report HDR.")
            .put("hdr_source", "missing")
        val json = JSONObject()
            .put("state", "streaming")
            .put("streaming_active", true)
            .put("dynamic_range", 1)
            .put("health", health)

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("sdr_10bit", status.health.hdrEffectiveMode)
        assertEquals("headless_hdr_unavailable", status.health.hdrDowngradeReason)
        assertEquals("Private Headless Stream is using a compositor output that does not report HDR.", status.health.hdrDowngradeMessage)
        assertEquals("missing", status.health.hdrSource)
        assertTrue(status.isHdrDowngraded)
        assertTrue(status.isHeadlessHdrUnavailable)
    }

    @Test
    fun parseSessionStatusResponse_includesLiveSessionFields() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true,\"shutdown_requested\":false," +
                "\"game_id\":123,\"game_uuid\":\"game-uuid\"," +
                "\"session_token\":\"token-123\",\"app_session_id\":\"app-session-123\",\"owner_unique_id\":\"owner-uuid\"," +
                "\"owner_device_name\":\"Retroid\",\"client_role\":\"viewer\",\"viewer_count\":2,\"owned_by_client\":true," +
                "\"cursor_visible\":true,\"dynamic_range\":1,\"mangohud_configured\":true,\"ai_auto_quality_enabled\":true," +
                "\"controls\":{\"host_tuning_allowed\":false,\"quit_allowed\":false,\"shutdown_in_progress\":false," +
                "\"client_commands_enabled\":true,\"device_commands_enabled\":true}," +
                "\"tuning\":{\"adaptive_bitrate_enabled\":true,\"adaptive_target_bitrate_kbps\":18000," +
                "\"adaptive_base_bitrate_kbps\":20000,\"adaptive_min_bitrate_kbps\":2000," +
                "\"adaptive_max_bitrate_kbps\":30000,\"adaptive_bitrate_state\":\"network_pressure\"," +
                "\"adaptive_bitrate_reason\":\"packet_loss\"," +
                "\"ai_auto_quality_enabled\":true,\"ai_optimizer_enabled\":true,\"mangohud_configured\":true}," +
                "\"display_mode\":{\"label\":\"Headless\",\"selection\":\"headless\",\"requested\":\"auto\"," +
                "\"explicit_choice\":false,\"virtual_display\":false,\"requested_headless\":true,\"effective_headless\":true}," +
                "\"capture\":{\"backend\":\"wayland\",\"resolution\":\"1920x1080\"," +
                "\"transport\":\"dmabuf\",\"residency\":\"gpu\",\"format\":\"bgra8\"}," +
                "\"encoder\":{\"codec\":\"hevc_nvenc\",\"bitrate_kbps\":20000,\"fps\":60.0," +
                "\"requested_client_fps\":60.0,\"session_target_fps\":60.0," +
                "\"encode_target_fps\":60.0,\"pacing_policy\":\"client_fps_limit\",\"optimization_source\":\"ai_cached\"," +
                "\"optimization_confidence\":\"medium\",\"optimization_cache_status\":\"hit\"," +
                "\"optimization_reasoning\":\"Cached AI recommendation remained healthy.\"," +
                "\"optimization_normalization_reason\":\"Adjusted bitrate to fit host limits.\"," +
                "\"recommendation_version\":2," +
                "\"target_device\":\"cuda\"," +
                "\"target_residency\":\"gpu\",\"target_format\":\"p010\"}," +
                "\"profile_state\":{\"state\":\"stable\",\"label\":\"Stable\",\"reason\":\"Auto Quality is holding the current profile.\"," +
                "\"source\":\"ai_cached\",\"cache_status\":\"hit\",\"confidence\":\"medium\"," +
                "\"preference\":\"high_fps\",\"preference_label\":\"Prefer High FPS\",\"preference_applied\":false," +
                "\"current_profile\":{\"display_mode\":\"1280x720x120\",\"target_bitrate_kbps\":60000," +
                "\"target_fps\":120.0,\"preferred_codec\":\"hevc\",\"hdr\":false}," +
                "\"last_result\":{\"grade\":\"A\",\"session_count\":3,\"delivered_fps\":118.0,\"target_fps\":120.0," +
                "\"low_1_percent_fps\":104.0,\"min_fps\":88.0,\"frame_pacing_bad_pct\":1.5," +
                "\"primary_issue\":\"steady\",\"sample_confidence\":\"high\",\"updated_at\":1770000000}," +
                "\"actions\":{\"can_reset\":true,\"can_retry_quality\":false,\"can_keep_recovery\":false," +
                "\"can_change_preference\":true}}," +
                "\"health\":{\"grade\":\"watch\",\"summary\":\"Network jitter is the most likely source of the hitching.\"," +
                "\"primary_issue\":\"network_jitter\",\"issues\":[\"network_jitter\",\"frame_pacing\"]," +
                "\"recommendations\":[\"Lower bitrate or keep Adaptive Bitrate enabled.\"]," +
                "\"safe_bitrate_kbps\":15000,\"safe_codec\":\"hevc\",\"safe_display_mode\":\"headless\"," +
                "\"safe_hdr\":false,\"decoder_risk\":\"normal\",\"hdr_risk\":\"normal\",\"network_risk\":\"elevated\"," +
                "\"relaunch_recommended\":true}," +
                "\"client_settings\":{\"desired\":{\"sync_mode\":\"auto_safe\",\"target_bitrate_kbps\":6000," +
                "\"adaptive_bitrate_enabled\":true,\"ai_optimizer_enabled\":true}," +
                "\"effective\":{\"target_bitrate_kbps\":6000,\"adaptive_target_bitrate_kbps\":5200," +
                "\"adaptive_bitrate_enabled\":true,\"ai_optimizer_enabled\":true," +
                "\"applied_stream_settings\":{\"target_bitrate_kbps\":6000,\"display_mode\":\"1280x720x60\"," +
                "\"preferred_codec\":\"hevc\",\"hdr\":false}}," +
                "\"sync_status\":{\"available\":true,\"version\":1,\"state\":\"synced\"," +
                "\"legacy_state\":\"adaptive_active\",\"sync_mode\":\"auto_safe\",\"manual_override\":false," +
                "\"message\":\"Auto Safe is active\"," +
                "\"applied_stream_settings\":{\"target_bitrate_kbps\":6000,\"display_mode\":\"1280x720x60\"," +
                "\"preferred_codec\":\"hevc\",\"hdr\":false}," +
                "\"fields\":{\"client_presentation\":{\"status\":\"synced\"," +
                "\"desired\":{\"target_refresh_rate_hz\":60.0,\"refresh_rate_policy\":\"exact_match_internal\"}," +
                "\"effective\":{\"status\":\"synced\",\"applied_refresh_rate_hz\":60.0,\"target_refresh_rate_hz\":60.0," +
                "\"refresh_rate_policy\":\"exact_match_internal\",\"display_mode\":\"refresh_rate:60.0\"," +
                "\"decoder\":\"c2.qti.hevc.decoder.low_latency\",\"frame_pacing_state\":\"steady\"," +
                "\"reason\":\"Nova matched the internal display refresh rate to the stream FPS\"}}}}}," +
                "\"stream_policy\":{\"presentation_policy\":{\"version\":1,\"target_refresh_rate_hz\":60.0," +
                "\"refresh_rate_policy\":\"exact_match_internal\",\"allow_display_mode_change\":true," +
                "\"internal_display_only\":true,\"reason\":\"Match internal handheld displays.\"}}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("streaming", status.state)
        assertEquals(123, status.gameId)
        assertEquals("game-uuid", status.gameUuid)
        assertEquals("token-123", status.sessionToken)
        assertEquals("app-session-123", status.appSessionId)
        assertTrue(status.appSessionIdPresent)
        assertEquals("owner-uuid", status.ownerUniqueId)
        assertEquals("Retroid", status.ownerDeviceName)
        assertEquals("viewer", status.clientRole)
        assertEquals(2, status.viewerCount)
        assertTrue(status.ownedByClient)
        assertTrue(status.streamingActive)
        assertTrue(status.cursorVisible)
        assertTrue(status.mangohudConfigured)
        assertFalse(status.controls.shutdownInProgress)
        assertTrue(status.tuning.adaptiveBitrateEnabled)
        assertTrue(status.aiAutoQualityEnabled)
        assertTrue(status.tuning.aiAutoQualityEnabled)
        assertEquals(18000, status.tuning.adaptiveTargetBitrateKbps)
        assertEquals(20000, status.tuning.adaptiveBaseBitrateKbps)
        assertEquals(2000, status.tuning.adaptiveMinBitrateKbps)
        assertEquals(30000, status.tuning.adaptiveMaxBitrateKbps)
        assertEquals("network_pressure", status.tuning.adaptiveBitrateState)
        assertEquals("packet_loss", status.tuning.adaptiveBitrateReason)
        assertEquals("auto", status.displayMode.requested)
        assertEquals("ai_cached", status.encoder.optimizationSource)
        assertEquals("medium", status.encoder.optimizationConfidence)
        assertEquals("hit", status.encoder.optimizationCacheStatus)
        assertEquals("Adjusted bitrate to fit host limits.", status.encoder.optimizationNormalizationReason)
        assertEquals(2, status.encoder.recommendationVersion)
        assertEquals("1920x1080", status.capture.resolution)
        assertEquals("dmabuf", status.capture.transport)
        assertEquals("gpu", status.encoder.targetResidency)
        assertEquals("p010", status.encoder.targetFormat)
        assertEquals("watch", status.health.grade)
        assertEquals("network_jitter", status.health.primaryIssue)
        assertTrue(status.health.recommendations.contains("Lower bitrate or keep Adaptive Bitrate enabled."))
        assertEquals(15000, status.health.safeBitrateKbps)
        assertEquals("hevc", status.health.safeCodec)
        assertEquals("headless", status.health.safeDisplayMode)
        assertEquals(false, status.health.safeHdr)
        assertTrue(status.hasHealthConcerns)
        assertTrue(status.isTenBitActive)
        assertTrue(status.isGpuPath)
        assertTrue(status.isViewer)
        assertEquals("Cached AI", status.optimizationSourceLabel)
        assertEquals("MEDIUM", status.optimizationConfidenceLabel)
        assertEquals(60.0, status.presentationPolicy.targetRefreshRateHz, 0.01)
        assertEquals("exact_match_internal", status.presentationPolicy.refreshRatePolicy)
        assertTrue(status.presentationPolicy.allowDisplayModeChange)
        assertTrue(status.isClientPresentationSynced)
        assertEquals("synced", status.clientPresentation.status)
        assertEquals(60.0, status.clientPresentation.appliedRefreshRateHz, 0.01)
        assertEquals("c2.qti.hevc.decoder.low_latency", status.clientPresentation.decoder)
        assertTrue(status.hasOptimizerSync)
        assertEquals("synced", status.syncStatus.state)
        assertEquals("adaptive_active", status.syncStatus.legacyState)
        assertEquals("auto_safe", status.syncStatus.syncMode)
        assertTrue(status.syncStatus.isSynced)
        assertEquals("Synced", status.syncStatus.label)
        assertEquals(6000, status.syncStatus.effective.targetBitrateKbps)
        assertEquals(5200, status.syncStatus.effective.adaptiveTargetBitrateKbps)
        assertEquals("1280x720x60", status.syncStatus.applied.displayMode)
        assertEquals("hevc", status.syncStatus.applied.preferredCodec)
        assertEquals("stable", status.profileState.state)
        assertEquals("Stable", status.profileState.label)
        assertEquals("Prefer High FPS", status.profileState.preferenceLabel)
        assertFalse(status.profileState.preferenceApplied)
        assertEquals("1280x720x120", status.profileState.currentProfile.displayMode)
        assertEquals(60000, status.profileState.currentProfile.targetBitrateKbps)
        assertEquals(120.0, status.profileState.currentProfile.targetFps, 0.01)
        assertEquals("hevc", status.profileState.currentProfile.preferredCodec)
        assertEquals(false, status.profileState.currentProfile.hdr)
        assertEquals("A", status.profileState.lastResult.grade)
        assertEquals(3, status.profileState.lastResult.sessionCount)
        assertEquals(118.0, status.profileState.lastResult.deliveredFps, 0.01)
        assertTrue(status.profileState.actions.canReset)
    }

    @Test
    fun parseSessionStatusResponse_rejectsTypedInvalidSecurityIdentities() {
        val invalidValues = listOf<Any>(JSONObject.NULL, 7, true, JSONObject())
        invalidValues.forEach { invalid ->
            val invalidAppSession = JSONObject()
                .put("state", "streaming")
                .put("game_uuid", "control")
                .put("session_token", "token-123")
                .put("app_session_id", invalid)
            assertThrows(JSONException::class.java) {
                PolarisApiClient.parseSessionStatusResponse(invalidAppSession)
            }

            val invalidTransport = JSONObject()
                .put("state", "streaming")
                .put("game_uuid", "control")
                .put("session_token", invalid)
            assertThrows(JSONException::class.java) {
                PolarisApiClient.parseSessionStatusResponse(invalidTransport)
            }

            val invalidGame = JSONObject()
                .put("state", "streaming")
                .put("game_uuid", invalid)
                .put("session_token", "token-123")
            assertThrows(JSONException::class.java) {
                PolarisApiClient.parseSessionStatusResponse(invalidGame)
            }
        }
    }

    @Test
    fun parseSessionStatusResponse_allowsAnAbsentAppIdentityForLegacyTokenScope() {
        val status = PolarisApiClient.parseSessionStatusResponse(
            JSONObject()
                .put("state", "streaming")
                .put("game_uuid", "control")
                .put("session_token", "legacy-token")
        )

        assertEquals("legacy-token", status.sessionToken)
        assertEquals("", status.appSessionId)
        assertFalse(status.appSessionIdPresent)
    }

    @Test
    fun parseSessionStatusResponse_includesPolarisDoctorDiagnosis() {
        val resultId = "doctor-v2-needs_action-network_jitter-gpu_native"
        val json = JSONObject()
            .put("state", "streaming")
            .put("streaming_active", true)
            .put(
                "doctor",
                JSONObject()
                    .put("version", 2)
                    .put("result_id", resultId)
                    .put("primary_issue", "network_jitter")
                    .put("summary", "Current evidence confirms network pressure.")
                    .put("confidence", JSONObject().put("level", "high"))
                    .put(
                        "evidence",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "packet_loss")
                                    .put("status", "fail")
                                    .put("source", "media_transport")
                                    .put("value", 3.4)
                                    .put("detail", "Packet loss is 3.4% over the last sample window.")
                            )
                            .put(
                                JSONObject()
                                    .put("id", "latency")
                                    .put("status", "pass")
                                    .put("source", "stream_stats")
                                    .put("value", 12.0)
                                    .put("detail", "Latency is 12ms.")
                            )
                    )
                    .put(
                        "recommendation",
                        JSONObject()
                            .put("body", "Current evidence confirms network pressure.")
                            .put("next_step_label", "Fix and verify")
                    )
                    .put(
                        "safe_recovery_action",
                        JSONObject()
                            .put("id", "lower_bitrate")
                            .put("label", "Auto Fix")
                            .put("capability", "auto_fix")
                            .put("kind", "live_tuning")
                            .put("endpoint", "/api/doctor/action")
                            .put("method", "POST")
                            .put("destructive", false)
                            .put("requires_confirmation", false)
                            .put("requires_owner", true)
                            .put("allowed_in_viewer_mode", false)
                            .put("owner_tuning_allowed", false)
                            .put("paired_endpoint", "")
                            .put(
                                "payload_preview",
                                JSONObject()
                                    .put("action_id", "lower_bitrate")
                                    .put("source_result_id", resultId)
                                    .put("target_bitrate_kbps", 16_000)
                            )
                            .put(
                                "verification",
                                JSONObject()
                                    .put("mode", "live_telemetry")
                                    .put("endpoint", "/api/doctor/action")
                                    .put("delay_seconds", 8)
                            )
                            .put(
                                "undo",
                                JSONObject()
                                    .put("supported", true)
                                    .put("endpoint", "/api/doctor/action")
                                    .put("paired_endpoint", "")
                            )
                    )
            )
            .put(
                "ai_doctor_explanation",
                JSONObject()
                    .put("status", true)
                    .put(
                        "source",
                        JSONObject()
                            .put("kind", "openai")
                            .put("mode", "subscription")
                            .put("informational", true)
                    )
                    .put(
                        "explanation",
                        JSONObject()
                            .put("likely_cause", "Wi-Fi jitter is the likely bottleneck.")
                            .put("evidence", org.json.JSONArray().put("3.4% packet loss"))
                            .put("try_first", org.json.JSONArray().put("Lower bitrate"))
                            .put("advanced_detail", "Network evidence beats encoder speculation.")
                            .put("confidence", "high")
                            .put("destructive_action_allowed", true)
                    )
            )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertTrue(status.doctor.available)
        assertEquals("NET", status.doctor.classification)
        assertEquals("Current evidence confirms network pressure.", status.doctor.likelyCause)
        assertEquals("Packet loss is 3.4% over the last sample window.", status.doctor.evidence.first())
        assertEquals("Current evidence confirms network pressure.", status.doctor.tryFirst.first())
        assertEquals("high", status.doctor.confidence)
        assertTrue(status.doctor.aiExplanation.available)
        assertEquals("Wi-Fi jitter is the likely bottleneck.", status.doctor.aiExplanation.likelyCause)
        assertEquals("Lower bitrate", status.doctor.aiExplanation.tryFirst.first())
        assertEquals(2, status.doctor.version)
        assertEquals("lower_bitrate", status.doctor.actionId)
        assertEquals("Auto Fix", status.doctor.actionLabel)
        assertEquals(16000, status.doctor.targetBitrateKbps)
        assertEquals(8, status.doctor.verificationDelaySeconds)
        assertTrue(status.doctor.undoSupported)
        assertEquals(3.4, status.doctor.packetLossPct!!, 0.01)
        assertTrue(status.doctor.canExecuteAction)
        assertFalse(status.doctor.destructiveActionAllowed)
    }

    @Test
    fun parseSessionStatusResponse_keepsSteamInputMutationReadOnly() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true," +
                "\"doctor\":{\"version\":2,\"result_id\":\"doctor-v2-steam_input_conflict-xbox\"," +
                "\"primary_issue\":\"steam_input_conflict\"," +
                "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"label\":\"Disable Xbox Steam Input\"," +
                "\"kind\":\"host_setting\",\"requires_confirmation\":true," +
                "\"verification\":{\"delay_seconds\":6},\"undo\":{\"supported\":true}}}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("disable_steam_input_xbox", status.doctor.actionId)
        assertEquals("steam_input_conflict", status.doctor.primaryIssue)
        assertEquals("host_setting", status.doctor.actionKind)
        assertEquals("doctor-v2-steam_input_conflict-xbox", status.doctor.resultId)
        assertTrue(status.doctor.undoSupported)
        assertTrue(status.doctor.requiresConfirmation)
        assertFalse(status.doctor.canExecuteAction)
    }

    @Test
    fun deprecatedNextLaunchRecoveryActionIsNeverExecutable() {
        fun status(mutate: (JSONObject) -> Unit = {}): PolarisSessionStatus.DoctorStatus {
            val action = JSONObject()
                .put("id", "apply_recovery_profile_next_launch")
                .put("label", "Use safer settings next launch")
                .put("kind", "next_launch_profile")
                .put("requires_confirmation", true)
                .put("owner_tuning_allowed", true)
                .put("paired_endpoint", "/polaris/v1/doctor/action")
                .put("payload_preview", JSONObject().put("app_uuid", "game-a"))
                .put(
                    "undo",
                    JSONObject()
                        .put("supported", true)
                        .put("paired_endpoint", "/polaris/v1/doctor/action")
                )
            mutate(action)
            return PolarisApiClient.parseSessionStatusResponse(
                JSONObject()
                    .put("state", "streaming")
                    .put(
                        "doctor",
                        JSONObject()
                            .put("version", 2)
                            .put("result_id", "doctor-frame-pacing-a")
                            .put("primary_issue", "frame_pacing")
                            .put("safe_recovery_action", action)
                    )
            ).doctor
        }

        val exact = status()
        assertFalse(exact.canExecuteAction)
        assertEquals("game-a", exact.actionAppUuid)
        assertFalse(status { it.put("id", "apply_recovery_profile") }.canExecuteAction)
        assertFalse(status { it.put("kind", "live_tuning") }.canExecuteAction)
        assertFalse(status { it.put("requires_confirmation", false) }.canExecuteAction)
        assertFalse(status { it.put("requires_confirmation", "true") }.canExecuteAction)
        assertFalse(status { it.put("owner_tuning_allowed", false) }.canExecuteAction)
        assertFalse(status { it.put("owner_tuning_allowed", "true") }.canExecuteAction)
        assertFalse(status { it.put("payload_preview", JSONObject()) }.canExecuteAction)
        assertFalse(status { it.put("undo", JSONObject().put("supported", false)) }.canExecuteAction)
        assertFalse(status { it.put("undo", JSONObject().put("supported", "true")) }.canExecuteAction)
        assertFalse(status { it.put("paired_endpoint", "/apps/close") }.canExecuteAction)
        assertFalse(
            status {
                it.getJSONObject("undo").put("paired_endpoint", "/apps/close")
            }.canExecuteAction
        )
    }

    @Test
    fun queuedRecoveryReceiptsReconstructEveryTerminalStateAndUndo() {
        val records = org.json.JSONArray()
        listOf("queued", "expired", "applied", "rejected", "undone").forEachIndexed { index, state ->
            records.put(
                JSONObject()
                    .put("status", state != "rejected")
                    .put("recovery_state", state)
                    .put("run_id", "run-$index")
                    .put("source_result_id", "doctor-result-$index")
                    .put("app_uuid", "game-$index")
                    .put("expires_at", 2_000_000_000L + index)
                    .put(
                        "safe_profile",
                        JSONObject()
                            .put("stream_display_mode", "host_virtual_display")
                            .put("width", 1920)
                            .put("height", 1080)
                            .put("target_fps", 60)
                            .put("target_bitrate_kbps", 16_000)
                            .put("preferred_codec", "hevc")
                            .put("hdr", false)
                            .put("preserve_paired_resolution", true)
                            .put("requires_fresh_launch", true)
                    )
                    .put(
                        "undo",
                        JSONObject()
                            .put("supported", true)
                            .put("available", state == "queued")
                            .put("action_id", "undo_recovery_profile_next_launch")
                    )
            )
        }
        val status = PolarisApiClient.parseSessionStatusResponse(
            JSONObject().put("state", "idle").put("recovery_records", records)
        )

        assertEquals(listOf("queued", "expired", "applied", "rejected", "undone"), status.recoveryRecords.map { it.normalizedState })
        val queued = status.recoveryRecords.first()
        assertTrue(queued.undoSupported)
        assertTrue(queued.undoAvailable)
        assertEquals("undo_recovery_profile_next_launch", queued.undoActionId)
        assertEquals("host_virtual_display", queued.safeProfile.streamDisplayMode)
        assertEquals(1920, queued.safeProfile.width)
        assertEquals(60f, queued.safeProfile.targetFps, 0.01f)
        assertTrue(queued.safeProfile.preservePairedResolution)
        assertTrue(queued.safeProfile.requiresFreshLaunch)
    }

    @Test
    fun deterministicDoctorFallbackIsAnInformationalSource() {
        val status = PolarisApiClient.parseSessionStatusResponse(
            JSONObject()
                .put("state", "streaming")
                .put(
                    "doctor",
                    JSONObject()
                        .put("version", 2)
                        .put("result_id", "doctor-frame-pacing")
                        .put("primary_issue", "frame_pacing")
                        .put("summary", "Frame pacing needs attention.")
                )
                .put(
                    "ai_doctor_explanation",
                    JSONObject()
                        .put("status", true)
                        .put(
                            "source",
                            JSONObject()
                                .put("kind", "deterministic-fallback")
                                .put("mode", "openai-subscription")
                                .put("informational", true)
                        )
                        .put(
                            "explanation",
                            JSONObject()
                                .put("likely_cause", "Frame pacing is uneven.")
                                .put("confidence", "deterministic-fallback")
                        )
                )
        )

        assertEquals("deterministic-fallback", status.doctor.explanationSourceKind)
        assertEquals("openai-subscription", status.doctor.explanationSourceMode)
        assertTrue(status.doctor.explanationInformational)
        assertEquals("Frame pacing needs attention.", status.doctor.likelyCause)
        assertEquals("deterministic", status.doctor.confidence)
        assertEquals("Frame pacing is uneven.", status.doctor.aiExplanation.likelyCause)
        assertEquals("deterministic-fallback", status.doctor.aiExplanation.confidence)
    }

    @Test
    fun steamInputMutationRemainsReadOnlyEvenWhenContractIsWellFormed() {
        fun status(overrides: String): PolarisSessionStatus.DoctorStatus {
            val json = JSONObject(
                "{\"state\":\"streaming\",\"streaming_active\":true," +
                    "\"doctor\":{\"version\":2,\"result_id\":\"doctor-xbox\"," +
                    "\"primary_issue\":\"steam_input_conflict\"," +
                    "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\"," +
                    "\"kind\":\"host_setting\",\"requires_confirmation\":true," +
                    "\"verification\":{\"delay_seconds\":6},\"undo\":{\"supported\":true}" +
                    overrides + "}}}"
            )
            return PolarisApiClient.parseSessionStatusResponse(json).doctor
        }

        assertFalse("Steam Input mutation is disabled for this release", status("").canExecuteAction)
        assertFalse(
            "version below 2 must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":1,\"result_id\":\"doctor-xbox\"," +
                        "\"primary_issue\":\"steam_input_conflict\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"host_setting\"," +
                        "\"requires_confirmation\":true,\"undo\":{\"supported\":true}}}}"
                )
            ).doctor.canExecuteAction
        )
        assertFalse(
            "mismatched primary_issue must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":2,\"result_id\":\"doctor-xbox\"," +
                        "\"primary_issue\":\"network_jitter\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"host_setting\"," +
                        "\"requires_confirmation\":true,\"undo\":{\"supported\":true}}}}"
                )
            ).doctor.canExecuteAction
        )
        assertFalse(
            "non host_setting kind must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":2,\"result_id\":\"doctor-xbox\"," +
                        "\"primary_issue\":\"steam_input_conflict\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"live_tuning\"," +
                        "\"requires_confirmation\":true,\"undo\":{\"supported\":true}}}}"
                )
            ).doctor.canExecuteAction
        )
        assertFalse(
            "blank result_id must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":2,\"result_id\":\"\"," +
                        "\"primary_issue\":\"steam_input_conflict\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"host_setting\"," +
                        "\"requires_confirmation\":true,\"undo\":{\"supported\":true}}}}"
                )
            ).doctor.canExecuteAction
        )
        assertFalse(
            "stringified requires_confirmation must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":2,\"result_id\":\"doctor-xbox\"," +
                        "\"primary_issue\":\"steam_input_conflict\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"host_setting\"," +
                        "\"requires_confirmation\":\"true\",\"undo\":{\"supported\":true}}}}"
                )
            ).doctor.canExecuteAction
        )
        assertFalse(
            "missing requires_confirmation must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":2,\"result_id\":\"doctor-xbox\"," +
                        "\"primary_issue\":\"steam_input_conflict\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"host_setting\"," +
                        "\"undo\":{\"supported\":true}}}}"
                )
            ).doctor.canExecuteAction
        )
        assertFalse(
            "undo.supported false must reject",
            PolarisApiClient.parseSessionStatusResponse(
                JSONObject(
                    "{\"state\":\"streaming\",\"doctor\":{\"version\":2,\"result_id\":\"doctor-xbox\"," +
                        "\"primary_issue\":\"steam_input_conflict\"," +
                        "\"safe_recovery_action\":{\"id\":\"disable_steam_input_xbox\",\"kind\":\"host_setting\"," +
                        "\"requires_confirmation\":true,\"undo\":{\"supported\":false}}}}"
                )
            ).doctor.canExecuteAction
        )
    }

    @Test
    fun matchesConfirmedAction_requiresExactActionIdAndResultIdPair() {
        val confirmed = PolarisSessionStatus.DoctorStatus(
            available = true,
            version = 2,
            resultId = "doctor-v2-network-a",
            primaryIssue = "network_jitter",
            evidenceItems = listOf(
                PolarisSessionStatus.DoctorStatus.EvidenceItem(
                    id = "packet_loss",
                    status = "fail",
                    source = "media_transport",
                    value = 3.4
                )
            ),
            actionId = "lower_bitrate",
            actionCapability = "auto_fix",
            actionKind = "live_tuning",
            actionEndpoint = "/api/doctor/action",
            actionMethod = "POST",
            actionPayloadId = "lower_bitrate",
            actionSourceResultId = "doctor-v2-network-a",
            actionContractTyped = true,
            targetBitrateKbps = 16_000,
            targetBitratePresent = true,
            targetBitrateTyped = true,
            verificationDelaySeconds = 8,
            verificationMode = "live_telemetry",
            verificationEndpoint = "/api/doctor/action",
            undoSupported = true,
            undoEndpoint = "/api/doctor/action",
            requiresOwner = true
        )

        assertTrue(confirmed.matchesConfirmedAction(confirmed))
        assertFalse(
            confirmed.copy(actionId = "restore_quality").matchesConfirmedAction(confirmed)
        )
        assertFalse(
            confirmed.copy(resultId = "doctor-v2-network-next")
                .matchesConfirmedAction(confirmed)
        )
        assertFalse(
            confirmed.copy(actionId = "").matchesConfirmedAction(confirmed)
        )
        assertFalse(
            confirmed.copy(resultId = "").matchesConfirmedAction(confirmed)
        )
        assertFalse(
            PolarisSessionStatus.DoctorStatus().matchesConfirmedAction(confirmed)
        )
    }

    @Test
    fun parseSessionStatusResponse_fallsBackForOlderHostsWithoutDoctorPayload() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true," +
                "\"health\":{\"grade\":\"watch\",\"summary\":\"Host render is missing the stream FPS target.\"," +
                "\"primary_issue\":\"host_render_limited\",\"recommendations\":[\"Lower game FPS before tuning bitrate.\"]}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertFalse(status.doctor.available)
        assertEquals("HOST", status.doctor.classification)
        assertEquals("Host render is missing the stream FPS target.", status.doctor.likelyCause)
        assertEquals("Lower game FPS before tuning bitrate.", status.doctor.tryFirst.first())
        assertEquals("fallback", status.doctor.confidence)
    }

    @Test
    fun observationalNetworkFallbackIsNotClassifiedAsHostOrNetworkFailure() {
        val status = PolarisApiClient.parseSessionStatusResponse(
            JSONObject(
                "{\"state\":\"streaming\",\"streaming_active\":true," +
                    "\"health\":{\"grade\":\"watch\",\"summary\":\"Control retries observed.\"," +
                    "\"primary_issue\":\"control_channel_observation\"}}"
            )
        )

        assertEquals("UNKNOWN", status.doctor.classification)
        assertFalse(status.hasHealthConcerns)
        assertEquals("Control retries", status.healthToneLabel)
    }

    @Test
    fun parseSessionStatusResponse_includesHostRenderLimitedHealth() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true," +
                "\"auto_quality\":{\"enabled\":true,\"state\":\"recovery_queued\",\"blocked_reason\":\"none\"," +
                "\"live_bitrate_kbps\":4707,\"quality_cap_kbps\":50000," +
                "\"relaunch_required\":true,\"suggested_profile\":{\"target_fps\":30}," +
                "\"summary\":\"AI Recovery Profile ready for the next launch.\"}," +
                "\"health\":{\"auto_mode\":true,\"limiting_factor\":\"host_render\",\"auto_action\":\"lower_render_profile\"," +
                "\"grade\":\"watch\",\"summary\":\"Host render is missing the stream FPS target.\"," +
                "\"primary_issue\":\"host_render_limited\",\"issues\":[\"host_render_limited\"]," +
                "\"host_render_limited\":true,\"safe_target_fps\":30,\"recovery_profile\":\"host_render_limited\"," +
                "\"render_fps_gap\":4.0,\"relaunch_recommended\":true}," +
                "\"encoder\":{\"requested_client_fps\":30.0,\"session_target_fps\":30.0,\"encode_target_fps\":30.0}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertTrue(status.isHostRenderLimited)
        assertEquals("Host Render", status.healthToneLabel)
        assertEquals("host_render_limited", status.health.primaryIssue)
        assertEquals(4.0, status.health.renderFpsGap, 0.01)
        assertTrue(status.health.autoMode)
        assertEquals("host_render", status.health.limitingFactor)
        assertEquals("lower_render_profile", status.health.autoAction)
        assertEquals("host_render_limited", status.health.recoveryProfile)
        assertEquals(30.0, status.health.safeTargetFps, 0.01)
        assertTrue(status.health.relaunchRecommended)
        assertEquals("recovery_queued", status.autoQuality.state)
        assertEquals("none", status.autoQuality.blockedReason)
        assertEquals(30.0, status.autoQuality.suggestedTargetFps, 0.01)
        assertEquals(4707, status.autoQuality.liveBitrateKbps)
        assertEquals(50000, status.autoQuality.qualityCapKbps)
        assertTrue(status.autoQuality.enabled)
        assertTrue(status.autoQuality.isRecoveryQueued)
    }

    @Test
    fun parseSessionStatusResponse_includesLinuxGpuProfile() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true," +
                "\"capture\":{\"path\":\"shm_cpu_capture\",\"reason\":\"gpu_native_requested_shm_fallback\"," +
                "\"transport\":\"shm\",\"residency\":\"cpu\",\"format\":\"bgra8\"}," +
                "\"encoder\":{\"codec\":\"hevc\",\"target_device\":\"vaapi\",\"target_residency\":\"gpu\"}," +
                "\"linux_gpu_profile\":{\"encoder_api\":\"vaapi\",\"encoder_adapter\":\"/dev/dri/renderD128\"," +
                "\"capture_device\":\"/dev/dri/renderD128\",\"adapter_matches_capture_device\":true," +
                "\"gpu_native_requested\":true,\"gpu_native_attempted\":true,\"gpu_native_succeeded\":false," +
                "\"vaapi_vendor\":\"Mesa Gallium\"}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("vaapi", status.linuxGpuProfile?.encoderApi)
        assertEquals("/dev/dri/renderD128", status.linuxGpuProfile?.encoderAdapter)
        assertEquals("/dev/dri/renderD128", status.linuxGpuProfile?.captureDevice)
        assertTrue(status.linuxGpuProfile?.adapterMatchesCaptureDevice == true)
        assertTrue(status.linuxGpuProfile?.gpuNativeRequested == true)
        assertFalse(status.linuxGpuProfile?.gpuNativeSucceeded ?: true)
        assertEquals("Mesa Gallium", status.linuxGpuProfile?.vaapiVendor)
        assertEquals("VAAPI + SHM fallback", status.hostCaptureTruthLabel)
    }

    @Test
    fun buildClientSettingsUpdateBody_mapsAiAutoQualityToLegacyFields() {
        val body = PolarisApiClient.buildClientSettingsUpdateBody(
            streamDisplayMode = null,
            displayMode = null,
            clearDisplayMode = false,
            targetBitrateKbps = null,
            clearTargetBitrate = false,
            adaptiveBitrateEnabled = null,
            aiOptimizerEnabled = null,
            aiAutoQualityEnabled = true,
            disconnectResumeTimeoutSeconds = null
        )

        assertTrue(body.getBoolean("ai_auto_quality_enabled"))
        assertTrue(body.getBoolean("ai_optimizer_enabled"))
        assertTrue(body.getBoolean("adaptive_bitrate_enabled"))
    }

    @Test
    fun buildOptimizerProfileClearBody_includesDeviceAndGame() {
        val body = PolarisApiClient.buildOptimizerProfileClearBody(
            "Retroid Pocket 6",
            "Black Myth: Wukong"
        )

        assertEquals("Retroid Pocket 6", body.getString("device"))
        assertEquals("Black Myth: Wukong", body.getString("game"))
    }

    @Test
    fun parseSessionStatus_cudaTargetDeviceImpliesGpuPathWhenResidencyMissing() {
        val status = PolarisApiClient.parseSessionStatusResponse(
            JSONObject(
                "{\"state\":\"streaming\",\"streaming_active\":true," +
                    "\"display_mode\":{\"label\":\"Virtual Display\",\"selection\":\"virtual_display\"," +
                    "\"virtual_display\":true,\"effective_headless\":false}," +
                    "\"capture\":{\"transport\":\"dmabuf\"}," +
                    "\"encoder\":{\"codec\":\"hevc_nvenc\",\"target_device\":\"cuda\",\"target_format\":\"p010\"}}"
            )
        )

        assertEquals("cuda", status.encoder.targetDevice)
        assertTrue(status.isGpuPath)
        assertTrue(status.isTenBitActive)
    }

    @Test
    fun parseGameResponse_includesLaunchModeContract() {
        val json = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Steam Big Picture\"," +
                "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"headless_stream\"," +
                "\"allowed_modes\":[\"headless_stream\",\"desktop_display\",\"windowed_stream\",\"host_virtual_display\"]," +
                "\"mode_reason\":\"Headless is recommended because this Polaris host is already configured for headless streaming.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(json)
        val launchMode = game.launchMode!!

        assertEquals("game-uuid", game.id)
        assertEquals("host_virtual_display", launchMode.preferredMode)
        assertEquals("headless_stream", launchMode.recommendedMode)
        assertTrue(launchMode.allowedModes.contains("headless_stream"))
        assertTrue(launchMode.allowedModes.contains("host_virtual_display"))
    }

    @Test
    fun parseGameResponse_emptyAllowedLaunchModesDefaultsAvailable() {
        val json = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                "\"launch_mode\":{\"preferred_mode\":\"headless_stream\",\"recommended_mode\":\"headless_stream\"," +
                "\"allowed_modes\":[],\"mode_reason\":\"Default launch mode.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(json)
        val launchMode = game.launchMode!!

        assertEquals("headless_stream", launchMode.preferredMode)
        assertTrue(launchMode.allowedModes.contains("headless_stream"))
        assertTrue(launchMode.allowedModes.contains("host_virtual_display"))
    }

    @Test
    fun launchModeChoice_forcesHeadlessWhenVirtualDisplayUnavailable() {
        val settingsJson = JSONObject(
            "{\"version\":1,\"desired\":{},\"effective\":{},\"capabilities\":{\"modes\":[" +
                "{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}," +
                "{\"value\":\"host_virtual_display\",\"label\":\"Host Virtual Display\",\"available\":false," +
                "\"reason\":\"Virtual display output is not configured.\"}]}}"
        )
        val settings = PolarisApiClient.parseClientSettingsResponse(settingsJson)
        val gameJson = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"host_virtual_display\"," +
                "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                "\"mode_reason\":\"Virtual display preferred.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(gameJson)
        val choice = game.resolveLaunchModeChoice(true, settings)

        assertEquals("headless_stream", choice.preferredMode)
        assertEquals("headless_stream", choice.recommendedMode)
        assertTrue(choice.headlessAllowed)
        assertFalse(choice.virtualDisplayAllowed)
        assertTrue(choice.virtualDisplayUnavailable)
        assertEquals("Virtual display output is not configured.", choice.virtualDisplayUnavailableReason)
    }

    @Test
    fun launchModeChoice_usesHostStreamModeOverGameVirtualPreference() {
        val settingsJson = JSONObject(
            "{\"version\":1," +
                "\"desired\":{\"stream_display_mode\":\"headless_stream\"," +
                "\"stream_display_mode_reason\":\"Polaris will stream from the private headless compositor runtime.\"}," +
                "\"effective\":{},\"capabilities\":{\"modes\":[" +
                "{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}," +
                "{\"value\":\"host_virtual_display\",\"label\":\"Host Virtual Display\",\"available\":true}]}}"
        )
        val settings = PolarisApiClient.parseClientSettingsResponse(settingsJson)
        val gameJson = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"host_virtual_display\"," +
                "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                "\"mode_reason\":\"This app is configured to prefer a dedicated virtual display on the host.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(gameJson)
        val choice = game.resolveLaunchModeChoice(true, settings)

        assertEquals("host_virtual_display", choice.preferredMode)
        assertEquals("headless_stream", choice.recommendedMode)
        assertEquals("headless_stream", choice.hostDefaultMode)
        assertTrue(choice.virtualDisplayAllowed)
        assertFalse(choice.virtualDisplayUnavailable)
        assertEquals(
            "Polaris will stream from the private headless compositor runtime.",
            choice.hostModeReason
        )
    }

    @Test
    fun parseGame_includesSteamLaunchContract() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """
                {
                  "id": "game-1",
                  "app_id": 123,
                  "name": "Portal",
                  "source": "steam",
                  "steam_appid": "400",
                  "steam_launch": {
                    "available": true,
                    "mode": "big-picture",
                    "recommended_mode": "direct",
                    "allowed_modes": ["direct", "big-picture"],
                    "mode_reason": "Steam Big Picture compatibility mode may also receive controller input."
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(game.supportsSteamLaunchMode)
        assertEquals("big-picture", game.steamLaunch?.mode)
        assertEquals("direct", game.steamLaunch?.recommendedMode)
        assertEquals(listOf("direct", "big-picture"), game.steamLaunch?.allowedModes)
        assertEquals(
            "Steam Big Picture compatibility mode may also receive controller input.",
            game.steamLaunch?.modeReason
        )
    }

    @Test
    fun parseGame_includesArtworkManifestWithDefaults() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """
                {
                  "id":"game-artwork",
                  "cover_url":"/legacy-cover",
                  "artwork":{
                    "revision":"revision-a",
                    "assets":{
                      "poster":{"url":"/polaris/v1/games/game-artwork/artwork/poster","source":"local","mime_type":"image/png","cached":true},
                      "hero":{"url":"/polaris/v1/games/game-artwork/artwork/hero"}
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(1, game.artwork?.version)
        assertEquals("revision-a", game.artwork?.revision)
        assertEquals("/polaris/v1/games/game-artwork/artwork/poster", game.posterArtwork?.url)
        assertEquals("local", game.posterArtwork?.source)
        assertEquals("image/png", game.posterArtwork?.mimeType)
        assertTrue(game.posterArtwork?.cached == true)
        assertEquals("", game.heroArtwork?.source)
        assertEquals("/legacy-cover", game.coverUrl)
    }

    @Test
    fun parseGame_includesCompleteArtworkManifestV1Shape() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """
                {
                  "id":"game-complete",
                  "artwork":{
                    "version":1,
                    "revision":"rev-complete",
                    "state":"partial",
                    "match":{"source":"steamgriddb","provider_game_id":"99","title":"Corrected Match","confidence":1.5,"manual":true},
                    "cached_at":1785641400000,
                    "assets":{
                      "screenshots":[
                        {"url":"/polaris/v1/games/game-complete/artwork/screenshots/0","source":"steam","mime_type":"image/jpeg","cached":true},
                        "malformed"
                      ],
                      "trailer":{"url":"/polaris/v1/games/game-complete/artwork/trailer","source":"steam","mime_type":"video/mp4","cached":true}
                    },
                    "override":{"active":true,"kinds":["logo"],"logo_transform":{"x":-2,"y":0.75,"scale":9}}
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals("partial", game.artwork?.state)
        assertEquals(1785641400000, game.artwork?.cachedAt)
        assertEquals(1.0, game.artwork?.match?.confidence)
        assertTrue(game.artwork?.match?.manual == true)
        assertEquals(1, game.screenshotArtwork.size)
        assertEquals("video/mp4", game.trailerArtwork?.mimeType)
        assertEquals(0.0, game.artwork?.override?.logoTransform?.x)
        assertEquals(0.75, game.artwork?.override?.logoTransform?.y)
        assertEquals(4.0, game.artwork?.override?.logoTransform?.scale)
    }

    @Test
    fun parseGame_ignoresMalformedArtworkKindsAndKeepsLegacyCover() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """
                {
                  "id":"game-malformed-artwork",
                  "cover_url":"/polaris/v1/games/game-malformed-artwork/cover",
                  "artwork":{
                    "version":"not-an-int",
                    "assets":{
                      "poster":"not-an-object",
                      "hero":{"url":""},
                      "banner":{"url":"https://provider.invalid/banner.png"}
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(1, game.artwork?.version)
        assertNull(game.posterArtwork)
        assertNull(game.heroArtwork)
        assertNull(game.artworkAsset("banner"))
        assertEquals("/polaris/v1/games/game-malformed-artwork/cover", game.coverUrl)
    }

    @Test
    fun parseGame_withoutArtworkRemainsCompatibleWithOldHosts() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject("{\"id\":\"legacy\",\"cover_url\":\"https://legacy.example/cover.png\"}")
        )

        assertNull(game.artwork)
        assertEquals("https://legacy.example/cover.png", game.coverUrl)
    }

    @Test
    fun artworkUrlSelectionPrefersSanitizedManifestThenLegacyFallbacks() {
        val manifestGame = PolarisGame(
            id = "game-url",
            coverUrl = "/polaris/v1/games/game-url/cover",
            artwork = PolarisGame.ArtworkManifest(
                revision = "rev-1",
                assets = PolarisGame.ArtworkAssets(
                    poster = PolarisGame.ArtworkAsset(
                        url = "/polaris/v1/games/game-url/artwork/poster",
                        cached = true
                    )
                )
            )
        )

        assertEquals(
            "https://polaris.lan:47984/polaris/v1/games/game-url/artwork/poster",
            PolarisApiClient.selectArtworkUrl("polaris.lan", 47984, manifestGame, "poster")
        )

        val absoluteManifest = manifestGame.copy(
            artwork = manifestGame.artwork?.copy(
                assets = PolarisGame.ArtworkAssets(
                    poster = PolarisGame.ArtworkAsset("https://steam.invalid/poster.jpg")
                )
            )
        )
        assertEquals(
            "https://polaris.lan:47984/polaris/v1/games/game-url/cover",
            PolarisApiClient.selectArtworkUrl("polaris.lan", 47984, absoluteManifest, "poster")
        )

        val absoluteLegacy = PolarisGame(
            id = "legacy-hosted",
            coverUrl = "https://provider.example/should-not-leave-the-host.jpg"
        )
        assertEquals(
            "https://polaris.lan:47984/polaris/v1/games/legacy-hosted/cover",
            PolarisApiClient.selectArtworkUrl("polaris.lan", 47984, absoluteLegacy, "poster")
        )

        val legacyEndpoint = PolarisGame(id = "legacy-endpoint")
        assertEquals(
            "https://polaris.lan:47984/polaris/v1/games/legacy-endpoint/cover",
            PolarisApiClient.selectArtworkUrl("polaris.lan", 47984, legacyEndpoint, "poster")
        )
        assertNull(PolarisApiClient.selectArtworkUrl("polaris.lan", 47984, legacyEndpoint, "hero"))

        val uncachedManifest = manifestGame.copy(
            artwork = manifestGame.artwork?.copy(
                assets = PolarisGame.ArtworkAssets(
                    poster = PolarisGame.ArtworkAsset(
                        url = "/polaris/v1/games/game-url/artwork/poster",
                        cached = false
                    )
                )
            )
        )
        assertEquals(
            "https://polaris.lan:47984/polaris/v1/games/game-url/cover",
            PolarisApiClient.selectArtworkUrl("polaris.lan", 47984, uncachedManifest, "poster")
        )
        assertNull(
            PolarisApiClient.selectArtworkUrl(
                "polaris.lan",
                47984,
                PolarisGame(id = "../unsafe", coverUrl = "javascript:bad"),
                "poster"
            )
        )
    }

    @Test
    fun manifestUrlResolutionRejectsAbsoluteProtocolRelativeAndTraversalPaths() {
        assertEquals(
            "https://host.example:444/polaris/v1/games/abc/artwork/logo?revision=2",
            PolarisApiClient.resolveManifestPath(
                "host.example",
                444,
                "/polaris/v1/games/abc/artwork/logo?revision=2"
            )
        )
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "https://sgdb.invalid/logo.png"))
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "//sgdb.invalid/logo.png"))
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "/polaris/v1/../private/logo.png"))
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "/covers/logo.png"))
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "/polaris/v1\\evil"))
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "/polaris/v1/%2e%2e/private/logo.png"))
        assertNull(PolarisApiClient.resolveManifestPath("host.example", 444, "/polaris/v1/%252e%252e/private/logo.png"))
    }

    @Test
    fun artworkCandidatesAreSanitizedAndMatchBodyIsBounded() {
        val response = JSONObject("""{"status":true,"candidates":[
          {"provider":"steamgriddb","provider_game_id":"12345","title":"Portal 2","steam_appid":"620","release_year":2011,"confidence":0.98,
           "preview":{"poster":"/polaris/v1/games/game-1/artwork/candidate/00000000000000000000000000000001/poster"}},
          {"provider":"evil","provider_game_id":"9","title":"Bad"},
          {"provider":"steamgriddb","provider_game_id":"../1","title":"Bad"},
          {"provider":"steamgriddb","provider_game_id":"7","title":"Absolute","preview":{"poster":"https://cdn.invalid/poster.png"}},
          {"provider":"steamgriddb","provider_game_id":"12345","title":"Portal II"}
        ]}""")

        val candidates = PolarisApiClient.parseArtworkCandidates(response, "game-1", "polaris.lan", 47984)
        assertEquals(2, candidates.size)
        val first = candidates.first()
        assertEquals("12345", first.providerGameId)
        assertEquals("https://polaris.lan:47984/polaris/v1/games/game-1/artwork/candidate/00000000000000000000000000000001/poster", first.posterPreviewUrl)
        assertNull(candidates.last().posterPreviewUrl)

        val body = PolarisApiClient.buildArtworkMatchBody(first, listOf("poster", "hero", "logo", "icon"))
        assertEquals(setOf("provider", "provider_game_id", "title", "steam_appid", "kinds"), body.keys().asSequence().toSet())
        assertEquals(4, body.getJSONArray("kinds").length())
        assertFalse(body.toString().contains("preview"))
        assertFalse(body.toString().contains("api_key"))
        var invalidKindsRejected = false
        try { PolarisApiClient.buildArtworkMatchBody(first, listOf("poster", "poster")) }
        catch (_: IllegalArgumentException) { invalidKindsRejected = true }
        assertTrue(invalidKindsRejected)
    }

    @Test
    fun artworkChoicesAreKindBoundAndSelectionBodiesContainOnlyOpaqueTokens() {
        val candidate = PolarisArtworkMatchCandidate(
            provider = "steamgriddb",
            providerGameId = "12345",
            title = "Portal 2",
            steamAppid = "620",
        )
        val token = "00000000000000000000000000000002"
        val choicesJson = JSONObject("""{"status":true,"kind":"hero","choices":[
          {"selection_token":"$token","preview":"/polaris/v1/games/game-1/artwork/candidate/$token/hero","expires_at":123456},
          {"selection_token":"ABCDEF00000000000000000000000000","preview":"/polaris/v1/games/game-1/artwork/candidate/ABCDEF00000000000000000000000000/hero","expires_at":123456},
          {"selection_token":"00000000000000000000000000000003","preview":"https://provider.invalid/hero.png","expires_at":123456}
        ]}""")

        val choices = PolarisApiClient.parseArtworkChoices(
            choicesJson, "game-1", "hero", "polaris.lan", 47984,
        )
        assertEquals(1, choices.size)
        val hero = choices.single()
        assertEquals("hero", hero.kind)
        assertEquals(token, hero.selectionToken)
        assertEquals(
            "https://polaris.lan:47984/polaris/v1/games/game-1/artwork/candidate/$token/hero",
            hero.previewUrl,
        )

        val identityBody = PolarisApiClient.buildArtworkChoiceBody(candidate)
        assertEquals(setOf("provider", "provider_game_id", "title", "steam_appid"), identityBody.keys().asSequence().toSet())
        assertFalse(identityBody.toString().contains("preview"))

        val posterToken = "00000000000000000000000000000004"
        val poster = PolarisArtworkChoice(
            kind = "poster",
            selectionToken = posterToken,
            previewUrl = "https://polaris.lan:47984/polaris/v1/games/game-1/artwork/candidate/$posterToken/poster",
            expiresAt = 123456,
        )
        val selectionBody = PolarisApiClient.buildArtworkSelectionBody(
            candidate,
            mapOf("poster" to poster, "hero" to hero),
        )
        assertEquals(setOf("provider", "provider_game_id", "title", "steam_appid", "selections"), selectionBody.keys().asSequence().toSet())
        assertEquals(
            setOf("poster", "hero"),
            selectionBody.getJSONObject("selections").keys().asSequence().toSet(),
        )
        assertEquals(posterToken, selectionBody.getJSONObject("selections").getString("poster"))
        assertEquals(token, selectionBody.getJSONObject("selections").getString("hero"))
        assertFalse(selectionBody.toString().contains("https://"))
        assertFalse(selectionBody.toString().contains("preview"))

        var emptySetRejected = false
        try { PolarisApiClient.buildArtworkSelectionBody(candidate, emptyMap()) }
        catch (_: IllegalArgumentException) { emptySetRejected = true }
        assertTrue(emptySetRejected)

        var mismatchedKindRejected = false
        try { PolarisApiClient.buildArtworkSelectionBody(candidate, mapOf("poster" to hero)) }
        catch (_: IllegalArgumentException) { mismatchedKindRejected = true }
        assertTrue(mismatchedKindRejected)
        assertTrue(PolarisApiClient.parseArtworkChoices(
            choicesJson, "game-1", "poster", "polaris.lan", 47984,
        ).isEmpty())
    }

    @Test
    fun artworkLibraryUpdateBodyAndResultAreStrictAndPrivacySafe() {
        val body = PolarisApiClient.buildArtworkLibraryUpdateBody()
        assertEquals(setOf("policy"), body.keys().asSequence().toSet())
        assertEquals("missing_or_stale", body.getString("policy"))

        val response = JSONObject("""{
          "version":1,"revision":"rev-2","assets":{},
          "resolution":{"status":"partial_failure","requested_kinds":["poster","hero"],"remaining_kinds":["hero"]}
        }""")
        val result = requireNotNull(PolarisApiClient.parseArtworkLibraryUpdateResponse(response))
        assertEquals(PolarisArtworkUpdateStatus.PARTIAL_FAILURE, result.status)
        assertEquals(listOf("poster", "hero"), result.requestedKinds)
        assertEquals(listOf("hero"), result.remainingKinds)
        assertEquals("rev-2", result.manifest.revision)

        assertNull(PolarisApiClient.parseArtworkLibraryUpdateResponse(JSONObject("""{
          "version":1,"revision":"bad","assets":{},
          "resolution":{"status":"updated","requested_kinds":["poster"],"remaining_kinds":["logo"]}
        }""")))
        assertNull(PolarisApiClient.parseArtworkLibraryUpdateResponse(JSONObject("""{
          "version":1,"revision":"bad","assets":{},
          "resolution":{"status":"provider_url","requested_kinds":[],"remaining_kinds":[]}
        }""")))
    }

    @Test
    fun artworkCandidateEnvelopeRejectsApplicationFailuresButAllowsTrueEmptyResults() {
        val validEmpty = JSONObject("{\"status\":true,\"candidates\":[]}")
        assertTrue(PolarisApiClient.parseArtworkCandidates(validEmpty, "game-1", "polaris.lan", 47984).isEmpty())

        val invalidEnvelopes = listOf(
            JSONObject("{\"status\":false,\"candidates\":[]}"),
            JSONObject("{\"candidates\":[]}"),
            JSONObject("{\"status\":true}"),
            JSONObject("{\"status\":true,\"candidates\":{}}"),
        )
        for (response in invalidEnvelopes) {
            val failure = try {
                PolarisApiClient.parseArtworkCandidates(response, "game-1", "polaris.lan", 47984)
                null
            } catch (e: IOException) {
                e
            }
            assertEquals("invalid artwork candidate search response", requireNotNull(failure).message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun artworkCandidateContractRejectsUtf8ByteOverflowAndForgedApplyBodies() {
        val oversizedTitle = "é".repeat(81)
        val response = JSONObject("""{"status":true,"candidates":[{"provider":"steamgriddb","provider_game_id":"1","title":"$oversizedTitle"}]}""")
        assertTrue(PolarisApiClient.parseArtworkCandidates(response, "game-1", "polaris.lan", 47984).isEmpty())
        val forged = PolarisArtworkMatchCandidate("evil", "../1", "Bad\nTitle")
        var rejected = false
        try { PolarisApiClient.buildArtworkMatchBody(forged, listOf("poster")) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun artworkResolveResponseAcceptsDirectAndNestedEnvelopes() {
        val direct = PolarisApiClient.parseArtworkResolveResponse(
            JSONObject("{\"version\":1,\"revision\":\"direct\",\"assets\":{\"poster\":{\"url\":\"/polaris/v1/games/a/artwork/poster\"}}}")
        )
        val nested = PolarisApiClient.parseArtworkResolveResponse(
            JSONObject("{\"data\":{\"game\":{\"artwork\":{\"version\":1,\"revision\":\"nested\",\"assets\":{\"poster\":{\"url\":\"/polaris/v1/games/b/artwork/poster\"}}}}}}")
        )

        val envelope = PolarisApiClient.parseArtworkResolveResponse(
            JSONObject("{\"status\":true,\"artwork\":{\"version\":1,\"revision\":\"envelope\",\"assets\":{}}}")
        )

        assertEquals("direct", direct?.revision)
        assertEquals("nested", nested?.revision)
        assertEquals("envelope", envelope?.revision)
        assertNull(PolarisApiClient.parseArtworkResolveResponse(JSONObject("{\"success\":true}")))
    }

    @Test
    fun buildMangoHudUpdateBody_includesGameAndExplicitOff() {
        val body = PolarisApiClient.buildMangoHudUpdateBody("game-1", false)

        assertEquals("game-1", body.getString("game_id"))
        assertFalse(body.getBoolean("mangohud"))
    }

    @Test
    fun buildSteamLaunchModeUpdateBody_includesGameAndMode() {
        val body = PolarisApiClient.buildSteamLaunchModeUpdateBody("game-1", "big-picture")

        assertEquals("game-1", body.getString("game_id"))
        assertEquals("big-picture", body.getString("mode"))
    }

    @Test
    fun buildOptimizationPath_carriesExplicitLaunchLocksWithoutATrialQuery() {
        val path = PolarisApiClient.buildOptimizationPath(
            device = "RetroidPocket6",
            game = "Black Myth: Wukong",
            preference = "high_fps",
            width = 1920,
            height = 1080,
            fps = 120f,
            displayLocked = true,
            bitrateKbps = 40000,
            bitrateLocked = true,
            hdr = false,
        )

        assertEquals(
            "/optimize?device=RetroidPocket6&game=Black+Myth%3A+Wukong&preference=high_fps" +
                "&width=1920&height=1080&fps=120.0&display_locked=1" +
                "&bitrate_kbps=40000&bitrate_locked=1&hdr=0",
            path
        )
        assertFalse(path.contains("trial="))
    }

    @Test
    fun buildOptimizationPath_carriesTheModeBucketAndOmitsItWhenBlank() {
        val withMode = PolarisApiClient.buildOptimizationPath(
            device = "RetroidPocket6",
            game = "Control Ultimate Edition",
            preference = "auto",
            mode = "gamescope_stream"
        )

        assertEquals(
            "/optimize?device=RetroidPocket6&game=Control+Ultimate+Edition&preference=auto&mode=gamescope_stream",
            withMode
        )

        val legacy = PolarisApiClient.buildOptimizationPath(
            device = "RetroidPocket6",
            game = "Control Ultimate Edition",
            preference = "auto"
        )

        assertEquals(
            "/optimize?device=RetroidPocket6&game=Control+Ultimate+Edition&preference=auto",
            legacy
        )
    }
    @Test
    fun buildSteamLaunchModeUpdateBodyNormalizesAliases() {
        val body = PolarisApiClient.buildSteamLaunchModeUpdateBody("game-1", "gamepadui")

        assertEquals("game-1", body.getString("game_id"))
        assertEquals("big-picture", body.getString("mode"))
    }

    @Test
    fun playTimeIsCarriedFromTheHostAndAbsentIsNotZero() {
        val played = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """{id:abc,name:Control,play_time:{seconds:143520,source:steam,read_at:1754470000}}"""
            )
        )
        assertEquals(143520L, played.playTime?.seconds)
        assertEquals("steam", played.playTime?.source)

        // No launcher owns the answer: null, so the gauge can be omitted rather than
        // drawn claiming nobody has played it.
        val unowned = PolarisGameJsonAdapter.fromJson(JSONObject("""{id:abc,name:Control}"""))
        assertNull(unowned.playTime)

        // Owned, but never played, is a different answer and keeps its object.
        val untouched = PolarisGameJsonAdapter.fromJson(
            JSONObject("""{id:abc,name:Control,play_time:{seconds:0,source:steam}}""")
        )
        assertNotNull(untouched.playTime)
        assertEquals(0L, untouched.playTime?.seconds)
    }
}
