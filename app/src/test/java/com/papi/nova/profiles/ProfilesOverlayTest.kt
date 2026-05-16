package com.papi.nova.profiles

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(
    sdk = [33],
    shadows = [
        com.papi.nova.shadows.ShadowMoonBridge::class,
        com.papi.nova.shadows.ShadowGameManager::class
    ]
)
@RunWith(RobolectricTestRunner::class)
class ProfilesOverlayTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun overlaySharedPreferencesReturnsPatchedValues() {
        val base = PreferenceManager.getDefaultSharedPreferences(context)
        base.edit()
            .putBoolean("checkbox_ultra_low_latency", false)
            .putInt("seekbar_bitrate_kbps", 15000)
            .apply()

        val patch: MutableMap<String, Any> = HashMap()
        patch["checkbox_ultra_low_latency"] = true
        patch["seekbar_bitrate_kbps"] = 30000

        val profile = SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), patch)
        val manager = ProfilesManager.getInstance()
        manager.add(profile)
        manager.setActive(profile.getUuid())

        val overlay: SharedPreferences = manager.getOverlayingSharedPreferences(context)
        assertTrue(overlay.getBoolean("checkbox_ultra_low_latency", false))
        assertEquals(30000, overlay.getInt("seekbar_bitrate_kbps", 0))
    }

    @Test
    fun overlayPersistsAcrossSessions() {
        val patch: MutableMap<String, Any> = HashMap()
        patch["checkbox_ultra_low_latency"] = true

        val profile = SettingsProfile(UUID.randomUUID(), "Persist", System.currentTimeMillis(), System.currentTimeMillis(), patch)
        val manager = ProfilesManager.getInstance()
        manager.add(profile)
        manager.setActive(profile.getUuid())
        manager.save(context)

        ProfilesManager.instance = null
        val fresh = ProfilesManager.getInstance()
        fresh.load(context)

        val overlay = fresh.getOverlayingSharedPreferences(context)
        assertTrue(overlay.getBoolean("checkbox_ultra_low_latency", false))
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteRecursively(child) }
        }
        file.delete()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
