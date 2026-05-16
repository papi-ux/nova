package com.papi.nova

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinAndroidEntrypointsMigrationTest {
    @Test
    fun androidEntrypointsAreKotlinSources() {
        val names = arrayOf(
            "KeyboardAccessibilityService",
            "StartExternalDisplayControlReceiver",
            "PosterContentProvider"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun androidEntrypointsKeepManifestCompatibleApis() {
        assertTrue(AccessibilityService::class.java.isAssignableFrom(KeyboardAccessibilityService::class.java))
        assertTrue(BroadcastReceiver::class.java.isAssignableFrom(StartExternalDisplayControlReceiver::class.java))
        assertTrue(ContentProvider::class.java.isAssignableFrom(PosterContentProvider::class.java))

        StartExternalDisplayControlReceiver::class.java.getMethod(
            "requestFocusToExternalDisplayControl",
            Context::class.java
        )
        StartExternalDisplayControlReceiver::class.java.getMethod(
            "requestFocusToGameActivity",
            Boolean::class.javaPrimitiveType!!
        )

        assertEquals(String::class.java, PosterContentProvider::class.java.getField("AUTHORITY").type)
        assertEquals(String::class.java, PosterContentProvider::class.java.getField("PNG_MIME_TYPE").type)
        assertEquals(Int::class.javaPrimitiveType!!, PosterContentProvider::class.java.getField("APP_ID_PATH_INDEX").type)
        assertEquals(
            Int::class.javaPrimitiveType!!,
            PosterContentProvider::class.java.getField("COMPUTER_UUID_PATH_INDEX").type
        )
        PosterContentProvider::class.java.getMethod("createBoxArtUri", String::class.java, String::class.java)
    }

    @Test
    fun posterContentProviderBuildsStableBoxArtUris() {
        val uuid = UUID.randomUUID().toString()

        val uri = PosterContentProvider.createBoxArtUri(uuid, "42")

        assertEquals(ContentResolver.SCHEME_CONTENT, uri.scheme)
        assertEquals(PosterContentProvider.AUTHORITY, uri.authority)
        assertEquals(listOf("boxart", uuid, "42"), uri.pathSegments)
        assertEquals("image/png", PosterContentProvider.PNG_MIME_TYPE)
        assertEquals(1, PosterContentProvider.COMPUTER_UUID_PATH_INDEX)
        assertEquals(2, PosterContentProvider.APP_ID_PATH_INDEX)
    }

    @Test
    fun posterContentProviderRejectsInvalidBoxArtRequests() {
        val provider = createProvider()
        val uuid = UUID.randomUUID().toString()

        assertThrows(UnsupportedOperationException::class.java) {
            provider.openBoxArtFile(PosterContentProvider.createBoxArtUri(uuid, "42"), "w")
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.openBoxArtFile(Uri.parse("content://${PosterContentProvider.AUTHORITY}/boxart/$uuid"), "r")
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.openBoxArtFile(PosterContentProvider.createBoxArtUri("not-a-uuid", "42"), "r")
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.openBoxArtFile(PosterContentProvider.createBoxArtUri(uuid, "-1"), "r")
        }
    }

    @Test
    fun posterContentProviderOpensCachedBoxArtReadOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = createProvider()
        val uuid = UUID.randomUUID().toString()
        val appId = "99"
        val image = File(File(File(context.cacheDir, "boxart"), uuid), "$appId.png")
        assertTrue(image.parentFile!!.mkdirs())
        FileOutputStream(image).use { outputStream ->
            outputStream.write(byteArrayOf(1, 2, 3))
        }

        provider.openFile(PosterContentProvider.createBoxArtUri(uuid, appId), "r").use { descriptor: ParcelFileDescriptor? ->
            assertNotNull(descriptor)
        }
    }

    companion object {
        private fun createProvider(): PosterContentProvider {
            return Robolectric.buildContentProvider(PosterContentProvider::class.java).create().get()
        }
    }
}
