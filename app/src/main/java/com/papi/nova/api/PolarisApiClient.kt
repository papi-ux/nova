package com.papi.nova.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.widget.ImageView
import com.papi.nova.LimeLog
import com.papi.nova.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import androidx.collection.LruCache

/**
 * HTTP client for Polaris REST API on the nvhttp port (47984).
 * Uses the same client certificate as Moonlight pairing.
 */
class PolarisApiClient(context: Context, private val serverAddress: String, private val httpsPort: Int = 47984) {

    @JvmField val client: OkHttpClient
    private val coverClient: OkHttpClient
    private val baseUrl = "https://$serverAddress:$httpsPort/polaris/v1"
    private val webBaseUrl = "https://$serverAddress:$WEB_UI_HTTPS_PORT"
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val coverCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val WEB_UI_HTTPS_PORT = 47990

        private fun parseStringArray(array: org.json.JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }

        @JvmStatic
        fun parseCapabilitiesResponse(json: JSONObject): PolarisCapabilities {
            val features = json.optJSONObject("features")
            val capture = json.optJSONObject("capture")

            return PolarisCapabilities(
                server = json.optString("server", ""),
                version = json.optString("version", ""),
                features = PolarisCapabilities.Features(
                    aiOptimizer = features?.optBoolean("ai_optimizer") ?: false,
                    aiOptimizerControl = features?.optBoolean("ai_optimizer_control") ?: false,
                    adaptiveBitrateControl = features?.optBoolean("adaptive_bitrate_control") ?: false,
                    gameLibrary = features?.optBoolean("game_library") ?: false,
                    sessionLifecycle = features?.optBoolean("session_lifecycle") ?: false,
                    deviceProfiles = features?.optBoolean("device_profiles") ?: false,
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
            val capture = json.optJSONObject("capture")
            val encoder = json.optJSONObject("encoder")
            val health = json.optJSONObject("health")

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
                health = PolarisSessionStatus.HealthStatus(
                    grade = health?.optString("grade", "") ?: "",
                    summary = health?.optString("summary", "") ?: "",
                    primaryIssue = health?.optString("primary_issue", "") ?: "",
                    issues = parseStringArray(health?.optJSONArray("issues")),
                    recommendations = parseStringArray(health?.optJSONArray("recommendations")),
                    safeBitrateKbps = health?.optInt("safe_bitrate_kbps", 0) ?: 0,
                    safeCodec = health?.optString("safe_codec", "") ?: "",
                    safeDisplayMode = health?.optString("safe_display_mode", "") ?: "",
                    safeHdr = if (health?.has("safe_hdr") == true) health.optBoolean("safe_hdr") else null,
                    decoderRisk = health?.optString("decoder_risk", "") ?: "",
                    hdrRisk = health?.optString("hdr_risk", "") ?: "",
                    networkRisk = health?.optString("network_risk", "") ?: "",
                    relaunchRecommended = health?.optBoolean("relaunch_recommended", false) ?: false
                )
            )
        }
    }

    init {
        val dataPath = context.filesDir.absolutePath
        val certFile = File(dataPath, "client.crt")
        val keyFile = File(dataPath, "client.key")

        client = if (certFile.exists() && keyFile.exists()) {
            createClientWithCert(certFile, keyFile)
        } else {
            LimeLog.warning("Nova: No client cert found, API calls will fail")
            createBasicClient()
        }
        coverClient = client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun createClientWithCert(certFile: File, keyFile: File): OkHttpClient {
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(FileInputStream(certFile)) as X509Certificate

        val keyBytes = keyFile.readBytes()
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, charArrayOf(), arrayOf(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }

        // Trust all certs (server uses self-signed)
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(trustAllManager), null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun createBasicClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Probe the server for Polaris capabilities.
     * Returns null if the server is not a Polaris server (404) or unreachable.
     */
    fun getCapabilities(): PolarisCapabilities? {
        return try {
            val request = Request.Builder().url("$baseUrl/capabilities").build()
            val response = client.newCall(request).execute()

            if (response.code != 200) return null

            parseCapabilitiesResponse(JSONObject(response.body?.string() ?: return null))
        } catch (e: Exception) {
            LimeLog.warning("Nova: Capabilities probe failed: ${e.message}")
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
            val response = client.newCall(request).execute()

            if (response.code != 200) return null

            parseSessionStatusResponse(JSONObject(response.body?.string() ?: return null))
        } catch (e: Exception) {
            LimeLog.warning("Nova: Session status query failed: ${e.message}")
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
            val response = client.newCall(request).execute()

            if (response.code != 200) return emptyList()

            val json = org.json.JSONObject(response.body?.string() ?: return emptyList())
            val gamesArray = json.optJSONArray("games") ?: return emptyList()

            (0 until gamesArray.length()).map { PolarisGame.fromJson(gamesArray.getJSONObject(it)) }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Game library fetch failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get the cover art URL for a game (full HTTPS URL).
     */
    fun getCoverUrl(gameId: String): String {
        return "https://$serverAddress:$httpsPort/polaris/v1/games/$gameId/cover"
    }

    fun getPreferredCoverUrl(game: PolarisGame): String {
        val coverUrl = game.coverUrl.trim()
        return when {
            coverUrl.isEmpty() -> getCoverUrl(game.id)
            coverUrl.startsWith("https://") || coverUrl.startsWith("http://") -> coverUrl
            coverUrl.startsWith("/") -> "https://$serverAddress:$httpsPort$coverUrl"
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
                coverClient.newCall(request).execute().use { response ->
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
                    LimeLog.warning("Nova: cover fetch failed [$url]: ${e.message}")
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
            val body = org.json.JSONObject().apply {
                put("game_id", gameId)
                put("mangohud", enabled)
            }
            val request = Request.Builder()
                .url("$baseUrl/games/$gameId/mangohud")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: MangoHud toggle failed: ${e.message}")
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
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: Bitrate change failed: ${e.message}")
            false
        }
    }

    /**
     * Toggle adaptive bitrate during an active Polaris session.
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
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: Adaptive bitrate toggle failed: ${e.message}")
            false
        }
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
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: AI optimizer toggle failed: ${e.message}")
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
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: Cursor visibility change failed: ${e.message}")
            false
        }
    }

    /**
     * Send session quality report at end of stream.
     */
    fun sendSessionReport(device: String, uniqueId: String, game: String, avgFps: Double, targetFps: Double, avgLatency: Double,
                          avgBitrate: Int, packetLoss: Double, codec: String,
                          durationS: Int, endReason: String,
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
                put("avg_latency_ms", avgLatency)
                put("avg_bitrate_kbps", avgBitrate)
                put("packet_loss_pct", packetLoss)
                put("codec", codec)
                put("duration_s", durationS)
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
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: Session report failed: ${e.message}")
            false
        }
    }

    /**
     * Get AI-recommended streaming settings for a device+game combo.
     */
    fun getOptimization(device: String, game: String): org.json.JSONObject? {
        return try {
            val url = "$baseUrl/optimize?device=${java.net.URLEncoder.encode(device, "UTF-8")}" +
                      "&game=${java.net.URLEncoder.encode(game, "UTF-8")}"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (response.code == 200) {
                org.json.JSONObject(response.body?.string() ?: "{}")
            } else null
        } catch (e: Exception) {
            LimeLog.warning("Nova: Optimization query failed: ${e.message}")
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
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            LimeLog.warning("Nova: Game launch failed: ${e.message}")
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
            val response = client.newCall(request).execute()
            if (response.code != 200) return false

            val json = JSONObject(response.body?.string() ?: "{}")
            json.optBoolean("success", false)
        } catch (e: Exception) {
            LimeLog.warning("Nova: Unlock request failed: ${e.message}")
            false
        }
    }
}
