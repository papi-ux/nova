package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfilesOverlayTest {
    private Context context;

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Reset singleton and clean files to ensure clean slate
        ProfilesManager.instance = null;
        File profilesDir = new File(context.getFilesDir(), "profiles");
        deleteRecursively(profilesDir);
    }

    @Test
    public void overlaySharedPreferences_returnsPatchedValues() {
        SharedPreferences base = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        base.edit()
            .putBoolean("checkbox_ultra_low_latency", false)
            .putInt("seekbar_bitrate_kbps", 15000)
            .apply();

        Map<String, Object> patch = new HashMap<>();
        patch.put("checkbox_ultra_low_latency", true);
        patch.put("seekbar_bitrate_kbps", 30000);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), patch);
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences overlay = pm.getOverlayingSharedPreferences(context);
        assertTrue(overlay.getBoolean("checkbox_ultra_low_latency", false));
        assertEquals(30000, overlay.getInt("seekbar_bitrate_kbps", 0));
    }

    @Test
    public void overlayPersistsAcrossSessions() {
        Map<String, Object> patch = new HashMap<>();
        patch.put("checkbox_ultra_low_latency", true);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Persist", System.currentTimeMillis(), System.currentTimeMillis(), patch);
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.add(profile);
        pm.setActive(profile.getUuid());
        pm.save(context);

        // Simulate app restart by resetting singleton
        ProfilesManager.instance = null;
        ProfilesManager fresh = ProfilesManager.getInstance();
        fresh.load(context);

        SharedPreferences overlay = fresh.getOverlayingSharedPreferences(context);
        assertTrue(overlay.getBoolean("checkbox_ultra_low_latency", false));
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }
}