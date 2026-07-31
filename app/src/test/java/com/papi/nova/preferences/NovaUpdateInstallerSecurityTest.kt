package com.papi.nova.preferences

import com.papi.nova.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUpdateInstallerSecurityTest {
    @Test
    fun trustedDownloadUrlsMustBeNovaGithubReleaseApks() {
        assertTrue(
            NovaUpdateInstaller.isTrustedDownloadUrl(
                "https://github.com/papi-ux/nova/releases/download/v1.3.0/Nova-Android-arm64-v8a.apk"
            )
        )

        assertFalse(NovaUpdateInstaller.isTrustedDownloadUrl("http://github.com/papi-ux/nova/releases/download/v1.3.0/Nova.apk"))
        assertFalse(NovaUpdateInstaller.isTrustedDownloadUrl("https://github.com/evil/nova/releases/download/v1.3.0/Nova.apk"))
        assertFalse(NovaUpdateInstaller.isTrustedDownloadUrl("https://github.com/papi-ux/nova/releases/download/v1.3.0/Nova.zip"))
        assertFalse(NovaUpdateInstaller.isTrustedDownloadUrl("https://example.com/Nova-Android-arm64-v8a.apk"))
    }

    @Test
    fun downloadedApkValidationBlocksPackageSignatureAndDowngradeMismatches() {
        val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
        val downloadedVersionCode = currentVersionCode + 1L
        val currentMajor = BuildConfig.VERSION_NAME.substringBefore('.').toIntOrNull() ?: 0
        val releaseVersionName = "${currentMajor + 1}.0.0"
        val release = NovaUpdateRelease(
            tagName = "v$releaseVersionName",
            versionName = releaseVersionName,
            releaseUrl = "https://github.com/papi-ux/nova/releases/tag/v$releaseVersionName",
            apkAssetName = "Nova-Android-arm64-v8a.apk",
            apkDownloadUrl = "https://github.com/papi-ux/nova/releases/download/v$releaseVersionName/Nova-Android-arm64-v8a.apk"
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = currentVersionCode,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova",
                downloadedVersionCode = downloadedVersionCode,
                downloadedSignerDigests = setOf("AA"),
                release = release
            ) is NovaUpdateInstallValidation.Valid
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = currentVersionCode,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova.debug",
                downloadedVersionCode = downloadedVersionCode,
                downloadedSignerDigests = setOf("AA"),
                release = release
            ) is NovaUpdateInstallValidation.Invalid
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = currentVersionCode,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova",
                downloadedVersionCode = downloadedVersionCode,
                downloadedSignerDigests = setOf("BB"),
                release = release
            ) is NovaUpdateInstallValidation.Invalid
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = currentVersionCode,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova",
                downloadedVersionCode = currentVersionCode,
                downloadedSignerDigests = setOf("AA"),
                release = release
            ) is NovaUpdateInstallValidation.Invalid
        )
    }
}
