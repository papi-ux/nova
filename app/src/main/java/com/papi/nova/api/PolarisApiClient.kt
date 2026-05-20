package com.papi.nova.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import com.papi.nova.binding.PlatformBinding
import android.widget.ImageView
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import org.json.JSONObject
import java.net.Proxy
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import androidx.collection.LruCache

/**
 * HTTP client for Polaris REST API on the nvhttp port (47984).
 * Uses the same client certificate as Moonlight pairing.
 */
class PolarisApiClient @JvmOverloads constructor(
    context: Context,
    private val serverAddress: String,
    private val httpsPort: Int = 47984,
	private val pinnedServerCert: X509Certificate? = null
) {

    constructor(context: Context, serverAddress: String, httpsPort: Int, serverCertDer: ByteArray?) :
        this(context, serverAddress, httpsPort, decodeCertificate(serverCertDer))

    @JvmField val client: OkHttpClient
    private var apiKeyManager: X509KeyManager? = null
    private var apiTrustManager: X509TrustManager? = null
    private val resolvedHttpsPort = if (httpsPort > 0) httpsPort else 47984
    private val baseUrl = "https://$serverAddress:$resolvedHttpsPort/polaris/v1"
    private val webBaseUrl = "https://$serverAddress:$WEB_UI_HTTPS_PORT"
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val coverCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val WEB_UI_HTTPS_PORT = 47990
        private const val CLIENT_CERT_ALIAS = "Limelight-RSA"

        @JvmStatic
        fun decodeCertificate(serverCertDer: ByteArray?): X509Certificate? {
            if (serverCertDer == null) return null
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(serverCertDer)) as X509Certificate
        }

        private fun parseStringArray(array: org.json.JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }

        private fun parseModeOptions(array: org.json.JSONArray?): List<PolarisClientSettings.ModeOption> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { mode ->
                    PolarisClientSettings.ModeOption(
                        value = mode.optString("value", ""),
                        label = mode.optString("label", ""),
                        available = mode.optBoolean("available", true),
                        restartRequired = mode.optBoolean("restart_required", true),
                        reason = mode.optString("reason", "")
                    )
                }
            }
        }

        @JvmStatic
        fun parseClientSettingsResponse(json: JSONObject): PolarisClientSettings {
            val settingsJson = json.optJSONObject("client_settings") ?: json
            val desired = settingsJson.optJSONObject("desired")
            val effective = settingsJson.optJSONObject("effective")
            val capabilities = settingsJson.optJSONObject("capabilities")

            return PolarisClientSettings(
                version = settingsJson.optInt("version", 1),
                revision = settingsJson.optString("revision", ""),
                desired = PolarisClientSettings.Desired(
                    streamDisplayMode = desired?.optString("stream_display_mode", "") ?: "",
                    streamDisplayModeLabel = desired?.optString("stream_display_mode_label", "") ?: "",
                    streamDisplayModeReason = desired?.optString("stream_display_mode_reason", "") ?: "",
                    displayMode = desired?.optString("display_mode", "") ?: "",
                    targetBitrateKbps = desired?.optInt("target_bitrate_kbps", 0) ?: 0,
                    aiAutoQualityEnabled = desired?.let {
                        it.optBoolean(
                            "ai_auto_quality_enabled",
                            it.optBoolean("ai_optimizer_enabled", false) ||
                                it.optBoolean("adaptive_bitrate_enabled", false)
                        )
                    } ?: false,
                    adaptiveBitrateEnabled = desired?.optBoolean("adaptive_bitrate_enabled", false) ?: false,
                    aiOptimizerEnabled = desired?.optBoolean("ai_optimizer_enabled", false) ?: false,
                    disconnectResumeTimeoutSeconds = desired?.optInt("disconnect_resume_timeout_seconds", 300) ?: 300
                ),
                effective = PolarisClientSettings.Effective(
                    streamDisplayMode = effective?.optString("stream_display_mode", "") ?: "",
                    streamDisplayModeLabel = effective?.optString("stream_display_mode_label", "") ?: "",
                    streamDisplayModeReason = effective?.optString("stream_display_mode_reason", "") ?: "",
                    displayMode = effective?.optString("display_mode", "") ?: "",
                    targetBitrateKbps = effective?.optInt("target_bitrate_kbps", 0) ?: 0,
                    aiAutoQualityEnabled = effective?.let {
                        it.optBoolean(
                            "ai_auto_quality_enabled",
                            it.optBoolean("ai_optimizer_enabled", false) ||
                                it.optBoolean("adaptive_bitrate_enabled", false)
                        )
                    } ?: false,
                    adaptiveBitrateEnabled = effective?.optBoolean("adaptive_bitrate_enabled", false) ?: false,
                    adaptiveTargetBitrateKbps = effective?.optInt("adaptive_target_bitrate_kbps", 0) ?: 0,
                    aiOptimizerEnabled = effective?.optBoolean("ai_optimizer_enabled", false) ?: false,
                    disconnectResumeTimeoutSeconds = effective?.optInt("disconnect_resume_timeout_seconds", 300) ?: 300,
                    capturePath = effective?.optString("capture_path", "") ?: "",
                    captureGpuNative = effective?.optBoolean("capture_gpu_native", false) ?: false
                ),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = parseModeOptions(capabilities?.optJSONArray("modes")),
                    displayModeOverride = capabilities?.optBoolean("display_mode_override", false) ?: false,
                    targetBitrateOverride = capabilities?.optBoolean("target_bitrate_override", false) ?: false,
                    aiAutoQualityControl = capabilities?.let {
                        when {
                            it.has("ai_auto_quality_control") -> it.optBoolean("ai_auto_quality_control", false)
                            else -> it.optBoolean("ai_optimizer_control", false) ||
                                it.optBoolean("adaptive_bitrate_control", false)
                        }
                    } ?: false,
                    adaptiveBitrateControl = capabilities?.optBoolean("adaptive_bitrate_control", false) ?: false,
                    aiOptimizerControl = capabilities?.optBoolean("ai_optimizer_control", false) ?: false,
                    disconnectResumeTimeoutControl = capabilities?.optBoolean("disconnect_resume_timeout_control", false) ?: false
                ),
                relaunchRequired = settingsJson.optBoolean("relaunch_required", false)
            )
        }

        @JvmStatic
        fun buildClientSettingsUpdateBody(
            streamDisplayMode: String? = null,
            displayMode: String? = null,
            clearDisplayMode: Boolean = false,
            targetBitrateKbps: Int? = null,
            clearTargetBitrate: Boolean = false,
            adaptiveBitrateEnabled: Boolean? = null,
            aiOptimizerEnabled: Boolean? = null,
            aiAutoQualityEnabled: Boolean? = null,
            disconnectResumeTimeoutSeconds: Int? = null
        ): JSONObject {
            return JSONObject().apply {
                streamDisplayMode?.let { put("stream_display_mode", it) }
                displayMode?.let { put("display_mode", it) }
                if (clearDisplayMode) put("clear_display_mode", true)
                targetBitrateKbps?.let { put("target_bitrate_kbps", it) }
                if (clearTargetBitrate) put("clear_target_bitrate", true)
                aiAutoQualityEnabled?.let {
                    put("ai_auto_quality_enabled", it)
                    if (adaptiveBitrateEnabled == null) put("adaptive_bitrate_enabled", it)
                    if (aiOptimizerEnabled == null) put("ai_optimizer_enabled", it)
                }
                adaptiveBitrateEnabled?.let { put("adaptive_bitrate_enabled", it) }
                aiOptimizerEnabled?.let { put("ai_optimizer_enabled", it) }
                disconnectResumeTimeoutSeconds?.let { put("disconnect_resume_timeout_seconds", it) }
            }
        }

        @JvmStatic
        fun buildOptimizerProfileClearBody(device: String, game: String): JSONObject {
            return JSONObject().apply {
                put("device", device)
                put("game", game)
            }
        }

        @JvmStatic
        fun buildMangoHudUpdateBody(gameId: String, enabled: Boolean): JSONObject {
            return JSONObject().apply {
                put("game_id", gameId)
                put("mangohud", enabled)
            }
        }

        @JvmStatic
        fun buildSteamLaunchModeUpdateBody(gameId: String, mode: String): JSONObject {
            return JSONObject().apply {
                put("game_id", gameId)
                put("mode", PolarisGame.SteamLaunchContract.normalizeMode(mode))
            }
        }

        private fun parseSyncValues(json: JSONObject?): PolarisSessionStatus.SyncValues {
            if (json == null) return PolarisSessionStatus.SyncValues()
            return PolarisSessionStatus.SyncValues(
                streamDisplayMode = json.optString("stream_display_mode", ""),
                displayMode = json.optString("display_mode", ""),
                targetBitrateKbps = json.optInt("target_bitrate_kbps", 0),
                adaptiveTargetBitrateKbps = json.optInt("adaptive_target_bitrate_kbps", 0),
                adaptiveBitrateEnabled = json.optBoolean("adaptive_bitrate_enabled", false),
                aiOptimizerEnabled = json.optBoolean("ai_optimizer_enabled", false),
                preferredCodec = json.optString("preferred_codec", ""),
                hdr = if (json.has("hdr")) json.optBoolean("hdr") else null
            )
        }

        private fun parseAutoQualityPolicy(json: JSONObject?): PolarisSessionStatus.AutoQualityPolicy {
            if (json == null) return PolarisSessionStatus.AutoQualityPolicy()
            val suggested = json.optJSONObject("suggested_profile")
            val components = json.optJSONObject("components")
            return PolarisSessionStatus.AutoQualityPolicy(
                enabled = json.optBoolean(
                    "enabled",
                    components?.optBoolean("optimizer_active", false) == true ||
                        components?.optBoolean("adaptive_bitrate_active", false) == true
                ),
                state = json.optString("state", ""),
                blockedReason = json.optString("blocked_reason", "none"),
                liveBitrateKbps = json.optInt("live_bitrate_kbps", 0),
                qualityCapKbps = json.optInt("quality_cap_kbps", 0),
                adaptiveBitrateActive = components?.optBoolean("adaptive_bitrate_active", false) ?: false,
                optimizerActive = components?.optBoolean("optimizer_active", false) ?: false,
                adaptiveState = components?.optString("adaptive_state", "") ?: "",
                adaptiveReason = components?.optString("adaptive_reason", "") ?: "",
                relaunchRequired = json.optBoolean("relaunch_required", false),
                canRecoverLive = json.optBoolean("can_recover_live", false),
                summary = json.optString("summary", ""),
                detail = json.optString("detail", ""),
                suggestedTargetFps = suggested?.optDouble("target_fps", 0.0) ?: 0.0,
                suggestedBitrateKbps = suggested?.optInt("target_bitrate_kbps", 0) ?: 0,
                suggestedCodec = suggested?.optString("preferred_codec", "") ?: "",
                suggestedDisplayMode = suggested?.optString("display_mode", "") ?: "",
                suggestedHdr = if (suggested?.has("hdr") == true) suggested.optBoolean("hdr") else null
            )
        }

        private fun parseProfileState(json: JSONObject?): PolarisSessionStatus.ProfileState {
            if (json == null) return PolarisSessionStatus.ProfileState()
            val currentProfile = json.optJSONObject("current_profile")
            val lastResult = json.optJSONObject("last_result")
            val actions = json.optJSONObject("actions")
            return PolarisSessionStatus.ProfileState(
                state = json.optString("state", ""),
                label = json.optString("label", ""),
                reason = json.optString("reason", ""),
                source = json.optString("source", ""),
                cacheStatus = json.optString("cache_status", ""),
                confidence = json.optString("confidence", ""),
                preference = json.optString("preference", "auto"),
                preferenceLabel = json.optString("preference_label", "Auto"),
                preferenceApplied = json.optBoolean("preference_applied", true),
                preferenceNote = json.optString("preference_note", ""),
                currentProfile = PolarisSessionStatus.ProfileState.ProfileValues(
                    displayMode = currentProfile?.optString("display_mode", "") ?: "",
                    targetBitrateKbps = currentProfile?.optInt("target_bitrate_kbps", 0) ?: 0,
                    targetFps = currentProfile?.optDouble("target_fps", 0.0) ?: 0.0,
                    preferredCodec = currentProfile?.optString("preferred_codec", "") ?: "",
                    hdr = if (currentProfile?.has("hdr") == true) currentProfile.optBoolean("hdr") else null
                ),
                lastResult = PolarisSessionStatus.ProfileState.LastResult(
                    grade = lastResult?.optString("grade", "") ?: "",
                    sessionCount = lastResult?.optInt("session_count", 0) ?: 0,
                    deliveredFps = lastResult?.optDouble("delivered_fps", 0.0) ?: 0.0,
                    targetFps = lastResult?.optDouble("target_fps", 0.0) ?: 0.0,
                    lowOnePercentFps = lastResult?.optDouble("low_1_percent_fps", 0.0) ?: 0.0,
                    minFps = lastResult?.optDouble("min_fps", 0.0) ?: 0.0,
                    framePacingBadPct = lastResult?.optDouble("frame_pacing_bad_pct", 0.0) ?: 0.0,
                    primaryIssue = lastResult?.optString("primary_issue", "") ?: "",
                    sampleConfidence = lastResult?.optString("sample_confidence", "") ?: "",
                    updatedAt = lastResult?.optLong("updated_at", 0L) ?: 0L
                ),
                actions = PolarisSessionStatus.ProfileState.Actions(
                    canReset = actions?.optBoolean("can_reset", false) ?: false,
                    canRetryQuality = actions?.optBoolean("can_retry_quality", false) ?: false,
                    canKeepRecovery = actions?.optBoolean("can_keep_recovery", false) ?: false,
                    canChangePreference = actions?.optBoolean("can_change_preference", true) ?: true
                )
            )
        }

        @JvmStatic
        fun parseUnlockResponse(json: JSONObject): Boolean =
            json.optBoolean("success", false)

        @JvmStatic
        fun parseCapabilitiesResponse(json: JSONObject): PolarisCapabilities {
            val features = json.optJSONObject("features")
            val capture = json.optJSONObject("capture")
            val aiAutoQuality = features?.let {
                if (it.has("ai_auto_quality")) {
                    it.optBoolean("ai_auto_quality", false)
                } else {
                    it.optBoolean("ai_optimizer", false)
                }
            } ?: false
            val aiAutoQualityControl = features?.let {
                if (it.has("ai_auto_quality_control")) {
                    it.optBoolean("ai_auto_quality_control", false)
                } else {
                    it.optBoolean("ai_optimizer_control", false) ||
                        it.optBoolean("adaptive_bitrate_control", false)
                }
            } ?: false

            return PolarisCapabilities(
                server = json.optString("server", ""),
                version = json.optString("version", ""),
                features = PolarisCapabilities.Features(
                    aiOptimizer = features?.optBoolean("ai_optimizer") ?: false,
                    aiAutoQuality = aiAutoQuality,
                    aiAutoQualityControl = aiAutoQualityControl,
                    aiOptimizerControl = features?.optBoolean("ai_optimizer_control") ?: false,
                    adaptiveBitrateControl = features?.optBoolean("adaptive_bitrate_control") ?: false,
                    gameLibrary = features?.optBoolean("game_library") ?: false,
                    sessionLifecycle = features?.optBoolean("session_lifecycle") ?: false,
                    deviceProfiles = features?.optBoolean("device_profiles") ?: false,
                    streamPolicy = features?.optBoolean("stream_policy_v1") ?: false,
                    clientSettings = features?.optBoolean("client_settings_v1") ?: false,
                    optimizerSync = features?.optBoolean("optimizer_sync_v1") ?: false,
                    lockScreenControl = features?.optBoolean("lock_screen_control") ?: false,
                    cursorVisibilityControl = features?.optBoolean("cursor_visibility_control") ?: false
                ),
                capture = PolarisCapabilities.CaptureInfo(
                    backend = capture?.optString("backend", "") ?: "",
                    compositor = capture?.optString("compositor", "") ?: "",
                    maxResolution = capture?.optString("max_resolution", "") ?: "",
                    maxFps = capture?.optInt("max_fps", 0) ?: 0,
                    codecs = capture?.optJSONArray("codecs")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                )
            )
        }

        @JvmStatic
        fun parseSessionStatusResponse(json: JSONObject): PolarisSessionStatus {
            val controls = json.optJSONObject("controls")
            val tuning = json.optJSONObject("tuning")
            val displayMode = json.optJSONObject("display_mode")
            val streamPolicy = json.optJSONObject("stream_policy")
            val presentationPolicy = streamPolicy?.optJSONObject("presentation_policy")
            val clientSettings = json.optJSONObject("client_settings")
            val syncStatus = json.optJSONObject("sync_status")
                ?: clientSettings?.optJSONObject("sync_status")
            val clientPresentationField = syncStatus
                ?.optJSONObject("fields")
                ?.optJSONObject("client_presentation")
            val clientPresentationEffective = clientPresentationField?.optJSONObject("effective")
                ?: clientSettings?.optJSONObject("effective")?.optJSONObject("client_presentation")
            val clientPresentationDesired = clientPresentationField?.optJSONObject("desired")
                ?: presentationPolicy
            val desiredSettings = clientSettings?.optJSONObject("desired")
            val effectiveSettings = clientSettings?.optJSONObject("effective")
            val appliedSettings = syncStatus?.optJSONObject("applied_stream_settings")
                ?: effectiveSettings?.optJSONObject("applied_stream_settings")
            val capture = json.optJSONObject("capture")
            val encoder = json.optJSONObject("encoder")
            val health = json.optJSONObject("health")
            val autoQuality = json.optJSONObject("auto_quality")
                ?: health?.optJSONObject("recovery_policy")
            val profileState = json.optJSONObject("profile_state")

            return PolarisSessionStatus(
                state = json.optString("state", "unknown"),
                streamingActive = json.optBoolean("streaming_active", false),
                shutdownRequested = json.optBoolean("shutdown_requested", false),
                game = json.optString("game", ""),
                gameId = json.optInt("game_id", 0),
                gameUuid = json.optString("game_uuid", ""),
                sessionToken = json.optString("session_token", ""),
                ownerUniqueId = json.optString("owner_unique_id", ""),
                ownerDeviceName = json.optString("owner_device_name", ""),
                clientRole = json.optString("client_role", "none"),
                viewerCount = json.optInt("viewer_count", 0),
                ownedByClient = json.optBoolean("owned_by_client", false),
                cagePid = json.optInt("cage_pid", 0),
                screenLocked = json.optBoolean("screen_locked", false),
                cursorVisible = json.optBoolean("cursor_visible", false),
                dynamicRange = json.optInt("dynamic_range", 0),
                adaptiveBitrateEnabled = json.optBoolean("adaptive_bitrate_enabled", false),
                adaptiveTargetBitrateKbps = json.optInt("adaptive_target_bitrate_kbps", 0),
                aiAutoQualityEnabled = json.optBoolean(
                    "ai_auto_quality_enabled",
                    json.optBoolean("ai_optimizer_enabled", false) ||
                        json.optBoolean("adaptive_bitrate_enabled", false)
                ),
                aiOptimizerEnabled = json.optBoolean("ai_optimizer_enabled", false),
                mangohudConfigured = json.optBoolean("mangohud_configured", false),
                controls = PolarisSessionStatus.ControlsStatus(
                    hostTuningAllowed = controls?.optBoolean("host_tuning_allowed", false) ?: false,
                    quitAllowed = controls?.optBoolean("quit_allowed", false) ?: false,
                    shutdownInProgress = controls?.optBoolean("shutdown_in_progress", false) ?: false,
                    clientCommandsEnabled = controls?.optBoolean("client_commands_enabled", false) ?: false,
                    deviceCommandsEnabled = controls?.optBoolean("device_commands_enabled", false) ?: false
                ),
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = tuning?.optBoolean("adaptive_bitrate_enabled", false)
                        ?: json.optBoolean("adaptive_bitrate_enabled", false),
                    adaptiveTargetBitrateKbps = tuning?.optInt("adaptive_target_bitrate_kbps", 0)
                        ?: json.optInt("adaptive_target_bitrate_kbps", 0),
                    adaptiveBaseBitrateKbps = tuning?.optInt("adaptive_base_bitrate_kbps", 0) ?: 0,
                    adaptiveMinBitrateKbps = tuning?.optInt("adaptive_min_bitrate_kbps", 0) ?: 0,
                    adaptiveMaxBitrateKbps = tuning?.optInt("adaptive_max_bitrate_kbps", 0) ?: 0,
                    adaptiveBitrateState = tuning?.optString("adaptive_bitrate_state", "")
                        ?: json.optString("adaptive_bitrate_state", ""),
                    adaptiveBitrateReason = tuning?.optString("adaptive_bitrate_reason", "")
                        ?: json.optString("adaptive_bitrate_reason", ""),
                    aiAutoQualityEnabled = tuning?.optBoolean(
                        "ai_auto_quality_enabled",
                        json.optBoolean("ai_auto_quality_enabled",
                            json.optBoolean("ai_optimizer_enabled", false) ||
                                json.optBoolean("adaptive_bitrate_enabled", false))
                    ) ?: json.optBoolean(
                        "ai_auto_quality_enabled",
                        json.optBoolean("ai_optimizer_enabled", false) ||
                            json.optBoolean("adaptive_bitrate_enabled", false)
                    ),
                    aiOptimizerEnabled = tuning?.optBoolean("ai_optimizer_enabled", false)
                        ?: json.optBoolean("ai_optimizer_enabled", false),
                    mangohudConfigured = tuning?.optBoolean("mangohud_configured", false)
                        ?: json.optBoolean("mangohud_configured", false)
                ),
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    label = displayMode?.optString("label", "") ?: "",
                    selection = displayMode?.optString("selection", "") ?: "",
                    requested = displayMode?.optString("requested", "") ?: "",
                    explicitChoice = displayMode?.optBoolean("explicit_choice", false) ?: false,
                    virtualDisplay = displayMode?.optBoolean("virtual_display", false) ?: false,
                    requestedHeadless = displayMode?.optBoolean("requested_headless", false) ?: false,
                    effectiveHeadless = displayMode?.optBoolean("effective_headless", false) ?: false,
                    gpuNativeOverrideActive = displayMode?.optBoolean("gpu_native_override_active", false) ?: false
                ),
                presentationPolicy = PolarisSessionStatus.PresentationPolicy(
                    version = presentationPolicy?.optInt("version", 0) ?: 0,
                    targetRefreshRateHz = presentationPolicy?.optDouble("target_refresh_rate_hz", 0.0) ?: 0.0,
                    refreshRatePolicy = presentationPolicy?.optString("refresh_rate_policy", "") ?: "",
                    allowDisplayModeChange = presentationPolicy?.optBoolean("allow_display_mode_change", false) ?: false,
                    internalDisplayOnly = presentationPolicy?.optBoolean("internal_display_only", true) ?: true,
                    reason = presentationPolicy?.optString("reason", "") ?: ""
                ),
                clientPresentation = PolarisSessionStatus.ClientPresentationStatus(
                    status = clientPresentationField?.optString("status", "")
                        ?: clientPresentationEffective?.optString("status", "")
                        ?: "",
                    appliedRefreshRateHz = clientPresentationEffective?.optDouble("applied_refresh_rate_hz", 0.0) ?: 0.0,
                    targetRefreshRateHz = (clientPresentationEffective?.optDouble("target_refresh_rate_hz", 0.0) ?: 0.0)
                        .takeIf { it > 0.0 }
                        ?: clientPresentationDesired?.optDouble("target_refresh_rate_hz", 0.0)
                        ?: 0.0,
                    refreshRatePolicy = clientPresentationEffective?.optString("refresh_rate_policy", "")
                        ?.takeIf { it.isNotBlank() }
                        ?: clientPresentationDesired?.optString("refresh_rate_policy", "")
                        ?: "",
                    displayMode = clientPresentationEffective?.optString("display_mode", "") ?: "",
                    decoder = clientPresentationEffective?.optString("decoder", "") ?: "",
                    framePacingState = clientPresentationEffective?.optString("frame_pacing_state", "") ?: "",
                    reason = clientPresentationEffective?.optString("reason", "")
                        ?: clientPresentationField?.optString("message", "")
                        ?: ""
                ),
                syncStatus = PolarisSessionStatus.SyncStatus(
                    available = syncStatus?.optBoolean("available", false) ?: false,
                    version = syncStatus?.optInt("version", 0) ?: 0,
                    state = syncStatus?.optString("state", "") ?: "",
                    legacyState = syncStatus?.optString("legacy_state", "") ?: "",
                    message = syncStatus?.optString("message", "") ?: "",
                    sourceOfTruth = syncStatus?.optString("source_of_truth", "") ?: "",
                    syncMode = syncStatus?.optString("sync_mode", desiredSettings?.optString("sync_mode", "") ?: "") ?: "",
                    manualOverride = syncStatus?.optBoolean("manual_override", desiredSettings?.optBoolean("manual_override", false) ?: false)
                        ?: false,
                    desired = parseSyncValues(desiredSettings),
                    effective = parseSyncValues(effectiveSettings),
                    applied = parseSyncValues(appliedSettings)
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    backend = capture?.optString("backend", "") ?: "",
                    resolution = capture?.optString("resolution", "") ?: "",
                    transport = capture?.optString("transport", "") ?: "",
                    residency = capture?.optString("residency", "") ?: "",
                    format = capture?.optString("format", "") ?: ""
                ),
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = encoder?.optString("codec", "") ?: "",
                    bitrateKbps = encoder?.optInt("bitrate_kbps", 0) ?: 0,
                    fps = encoder?.optDouble("fps", 0.0) ?: 0.0,
                    requestedClientFps = encoder?.optDouble("requested_client_fps", 0.0) ?: 0.0,
                    sessionTargetFps = encoder?.optDouble("session_target_fps", 0.0) ?: 0.0,
                    encodeTargetFps = encoder?.optDouble("encode_target_fps", 0.0) ?: 0.0,
                    pacingPolicy = encoder?.optString("pacing_policy", "") ?: "",
                    optimizationSource = encoder?.optString("optimization_source", "") ?: "",
                    optimizationConfidence = encoder?.optString("optimization_confidence", "") ?: "",
                    optimizationCacheStatus = encoder?.optString("optimization_cache_status", "") ?: "",
                    optimizationReasoning = encoder?.optString("optimization_reasoning", "") ?: "",
                    optimizationNormalizationReason = encoder?.optString("optimization_normalization_reason", "") ?: "",
                    recommendationVersion = encoder?.optInt("recommendation_version", 0) ?: 0,
                    targetDevice = encoder?.optString("target_device", "") ?: "",
                    targetResidency = encoder?.optString("target_residency", "") ?: "",
                    targetFormat = encoder?.optString("target_format", "") ?: ""
                ),
                autoQuality = parseAutoQualityPolicy(autoQuality),
                profileState = parseProfileState(profileState),
                health = PolarisSessionStatus.HealthStatus(
                    autoMode = health?.optBoolean("auto_mode", false) ?: false,
                    limitingFactor = health?.optString("limiting_factor", "") ?: "",
                    autoAction = health?.optString("auto_action", "") ?: "",
                    grade = health?.optString("grade", "") ?: "",
                    summary = health?.optString("summary", "") ?: "",
                    primaryIssue = health?.optString("primary_issue", "") ?: "",
                    issues = parseStringArray(health?.optJSONArray("issues")),
                    recommendations = parseStringArray(health?.optJSONArray("recommendations")),
                    safeBitrateKbps = health?.optInt("safe_bitrate_kbps", 0) ?: 0,
                    safeCodec = health?.optString("safe_codec", "") ?: "",
                    safeDisplayMode = health?.optString("safe_display_mode", "") ?: "",
                    safeTargetFps = health?.optDouble("safe_target_fps", 0.0) ?: 0.0,
                    safeHdr = if (health?.has("safe_hdr") == true) health.optBoolean("safe_hdr") else null,
                    decoderRisk = health?.optString("decoder_risk", "") ?: "",
                    hdrRisk = health?.optString("hdr_risk", "") ?: "",
                    networkRisk = health?.optString("network_risk", "") ?: "",
                    hostRenderLimited = health?.optBoolean("host_render_limited", false) ?: false,
                    renderFpsGap = health?.optDouble("render_fps_gap", 0.0) ?: 0.0,
                    recoveryProfile = health?.optString("recovery_profile", "") ?: "",
                    relaunchRecommended = health?.optBoolean("relaunch_recommended", false) ?: false
                )
            )
        }
    }

	init {
		val cryptoProvider = PlatformBinding.getCryptoProvider(context)
		client = try {
			createClientWithCryptoProvider(cryptoProvider).also {
				LimeLog.info(
					"Nova: Polaris API TLS ready host=$serverAddress port=$resolvedHttpsPort " +
						"pinned=${pinnedServerCert != null}"
				)
			}
		} catch (e: Exception) {
			LimeLog.warning("Nova: Failed to initialize Polaris API TLS client: ${errorMessage(e)}")
			createBasicClient()
		}
    }

    private fun createClientWithCryptoProvider(cryptoProvider: LimelightCryptoProvider): OkHttpClient {
        val keyManager = object : X509KeyManager {
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String =
                CLIENT_CERT_ALIAS

            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(cryptoProvider.clientCertificate)

            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null

            override fun getPrivateKey(alias: String?): PrivateKey =
                cryptoProvider.clientPrivateKey

            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
        }

		val trustManager = createPinnedServerTrustManager()
		apiKeyManager = keyManager
		apiTrustManager = trustManager
		val sslContext = SSLContext.getInstance("TLS").apply {
			init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
		}

		return OkHttpClient.Builder()
			.sslSocketFactory(sslContext.socketFactory, trustManager)
			.hostnameVerifier { hostname, session ->
				isPinnedServerCertificate(session) ||
					HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun createPinnedServerTrustManager(): X509TrustManager {
        val defaultTrustManager = getDefaultTrustManager()

		return object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
				throw IllegalStateException("Should never be called")
			}

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    defaultTrustManager.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    if (pinnedServerCert != null && chain.size == 1 && chain[0] == pinnedServerCert) {
                        return
                    }
                    throw e
                }
            }

			override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		}
	}

    private fun getDefaultTrustManager(): X509TrustManager {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }

        return trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No X509 trust manager found")
    }

    private fun isPinnedServerCertificate(session: SSLSession): Boolean {
        val pinnedCert = pinnedServerCert ?: return false
        return try {
            session.peerCertificates.size == 1 && session.peerCertificates[0] == pinnedCert
        } catch (_: SSLPeerUnverifiedException) {
            false
        }
    }

    private fun createBasicClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun clientForCall(): OkHttpClient {
        val keyManager = apiKeyManager ?: return client
        val trustManager = apiTrustManager ?: return client
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        return client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

	private fun execute(request: Request) = clientForCall().newCall(
		request.newBuilder()
			.header("Connection", "close")
			.build()
	).execute()

    private fun executeGetWithRetry(request: Request, attempts: Int = 3): okhttp3.Response {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return execute(request)
            } catch (e: Exception) {
                lastError = e
                if (attempt == attempts - 1) {
                    throw e
                }
                Thread.sleep((attempt + 1) * 150L)
            }
        }
        throw lastError ?: IllegalStateException("GET request failed without an exception")
    }

	private fun errorMessage(e: Exception): String {
		val parts = mutableListOf<String>()
		var current: Throwable? = e
		while (current != null && parts.size < 5) {
			val message = current.message?.takeIf { it.isNotBlank() }
				?: current.localizedMessage?.takeIf { it.isNotBlank() }
				?: "no detail"
			parts += "${current.javaClass.simpleName}: $message"
			current = current.cause
		}
		return parts.joinToString(" <- ")
	}

    /**
     * Probe the server for Polaris capabilities.
     * Returns null if the server is not a Polaris server (404) or unreachable.
     */
    fun getCapabilities(): PolarisCapabilities? {
        return try {
            val request = Request.Builder().url("$baseUrl/capabilities").build()
            executeGetWithRetry(request).use { response ->
                if (response.code != 200) return null
                parseCapabilitiesResponse(JSONObject(response.body?.string() ?: return null))
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Capabilities probe failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Query the current session state. Used by ConnectionResilienceManager
     * to determine if the server session is still alive after a stream drop.
     */
    fun getSessionStatus(): PolarisSessionStatus? {
        return try {
            val request = Request.Builder().url("$baseUrl/session/status").build()
            executeGetWithRetry(request).use { response ->
                if (response.code != 200) return null
                parseSessionStatusResponse(JSONObject(response.body?.string() ?: return null))
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Session status query failed: ${errorMessage(e)}")
            null
        }
    }

    fun getClientSettings(): PolarisClientSettings? {
        return try {
            val request = Request.Builder().url("$baseUrl/client-settings").build()
            executeGetWithRetry(request).use { response ->
                if (response.code != 200) return null
                parseClientSettingsResponse(JSONObject(response.body?.string() ?: return null))
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client settings query failed: ${errorMessage(e)}")
            null
        }
    }

    fun updateClientSettings(
        streamDisplayMode: String? = null,
        displayMode: String? = null,
        clearDisplayMode: Boolean = false,
        targetBitrateKbps: Int? = null,
        clearTargetBitrate: Boolean = false,
        adaptiveBitrateEnabled: Boolean? = null,
        aiOptimizerEnabled: Boolean? = null,
        aiAutoQualityEnabled: Boolean? = null,
        disconnectResumeTimeoutSeconds: Int? = null
    ): PolarisClientSettings? {
        return try {
            val body = buildClientSettingsUpdateBody(
                streamDisplayMode = streamDisplayMode,
                displayMode = displayMode,
                clearDisplayMode = clearDisplayMode,
                targetBitrateKbps = targetBitrateKbps,
                clearTargetBitrate = clearTargetBitrate,
                adaptiveBitrateEnabled = adaptiveBitrateEnabled,
                aiOptimizerEnabled = aiOptimizerEnabled,
                aiAutoQualityEnabled = aiAutoQualityEnabled,
                disconnectResumeTimeoutSeconds = disconnectResumeTimeoutSeconds
            )
            val request = Request.Builder()
                .url("$baseUrl/client-settings")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                if (response.code != 200) return null
                parseClientSettingsResponse(JSONObject(response.body?.string() ?: return null))
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client settings update failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Fetch the game library.
     */
    fun getGames(search: String = "", source: String = "", limit: Int = 50): List<PolarisGame> {
        return try {
            var url = "$baseUrl/games?limit=$limit"
            if (search.isNotEmpty()) url += "&search=$search"
            if (source.isNotEmpty()) url += "&source=$source"

            val request = Request.Builder().url(url).build()
            executeGetWithRetry(request).use { response ->
                if (response.code != 200) return emptyList()

                val json = org.json.JSONObject(response.body?.string() ?: return emptyList())
                val gamesArray = json.optJSONArray("games") ?: return emptyList()

                (0 until gamesArray.length()).map { PolarisGame.fromJson(gamesArray.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Game library fetch failed: ${errorMessage(e)}")
            emptyList()
        }
    }

    /**
     * Get the cover art URL for a game (full HTTPS URL).
     */
	fun getCoverUrl(gameId: String): String {
		return "https://$serverAddress:$resolvedHttpsPort/polaris/v1/games/$gameId/cover"
	}

    fun getPreferredCoverUrl(game: PolarisGame): String {
        val coverUrl = game.coverUrl.trim()
        return when {
            coverUrl.isEmpty() -> getCoverUrl(game.id)
            coverUrl.startsWith("https://") || coverUrl.startsWith("http://") -> coverUrl
			coverUrl.startsWith("/") -> "https://$serverAddress:$resolvedHttpsPort$coverUrl"
            else -> getCoverUrl(game.id)
        }
    }

    fun clearCoverCache() {
        coverCache.evictAll()
    }

    fun loadCoverInto(view: ImageView, game: PolarisGame) {
        val cacheKey = "polaris-cover:${game.id}:${game.coverUrl}"
        val imageUrl = getPreferredCoverUrl(game)

        view.tag = cacheKey
        view.setImageResource(R.drawable.nova_cover_placeholder)

        coverCache.get(cacheKey)?.let { cached ->
            view.setImageBitmap(cached)
            return
        }

        imageScope.launch {
            val bitmap = fetchCoverBitmap(imageUrl)
            withContext(Dispatchers.Main) {
                if (view.tag != cacheKey) {
                    return@withContext
                }

                if (bitmap != null) {
                    coverCache.put(cacheKey, bitmap)
                    view.setImageBitmap(bitmap)
                } else {
                    view.setImageResource(R.drawable.nova_cover_placeholder)
                    LimeLog.warning("Nova: cover load failed for ${game.name}")
                }
            }
        }
    }

    private fun fetchCoverBitmap(url: String): Bitmap? {
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Connection", "close")
                    .build()
                execute(request).use { response ->
                    if (!response.isSuccessful) {
                        LimeLog.warning("Nova: cover request failed [$url] code=${response.code}")
                        return null
                    }

                    val bytes = response.body?.bytes() ?: return null
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    })
                    if (bitmap != null) {
                        return bitmap
                    }

                    LimeLog.warning("Nova: cover decode failed [$url] bytes=${bytes.size}")
                }

                if (attempt < 2) {
                    Thread.sleep((attempt + 1) * 150L)
                }
            } catch (e: Exception) {
                if (attempt == 2) {
                    LimeLog.warning("Nova: cover fetch failed [$url]: ${errorMessage(e)}")
                }
            }
        }

        return null
    }

    /**
     * Toggle MangoHud for a game via Polaris API.
     */
    fun setMangoHud(gameId: String, enabled: Boolean): Boolean {
        return try {
            val body = buildMangoHudUpdateBody(gameId, enabled)
            val request = Request.Builder()
                .url("$baseUrl/games/$gameId/mangohud")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: MangoHud toggle failed: ${errorMessage(e)}")
            false
        }
    }

    fun setSteamLaunchMode(gameId: String, mode: String): Boolean {
        return try {
            val body = buildSteamLaunchModeUpdateBody(gameId, mode)
            val request = Request.Builder()
                .url("$baseUrl/games/$gameId/steam-launch-mode")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Steam launch mode update failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Set the stream bitrate mid-session without reconnecting.
     */
    fun setBitrate(bitrateKbps: Int): Boolean {
        return try {
            val body = org.json.JSONObject().apply { put("bitrate_kbps", bitrateKbps) }
            val request = Request.Builder()
                .url("$baseUrl/session/bitrate")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Bitrate change failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Compatibility API for older Polaris hosts. Current Polaris maps this to AI Auto Quality.
     */
    fun setAdaptiveBitrateEnabled(enabled: Boolean): Boolean {
        return try {
            val body = org.json.JSONObject().apply { put("enabled", enabled) }
            val request = Request.Builder()
                .url("$baseUrl/session/adaptive-bitrate")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Adaptive bitrate toggle failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Toggle AI Auto Quality. Polaris maps this to optimizer decisions and adaptive bitrate.
     */
    fun setAiAutoQualityEnabled(enabled: Boolean): Boolean {
        val aiUpdated = setAiOptimizerEnabled(enabled)
        val adaptiveUpdated = setAdaptiveBitrateEnabled(enabled)
        return aiUpdated || adaptiveUpdated
    }

    /**
     * Toggle the AI optimizer state for subsequent launches.
     */
    fun setAiOptimizerEnabled(enabled: Boolean): Boolean {
        return try {
            val body = org.json.JSONObject().apply { put("enabled", enabled) }
            val request = Request.Builder()
                .url("$baseUrl/session/ai-optimizer")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: AI optimizer toggle failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Toggle the host cursor visibility during an active Polaris session.
     */
    fun setCursorVisibility(visible: Boolean): Boolean {
        return try {
            val body = org.json.JSONObject().apply { put("visible", visible) }
            val request = Request.Builder()
                .url("$baseUrl/session/cursor")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Cursor visibility change failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Report the client-side presentation state that Nova actually applied.
     */
    fun reportClientPresentation(status: String,
                                 appliedRefreshRateHz: Double,
                                 displayModeId: Int,
                                 displayMode: String,
                                 decoder: String,
                                 reason: String,
                                 targetRefreshRateHz: Double,
                                 refreshRatePolicy: String): Boolean {
        return try {
            val presentation = org.json.JSONObject().apply {
                put("status", status)
                if (appliedRefreshRateHz > 0.0) put("applied_refresh_rate_hz", appliedRefreshRateHz)
                if (displayModeId > 0) put("display_mode_id", displayModeId)
                if (displayMode.isNotBlank()) put("display_mode", displayMode)
                if (decoder.isNotBlank()) put("decoder", decoder)
                if (reason.isNotBlank()) put("reason", reason)
                if (targetRefreshRateHz > 0.0) put("target_refresh_rate_hz", targetRefreshRateHz)
                if (refreshRatePolicy.isNotBlank()) put("refresh_rate_policy", refreshRatePolicy)
            }
            reportClientSettings(clientPresentation = presentation) != null
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client presentation report failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Report the client runtime and the stream settings Nova actually applied.
     * Polaris treats this as the client side of the Auto Safe sync contract.
     */
    fun reportClientSettings(syncMode: String = "auto_safe",
                             manualOverride: Boolean = false,
                             deviceCapabilities: JSONObject? = null,
                             clientRuntime: JSONObject? = null,
                             appliedStreamSettings: JSONObject? = null,
                             clientPresentation: JSONObject? = null): PolarisSessionStatus.SyncStatus? {
        return try {
            val body = org.json.JSONObject().apply {
                put("sync_mode", syncMode)
                put("manual_override", manualOverride)
                deviceCapabilities?.let { put("device_capabilities", it) }
                clientRuntime?.let { put("client_runtime", it) }
                appliedStreamSettings?.let { put("applied_stream_settings", it) }
                clientPresentation?.let { put("client_presentation", it) }
            }
            val request = Request.Builder()
                .url("$baseUrl/client-settings")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                if (response.code != 200) return null
                val responseJson = JSONObject(response.body?.string() ?: return null)
                val statusJson = JSONObject().apply {
                    responseJson.optJSONObject("sync_status")?.let { put("sync_status", it) }
                    responseJson.optJSONObject("client_settings")?.let { put("client_settings", it) }
                    responseJson.optJSONObject("stream_policy")?.let { put("stream_policy", it) }
                    responseJson.optJSONObject("health")?.let { put("health", it) }
                }
                parseSessionStatusResponse(statusJson).syncStatus
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client settings sync failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Send session quality report at end of stream.
     */
    fun sendSessionReport(device: String, uniqueId: String, game: String, avgFps: Double, targetFps: Double,
                          lowOnePercentFps: Double, minFps: Double, framePacingBadPct: Double, safeTargetFps: Double,
                          avgLatency: Double,
                          avgBitrate: Int, packetLoss: Double, codec: String,
                          durationS: Int, samples: Int, endReason: String,
                          optimizationSource: String, optimizationConfidence: String,
                          recommendationVersion: Int,
                          healthGrade: String,
                          primaryIssue: String,
                          issues: List<String>,
                          decoderRisk: String,
                          hdrRisk: String,
                          networkRisk: String,
                          capturePath: String,
                          safeBitrateKbps: Int,
                          safeCodec: String,
                          safeDisplayMode: String,
                          safeHdr: Boolean?,
                          relaunchRecommended: Boolean): Boolean {
        return try {
            val body = org.json.JSONObject().apply {
                put("device", device)
                if (uniqueId.isNotBlank()) put("unique_id", uniqueId)
                put("game", game)
                put("avg_fps", avgFps)
                if (targetFps > 0.0) put("target_fps", targetFps)
                if (lowOnePercentFps > 0.0) put("low_1_percent_fps", lowOnePercentFps)
                if (minFps > 0.0) put("min_fps", minFps)
                if (framePacingBadPct > 0.0) put("frame_pacing_bad_pct", framePacingBadPct)
                if (safeTargetFps > 0.0) put("safe_target_fps", safeTargetFps)
                put("avg_latency_ms", avgLatency)
                put("avg_bitrate_kbps", avgBitrate)
                put("packet_loss_pct", packetLoss)
                put("codec", codec)
                put("duration_s", durationS)
                put("samples", samples)
                put("end_reason", endReason)
                if (optimizationSource.isNotBlank()) put("optimization_source", optimizationSource)
                if (optimizationConfidence.isNotBlank()) put("optimization_confidence", optimizationConfidence)
                if (recommendationVersion > 0) put("recommendation_version", recommendationVersion)
                if (healthGrade.isNotBlank()) put("health_grade", healthGrade)
                if (primaryIssue.isNotBlank()) put("primary_issue", primaryIssue)
                if (issues.isNotEmpty()) put("issues", org.json.JSONArray(issues))
                if (decoderRisk.isNotBlank()) put("decoder_risk", decoderRisk)
                if (hdrRisk.isNotBlank()) put("hdr_risk", hdrRisk)
                if (networkRisk.isNotBlank()) put("network_risk", networkRisk)
                if (capturePath.isNotBlank()) put("capture_path", capturePath)
                if (safeBitrateKbps > 0) put("safe_bitrate_kbps", safeBitrateKbps)
                if (safeCodec.isNotBlank()) put("safe_codec", safeCodec)
                if (safeDisplayMode.isNotBlank()) put("safe_display_mode", safeDisplayMode)
                if (safeHdr != null) put("safe_hdr", safeHdr)
                if (relaunchRecommended) put("relaunch_recommended", true)
            }
            val request = Request.Builder()
                .url("$baseUrl/session/report")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Session report failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Get AI-recommended streaming settings for a device+game combo.
     */
    @JvmOverloads
    fun getOptimization(device: String, game: String, preference: String = ""): org.json.JSONObject? {
        return try {
            val preferenceParam = preference
                .takeIf { it.isNotBlank() }
                ?.let { "&preference=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                ?: ""
            val url = "$baseUrl/optimize?device=${java.net.URLEncoder.encode(device, "UTF-8")}" +
                      "&game=${java.net.URLEncoder.encode(game, "UTF-8")}" +
                      preferenceParam
            val request = Request.Builder().url(url).get().build()
            executeGetWithRetry(request).use { response ->
                if (response.code == 200) {
                    org.json.JSONObject(response.body?.string() ?: "{}")
                } else null
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Optimization query failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Clear the saved Auto Quality profile for a single device+game combo.
     */
    fun clearOptimizerProfile(device: String, game: String): Boolean? {
        return try {
            val body = buildOptimizerProfileClearBody(device, game)
            val request = Request.Builder()
                .url("$baseUrl/optimizer/profile/clear")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                if (response.code != 200) return null
                val responseJson = JSONObject(response.body?.string() ?: "{}")
                responseJson.optBoolean("cleared", false)
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Optimizer profile clear failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Fetch the merged optimizer profile audit list for this paired device.
     */
    fun getOptimizerProfiles(): org.json.JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/optimizer/profiles")
                .get()
                .build()
            executeGetWithRetry(request).use { response ->
                if (response.code == 200) {
                    org.json.JSONObject(response.body?.string() ?: "{}")
                } else null
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Optimizer profiles query failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Clear all saved Auto Quality profiles for this paired device.
     */
    fun clearOptimizerProfiles(): Boolean? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/optimizer/profiles/clear")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    JSONObject().toString()
                ))
                .build()
            execute(request).use { response ->
                if (response.code != 200) return null
                val responseJson = JSONObject(response.body?.string() ?: "{}")
                responseJson.optBoolean("cleared", false)
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Optimizer profiles clear failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Launch a game via the Polaris API.
     */
    fun launchGame(gameId: String, displayWidth: Int = 0, displayHeight: Int = 0, displayFps: Int = 0): Boolean {
        return try {
            val body = org.json.JSONObject().apply {
                put("game_id", gameId)
                if (displayWidth > 0 && displayHeight > 0) {
                    put("client_width", displayWidth)
                    put("client_height", displayHeight)
                    if (displayFps > 0) put("client_fps", displayFps)
                }
            }
            val request = Request.Builder()
                .url("$baseUrl/session/launch")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Game launch failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Request that Polaris unlock the current desktop session.
     */
    fun unlockScreen(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$webBaseUrl/api/polaris/unlock")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    "{}"
                ))
                .build()
            execute(request).use { response ->
                if (response.code != 200) return false

                val body = response.body.string()
                if (body.isBlank()) return false
                parseUnlockResponse(JSONObject(body))
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Unlock request failed: ${errorMessage(e)}")
            false
        }
    }
}
