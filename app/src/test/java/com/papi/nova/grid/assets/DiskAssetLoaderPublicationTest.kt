package com.papi.nova.grid.assets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class DiskAssetLoaderPublicationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = DiskAssetLoader(context)
    private val tuple = CachedAppAssetLoader.LoaderTuple(
        ComputerDetails().apply { uuid = UUID.randomUUID().toString() },
        NvApp("Cache fixture").apply { appId = 42 }
    )
    private val previous = "previous complete cover".toByteArray()
    private val replacement = ByteArray(12_000) { (it % 251).toByte() }

    private class HeldStream(bytes: ByteArray, private val failAfterRelease: Boolean = false) :
        ByteArrayInputStream(bytes) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var reads = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (++reads == 2) {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Fixture was not released")
                if (failAfterRelease) throw IOException("Fixture transfer failed")
            }
            return super.read(buffer, offset, length)
        }
    }

    private fun heldDownload(stream: HeldStream, whileHeld: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit { loader.populateCacheWithStream(tuple, stream) }
        try {
            assertTrue("Download did not reach its held read", stream.entered.await(5, TimeUnit.SECONDS))
            whileHeld()
        } finally {
            stream.release.countDown()
            try {
                task.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun assertNoTemporaryFiles() {
        val directory = loader.getFile(tuple.computer.uuid, tuple.app.appId).parentFile!!
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun firstDownloadIsInvisibleUntilComplete() {
        heldDownload(HeldStream(replacement)) {
            assertFalse(loader.checkCacheExists(tuple))
        }
        assertArrayEquals(replacement, loader.getFile(tuple.computer.uuid, tuple.app.appId).readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun readersKeepThePreviousCoverWhileReplacementDownloads() {
        loader.populateCacheWithStream(tuple, ByteArrayInputStream(previous))
        heldDownload(HeldStream(replacement)) {
            assertArrayEquals(previous, loader.getFile(tuple.computer.uuid, tuple.app.appId).readBytes())
        }
        assertArrayEquals(replacement, loader.getFile(tuple.computer.uuid, tuple.app.appId).readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun failedReplacementKeepsThePreviousCover() {
        loader.populateCacheWithStream(tuple, ByteArrayInputStream(previous))
        heldDownload(HeldStream(replacement, failAfterRelease = true)) {}
        assertArrayEquals(previous, loader.getFile(tuple.computer.uuid, tuple.app.appId).readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun failedWriterCannotDeleteAnotherLoadersCompletedCover() {
        val winner = "completed by another loader".toByteArray()
        heldDownload(HeldStream(replacement, failAfterRelease = true)) {
            DiskAssetLoader(context).populateCacheWithStream(tuple, ByteArrayInputStream(winner))
        }
        assertArrayEquals(winner, loader.getFile(tuple.computer.uuid, tuple.app.appId).readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun oversizedReplacementKeepsThePreviousCover() {
        loader.populateCacheWithStream(tuple, ByteArrayInputStream(previous))
        loader.populateCacheWithStream(tuple, ByteArrayInputStream(ByteArray(5 * 1024 * 1024 + 1)))
        assertArrayEquals(previous, loader.getFile(tuple.computer.uuid, tuple.app.appId).readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun unsafeHostComponentsAreRejectedBeforeCreatingCacheDirectories() {
        for (host in listOf("../outside", "/absolute", "host/child", "host\\child")) {
            val unsafe = CachedAppAssetLoader.LoaderTuple(ComputerDetails().apply { uuid = host }, tuple.app)
            loader.populateCacheWithStream(unsafe, ByteArrayInputStream(replacement))
        }
        assertFalse(File(context.cacheDir, "boxart").exists())
    }

    @Test
    fun symlinkOutsideCacheCannotBeUsedAsThePublicationDirectory() {
        val outside = File(context.filesDir, "outside-cache").apply { mkdirs() }
        val original = File(outside, "42.png").apply { writeBytes(previous) }
        val boxart = File(context.cacheDir, "boxart").apply { mkdirs() }
        Files.createSymbolicLink(File(boxart, tuple.computer.uuid).toPath(), outside.toPath())
        loader.populateCacheWithStream(tuple, ByteArrayInputStream(replacement))
        assertArrayEquals(previous, original.readBytes())
        assertTrue(outside.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
