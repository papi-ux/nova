package com.papi.nova.preferences

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URI
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.papi.nova.BuildConfig
import com.papi.nova.R
import com.papi.nova.ui.NovaSheetChrome
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed class NovaUpdateInstallValidation {
    data object Valid : NovaUpdateInstallValidation()
    data class Invalid(val reason: String) : NovaUpdateInstallValidation()
}

internal sealed class NovaUpdateInstallResult {
    data object StartedInstaller : NovaUpdateInstallResult()
    data object PermissionRequired : NovaUpdateInstallResult()
    data class Blocked(val reason: String) : NovaUpdateInstallResult()
    data class Failed(val reason: String) : NovaUpdateInstallResult()
}

internal object NovaUpdateInstaller {
    private const val MAX_APK_BYTES = 300L * 1024L * 1024L
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun isTrustedDownloadUrl(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        if (parsed.scheme != "https") return false
        if (parsed.host != "github.com") return false
        val path = parsed.rawPath ?: return false
        if (!path.startsWith("/papi-ux/nova/releases/download/")) return false
        if (path.contains("..")) return false
        return path.endsWith(".apk", ignoreCase = true)
    }

    fun validateDownloadedApkMetadata(
        expectedPackageName: String,
        currentVersionCode: Long,
        currentSignerDigests: Set<String>,
        downloadedPackageName: String?,
        downloadedVersionCode: Long,
        downloadedSignerDigests: Set<String>,
        release: NovaUpdateRelease
    ): NovaUpdateInstallValidation {
        if (downloadedPackageName != expectedPackageName) {
            return NovaUpdateInstallValidation.Invalid(
                "Downloaded APK package is ${downloadedPackageName ?: "unknown"}, expected $expectedPackageName."
            )
        }
        if (downloadedVersionCode <= currentVersionCode) {
            return NovaUpdateInstallValidation.Invalid(
                "Downloaded APK versionCode $downloadedVersionCode is not newer than installed $currentVersionCode."
            )
        }
        if (currentSignerDigests.isEmpty() || downloadedSignerDigests.isEmpty()) {
            return NovaUpdateInstallValidation.Invalid("Could not verify APK signing certificate.")
        }
        if (currentSignerDigests.intersect(downloadedSignerDigests).isEmpty()) {
            return NovaUpdateInstallValidation.Invalid("Downloaded APK signing certificate does not match installed Nova.")
        }
        if (!NovaUpdateChecker.isNewerVersion(release.versionName, BuildConfig.VERSION_NAME)) {
            return NovaUpdateInstallValidation.Invalid("Release ${release.tagName} is not newer than installed Nova.")
        }
        return NovaUpdateInstallValidation.Valid
    }

    suspend fun downloadValidateAndInstall(
        activity: Activity,
        release: NovaUpdateRelease,
        client: OkHttpClient = OkHttpClient(),
        onProgress: (Int) -> Unit = {}
    ): NovaUpdateInstallResult {
        val downloadUrl = release.apkDownloadUrl
            ?: return NovaUpdateInstallResult.Blocked(activity.getString(R.string.nova_update_no_apk_message))
        if (!isTrustedDownloadUrl(downloadUrl)) {
            return NovaUpdateInstallResult.Blocked(activity.getString(R.string.nova_update_untrusted_url_message))
        }
        if (!canRequestPackageInstalls(activity)) {
            withContext(Dispatchers.Main.immediate) {
                showUnknownSourcesPermissionDialog(activity)
            }
            return NovaUpdateInstallResult.PermissionRequired
        }

        return withContext(Dispatchers.IO) {
            try {
                val apkFile = downloadApk(activity, release, downloadUrl, client, onProgress)
                when (val validation = validateDownloadedApk(activity, apkFile, release)) {
                    NovaUpdateInstallValidation.Valid -> {
                        withContext(Dispatchers.Main.immediate) {
                            launchPackageInstaller(activity, apkFile)
                        }
                        NovaUpdateInstallResult.StartedInstaller
                    }
                    is NovaUpdateInstallValidation.Invalid -> NovaUpdateInstallResult.Blocked(validation.reason)
                }
            } catch (e: Exception) {
                NovaUpdateInstallResult.Failed(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    private fun canRequestPackageInstalls(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return activity.packageManager.canRequestPackageInstalls()
    }

    private fun showUnknownSourcesPermissionDialog(activity: Activity) {
        if (activity.isFinishing) return
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.nova_update_permission_title)
            .setMessage(R.string.nova_update_permission_message)
            .setPositiveButton(R.string.nova_update_open_android_settings) { _, _ ->
                openUnknownSourcesSettings(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        NovaSheetChrome.applyAlertDialogChrome(dialog)
    }

    private fun openUnknownSourcesSettings(activity: Activity) {
        val packageUri = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    private fun downloadApk(
        activity: Activity,
        release: NovaUpdateRelease,
        downloadUrl: String,
        client: OkHttpClient,
        onProgress: (Int) -> Unit
    ): File {
        val safeName = release.apkAssetName
            ?.substringAfterLast('/')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "Nova-${release.versionName}.apk"
        val updateDir = File(activity.cacheDir, "nova-updates").apply { mkdirs() }
        val apkFile = File(updateDir, safeName)

        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Nova/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed: HTTP ${response.code}")
            }
            val body = response.body
            val totalBytes = body.contentLength()
            if (totalBytes > MAX_APK_BYTES) {
                throw IllegalStateException("APK is unexpectedly large (${totalBytes / (1024 * 1024)} MB).")
            }
            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        copied += read
                        if (copied > MAX_APK_BYTES) {
                            throw IllegalStateException("APK exceeded the maximum safe download size.")
                        }
                        output.write(buffer, 0, read)
                        if (totalBytes > 0L) {
                            val progress = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw IllegalStateException("Downloaded APK is empty.")
        }
        return apkFile
    }

    private fun validateDownloadedApk(
        activity: Activity,
        apkFile: File,
        release: NovaUpdateRelease
    ): NovaUpdateInstallValidation {
        val packageManager = activity.packageManager
        val downloadedInfo = packageManager.getArchivePackageInfo(apkFile)
            ?: return NovaUpdateInstallValidation.Invalid("Android could not read the downloaded APK.")
        val currentInfo = packageManager.getInstalledPackageInfo(BuildConfig.APPLICATION_ID)
            ?: return NovaUpdateInstallValidation.Invalid("Android could not read the installed Nova package.")

        return validateDownloadedApkMetadata(
            expectedPackageName = BuildConfig.APPLICATION_ID,
            currentVersionCode = currentInfo.versionCodeCompat(),
            currentSignerDigests = currentInfo.signerSha256Digests(),
            downloadedPackageName = downloadedInfo.packageName,
            downloadedVersionCode = downloadedInfo.versionCodeCompat(),
            downloadedSignerDigests = downloadedInfo.signerSha256Digests(),
            release = release
        )
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            BuildConfig.APPLICATION_ID + ".fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        activity.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getArchivePackageInfo(apkFile: File): PackageInfo? {
        val flags = signingInfoFlags()
        return getPackageArchiveInfo(apkFile.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getInstalledPackageInfo(packageName: String): PackageInfo? {
        val flags = signingInfoFlags()
        return try {
            getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun signingInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerSha256Digests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02X".format(byte) }
        }.toSet()
    }
}
