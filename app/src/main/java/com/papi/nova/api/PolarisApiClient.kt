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
import java.io.ByteArrayInputStream
import java.net.Proxy
import java.net.Socket
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
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
    private val coverClient: OkHttpClient
    private val baseUrl = "https://$serverAddress:$httpsPort/polaris/v1"
    private val webBaseUrl = "https://$serverAddress:$WEB_UI_HTTPS_PORT"
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val coverCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val WEB_UI_HTTPS_PORT = 47990

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
                    clientSettings = features?.optBoolean("client_settings_v1") ?: false,
                    lockScreenControl = features?.optBoolean("lock_screen_control") ?: false,
                    cursorVisibilityControl = features?.optBoolean("cursor_visibility_control") ?: false,
                    disconnectResume = features?.optBoolean("disconnect_resume_v1") ?: false
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
                    adaptiveBitrateControl = capabilities?.optBoolean("adaptive_bitrate_control", false) ?: false,
                    aiOptimizerControl = capabilities?.optBoolean("ai_optimizer_control", false) ?: false,
                    disconnectResumeTimeoutControl = capabilities?.optBoolean("disconnect_resume_timeout_control", false) ?: false
                ),
                relaunchRequired = settingsJson.optBoolean("relaunch_required", false)
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
            val clientSettings = json.optJSONObject("client_settings")

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
                    format = capture?.optString("format", "") ?: "",
                    path = capture?.optString("path", "") ?: "",
                    reason = capture?.optString("reason", "") ?: "",
                    reasonMessage = capture?.optString("reason_message", "") ?: "",
                    cpuCopy = capture?.optBoolean("cpu_copy", false) ?: false,
                    gpuNative = capture?.optBoolean("gpu_native", false) ?: false
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
                ),
                clientSettings = clientSettings?.let { parseClientSettingsResponse(it) } ?: PolarisClientSettings()
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
            disconnectResumeTimeoutSeconds: Int? = null
        ): JSONObject {
            return JSONObject().apply {
                streamDisplayMode?.let { put("stream_display_mode", it) }
                displayMode?.let { put("display_mode", it) }
                if (clearDisplayMode) put("clear_display_mode", true)
                targetBitrateKbps?.let { put("target_bitrate_kbps", it) }
                if (clearTargetBitrate) put("clear_target_bitrate", true)
                adaptiveBitrateEnabled?.let { put("adaptive_bitrate_enabled", it) }
                aiOptimizerEnabled?.let { put("ai_optimizer_enabled", it) }
                disconnectResumeTimeoutSeconds?.let { put("disconnect_resume_timeout_seconds", it) }
            }
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
        val cert = certFile.inputStream().use {
            certFactory.generateCertificate(it) as X509Certificate
        }

        val keyBytes = keyFile.readBytes()
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        val trustManager = createPinnedServerTrustManager()
        val keyManager = createForcedClientKeyManager(cert, privateKey)

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .protocols(listOf(Protocol.HTTP_1_1))
            .hostnameVerifier { hostname, session ->
                isPinnedServerCertificate(session) ||
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    private fun createForcedClientKeyManager(
        cert: X509Certificate,
        privateKey: PrivateKey
    ): X509KeyManager {
        return object : X509KeyManager {
            override fun chooseClientAlias(
                keyTypes: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? {
                return if (keyTypes == null || keyTypes.any { it.equals("RSA", ignoreCase = true) }) {
                    "Limelight-RSA"
                } else {
                    null
                }
            }

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate> {
                return arrayOf(cert)
            }

            override fun getClientAliases(
                keyType: String?,
                issuers: Array<out Principal>?
            ): Array<String> = arrayOf("Limelight-RSA")

            override fun getPrivateKey(alias: String?): PrivateKey = privateKey

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<out Principal>?
            ): Array<String>? = null
        }
    }

    private fun createPinnedServerTrustManager(): X509TrustManager {
        val defaultTrustManager = getDefaultTrustManager()

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                defaultTrustManager.checkClientTrusted(chain, authType)
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

            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers
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
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun logMessage(e: Exception): String {
        val message = e.message
        return if (message.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message"
    }

    private fun jsonBody(body: JSONObject = JSONObject()): okhttp3.RequestBody {
        return okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            body.toString()
        )
    }

    private fun getJson(path: String): JSONObject? {
        val request = Request.Builder().url("$baseUrl/$path").build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            return JSONObject(response.body?.string() ?: return null)
        }
    }

    private fun postJson(path: String, body: JSONObject = JSONObject()): JSONObject? {
        val request = Request.Builder()
            .url("$baseUrl/$path")
            .post(jsonBody(body))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun postBoolean(path: String, body: JSONObject = JSONObject()): Boolean {
        return postJson(path, body)?.optBoolean("status", true) == true
    }

    /**
     * Probe the server for Polaris capabilities.
     * Returns null if the server is not a Polaris server (404) or unreachable.
     */
    fun getCapabilities(): PolarisCapabilities? {
        return try {
            parseCapabilitiesResponse(getJson("capabilities") ?: return null)
        } catch (e: Exception) {
            LimeLog.warning("Nova: Capabilities probe failed: ${logMessage(e)}")
            null
        }
    }

    /**
     * Query the current session state. Used by ConnectionResilienceManager
     * to determine if the server session is still alive after a stream drop.
     */
    fun getSessionStatus(): PolarisSessionStatus? {
        return try {
            parseSessionStatusResponse(getJson("session/status") ?: return null)
        } catch (e: Exception) {
            LimeLog.warning("Nova: Session status query failed: ${logMessage(e)}")
            null
        }
    }

    fun getClientSettings(): PolarisClientSettings? {
        return try {
            parseClientSettingsResponse(getJson("client-settings") ?: return null)
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client settings query failed: ${logMessage(e)}")
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
                disconnectResumeTimeoutSeconds = disconnectResumeTimeoutSeconds
            )
            parseClientSettingsResponse(postJson("client-settings", body) ?: return null)
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client settings update failed: ${logMessage(e)}")
            null
        }
    }

    fun setClientTargetBitrate(bitrateKbps: Int): Boolean {
        return updateClientSettings(targetBitrateKbps = bitrateKbps) != null
    }

    fun setLaunchProfile(displayMode: String, bitrateKbps: Int): Boolean {
        return updateClientSettings(
            displayMode = displayMode,
            targetBitrateKbps = bitrateKbps.takeIf { it > 0 }
        ) != null
    }

    fun setDisconnectResumeTimeout(timeoutSeconds: Int): Boolean {
        return updateClientSettings(disconnectResumeTimeoutSeconds = timeoutSeconds) != null
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
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return emptyList()

                val json = org.json.JSONObject(response.body?.string() ?: return emptyList())
                val gamesArray = json.optJSONArray("games") ?: return emptyList()

                (0 until gamesArray.length()).map { PolarisGame.fromJson(gamesArray.getJSONObject(it)) }
            }
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
            postBoolean("games/$gameId/mangohud", body)
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
            postBoolean("session/bitrate", body)
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
            postBoolean("session/adaptive-bitrate", body)
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
            postBoolean("session/ai-optimizer", body)
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
            postBoolean("session/cursor", body)
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
            postBoolean("session/report", body)
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
            client.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    org.json.JSONObject(response.body?.string() ?: "{}")
                } else null
            }
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
            postBoolean("session/launch", body)
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
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return false

                val json = JSONObject(response.body?.string() ?: "{}")
                json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Unlock request failed: ${e.message}")
            false
        }
    }
}
