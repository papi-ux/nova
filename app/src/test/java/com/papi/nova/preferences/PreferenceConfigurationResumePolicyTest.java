package com.papi.nova.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class PreferenceConfigurationResumePolicyTest {
    @Test
    public void readsBackgroundResumePolicyPreferences() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("resume-policy", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("nova_keep_stream_alive", false)
                .putString("nova_disconnect_resume_timeout_seconds", "600")
                .apply();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);

        assertFalse(config.keepStreamAlive);
        assertEquals(600, config.disconnectResumeTimeoutSeconds);
    }

    @Test
    public void defaultsToFiveMinuteResumeWindowWhenUnsetOrInvalid() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("resume-policy-invalid", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("nova_disconnect_resume_timeout_seconds", "not-a-number")
                .apply();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);

        assertTrue(config.keepStreamAlive);
        assertEquals(300, config.disconnectResumeTimeoutSeconds);
    }
}
