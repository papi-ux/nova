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
    fun composeSettingsQuickStripAdvertisesHorizontalOverflow() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("NovaSettingsQuickStripEdgeHint"))
        assertTrue(settingsScreen.contains("Brush.horizontalGradient"))
        assertTrue(settingsScreen.contains("horizontalScroll(scrollState)"))
        assertTrue(settingsScreen.contains("fun rowsBottomPaddingDp(): Int = 72"))
        assertTrue(settingsScreen.contains("fun quickPillWidthDp(): Int = 168"))
    }

    @Test
    fun composeSettingsSelectDialogShowsCurrentBadge() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val selectDialog = settingsScreen.substringAfter("private fun NovaSelectDialog(")
            .substringBefore("@Composable\nprivate fun NovaSliderDialog(")

        assertTrue(selectDialog.contains("selectedOption"))
        assertTrue(selectDialog.contains("Current"))
        assertTrue(selectDialog.contains("NovaSettingCurrentBadge"))
        assertFalse(selectDialog.contains("AlertDialog("))
        assertTrue(selectDialog.contains("Dialog("))
        assertTrue(selectDialog.contains("NovaSettingsSelectOptionRow"))
    }

    @Test
    fun composeSettingsHidesBetaToggleAndUsesShortReleaseSubtitle() {
        val streamSettings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val preferences = File("src/main/res/xml/preferences.xml").readText()
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(streamSettings.contains("NovaSettingsFeatureFlags.COMPOSE_SETTINGS_KEY"))
        assertTrue(streamSettings.contains("filterNot { it.key == NovaSettingsFeatureFlags.COMPOSE_SETTINGS_KEY }"))
        assertTrue(strings.contains("%1\$s · Stream · input · Polaris"))
        assertTrue(preferences.contains("android:title=\"Modern Settings\""))
        assertFalse(preferences.contains("android:title=\"New Settings\""))
        assertTrue(settingsScreen.contains("applyThemeSelectionIfNeeded"))
        assertTrue(settingsScreen.contains("NovaThemeManager.setTheme(context, value.value)") && settingsScreen.contains("window.decorView.post") && settingsScreen.contains("activity.recreate()"))
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
    fun composeSettingsBBackHintHasActivityKeyHandler() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val streamSettings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()

        assertTrue(settingsScreen.contains("R.string.nova_controller_hint_b"))
        assertTrue(streamSettings.contains("override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean"))
        assertTrue(streamSettings.contains("if (keyCode == KeyEvent.KEYCODE_BUTTON_B && !legacyMode)"))
        assertTrue(streamSettings.contains("onBackPressed()\n            return true"))
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


    @Test
    fun composeSettingsShowsHudPreviewAndThemePreviewCards() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("NovaHudSettingsPreview"))
        assertTrue(settingsScreen.contains("NovaStreamHudContent("))
        assertTrue(settingsScreen.contains("NovaHudUiState.preview"))
        assertTrue(settingsScreen.contains("category_overlays"))
        assertTrue(settingsScreen.contains("NovaThemePreviewSwatch"))
        assertTrue(settingsScreen.contains("definition.key == \"nova_theme\""))
    }

    @Test
    fun composeSettingsExposesResetStreamUiDefaultsAction() {
        val preferences = File("src/main/res/xml/preferences.xml").readText()
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val viewModel = File("src/main/java/com/papi/nova/preferences/NovaSettingsViewModel.kt").readText()

        assertTrue(preferences.contains("nova_reset_stream_ui"))
        assertTrue(settingsScreen.contains("resetStreamUiDefaults"))
        assertTrue(settingsScreen.contains("definition.key == \"nova_theme\""))
        assertTrue(viewModel.contains("fun resetStreamUiDefaults()"))
        assertTrue(viewModel.contains("nova_polaris_hud"))
        assertTrue(viewModel.contains("nova_polaris_hud_mode"))
        assertTrue(viewModel.contains("nova_polaris_hud_opacity"))
        assertTrue(viewModel.contains("checkbox_enable_perf_overlay"))
        assertTrue(viewModel.contains("checkbox_show_onscreen_controls"))
    }
}
