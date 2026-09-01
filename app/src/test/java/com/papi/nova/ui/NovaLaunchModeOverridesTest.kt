package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaLaunchModeOverridesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
    }
    private val game = PolarisGame(id = "override-game", name = "Control")
    private val key = "launch_mode_override_override-game"

    @Before
    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun loadingLegacyHeadlessDongleOverrideRetiresIt() {
        preferences.edit().putString(key, PolarisClientSettings.MODE_HEADLESS_DONGLE).commit()

        assertNull(NovaLaunchModeOverrides.load(context, game))
        assertFalse(preferences.contains(key))
    }

    @Test
    fun savingHeadlessDongleCannotCreateAPerGameOverride() {
        NovaLaunchModeOverrides.save(context, game, "gamescope_stream")
        assertEquals("gamescope_stream", NovaLaunchModeOverrides.load(context, game))

        NovaLaunchModeOverrides.save(context, game, PolarisClientSettings.MODE_HEADLESS_DONGLE)
        assertNull(NovaLaunchModeOverrides.load(context, game))
        assertFalse(preferences.contains(key))
    }
}
