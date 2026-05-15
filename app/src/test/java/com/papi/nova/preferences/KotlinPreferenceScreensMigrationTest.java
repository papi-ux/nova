package com.papi.nova.preferences;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinPreferenceScreensMigrationTest {
    @Test
    public void preferenceScreensAreKotlinSources() {
        String[] names = {
                "AddComputerManually",
                "GlPreferences",
                "StreamSettings"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/preferences/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/preferences/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void migratedPreferenceEntryPointsRemainJavaCompatible() throws NoSuchMethodException {
        assertTrue(AppCompatActivity.class.isAssignableFrom(AddComputerManually.class));
        assertTrue(AppCompatActivity.class.isAssignableFrom(StreamSettings.class));
        assertTrue(PreferenceFragmentCompat.class.isAssignableFrom(StreamSettings.SettingsFragment.class));

        StreamSettings.SettingsFragment.class.getConstructor();
        StreamSettings.SettingsFragment.class.getConstructor(PreferenceConfiguration.class);
        GlPreferences.class.getMethod("readPreferences", Context.class);
        GlPreferences.class.getMethod("writePreferences");
    }

    @Test
    public void glPreferencesKeepPublicFieldContract() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("GlPreferences", 0).edit().clear().commit();

        GlPreferences prefs = GlPreferences.readPreferences(context);
        prefs.glRenderer = "ANGLE";
        prefs.savedFingerprint = "fingerprint-1";
        assertTrue(prefs.writePreferences());

        GlPreferences restored = GlPreferences.readPreferences(context);
        assertEquals("ANGLE", restored.glRenderer);
        assertEquals("fingerprint-1", restored.savedFingerprint);
    }
}
