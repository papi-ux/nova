package com.papi.nova

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.binding.input.NovaControllerShortcutState
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.NovaSettingApplyTiming
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsAvailability
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.service.NovaQsTile
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@Config(sdk = [30], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class GuideShortcutPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
        ProfilesManager.instance = null
    }

    @Test fun guideShortcutsDefaultOnAndSavedChoiceReloads() {
        assertTrue(PreferenceConfiguration.readPreferences(context, prefs).guideShortcuts)
        prefs.edit().putBoolean("checkbox_guide_shortcuts", false).commit()
        assertFalse(PreferenceConfiguration.readPreferences(context, prefs).guideShortcuts)
        prefs.edit().remove("checkbox_guide_shortcuts").commit()
        assertTrue(PreferenceConfiguration.readPreferences(context, prefs).guideShortcuts)
    }

    @Test fun actualLaunchLoadsTheChoiceIntoTheIndependentFallbackPath() {
        prefs.edit().putBoolean("checkbox_guide_shortcuts", false).commit()
        // This real onCreate branch reads the launch preferences and then finishes before any
        // renderer or connection is constructed. Inspect the same fallback dispatch it configured.
        val intent = Intent(context, Game::class.java).setAction(NovaQsTile.NOVA_DISCONNECT_ACTION)
        val game = Robolectric.buildActivity(Game::class.java, intent).create().get()
        assertTrue(game.isFinishing)
        assertFalse(game.prefConfig.guideShortcuts)
        for ((code, down) in listOf(
            KeyEvent.KEYCODE_BUTTON_MODE to true, KeyEvent.KEYCODE_BUTTON_Y to true,
            KeyEvent.KEYCODE_BUTTON_Y to false, KeyEvent.KEYCODE_BUTTON_MODE to false,
        )) {
            assertFalse("disabled Guide shortcuts must not be intercepted by Game's fallback",
                fallback(game, key(code, down), down))
        }
    }

    @Test fun actualLaunchKeepsDefaultFallbackGuideDeferral() {
        val intent = Intent(context, Game::class.java).setAction(NovaQsTile.NOVA_DISCONNECT_ACTION)
        val game = Robolectric.buildActivity(Game::class.java, intent).create().get()
        assertTrue(game.prefConfig.guideShortcuts)
        assertTrue(fallback(game, key(KeyEvent.KEYCODE_BUTTON_MODE, true), true))
        assertFalse(fallback(game, key(KeyEvent.KEYCODE_BUTTON_MODE, false), false))
    }

    @Test fun bothSettingsSurfacesExposeTheDefaultAndHonestApplyTiming() {
        val all = NovaSettingDefinitions.load(context)
        val setting = all.require("checkbox_guide_shortcuts")
        assertEquals(NovaSettingValue.BooleanValue(true), setting.defaultValue)
        assertEquals(NovaSettingApplyTiming.NextStream, setting.applyTiming)
        assertTrue(NovaSettingsAvailability.filter(context, all).settings.any { it.key == setting.key })
        assertTrue(NovaSettingsAvailability.filterForProfileEditor(all).settings.any { it.key == setting.key })
        assertTrue(NovaSettingsAvailability.shouldPersistProfileOverride(setting.key))
    }

    @Test fun aLaunchProfileCanGiveGuideToItsHostWithoutChangingTheGlobalChoice() {
        prefs.edit().putBoolean("checkbox_guide_shortcuts", true).commit()
        val profiles = ProfilesManager.getInstance()
        profiles.load(context)
        val profile = SettingsProfile(UUID.randomUUID(), "Host Guide", 0L, 0L,
            mapOf("checkbox_guide_shortcuts" to false))
        profiles.add(profile)
        val intent = Intent(context, Game::class.java).setAction(NovaQsTile.NOVA_DISCONNECT_ACTION)
            .putExtra(Game.EXTRA_LAUNCH_PROFILE_UUID, profile.getUuid().toString())
        val game = Robolectric.buildActivity(Game::class.java, intent).create().get()
        assertFalse(game.prefConfig.guideShortcuts)
        assertFalse(fallback(game, key(KeyEvent.KEYCODE_BUTTON_MODE, true), true))
        assertTrue(prefs.getBoolean("checkbox_guide_shortcuts", false))
    }

    @Test fun turningGuideShortcutsOffDoesNotBypassSyntheticInputRefusal() {
        val (game, handler) = bareReadyGame(ignoreSynthetic = true)
        assertFalse(game.handleKeyDown(key(KeyEvent.KEYCODE_BUTTON_MODE, true)))
        assertFalse(game.handleKeyUp(key(KeyEvent.KEYCODE_BUTTON_MODE, false)))
        verifyNoInteractions(handler)
    }

    @Test fun allowedSyntheticGuideReachesTheNormalControllerRoute() {
        val (game, handler) = bareReadyGame(ignoreSynthetic = false)
        val down = key(KeyEvent.KEYCODE_BUTTON_MODE, true)
        val up = key(KeyEvent.KEYCODE_BUTTON_MODE, false)
        `when`(handler.handleButtonDown(down)).thenReturn(true)
        `when`(handler.handleButtonUp(up)).thenReturn(true)
        assertTrue(game.handleKeyDown(down))
        assertTrue(game.handleKeyUp(up))
        verify(handler).handleButtonDown(down)
        verify(handler).handleButtonUp(up)
    }

    private fun bareReadyGame(ignoreSynthetic: Boolean): Pair<Game, ControllerHandler> {
        val game = Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig = PreferenceConfiguration().apply {
            guideShortcuts = false
            ignoreSynthEvents = ignoreSynthetic
        }
        // The launch test above proves the real binding. This fixture isolates the subsequent
        // synthetic-input fence without starting a renderer, network or a finishing Activity.
        val shortcuts = field(game, "fallbackNovaShortcutState") as NovaControllerShortcutState
        shortcuts.guideShortcutsEnabled = false
        val handler = mock(ControllerHandler::class.java)
        Game::class.java.getDeclaredField("controllerHandler").apply { isAccessible = true }.set(game, handler)
        Game::class.java.getDeclaredField("conn").apply { isAccessible = true }.set(game, mock(NvConnection::class.java))
        return game to handler
    }

    private fun field(game: Game, name: String) =
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.get(game)

    private fun fallback(game: Game, event: KeyEvent, down: Boolean): Boolean =
        Game::class.java.getDeclaredMethod("handleFallbackNovaShortcut", KeyEvent::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(game, event, down) as Boolean

    private fun key(code: Int, down: Boolean) =
        KeyEvent(1L, 1000L, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0, 0, 0)
}
