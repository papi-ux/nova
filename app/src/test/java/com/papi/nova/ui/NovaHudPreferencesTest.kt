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

        NovaHudPreferences.writeOpacityPercent(prefs, 10)

        assertEquals(25, prefs.getInt(NovaHudPreferences.KEY_OPACITY, 0))
        assertEquals(25, NovaHudPreferences.readOpacityPercent(prefs))
        assertEquals(0.25f, NovaHudPreferences.opacityScale(10), 0.001f)

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

        NovaHudPreferences.writeOpacityPercent(repository, definition, 50)

        assertEquals(50, mirrorPrefs.getInt(NovaHudPreferences.KEY_OPACITY, 0))
        assertEquals(
            NovaSettingValue.IntValue(50),
            repository.snapshot(definitions)[NovaHudPreferences.KEY_OPACITY]
        )

        NovaHudPreferences.writeOpacityPercent(repository, definition, 10)

        assertEquals(25, mirrorPrefs.getInt(NovaHudPreferences.KEY_OPACITY, 0))
        assertEquals(
            NovaSettingValue.IntValue(25),
            repository.snapshot(definitions)[NovaHudPreferences.KEY_OPACITY]
        )
    }
}
