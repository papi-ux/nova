package com.papi.nova.grid.assets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvApp;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinGridAssetsMigrationTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        com.papi.nova.TestLogSuppressor.install();
    }

    @Test
    public void gridAssetHelpersAreKotlinSources() {
        String[] names = {
                "ScaledBitmap",
                "MemoryAssetLoader",
                "NetworkAssetLoader",
                "DiskAssetLoader"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/grid/assets/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/grid/assets/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void migratedGridAssetHelpersKeepJavaCompatibleApis() throws NoSuchMethodException, NoSuchFieldException {
        ScaledBitmap.class.getConstructor();
        ScaledBitmap.class.getConstructor(int.class, int.class, Bitmap.class);
        ScaledBitmap.class.getField("originalWidth");
        ScaledBitmap.class.getField("originalHeight");
        ScaledBitmap.class.getField("bitmap");

        MemoryAssetLoader.class.getConstructor();
        MemoryAssetLoader.class.getMethod("loadBitmapFromCache", CachedAppAssetLoader.LoaderTuple.class);
        MemoryAssetLoader.class.getMethod("populateCache", CachedAppAssetLoader.LoaderTuple.class, ScaledBitmap.class);
        MemoryAssetLoader.class.getMethod("clearCache");

        NetworkAssetLoader.class.getConstructor(Context.class, String.class);
        NetworkAssetLoader.class.getMethod("tryAcquire", CachedAppAssetLoader.LoaderTuple.class);
        NetworkAssetLoader.class.getMethod("release", CachedAppAssetLoader.LoaderTuple.class);
        assertEquals(InputStream.class, NetworkAssetLoader.class.getMethod("getBitmapStream", CachedAppAssetLoader.LoaderTuple.class).getReturnType());
        NetworkAssetLoader.class.getMethod("invalidate");

        DiskAssetLoader.class.getConstructor(Context.class);
        DiskAssetLoader.class.getMethod("checkCacheExists", CachedAppAssetLoader.LoaderTuple.class);
        DiskAssetLoader.class.getMethod("loadBitmapFromCache", CachedAppAssetLoader.LoaderTuple.class, int.class);
        DiskAssetLoader.class.getMethod("getFile", String.class, int.class);
        DiskAssetLoader.class.getMethod("deleteAssetsForComputer", String.class);
        DiskAssetLoader.class.getMethod("populateCacheWithStream", CachedAppAssetLoader.LoaderTuple.class, InputStream.class);
        DiskAssetLoader.class.getMethod("calculateInSampleSize", BitmapFactory.Options.class, int.class, int.class);
    }

    @Test
    public void memoryAssetLoaderStoresAndClearsScaledBitmaps() {
        MemoryAssetLoader loader = new MemoryAssetLoader();
        loader.clearCache();

        CachedAppAssetLoader.LoaderTuple tuple = createTuple("computer-a", 42);
        Bitmap bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        ScaledBitmap scaledBitmap = new ScaledBitmap(200, 300, bitmap);

        assertNull(loader.loadBitmapFromCache(tuple));

        loader.populateCache(tuple, scaledBitmap);
        ScaledBitmap cached = loader.loadBitmapFromCache(tuple);

        assertSame(scaledBitmap, cached);
        assertEquals(200, cached.originalWidth);
        assertEquals(300, cached.originalHeight);
        assertSame(bitmap, cached.bitmap);

        loader.clearCache();
        assertNull(loader.loadBitmapFromCache(tuple));
    }

    @Test
    public void networkAssetLoaderDeduplicatesInFlightLoadsByTupleKey() {
        Context context = ApplicationProvider.getApplicationContext();
        NetworkAssetLoader loader = new NetworkAssetLoader(context, "unique-id");
        CachedAppAssetLoader.LoaderTuple tuple = createTuple("computer-b", 77);
        CachedAppAssetLoader.LoaderTuple sameKey = createTuple("computer-b", 77);
        CachedAppAssetLoader.LoaderTuple differentApp = createTuple("computer-b", 78);

        assertTrue(loader.tryAcquire(tuple));
        assertFalse(loader.tryAcquire(sameKey));
        assertTrue(loader.tryAcquire(differentApp));

        loader.release(tuple);
        assertTrue(loader.tryAcquire(sameKey));

        loader.invalidate();
        assertTrue(loader.tryAcquire(tuple));
    }

    @Test
    public void diskAssetLoaderStoresAndDeletesCachedStreams() {
        Context context = ApplicationProvider.getApplicationContext();
        DiskAssetLoader loader = new DiskAssetLoader(context);
        CachedAppAssetLoader.LoaderTuple tuple = createTuple("computer-disk", 99);
        loader.deleteAssetsForComputer(tuple.computer.uuid);

        assertFalse(loader.checkCacheExists(tuple));

        Bitmap bitmap = Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));

        loader.populateCacheWithStream(tuple, new ByteArrayInputStream(output.toByteArray()));

        assertTrue(loader.checkCacheExists(tuple));
        assertTrue(loader.getFile(tuple.computer.uuid, tuple.app.getAppId()).exists());

        loader.deleteAssetsForComputer(tuple.computer.uuid);
        assertFalse(loader.checkCacheExists(tuple));
    }

    @Test
    public void diskAssetLoaderKeepsSampleSizeCalculation() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.outWidth = 1200;
        options.outHeight = 1600;

        assertEquals(4, DiskAssetLoader.calculateInSampleSize(options, 300, 400));
        assertEquals(1, DiskAssetLoader.calculateInSampleSize(options, 900, 1200));
    }

    @Test
    public void scaledBitmapKeepsMutableJavaFields() {
        ScaledBitmap scaledBitmap = new ScaledBitmap();
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);

        scaledBitmap.originalWidth = 10;
        scaledBitmap.originalHeight = 20;
        scaledBitmap.bitmap = bitmap;

        assertEquals(10, scaledBitmap.originalWidth);
        assertEquals(20, scaledBitmap.originalHeight);
        assertSame(bitmap, scaledBitmap.bitmap);

        ScaledBitmap constructed = new ScaledBitmap(30, 40, bitmap);
        assertEquals(30, constructed.originalWidth);
        assertEquals(40, constructed.originalHeight);
        assertSame(bitmap, constructed.bitmap);
        assertNotNull(constructed.bitmap);
    }

    private static CachedAppAssetLoader.LoaderTuple createTuple(String computerUuid, int appId) {
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = computerUuid;
        NvApp app = new NvApp("Test App");
        app.setAppId(appId);
        return new CachedAppAssetLoader.LoaderTuple(computer, app);
    }
}
