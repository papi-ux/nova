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
    fun composeSettingsModelIsSharedByGlobalAndProfileEditors() {
        val streamSettings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val profileEditor = File("src/main/java/com/papi/nova/EditProfileActivity.kt").readText()

        assertTrue(streamSettings.contains("NovaSettingsScreen"))
        assertTrue(streamSettings.contains("NovaSettingsRepository.create"))
        assertTrue(profileEditor.contains("NovaSettingsScreen"))
        assertTrue(profileEditor.contains("NovaSharedPreferencesSettingsStore"))
    }

    @Test
    fun composeSettingsUsesCompactHeaderAndQuickStrip() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("NovaSettingsCompactHeader"))
        assertTrue(settingsScreen.contains("NovaSettingsQuickStrip"))
        assertTrue(settingsScreen.contains(".height(NovaSettingsMetrics.quickStripHeightDp().dp)"))
        assertTrue(settingsScreen.contains(".heightIn(min = NovaSettingsMetrics.quickStripHeightDp().dp)"))
        assertTrue(settingsScreen.contains("NovaSettingsCardShape = RoundedCornerShape(14.dp)"))
        assertTrue(settingsScreen.contains("NovaSettingsChipShape = RoundedCornerShape(12.dp)"))
        assertFalse(settingsScreen.contains(".height(44.dp)\n            .horizontalScroll"))
        assertFalse(settingsScreen.contains("label = { Text(\"Search settings\") }"))
    }

    @Test
    fun composeSettingsRowsUseDenseScanningLayout() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("NovaSettingValueChip"))
        assertTrue(settingsScreen.contains("widthIn(min = 92.dp, max = 220.dp)"))
        assertTrue(settingsScreen.contains("heightIn(min = NovaSettingsMetrics.valueChipMinHeightDp().dp)"))
        assertTrue(settingsScreen.contains("maxLines = 1"))
        assertFalse(settingsScreen.contains("maxLines = 2"))
    }

    @Test
    fun composeSettingsExposeSearchOverrideAndApplyStateControls() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("NovaSettingApplyBadge"))
        assertTrue(settingsScreen.contains("NovaSettingOverrideBadge"))
        assertTrue(settingsScreen.contains("onResetSetting"))
        assertTrue(settingsScreen.contains("SearchResultSummary"))
        assertTrue(settingsScreen.contains("Clear"))
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
