package com.papi.nova.preferences

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUpdateInstallerIntegrationTest {
    @Test
    fun manifestDeclaresInstallerPermissionAndCacheFileProvider() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val providerPaths = File("src/main/res/xml/provider_file_paths.xml").readText()

        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("android.support.FILE_PROVIDER_PATHS"))
        assertTrue(providerPaths.contains("<cache-path"))
    }

    @Test
    fun updateUiUsesSecureDownloaderInsteadOfOpeningApkUrlDirectly() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val settings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val installer = File("src/main/java/com/papi/nova/preferences/NovaUpdateInstaller.kt").readText()

        assertTrue(pcView.contains("NovaUpdateInstaller.downloadValidateAndInstall"))
        assertTrue(settings.contains("NovaUpdateInstaller.downloadValidateAndInstall"))
        assertTrue(installer.contains("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
        assertTrue(installer.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(installer.contains("application/vnd.android.package-archive"))
        assertTrue(installer.contains("getPackageArchiveInfo"))
    }
}
