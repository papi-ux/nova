package com.papi.nova.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheHelperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cacheStreamsAllowSafeComponents() {
        val root = temporaryFolder.newFolder("cache")

        CacheHelper.openCacheFileForOutput(root, "boxart", "host-uuid", "123.png").use { outputStream ->
            CacheHelper.writeStringToOutputStream(outputStream, "ok")
        }

        CacheHelper.openCacheFileForInput(root, "boxart", "host-uuid", "123.png").use { inputStream ->
            assertEquals("ok", CacheHelper.readInputStreamToString(inputStream))
        }
    }

    @Test
    fun cacheStreamsRejectParentTraversalComponents() {
        val root = temporaryFolder.newFolder("cache")

        try {
            CacheHelper.openCacheFileForOutput(root, "boxart", "..", "escape.png")
            fail("Expected parent traversal component to be rejected")
        } catch (expected: IOException) {
            // Expected.
        }
    }

    @Test
    fun cacheStreamsRejectEmbeddedSeparators() {
        val root = temporaryFolder.newFolder("cache")

        try {
            CacheHelper.openCacheFileForInput(root, "boxart/escape.png")
            fail("Expected embedded separator to be rejected")
        } catch (expected: IOException) {
            // Expected.
        }
    }

    @Test
    fun cacheStreamsRejectSymlinkEscapes() {
        val root = temporaryFolder.newFolder("cache")
        val outside = temporaryFolder.newFolder("outside")
        val secret = File(outside, "secret.txt").apply {
            writeText("secret")
        }
        val link = File(root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        } catch (e: IOException) {
            assumeNoException(e)
        }

        try {
            CacheHelper.openCacheFileForInput(root, "link", secret.name)
            fail("Expected cache stream to reject symlink escape")
        } catch (expected: IOException) {
            // Expected.
        }
    }
}
