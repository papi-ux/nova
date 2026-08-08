package com.papi.nova.api

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisArtworkDiskCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun cacheKeysAreHashedAndIsolatedByHostPortGameKindAndRevision() {
        val base = PolarisArtworkDiskCache.cacheKey("host-a", 47984, "game-a", "poster", "rev-a")

        assertTrue(base.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(base, PolarisArtworkDiskCache.cacheKey("host-b", 47984, "game-a", "poster", "rev-a"))
        assertNotEquals(base, PolarisArtworkDiskCache.cacheKey("host-a", 47985, "game-a", "poster", "rev-a"))
        assertNotEquals(base, PolarisArtworkDiskCache.cacheKey("host-a", 47984, "game-b", "poster", "rev-a"))
        assertNotEquals(base, PolarisArtworkDiskCache.cacheKey("host-a", 47984, "game-a", "hero", "rev-a"))
        assertNotEquals(base, PolarisArtworkDiskCache.cacheKey("host-a", 47984, "game-a", "poster", "rev-b"))
        assertFalse(base.contains("host-a"))
    }

    @Test
    fun boundedReadAndMimeValidationRejectOversizedOrNonImageResponses() {
        assertEquals(
            4,
            PolarisArtworkDiskCache.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4)?.size
        )
        assertNull(
            PolarisArtworkDiskCache.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 4)
        )
        assertTrue(PolarisArtworkDiskCache.isSupportedImageMime("image/png; charset=binary"))
        assertTrue(PolarisArtworkDiskCache.isSupportedImageMime("IMAGE/JPEG"))
        assertFalse(PolarisArtworkDiskCache.isSupportedImageMime("application/octet-stream"))
        assertFalse(PolarisArtworkDiskCache.isSupportedImageMime(null))
        assertNull(PolarisArtworkDiskCache.decodeBounded(pngWithDeclaredDimensions(9_000, 1)))
        assertNull(PolarisArtworkDiskCache.decodeBounded(pngWithDeclaredDimensions(8_000, 8_000)))
    }

    @Test
    fun cacheValidatesDecodeSupportsOfflineFallbackAndCleansStaleRevision() {
        val cache = PolarisArtworkDiskCache(context, "offline-host", 47984)
        cache.clear()
        val png = pngBytes(3, 4)

        assertNull(cache.store("game-offline", "poster", "rev-bad-mime", png, "text/plain"))
        assertNull(cache.store("game-offline", "poster", "rev-bad-image", byteArrayOf(1, 2, 3), "image/png"))

        assertNotNull(cache.store("game-offline", "poster", "rev-1", png, "image/png"))
        assertNotNull(cache.load("game-offline", "poster", "rev-1", allowStale = false))
        assertNotNull(cache.load("game-offline", "poster", "rev-2", allowStale = true))
        assertNull(cache.load("game-offline", "poster", "rev-2", allowStale = false))

        assertNotNull(cache.store("game-offline", "poster", "rev-2", pngBytes(5, 6), "image/png"))
        assertNotNull(cache.load("game-offline", "poster", "rev-2", allowStale = false))
        assertNull(cache.load("game-offline", "poster", "rev-1", allowStale = false))
        assertTrue(cache.cacheFilesForTest("game-offline", "poster").none {
            it.name.contains(".tmp") || it.name.contains(".bak")
        })
        assertEquals(1, cache.cacheFilesForTest("game-offline", "poster").size)
        cache.clear()
    }

    @Test
    fun failedAtomicReplacementKeepsExistingExactRevisionReadable() {
        var replacements = 0
        lateinit var cache: PolarisArtworkDiskCache
        cache = PolarisArtworkDiskCache(context, "failure-host", 47984) { from, to ->
            replacements += 1
            if (replacements == 1) PolarisArtworkDiskCache.atomicReplace(from, to) else {
                assertTrue(to.isFile)
                assertNotNull(cache.load("game", "poster", "rev", allowStale = false))
                false
            }
        }
        cache.clear()
        assertNotNull(cache.store("game", "poster", "rev", pngBytes(2, 3), "image/png"))
        assertNull(cache.store("game", "poster", "rev", pngBytes(5, 6), "image/png"))
        val preserved = checkNotNull(cache.load("game", "poster", "rev", allowStale = false))
        assertEquals(2, preserved.width)
        assertEquals(3, preserved.height)
        preserved.recycle()
        assertTrue(cache.rawCacheFilesForTest().none { it.name.endsWith(".tmp") || it.name.endsWith(".bak") })
        cache.clear()
    }

    @Test
    fun concurrentReaderSeesOldBitmapUntilAtomicReplacementPublishesNewBitmap() {
        val enteredReplace = CountDownLatch(1)
        val releaseReplace = CountDownLatch(1)
        val replacements = AtomicInteger()
        val cache = PolarisArtworkDiskCache(context, "concurrent-replace-host", 47984) { from, to ->
            if (replacements.incrementAndGet() == 2) {
                enteredReplace.countDown()
                assertTrue(releaseReplace.await(2, TimeUnit.SECONDS))
            }
            PolarisArtworkDiskCache.atomicReplace(from, to)
        }
        cache.clear()
        assertNotNull(cache.store("game", "poster", "rev", pngBytes(2, 3), "image/png"))
        val pool = Executors.newSingleThreadExecutor()
        val replacement = pool.submit<File?> {
            cache.store("game", "poster", "rev", pngBytes(5, 6), "image/png")
        }
        assertTrue(enteredReplace.await(2, TimeUnit.SECONDS))
        val beforePublish = checkNotNull(cache.load("game", "poster", "rev", allowStale = false))
        assertEquals(2, beforePublish.width)
        beforePublish.recycle()
        releaseReplace.countDown()
        assertNotNull(replacement.get(2, TimeUnit.SECONDS))
        val afterPublish = checkNotNull(cache.load("game", "poster", "rev", allowStale = false))
        assertEquals(5, afterPublish.width)
        afterPublish.recycle()
        pool.shutdownNow()
        cache.clear()
    }

    @Test
    fun revisionlessManifestAssetsNeverEnterPersistentCache() {
        val root = File(context.cacheDir, "revisionless-artwork-${System.nanoTime()}").apply { deleteRecursively() }
        val cache = PolarisArtworkDiskCache(context, "revisionless-host", 47984, cacheRoot = root)
        val png = pngBytes(3, 4)

        assertNull(cache.store("game", "poster", "", png, "image/png"))
        assertNull(cache.store("game", "poster", "   ", png, "image/png"))
        assertNull(cache.load("game", "poster", "", allowStale = true))
        assertTrue(cache.rawCacheFilesForTest().isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun globalByteBudgetEvictsOldestArtworkAcrossHosts() {
        val root = File(context.cacheDir, "bounded-artwork-bytes-${System.nanoTime()}").apply { deleteRecursively() }
        val png = pngBytes(24, 24)
        val budget = png.size.toLong() * 2L
        val firstHost = PolarisArtworkDiskCache(
            context,
            "budget-host-a",
            47984,
            cacheRoot = root,
            maxCacheBytes = budget,
            maxCacheEntries = 10,
        )
        val secondHost = PolarisArtworkDiskCache(
            context,
            "budget-host-b",
            47984,
            cacheRoot = root,
            maxCacheBytes = budget,
            maxCacheEntries = 10,
        )
        val oldest = checkNotNull(firstHost.store("game-a", "poster", "rev-a", png, "image/png"))
        val newer = checkNotNull(firstHost.store("game-b", "poster", "rev-a", png, "image/png"))
        oldest.setLastModified(1_000L)
        newer.setLastModified(2_000L)

        val newest = checkNotNull(secondHost.store("game-c", "poster", "rev-a", png, "image/png"))

        assertFalse(oldest.exists())
        assertTrue(newer.isFile)
        assertTrue(newest.isFile)
        assertNull(firstHost.load("game-a", "poster", "rev-a", allowStale = false))
        root.deleteRecursively()
    }

    @Test
    fun globalEntryBudgetUsesReadRefreshedLruAcrossHosts() {
        val root = File(context.cacheDir, "bounded-artwork-entries-${System.nanoTime()}").apply { deleteRecursively() }
        val png = pngBytes(24, 24)
        val firstHost = PolarisArtworkDiskCache(
            context,
            "entry-host-a",
            47984,
            cacheRoot = root,
            maxCacheBytes = Long.MAX_VALUE,
            maxCacheEntries = 2,
        )
        val secondHost = PolarisArtworkDiskCache(
            context,
            "entry-host-b",
            47984,
            cacheRoot = root,
            maxCacheBytes = Long.MAX_VALUE,
            maxCacheEntries = 2,
        )
        val first = checkNotNull(firstHost.store("game-a", "poster", "rev-a", png, "image/png"))
        val second = checkNotNull(firstHost.store("game-b", "poster", "rev-a", png, "image/png"))
        first.setLastModified(1_000L)
        second.setLastModified(2_000L)
        checkNotNull(firstHost.load("game-a", "poster", "rev-a", allowStale = false)).recycle()

        val third = checkNotNull(secondHost.store("game-c", "poster", "rev-a", png, "image/png"))

        assertTrue(first.isFile)
        assertFalse(second.exists())
        assertTrue(third.isFile)
        root.deleteRecursively()
    }

    @Test
    fun cacheOwnershipChecksRemainApi21Compatible() {
        val source = File("src/main/java/com/papi/nova/api/PolarisArtworkDiskCache.kt").readText()

        assertFalse(source.contains("java.nio.file.Files"))
        assertFalse(source.contains(".toPath()"))
        assertTrue(source.contains("file.canonicalFile == file.absoluteFile"))
    }

    @SuppressLint("NewApi")
    @Test
    fun globalEvictionDoesNotFollowOwnedLookingSymlinkDirectory() {
        val root = File(context.cacheDir, "symlink-cache-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val outside = File(context.cacheDir, "symlink-target-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val outsideImage = File(
            outside,
            "${"b".repeat(64)}.${"c".repeat(64)}.image",
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ownedLookingLink = File(root, "a".repeat(64))
        Files.createSymbolicLink(ownedLookingLink.toPath(), outside.toPath())
        val cache = PolarisArtworkDiskCache(
            context,
            "real-host",
            47984,
            cacheRoot = root,
            maxCacheBytes = Long.MAX_VALUE,
            maxCacheEntries = 1,
        )

        val published = cache.store("game", "poster", "revision", pngBytes(24, 24), "image/png")

        assertNotNull(published)
        assertTrue(outsideImage.isFile)
        assertTrue(Files.isSymbolicLink(ownedLookingLink.toPath()))
        Files.deleteIfExists(ownedLookingLink.toPath())
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun concurrentRevisionWritersSerializeAndLeaveOnePublishedGeneration() {
        val root = File(context.cacheDir, "concurrent-writers-${System.nanoTime()}").apply { deleteRecursively() }
        val enteredStore = CountDownLatch(2)
        val startStores = CountDownLatch(1)
        val enteredReplace = CountDownLatch(2)
        val activeWriters = AtomicInteger()
        val maxActiveWriters = AtomicInteger()
        val cache = PolarisArtworkDiskCache(
            context,
            "writer-host",
            47984,
            cacheRoot = root,
            replaceFile = { from, to ->
                val active = activeWriters.incrementAndGet()
                maxActiveWriters.updateAndGet { previous -> maxOf(previous, active) }
                enteredReplace.countDown()
                enteredReplace.await(500, TimeUnit.MILLISECONDS)
                try {
                    PolarisArtworkDiskCache.atomicReplace(from, to)
                } finally {
                    activeWriters.decrementAndGet()
                }
            },
        )
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf("rev-a", "rev-b").map { revision ->
            pool.submit<File?> {
                enteredStore.countDown()
                startStores.await(2, TimeUnit.SECONDS)
                cache.store("game", "poster", revision, pngBytes(24, 24), "image/png")
            }
        }
        assertTrue(enteredStore.await(2, TimeUnit.SECONDS))
        startStores.countDown()
        val published = results.map { checkNotNull(it.get(3, TimeUnit.SECONDS)) }

        assertEquals(1, maxActiveWriters.get())
        assertEquals(1, cache.rawCacheFilesForTest().count { it.name.endsWith(".image") })
        assertEquals(1, published.count(File::isFile))
        pool.shutdownNow()
        root.deleteRecursively()
    }

    @Test
    fun globalEvictionPreservesUnrelatedRootContent() {
        val root = File(context.cacheDir, "owned-cache-files-${System.nanoTime()}").apply { deleteRecursively() }
        val unrelated = File(root, "not-a-cache-host").apply { mkdirs() }
        val unrelatedImage = File(unrelated, "keep.image").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_000L)
        }
        val unrelatedEmptyDirectory = File(root, "empty-unrelated").apply { mkdirs() }
        val cache = PolarisArtworkDiskCache(
            context,
            "owned-host",
            47984,
            cacheRoot = root,
            maxCacheBytes = Long.MAX_VALUE,
            maxCacheEntries = 1,
        )
        val first = checkNotNull(cache.store("game-a", "poster", "rev-a", pngBytes(24, 24), "image/png"))
        val unrelatedInOwnedHost = File(first.parentFile, "keep.image").apply {
            writeBytes(byteArrayOf(4, 5, 6))
            setLastModified(1_000L)
        }

        val second = checkNotNull(cache.store("game-b", "poster", "rev-a", pngBytes(24, 24), "image/png"))

        assertFalse(first.exists())
        assertTrue(second.isFile)
        assertTrue(unrelatedImage.isFile)
        assertTrue(unrelatedInOwnedHost.isFile)
        assertTrue(unrelatedEmptyDirectory.isDirectory)
        root.deleteRecursively()
    }

    @Test
    fun resolveCoordinatorRunsOneResolverForConcurrentAndRepeatedRequests() {
        val coordinator = ArtworkResolveOnce<String>()
        val starts = AtomicInteger()
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)

        val futures = (0 until 6).map {
            pool.submit<String?> {
                coordinator.resolve("game-a") {
                    starts.incrementAndGet()
                    release.await(2, TimeUnit.SECONDS)
                    "resolved"
                }
            }
        }
        while (starts.get() == 0) Thread.yield()
        release.countDown()

        assertEquals(List(6) { "resolved" }, futures.map { it.get(2, TimeUnit.SECONDS) })
        assertEquals(1, starts.get())
        assertEquals("resolved", coordinator.resolve("game-a") { "should-not-run" })
        assertEquals(1, starts.get())
        coordinator.invalidate("game-a")
        assertEquals("resolved-again", coordinator.resolve("game-a") {
            starts.incrementAndGet()
            "resolved-again"
        })
        assertEquals(2, starts.get())
        pool.shutdownNow()
    }

    @Test
    fun resolveCoordinatorRetriesAfterNullResult() {
        val coordinator = ArtworkResolveOnce<String>()
        val starts = AtomicInteger()

        assertNull(coordinator.resolve("game-retry") {
            starts.incrementAndGet()
            null
        })
        assertEquals("resolved", coordinator.resolve("game-retry") {
            starts.incrementAndGet()
            "resolved"
        })
        assertEquals(2, starts.get())
    }

    @Test
    fun sampleSizeNeverUndershootsTheTargetAndDefaultsToFullResolution() {
        assertEquals(1, PolarisArtworkDiskCache.sampleSizeFor(2048, 3072, 0, 0))
        assertEquals(1, PolarisArtworkDiskCache.sampleSizeFor(600, 900, 512, 768))
        assertEquals(4, PolarisArtworkDiskCache.sampleSizeFor(2048, 3072, 512, 768))
        assertEquals(16, PolarisArtworkDiskCache.sampleSizeFor(4096, 4096, 256, 256))
        // Mismatched aspect ratios err toward too much resolution, never too little.
        assertEquals(1, PolarisArtworkDiskCache.sampleSizeFor(1920, 620, 512, 768))
    }

    @Test
    fun decodeAndLoadHonorTargetBucketWithoutDroppingBelowIt() {
        val sampled = checkNotNull(PolarisArtworkDiskCache.decodeBounded(pngBytes(64, 96), 16, 24))
        assertEquals(16, sampled.width)
        assertEquals(24, sampled.height)
        sampled.recycle()

        val fullRes = checkNotNull(PolarisArtworkDiskCache.decodeBounded(pngBytes(64, 96)))
        assertEquals(64, fullRes.width)
        assertEquals(96, fullRes.height)
        fullRes.recycle()

        val cache = PolarisArtworkDiskCache(context, "sampled-host", 47984)
        cache.clear()
        assertNotNull(cache.store("game-sampled", "poster", "rev-1", pngBytes(64, 96), "image/png"))
        val loaded = checkNotNull(
            cache.load("game-sampled", "poster", "rev-1", allowStale = false, targetWidth = 16, targetHeight = 24)
        )
        assertEquals(16, loaded.width)
        assertEquals(24, loaded.height)
        loaded.recycle()
        cache.clear()
    }

    @Test
    fun boundsValidationAcceptsRealImagesAndRejectsEmptyOrOversizedHeaders() {
        // Garbage non-image bytes are not asserted here: Robolectric's BitmapFactory
        // fabricates bounds for them, and production rejects garbage earlier via the
        // image-signature check before bounds validation runs.
        assertTrue(PolarisArtworkDiskCache.validateImageBounds(pngBytes(3, 4)))
        assertFalse(PolarisArtworkDiskCache.validateImageBounds(ByteArray(0)))
        assertFalse(PolarisArtworkDiskCache.validateImageBounds(pngWithDeclaredDimensions(9_000, 1)))
        assertFalse(PolarisArtworkDiskCache.validateImageBounds(pngWithDeclaredDimensions(8_000, 8_000)))
    }

    private fun pngWithDeclaredDimensions(width: Int, height: Int): ByteArray {
        val bytes = pngBytes(1, 1)
        writeInt(bytes, 16, width)
        writeInt(bytes, 20, height)
        val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        writeInt(bytes, 29, crc)
        return bytes
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) {
            bytes[offset + index] = (value ushr ((3 - index) * 8)).toByte()
        }
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }.also { bitmap.recycle() }
    }
}