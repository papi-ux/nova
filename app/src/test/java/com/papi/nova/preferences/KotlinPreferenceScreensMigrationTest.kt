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
        assertTrue(settingsScreen.contains(".heightIn(min = NovaSettingsMetrics.headerMinHeightDp().dp)"))
        // The guarantee here is that settings names its shape once instead of inlining a corner
        // at each call site, not that it is any particular number. Every other corner comes from
        // the shared scale by name.
        assertTrue(settingsScreen.contains("NovaSettingsCardShape = RoundedCornerShape(NovaRadius.row)"))
        assertFalse(Regex("""RoundedCornerShape\(\d""").containsMatchIn(settingsScreen))
        assertFalse(settingsScreen.contains(".horizontalScroll"))
        assertFalse(settingsScreen.contains("label = { Text(\"Search settings\") }"))
    }

    // Was composeSettingsQuickStripAdvertisesHorizontalOverflow: the strip scrolled sideways and
    // a gradient admitted the last pill was cut at the edge. R13 turns that around: the strip
    // wraps onto a second line, so there is no overflow left to advertise.
    @Test
    fun composeSettingsQuickStripWrapsInsteadOfOverflowing() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val strip = settingsScreen.substringAfter("private fun NovaSettingsQuickStrip(")
            .substringBefore("@Composable\nprivate fun NovaSettingPill(")

        assertTrue(strip.contains("FlowRow("))
        assertFalse(settingsScreen.contains("NovaSettingsQuickStripEdgeHint"))
        assertFalse(settingsScreen.contains("Brush.horizontalGradient"))
        assertFalse(settingsScreen.contains("horizontalScroll(scrollState)"))
    }

    // Was composeSettingsSelectDialogShowsCurrentBadge. The select dialog is gone: a Select
    // changes in its row or opens a Choice page, and the current value carries the one mark (R9),
    // a trailing check drawn by the page, never a "Current" badge.
    @Test
    fun composeSettingsSelectsFollowTheOnePresentationRule() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val pages = File("src/main/java/com/papi/nova/preferences/NovaSettingsPages.kt").readText()

        assertTrue(settingsScreen.contains("definition.selectPresentation == NovaSelectPresentation.InPlace"))
        assertTrue(settingsScreen.contains("ordered = definition.isOrderedScale"))
        assertTrue(pages.contains("val NovaSettingDefinition.selectPresentation: NovaSelectPresentation"))
        assertTrue(pages.contains("NovaCommonPage.Choice("))
        assertFalse(settingsScreen.contains("NovaSettingCurrentBadge"))
        assertFalse(settingsScreen.contains("nova_settings_current_badge"))
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

    // Was composeSettingsRowsUseDenseScanningLayout, which pinned a value chip cut to one line.
    // Values now change in the row itself (R1), and R13 forbids the cut: text wraps.
    @Test
    fun composeSettingsRowsChangeValuesInPlaceWithoutCuttingText() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("NovaValueRow("))
        assertTrue(settingsScreen.contains("style = NovaValueStyle.Switch"))
        assertTrue(settingsScreen.contains("NovaStepperRow("))
        assertFalse(settingsScreen.contains("NovaSettingValueChip"))
        assertFalse(settingsScreen.contains("maxLines = 1"))
    }

    @Test
    fun composeSettingsExposeSearchOverrideAndApplyStateControls() {
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()

        assertTrue(settingsScreen.contains("R.string.nova_settings_applies_next_stream"))
        assertTrue(settingsScreen.contains("R.string.nova_settings_profile_override"))
        assertTrue(settingsScreen.contains("onResetSetting"))
        assertTrue(settingsScreen.contains("R.string.nova_settings_search_results"))
        assertTrue(settingsScreen.contains("R.string.nova_settings_search_clear"))
    }

    // Was composeSettingsBBackHintHasActivityKeyHandler. StreamSettings handled B itself on key
    // down; it now opts into the key gate, so B acts on release through the back dispatcher, and
    // the language check moved from an onBackPressed override the gate never calls to a callback.
    @Test
    fun composeSettingsBGoesThroughTheKeyGateAndTheBackDispatcher() {
        val streamSettings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()

        assertTrue(streamSettings.contains("override val novaKeyGate: Boolean = true"))
        assertTrue(streamSettings.contains("onBackPressedDispatcher.addCallback(this, leaveCallback)"))
        assertTrue(streamSettings.contains("override fun handleOnBackPressed() = leaveSettings()"))
        assertTrue(streamSettings.contains("onBack = ::leaveSettings"))
        assertFalse(streamSettings.contains("override fun onKeyDown("))
        assertFalse(streamSettings.contains("override fun onBackPressed()"))
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
        val pages = File("src/main/java/com/papi/nova/preferences/NovaSettingsPages.kt").readText()
        // The private preview moved to the shared ui/compose/NovaThemeSwatch, which the theme
        // Choice page draws beside each theme.
        assertTrue(pages.contains("NovaThemeSwatch(option.value)"))
        assertTrue(pages.contains("leading = if (key == THEME_KEY) themeSwatch else null"))
        // The theme still applies at once when chosen: the check moved from the select dialog's
        // preview flag to the write path, where it always was for applying.
        assertTrue(settingsScreen.contains("definition.key != \"nova_theme\""))
    }

    @Test
    fun composeSettingsExposesResetStreamUiDefaultsAction() {
        val preferences = File("src/main/res/xml/preferences.xml").readText()
        val settingsScreen = File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
        val viewModel = File("src/main/java/com/papi/nova/preferences/NovaSettingsViewModel.kt").readText()
        val repository = File("src/main/java/com/papi/nova/preferences/NovaSettingsRepository.kt").readText()
        val streamSettings = File("src/main/java/com/papi/nova/preferences/StreamSettings.kt").readText()

        assertTrue(preferences.contains("nova_reset_stream_ui"))
        assertTrue(settingsScreen.contains("resetStreamUiDefaults"))
        assertTrue(settingsScreen.contains("definition.key != \"nova_theme\""))
        assertTrue(viewModel.contains("fun resetStreamUiDefaults()"))
        assertTrue(viewModel.contains("nova_polaris_hud"))
        assertTrue(viewModel.contains("nova_polaris_hud_mode"))
        assertTrue(viewModel.contains("NovaHudPreferences.KEY_OPACITY"))
        assertTrue(viewModel.contains("NovaMenuPreferences.KEY_OPACITY"))
        assertTrue(viewModel.contains("checkbox_enable_perf_overlay"))
        assertTrue(viewModel.contains("checkbox_show_onscreen_controls"))

        val resetBranch = settingsScreen
            .substringAfter("if (definition.key == RESET_STREAM_UI_DEFAULTS_KEY)")
            .substringBefore("} else")
        assertTrue("Compose reset should have one ViewModel owner", resetBranch.contains("viewModel.resetStreamUiDefaults()"))
        assertFalse("Compose reset must not also dispatch the Activity action", resetBranch.contains("onAction(definition)"))

        val persistHelper = viewModel
            .substringAfter("internal suspend fun persistNovaStreamUiDefaults(")
            .substringBefore("\n}\n\nclass NovaSettingsViewModel")
        assertTrue("reset defaults should use one store batch", persistHelper.contains("store.updateAtomically("))
        assertFalse("reset defaults must not loop over individual store writes", persistHelper.contains("store.set("))
        assertTrue("repository must implement a dedicated atomic batch", repository.contains("override suspend fun updateAtomically("))
        assertFalse("StreamSettings must not own a second reset coroutine", streamSettings.contains("resetStreamUiRuntimePreferences()"))
    }
}
