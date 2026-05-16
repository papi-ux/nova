package com.papi.nova.grid.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinGridAssetsMigrationTest {
    @Test
    fun gridAssetHelpersAreKotlinSources() {
        val names = arrayOf(
            "ScaledBitmap",
            "MemoryAssetLoader",
            "NetworkAssetLoader",
            "DiskAssetLoader",
            "CachedAppAssetLoader"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/grid/assets/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/grid/assets/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun migratedGridAssetHelpersKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val doubleType = Double::class.javaPrimitiveType!!

        ScaledBitmap::class.java.getConstructor()
        ScaledBitmap::class.java.getConstructor(intType, intType, Bitmap::class.java)
        ScaledBitmap::class.java.getField("originalWidth")
        ScaledBitmap::class.java.getField("originalHeight")
        ScaledBitmap::class.java.getField("bitmap")

        MemoryAssetLoader::class.java.getConstructor()
        MemoryAssetLoader::class.java.getMethod("loadBitmapFromCache", CachedAppAssetLoader.LoaderTuple::class.java)
        MemoryAssetLoader::class.java.getMethod(
            "populateCache",
            CachedAppAssetLoader.LoaderTuple::class.java,
            ScaledBitmap::class.java
        )
        MemoryAssetLoader::class.java.getMethod("clearCache")

        NetworkAssetLoader::class.java.getConstructor(Context::class.java, String::class.java)
        NetworkAssetLoader::class.java.getMethod("tryAcquire", CachedAppAssetLoader.LoaderTuple::class.java)
        NetworkAssetLoader::class.java.getMethod("release", CachedAppAssetLoader.LoaderTuple::class.java)
        assertEquals(
            InputStream::class.java,
            NetworkAssetLoader::class.java.getMethod("getBitmapStream", CachedAppAssetLoader.LoaderTuple::class.java).returnType
        )
        NetworkAssetLoader::class.java.getMethod("invalidate")

        DiskAssetLoader::class.java.getConstructor(Context::class.java)
        DiskAssetLoader::class.java.getMethod("checkCacheExists", CachedAppAssetLoader.LoaderTuple::class.java)
        DiskAssetLoader::class.java.getMethod("loadBitmapFromCache", CachedAppAssetLoader.LoaderTuple::class.java, intType)
        DiskAssetLoader::class.java.getMethod("getFile", String::class.java, intType)
        DiskAssetLoader::class.java.getMethod("deleteAssetsForComputer", String::class.java)
        DiskAssetLoader::class.java.getMethod(
            "populateCacheWithStream",
            CachedAppAssetLoader.LoaderTuple::class.java,
            InputStream::class.java
        )
        DiskAssetLoader::class.java.getMethod("calculateInSampleSize", BitmapFactory.Options::class.java, intType, intType)

        CachedAppAssetLoader::class.java.getConstructor(
            ComputerDetails::class.java,
            doubleType,
            NetworkAssetLoader::class.java,
            MemoryAssetLoader::class.java,
            DiskAssetLoader::class.java,
            Bitmap::class.java
        )
        CachedAppAssetLoader::class.java.getMethod("cancelBackgroundLoads")
        CachedAppAssetLoader::class.java.getMethod("cancelForegroundLoads")
        CachedAppAssetLoader::class.java.getMethod("freeCacheMemory")
        CachedAppAssetLoader::class.java.getMethod("queueCacheLoad", NvApp::class.java)
        assertEquals(
            booleanType,
            CachedAppAssetLoader::class.java.getMethod("populateImageView", NvApp::class.java, ImageView::class.java).returnType
        )
        assertEquals(
            booleanType,
            CachedAppAssetLoader::class.java.getMethod(
                "populateImageView",
                NvApp::class.java,
                ImageView::class.java,
                TextView::class.java
            ).returnType
        )

        CachedAppAssetLoader.LoaderTuple::class.java.getConstructor(ComputerDetails::class.java, NvApp::class.java)
        CachedAppAssetLoader.LoaderTuple::class.java.getField("computer")
        CachedAppAssetLoader.LoaderTuple::class.java.getField("app")
    }

    @Test
    fun memoryAssetLoaderStoresAndClearsScaledBitmaps() {
        val loader = MemoryAssetLoader()
        loader.clearCache()

        val tuple = createTuple("computer-a", 42)
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val scaledBitmap = ScaledBitmap(200, 300, bitmap)

        assertNull(loader.loadBitmapFromCache(tuple))

        loader.populateCache(tuple, scaledBitmap)
        val cached = loader.loadBitmapFromCache(tuple)

        assertSame(scaledBitmap, cached)
        assertEquals(200, cached!!.originalWidth)
        assertEquals(300, cached.originalHeight)
        assertSame(bitmap, cached.bitmap)

        loader.clearCache()
        assertNull(loader.loadBitmapFromCache(tuple))
    }

    @Test
    fun networkAssetLoaderDeduplicatesInFlightLoadsByTupleKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = NetworkAssetLoader(context, "unique-id")
        val tuple = createTuple("computer-b", 77)
        val sameKey = createTuple("computer-b", 77)
        val differentApp = createTuple("computer-b", 78)

        assertTrue(loader.tryAcquire(tuple))
        assertFalse(loader.tryAcquire(sameKey))
        assertTrue(loader.tryAcquire(differentApp))

        loader.release(tuple)
        assertTrue(loader.tryAcquire(sameKey))

        loader.invalidate()
        assertTrue(loader.tryAcquire(tuple))
    }

    @Test
    fun diskAssetLoaderStoresAndDeletesCachedStreams() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = DiskAssetLoader(context)
        val tuple = createTuple("computer-disk", 99)
        loader.deleteAssetsForComputer(tuple.computer.uuid)

        assertFalse(loader.checkCacheExists(tuple))

        val bitmap = Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))

        loader.populateCacheWithStream(tuple, ByteArrayInputStream(output.toByteArray()))

        assertTrue(loader.checkCacheExists(tuple))
        assertTrue(loader.getFile(tuple.computer.uuid, tuple.app.appId).exists())

        loader.deleteAssetsForComputer(tuple.computer.uuid)
        assertFalse(loader.checkCacheExists(tuple))
    }

    @Test
    fun diskAssetLoaderKeepsSampleSizeCalculation() {
        val options = BitmapFactory.Options()
        options.outWidth = 1200
        options.outHeight = 1600

        assertEquals(4, DiskAssetLoader.calculateInSampleSize(options, 300, 400))
        assertEquals(1, DiskAssetLoader.calculateInSampleSize(options, 900, 1200))
    }

    @Test
    fun loaderTupleKeepsJavaFieldAndEqualityContract() {
        val tuple = createTuple("computer-tuple", 7)
        val sameKey = createTuple("computer-tuple", 7)
        val differentComputer = createTuple("computer-other", 7)
        val differentApp = createTuple("computer-tuple", 8)

        assertEquals("computer-tuple", tuple.computer.uuid)
        assertEquals(7, tuple.app.appId)
        assertEquals(tuple, sameKey)
        assertFalse(tuple == differentComputer)
        assertFalse(tuple == differentApp)
        assertFalse(tuple.equals("not-a-loader-tuple"))
        assertEquals("(computer-tuple, 7)", tuple.toString())
    }

    @Test
    fun scaledBitmapKeepsMutableJavaFields() {
        val scaledBitmap = ScaledBitmap()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        scaledBitmap.originalWidth = 10
        scaledBitmap.originalHeight = 20
        scaledBitmap.bitmap = bitmap

        assertEquals(10, scaledBitmap.originalWidth)
        assertEquals(20, scaledBitmap.originalHeight)
        assertSame(bitmap, scaledBitmap.bitmap)

        val constructed = ScaledBitmap(30, 40, bitmap)
        assertEquals(30, constructed.originalWidth)
        assertEquals(40, constructed.originalHeight)
        assertSame(bitmap, constructed.bitmap)
        assertNotNull(constructed.bitmap)
    }

    private fun createTuple(computerUuid: String, appId: Int): CachedAppAssetLoader.LoaderTuple {
        val computer = ComputerDetails()
        computer.uuid = computerUuid
        val app = NvApp("Test App")
        app.appId = appId
        return CachedAppAssetLoader.LoaderTuple(computer, app)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
