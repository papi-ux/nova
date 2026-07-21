package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaHudPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sharedPreferencesReadWriteClampsAndScales() {
        val prefs = context.getSharedPreferences("nova-hud-preferences-shared", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertEquals(64, NovaHudPreferences.readOpacityPercent(prefs))
        assertEquals(listOf(0, 25, 64, 90, 100), NovaHudPreferences.OPACITY_PRESETS)

        NovaHudPreferences.writeOpacityPercent(prefs, -10)

        assertEquals(0, prefs.getInt(NovaHudPreferences.KEY_OPACITY, 25))
        assertEquals(0, NovaHudPreferences.readOpacityPercent(prefs))
        assertEquals(0.0f, NovaHudPreferences.opacityScale(-10), 0.001f)
        assertEquals(0.64f, NovaHudPreferences.opacityScale(64), 0.001f)

        NovaHudPreferences.writeOpacityPercent(prefs, 150)

        assertEquals(100, prefs.getInt(NovaHudPreferences.KEY_OPACITY, 0))
        assertEquals(100, NovaHudPreferences.readOpacityPercent(prefs))
        assertEquals(0.9f, NovaHudPreferences.opacityScale(90), 0.001f)
    }

    @Test
    fun repositoryWriteUpdatesDataStoreAndMirrorPrefs() = runBlocking {
        val mirrorPrefs = context.getSharedPreferences("nova-hud-preferences-mirror", Context.MODE_PRIVATE)
        mirrorPrefs.edit().clear().commit()
        val storeFile = File(context.filesDir, "nova-hud-preferences-" + System.nanoTime() + ".preferences_pb")
        val repository = NovaSettingsRepository.createForTest(
            context = context,
            mirrorPrefs = mirrorPrefs,
            storeFile = storeFile
        )
        val definitions = NovaSettingDefinitions.load(context)
        val definition = definitions.require(NovaHudPreferences.KEY_OPACITY)

        NovaHudPreferences.writeOpacityPercent(repository, definition, 64)

        assertEquals(64, mirrorPrefs.getInt(NovaHudPreferences.KEY_OPACITY, 0))
        assertEquals(
            NovaSettingValue.IntValue(64),
            repository.snapshot(definitions)[NovaHudPreferences.KEY_OPACITY]
        )

        NovaHudPreferences.writeOpacityPercent(repository, definition, -10)

        assertEquals(0, mirrorPrefs.getInt(NovaHudPreferences.KEY_OPACITY, 25))
        assertEquals(
            NovaSettingValue.IntValue(0),
            repository.snapshot(definitions)[NovaHudPreferences.KEY_OPACITY]
        )
    }
}
