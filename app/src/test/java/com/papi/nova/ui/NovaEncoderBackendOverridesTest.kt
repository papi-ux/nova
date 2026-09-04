package com.papi.nova.ui

import android.content.Context
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaEncoderBackendOverridesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val preferences
        get() = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
    private val game = PolarisGame(id = "encoder-game", appId = 42, name = "Game")
    private val key = "encoder_backend_override_encoder-game"

    @Before
    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    private fun settings(vararg backends: String) = PolarisClientSettings(
        capabilities = PolarisClientSettings.Capabilities(
            sessionEncoderOverride = true,
            encoders = backends.map { backend ->
                PolarisClientSettings.EncoderOption(value = backend, available = true)
            },
        ),
    )

    @Test
    fun savedChoiceIsCanonicalAndScopedToTheGame() {
        NovaEncoderBackendOverrides.save(context, game, " VULKAN ")

        assertEquals("vulkan", NovaEncoderBackendOverrides.load(context, game))
        assertNull(
            NovaEncoderBackendOverrides.load(
                context,
                PolarisGame(id = "other-game", appId = 43, name = "Other"),
            ),
        )
    }

    @Test
    fun autoRemainsAnExplicitChoiceDistinctFromHostDefault() {
        NovaEncoderBackendOverrides.save(context, game, "auto")

        assertEquals(
            "auto",
            NovaEncoderBackendOverrides.loadAvailable(context, game, settings("auto", "vulkan")),
        )
        NovaEncoderBackendOverrides.clear(context, game)
        assertNull(NovaEncoderBackendOverrides.load(context, game))
    }

    @Test
    fun choiceMissingFromTheCurrentHostCatalogIsRetired() {
        NovaEncoderBackendOverrides.save(context, game, "vulkan")

        assertNull(
            NovaEncoderBackendOverrides.loadAvailable(context, game, settings("auto", "vaapi")),
        )
        assertFalse(preferences.contains(key))
    }

    @Test
    fun disabledSessionOverrideCannotLeaveInvisibleLaunchAuthority() {
        NovaEncoderBackendOverrides.save(context, game, "vulkan")
        val disabled = settings("auto", "vulkan").copy(
            capabilities = settings("auto", "vulkan").capabilities.copy(
                sessionEncoderOverride = false,
            ),
        )

        assertNull(NovaEncoderBackendOverrides.loadAvailable(context, game, disabled))
        assertFalse(preferences.contains(key))
    }

    @Test
    fun malformedPersistedValueIsRemoved() {
        preferences.edit().putString(key, "not/a/backend").commit()

        assertNull(NovaEncoderBackendOverrides.load(context, game))
        assertFalse(preferences.contains(key))
    }
}
