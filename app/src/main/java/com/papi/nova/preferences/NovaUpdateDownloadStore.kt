package com.papi.nova.preferences

import com.papi.nova.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal object NovaUpdateDownloadStore {
    private const val MAX_APK_BYTES = 300L * 1024L * 1024L
    private val COMPLETED_APK_RETENTION_MS = TimeUnit.HOURS.toMillis(24)
    private val downloadMutex = Mutex()

    suspend fun download(
        cacheDir: File,
        release: NovaUpdateRelease,
        client: OkHttpClient = OkHttpClient(),
        onProgress: (Int) -> Unit = {},
    ): File = downloadMutex.withLock {
        val downloadUrl = requireNotNull(release.apkDownloadUrl) { "Release does not contain an APK download URL." }
        val safeAssetName = release.apkAssetName
            ?.substringAfterLast('/')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "Nova-${release.versionName}.apk"
        val safeStem = safeAssetName.dropLast(4)
        val transactionName = "$safeStem-${UUID.randomUUID()}.apk"
        val updateDir = File(cacheDir, "nova-updates")
        val apkFile = File(updateDir, transactionName)
        val partialFile = File(updateDir, "$transactionName.part")
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Nova/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            coroutineScope {
                val call = client.newCall(request)
                val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        if (!updateDir.isDirectory && !updateDir.mkdirs()) {
                            throw IllegalStateException("Could not create Nova update cache.")
                        }
                        cleanupStaleArtifacts(updateDir)

                        var committed = false
                        try {
                            call.execute().use { response ->
                                if (!response.isSuccessful) {
                                    throw IllegalStateException("Download failed: HTTP ${response.code}")
                                }
                                val body = response.body
                                val totalBytes = body.contentLength()
                                if (totalBytes > MAX_APK_BYTES) {
                                    throw IllegalStateException("APK is unexpectedly large (${totalBytes / (1024 * 1024)} MB).")
                                }
                                body.byteStream().use { input ->
                                    partialFile.outputStream().use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        var copied = 0L
                                        var lastProgress = -1
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
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
                                                    withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (!partialFile.isFile || partialFile.length() <= 0L) {
                                throw IllegalStateException("Downloaded APK is empty.")
                            }
                            currentCoroutineContext().ensureActive()
                            if (!partialFile.renameTo(apkFile)) {
                                throw IllegalStateException("Could not commit downloaded APK to the update cache.")
                            }
                            committed = true
                            apkFile
                        } finally {
                            if (!committed) {
                                cleanupArtifacts(partialFile, apkFile)
                            }
                        }
                    }
                } finally {
                    cancellationWatcher.cancel()
                }
            }
        } catch (e: Exception) {
            val cancellation = if (e is CancellationException) {
                e
            } else {
                try {
                    currentCoroutineContext().ensureActive()
                    null
                } catch (cancelled: CancellationException) {
                    cancelled.apply { addSuppressed(e) }
                }
            }
            if (cancellation != null) {
                val cleanupFailure = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { cleanupArtifacts(partialFile, apkFile) }.exceptionOrNull()
                }
                cleanupFailure?.let(cancellation::addSuppressed)
                throw cancellation
            }
            throw e
        }
    }

    private fun cleanupStaleArtifacts(updateDir: File) {
        val staleBefore = System.currentTimeMillis() - COMPLETED_APK_RETENTION_MS
        updateDir.listFiles().orEmpty().forEach { file ->
            val stalePartial = file.name.endsWith(".apk.part", ignoreCase = true)
            val staleCompletedApk = file.name.endsWith(".apk", ignoreCase = true) && file.lastModified() < staleBefore
            if (stalePartial || staleCompletedApk) {
                deleteForCleanup(file)
            }
        }
    }

    private fun cleanupArtifacts(partialFile: File, apkFile: File) {
        deleteForCleanup(partialFile)
        deleteForCleanup(apkFile)
    }

    private fun deleteForCleanup(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Could not remove update file ${file.name}.")
        }
    }
}
