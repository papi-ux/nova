package com.papi.nova;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.papi.nova.computers.ComputerManagerService;
import com.papi.nova.discovery.DiscoveryService;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener;
import com.papi.nova.utils.ServerHelper;
import com.papi.nova.utils.ShortcutHelper;
import com.papi.nova.utils.TvChannelHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class KotlinLaunchHelpersMigrationTest {
    @Test
    public void launchHelpersAreKotlinSources() {
        String[] names = {
                "ShortcutTrampoline",
                "discovery/DiscoveryService",
                "utils/ServerHelper",
                "utils/ShortcutHelper",
                "utils/TvChannelHelper"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void launchHelpersKeepJavaCompatibleApis() throws Exception {
        assertTrue(Activity.class.isAssignableFrom(ShortcutTrampoline.class));

        assertTrue(Service.class.isAssignableFrom(DiscoveryService.class));
        DiscoveryService.DiscoveryBinder.class.getMethod("setListener", MdnsDiscoveryListener.class);
        DiscoveryService.DiscoveryBinder.class.getMethod("startDiscovery", int.class);
        DiscoveryService.DiscoveryBinder.class.getMethod("stopDiscovery");
        assertEquals(List.class, DiscoveryService.DiscoveryBinder.class.getMethod("getComputerSet").getReturnType());

        assertEquals("android.conntest.moonlight-stream.org", ServerHelper.CONNECTION_TEST_SERVER);
        ServerHelper.class.getMethod("getCurrentAddressFromComputer", ComputerDetails.class);
        ServerHelper.class.getMethod("createPcShortcutIntent", Activity.class, ComputerDetails.class);
        ServerHelper.class.getMethod("createAppShortcutIntent", Activity.class, ComputerDetails.class, com.papi.nova.nvstream.http.NvApp.class);
        ServerHelper.class.getMethod("getActiveDisplay", Context.class, com.papi.nova.preferences.PreferenceConfiguration.class);
        ServerHelper.class.getMethod("getSecondaryDisplay", Context.class);
        ServerHelper.class.getMethod("createStartIntent", Activity.class, com.papi.nova.nvstream.http.NvApp.class, ComputerDetails.class,
                ComputerManagerService.ComputerManagerBinder.class, boolean.class);
        ServerHelper.class.getMethod("doNetworkTest", Activity.class);

        assertEquals(778, ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE);
        assertEquals("host_uuid", ShortcutHelper.KEY_HOST_UUID);
        assertEquals("host_name", ShortcutHelper.KEY_HOST_NAME);
        assertEquals("app_uuid", ShortcutHelper.KEY_APP_UUID);
        assertEquals("app_name", ShortcutHelper.KEY_APP_NAME);
        assertEquals("app_id", ShortcutHelper.KEY_APP_ID);
        ShortcutHelper.class.getConstructor(Activity.class);
        ShortcutHelper.class.getMethod("reportComputerShortcutUsed", ComputerDetails.class);
        ShortcutHelper.class.getMethod("createPinnedGameShortcut", ComputerDetails.class, com.papi.nova.nvstream.http.NvApp.class, android.graphics.Bitmap.class);
        ShortcutHelper.class.getMethod("writeArtFileToUri", Activity.class, Uri.class);

        Class<?> previewBuilder = Class.forName("com.papi.nova.utils.TvChannelHelper$PreviewProgramBuilder");
        previewBuilder.getDeclaredConstructor();
        previewBuilder.getDeclaredMethod("setChannelId", Long.class);
        previewBuilder.getDeclaredMethod("setType", int.class);
        previewBuilder.getDeclaredMethod("setTitle", String.class);
        previewBuilder.getDeclaredMethod("setPosterArtAspectRatio", int.class);
        previewBuilder.getDeclaredMethod("setIntent", Intent.class);
        previewBuilder.getDeclaredMethod("setIntentUri", Uri.class);
        previewBuilder.getDeclaredMethod("setInternalProviderId", String.class);
        previewBuilder.getDeclaredMethod("setPosterArtUri", Uri.class);
        previewBuilder.getDeclaredMethod("setWeight", int.class);
        previewBuilder.getDeclaredMethod("toContentValues");
        Class<?> channelBuilder = Class.forName("com.papi.nova.utils.TvChannelHelper$ChannelBuilder");
        channelBuilder.getDeclaredConstructor();
        channelBuilder.getDeclaredMethod("setType", String.class);
        channelBuilder.getDeclaredMethod("setDisplayName", String.class);
        channelBuilder.getDeclaredMethod("setInternalProviderId", String.class);
        channelBuilder.getDeclaredMethod("setAppLinkIntent", Intent.class);
        channelBuilder.getDeclaredMethod("toContentValues");
    }

    @Test
    public void tvChannelBuildersKeepContentValuesShape() {
        Uri poster = Uri.parse("content://poster");
        Object previewBuilder = newPrivateBuilder("com.papi.nova.utils.TvChannelHelper$PreviewProgramBuilder");
        invokeBuilder(previewBuilder, "setChannelId", Long.class, 12L);
        invokeBuilder(previewBuilder, "setType", int.class, 0);
        invokeBuilder(previewBuilder, "setTitle", String.class, "Nova");
        invokeBuilder(previewBuilder, "setPosterArtAspectRatio", int.class, 1);
        invokeBuilder(previewBuilder, "setIntentUri", Uri.class, Uri.parse("nova://launch"));
        invokeBuilder(previewBuilder, "setInternalProviderId", String.class, "game-1");
        invokeBuilder(previewBuilder, "setPosterArtUri", Uri.class, poster);
        invokeBuilder(previewBuilder, "setWeight", int.class, 7);
        ContentValues values = toContentValues(previewBuilder);

        assertEquals(12L, values.getAsLong("channel_id").longValue());
        assertEquals("Nova", values.getAsString("title"));
        assertEquals("game-1", values.getAsString("internal_provider_id"));
        assertEquals(poster.toString(), values.getAsString("poster_art_uri"));

        Object channelBuilder = newPrivateBuilder("com.papi.nova.utils.TvChannelHelper$ChannelBuilder");
        invokeBuilder(channelBuilder, "setType", String.class, "TYPE_PREVIEW");
        invokeBuilder(channelBuilder, "setDisplayName", String.class, "Nova");
        invokeBuilder(channelBuilder, "setInternalProviderId", String.class, "nova");
        invokeBuilder(channelBuilder, "setAppLinkIntent", Intent.class,
                new Intent(Intent.ACTION_VIEW, Uri.parse("nova://home")));
        ContentValues channel = toContentValues(channelBuilder);

        assertEquals("TYPE_PREVIEW", channel.getAsString("type"));
        assertEquals("Nova", channel.getAsString("display_name"));
        assertEquals("nova", channel.getAsString("internal_provider_id"));
    }

    private static Object newPrivateBuilder(String className) {
        try {
            Constructor<?> constructor = Class.forName(className).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new AssertionError("Unable to construct " + className, e);
        }
    }

    private static void invokeBuilder(Object target, String methodName, Class<?> paramType, Object value) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, paramType);
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (Exception e) {
            throw new AssertionError("Unable to call " + methodName, e);
        }
    }

    private static ContentValues toContentValues(Object target) {
        try {
            Method method = target.getClass().getDeclaredMethod("toContentValues");
            method.setAccessible(true);
            return (ContentValues) method.invoke(target);
        } catch (Exception e) {
            throw new AssertionError("Unable to call toContentValues", e);
        }
    }
}
