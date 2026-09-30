package com.papi.nova

import android.view.InputDevice
import android.view.MotionEvent
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.binding.input.capture.InputCaptureProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameMotionLifecycleTest {
    @Test fun motionBeforePreferencesAndDuringPartialInitializationPassesThrough() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        withMotion { event ->
            assertFalse(game.onGenericMotion(null, event))
            game.prefConfig = PreferenceConfiguration()
            assertFalse(game.onGenericMotion(null, event))
            assertFalse(game.handleMotionEvent(null, null))
        }
    }

    @Test fun readyControllerReceivesMotionButFinishingSessionDoesNot() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig = PreferenceConfiguration().apply { ignoreSynthEvents = false }
        val handler = Mockito.mock(ControllerHandler::class.java)
        setField(game, "controllerHandler", handler)
        setField(game, "conn", Mockito.mock(NvConnection::class.java))
        setField(game, "inputCaptureProvider", Mockito.mock(InputCaptureProvider::class.java))
        // Use the declared type so this test follows the actual stream container contract.
        val containerField = Game::class.java.getDeclaredField("streamContainer").apply { isAccessible = true }
        containerField.set(game, Mockito.mock(containerField.type))
        withMotion { event ->
            Mockito.`when`(handler.handleMotionEvent(event)).thenReturn(true)
            assertTrue(game.onGenericMotion(null, event))
            Mockito.verify(handler).handleMotionEvent(event)
            Mockito.clearInvocations(handler)
            game.finish()
            assertFalse(game.onGenericMotion(null, event))
            Mockito.verifyNoInteractions(handler)
        }
    }

    private fun withMotion(block: (MotionEvent) -> Unit) {
        val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        event.source = InputDevice.SOURCE_JOYSTICK
        try { block(event) } finally { event.recycle() }
    }

    private fun setField(game: Game, name: String, value: Any) {
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.set(game, value)
    }
}
