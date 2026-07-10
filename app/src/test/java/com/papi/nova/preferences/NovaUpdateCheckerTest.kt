package com.papi.nova.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaUpdateCheckerTest {
    @Test
    fun parsesLatestReleaseAndSelectsPreferredAbiApk() {
        val result = NovaUpdateChecker.parseLatestRelease(
            json = githubReleaseJson(
                tagName = "v1.3.0",
                htmlUrl = "https://github.com/papi-ux/nova/releases/tag/v1.3.0",
                assets = listOf(
                    "Nova-Android-armeabi-v7a.apk" to "https://downloads.example/Nova-Android-armeabi-v7a.apk",
                    "Nova-Android-arm64-v8a.apk" to "https://downloads.example/Nova-Android-arm64-v8a.apk"
                )
            ),
            currentVersionName = "1.2.1",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertTrue(result is NovaUpdateCheckResult.UpdateAvailable)
        val update = (result as NovaUpdateCheckResult.UpdateAvailable).release
        assertEquals("v1.3.0", update.tagName)
        assertEquals("1.3.0", update.versionName)
        assertEquals("https://github.com/papi-ux/nova/releases/tag/v1.3.0", update.releaseUrl)
        assertEquals("Nova-Android-arm64-v8a.apk", update.apkAssetName)
        assertEquals("https://downloads.example/Nova-Android-arm64-v8a.apk", update.apkDownloadUrl)
        assertEquals("Bug fixes and flamingo polish", update.releaseNotes)
    }

    @Test
    fun reportsCurrentWhenReleaseIsNotNewerThanInstalledVersion() {
        val result = NovaUpdateChecker.parseLatestRelease(
            json = githubReleaseJson(tagName = "v1.2.1"),
            currentVersionName = "1.2.1",
            supportedAbis = listOf("arm64-v8a")
        )

        assertTrue(result is NovaUpdateCheckResult.UpToDate)
        assertEquals("v1.2.1", (result as NovaUpdateCheckResult.UpToDate).release.tagName)
    }

    @Test
    fun comparesSemanticVersionsNumericallyInsteadOfLexically() {
        assertTrue(NovaUpdateChecker.isNewerVersion("1.10.0", "1.9.9"))
        assertFalse(NovaUpdateChecker.isNewerVersion("1.2.0", "1.10.0"))
        assertFalse(NovaUpdateChecker.isNewerVersion("v1.2.1", "1.2.1"))
    }

    @Test
    fun fallsBackToReleasePageWhenNoSupportedApkAssetExists() {
        val result = NovaUpdateChecker.parseLatestRelease(
            json = githubReleaseJson(
                tagName = "v1.3.0",
                assets = listOf("Nova-Desktop.zip" to "https://downloads.example/Nova-Desktop.zip")
            ),
            currentVersionName = "1.2.1",
            supportedAbis = listOf("arm64-v8a")
        )

        assertTrue(result is NovaUpdateCheckResult.UpdateAvailable)
        val update = (result as NovaUpdateCheckResult.UpdateAvailable).release
        assertNull(update.apkAssetName)
        assertNull(update.apkDownloadUrl)
    }

    private fun githubReleaseJson(
        tagName: String,
        htmlUrl: String = "https://github.com/papi-ux/nova/releases/tag/$tagName",
        assets: List<Pair<String, String>> = listOf("Nova-Android-arm64-v8a.apk" to "https://downloads.example/Nova-Android-arm64-v8a.apk")
    ): String {
        val assetsJson = assets.joinToString(separator = ",") { (name, url) ->
            """{"name":"$name","browser_download_url":"$url"}"""
        }
        return """
            {
              "tag_name": "$tagName",
              "name": "Nova $tagName",
              "html_url": "$htmlUrl",
              "body": "Bug fixes and flamingo polish",
              "draft": false,
              "prerelease": false,
              "assets": [$assetsJson]
            }
        """.trimIndent()
    }
}
