package com.papi.nova.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

internal class PolarisArtworkDiskCache(
    context: Context,
    private val host: String,
    private val port: Int,
    private val cacheRoot: File = File(context.cacheDir, "polaris-artwork"),
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
    private val maxCacheEntries: Int = MAX_CACHE_ENTRIES,
    private val replaceFile: (File, File) -> Boolean = Companion::atomicReplace,
) {
    init {
        require(maxCacheBytes > 0L)
        require(maxCacheEntries > 0)
    }

    private val cacheDir = File(
        cacheRoot,
        sha256(listOf(host.trim().lowercase(), port.toString())),
    )

    fun load(gameId: String, kind: String, revision: String, allowStale: Boolean): Bitmap? {
        if (revision.isBlank()) return null
        val exact = cacheFile(gameId, kind, revision)
        val candidates = buildList {
            if (exact.isFile) add(exact)
            if (allowStale) {
                cacheFilesForTest(gameId, kind)
                    .asSequence()
                    .filterNot { it == exact }
                    .sortedByDescending(File::lastModified)
                    .forEach(::add)
            }
        }
        for (file in candidates) {
            val bytes = runCatching {
                file.inputStream().buffered().use { readBounded(it, MAX_IMAGE_BYTES) }
            }.getOrNull()
            val bitmap = bytes?.let(::decodeBounded)
            if (bitmap != null) {
                synchronized(CACHE_LOCK) {
                    if (file.isFile) file.setLastModified(System.currentTimeMillis())
                }
                return bitmap
            }
            synchronized(CACHE_LOCK) { file.delete() }
        }
        return null
    }

    fun store(gameId: String, kind: String, revision: String, bytes: ByteArray, mimeType: String?): File? {
        if (
            revision.isBlank() ||
            bytes.isEmpty() ||
            bytes.size > MAX_IMAGE_BYTES ||
            !isSupportedImageMime(mimeType) ||
            !hasSupportedImageSignature(bytes, mimeType)
        ) return null
        val decoded = decodeBounded(bytes) ?: return null
        decoded.recycle()

        if (!cacheDir.isDirectory && !cacheDir.mkdirs() && !cacheDir.isDirectory) return null
        val target = cacheFile(gameId, kind, revision)
        val temp = File(cacheDir, "${target.name}.${TEMP_SEQUENCE.incrementAndGet()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            synchronized(WRITER_LOCK) {
                if (!replaceFile(temp, target)) return null
                cacheFilesForTest(gameId, kind).filterNot { it == target }.forEach(File::delete)
                enforceGlobalBudget(pinned = target)
            }
            target
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    fun clear() {
        synchronized(WRITER_LOCK) { cacheDir.deleteRecursively() }
    }

    internal fun cacheFilesForTest(gameId: String, kind: String): List<File> {
        val prefix = "${scopeKey(gameId, kind)}."
        return cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(FILE_SUFFIX) }
            .orEmpty()
    }

    internal fun rawCacheFilesForTest(): List<File> =
        cacheDir.listFiles()?.filter(File::isFile).orEmpty()

    private fun enforceGlobalBudget(pinned: File) {
        val files = globalCacheFiles()
        var totalBytes = files.sumOf(File::length)
        var totalEntries = files.size
        if (totalBytes <= maxCacheBytes && totalEntries <= maxCacheEntries) return

        val evictionOrder = files
            .asSequence()
            .filterNot { it == pinned }
            .sortedWith(compareBy<File>(File::lastModified).thenBy { it.absolutePath })
            .toList()
        for (candidate in evictionOrder) {
            if (totalBytes <= maxCacheBytes && totalEntries <= maxCacheEntries) break
            val size = candidate.length()
            if (candidate.delete()) {
                totalBytes = (totalBytes - size).coerceAtLeast(0L)
                totalEntries -= 1
            }
        }
        cacheRoot.listFiles()
            ?.filter {
                OWNED_HOST_DIRECTORY.matches(it.name) &&
                    Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    it.listFiles().isNullOrEmpty()
            }
            ?.forEach(File::delete)
    }

    private fun globalCacheFiles(): List<File> {
        if (!Files.isDirectory(cacheRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return cacheRoot.listFiles()
            ?.asSequence()
            ?.filter {
                OWNED_HOST_DIRECTORY.matches(it.name) &&
                    Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            ?.flatMap { directory -> directory.listFiles()?.asSequence() ?: emptySequence() }
            ?.filter {
                OWNED_CACHE_FILE.matches(it.name) &&
                    Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            ?.toList()
            .orEmpty()
    }

    private fun cacheFile(gameId: String, kind: String, revision: String): File {
        return File(cacheDir, "${scopeKey(gameId, kind)}.${cacheKey(host, port, gameId, kind, revision)}$FILE_SUFFIX")
    }

    private fun scopeKey(gameId: String, kind: String): String =
        sha256(listOf(host.trim().lowercase(), port.toString(), gameId, kind.trim().lowercase()))

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        const val MAX_CACHE_ENTRIES = 128
        private const val MAX_IMAGE_DIMENSION = 8_192
        private const val MAX_IMAGE_PIXELS = 32L * 1024L * 1024L
        private const val FILE_SUFFIX = ".image"
        private val OWNED_HOST_DIRECTORY = Regex("^[0-9a-f]{64}$")
        private val OWNED_CACHE_FILE = Regex("^[0-9a-f]{64}\\.[0-9a-f]{64}\\.image$")
        private val TEMP_SEQUENCE = AtomicLong()
        private val CACHE_LOCK = Any()
        private val WRITER_LOCK = Any()
        private val SUPPORTED_IMAGE_MIMES = setOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif",
        )

        @JvmStatic
        internal fun atomicReplace(from: File, to: File): Boolean {
            runCatching { Os.rename(from.absolutePath, to.absolutePath) }
            if (!from.exists() && to.isFile) return true
            return from.renameTo(to) && !from.exists() && to.isFile
        }

        @JvmStatic
        fun cacheKey(host: String, port: Int, gameId: String, kind: String, revision: String): String =
            sha256(listOf(host.trim().lowercase(), port.toString(), gameId, kind.trim().lowercase(), revision))

        @JvmStatic
        fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
            if (maxBytes < 0) return null
            val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        @JvmStatic
        fun decodeBounded(bytes: ByteArray): Bitmap? {
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }.isFailure) return null
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width !in 1..MAX_IMAGE_DIMENSION || height !in 1..MAX_IMAGE_DIMENSION) return null
            if (width.toLong() * height.toLong() > MAX_IMAGE_PIXELS) return null
            return runCatching {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    },
                )
            }.getOrNull()
        }

        @JvmStatic
        fun isSupportedImageMime(value: String?): Boolean {
            val normalized = value?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return normalized in SUPPORTED_IMAGE_MIMES
        }

        @JvmStatic
        fun hasSupportedImageSignature(bytes: ByteArray, mimeType: String?): Boolean {
            fun startsWith(vararg expected: Int): Boolean =
                bytes.size >= expected.size && expected.indices.all { bytes[it].toInt() and 0xff == expected[it] }

            return when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
                "image/png" -> startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                "image/jpeg", "image/jpg" -> startsWith(0xff, 0xd8, 0xff)
                "image/gif" -> startsWith(0x47, 0x49, 0x46, 0x38) && bytes.size >= 6 &&
                    (bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                        bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray()))
                "image/webp" -> bytes.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
                    bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
                else -> false
            }
        }

        private fun sha256(parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                ))
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal class ArtworkResolveOnce<T> {
    private val resolutions = ConcurrentHashMap<String, FutureTask<T?>>()

    fun resolve(key: String, resolver: () -> T?): T? {
        val candidate = FutureTask(resolver)
        val task = resolutions.putIfAbsent(key, candidate) ?: candidate.also { it.run() }
        return try {
            val result = task.get()
            if (result == null) resolutions.remove(key, task)
            result
        } catch (e: java.util.concurrent.ExecutionException) {
            resolutions.remove(key, task)
            throw (e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    fun invalidate(key: String) {
        val task = resolutions[key] ?: return
        if (task.isDone) resolutions.remove(key, task)
    }
}
