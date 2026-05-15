package com.papi.nova.manager;

import android.content.Context;
import android.view.Display;

import com.papi.nova.binding.video.MediaCodecDecoderRenderer;
import com.papi.nova.nvstream.jni.MoonBridge;
import com.papi.nova.preferences.PreferenceConfiguration;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinStreamSyncManagerMigrationTest {
    @Test
    public void streamSyncManagerIsKotlinSource() {
        String path = "src/main/java/com/papi/nova/manager/StreamSyncManager";

        assertFalse(path + " should no longer be a Java source", new File(path + ".java").exists());
        assertTrue(path + " should be migrated to Kotlin", new File(path + ".kt").exists());
    }

    @Test
    public void streamSyncManagerKeepsJavaCompatibleApis() throws Exception {
        Field syncMode = StreamSyncManager.class.getField("SYNC_MODE_AUTO_SAFE");
        assertEquals(String.class, syncMode.getType());
        assertTrue(Modifier.isStatic(syncMode.getModifiers()));
        assertEquals("auto_safe", syncMode.get(null));

        StreamSyncManager.StreamResolution.class.getConstructor(int.class, int.class);
        assertEquals(int.class, StreamSyncManager.StreamResolution.class.getField("width").getType());
        assertEquals(int.class, StreamSyncManager.StreamResolution.class.getField("height").getType());
        assertEquals(boolean.class, StreamSyncManager.StreamResolution.class.getMethod("isValid").getReturnType());

        assertEquals(int.class, StreamSyncManager.class.getMethod(
                "resolveAutoSafeBitrateKbps", int.class, JSONObject.class).getReturnType());
        assertEquals(StreamSyncManager.StreamResolution.class, StreamSyncManager.class.getMethod(
                "resolveAutoSafeResolution", int.class, int.class, JSONObject.class).getReturnType());
        assertEquals(float.class, StreamSyncManager.class.getMethod(
                "resolveAutoSafeTargetFps", float.class, JSONObject.class).getReturnType());
        assertEquals(float.class, StreamSyncManager.class.getMethod(
                "resolveDisplayCompatibleAutoSafeTargetFps", float.class, float.class, float[].class).getReturnType());
        assertEquals(boolean.class, StreamSyncManager.class.getMethod(
                "shouldPreferStabilityDecoder", JSONObject.class).getReturnType());
        assertEquals(boolean.class, StreamSyncManager.class.getMethod(
                "shouldForceFreshLaunch", JSONObject.class).getReturnType());
        assertEquals(boolean.class, StreamSyncManager.class.getMethod(
                "shouldPreferStableRefreshMultiple", JSONObject.class, float.class).getReturnType());
        assertEquals(JSONObject.class, StreamSyncManager.class.getMethod(
                "buildDeviceCapabilities",
                Context.class,
                Display.class,
                MediaCodecDecoderRenderer.class,
                int.class,
                boolean.class,
                boolean.class).getReturnType());
        assertEquals(JSONObject.class, StreamSyncManager.class.getMethod(
                "buildClientRuntime",
                Context.class,
                MediaCodecDecoderRenderer.class,
                float.class,
                int.class,
                String.class,
                int.class).getReturnType());
        assertEquals(JSONObject.class, StreamSyncManager.class.getMethod(
                "buildAppliedStreamSettings",
                int.class,
                int.class,
                int.class,
                float.class,
                float.class,
                boolean.class,
                boolean.class,
                int.class,
                PreferenceConfiguration.FormatOption.class,
                boolean.class).getReturnType());
    }

    @Test
    public void streamSyncManagerKeepsJsonBuilderBehavior() throws Exception {
        JSONObject applied = StreamSyncManager.buildAppliedStreamSettings(
                12000,
                1280,
                720,
                60f,
                59.94f,
                true,
                false,
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8,
                PreferenceConfiguration.FormatOption.AUTO,
                true);

        assertEquals(12000, applied.getInt("target_bitrate_kbps"));
        assertEquals("1280x720x60", applied.getString("display_mode"));
        assertEquals(1280, applied.getInt("width"));
        assertEquals(720, applied.getInt("height"));
        assertEquals(60f, (float) applied.getDouble("launch_refresh_rate_hz"), 0.01f);
        assertEquals(59.94f, (float) applied.getDouble("render_refresh_rate_hz"), 0.01f);
        assertTrue(applied.getBoolean("virtual_display"));
        assertFalse(applied.getBoolean("hdr"));
        assertTrue(applied.getBoolean("display_mode_explicit"));
        assertEquals("av1", applied.getString("preferred_codec"));
    }
}
