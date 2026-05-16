package com.papi.nova.preferences

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinPreferenceScreensMigrationTest {
    @Test
    fun preferenceScreensAreKotlinSources() {
        val names = arrayOf(
            "AddComputerManually",
            "GlPreferences",
            "StreamSettings"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/preferences/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/preferences/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun migratedPreferenceEntryPointsRemainJavaCompatible() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(AddComputerManually::class.java))
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(StreamSettings::class.java))
        assertTrue(PreferenceFragmentCompat::class.java.isAssignableFrom(StreamSettings.SettingsFragment::class.java))

        StreamSettings.SettingsFragment::class.java.getConstructor()
        StreamSettings.SettingsFragment::class.java.getConstructor(PreferenceConfiguration::class.java)
        GlPreferences::class.java.getMethod("readPreferences", Context::class.java)
        GlPreferences::class.java.getMethod("writePreferences")
    }

    @Test
    fun glPreferencesKeepPublicFieldContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("GlPreferences", 0).edit().clear().commit()

        val prefs = GlPreferences.readPreferences(context)
        prefs.glRenderer = "ANGLE"
        prefs.savedFingerprint = "fingerprint-1"
        assertTrue(prefs.writePreferences())

        val restored = GlPreferences.readPreferences(context)
        assertEquals("ANGLE", restored.glRenderer)
        assertEquals("fingerprint-1", restored.savedFingerprint)
    }
}
