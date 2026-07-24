package com.papi.nova.ui

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.preferences.NOVA_STREAM_UI_DEFAULT_UPDATES
import com.papi.nova.preferences.NOVA_STREAM_UI_RESET_REMOVALS
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsRepository
import com.papi.nova.preferences.NovaSharedPreferencesSettingsStore
import com.papi.nova.preferences.persistNovaStreamUiDefaults
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaMenuPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sharedPreferencesDefaultReadWriteClampsAndScalesThemeAlpha() {
        val prefs = context.getSharedPreferences("nova-menu-preferences-shared", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertEquals(64, NovaMenuPreferences.readOpacityPercent(prefs))
        assertEquals(listOf(0, 25, 64, 90, 100), NovaMenuPreferences.OPACITY_PRESETS)
        assertEquals(0.62f, NovaMenuPreferences.scaleAlpha(0.62f, 100), 0.001f)
        assertEquals(0.31f, NovaMenuPreferences.scaleAlpha(0.62f, 50), 0.001f)
        assertEquals(1f, NovaMenuPreferences.outerSurfaceAlpha(100, usesDarkText = false), 0.001f)
        assertEquals(0.64f, NovaMenuPreferences.outerSurfaceAlpha(64, usesDarkText = false), 0.001f)
        assertEquals(0f, NovaMenuPreferences.outerSurfaceAlpha(0, usesDarkText = false), 0.001f)

        NovaMenuPreferences.writeOpacityPercent(prefs, -10)

        assertEquals(0, prefs.getInt(NovaMenuPreferences.KEY_OPACITY, 25))
        assertEquals(0, NovaMenuPreferences.readOpacityPercent(prefs))
        assertEquals(0.0f, NovaMenuPreferences.opacityScale(-10), 0.001f)

        NovaMenuPreferences.writeOpacityPercent(prefs, 150)

        assertEquals(100, prefs.getInt(NovaMenuPreferences.KEY_OPACITY, 0))
        assertEquals(100, NovaMenuPreferences.readOpacityPercent(prefs))
        assertEquals(1.0f, NovaMenuPreferences.opacityScale(150), 0.001f)
        assertEquals(0.58f, NovaMenuPreferences.readabilityScrimAlpha(0.58f, 100), 0.001f)
        assertEquals(0.56f, NovaMenuPreferences.readabilityScrimAlpha(0.58f, 50), 0.001f)
        assertEquals(NovaMenuPreferences.MIN_READABILITY_SCRIM_ALPHA, NovaMenuPreferences.readabilityScrimAlpha(0.58f, 0), 0.001f)
        assertEquals(147, NovaMenuPreferences.alphaByte(0.58f, 100))
        assertEquals(178, NovaMenuPreferences.alphaByte(0.70f, 100))
        assertEquals(239, NovaMenuPreferences.alphaByte(0.94f, 100))
        assertEquals(0, NovaMenuPreferences.alphaByte(0.94f, 0))
    }

    @Test
    fun livePreviewIsProcessLocalOwnershipSafeAndNeverMutatesTheDurablePreference() {
        val prefs = context.getSharedPreferences("nova-menu-preview", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt(NovaMenuPreferences.KEY_OPACITY, 100).commit()
        val firstOwner = NovaMenuOpacityPreview.newOwner()
        val secondOwner = NovaMenuOpacityPreview.newOwner()

        NovaMenuOpacityPreview.update(firstOwner, 25)

        assertEquals(25, NovaMenuOpacityPreview.opacityPercent.value)
        assertEquals(100, prefs.getInt(NovaMenuPreferences.KEY_OPACITY, 0))

        NovaMenuOpacityPreview.update(secondOwner, 64)
        NovaMenuOpacityPreview.clear(firstOwner)

        assertEquals(64, NovaMenuOpacityPreview.opacityPercent.value)
        assertEquals(100, prefs.getInt(NovaMenuPreferences.KEY_OPACITY, 0))

        NovaMenuOpacityPreview.clear(secondOwner)

        assertNull(NovaMenuOpacityPreview.opacityPercent.value)
        assertEquals(100, prefs.getInt(NovaMenuPreferences.KEY_OPACITY, 0))
    }

    @Test
    fun darkTextContrastFloorsMeetWcagAgainstABlackScene() {
        assertEquals(0.71f, NovaMenuPreferences.MIN_DARK_TEXT_SURFACE_ALPHA, 0.001f)
        assertEquals(0.57f, NovaMenuPreferences.MIN_DARK_TEXT_SCRIM_ALPHA, 0.001f)
        assertEquals(
            NovaMenuPreferences.MIN_DARK_TEXT_SURFACE_ALPHA,
            NovaMenuPreferences.outerSurfaceAlpha(0, usesDarkText = true),
            0.001f
        )
        assertEquals(
            0f,
            NovaMenuPreferences.outerSurfaceAlpha(0, usesDarkText = false),
            0.001f
        )
        assertEquals(1f, NovaMenuPreferences.outerSurfaceAlpha(100, usesDarkText = true), 0.001f)

        val text = Color.rgb(0x1F, 0x2A, 0x35)
        val portableSurface = Color.rgb(0xC4, 0xCD, 0xD8)
        val nativeComposite = ColorUtils.compositeColors(
            ColorUtils.setAlphaComponent(
                portableSurface,
                (NovaMenuPreferences.MIN_DARK_TEXT_SURFACE_ALPHA * 255f).toInt()
            ),
            Color.BLACK
        )
        val dialogBackdrop = ColorUtils.compositeColors(
            ColorUtils.setAlphaComponent(
                Color.WHITE,
                (NovaMenuPreferences.MIN_DARK_TEXT_SCRIM_ALPHA * 255f).toInt()
            ),
            Color.BLACK
        )
        assertTrue(ColorUtils.calculateContrast(text, nativeComposite) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(text, dialogBackdrop) >= 4.5)
        assertEquals(
            NovaMenuPreferences.MIN_DARK_TEXT_SCRIM_ALPHA,
            NovaMenuPreferences.readabilityScrimAlpha(0.58f, 0f, usesDarkText = true),
            0.001f
        )
    }

    @Test
    fun adaptiveBlurMatchesOpacityEndpointsAndStrengthensAsGlassClears() {
        assertEquals(0f, NovaMenuPreferences.blurRadiusDp(100), 0.001f)
        assertEquals(2.4f, NovaMenuPreferences.blurRadiusDp(90), 0.001f)
        assertEquals(8.64f, NovaMenuPreferences.blurRadiusDp(64), 0.001f)
        assertEquals(18f, NovaMenuPreferences.blurRadiusDp(25), 0.001f)
        assertEquals(24f, NovaMenuPreferences.blurRadiusDp(0), 0.001f)
        assertEquals(0f, NovaMenuPreferences.blurRadiusDp(150), 0.001f)
        assertEquals(24f, NovaMenuPreferences.blurRadiusDp(-10), 0.001f)
    }

    @Test
    fun sharedSheetChromeScalesThemeGlassScrimAndBorder() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()

        NovaMenuPreferences.writeOpacityPercent(prefs, 100)
        val fullGlassAlpha = NovaSheetChrome.getSheetGlassAlpha(context)
        val fullSurfaceAlpha = Color.alpha(NovaSheetChrome.createSheetSurfaceColor(context))
        val fullStrokeAlpha = Color.alpha(NovaSheetChrome.getSheetStrokeColor(context))
        assertEquals(1f, fullGlassAlpha, 0.001f)
        assertEquals(255, fullSurfaceAlpha)

        NovaMenuPreferences.writeOpacityPercent(prefs, 50)

        assertEquals(0.5f, NovaSheetChrome.getSheetGlassAlpha(context), 0.001f)
        assertEquals(127, Color.alpha(NovaSheetChrome.createSheetSurfaceColor(context)))
        assertEquals(
            NovaMenuPreferences.readabilityScrimAlpha(NovaSheetChrome.SCRIM_ALPHA, 50),
            NovaSheetChrome.getSheetScrimAlpha(context),
            0.001f
        )
        assertEquals(fullStrokeAlpha * 0.5f, Color.alpha(NovaSheetChrome.getSheetStrokeColor(context)).toFloat(), 1.0f)

        NovaMenuPreferences.writeOpacityPercent(prefs, 0)

        assertEquals(0f, NovaSheetChrome.getSheetGlassAlpha(context), 0.001f)
        assertEquals(0, Color.alpha(NovaSheetChrome.createSheetSurfaceColor(context)))
        assertEquals(NovaMenuPreferences.MIN_READABILITY_SCRIM_ALPHA, NovaSheetChrome.getSheetScrimAlpha(context), 0.001f)
        assertEquals(0, Color.alpha(NovaSheetChrome.getSheetStrokeColor(context)))
    }

    @Test
    fun streamUiResetFailsClosedWhenGivenAvailabilityFilteredDefinitions() {
        val fullDefinitions = NovaSettingDefinitions.load(context)
        val filteredDefinitions = fullDefinitions.copy(
            settings = fullDefinitions.settings.filterNot { it.key == NovaMenuPreferences.KEY_OPACITY }
        )
        val prefs = context.getSharedPreferences("nova-menu-filtered-reset", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = NovaSharedPreferencesSettingsStore(prefs)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { persistNovaStreamUiDefaults(store, filteredDefinitions) }
        }
        assertFalse(prefs.contains(NovaHudPreferences.KEY_OPACITY))
        assertFalse(prefs.contains(NovaMenuPreferences.KEY_OPACITY))
    }

    @Test
    fun durableStreamUiResetWritesEveryDefaultAsOneBatchAndRemovesHudPosition() = runBlocking {
        val mirrorPrefs = context.getSharedPreferences("nova-menu-reset-mirror", Context.MODE_PRIVATE)
        mirrorPrefs.edit()
            .clear()
            .putFloat("nova_polaris_hud_x", 0.33f)
            .putFloat("nova_polaris_hud_y", 0.66f)
            .commit()
        val storeFile = File(context.filesDir, "nova-menu-reset-" + System.nanoTime() + ".preferences_pb")
        val repository = NovaSettingsRepository.createForTest(
            context = context,
            mirrorPrefs = mirrorPrefs,
            storeFile = storeFile
        )
        val definitions = NovaSettingDefinitions.load(context)
        for ((key, expected) in NOVA_STREAM_UI_DEFAULT_UPDATES) {
            val definition = definitions.require(key)
            val stale = when (expected) {
                is NovaSettingValue.BooleanValue -> NovaSettingValue.BooleanValue(!expected.value)
                is NovaSettingValue.IntValue -> NovaSettingValue.IntValue(expected.value + 1)
                is NovaSettingValue.StringValue -> NovaSettingValue.StringValue(expected.value + "_stale")
                is NovaSettingValue.StringSetValue -> NovaSettingValue.StringSetValue(expected.value + "stale")
            }
            repository.set(definition, stale)
        }

        persistNovaStreamUiDefaults(repository, definitions)

        val snapshot = repository.snapshot(definitions)
        for ((key, expected) in NOVA_STREAM_UI_DEFAULT_UPDATES) {
            assertEquals("DataStore reset mismatch for $key", expected, snapshot[key])
            when (expected) {
                is NovaSettingValue.BooleanValue -> assertEquals(expected.value, mirrorPrefs.getBoolean(key, !expected.value))
                is NovaSettingValue.IntValue -> assertEquals(expected.value, mirrorPrefs.getInt(key, Int.MIN_VALUE))
                is NovaSettingValue.StringValue -> assertEquals(expected.value, mirrorPrefs.getString(key, null))
                is NovaSettingValue.StringSetValue -> assertEquals(expected.value, mirrorPrefs.getStringSet(key, null))
            }
        }
        for (key in NOVA_STREAM_UI_RESET_REMOVALS) {
            assertFalse("runtime reset key should be removed: $key", mirrorPrefs.contains(key))
        }
    }

    @Test
    fun repositoryWriteUpdatesDataStoreAndMirrorPrefs() = runBlocking {
        val mirrorPrefs = context.getSharedPreferences("nova-menu-preferences-mirror", Context.MODE_PRIVATE)
        mirrorPrefs.edit().clear().commit()
        val storeFile = File(context.filesDir, "nova-menu-preferences-" + System.nanoTime() + ".preferences_pb")
        val repository = NovaSettingsRepository.createForTest(
            context = context,
            mirrorPrefs = mirrorPrefs,
            storeFile = storeFile
        )
        val definitions = NovaSettingDefinitions.load(context)
        val definition = definitions.require(NovaMenuPreferences.KEY_OPACITY)

        NovaMenuPreferences.writeOpacityPercent(repository, definition, 64)

        assertEquals(64, mirrorPrefs.getInt(NovaMenuPreferences.KEY_OPACITY, 0))
        assertEquals(
            NovaSettingValue.IntValue(64),
            repository.snapshot(definitions)[NovaMenuPreferences.KEY_OPACITY]
        )

        NovaMenuPreferences.writeOpacityPercent(repository, definition, -10)

        assertEquals(0, mirrorPrefs.getInt(NovaMenuPreferences.KEY_OPACITY, 25))
        assertEquals(
            NovaSettingValue.IntValue(0),
            repository.snapshot(definitions)[NovaMenuPreferences.KEY_OPACITY]
        )
    }
}
