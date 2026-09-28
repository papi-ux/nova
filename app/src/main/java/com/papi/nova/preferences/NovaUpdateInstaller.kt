package com.papi.nova.preferences

import android.app.Activity
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.papi.nova.BuildConfig
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private val encodedPathMetaOctet = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)
    private val installInProgress = AtomicBoolean(false)

    fun isTrustedDownloadUrl(url: String): Boolean {
        if (encodedPathMetaOctet.containsMatchIn(url)) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.host != "github.com" || parsed.port != 443) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.query != null || parsed.fragment != null) return false

        val encodedSegments = parsed.encodedPathSegments
        if (encodedSegments.any { segment ->
                val lower = segment.lowercase()
                lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")
            }
        ) {
            return false
        }

        val segments = parsed.pathSegments
        if (segments.size != 6) return false
        if (segments.take(4) != listOf("papi-ux", "nova", "releases", "download")) return false
        if (segments[4].isBlank()) return false
        return segments[5].isNotBlank() && segments[5].endsWith(".apk", ignoreCase = true)
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

    fun showInstallResult(
        activity: Activity,
        release: NovaUpdateRelease,
        result: NovaUpdateInstallResult,
        onRetry: (NovaUpdateRelease) -> Unit,
        onViewReleases: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        when (result) {
            NovaUpdateInstallResult.StartedInstaller -> Toast.makeText(
                activity,
                R.string.nova_update_installer_started,
                Toast.LENGTH_LONG,
            ).show()
            NovaUpdateInstallResult.PermissionRequired -> Unit
            is NovaUpdateInstallResult.Blocked -> showInstallProblem(
                activity,
                R.string.nova_update_install_blocked_title,
                result.reason,
                onRetry = null,
                onViewReleases = onViewReleases,
            )
            is NovaUpdateInstallResult.Failed -> showInstallProblem(
                activity,
                R.string.nova_update_install_failed_title,
                result.reason,
                onRetry = { onRetry(release) },
                onViewReleases = onViewReleases,
            )
        }
    }

    fun dismissIfAlive(activity: Activity, dismiss: () -> Unit) {
        if (!activity.isDestroyed) dismiss()
    }

    fun showCheckError(
        activity: Activity,
        error: Throwable,
        onRetry: () -> Unit,
        onViewReleases: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val detail = error.localizedMessage ?: error.javaClass.simpleName ?: activity.getString(R.string.nova_update_unknown_error)
        present(
            activity,
            key = "nova-update-check-failed",
            titleRes = R.string.nova_update_failed_title,
            message = activity.getString(R.string.nova_update_failed_message, detail),
            primary = NovaAction(activity.getString(R.string.nova_update_retry), run = onRetry),
            releases = onViewReleases,
        )
    }

    private fun showInstallProblem(
        activity: Activity,
        titleRes: Int,
        message: String,
        onRetry: (() -> Unit)?,
        onViewReleases: () -> Unit,
    ) {
        present(
            activity,
            key = "nova-update-install-problem",
            titleRes = titleRes,
            message = message,
            primary = onRetry?.let { NovaAction(activity.getString(R.string.nova_update_retry), run = it) },
            releases = onViewReleases,
        )
    }

    /**
     * An updater message as a Notice page in the right-edge panel: its action focused when it
     * has one, View releases beside it, and Close.
     */
    private fun present(
        activity: Activity,
        key: String,
        titleRes: Int,
        message: String,
        primary: NovaAction?,
        releases: () -> Unit,
    ) {
        NovaSurfaces.of(activity).present(
            NovaCommonPage.Notice(
                key = key,
                title = activity.getString(titleRes),
                message = message,
                primary = primary,
                closeLabel = activity.getString(R.string.nova_panel_close),
                help = NovaAction(activity.getString(R.string.nova_update_view_releases), run = releases),
            ),
        )
    }

    suspend fun downloadValidateAndInstall(
        activity: Activity,
        release: NovaUpdateRelease,
        client: OkHttpClient = OkHttpClient(),
        onProgress: (Int) -> Unit = {},
    ): NovaUpdateInstallResult = downloadValidateAndInstall(
        activity = activity,
        release = release,
        downloader = {
            NovaUpdateDownloadStore.download(activity.cacheDir, release, client, onProgress)
        },
        validator = { apkFile ->
            withContext(Dispatchers.IO) {
                validateDownloadedApk(activity, apkFile, release)
            }
        },
        installerLauncher = { apkFile ->
            launchPackageInstaller(activity, apkFile)
        },
    )

    internal suspend fun downloadValidateAndInstall(
        activity: Activity,
        release: NovaUpdateRelease,
        downloader: suspend () -> File,
        validator: suspend (File) -> NovaUpdateInstallValidation,
        installerLauncher: (File) -> Unit,
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
        if (!installInProgress.compareAndSet(false, true)) {
            return NovaUpdateInstallResult.Failed(activity.getString(R.string.nova_update_install_in_progress))
        }

        var downloadedApk: File? = null
        return try {
            val apkFile = downloader()
            downloadedApk = apkFile
            val validation = try {
                validator(apkFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cleanupProblem = deleteDownloadedApk(apkFile)
                return NovaUpdateInstallResult.Blocked(
                    activity.getString(
                        R.string.nova_update_validation_failed_message,
                        e.localizedMessage ?: e.javaClass.simpleName,
                    ) + cleanupProblem.asDialogSuffix()
                )
            }
            when (validation) {
                NovaUpdateInstallValidation.Valid -> {
                    withContext(Dispatchers.Main.immediate) {
                        installerLauncher(apkFile)
                    }
                    NovaUpdateInstallResult.StartedInstaller
                }
                is NovaUpdateInstallValidation.Invalid -> {
                    val cleanupProblem = deleteDownloadedApk(apkFile)
                    NovaUpdateInstallResult.Blocked(validation.reason + cleanupProblem.asDialogSuffix())
                }
            }
        } catch (e: CancellationException) {
            deleteDownloadedApk(downloadedApk)?.let { cleanupProblem ->
                e.addSuppressed(IllegalStateException(cleanupProblem))
            }
            throw e
        } catch (e: Exception) {
            val cleanupProblem = deleteDownloadedApk(downloadedApk)
            NovaUpdateInstallResult.Failed(
                (e.localizedMessage ?: e.javaClass.simpleName) + cleanupProblem.asDialogSuffix()
            )
        } finally {
            installInProgress.set(false)
        }
    }

    private fun deleteDownloadedApk(apkFile: File?): String? {
        if (apkFile == null || !apkFile.exists() || apkFile.delete()) return null
        return "Nova could not remove the downloaded APK from its private cache."
    }

    private fun String?.asDialogSuffix(): String = if (this == null) "" else "\n\n$this"

    private fun canRequestPackageInstalls(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return activity.packageManager.canRequestPackageInstalls()
    }

    private fun showUnknownSourcesPermissionDialog(activity: Activity) {
        if (activity.isFinishing) return
        NovaSurfaces.of(activity).present(unknownSourcesNotice(activity) { openUnknownSourcesSettings(activity) })
    }

    /**
     * Nova's own word before Android's trust screen, which stays Android's (spec section 8): why
     * Nova asks, with the way to Android's setting as the primary and Cancel to leave it.
     */
    internal fun unknownSourcesNotice(context: Context, onOpenSettings: () -> Unit): NovaCommonPage.Notice =
        NovaCommonPage.Notice(
            key = UNKNOWN_SOURCES_NOTICE_KEY,
            title = context.getString(R.string.nova_update_permission_title),
            message = context.getString(R.string.nova_update_permission_message),
            primary = NovaAction(context.getString(R.string.nova_update_open_android_settings), run = onOpenSettings),
            closeLabel = context.getString(R.string.nova_panel_cancel),
        )

    internal const val UNKNOWN_SOURCES_NOTICE_KEY = "nova-update-permission"

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
