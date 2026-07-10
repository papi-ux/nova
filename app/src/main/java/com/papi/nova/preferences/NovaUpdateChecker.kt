package com.papi.nova.preferences

import android.os.Build
import com.papi.nova.BuildConfig
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

internal object NovaUpdateChecker {
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/papi-ux/nova/releases/latest"

    fun currentVersionLabel(): String = NovaAppVersion.current()

    fun checkLatest(
        client: OkHttpClient = OkHttpClient(),
        currentVersionName: String = BuildConfig.VERSION_NAME,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
    ): NovaUpdateCheckResult {
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
        val root = JSONObject(json)
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
        val candidateParts = versionParts(candidate)
        val currentParts = versionParts(current)
        val width = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until width) {
            val left = candidateParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) {
                return left > right
            }
        }
        return false
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
            .substringBefore("-")
            .substringBefore("+")
    }

    private fun versionParts(version: String): List<Int> {
        val normalized = normalizeVersionName(version)
        return normalized.split('.')
            .map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
