package com.papi.nova.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CacheHelperTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cacheStreamsAllowSafeComponents() throws IOException {
        File root = temporaryFolder.newFolder("cache");

        try (OutputStream outputStream = CacheHelper.openCacheFileForOutput(
                root, "boxart", "host-uuid", "123.png")) {
            CacheHelper.writeStringToOutputStream(outputStream, "ok");
        }

        try (InputStream inputStream = CacheHelper.openCacheFileForInput(
                root, "boxart", "host-uuid", "123.png")) {
            assertEquals("ok", CacheHelper.readInputStreamToString(inputStream));
        }
    }

    @Test
    public void cacheStreamsRejectParentTraversalComponents() throws IOException {
        File root = temporaryFolder.newFolder("cache");

        try {
            CacheHelper.openCacheFileForOutput(root, "boxart", "..", "escape.png");
            fail("Expected parent traversal component to be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void cacheStreamsRejectEmbeddedSeparators() throws IOException {
        File root = temporaryFolder.newFolder("cache");

        try {
            CacheHelper.openCacheFileForInput(root, "boxart/escape.png");
            fail("Expected embedded separator to be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
