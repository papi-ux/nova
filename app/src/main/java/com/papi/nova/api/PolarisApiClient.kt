package com.papi.nova.api

import android.graphics.Bitmap
import android.content.Context
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.binding.video.PerfOverlaySample
import android.widget.ImageView
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
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
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import androidx.collection.LruCache

data class PolarisApiRejection(
    val httpStatus: Int,
    val code: String,
    val error: String,
)

class PolarisApiRejectedException(
    val rejection: PolarisApiRejection,
) : IOException(rejection.error)

data class PolarisArtworkMatchCandidate(
    val provider: String,
    val providerGameId: String,
    val title: String,
    val steamAppid: String? = null,
    val releaseYear: Int? = null,
    val confidence: Double = 0.0,
    val posterPreviewUrl: String? = null,
)

data class PolarisArtworkChoice(
    val kind: String,
    val selectionToken: String,
    val previewUrl: String,
    val expiresAt: Long,
)

enum class PolarisArtworkUpdateStatus {
    HEALTHY,
    UPDATED,
    CUSTOM_PRESERVED,
    PARTIAL_FAILURE,
}

data class PolarisArtworkUpdateResult(
    val manifest: PolarisGame.ArtworkManifest,
    val status: PolarisArtworkUpdateStatus,
    val requestedKinds: List<String>,
    val remainingKinds: List<String>,
)

class PolarisArtworkLibraryUpdateUnavailableException :
    IOException("Polaris artwork library update API unavailable")

enum class PolarisLaunchHostKind {
    CURRENT_POLARIS,
    LEGACY_POLARIS,
    NON_POLARIS,
    UNKNOWN
}

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
    private val artworkDiskCache = PolarisArtworkDiskCache(context.applicationContext, serverAddress, resolvedHttpsPort)
    private val artworkResolveOnce = ArtworkResolveOnce<PolarisGame.ArtworkManifest>()
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    private val coverCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val WEB_UI_HTTPS_PORT = 47990
        private const val CLIENT_CERT_ALIAS = "Limelight-RSA"
        // Poster-shaped bucket; also the fallback for studio preview cells.
        private const val PREVIEW_TARGET_WIDTH = 512
        private const val PREVIEW_TARGET_HEIGHT = 768

        @JvmStatic
        fun decodeCertificate(serverCertDer: ByteArray?): X509Certificate? {
            if (serverCertDer == null) return null
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(serverCertDer)) as X509Certificate
        }

        @JvmStatic
        internal fun buildArtworkHttpClient(base: OkHttpClient): OkHttpClient =
            base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(ARTWORK_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(ARTWORK_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        @JvmStatic
        internal fun buildNonRetryableHttpClient(base: OkHttpClient): OkHttpClient =
            base.newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        // TLS session resumption against a server that intermittently rejects it (e.g. a
        // missing server-side session id context) surfaces as an SSLException on an
        // otherwise-healthy link; one fresh-handshake retry is the correct recovery. Only
        // idempotent requests may ride this helper.
        @JvmStatic
        internal fun <T> runWithTransientTlsRetry(
            onTransient: () -> Unit,
            retryDelayMs: Long = 150L,
            attempt: () -> T
        ): T {
            return try {
                attempt()
            } catch (e: SSLException) {
                LimeLog.warning("Nova: retrying once after transient TLS failure: ${e.javaClass.simpleName}: ${e.message}")
                onTransient()
                if (retryDelayMs > 0) {
                    Thread.sleep(retryDelayMs)
                }
                attempt()
            }
        }

        @JvmStatic
        internal fun parseArtworkJsonBytes(bytes: ByteArray): JSONObject? {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) return null
            return try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw IOException("invalid artwork JSON")
            }
        }

        private val SAFE_GAME_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
        private val CANDIDATE_PREVIEW_PATH = Regex(
            "^/polaris/v1/games/[A-Za-z0-9][A-Za-z0-9._-]{0,255}/artwork/candidate/[0-9a-f]{32}/(poster|hero|logo|icon)$",
        )
        private val OPAQUE_SELECTION_TOKEN = Regex("[0-9a-f]{32}")
        private val PROVIDER_GAME_ID = Regex("[1-9][0-9]{0,19}")
        private val STEAM_APP_ID = Regex("[1-9][0-9]{0,9}")
        private val ARTWORK_KINDS = listOf("poster", "hero", "logo", "icon")
        private const val MAX_ARTWORK_JSON_BYTES = 1024 * 1024
        private const val ARTWORK_REQUEST_TIMEOUT_SECONDS = 120L

        @JvmStatic
        internal fun paginateAllGames(
            pageSize: Int,
            fetchPage: (offset: Int) -> List<PolarisGame>,
        ): List<PolarisGame> {
            require(pageSize > 0)
            val games = linkedMapOf<String, PolarisGame>()
            var offset = 0
            while (true) {
                val before = games.size
                val batch = fetchPage(offset)
                batch.forEach { game ->
                    if (!games.containsKey(game.id)) {
                        games[game.id] = game
                    }
                }
                if (batch.size < pageSize) return games.values.toList()
                if (games.size == before) {
                    throw IOException("game library pagination made no progress")
                }
                if (offset > Int.MAX_VALUE - pageSize) {
                    throw IOException("game library pagination overflow")
                }
                offset += pageSize
            }
        }

        private fun sanitizedCandidateTitle(value: String): String? {
            val title = value.trim()
            if (title.isEmpty() || title.toByteArray(Charsets.UTF_8).size > 160) return null
            if (title.any { it.code < 0x20 || it.code in 0x7f..0x9f }) return null
            return title
        }

        @JvmStatic
        fun isSafeArtworkGameId(gameId: String): Boolean =
            SAFE_GAME_ID.matches(gameId) && gameId != "." && gameId != ".."

        @JvmStatic
        fun artworkPresentationKey(game: PolarisGame, kind: String): String {
            val normalizedKind = kind.trim().lowercase()
            val asset = game.artworkAsset(normalizedKind)?.takeIf { it.cached }
            return if (asset != null) {
                "manifest:${game.id}:$normalizedKind:${game.artwork?.revision.orEmpty()}:${asset.url}"
            } else {
                "legacy:${game.id}:${game.coverUrl}"
            }
        }

        @JvmStatic
        fun isTrustedCandidatePreviewUrl(url: String, host: String, port: Int): Boolean {
            if (port !in 1..65535) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val pairedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
            if (!uri.host.orEmpty().equals(pairedHost, ignoreCase = true) || uri.port != port) return false
            return CANDIDATE_PREVIEW_PATH.matches(uri.rawPath.orEmpty())
        }

        @JvmStatic
        internal fun artworkRequestLogLabel(url: String): String {
            val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
            return when {
                CANDIDATE_PREVIEW_PATH.matches(path) -> "candidate-preview"
                path.startsWith("/polaris/v1/games/") && path.contains("/artwork/") -> "manifest-artwork"
                else -> "legacy-cover"
            }
        }

        @JvmStatic
        fun resolveManifestPath(host: String, port: Int, path: String): String? =
            resolveHostRelativePath(host, port, path, requiredPrefix = "/polaris/v1/")

        @JvmStatic
        fun parseArtworkCandidates(json: JSONObject, gameId: String, host: String, port: Int): List<PolarisArtworkMatchCandidate> {
            if (!isSafeArtworkGameId(gameId)) return emptyList()
            val status = json.opt("status")
            if (status !is Boolean || !status) {
                throw IOException("invalid artwork candidate search response")
            }
            val values = json.optJSONArray("candidates")
                ?: throw IOException("invalid artwork candidate search response")
            return (0 until values.length()).asSequence()
                .mapNotNull { index -> values.optJSONObject(index)?.let { parseArtworkCandidate(it, gameId, host, port) } }
                .distinctBy { "${it.provider}:${it.providerGameId}" }
                .take(5)
                .toList()
        }

        @JvmStatic
        fun parseArtworkChoices(
            json: JSONObject,
            gameId: String,
            requestedKind: String,
            host: String,
            port: Int,
        ): List<PolarisArtworkChoice> {
            if (!isSafeArtworkGameId(gameId)) return emptyList()
            val kind = requestedKind.trim().lowercase()
            if (kind !in ARTWORK_KINDS || json.opt("status") != true || json.optString("kind") != kind) {
                return emptyList()
            }
            val values = json.optJSONArray("choices") ?: return emptyList()
            val prefix = "/polaris/v1/games/$gameId/artwork/candidate/"
            return (0 until values.length()).asSequence()
                .mapNotNull { values.optJSONObject(it) }
                .mapNotNull { item ->
                    val token = item.optString("selection_token")
                    if (!OPAQUE_SELECTION_TOKEN.matches(token)) return@mapNotNull null
                    val path = item.optString("preview")
                    if (path != "$prefix$token/$kind") return@mapNotNull null
                    val url = resolveManifestPath(host, port, path) ?: return@mapNotNull null
                    if (!isTrustedCandidatePreviewUrl(url, host, port)) return@mapNotNull null
                    PolarisArtworkChoice(kind, token, url, item.optLong("expires_at", 0L))
                }
                .distinctBy { it.selectionToken }
                .take(5)
                .toList()
        }

        @JvmStatic
        fun buildArtworkChoiceBody(candidate: PolarisArtworkMatchCandidate): JSONObject {
            require(candidate.provider == "steamgriddb")
            require(PROVIDER_GAME_ID.matches(candidate.providerGameId))
            val title = requireNotNull(sanitizedCandidateTitle(candidate.title))
            require(candidate.steamAppid == null || STEAM_APP_ID.matches(candidate.steamAppid))
            return JSONObject().apply {
                put("provider", "steamgriddb")
                put("provider_game_id", candidate.providerGameId)
                put("title", title)
                candidate.steamAppid?.let { put("steam_appid", it) }
            }
        }

        @JvmStatic
        fun buildArtworkMatchBody(candidate: PolarisArtworkMatchCandidate, kinds: List<String>): JSONObject {
            val selectedKinds = kinds.map { it.trim().lowercase() }
            require(selectedKinds.isNotEmpty() && selectedKinds.size <= ARTWORK_KINDS.size)
            require(selectedKinds.distinct().size == selectedKinds.size && selectedKinds.all { it in ARTWORK_KINDS })
            return buildArtworkChoiceBody(candidate).apply { put("kinds", JSONArray(selectedKinds)) }
        }

        @JvmStatic
        fun buildArtworkSelectionBody(
            candidate: PolarisArtworkMatchCandidate,
            selections: Map<String, PolarisArtworkChoice>,
        ): JSONObject {
            require(selections.isNotEmpty() && selections.size <= ARTWORK_KINDS.size)
            val tokens = linkedMapOf<String, String>()
            selections.forEach { (rawKind, choice) ->
                val kind = rawKind.trim().lowercase()
                require(kind in ARTWORK_KINDS && choice.kind == kind)
                require(OPAQUE_SELECTION_TOKEN.matches(choice.selectionToken))
                require(choice.selectionToken !in tokens.values)
                tokens[kind] = choice.selectionToken
            }
            val selected = JSONObject()
            ARTWORK_KINDS.forEach { kind -> tokens[kind]?.let { selected.put(kind, it) } }
            return buildArtworkChoiceBody(candidate).apply { put("selections", selected) }
        }

        @JvmStatic
        fun buildArtworkLibraryUpdateBody(): JSONObject = JSONObject().put("policy", "missing_or_stale")

        private fun parseArtworkCandidate(item: JSONObject, gameId: String, host: String, port: Int): PolarisArtworkMatchCandidate? {
            if (item.optString("provider") != "steamgriddb") return null
            val providerGameId = item.optString("provider_game_id")
            if (!PROVIDER_GAME_ID.matches(providerGameId)) return null
            val title = sanitizedCandidateTitle(item.optString("title")) ?: return null
            val steamAppid = item.optString("steam_appid").takeIf { STEAM_APP_ID.matches(it) }
            val releaseYear = item.optInt("release_year", 0).takeIf { it in 1970..2100 }
            val confidence = item.optDouble("confidence", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
            val previewPath = item.optJSONObject("preview")?.optString("poster").orEmpty()
            val prefix = "/polaris/v1/games/$gameId/artwork/candidate/"
            val token = previewPath.removePrefix(prefix).removeSuffix("/poster")
            val previewUrl = previewPath.takeIf {
                it.startsWith(prefix) && it.endsWith("/poster") && Regex("[0-9a-f]{32}").matches(token)
            }?.let { resolveManifestPath(host, port, it) }
            return PolarisArtworkMatchCandidate("steamgriddb", providerGameId, title, steamAppid, releaseYear, confidence, previewUrl)
        }

        private fun resolveLegacyCoverPath(host: String, port: Int, path: String): String? =
            resolveHostRelativePath(host, port, path, requiredPrefix = "/")

        private fun resolveHostRelativePath(
            host: String,
            port: Int,
            path: String,
            requiredPrefix: String,
        ): String? {
            val candidate = path.trim()
            if (candidate.isEmpty() || !candidate.startsWith("/") || candidate.startsWith("//") || '\\' in candidate) return null
            if (port !in 1..65535) return null
            return try {
                val uri = URI(candidate)
                if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null) return null
                val rawPath = uri.rawPath ?: return null
                if (!rawPath.startsWith(requiredPrefix)) return null
                var decodedPath = rawPath
                repeat(4) {
                    val decoded = URLDecoder.decode(decodedPath.replace("+", "%2B"), Charsets.UTF_8.name())
                    if (decoded != decodedPath) decodedPath = decoded
                }
                if ('\\' in decodedPath || !decodedPath.startsWith(requiredPrefix)) return null
                if (decodedPath.split('/').any { it == "." || it == ".." }) return null
                val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
                val base = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host(normalizedHost)
                    .port(port)
                    .build()
                val resolved = base.resolve(candidate) ?: return null
                if (resolved.scheme != "https" || resolved.host != base.host || resolved.port != port) return null
                if (!resolved.encodedPath.startsWith(requiredPrefix)) return null
                resolved.toString()
            } catch (_: Exception) {
                null
            }
        }

        @JvmStatic
        fun selectArtworkUrl(
            host: String,
            port: Int,
            game: PolarisGame,
            kind: String = PolarisGame.ARTWORK_KIND_POSTER,
        ): String? {
            val normalizedKind = kind.trim().lowercase()
            game.artworkAsset(normalizedKind)
                ?.takeIf { it.cached }
                ?.let { resolveManifestPath(host, port, it.url) }
                ?.let { return it }
            if (normalizedKind != PolarisGame.ARTWORK_KIND_POSTER) return null

            val legacy = game.coverUrl.trim()
            if (legacy.startsWith("/")) {
                resolveLegacyCoverPath(host, port, legacy)?.let { return it }
            }
            if (!isSafeArtworkGameId(game.id)) return null
            return resolveManifestPath(host, port, "/polaris/v1/games/${game.id}/cover")
        }

        @JvmStatic
        fun parseArtworkResolveResponse(json: JSONObject): PolarisGame.ArtworkManifest? {
            val manifest = when {
                json.has("assets") -> json
                json.optJSONObject("artwork") != null -> json.optJSONObject("artwork")
                json.optJSONObject("game")?.optJSONObject("artwork") != null ->
                    json.optJSONObject("game")?.optJSONObject("artwork")
                json.optJSONObject("data")?.optJSONObject("artwork") != null ->
                    json.optJSONObject("data")?.optJSONObject("artwork")
                else -> json.optJSONObject("data")
                    ?.optJSONObject("game")
                    ?.optJSONObject("artwork")
            } ?: return null
            if (!manifest.has("assets")) return null
            return PolarisGameJsonAdapter.parseArtworkManifest(manifest)
        }

        @JvmStatic
        fun parseArtworkLibraryUpdateResponse(json: JSONObject): PolarisArtworkUpdateResult? {
            val manifest = parseArtworkResolveResponse(json) ?: return null
            val resolution = json.optJSONObject("resolution") ?: return null
            val status = when (resolution.optString("status")) {
                "healthy" -> PolarisArtworkUpdateStatus.HEALTHY
                "updated" -> PolarisArtworkUpdateStatus.UPDATED
                "custom_preserved" -> PolarisArtworkUpdateStatus.CUSTOM_PRESERVED
                "partial_failure" -> PolarisArtworkUpdateStatus.PARTIAL_FAILURE
                else -> return null
            }
            fun parseKinds(name: String): List<String>? {
                val values = resolution.optJSONArray(name) ?: return null
                if (values.length() > ARTWORK_KINDS.size) return null
                val kinds = (0 until values.length()).map { values.optString(it) }
                if (kinds.any { it !in ARTWORK_KINDS } || kinds.distinct().size != kinds.size) return null
                return kinds
            }
            val requested = parseKinds("requested_kinds") ?: return null
            val remaining = parseKinds("remaining_kinds") ?: return null
            if (remaining.any { it !in requested }) return null
            return PolarisArtworkUpdateResult(manifest, status, requested, remaining)
        }

        @JvmStatic
        fun buildOptimizationPath(
            device: String,
            game: String,
            preference: String = "",
            mode: String = "",
            topologyLocked: Boolean = false,
            mirrorDesktop: Boolean = false,
            forcePrivateAfterSteamClose: Boolean = false,
            width: Int = 0,
            height: Int = 0,
            fps: Float = 0f,
            displayLocked: Boolean = false,
            bitrateKbps: Int = 0,
            bitrateLocked: Boolean = false,
            hdr: Boolean? = null,
            clientMaxFps: Float = 0f
        ): String {
            val preferenceParam = preference
                .takeIf { it.isNotBlank() }
                ?.let { "&preference=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                ?: ""
            // Omitted when blank: an absent mode is the host's legacy cache bucket.
            val modeParam = mode
                .takeIf { it.isNotBlank() }
                ?.let { "&mode=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                ?: ""
            val topologyLockParam = if (topologyLocked) "&topology_locked=1" else ""
            val mirrorDesktopParam = if (mirrorDesktop) "&mirrorDesktop=1" else ""
            val forcePrivateParam = if (forcePrivateAfterSteamClose) {
                "&closeDesktopSteamForPrivate=1&launchMode=force_private_stream"
            } else {
                ""
            }
            val displayParam = if (width > 0 && height > 0 && fps > 0f) {
                "&width=$width&height=$height&fps=$fps"
            } else {
                ""
            }
            val displayLockParam = if (displayLocked && displayParam.isNotEmpty()) "&display_locked=1" else ""
            val bitrateParam = bitrateKbps.takeIf { it > 0 }?.let { "&bitrate_kbps=$it" } ?: ""
            val bitrateLockParam = if (bitrateLocked && bitrateKbps > 0) "&bitrate_locked=1" else ""
            val hdrParam = hdr?.let { "&hdr=${if (it) 1 else 0}" } ?: ""
            val clientMaxFpsParam = clientMaxFps
                .takeIf { it > 0f && it.isFinite() }
                ?.let { "&client_max_fps=$it" }
                ?: ""
            return "/optimize?device=${java.net.URLEncoder.encode(device, "UTF-8")}" +
                "&game=${java.net.URLEncoder.encode(game, "UTF-8")}" +
                preferenceParam +
                modeParam +
                topologyLockParam +
                mirrorDesktopParam +
                forcePrivateParam +
                displayParam +
                displayLockParam +
                bitrateParam +
                bitrateLockParam +
                hdrParam +
                clientMaxFpsParam
        }

        private fun parseStringArray(array: org.json.JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }

        private fun strictString(json: JSONObject?, key: String): String =
            (json?.opt(key) as? String).orEmpty()

        private fun strictBoolean(json: JSONObject?, key: String): Boolean =
            json?.opt(key) as? Boolean ?: false

        @JvmStatic
        internal fun parseTypedRejection(
            httpStatus: Int,
            body: String,
            mutationEnvelope: Boolean,
        ): PolarisApiRejection? {
            if (httpStatus < 400 || body.isBlank()) return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (json.opt("status") !is Boolean || strictBoolean(json, "status")) return null
            if (mutationEnvelope) {
                if (json.opt("changed") !is Boolean || strictBoolean(json, "changed")) return null
                if (strictString(json, "state").isBlank()) return null
            }
            val code = strictString(json, "code").trim().take(96)
            val error = strictString(json, "error")
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .trim()
                .take(320)
            if (code.isBlank() || error.isBlank()) return null
            return PolarisApiRejection(httpStatus, code, error)
        }

        private fun strictIntOrNull(json: JSONObject?, key: String): Int? {
            val number = json?.opt(key) as? Number ?: return null
            val value = number.toDouble()
            if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
                return null
            }
            return value.toInt()
        }

        private fun strictInt(json: JSONObject?, key: String): Int =
            strictIntOrNull(json, key) ?: 0

        private fun strictLong(json: JSONObject?, key: String): Long {
            val number = json?.opt(key) as? Number ?: return 0L
            val value = number.toDouble()
            if (!value.isFinite() || value % 1.0 != 0.0 || value < 0.0 || value > Long.MAX_VALUE.toDouble()) {
                return 0L
            }
            return number.toLong()
        }

        private fun strictOptionalNonNegativeLong(json: JSONObject, key: String): Long {
            if (!json.has(key)) return 0L
            val number = json.opt(key) as? Number
                ?: throw JSONException("$key must be an integer")
            val value = number.toDouble()
            if (!value.isFinite() || value % 1.0 != 0.0 || value < 0.0 ||
                value > Long.MAX_VALUE.toDouble()
            ) {
                throw JSONException("$key must be a non-negative integer")
            }
            return number.toLong()
        }

        private fun strictDouble(json: JSONObject?, key: String): Double? {
            val value = (json?.opt(key) as? Number)?.toDouble() ?: return null
            return value.takeIf { it.isFinite() }
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
                        reason = mode.optString("reason", ""),
                        group = mode.optString("group", ""),
                        unavailableReason = mode.optString("unavailable_reason", ""),
                        sessionOverridable = mode.optBoolean("session_overridable", true)
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
        internal fun buildClientSettingsBody(
            syncMode: String,
            manualOverride: Boolean,
            deviceCapabilities: JSONObject?,
            clientRuntime: JSONObject?,
            appliedStreamSettings: JSONObject?,
            clientPresentation: JSONObject?
        ): JSONObject = JSONObject().apply {
            put("sync_mode", syncMode)
            put("manual_override", manualOverride)
            deviceCapabilities?.let { put("device_capabilities", it) }
            clientRuntime?.let { put("client_runtime", it) }
            appliedStreamSettings?.let { put("applied_stream_settings", it) }
            clientPresentation?.let { put("client_presentation", it) }
        }

        @JvmStatic
        fun buildClientSettingsBodyForTest(
            syncMode: String,
            manualOverride: Boolean,
            deviceCapabilities: JSONObject?,
            clientRuntime: JSONObject?,
            appliedStreamSettings: JSONObject?,
            clientPresentation: JSONObject?
        ): JSONObject = buildClientSettingsBody(
            syncMode = syncMode,
            manualOverride = manualOverride,
            deviceCapabilities = deviceCapabilities,
            clientRuntime = clientRuntime,
            appliedStreamSettings = appliedStreamSettings,
            clientPresentation = clientPresentation
        )

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
                streamDisplayMode
                    ?.let { PolarisStreamDisplayMode.normalize(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("stream_display_mode", it) }
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

        private fun parseLinuxGpuProfile(json: JSONObject?): PolarisSessionStatus.LinuxGpuProfile? {
            if (json == null) return null
            val hasAnyField = listOf(
                "encoder_api",
                "encoder_adapter",
                "capture_device",
                "adapter_matches_capture_device",
                "gpu_native_requested",
                "gpu_native_attempted",
                "gpu_native_succeeded",
                "vaapi_vendor"
            ).any(json::has)
            if (!hasAnyField) return null
            return PolarisSessionStatus.LinuxGpuProfile(
                encoderApi = json.optString("encoder_api", ""),
                encoderAdapter = json.optString("encoder_adapter", ""),
                captureDevice = json.optString("capture_device", ""),
                adapterMatchesCaptureDevice = json.optBoolean("adapter_matches_capture_device", true),
                crossGpuDmabufRisk = json.optBoolean("cross_gpu_dmabuf_risk", false),
                gpuNativeRequested = json.optBoolean("gpu_native_requested", false),
                gpuNativeAttempted = json.optBoolean("gpu_native_attempted", false),
                gpuNativeSucceeded = json.optBoolean("gpu_native_succeeded", false),
                vaapiVendor = json.optString("vaapi_vendor", "")
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
                    resolvedProfileProvenance = strictBoolean(features, "resolved_profile_provenance_v1"),
                    expectedTopologyAssertion = strictBoolean(features, "expected_topology_assertion_v1"),
                    lockScreenControl = features?.optBoolean("lock_screen_control") ?: false,
                    cursorVisibilityControl = features?.optBoolean("cursor_visibility_control") ?: false,
                    liveMediaTelemetry = strictBoolean(features, "live_media_telemetry_v1"),
                    doctorV2Shadow = features?.optBoolean("doctor_v2_shadow_v1") ?: false,
                    doctorV2ShadowEnabled = features?.optBoolean("doctor_v2_shadow_enabled") ?: false,
                    doctorTrials = features?.optBoolean("doctor_trials_v1") ?: false,
                    doctorTrialsEnabled = features?.optBoolean("doctor_trials_enabled") ?: false
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
        fun supportsDeterministicLaunchContract(capabilities: PolarisCapabilities): Boolean =
            capabilities.features.resolvedProfileProvenance &&
                capabilities.features.expectedTopologyAssertion

        private fun parseDoctorStatus(
            doctor: JSONObject?,
            health: JSONObject?,
            aiDoctor: JSONObject?
        ): PolarisSessionStatus.DoctorStatus {
            val explanation = aiDoctor?.optJSONObject("explanation")
                ?.takeIf { strictBoolean(aiDoctor, "status") }
            val doctorVersion = strictInt(doctor, "version")
            val doctorResultId = strictString(doctor, "result_id")
            val authoritativeDoctor = doctorVersion >= 2 && doctorResultId.isNotBlank()
            val doctorPrimaryIssue = strictString(doctor, "primary_issue")
            val primaryIssue = if (authoritativeDoctor) {
                doctorPrimaryIssue.ifBlank { "none" }
            } else {
                doctorPrimaryIssue.takeIf { it.isNotBlank() }
                    ?: health?.optString("primary_issue", "")
                    ?: ""
            }
            val deterministicDoctorSummary = strictString(doctor, "summary").takeIf { it.isNotBlank() }
                ?: strictString(doctor, "simple_state").takeIf { it.isNotBlank() }
                ?: strictString(doctor, "diagnosis").takeIf { it.isNotBlank() }
            val likelyCause = deterministicDoctorSummary
                ?: if (authoritativeDoctor) {
                    if (primaryIssue.equals("none", ignoreCase = true)) {
                        "No confirmed issue"
                    } else {
                        "Review the measured Doctor evidence"
                    }
                } else {
                    health?.optString("summary", "").orEmpty()
                }
            val evidenceItems = parseDoctorEvidenceItems(doctor)
            val evidence = parseDoctorEvidence(doctor)
            val recommendation = doctor?.optJSONObject("recommendation")
            val safeAction = doctor?.optJSONObject("safe_recovery_action")
            val actionPayload = safeAction?.optJSONObject("payload_preview")
            val actionVerification = safeAction?.optJSONObject("verification")
            val actionUndo = safeAction?.optJSONObject("undo")
            val explanationSource = aiDoctor?.optJSONObject("source")
            val targetBitratePresent = actionPayload?.has("target_bitrate_kbps") == true
            val targetBitrateTyped = strictIntOrNull(actionPayload, "target_bitrate_kbps") != null
            val actionSessionGeneration = strictLong(actionPayload, "session_generation")
            val actionControllerRevision = strictLong(actionPayload, "controller_revision")
            val actionEvidenceRevision = strictLong(actionPayload, "evidence_revision")
            val actionContractTyped = safeAction != null &&
                safeAction.opt("id") is String &&
                safeAction.opt("capability") is String &&
                safeAction.opt("kind") is String &&
                safeAction.opt("endpoint") is String &&
                safeAction.opt("method") is String &&
                safeAction.opt("destructive") is Boolean &&
                safeAction.opt("requires_confirmation") is Boolean &&
                safeAction.opt("requires_owner") is Boolean &&
                safeAction.opt("allowed_in_viewer_mode") is Boolean &&
                safeAction.opt("owner_tuning_allowed") is Boolean &&
                safeAction.opt("paired_endpoint") is String &&
                actionPayload?.opt("action_id") is String &&
                actionPayload.opt("source_result_id") is String &&
                actionPayload.opt("app_session_id") is String &&
                actionPayload.opt("session_generation") is Number &&
                actionVerification?.opt("mode") is String &&
                actionVerification.opt("endpoint") is String &&
                strictIntOrNull(actionVerification, "delay_seconds") != null &&
                actionUndo?.opt("supported") is Boolean &&
                actionUndo.opt("endpoint") is String &&
                actionUndo.opt("paired_endpoint") is String
            val tryFirst = listOfNotNull(
                    recommendation?.optString("body", "")?.takeIf { it.isNotBlank() },
                    recommendation?.optString("next_step_label", "")?.takeIf { it.isNotBlank() },
                    safeAction?.optString("label", "")?.takeIf { it.isNotBlank() }
                ).takeIf { it.isNotEmpty() }
                ?: parseStringArray(health?.optJSONArray("recommendations"))
            val confidence = doctor?.optJSONObject("confidence")?.optString("level", "")?.takeIf { it.isNotBlank() }
                ?: doctor?.optString("confidence", "")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: if (doctor != null) "deterministic" else if (primaryIssue.isNotBlank() || likelyCause.isNotBlank()) "fallback" else ""
            val aiExplanation = PolarisSessionStatus.DoctorStatus.AiExplanation(
                available = explanation != null,
                likelyCause = strictString(explanation, "likely_cause"),
                evidence = parseStringArray(explanation?.optJSONArray("evidence")),
                tryFirst = parseStringArray(explanation?.optJSONArray("try_first")),
                confidence = strictString(explanation, "confidence"),
                advancedDetail = strictString(explanation, "advanced_detail"),
                sourceKind = strictString(explanationSource, "kind"),
                sourceMode = strictString(explanationSource, "mode"),
                informational = strictBoolean(explanationSource, "informational")
            )
            return PolarisSessionStatus.DoctorStatus(
                available = doctor != null,
                version = doctorVersion,
                resultId = doctorResultId,
                classification = classifyDoctorIssue(primaryIssue),
                likelyCause = likelyCause,
                evidence = evidence,
                evidenceItems = evidenceItems,
                tryFirst = tryFirst,
                confidence = confidence,
                advancedDetail = doctor?.optJSONObject("advanced_evidence")?.optString("summary", "")
                    ?: "",
                primaryIssue = primaryIssue,
                actionId = strictString(safeAction, "id"),
                actionLabel = strictString(safeAction, "label"),
                actionCapability = strictString(safeAction, "capability"),
                actionKind = strictString(safeAction, "kind"),
                actionEndpoint = strictString(safeAction, "endpoint"),
                actionMethod = strictString(safeAction, "method"),
                actionPayloadId = strictString(actionPayload, "action_id"),
                actionSourceResultId = strictString(actionPayload, "source_result_id"),
                actionContractTyped = actionContractTyped,
                actionAppUuid = strictString(actionPayload, "app_uuid"),
                actionAppSessionId = strictString(actionPayload, "app_session_id"),
                actionSessionGeneration = actionSessionGeneration,
                actionControllerRevision = actionControllerRevision,
                actionEvidenceRevision = actionEvidenceRevision,
                targetBitrateKbps = strictInt(actionPayload, "target_bitrate_kbps"),
                targetBitratePresent = targetBitratePresent,
                targetBitrateTyped = targetBitrateTyped,
                verificationDelaySeconds = strictInt(actionVerification, "delay_seconds"),
                undoSupported = strictBoolean(actionUndo, "supported"),
                undoEndpoint = strictString(actionUndo, "endpoint"),
                requiresConfirmation = strictBoolean(safeAction, "requires_confirmation"),
                requiresOwner = strictBoolean(safeAction, "requires_owner"),
                allowedInViewerMode = strictBoolean(safeAction, "allowed_in_viewer_mode"),
                destructive = strictBoolean(safeAction, "destructive"),
                ownerTuningAllowed = strictBoolean(safeAction, "owner_tuning_allowed"),
                pairedEndpoint = strictString(safeAction, "paired_endpoint"),
                undoPairedEndpoint = strictString(actionUndo, "paired_endpoint"),
                verificationMode = strictString(actionVerification, "mode"),
                verificationEndpoint = strictString(actionVerification, "endpoint"),
                packetLossPct = evidenceItems.firstOrNull { it.id == "packet_loss" }?.value,
                latencyMs = evidenceItems.firstOrNull { it.id == "latency" }?.value,
                destructiveActionAllowed = false,
                explanationSourceKind = aiExplanation.sourceKind,
                explanationSourceMode = aiExplanation.sourceMode,
                explanationInformational = aiExplanation.informational,
                aiExplanation = aiExplanation
            )
        }

        private fun parseDoctorEvidenceItems(
            doctor: JSONObject?
        ): List<PolarisSessionStatus.DoctorStatus.EvidenceItem> {
            val array = doctor?.optJSONArray("evidence") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                PolarisSessionStatus.DoctorStatus.EvidenceItem(
                    id = strictString(item, "id"),
                    status = strictString(item, "status"),
                    source = strictString(item, "source"),
                    value = strictDouble(item, "value"),
                    detail = strictString(item, "detail")
                )
            }
        }

        private fun parseDoctorEvidence(doctor: JSONObject?): List<String> {
            val array = doctor?.optJSONArray("evidence") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val value = array.opt(index)
                when (value) {
                    is JSONObject -> value.optString("detail", value.optString("value", "")).takeIf { it.isNotBlank() }
                    is String -> value.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        }

        private fun classifyDoctorIssue(issue: String): String {
            val normalized = issue.lowercase()
            return when {
                normalized.isBlank() || normalized == "none" -> "UNKNOWN"
                normalized == "network_observation" || normalized == "control_channel_observation" -> "UNKNOWN"
                normalized.contains("network") || normalized.contains("packet") || normalized.contains("jitter") || normalized.contains("latency") -> "NET"
                normalized.contains("client") || normalized.contains("decoder") || normalized.contains("presentation") || normalized.contains("refresh") -> "CLIENT"
                else -> "HOST"
            }
        }

        @JvmStatic
        internal fun parseDoctorActionResponse(json: JSONObject): PolarisDoctorActionResult {
            val verification = json.optJSONObject("verification")
            val undo = json.optJSONObject("undo")
            val evidence = json.optJSONObject("evidence")
            val undoAvailable = undo?.let { value ->
                if (!value.has("available") || value.isNull("available")) {
                    null
                } else {
                    value.opt("available") as? Boolean
                }
            }
            val rawVerificationActionId = strictString(verification, "action_id")
            val verificationActionId = rawVerificationActionId.takeIf {
                it == "verify" || it == "verify_recovery_profile_next_launch"
            }.orEmpty()
            val verificationDelay = strictInt(verification, "delay_seconds")
                .takeIf { verificationActionId.isNotBlank() && it > 0 }
                ?: 0
            val rawUndoActionId = strictString(undo, "action_id")
            val undoActionId = rawUndoActionId.takeIf {
                it == "undo" || it == "undo_recovery_profile_next_launch"
            }.orEmpty()
            val safeUndoAvailable = undoAvailable?.let { available ->
                available && undoActionId.isNotBlank()
            }
            return PolarisDoctorActionResult(
                status = strictBoolean(json, "status"),
                changed = strictBoolean(json, "changed"),
                changedContractValid = json.has("changed") && json.opt("changed") is Boolean,
                state = strictString(json, "state"),
                message = strictString(json, "message"),
                error = strictString(json, "error"),
                runId = strictString(json, "run_id"),
                requestId = strictString(json, "request_id"),
                recoveryState = strictString(json, "recovery_state").ifBlank { strictString(json, "state") },
                appUuid = strictString(json, "app_uuid"),
                appSessionId = strictString(json, "app_session_id"),
                sessionGeneration = strictLong(json, "session_generation"),
                scopeContractValid = json.opt("app_session_id") is String &&
                    json.opt("session_generation") is Number &&
                    strictString(json, "app_session_id").isNotBlank() &&
                    strictLong(json, "session_generation") > 0L,
                expiresAt = strictLong(json, "expires_at"),
                safeProfile = parseRecoverySafeProfile(json.optJSONObject("safe_profile")),
                verificationDelaySeconds = verificationDelay,
                verificationActionId = verificationActionId,
                undoAvailable = safeUndoAvailable,
                undoActionId = if (safeUndoAvailable == true) undoActionId else "",
                evidencePacketLossPct = strictDouble(evidence, "packet_loss_pct"),
                evidenceLatencyMs = strictDouble(evidence, "latency_ms")
            )
        }

        @JvmStatic
        internal fun parseDoctorActionHttpResponse(
            statusCode: Int,
            responseBody: String,
            actionId: String,
            requestedRunId: String,
            requestedRequestId: String = "",
            requestedAppSessionId: String = "",
            requestedSessionGeneration: Long = 0L
        ): PolarisDoctorActionResult {
            val parsed = runCatching {
                parseDoctorActionResponse(JSONObject(responseBody.ifBlank { "{}" }))
            }.getOrNull()
            if (statusCode == 200 && parsed != null && doctorActionResponseMatchesRequest(
                    actionId = actionId,
                    requestedRunId = requestedRunId,
                    requestedRequestId = requestedRequestId,
                    requestedAppSessionId = requestedAppSessionId,
                    requestedSessionGeneration = requestedSessionGeneration,
                    result = parsed
                )) {
                return parsed
            }

            return (parsed ?: PolarisDoctorActionResult(status = false)).copy(
                status = false,
                error = parsed?.error?.takeIf { it.isNotBlank() }
                    ?: if (statusCode == 200) "Invalid Doctor action response" else "Doctor action rejected",
                undoAvailable = false,
                undoActionId = ""
            )
        }

        @JvmStatic
        internal fun doctorActionResponseMatchesRequest(
            actionId: String,
            requestedRunId: String,
            requestedRequestId: String = "",
            requestedAppSessionId: String = "",
            requestedSessionGeneration: Long = 0L,
            result: PolarisDoctorActionResult
        ): Boolean {
            if (!result.status || !result.changedContractValid) return false
            if (requestedAppSessionId.isNotBlank() || requestedSessionGeneration > 0L) {
                if (!result.scopeContractValid ||
                    result.appSessionId != requestedAppSessionId ||
                    result.sessionGeneration != requestedSessionGeneration
                ) {
                    return false
                }
            }
            val runId = result.runId.trim()
            return when (actionId) {
                "recheck_pacing" ->
                    requestedRunId.isBlank() && !result.changed && runId.isBlank() &&
                        result.state == "observed" && result.verificationActionId.isBlank() &&
                        result.undoAvailable != true && result.undoActionId.isBlank()
                "recheck_network" ->
                    requestedRunId.isBlank() && !result.changed && runId.isBlank() &&
                        result.state in setOf("stable", "confirmed_pressure") &&
                        result.verificationActionId.isBlank() &&
                        result.undoAvailable != true && result.undoActionId.isBlank()
                "lower_bitrate", "restore_quality" ->
                    requestedRunId.isBlank() && requestedRequestId.isNotBlank() &&
                        result.requestId == requestedRequestId && runId.isNotBlank() &&
                        runId.startsWith("doctor-run-") && when (result.state) {
                            "applying", "watching" ->
                                result.verificationActionId == "verify" &&
                                    result.verificationDelaySeconds >= 8 &&
                                    result.undoAvailable == true && result.undoActionId == "undo"
                            // A transport timeout can outlive Polaris's host-side
                            // apply/verification watchdog. The idempotency key
                            // still authorizes returning that exact terminal receipt.
                            "rolled_back" -> result.changed &&
                                result.undoAvailable != true && result.undoActionId.isBlank()
                            "superseded" -> !result.changed &&
                                result.undoAvailable != true && result.undoActionId.isBlank()
                            else -> false
                        }
                "verify" ->
                    requestedRunId.isNotBlank() && runId == requestedRunId &&
                        runId.startsWith("doctor-run-") && when (result.state) {
                            "applying" -> result.undoAvailable == true &&
                                result.undoActionId == "undo" &&
                                ((!result.changed &&
                                    result.verificationActionId.isBlank() &&
                                    result.verificationDelaySeconds == 0) ||
                                    (result.changed &&
                                        result.verificationActionId == "verify" &&
                                        result.verificationDelaySeconds >= 8))
                            "watching" -> result.undoAvailable == true &&
                                result.undoActionId == "undo" &&
                                ((result.changed &&
                                    result.verificationActionId == "verify" &&
                                    result.verificationDelaySeconds >= 8) ||
                                    (!result.changed &&
                                        result.verificationActionId.isBlank() &&
                                        result.verificationDelaySeconds == 0))
                            "resolved" -> !result.changed && result.undoAvailable == true &&
                                result.undoActionId == "undo"
                            "rolled_back" -> result.changed && result.undoAvailable != true &&
                                result.undoActionId.isBlank()
                            else -> false
                        }
                "undo" ->
                    requestedRunId.isNotBlank() && runId == requestedRunId &&
                        (runId.startsWith("doctor-run-") || runId.startsWith("recovery-run-")) &&
                        result.state == "undone" &&
                        result.changed && result.undoAvailable != true
                "undo_recovery_profile_next_launch" ->
                    requestedRunId.startsWith("recovery-run-") && runId == requestedRunId &&
                        result.state == "undone" && result.changed && result.undoAvailable != true
                else -> false
            }
        }

        private fun strictOptionalIdentity(json: JSONObject, key: String): String {
            if (!json.has(key)) return ""
            return json.opt(key) as? String
                ?: throw JSONException("$key must be a string")
        }

        @JvmStatic
        internal fun buildDoctorActionBody(
            actionId: String,
            appSessionId: String,
            sessionGeneration: Long = 0L,
            appUuid: String = "",
            sourceResultId: String = "",
            targetBitrateKbps: Int = 0,
            controllerRevision: Long = 0L,
            evidenceRevision: Long = 0L,
            runId: String = "",
            requestId: String = "",
            confirmed: Boolean = false
        ): JSONObject = JSONObject().apply {
            put("action_id", actionId)
            if (appSessionId.isNotBlank() && sessionGeneration > 0L) {
                put("app_session_id", appSessionId)
                put("session_generation", sessionGeneration)
            }
            if (appUuid.isNotBlank()) put("app_uuid", appUuid)
            if (sourceResultId.isNotBlank()) put("source_result_id", sourceResultId)
            if (targetBitrateKbps > 0) put("target_bitrate_kbps", targetBitrateKbps)
            if (controllerRevision > 0L) put("controller_revision", controllerRevision)
            if (evidenceRevision > 0L) put("evidence_revision", evidenceRevision)
            if (runId.isNotBlank()) put("run_id", runId)
            if (requestId.isNotBlank()) put("request_id", requestId)
            if (confirmed) put("confirmed", true)
        }

        @JvmStatic
        internal fun buildLiveMediaTelemetryBody(
            sample: PerfOverlaySample,
            appSessionId: String,
            sessionGeneration: Long,
            targetFps: Double,
            refreshRateHz: Double,
            bitrateKbps: Int,
            topology: String,
            hdr: Boolean
        ): JSONObject = JSONObject().apply {
            put("app_session_id", appSessionId)
            put("session_generation", sessionGeneration)
            put("sample", JSONObject().apply {
                put("monotonic_timestamp_ms", sample.monotonicTimestampMs)
                put("decoder_generation", sample.sessionGeneration)
                put("frames_expected", sample.framesExpected)
                put("frames_received", sample.framesReceived)
                put("frames_rendered", sample.framesRendered)
                put("frames_lost", sample.framesLost)
                put("received_fps", sample.incomingFps)
                put("rendered_fps", sample.renderedFps)
                put("target_fps", targetFps)
                put("refresh_rate_hz", refreshRateHz)
                put("decode_latency_ms", sample.decodeTimeMs)
                sample.hostProcessingLatencyMs?.let { put("host_processing_latency_ms", it) }
                put("width", sample.width)
                put("height", sample.height)
                put("codec", sample.codec)
                put("bitrate_kbps", bitrateKbps)
                put("topology", topology)
                put("hdr", hdr)
            })
        }

        private fun parseRecoverySafeProfile(json: JSONObject?): PolarisSessionStatus.RecoverySafeProfile =
            PolarisSessionStatus.RecoverySafeProfile(
                streamDisplayMode = json?.optString("stream_display_mode", "") ?: "",
                width = json?.optInt("width", 0) ?: 0,
                height = json?.optInt("height", 0) ?: 0,
                targetFps = json?.optDouble("target_fps", 0.0)?.toFloat() ?: 0f,
                targetBitrateKbps = json?.optInt("target_bitrate_kbps", 0) ?: 0,
                preferredCodec = json?.optString("preferred_codec", "") ?: "",
                hdr = json?.optBoolean("hdr", false) ?: false,
                preservePairedResolution = json?.optBoolean("preserve_paired_resolution", false) ?: false,
                requiresFreshLaunch = json?.optBoolean("requires_fresh_launch", false) ?: false
            )

        private fun parseRecoveryReceipt(json: JSONObject?): PolarisSessionStatus.RecoveryReceipt {
            if (json == null) return PolarisSessionStatus.RecoveryReceipt()
            val undo = json.optJSONObject("undo")
            val verification = json.optJSONObject("verification")
            return PolarisSessionStatus.RecoveryReceipt(
                status = json.optBoolean("status", true),
                state = json.optString("recovery_state", json.optString("state", "none")),
                runId = json.optString("run_id", ""),
                sourceResultId = json.optString("source_result_id", ""),
                appUuid = json.optString("app_uuid", ""),
                expiresAt = json.optLong("expires_at", 0L),
                message = json.optString("message", ""),
                error = json.optString("error", ""),
                safeProfile = parseRecoverySafeProfile(json.optJSONObject("safe_profile")),
                undoSupported = undo?.optBoolean("supported", false) ?: false,
                undoAvailable = undo?.optBoolean("available", false) ?: false,
                undoActionId = undo?.optString("action_id", "") ?: "",
                verificationActionId = verification?.optString("action_id", "") ?: "",
                deprecated = json.optBoolean("deprecated", false),
                applicable = json.optBoolean("applicable", true),
                cancellable = json.optBoolean("cancellable", false),
                reasonCode = json.optString("reason_code", "")
            )
        }

        private fun parseRecoveryRecords(json: org.json.JSONArray?): List<PolarisSessionStatus.RecoveryReceipt> =
            if (json == null) emptyList() else (0 until json.length()).mapNotNull { index ->
                json.optJSONObject(index)?.let(::parseRecoveryReceipt)
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
            val doctor = json.optJSONObject("doctor") ?: health?.optJSONObject("doctor")
            val aiDoctor = json.optJSONObject("ai_doctor_explanation")
                ?: json.optJSONObject("doctor_ai_explanation")
                ?: json.optJSONObject("ai_explanation")
            val autoQuality = json.optJSONObject("auto_quality")
                ?: health?.optJSONObject("recovery_policy")
            val profileState = json.optJSONObject("profile_state")
            val linuxGpuProfile = json.optJSONObject("linux_gpu_profile")
                ?: streamPolicy?.optJSONObject("linux_gpu_profile")
            val recovery = parseRecoveryReceipt(json.optJSONObject("recovery"))
            val recoveryRecords = parseRecoveryRecords(json.optJSONArray("recovery_records"))
            val rawClientRole = json.opt("client_role")
            val clientRole = (rawClientRole as? String)?.lowercase().orEmpty()
            val clientRoleContractValid = json.has("client_role") &&
                clientRole in setOf("none", "owner", "viewer")
            val rawOwnedByClient = json.opt("owned_by_client")
            val ownedByClientContractValid = json.has("owned_by_client") &&
                rawOwnedByClient is Boolean
            val rawHostTuningAllowed = controls?.opt("host_tuning_allowed")
            val hostTuningContractValid = controls != null &&
                controls.has("host_tuning_allowed") &&
                rawHostTuningAllowed is Boolean
            val authorityContractValid = clientRoleContractValid &&
                ownedByClientContractValid && hostTuningContractValid
            val sessionGeneration = strictOptionalNonNegativeLong(json, "session_generation")

            return PolarisSessionStatus(
                state = json.optString("state", "unknown"),
                streamingActive = json.optBoolean("streaming_active", false),
                shutdownRequested = json.optBoolean("shutdown_requested", false),
                game = json.optString("game", ""),
                gameId = json.optInt("game_id", 0),
                gameUuid = strictOptionalIdentity(json, "game_uuid"),
                sessionToken = strictOptionalIdentity(json, "session_token"),
                appSessionId = strictOptionalIdentity(json, "app_session_id"),
                appSessionIdPresent = json.has("app_session_id"),
                sessionGeneration = sessionGeneration,
                ownerUniqueId = json.optString("owner_unique_id", ""),
                ownerDeviceName = json.optString("owner_device_name", ""),
                clientRole = clientRole.takeIf { clientRoleContractValid } ?: "none",
                viewerCount = json.optInt("viewer_count", 0),
                ownedByClient = rawOwnedByClient as? Boolean ?: false,
                authorityContractValid = authorityContractValid,
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
                    hostTuningAllowed = rawHostTuningAllowed as? Boolean,
                    quitAllowed = strictBoolean(controls, "quit_allowed"),
                    shutdownInProgress = strictBoolean(controls, "shutdown_in_progress"),
                    clientCommandsEnabled = strictBoolean(controls, "client_commands_enabled"),
                    deviceCommandsEnabled = strictBoolean(controls, "device_commands_enabled")
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
                    gpuNativeOverrideActive = displayMode?.optBoolean("gpu_native_override_active", false) ?: false,
                    mirrorDesktop = strictBoolean(displayMode, "mirror_desktop"),
                    forcePrivateAfterSteamClose = strictBoolean(displayMode, "force_private_after_steam_close"),
                    warning = displayMode?.optString("warning", "") ?: ""
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
                    path = capture?.optString("path", json.optString("capture_path", ""))
                        ?: json.optString("capture_path", ""),
                    reason = capture?.optString("reason", json.optString("capture_path_reason", ""))
                        ?: json.optString("capture_path_reason", ""),
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
                linuxGpuProfile = parseLinuxGpuProfile(linuxGpuProfile),
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
                    hdrEffectiveMode = health?.optString("hdr_effective_mode", "") ?: "",
                    hdrDowngradeReason = health?.optString("hdr_downgrade_reason", "") ?: "",
                    hdrDowngradeMessage = health?.optString("hdr_downgrade_message", "") ?: "",
                    hdrSource = health?.optString("hdr_source", "") ?: "",
                    networkRisk = health?.optString("network_risk", "") ?: "",
                    hostRenderLimited = health?.optBoolean("host_render_limited", false) ?: false,
                    renderFpsGap = health?.optDouble("render_fps_gap", 0.0) ?: 0.0,
                    recoveryProfile = health?.optString("recovery_profile", "") ?: "",
                    relaunchRecommended = health?.optBoolean("relaunch_recommended", false) ?: false
                ),
                doctor = parseDoctorStatus(doctor, health, aiDoctor),
                recovery = recovery,
                recoveryRecords = recoveryRecords
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

    // Instance-scoped: the key and trust managers are assigned once in init, and re-pairing
    // constructs a new PolarisApiClient, so cached TLS state never outlives the certificate
    // material it was built from. Never cache these at companion/static scope.
    @Volatile
    private var perCallClientCache: OkHttpClient? = null

    private fun buildPerCallClient(): OkHttpClient {
        val keyManager = apiKeyManager
        val trustManager = apiTrustManager
        return if (keyManager == null || trustManager == null) {
            client
        } else {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            client.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .build()
        }
    }

    // The pool must be set explicitly: newBuilder() copies the API path's no-keep-alive pool.
    private val artworkClient: OkHttpClient by lazy {
        buildArtworkHttpClient(clientForCall())
            .newBuilder()
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()
    }

    private fun clientForCall(): OkHttpClient =
        perCallClientCache ?: synchronized(this) {
            perCallClientCache ?: buildPerCallClient().also { perCallClientCache = it }
        }

    // Dropping the cached client discards its SSLContext and with it any cached TLS
    // sessions, so the next call performs a full handshake instead of offering a
    // resumption the server may refuse.
    private fun resetCallClient() {
        synchronized(this) { perCallClientCache = null }
    }

    private fun executeWithTransientRetry(request: Request): okhttp3.Response =
        runWithTransientTlsRetry(onTransient = { resetCallClient() }) { execute(request) }

    private fun executeNonRetryable(request: Request): okhttp3.Response =
        buildNonRetryableHttpClient(clientForCall()).newCall(
            request.newBuilder()
                .header("Connection", "close")
                .build()
        ).execute()

    private fun executeIdempotentDoctorAction(request: Request): okhttp3.Response = try {
        executeNonRetryable(request)
    } catch (e: IOException) {
        // The host binds request_id to the exact owner/app/generation scope.
        // Repeating this same body can only recover the existing receipt.
        resetCallClient()
        executeNonRetryable(request)
    }

    private fun executeLaunchPolicyGet(request: Request): okhttp3.Response =
        clientForCall().newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
            .newCall(
                request.newBuilder()
                    .header("Connection", "close")
                    .build()
            )
            .execute()

	private fun execute(request: Request) = clientForCall().newCall(
		request.newBuilder()
			.header("Connection", "close")
			.build()
	).execute()

    private fun executeArtwork(request: Request) = artworkClient.newCall(request).execute()

    private fun executeGetWithRetry(request: Request, attempts: Int = 3): okhttp3.Response {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return execute(request)
            } catch (e: Exception) {
                lastError = e
                if (e is SSLException) {
                    resetCallClient()
                }
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
    /**
     * Post this device's half of a support report to the paired host.
     *
     * The host holds it alongside its own evidence so both halves land in one
     * bundle. A streaming bug needs both, and a user should not have to export
     * from two devices and keep the two matched.
     *
     * @param payload Already-redacted JSON body; see NovaSupportReport.hostPayload.
     * @return true when the host accepted it. A host predating this endpoint
     *         answers 404 and returns false, which is why the caller always has a
     *         share-sheet fallback rather than treating this as the only route.
     */
    fun submitSupportReport(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/support/client-report")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), payload))
                .build()
            executeWithTransientRetry(request).use { response ->
                if (response.code != 200) {
                    LimeLog.warning("Nova: host rejected the support report code=${response.code}")
                    return false
                }
                true
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: could not send the support report to the host: ${errorMessage(e)}")
            false
        }
    }

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
     * Resolve host identity for a new launch without publishing process-global
     * feature state. Network/protocol ambiguity is UNKNOWN and must fail closed;
     * only explicit 404s from both Polaris routes identify a stock host.
     */
    fun identifyLaunchHost(): PolarisLaunchHostKind {
        val probeClient = clientForCall().newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        fun executeProbe(path: String) = probeClient.newCall(
            Request.Builder()
                .url("$baseUrl$path")
                .header("Connection", "close")
                .build()
        ).execute()

        return try {
            executeProbe("/capabilities").use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: return PolarisLaunchHostKind.UNKNOWN
                        val capabilities = runCatching {
                            parseCapabilitiesResponse(JSONObject(body))
                        }.getOrNull() ?: return PolarisLaunchHostKind.UNKNOWN
                        if (!capabilities.server.equals("polaris", ignoreCase = true)) {
                            PolarisLaunchHostKind.UNKNOWN
                        } else if (supportsDeterministicLaunchContract(capabilities)) {
                            PolarisLaunchHostKind.CURRENT_POLARIS
                        } else {
                            PolarisLaunchHostKind.LEGACY_POLARIS
                        }
                    }
                    404 -> executeProbe("/session/status").use { fallback ->
                        when (fallback.code) {
                            200 -> PolarisLaunchHostKind.LEGACY_POLARIS
                            404 -> PolarisLaunchHostKind.NON_POLARIS
                            else -> PolarisLaunchHostKind.UNKNOWN
                        }
                    }
                    else -> PolarisLaunchHostKind.UNKNOWN
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Launch host identity probe failed: ${errorMessage(e)}")
            PolarisLaunchHostKind.UNKNOWN
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
            val canAffectActiveStream = streamDisplayMode != null ||
                targetBitrateKbps != null || adaptiveBitrateEnabled != null ||
                disconnectResumeTimeoutSeconds != null
            if (canAffectActiveStream) {
                val status = getSessionStatus() ?: return null
                if (status.isStreaming) {
                    if (!status.canAdjustHostTuning || status.appSessionId.isBlank() ||
                        status.sessionGeneration <= 0L
                    ) {
                        return null
                    }
                    body.put("app_session_id", status.appSessionId)
                    body.put("session_generation", status.sessionGeneration)
                }
            }
            val request = Request.Builder()
                .url("$baseUrl/client-settings")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            executeWithTransientRetry(request).use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.code != 200) {
                    LimeLog.warning("Nova: Client settings update rejected code=${response.code}")
                    parseTypedRejection(response.code, responseBody, mutationEnvelope = true)?.let {
                        throw PolarisApiRejectedException(it)
                    }
                    return null
                }
                parseClientSettingsResponse(JSONObject(responseBody.ifBlank { return null }))
            }
        } catch (e: PolarisApiRejectedException) {
            throw e
        } catch (e: Exception) {
            LimeLog.warning("Nova: Client settings update failed: ${errorMessage(e)}")
            null
        }
    }

    private fun getGamesPageOrThrow(
        search: String = "",
        source: String = "",
        limit: Int = 50,
        offset: Int = 0,
    ): List<PolarisGame> {
        var url = "$baseUrl/games?limit=${limit.coerceAtLeast(1)}&offset=${offset.coerceAtLeast(0)}"
        if (search.isNotEmpty()) url += "&search=$search"
        if (source.isNotEmpty()) url += "&source=$source"

        val request = Request.Builder().url(url).build()
        return executeGetWithRetry(request).use { response ->
            if (response.code != 200) {
                throw IOException("game library HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("empty game library response")
            try {
                val gamesArray = org.json.JSONObject(body).optJSONArray("games")
                    ?: throw IOException("invalid game library response")
                (0 until gamesArray.length()).map {
                    PolarisGameJsonAdapter.fromJson(gamesArray.getJSONObject(it))
                }
            } catch (e: IOException) {
                throw e
            } catch (_: Exception) {
                throw IOException("invalid game library response")
            }
        }
    }

    /**
     * Fetch one best-effort game-library page for legacy callers.
     */
    fun getGames(search: String = "", source: String = "", limit: Int = 50, offset: Int = 0): List<PolarisGame> {
        return try {
            getGamesPageOrThrow(search = search, source = source, limit = limit, offset = offset)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LimeLog.warning("Nova: Game library page fetch failed")
            emptyList()
        }
    }

    /**
     * Fetch every local page. Any failed page aborts instead of returning a successful prefix.
     */
    fun getAllGames(pageSize: Int = 100): List<PolarisGame> =
        paginateAllGames(pageSize) { offset ->
            getGamesPageOrThrow(limit = pageSize, offset = offset)
        }

    /**
     * Get the legacy cover art URL for a game. Unsafe IDs fail closed.
     */
    fun getCoverUrl(gameId: String): String =
        if (isSafeArtworkGameId(gameId)) {
            resolveManifestPath(serverAddress, resolvedHttpsPort, "/polaris/v1/games/$gameId/cover").orEmpty()
        } else ""

    fun getPreferredCoverUrl(game: PolarisGame): String =
        selectArtworkUrl(serverAddress, resolvedHttpsPort, game, PolarisGame.ARTWORK_KIND_POSTER).orEmpty()

    fun clearCoverCache() {
        coverCache.evictAll()
    }

    private fun parseBoundedArtworkJson(body: okhttp3.ResponseBody): JSONObject? {
        if (body.contentLength() > MAX_ARTWORK_JSON_BYTES) return null
        val bytes = PolarisArtworkDiskCache.readBounded(body.byteStream(), MAX_ARTWORK_JSON_BYTES) ?: return null
        return parseArtworkJsonBytes(bytes)
    }

    fun resolveArtwork(gameId: String, force: Boolean = false): PolarisGame.ArtworkManifest? {
        if (!isSafeArtworkGameId(gameId)) return null
        if (force) artworkResolveOnce.invalidate(gameId)
        return artworkResolveOnce.resolve(gameId) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/games/$gameId/artwork/resolve")
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), "{}"))
                    .build()
                executeArtwork(request).use { response ->
                    if (!response.isSuccessful) return@resolve null
                    response.body.let(::parseBoundedArtworkJson)?.let(::parseArtworkResolveResponse)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LimeLog.warning("Nova: artwork resolve failed for $gameId: ${errorMessage(e)}")
                null
            }
        }
    }


    fun updateArtworkForLibrary(gameId: String): PolarisArtworkUpdateResult {
        require(isSafeArtworkGameId(gameId))
        val body = buildArtworkLibraryUpdateBody()
        try {
            val request = Request.Builder()
                .url("$baseUrl/games/$gameId/artwork/resolve")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
                .build()
            return executeArtwork(request).use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404 || response.code == 405 || response.code == 501) {
                        throw PolarisArtworkLibraryUpdateUnavailableException()
                    }
                    throw IOException("artwork library update HTTP ${response.code}")
                }
                val json = parseBoundedArtworkJson(response.body)
                    ?: throw IOException("invalid artwork library update response")
                val result = parseArtworkLibraryUpdateResponse(json)
                    ?: throw PolarisArtworkLibraryUpdateUnavailableException()
                artworkResolveOnce.invalidate(gameId)
                clearCoverCache()
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LimeLog.warning("Nova: artwork library update failed for $gameId: ${errorMessage(e)}")
            throw e
        }
    }

    fun searchArtworkCandidates(gameId: String, query: String): List<PolarisArtworkMatchCandidate> {
        if (!isSafeArtworkGameId(gameId)) return emptyList()
        val sanitizedQuery = sanitizedCandidateTitle(query) ?: return emptyList()
        return try {
            val url = "$baseUrl/games/$gameId/artwork/candidates".toHttpUrl().newBuilder()
                .addQueryParameter("query", sanitizedQuery)
                .build()
            executeArtwork(Request.Builder().url(url).build()).use { response ->
                if (!response.isSuccessful) throw IOException("artwork candidate search HTTP ${response.code}")
                val json = parseBoundedArtworkJson(response.body)
                    ?: throw IOException("artwork candidate search returned an invalid response")
                parseArtworkCandidates(json, gameId, serverAddress, resolvedHttpsPort)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LimeLog.warning("Nova: artwork candidate search failed for $gameId: ${errorMessage(e)}")
            throw e
        }
    }

    fun listArtworkChoices(
        gameId: String,
        candidate: PolarisArtworkMatchCandidate,
        kind: String,
    ): List<PolarisArtworkChoice> {
        require(isSafeArtworkGameId(gameId))
        val normalizedKind = kind.trim().lowercase()
        require(normalizedKind in ARTWORK_KINDS)
        val body = buildArtworkChoiceBody(candidate)
        try {
            val request = Request.Builder()
                .url("$baseUrl/games/$gameId/artwork/choices/$normalizedKind")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
                .build()
            return executeArtwork(request).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("artwork choices HTTP ${response.code}")
                }
                val json = parseBoundedArtworkJson(response.body)
                    ?: throw IOException("invalid artwork choices response")
                parseArtworkChoices(json, gameId, normalizedKind, serverAddress, resolvedHttpsPort)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LimeLog.warning("Nova: artwork choices failed for $gameId/$normalizedKind: ${errorMessage(e)}")
            throw e
        }
    }


    fun applyArtworkMatch(
        gameId: String,
        candidate: PolarisArtworkMatchCandidate,
        kinds: List<String> = ARTWORK_KINDS,
    ): PolarisGame.ArtworkManifest? {
        if (!isSafeArtworkGameId(gameId)) return null
        val body = runCatching { buildArtworkMatchBody(candidate, kinds) }.getOrNull() ?: return null
        val request = Request.Builder()
            .url("$baseUrl/games/$gameId/artwork/match")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        return executeArtworkManifestMutation(gameId, request, "apply")
    }

    fun applyArtworkSelections(
        gameId: String,
        candidate: PolarisArtworkMatchCandidate,
        selections: Map<String, PolarisArtworkChoice>,
    ): PolarisGame.ArtworkManifest? {
        require(isSafeArtworkGameId(gameId))
        val body = buildArtworkSelectionBody(candidate, selections)
        val request = Request.Builder()
            .url("$baseUrl/games/$gameId/artwork/match")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        return executeArtworkManifestMutation(gameId, request, "selected apply")
    }


    fun clearArtworkOverride(gameId: String): PolarisGame.ArtworkManifest? {
        if (!isSafeArtworkGameId(gameId)) return null
        val request = Request.Builder()
            .url("$baseUrl/games/$gameId/artwork/override")
            .delete()
            .build()
        return executeArtworkManifestMutation(gameId, request, "clear")
    }

    private fun executeArtworkManifestMutation(gameId: String, request: Request, operation: String): PolarisGame.ArtworkManifest? {
        return try {
            executeArtwork(request).use { response ->
                if (!response.isSuccessful) return null
                val json = parseBoundedArtworkJson(response.body)
                val manifest = json?.let(::parseArtworkResolveResponse)
                if (manifest != null) {
                    artworkResolveOnce.invalidate(gameId)
                    clearCoverCache()
                }
                manifest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LimeLog.warning("Nova: artwork $operation failed for $gameId: ${errorMessage(e)}")
            null
        }
    }

    fun loadArtworkCandidatePreviewInto(view: ImageView, candidate: PolarisArtworkMatchCandidate) {
        val url = candidate.posterPreviewUrl ?: return
        loadTrustedArtworkPreviewInto(view, url)
    }

    fun loadArtworkChoicePreviewInto(view: ImageView, choice: PolarisArtworkChoice) {
        if (choice.kind !in ARTWORK_KINDS) return
        loadTrustedArtworkPreviewInto(view, choice.previewUrl)
    }

    private fun loadTrustedArtworkPreviewInto(view: ImageView, url: String) {
        if (!isTrustedCandidatePreviewUrl(url, serverAddress, resolvedHttpsPort)) return
        val requestMarker = Any()
        view.setTag(R.id.nova_artwork_request_key, requestMarker)
        view.setImageResource(R.drawable.nova_cover_placeholder)
        (view.getTag(R.id.nova_artwork_job) as? Job)?.cancel()
        val job = imageScope.launch {
            val fetched = fetchArtwork(url, PREVIEW_TARGET_WIDTH, PREVIEW_TARGET_HEIGHT) ?: return@launch
            withContext(Dispatchers.Main) {
                if (view.getTag(R.id.nova_artwork_request_key) === requestMarker) view.setImageBitmap(fetched.bitmap)
            }
        }
        view.setTag(R.id.nova_artwork_job, job)
        job.invokeOnCompletion {
            view.post { if (view.getTag(R.id.nova_artwork_job) === job) view.setTag(R.id.nova_artwork_job, null) }
        }
    }


    fun loadCoverInto(view: ImageView, game: PolarisGame) {
        loadArtworkInto(view, game, PolarisGame.ARTWORK_KIND_POSTER)
    }

    fun loadArtworkInto(view: ImageView, game: PolarisGame, kind: String) {
        val normalizedKind = kind.trim().lowercase()
        val manifestAsset = game.artworkAsset(normalizedKind)?.takeIf { it.cached }
        val manifestUrl = manifestAsset?.let {
            resolveManifestPath(serverAddress, resolvedHttpsPort, it.url)
        }
        val usesManifest = manifestUrl != null
        val imageUrl = manifestUrl
            ?: selectArtworkUrl(serverAddress, resolvedHttpsPort, game, normalizedKind)
        val revision = if (usesManifest) game.artwork?.revision.orEmpty() else ""
        // The decode bucket is part of the cache key: a poster-res bitmap must never be
        // served where the full-screen hero bucket is expected, and vice versa.
        val (targetWidth, targetHeight) = artworkTargetSize(normalizedKind)
        val sizeBucket = "w${targetWidth}h$targetHeight"
        val cacheKey = if (usesManifest) {
            "polaris-artwork:${game.id}:$normalizedKind:$revision:$sizeBucket:$manifestUrl"
        } else {
            "polaris-cover:${game.id}:$normalizedKind:$sizeBucket:${game.coverUrl}"
        }

        view.setTag(R.id.nova_artwork_request_key, cacheKey)
        view.setImageResource(R.drawable.nova_cover_placeholder)
        (view.getTag(R.id.nova_artwork_job) as? Job)?.cancel()
        if (imageUrl == null) return

        coverCache.get(cacheKey)?.let { cached ->
            view.setImageBitmap(cached)
            return
        }

        val job = imageScope.launch {
            val exactDisk = if (usesManifest) {
                artworkDiskCache.load(game.id, normalizedKind, revision, allowStale = false, targetWidth, targetHeight)
            } else null
            val bitmap = exactDisk ?: run {
                val fetched = fetchArtwork(imageUrl, targetWidth, targetHeight)
                if (fetched != null) {
                    if (usesManifest) {
                        artworkDiskCache.store(
                            game.id,
                            normalizedKind,
                            revision,
                            fetched.bytes,
                            fetched.mimeType,
                        )
                    }
                    fetched.bitmap
                } else if (usesManifest) {
                    artworkDiskCache.load(game.id, normalizedKind, revision, allowStale = true, targetWidth, targetHeight)
                } else null
            }
            withContext(Dispatchers.Main) {
                if (view.getTag(R.id.nova_artwork_request_key) != cacheKey) return@withContext
                if (bitmap != null) {
                    coverCache.put(cacheKey, bitmap)
                    view.setImageBitmap(bitmap)
                } else {
                    view.setImageResource(R.drawable.nova_cover_placeholder)
                    LimeLog.warning("Nova: $normalizedKind artwork load failed for ${game.name}")
                }
            }
        }
        view.setTag(R.id.nova_artwork_job, job)
        job.invokeOnCompletion {
            view.post { if (view.getTag(R.id.nova_artwork_job) === job) view.setTag(R.id.nova_artwork_job, null) }
        }
    }

    private data class FetchedArtwork(val bytes: ByteArray, val mimeType: String, val bitmap: Bitmap)

    private suspend fun fetchArtwork(url: String, targetWidth: Int = 0, targetHeight: Int = 0): FetchedArtwork? {
        val requestClass = artworkRequestLogLabel(url)
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(url).build()
                executeArtwork(request).use { response ->
                    if (!response.isSuccessful) {
                        LimeLog.warning("Nova: artwork request failed [$requestClass] code=${response.code}")
                        return null
                    }
                    val body = response.body ?: return null
                    val mimeType = response.header("Content-Type") ?: body.contentType()?.toString()
                    if (!PolarisArtworkDiskCache.isSupportedImageMime(mimeType)) {
                        LimeLog.warning("Nova: artwork MIME rejected [$requestClass] type=${mimeType ?: "missing"}")
                        return null
                    }
                    val contentLength = body.contentLength()
                    if (contentLength > PolarisArtworkDiskCache.MAX_IMAGE_BYTES) return null
                    val bytes = body.byteStream().use {
                        PolarisArtworkDiskCache.readBounded(it, PolarisArtworkDiskCache.MAX_IMAGE_BYTES)
                    } ?: return null
                    if (!PolarisArtworkDiskCache.hasSupportedImageSignature(bytes, mimeType)) return null
                    val bitmap = PolarisArtworkDiskCache.decodeBounded(bytes, targetWidth, targetHeight)
                    if (bitmap != null) return FetchedArtwork(bytes, mimeType.orEmpty(), bitmap)
                    LimeLog.warning("Nova: artwork decode failed [$requestClass] bytes=${bytes.size}")
                    return null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 2) {
                    LimeLog.warning("Nova: artwork fetch failed [$requestClass]: ${e.javaClass.simpleName}")
                }
                if (attempt < 2) delay((attempt + 1) * 150L)
            }
        }
        return null
    }

    // Decode buckets are fixed per artwork kind rather than measured from the view:
    // AndroidView cells call this before layout, and stable buckets keep cache keys stable.
    private fun artworkTargetSize(kind: String): Pair<Int, Int> = when (kind) {
        PolarisGame.ARTWORK_KIND_HERO -> 1920 to 1080
        PolarisGame.ARTWORK_KIND_LOGO -> 640 to 360
        PolarisGame.ARTWORK_KIND_ICON -> 256 to 256
        else -> PREVIEW_TARGET_WIDTH to PREVIEW_TARGET_HEIGHT
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
            executeWithTransientRetry(request).use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: MangoHud toggle failed: ${errorMessage(e)}")
            false
        }
    }

    fun setSteamLaunchMode(gameId: String, mode: String): String? {
        return try {
            val normalizedMode = PolarisGame.SteamLaunchContract.normalizeMode(mode)
            val body = buildSteamLaunchModeUpdateBody(gameId, normalizedMode)
            val request = Request.Builder()
                .url("$baseUrl/games/$gameId/steam-launch-mode")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            executeWithTransientRetry(request).use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.code != 200) {
                    LimeLog.warning("Nova: Steam launch mode update rejected code=${response.code}")
                    return null
                }
                val json = JSONObject(responseBody.ifBlank { "{}" })
                PolarisGame.SteamLaunchContract.normalizeMode(json.optString("mode", normalizedMode))
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Steam launch mode update failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Execute an evidence-gated Doctor action on Polaris.
     */
    fun runDoctorAction(
        actionId: String,
        appSessionId: String,
        sessionGeneration: Long = 0L,
        appUuid: String = "",
        sourceResultId: String = "",
        targetBitrateKbps: Int = 0,
        controllerRevision: Long = 0L,
        evidenceRevision: Long = 0L,
        runId: String = "",
        requestId: String = "",
        confirmed: Boolean = false
    ): PolarisDoctorActionResult? {
        if (actionId in setOf("lower_bitrate", "restore_quality") && requestId.isBlank()) {
            return PolarisDoctorActionResult(
                status = false,
                error = "Doctor Auto Fix requires an idempotency request ID"
            )
        }
        return try {
            val body = buildDoctorActionBody(
                actionId = actionId,
                appSessionId = appSessionId,
                sessionGeneration = sessionGeneration,
                appUuid = appUuid,
                sourceResultId = sourceResultId,
                targetBitrateKbps = targetBitrateKbps,
                controllerRevision = controllerRevision,
                evidenceRevision = evidenceRevision,
                runId = runId,
                requestId = requestId,
                confirmed = confirmed
            )
            val request = Request.Builder()
                .url("$baseUrl/doctor/action")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            val response = if (actionId in setOf("lower_bitrate", "restore_quality")) {
                executeIdempotentDoctorAction(request)
            } else {
                // Verification and Undo are state transitions and remain single-shot.
                executeNonRetryable(request)
            }
            response.use {
                parseDoctorActionHttpResponse(
                    statusCode = it.code,
                    responseBody = it.body?.string().orEmpty(),
                    actionId = actionId,
                    requestedRunId = runId,
                    requestedRequestId = requestId,
                    requestedAppSessionId = appSessionId,
                    requestedSessionGeneration = sessionGeneration
                )
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Doctor action failed: ${errorMessage(e)}")
            null
        }
    }

    /**
     * Set the stream bitrate mid-session without reconnecting.
     */
    fun setBitrate(bitrateKbps: Int): Boolean {
        return try {
            val status = getSessionStatus()?.takeIf {
                it.canAdjustHostTuning && it.appSessionId.isNotBlank() && it.sessionGeneration > 0L
            } ?: return false
            val body = org.json.JSONObject().apply {
                put("bitrate_kbps", bitrateKbps)
                put("app_session_id", status.appSessionId)
                put("session_generation", status.sessionGeneration)
            }
            val request = Request.Builder()
                .url("$baseUrl/session/bitrate")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            executeWithTransientRetry(request).use { response ->
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
            val status = getSessionStatus()?.takeIf {
                it.canAdjustHostTuning && it.appSessionId.isNotBlank() && it.sessionGeneration > 0L
            } ?: return false
            val body = org.json.JSONObject().apply {
                put("enabled", enabled)
                put("app_session_id", status.appSessionId)
                put("session_generation", status.sessionGeneration)
            }
            val request = Request.Builder()
                .url("$baseUrl/session/adaptive-bitrate")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            executeWithTransientRetry(request).use { response ->
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
            executeWithTransientRetry(request).use { response ->
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
            executeWithTransientRetry(request).use { response ->
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
            val body = buildClientSettingsBody(
                syncMode = syncMode,
                manualOverride = manualOverride,
                deviceCapabilities = deviceCapabilities,
                clientRuntime = clientRuntime,
                appliedStreamSettings = appliedStreamSettings,
                clientPresentation = clientPresentation
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
    fun sendDoctorEvidenceReport(
        device: String,
        uniqueId: String,
        game: String,
        evidence: Map<String, Any>,
        endReason: String
    ): Boolean {
        return try {
            val raw = JSONObject()
            evidence.forEach { (key, value) -> raw.put(key, value) }
            raw.put("schema_version", 2)
            raw.put("contract", "doctor_v2_raw")
            raw.put("end_reason", endReason)

            val body = JSONObject().apply {
                put("device", device)
                if (uniqueId.isNotBlank()) put("unique_id", uniqueId)
                put("game", game)
                put("doctor_v2", raw)
                // Temporary observational v1 compatibility. Only raw numeric
                // evidence is mirrored; no diagnosis, action, safe setting, or
                // relaunch recommendation can be supplied by Nova.
                listOf(
                    "avg_fps", "target_fps", "low_1_percent_fps", "min_fps",
                    "avg_latency_ms", "avg_bitrate_kbps",
                    "packet_loss_pct", "packet_loss_source", "codec", "duration_s", "samples"
                ).forEach { key -> if (raw.has(key)) put(key, raw.get(key)) }
                put("end_reason", endReason)
            }
            val request = Request.Builder()
                .url("$baseUrl/session/report")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            execute(request).use { response -> response.code == 200 }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Doctor evidence report failed: ${errorMessage(e)}")
            false
        }
    }

    /**
     * Upload one raw monotonic Doctor v2 sample while the stream is active.
     * Polaris derives session/app scope and every diagnosis; Nova supplies no
     * action, confidence, safe setting, or launch recommendation.
     */
    fun sendDoctorV2Sample(
        sample: PerfOverlaySample,
        targetFps: Double,
        refreshRateHz: Double,
        bitrateKbps: Int,
        topology: String,
        hdr: Boolean
    ): Boolean {
        return try {
            val raw = JSONObject().apply {
                put("monotonic_timestamp_ms", sample.monotonicTimestampMs)
                put("session_generation", sample.sessionGeneration)
                put("frames_expected", sample.framesExpected)
                put("frames_received", sample.framesReceived)
                put("frames_rendered", sample.framesRendered)
                put("frames_lost", sample.framesLost)
                put("received_fps", sample.incomingFps)
                put("rendered_fps", sample.renderedFps)
                put("target_fps", targetFps)
                put("refresh_rate_hz", refreshRateHz)
                put("rtt_ms", sample.rttMs)
                put("decode_latency_ms", sample.decodeTimeMs)
                sample.hostProcessingLatencyMs?.let { put("host_processing_latency_ms", it) }
                put("width", sample.width)
                put("height", sample.height)
                put("codec", sample.codec)
                put("bitrate_kbps", bitrateKbps)
                put("topology", topology)
                put("hdr", hdr)
            }
            val request = Request.Builder()
                .url("$baseUrl/doctor/v2/evidence")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    JSONObject().put("sample", raw).toString()
                ))
                .build()
            // Samples are monotonic and intentionally best-effort. Retrying a
            // lost response would submit a duplicate timestamp.
            executeNonRetryable(request).use { response -> response.code == 200 }
        } catch (_: Exception) {
            // Continuous shadow sampling is best-effort. The caller records a
            // single failure without turning a transient outage into log spam.
            false
        }
    }

    /**
     * Send raw cumulative media counters for host-derived live Doctor evidence.
     * The exact host app/session identity is mandatory and the request is never
     * retried, so a delayed sample cannot cross a stream generation.
     */
    fun sendLiveMediaTelemetry(
        sample: PerfOverlaySample,
        appSessionId: String,
        sessionGeneration: Long,
        targetFps: Double,
        refreshRateHz: Double,
        bitrateKbps: Int,
        topology: String,
        hdr: Boolean
    ): Boolean {
        if (appSessionId.isBlank() || sessionGeneration <= 0L ||
            sample.monotonicTimestampMs <= 0L || sample.framesExpected < 0L ||
            sample.framesReceived < 0L || sample.framesLost < 0L
        ) {
            return false
        }
        return try {
            val body = buildLiveMediaTelemetryBody(
                sample = sample,
                appSessionId = appSessionId,
                sessionGeneration = sessionGeneration,
                targetFps = targetFps,
                refreshRateHz = refreshRateHz,
                bitrateKbps = bitrateKbps,
                topology = topology,
                hdr = hdr
            )
            val request = Request.Builder()
                .url("$baseUrl/session/telemetry")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body.toString()
                ))
                .build()
            executeNonRetryable(request).use { response ->
                if (response.code != 200) return false
                val json = JSONObject(response.body?.string() ?: return false)
                json.opt("status") is Boolean && json.getBoolean("status")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Legacy v1 report shape retained for older integrations. New Nova code
     * uses [sendDoctorEvidenceReport].
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
                if (capturePath.isNotBlank()) put("capture_path", capturePath)
                // Legacy callers are observational too. Derived diagnoses,
                // safe settings, and relaunch recommendations are ignored.
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

    /** Resolve deterministic launch settings for a device and game. */
    @JvmOverloads
    fun getOptimization(
        device: String,
        game: String,
        preference: String = "",
        mode: String = "",
        topologyLocked: Boolean = false,
        mirrorDesktop: Boolean = false,
        forcePrivateAfterSteamClose: Boolean = false,
        width: Int = 0,
        height: Int = 0,
        fps: Float = 0f,
        displayLocked: Boolean = false,
        bitrateKbps: Int = 0,
        bitrateLocked: Boolean = false,
        hdr: Boolean? = null,
        clientMaxFps: Float = 0f,
        launchBounded: Boolean = false
    ): org.json.JSONObject? {
        return try {
            val url = "$baseUrl${buildOptimizationPath(
                device = device,
                game = game,
                preference = preference,
                mode = mode,
                topologyLocked = topologyLocked,
                mirrorDesktop = mirrorDesktop,
                forcePrivateAfterSteamClose = forcePrivateAfterSteamClose,
                width = width,
                height = height,
                fps = fps,
                displayLocked = displayLocked,
                bitrateKbps = bitrateKbps,
                bitrateLocked = bitrateLocked,
                hdr = hdr,
                clientMaxFps = clientMaxFps
            )}"
            val request = Request.Builder().url(url).get().build()
            LimeLog.info("Nova: Optimization query start for $url")
            val response = if (launchBounded) {
                executeLaunchPolicyGet(request)
            } else {
                executeGetWithRetry(request)
            }
            response.use {
                val body = it.body?.string().orEmpty()
                if (it.code == 200) {
                    LimeLog.info("Nova: Optimization query HTTP 200 for $url")
                    val responseBody = body.ifBlank { "{}" }
                    LimeLog.info("Nova: Optimization query body received (${responseBody.length} bytes)")
                    org.json.JSONObject(responseBody)
                } else {
                    LimeLog.warning("Nova: Optimization query returned HTTP ${it.code} for $url")
                    parseTypedRejection(it.code, body, mutationEnvelope = false)?.let { rejection ->
                        throw PolarisApiRejectedException(rejection)
                    }
                    null
                }
            }
        } catch (e: PolarisApiRejectedException) {
            throw e
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
            executeWithTransientRetry(request).use { response ->
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
            executeWithTransientRetry(request).use { response ->
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
