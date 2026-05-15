package com.papi.nova;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinAndroidEntrypointsMigrationTest {
    @Test
    public void androidEntrypointsAreKotlinSources() {
        String[] names = {
                "KeyboardAccessibilityService",
                "StartExternalDisplayControlReceiver",
                "PosterContentProvider"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void androidEntrypointsKeepManifestCompatibleApis() throws NoSuchMethodException, NoSuchFieldException {
        assertTrue(AccessibilityService.class.isAssignableFrom(KeyboardAccessibilityService.class));
        assertTrue(BroadcastReceiver.class.isAssignableFrom(StartExternalDisplayControlReceiver.class));
        assertTrue(ContentProvider.class.isAssignableFrom(PosterContentProvider.class));

        StartExternalDisplayControlReceiver.class.getMethod("requestFocusToExternalDisplayControl", Context.class);
        StartExternalDisplayControlReceiver.class.getMethod("requestFocusToGameActivity", boolean.class);

        assertEquals(String.class, PosterContentProvider.class.getField("AUTHORITY").getType());
        assertEquals(String.class, PosterContentProvider.class.getField("PNG_MIME_TYPE").getType());
        assertEquals(int.class, PosterContentProvider.class.getField("APP_ID_PATH_INDEX").getType());
        assertEquals(int.class, PosterContentProvider.class.getField("COMPUTER_UUID_PATH_INDEX").getType());
        PosterContentProvider.class.getMethod("createBoxArtUri", String.class, String.class);
    }

    @Test
    public void posterContentProviderBuildsStableBoxArtUris() {
        String uuid = UUID.randomUUID().toString();

        Uri uri = PosterContentProvider.createBoxArtUri(uuid, "42");

        assertEquals(ContentResolver.SCHEME_CONTENT, uri.getScheme());
        assertEquals(PosterContentProvider.AUTHORITY, uri.getAuthority());
        assertEquals(Arrays.asList("boxart", uuid, "42"), uri.getPathSegments());
        assertEquals("image/png", PosterContentProvider.PNG_MIME_TYPE);
        assertEquals(1, PosterContentProvider.COMPUTER_UUID_PATH_INDEX);
        assertEquals(2, PosterContentProvider.APP_ID_PATH_INDEX);
    }

    @Test
    public void posterContentProviderRejectsInvalidBoxArtRequests() {
        PosterContentProvider provider = createProvider();
        String uuid = UUID.randomUUID().toString();

        assertThrows(UnsupportedOperationException.class,
                () -> provider.openBoxArtFile(PosterContentProvider.createBoxArtUri(uuid, "42"), "w"));
        assertThrows(FileNotFoundException.class,
                () -> provider.openBoxArtFile(Uri.parse("content://" + PosterContentProvider.AUTHORITY + "/boxart/" + uuid), "r"));
        assertThrows(FileNotFoundException.class,
                () -> provider.openBoxArtFile(PosterContentProvider.createBoxArtUri("not-a-uuid", "42"), "r"));
        assertThrows(FileNotFoundException.class,
                () -> provider.openBoxArtFile(PosterContentProvider.createBoxArtUri(uuid, "-1"), "r"));
    }

    @Test
    public void posterContentProviderOpensCachedBoxArtReadOnly() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PosterContentProvider provider = createProvider();
        String uuid = UUID.randomUUID().toString();
        String appId = "99";
        File image = new File(new File(new File(context.getCacheDir(), "boxart"), uuid), appId + ".png");
        assertTrue(image.getParentFile().mkdirs());
        try (FileOutputStream outputStream = new FileOutputStream(image)) {
            outputStream.write(new byte[] {1, 2, 3});
        }

        try (ParcelFileDescriptor descriptor = provider.openFile(PosterContentProvider.createBoxArtUri(uuid, appId), "r")) {
            assertNotNull(descriptor);
        }
    }

    private static PosterContentProvider createProvider() {
        return Robolectric.buildContentProvider(PosterContentProvider.class).create().get();
    }
}
