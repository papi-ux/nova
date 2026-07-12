package com.papi.nova.preferences

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
        val release = NovaUpdateRelease(
            tagName = "v1.3.2",
            versionName = "1.3.2",
            releaseUrl = "https://github.com/papi-ux/nova/releases/tag/v1.3.2",
            apkAssetName = "Nova-Android-arm64-v8a.apk",
            apkDownloadUrl = "https://github.com/papi-ux/nova/releases/download/v1.3.2/Nova-Android-arm64-v8a.apk"
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = 33L,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova",
                downloadedVersionCode = 34L,
                downloadedSignerDigests = setOf("AA"),
                release = release
            ) is NovaUpdateInstallValidation.Valid
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = 33L,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova.debug",
                downloadedVersionCode = 34L,
                downloadedSignerDigests = setOf("AA"),
                release = release
            ) is NovaUpdateInstallValidation.Invalid
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = 33L,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova",
                downloadedVersionCode = 34L,
                downloadedSignerDigests = setOf("BB"),
                release = release
            ) is NovaUpdateInstallValidation.Invalid
        )

        assertTrue(
            NovaUpdateInstaller.validateDownloadedApkMetadata(
                expectedPackageName = "com.papi.nova",
                currentVersionCode = 33L,
                currentSignerDigests = setOf("AA"),
                downloadedPackageName = "com.papi.nova",
                downloadedVersionCode = 33L,
                downloadedSignerDigests = setOf("AA"),
                release = release
            ) is NovaUpdateInstallValidation.Invalid
        )
    }
}
