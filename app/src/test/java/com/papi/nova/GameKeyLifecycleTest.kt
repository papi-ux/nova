package com.papi.nova

import android.view.KeyEvent
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The RP6 crashed in Game.handleKeyDown from StreamContainer.onKeyPreIme when a controller key
 * arrived while a stream was starting or after a refused launch. Device 7 does not exist, so the
 * key reads as a game controller, the path that dereferenced the missing controller handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameKeyLifecycleTest {
    @Test fun keyBeforeTheSessionExistsPassesThrough() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        val down = controllerKey(KeyEvent.ACTION_DOWN)
        val up = controllerKey(KeyEvent.ACTION_UP)
        assertFalse(game.handleKeyDown(down))
        assertFalse(game.handleKeyUp(up))
        game.prefConfig = PreferenceConfiguration().apply { ignoreSynthEvents = false }
        assertFalse(game.handleKeyDown(down))
        assertFalse(game.handleKeyUp(up))
        setField(game, "conn", Mockito.mock(NvConnection::class.java))
        assertFalse(game.handleKeyDown(down))
        assertFalse(game.handleKeyUp(up))
    }

    @Test fun readyControllerGetsKeysButAFinishingSessionDoesNot() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig = PreferenceConfiguration().apply { ignoreSynthEvents = false }
        val handler = Mockito.mock(ControllerHandler::class.java)
        setField(game, "controllerHandler", handler)
        setField(game, "conn", Mockito.mock(NvConnection::class.java))
        val down = controllerKey(KeyEvent.ACTION_DOWN)
        Mockito.`when`(handler.handleButtonDown(down)).thenReturn(true)
        assertTrue(game.handleKeyDown(down))
        Mockito.verify(handler).handleButtonDown(down)
        Mockito.clearInvocations(handler)
        game.finish()
        assertFalse(game.handleKeyDown(down))
        assertFalse(game.handleKeyUp(controllerKey(KeyEvent.ACTION_UP)))
        Mockito.verifyNoInteractions(handler)
    }

    private fun controllerKey(action: Int) =
        KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_BUTTON_A, 0, 0, 7, 0)

    private fun setField(game: Game, name: String, value: Any) {
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.set(game, value)
    }
}
