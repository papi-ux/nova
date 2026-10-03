package com.papi.nova.preferences

import android.os.Build
import com.papi.nova.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class NovaUpdateRelease(
    val tagName: String,
    val versionName: String,
    val releaseUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val releaseNotes: String? = null
)

internal sealed class NovaUpdateCheckResult {
    data class UpdateAvailable(val release: NovaUpdateRelease) : NovaUpdateCheckResult()
    data class UpToDate(val release: NovaUpdateRelease) : NovaUpdateCheckResult()
}

internal enum class NovaUpdateChannel { STABLE, BETA }

internal object NovaUpdateChecker {
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/papi-ux/nova/releases/latest"
    private const val RELEASES_API_URL = "https://api.github.com/repos/papi-ux/nova/releases"
    private val VERSION_PATTERN = Regex("^([0-9]+(?:\\.[0-9]+){1,3})(?:-(pre|beta(?:\\.[0-9]+)?|rc\\.[0-9]+))?$")

    private data class Version(val parts: List<Long>, val channel: Int, val ordinal: Long)

    fun currentVersionLabel(): String = NovaAppVersion.current()

    fun checkLatest(
        client: OkHttpClient = OkHttpClient(),
        currentVersionName: String = BuildConfig.VERSION_NAME,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
        channel: NovaUpdateChannel = if (BuildConfig.BUILD_TYPE == "preRelease") NovaUpdateChannel.BETA else NovaUpdateChannel.STABLE
    ): NovaUpdateCheckResult {
        if (channel == NovaUpdateChannel.BETA) {
            return checkBeta(client, currentVersionName, supportedAbis)
        }
        val request = Request.Builder()
            .url(LATEST_RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Nova/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub releases returned HTTP ${response.code}")
            }
            return parseLatestRelease(
                json = response.body.string(),
                currentVersionName = currentVersionName,
                supportedAbis = supportedAbis
            )
        }
    }

    fun parseLatestRelease(
        json: String,
        currentVersionName: String,
        supportedAbis: List<String>
    ): NovaUpdateCheckResult {
        return parseRelease(JSONObject(json), currentVersionName, supportedAbis)
    }

    private fun checkBeta(client: OkHttpClient, currentVersionName: String, supportedAbis: List<String>): NovaUpdateCheckResult {
        var next: String? = "$RELEASES_API_URL?per_page=100"
        val visited = mutableSetOf<String>()
        var newest: JSONObject? = null
        var newestVersion: Version? = null
        while (next != null) {
            if (!visited.add(next) || visited.size > 10) {
                throw IllegalStateException("GitHub Beta releases returned invalid pagination")
            }
            val request = Request.Builder().url(next)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nova/${BuildConfig.VERSION_NAME}").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub releases returned HTTP ${response.code}")
                }
                val releases = JSONArray(response.body.string())
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    // Beta has a separate Android package. A stable or draft object must never
                    // win merely because it appears first or has a higher version number.
                    if (release.opt("draft") != false || release.opt("prerelease") != true) continue
                    val version = parseVersion(release.optString("tag_name")) ?: continue
                    if (version.channel == 3) continue
                    val previousVersion = newestVersion
                    if (previousVersion == null || compareVersions(version, previousVersion) > 0) {
                        newest = release
                        newestVersion = version
                    }
                }
                next = nextReleasePage(response.header("Link"))
            }
        }
        return parseRelease(newest ?: throw IllegalStateException("No published Nova Beta release is available"),
            currentVersionName, supportedAbis)
    }

    private fun nextReleasePage(link: String?): String? {
        val next = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"").find(link.orEmpty())?.groupValues?.get(1) ?: return null
        val url = next.toHttpUrl()
        if (url.scheme != "https" || url.host != "api.github.com" || url.port != 443 ||
            url.encodedPath != "/repos/papi-ux/nova/releases" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IllegalStateException("GitHub Beta releases returned invalid pagination")
        }
        return url.toString()
    }

    private fun parseRelease(root: JSONObject, currentVersionName: String, supportedAbis: List<String>): NovaUpdateCheckResult {
        val tagName = root.optString("tag_name").ifBlank { root.optString("name") }
        val versionName = normalizeVersionName(tagName)
        val releaseUrl = root.optString("html_url").ifBlank {
            "https://github.com/papi-ux/nova/releases"
        }
        val asset = selectApkAsset(root.optJSONArray("assets"), supportedAbis)
        val release = NovaUpdateRelease(
            tagName = tagName,
            versionName = versionName,
            releaseUrl = releaseUrl,
            apkAssetName = asset?.first,
            apkDownloadUrl = asset?.second,
            releaseNotes = root.optString("body").takeIf { it.isNotBlank() }
        )

        return if (isNewerVersion(versionName, currentVersionName)) {
            NovaUpdateCheckResult.UpdateAvailable(release)
        } else {
            NovaUpdateCheckResult.UpToDate(release)
        }
    }

    fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateVersion = parseVersion(candidate) ?: return false
        // Development builds historically compared their numeric base against stable. Keep
        // that behavior, without admitting unversioned/malformed release tags to the feed.
        val currentVersion = parseVersion(current) ?: parseVersion(current.substringBefore('-')) ?: return false
        return compareVersions(candidateVersion, currentVersion) > 0
    }

    private fun compareVersions(candidate: Version, current: Version): Int {
        val width = maxOf(candidate.parts.size, current.parts.size)
        for (index in 0 until width) {
            val left = candidate.parts.getOrElse(index) { 0 }
            val right = current.parts.getOrElse(index) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return candidate.channel.compareTo(current.channel).takeIf { it != 0 }
            ?: candidate.ordinal.compareTo(current.ordinal)
    }

    private fun selectApkAsset(assets: JSONArray?, supportedAbis: List<String>): Pair<String, String>? {
        if (assets == null) return null
        val apkAssets = (0 until assets.length())
            .mapNotNull { index -> assets.optJSONObject(index) }
            .mapNotNull { asset ->
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    name to url
                } else {
                    null
                }
            }
        if (apkAssets.isEmpty()) return null

        val normalizedAbis = supportedAbis.filter { it.isNotBlank() }
        for (abi in normalizedAbis) {
            apkAssets.firstOrNull { (name, _) -> name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        apkAssets.firstOrNull { (name, _) ->
            name.contains("universal", ignoreCase = true) || name.contains("all", ignoreCase = true)
        }?.let { return it }

        return apkAssets.firstOrNull()
    }

    private fun normalizeVersionName(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
    }

    private fun parseVersion(raw: String): Version? {
        val match = VERSION_PATTERN.matchEntire(normalizeVersionName(raw)) ?: return null
        val parts = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        val suffix = match.groupValues[2]
        val channel = when {
            suffix == "pre" -> 0
            suffix == "beta" || suffix.startsWith("beta.") -> 1
            suffix.startsWith("rc.") -> 2
            else -> 3
        }
        val ordinal = if (suffix.contains('.') && (channel == 1 || channel == 2)) {
            suffix.substringAfter('.').toLongOrNull() ?: return null
        } else 0
        return Version(parts, channel, ordinal)
    }
}
