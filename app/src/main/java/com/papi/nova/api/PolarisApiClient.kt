package com.papi.nova.api

import android.content.Context
import com.papi.nova.LimeLog
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

/**
 * HTTP client for Polaris REST API on the nvhttp port (47984).
 * Uses the same client certificate as Moonlight pairing.
 */
class PolarisApiClient(context: Context, private val serverAddress: String) {

    @JvmField val client: OkHttpClient
    private val baseUrl = "https://$serverAddress:47984/polaris/v1"

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

            val json = JSONObject(response.body?.string() ?: return null)
            val features = json.optJSONObject("features")
            val capture = json.optJSONObject("capture")

            PolarisCapabilities(
                server = json.optString("server", ""),
                version = json.optString("version", ""),
                features = PolarisCapabilities.Features(
                    aiOptimizer = features?.optBoolean("ai_optimizer") ?: false,
                    gameLibrary = features?.optBoolean("game_library") ?: false,
                    sessionLifecycle = features?.optBoolean("session_lifecycle") ?: false,
                    deviceProfiles = features?.optBoolean("device_profiles") ?: false,
                    lockScreenControl = features?.optBoolean("lock_screen_control") ?: false
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

            val json = JSONObject(response.body?.string() ?: return null)
            val capture = json.optJSONObject("capture")
            val encoder = json.optJSONObject("encoder")

            PolarisSessionStatus(
                state = json.optString("state", "unknown"),
                game = json.optString("game", ""),
                cagePid = json.optInt("cage_pid", 0),
                screenLocked = json.optBoolean("screen_locked", false),
                capture = PolarisSessionStatus.CaptureStatus(
                    backend = capture?.optString("backend", "") ?: "",
                    resolution = capture?.optString("resolution", "") ?: ""
                ),
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = encoder?.optString("codec", "") ?: "",
                    bitrateKbps = encoder?.optInt("bitrate_kbps", 0) ?: 0,
                    fps = encoder?.optDouble("fps", 0.0) ?: 0.0
                )
            )
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
        return "https://$serverAddress:47984/polaris/v1/games/$gameId/cover"
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
     * Send session quality report at end of stream.
     */
    fun sendSessionReport(device: String, game: String, avgFps: Double, avgLatency: Double,
                          avgBitrate: Int, packetLoss: Double, codec: String,
                          durationS: Int, endReason: String): Boolean {
        return try {
            val body = org.json.JSONObject().apply {
                put("device", device)
                put("game", game)
                put("avg_fps", avgFps)
                put("avg_latency_ms", avgLatency)
                put("avg_bitrate_kbps", avgBitrate)
                put("packet_loss_pct", packetLoss)
                put("codec", codec)
                put("duration_s", durationS)
                put("end_reason", endReason)
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
}
