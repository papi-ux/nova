package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.robolectric.annotation.Config;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;

import static org.junit.Assert.*;

import com.limelight.TestLogSuppressor;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class OptionDiffUtilTest {
    private Context context;
    private SharedPreferences prefs;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
    }

    @Test
    public void diff_returnsEmptyMap_whenPrefsMatchDefaults() {
        Map<String, Object> diff = OptionDiffUtil.diff(context);
        assertTrue(diff.isEmpty());
    }

    @Test
    public void diff_detectsChangedBooleanPref() {
        prefs.edit().putBoolean("checkbox_ultra_low_latency", true).commit();
        Map<String, Object> diff = OptionDiffUtil.diff(context);
        assertEquals(1, diff.size());
        assertEquals(Boolean.TRUE, diff.get("checkbox_ultra_low_latency"));
    }

    @Test
    public void diff_detectsChangedStringPref() {
        prefs.edit().putString("list_resolution", "1920x1080").commit();
        Map<String, Object> diff = OptionDiffUtil.diff(context);
        assertEquals(1, diff.size());
        assertEquals("1920x1080", diff.get("list_resolution"));
    }

    @Test
    public void diff_detectsChangedIntegerPref() {
        prefs.edit().putInt("seekbar_bitrate_kbps", 50000).commit();
        Map<String, Object> diff = OptionDiffUtil.diff(context);
        assertEquals(1, diff.size());
        assertEquals(50000, diff.get("seekbar_bitrate_kbps"));
    }
}