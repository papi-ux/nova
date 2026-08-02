package com.papi.nova.api

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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