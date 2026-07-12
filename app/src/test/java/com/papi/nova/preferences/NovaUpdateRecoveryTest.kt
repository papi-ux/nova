package com.papi.nova.preferences

import android.app.Activity
import android.content.DialogInterface
import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaUpdateRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val release = NovaUpdateRelease(
        tagName = "v1.3.2",
        versionName = "1.3.2",
        releaseUrl = "https://github.com/papi-ux/nova/releases/tag/v1.3.2",
        apkAssetName = "Nova-Android-arm64-v8a.apk",
        apkDownloadUrl = "https://github.com/papi-ux/nova/releases/download/v1.3.2/Nova-Android-arm64-v8a.apk",
    )

    @Test
    fun trustedDownloadUrlRequiresCanonicalNovaGitHubAssetPath() {
        assertTrue(
            NovaUpdateInstaller.isTrustedDownloadUrl(
                "https://github.com/papi-ux/nova/releases/download/v1.3.2/Nova-Android-arm64-v8a.apk"
            )
        )
        listOf(
            "https://github.com/papi-ux/ignored/%2e%2e/nova/releases/download/v1.3.2/Nova.apk",
            "https://github.com/papi-ux/ignored/%2E%2E/nova/releases/download/v1.3.2/Nova.apk",
            "https://github.com/papi-ux/nova/releases/download/%2e%2e/other.apk",
            "https://github.com/papi-ux/nova/releases/download/v1.3.2%2F..%2Fother.apk",
            "https://user@github.com/papi-ux/nova/releases/download/v1.3.2/Nova.apk",
            "https://github.com/papi-ux/nova/releases/download/v1.3.2/Nova.apk?redirect=1",
            "https://github.com/papi-ux/nova/releases/download/v1.3.2/Nova.apk#fragment",
            "https://github.com/papi-ux/nova/releases/download/v1.3.2/extra/Nova.apk",
        ).forEach { candidate ->
            assertFalse("Untrusted updater URL was accepted: $candidate", NovaUpdateInstaller.isTrustedDownloadUrl(candidate))
        }
    }

    @Test
    fun successfulRetryWritesOnlyPartialUntilAtomicCommitAndReportsProgressOnMainThread() {
        val cacheDir = freshCacheDir()
        val updateDir = File(cacheDir, "nova-updates").apply { mkdirs() }
        val staleFinalFile = File(updateDir, release.apkAssetName!!).apply {
            writeText("stale-final")
            setLastModified(1L)
        }
        val stalePartialFile = File(updateDir, "${release.apkAssetName}.part").apply { writeText("stale-partial") }
        val responseBody = PausingResponseBody()
        val mainThreadProgress = mutableListOf<Boolean>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Result<File>> {
                runBlocking {
                    runCatching {
                        NovaUpdateDownloadStore.download(cacheDir, release, clientReturning(responseBody)) {
                            mainThreadProgress += Looper.myLooper() == Looper.getMainLooper()
                        }
                    }
                }
            }
            awaitLatchWithMainLooper(responseBody.secondReadStarted)
            val activePartials = updateDir.listFiles().orEmpty().filter { it.name.endsWith(".apk.part") }
            val staleFinalWasRemoved = !staleFinalFile.exists()
            val stalePartialWasRemoved = !stalePartialFile.exists()
            val noFinalBeforeCommit = updateDir.listFiles().orEmpty().none { it.name.endsWith(".apk") }
            val inFlightContent = activePartials.singleOrNull()?.readText()

            responseBody.allowSecondRead.countDown()
            val downloaded = awaitFutureWithMainLooper(future).getOrThrow()

            assertTrue("Aged completed APK must be cleaned before retry", staleFinalWasRemoved)
            assertTrue("Stale partial APK must be cleaned before retry", stalePartialWasRemoved)
            assertTrue("Final APK must not exist before the transfer completes", noFinalBeforeCommit)
            assertEquals("fresh-", inFlightContent)
            assertEquals("fresh-apk", downloaded.readText())
            assertTrue(updateDir.listFiles().orEmpty().none { it.name.endsWith(".apk.part") })
            assertTrue(mainThreadProgress.isNotEmpty())
            assertTrue(mainThreadProgress.all { it })
        } finally {
            responseBody.allowSecondRead.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun sequentialDownloadsUseDistinctFinalFilesSoIssuedInstallerUriCannotBeReplaced() {
        val cacheDir = freshCacheDir()
        val first = runDownloadWithMainLooper {
            NovaUpdateDownloadStore.download(
                cacheDir,
                release,
                clientReturning("first-apk".toResponseBody(APK_MEDIA_TYPE)),
            )
        }.getOrThrow()
        val second = runDownloadWithMainLooper {
            NovaUpdateDownloadStore.download(
                cacheDir,
                release,
                clientReturning("second-apk".toResponseBody(APK_MEDIA_TYPE)),
            )
        }.getOrThrow()

        assertTrue("Each completed download must own a unique immutable path", first != second)
        assertEquals("first-apk", first.readText())
        assertEquals("second-apk", second.readText())
    }

    @Test
    fun undeletableStaleArtifactFailsClosedBeforeDownloadStarts() {
        val cacheDir = freshCacheDir()
        val staleDirectory = File(cacheDir, "nova-updates/undeletable.apk.part").apply { mkdirs() }
        File(staleDirectory, "child").writeText("keeps-directory-non-empty")

        val failure = runDownloadWithMainLooper {
            NovaUpdateDownloadStore.download(
                cacheDir,
                release,
                clientReturning("must-not-download".toResponseBody(APK_MEDIA_TYPE)),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Could not remove update file"))
    }

    @Test
    fun interruptedDownloadDeletesPartialAndFinalArtifactsSoRetryStartsClean() {
        val cacheDir = freshCacheDir()
        val updateDir = File(cacheDir, "nova-updates").apply { mkdirs() }
        File(updateDir, release.apkAssetName!!).apply {
            writeText("stale-final")
            setLastModified(1L)
        }
        File(updateDir, "${release.apkAssetName}.part").apply { writeText("stale-partial") }

        val failure = runDownloadWithMainLooper {
            NovaUpdateDownloadStore.download(cacheDir, release, clientReturning(InterruptingResponseBody()))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertDownloadArtifactsAbsent(cacheDir)
    }

    @Test
    fun cancellingBlockedDownloadCancelsOkHttpCallAndDeletesArtifacts() = runBlocking {
        val cacheDir = freshCacheDir()
        val body = CancellationAwareResponseBody()
        val job = launch(Dispatchers.Default) {
            NovaUpdateDownloadStore.download(cacheDir, release, clientReturning(body))
        }
        assertTrue("Download body never started", body.readStarted.await(2, TimeUnit.SECONDS))

        job.cancel()
        val callCancelled = body.callCancellationObserved.await(2, TimeUnit.SECONDS)
        job.join()

        assertTrue("Coroutine cancellation must cancel the active OkHttp Call", callCancelled)
        assertTrue(job.isCancelled)
        assertDownloadArtifactsAbsent(cacheDir)
    }

    @Test
    fun cancellationAfterAtomicCommitDeletesFinalArtifact() {
        val cacheDir = freshCacheDir()
        val updateDir = File(cacheDir, "nova-updates")
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val job = scope.launch {
            NovaUpdateDownloadStore.download(
                cacheDir,
                release,
                clientReturning(UnknownLengthResponseBody("fresh-apk")),
            )
        }
        try {
            mainLooper.idle()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var committedFile: File? = null
            while (committedFile == null && System.nanoTime() < deadline) {
                committedFile = updateDir.listFiles().orEmpty().singleOrNull { it.name.endsWith(".apk") }
                if (committedFile == null) Thread.sleep(5L)
            }
            assertTrue("Download never reached the atomic commit boundary", committedFile != null)

            job.cancel()
            while (!job.isCompleted && System.nanoTime() < deadline) {
                mainLooper.idle()
                Thread.sleep(5L)
            }
            mainLooper.idle()

            assertTrue(job.isCancelled)
            assertDownloadArtifactsAbsent(cacheDir)
        } finally {
            scope.cancel()
        }
    }

    @Test
    @Config(sdk = [25])
    fun installerDeletesRejectedApkAndReturnsBlocked() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val apkFile = File(freshCacheDir(), "rejected.apk").apply { writeText("not-an-apk") }

        val result = NovaUpdateInstaller.downloadValidateAndInstall(
            activity = activity,
            release = release,
            downloader = { apkFile },
            validator = { NovaUpdateInstallValidation.Invalid("signer mismatch") },
            installerLauncher = { throw AssertionError("Blocked APK must never reach the installer") },
        )

        assertTrue(result is NovaUpdateInstallResult.Blocked)
        assertFalse("Rejected APK must be deleted", apkFile.exists())
    }

    @Test
    @Config(sdk = [25])
    fun rejectedApkCleanupFailureStaysBlockedAndIsReported() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val apkDirectory = File(freshCacheDir(), "undeletable.apk").apply { mkdirs() }
        File(apkDirectory, "child").writeText("prevents-directory-delete")

        val result = NovaUpdateInstaller.downloadValidateAndInstall(
            activity = activity,
            release = release,
            downloader = { apkDirectory },
            validator = { NovaUpdateInstallValidation.Invalid("bad package") },
            installerLauncher = { throw AssertionError("Rejected APK must never reach installer") },
        )

        assertTrue(result is NovaUpdateInstallResult.Blocked)
        val reason = (result as NovaUpdateInstallResult.Blocked).reason
        assertTrue(reason.contains("could not remove the downloaded APK"))
    }

    @Test
    @Config(sdk = [25])
    fun installerPropagatesCancellationAndDeletesDownloadedApk() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val apkFile = File(freshCacheDir(), "cancelled.apk").apply { writeText("cancel-me") }

        val failure = runCatching {
            NovaUpdateInstaller.downloadValidateAndInstall(
                activity = activity,
                release = release,
                downloader = { apkFile },
                validator = { throw CancellationException("cancelled during validation") },
                installerLauncher = { throw AssertionError("Cancelled APK must never reach the installer") },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse("Cancelled install must delete its downloaded APK", apkFile.exists())
    }

    @Test
    @Config(sdk = [25])
    fun validatorExceptionRemainsSafetyBlockedAndDeletesDownloadedApk() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val apkFile = File(freshCacheDir(), "validator-crash.apk").apply { writeText("unverifiable") }

        val result = NovaUpdateInstaller.downloadValidateAndInstall(
            activity = activity,
            release = release,
            downloader = { apkFile },
            validator = { throw IllegalStateException("package parser failed") },
            installerLauncher = { throw AssertionError("Unverifiable APK must never reach the installer") },
        )

        assertTrue("Validation exceptions must fail closed without Retry", result is NovaUpdateInstallResult.Blocked)
        assertFalse("Unverifiable APK must be deleted", apkFile.exists())
    }

    @Test
    @Config(sdk = [25])
    fun downloaderIoFailureRemainsTransientAndRetryable() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val result = NovaUpdateInstaller.downloadValidateAndInstall(
            activity = activity,
            release = release,
            downloader = { throw IOException("temporary network failure") },
            validator = { NovaUpdateInstallValidation.Valid },
            installerLauncher = {},
        )
        assertTrue(result is NovaUpdateInstallResult.Failed)
    }

    @Test
    @Config(sdk = [25])
    fun concurrentInstallerRequestIsRejectedBeforeStartingAnotherDownload() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val firstStarted = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)
        val downloaderCalls = AtomicInteger(0)
        val firstApk = File(freshCacheDir(), "first.apk").apply { writeText("first") }
        val secondApk = File(freshCacheDir(), "second.apk").apply { writeText("second") }

        val first = async(Dispatchers.Default) {
            NovaUpdateInstaller.downloadValidateAndInstall(
                activity = activity,
                release = release,
                downloader = {
                    downloaderCalls.incrementAndGet()
                    firstStarted.countDown()
                    withContext(Dispatchers.IO) {
                        if (!allowFirstToFinish.await(5, TimeUnit.SECONDS)) {
                            throw IOException("timed out waiting for duplicate-install assertion")
                        }
                    }
                    firstApk
                },
                validator = { NovaUpdateInstallValidation.Valid },
                installerLauncher = {},
            )
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        val second = NovaUpdateInstaller.downloadValidateAndInstall(
            activity = activity,
            release = release,
            downloader = {
                downloaderCalls.incrementAndGet()
                secondApk
            },
            validator = { NovaUpdateInstallValidation.Valid },
            installerLauncher = {},
        )

        assertTrue(second is NovaUpdateInstallResult.Failed)
        assertEquals(1, downloaderCalls.get())
        allowFirstToFinish.countDown()
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!first.isCompleted && System.nanoTime() < deadline) {
            mainLooper.idle()
            Thread.sleep(5L)
        }
        assertTrue("First install did not complete after releasing the test barrier", first.isCompleted)
        assertTrue(first.await() is NovaUpdateInstallResult.StartedInstaller)

        val afterCompletion = NovaUpdateInstaller.downloadValidateAndInstall(
            activity = activity,
            release = release,
            downloader = {
                downloaderCalls.incrementAndGet()
                secondApk
            },
            validator = { NovaUpdateInstallValidation.Valid },
            installerLauncher = {},
        )
        assertTrue("Install guard must be released after the first transaction", afterCompletion is NovaUpdateInstallResult.StartedInstaller)
        assertEquals(2, downloaderCalls.get())
    }

    @Test
    fun installResultPresenterRetriesCapturedReleaseOnlyForTransientFailure() {
        val controller = Robolectric.buildActivity(UpdateDialogTestActivity::class.java)
        val activity = controller.get().apply { setTheme(com.papi.nova.R.style.AppTheme) }
        controller.setup()
        var retriedRelease: NovaUpdateRelease? = null

        NovaUpdateInstaller.showInstallResult(
            activity,
            release,
            NovaUpdateInstallResult.Failed("temporary network failure"),
            onRetry = { retriedRelease = it },
            onViewReleases = {},
        )
        ShadowAlertDialog.getLatestAlertDialog()
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertSame(release, retriedRelease)

        retriedRelease = null
        NovaUpdateInstaller.showInstallResult(
            activity,
            release,
            NovaUpdateInstallResult.Blocked("signer mismatch"),
            onRetry = { retriedRelease = it },
            onViewReleases = {},
        )
        ShadowAlertDialog.getLatestAlertDialog()
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, retriedRelease)

        NovaUpdateInstaller.showInstallResult(
            activity,
            release,
            NovaUpdateInstallResult.StartedInstaller,
            onRetry = { retriedRelease = it },
            onViewReleases = {},
        )
        assertEquals(activity.getString(com.papi.nova.R.string.nova_update_installer_started), ShadowToast.getTextOfLatestToast())

        val latestDialog = ShadowAlertDialog.getLatestAlertDialog()
        NovaUpdateInstaller.showInstallResult(
            activity,
            release,
            NovaUpdateInstallResult.PermissionRequired,
            onRetry = { retriedRelease = it },
            onViewReleases = {},
        )
        assertSame(latestDialog, ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun failedManualCheckDialogInvokesFreshRetryAction() {
        val controller = Robolectric.buildActivity(UpdateDialogTestActivity::class.java)
        val activity = controller.get().apply { setTheme(com.papi.nova.R.style.AppTheme) }
        controller.setup()
        var retryCalls = 0

        NovaUpdateInstaller.showCheckError(
            activity,
            IOException("offline"),
            onRetry = { retryCalls += 1 },
            onViewReleases = {},
        )
        ShadowAlertDialog.getLatestAlertDialog()
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, retryCalls)
    }

    @Test
    fun automaticCheckThrottleRecordsOnlySuccessfulResultsInRealPreferences() {
        val prefs = context.getSharedPreferences("nova-update-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val nowMs = 5L * NovaUpdatePromptPreferences.AUTO_CHECK_INTERVAL_MS

        NovaUpdatePromptPreferences.recordAutomaticCheckResult(
            prefs,
            nowMs,
            Result.failure(IOException("offline")),
        )
        assertTrue(NovaUpdatePromptPreferences.shouldRunAutomaticCheck(prefs, nowMs))

        NovaUpdatePromptPreferences.recordAutomaticCheckResult(
            prefs,
            nowMs,
            Result.success(NovaUpdateCheckResult.UpToDate(release)),
        )
        assertFalse(NovaUpdatePromptPreferences.shouldRunAutomaticCheck(prefs, nowMs))
    }

    @Test
    fun destroyedActivityNeverDismissesUpdaterDialog() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var dismissCalls = 0
        controller.destroy()

        NovaUpdateInstaller.dismissIfAlive(activity) { dismissCalls += 1 }

        assertEquals(0, dismissCalls)
    }

    @Test
    fun liveActivityDismissesUpdaterDialog() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var dismissCalls = 0

        NovaUpdateInstaller.dismissIfAlive(activity) { dismissCalls += 1 }

        assertEquals(1, dismissCalls)
    }

    private fun runDownloadWithMainLooper(block: suspend () -> File): Result<File> {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            awaitFutureWithMainLooper(
                executor.submit<Result<File>> { runBlocking { runCatching { block() } } }
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitFutureWithMainLooper(future: Future<Result<File>>): Result<File> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (!future.isDone && System.nanoTime() < deadline) {
            mainLooper.idle()
            Thread.sleep(5L)
        }
        assertTrue("Download did not complete while the main looper was being driven", future.isDone)
        return future.get()
    }

    private fun awaitLatchWithMainLooper(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (latch.count > 0L && System.nanoTime() < deadline) {
            mainLooper.idle()
            Thread.sleep(5L)
        }
        assertEquals("Download did not reach the controlled transfer boundary", 0L, latch.count)
    }

    private fun assertDownloadArtifactsAbsent(cacheDir: File) {
        val artifacts = File(cacheDir, "nova-updates").listFiles().orEmpty().filter {
            it.name.endsWith(".apk") || it.name.endsWith(".apk.part")
        }
        assertTrue("Updater artifacts remained: ${artifacts.joinToString { it.name }}", artifacts.isEmpty())
    }

    private fun freshCacheDir() = File(context.cacheDir, "updater-recovery-${UUID.randomUUID()}").apply { assertTrue(mkdirs()) }

    private fun clientReturning(body: ResponseBody) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        if (body is CancellationAwareResponseBody) {
            body.isCallCancelled = { chain.call().isCanceled() }
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    }).build()

    private class PausingResponseBody : ResponseBody() {
        val secondReadStarted = CountDownLatch(1)
        val allowSecondRead = CountDownLatch(1)

        override fun contentType() = APK_MEDIA_TYPE
        override fun contentLength() = 9L
        override fun source(): BufferedSource {
            val first = Buffer().writeUtf8("fresh-")
            val second = Buffer().writeUtf8("apk")
            return object : Source {
                private var readIndex = 0
                override fun read(sink: Buffer, byteCount: Long): Long = when (readIndex++) {
                    0 -> first.read(sink, byteCount)
                    1 -> {
                        secondReadStarted.countDown()
                        if (!allowSecondRead.await(5, TimeUnit.SECONDS)) {
                            throw IOException("timed out waiting to finish controlled transfer")
                        }
                        second.read(sink, byteCount)
                    }
                    else -> -1L
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
        }
    }

    private class CancellationAwareResponseBody : ResponseBody() {
        val readStarted = CountDownLatch(1)
        val callCancellationObserved = CountDownLatch(1)
        @Volatile var isCallCancelled: () -> Boolean = { false }

        override fun contentType() = APK_MEDIA_TYPE
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!isCallCancelled() && System.nanoTime() < deadline) {
                    Thread.sleep(5L)
                }
                if (isCallCancelled()) {
                    callCancellationObserved.countDown()
                    throw IOException("active call cancelled")
                }
                throw IOException("active call was not cancelled")
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    private class UnknownLengthResponseBody(text: String) : ResponseBody() {
        private val buffer = Buffer().writeUtf8(text)
        override fun contentType() = APK_MEDIA_TYPE
        override fun contentLength() = -1L
        override fun source(): BufferedSource = buffer
    }

    private class InterruptingResponseBody : ResponseBody() {
        override fun contentType() = APK_MEDIA_TYPE
        override fun contentLength() = 9L
        override fun source(): BufferedSource {
            val prefix = Buffer().writeUtf8("partial")
            return object : Source {
                private var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (first) { first = false; return prefix.read(sink, byteCount) }
                    throw IOException("simulated interrupted transfer")
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
        }
    }

    companion object {
        private val APK_MEDIA_TYPE = "application/vnd.android.package-archive".toMediaType()
    }
}

private class UpdateDialogTestActivity : AppCompatActivity()
