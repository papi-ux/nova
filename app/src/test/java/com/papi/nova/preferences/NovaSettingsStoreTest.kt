package com.papi.nova.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sharedPreferencesStoreReadsAndWritesTypedValues() = runBlocking {
        val prefs = context.getSharedPreferences("nova-settings-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = NovaSharedPreferencesSettingsStore(prefs)
        val definitions = NovaSettingDefinitions.load(context)

        store.set(definitions.require("checkbox_enable_hdr"), NovaSettingValue.BooleanValue(true))
        store.set(definitions.require("seekbar_bitrate_kbps"), NovaSettingValue.IntValue(45000))
        store.set(definitions.require("list_resolution"), NovaSettingValue.StringValue("1920x1080"))

        assertTrue(prefs.getBoolean("checkbox_enable_hdr", false))
        assertEquals(45000, prefs.getInt("seekbar_bitrate_kbps", 0))
        assertEquals("1920x1080", prefs.getString("list_resolution", null))
        assertEquals(NovaSettingValue.IntValue(45000), store.snapshot()["seekbar_bitrate_kbps"])
    }

    @Test
    fun sharedPreferencesStoreOverlaysFallbackAndResetsOverrides() = runBlocking {
        val globalPrefs = context.getSharedPreferences("nova-settings-store-global-test", Context.MODE_PRIVATE)
        val profilePrefs = context.getSharedPreferences("nova-settings-store-profile-test", Context.MODE_PRIVATE)
        globalPrefs.edit()
            .clear()
            .putString("list_resolution", "1280x720")
            .putInt("seekbar_bitrate_kbps", 10000)
            .commit()
        profilePrefs.edit()
            .clear()
            .putString("list_resolution", "1920x1080")
            .commit()

        val definitions = NovaSettingDefinitions.load(context)
        val store = NovaSharedPreferencesSettingsStore(profilePrefs, fallbackPrefs = globalPrefs)

        assertEquals(
            NovaSettingValue.StringValue("1920x1080"),
            store.snapshot(definitions)["list_resolution"]
        )
        assertEquals(
            NovaSettingValue.IntValue(10000),
            store.snapshot(definitions)["seekbar_bitrate_kbps"]
        )
        assertEquals(setOf("list_resolution"), store.overrideKeys(definitions))
        assertEquals(setOf("list_resolution"), store.resettableKeys(definitions))

        store.reset(definitions.require("list_resolution"))

        assertFalse(profilePrefs.contains("list_resolution"))
        assertEquals(
            NovaSettingValue.StringValue("1280x720"),
            store.snapshot(definitions)["list_resolution"]
        )
        assertEquals(emptySet<String>(), store.overrideKeys(definitions))
    }

    @Test
    fun dataStoreRepositoryMigratesAndMirrorsLegacySharedPreferences() = runBlocking {
        val mirrorPrefs = context.getSharedPreferences("nova-settings-mirror-test", Context.MODE_PRIVATE)
        mirrorPrefs.edit()
            .clear()
            .putBoolean("checkbox_enable_hdr", true)
            .putString("list_resolution", "1920x1080")
            .putInt("seekbar_bitrate_kbps", 45000)
            .commit()

        val storeFile = File(context.filesDir, "nova-settings-store-test-${System.nanoTime()}.preferences_pb")
        val repository = NovaSettingsRepository.createForTest(
            context = context,
            mirrorPrefs = mirrorPrefs,
            storeFile = storeFile
        )
        val definitions = NovaSettingDefinitions.load(context)

        val migrated = repository.snapshot(definitions)
        assertEquals(NovaSettingValue.BooleanValue(true), migrated["checkbox_enable_hdr"])
        assertEquals(NovaSettingValue.StringValue("1920x1080"), migrated["list_resolution"])
        assertEquals(NovaSettingValue.IntValue(45000), migrated["seekbar_bitrate_kbps"])

        repository.set(definitions.require("checkbox_enable_hdr"), NovaSettingValue.BooleanValue(false))

        assertFalse(mirrorPrefs.getBoolean("checkbox_enable_hdr", true))
        assertEquals(
            NovaSettingValue.BooleanValue(false),
            repository.snapshot(definitions)["checkbox_enable_hdr"]
        )
    }
}
