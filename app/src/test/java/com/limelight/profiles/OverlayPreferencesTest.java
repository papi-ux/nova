package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.limelight.TestLogSuppressor;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class OverlayPreferencesTest {
    private Context ctx;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setup() {
        ctx = ApplicationProvider.getApplicationContext();
        // Reset singleton to ensure clean slate for each run
        ProfilesManager.instance = null;
    }

    /**
     * Ensure that a Double originating from Gson is properly coerced to int via getInt().
     */
    @Test
    public void overlayPref_CoercesDoubleToInt() {
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.load(ctx);

        // Build options map through Gson to replicate production deserialization (numbers => Double)
        String json = "{\"video_bitrate_kbps\":15000}";
        Type t = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> opts = new Gson().fromJson(json, t);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Test", 0, 0, opts);
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences sp = pm.getOverlayingSharedPreferences(ctx);
        assertEquals(15000, sp.getInt("video_bitrate_kbps", -1));
    }

    /**
     * Ensure that a Double value is coerced correctly by getLong().
     */
    @Test
    public void overlayPref_CoercesDoubleToLong() {
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.load(ctx);
        String json = "{\"decoder_flush_delay_ms\":250.0}"; // value comes back as Double
        Type t = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> opts = new Gson().fromJson(json, t);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "TestLong", 0, 0, opts);
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences sp = pm.getOverlayingSharedPreferences(ctx);
        assertEquals(250L, sp.getLong("decoder_flush_delay_ms", -1));
    }

    /**
     * Verify that the overlay zoom toggle visibility and zoom\\pan remembering options
     * are persisted with the profile and correctly reloaded across sessions.
     */
    @Test
    public void overlayPref_RemembersZoomOptionsBetweenSessions() {
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.load(ctx);

        String json = "{\"checkbox_show_overlay_zoom_toggle_button\":true," +
                "\"checkbox_remember_zoom_pan\":true," +
                "\"number_zoom_scale\":1.5," +
                "\"number_pan_offset_x\":0.25," +
                "\"number_pan_offset_y\":0.25}";
        Type t = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> opts = new Gson().fromJson(json, t);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "ZoomTest", 0, 0, opts);
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences sp = pm.getOverlayingSharedPreferences(ctx);
        assertTrue(sp.getBoolean("checkbox_show_overlay_zoom_toggle_button", false));
        assertTrue(sp.getBoolean("checkbox_remember_zoom_pan", false));
        assertEquals(1.5f, sp.getFloat("number_zoom_scale", -1f), 0.0001f);
        assertEquals(0.25f, sp.getFloat("number_pan_offset_x", -1f), 0.0001f);
        assertEquals(0.25f, sp.getFloat("number_pan_offset_y", -1f), 0.0001f);

        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertTrue(cfg.showOverlayZoomToggleButton);
        assertTrue(cfg.rememberZoomPan);
        assertEquals(1.5f, cfg.zoomScale, 0.0001f);
        assertEquals(0.25f, cfg.panOffsetX, 0.0001f);
        assertEquals(0.25f, cfg.panOffsetY, 0.0001f);

        ProfilesManager.instance = null;
        ProfilesManager pm2 = ProfilesManager.getInstance();
        pm2.load(ctx);

        PreferenceConfiguration cfg2 = PreferenceConfiguration.readPreferences(ctx);
        assertTrue(cfg2.showOverlayZoomToggleButton);
        assertTrue(cfg2.rememberZoomPan);
        assertEquals(1.5f, cfg2.zoomScale, 0.0001f);
        assertEquals(0.25f, cfg2.panOffsetX, 0.0001f);
        assertEquals(0.25f, cfg2.panOffsetY, 0.0001f);
    }
}