package com.papi.nova.binding.input

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.papi.nova.nvstream.input.ControllerPacket
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class NativeControllerUiKeysTest {
    @Test fun yUsesTheNormalLibraryShortcutAndMatchedGamepadEvents() {
        val keys = NativeControllerUiKeys()
        val owner = Any()
        val events = mutableListOf<KeyEvent>()
        keys.update(owner, ControllerPacket.Y_FLAG, 0f, 0f, events::add)
        keys.update(owner, ControllerPacket.Y_FLAG, 0f, 0f, events::add)
        keys.update(owner, 0, 0f, 0f, events::add)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.map { it.action })
        assertTrue(events.all { it.keyCode == KeyEvent.KEYCODE_BUTTON_Y && it.source == InputDevice.SOURCE_GAMEPAD })
        assertTrue(events.all { it.device == null && it.repeatCount == 0 && !it.isCanceled })
        assertEquals(events.first().downTime, events.last().downTime)
    }

    @Test fun anOpeningPressIsSuppressedUntilReleasedAndPressedAgain() {
        val keys = NativeControllerUiKeys()
        val owner = Any()
        val events = mutableListOf<KeyEvent>()
        keys.suppress(ControllerPacket.A_FLAG)
        keys.update(owner, ControllerPacket.A_FLAG, 0f, 0f, events::add)
        keys.update(owner, 0, 0f, 0f, events::add)
        assertTrue(events.isEmpty())
        keys.update(owner, ControllerPacket.A_FLAG, 0f, 0f, events::add)
        keys.update(owner, 0, 0f, 0f, events::add)
        assertEquals(2, events.size)
    }

    @Test fun changingWindowCancelsTheOldPressAndDoesNotReleaseItIntoTheNewWindow() {
        val keys = NativeControllerUiKeys()
        val first = Any(); val second = Any()
        val oldEvents = mutableListOf<KeyEvent>(); val newEvents = mutableListOf<KeyEvent>()
        keys.update(first, ControllerPacket.A_FLAG, 0f, 0f, oldEvents::add)
        keys.update(second, ControllerPacket.A_FLAG, 0f, 0f, newEvents::add)
        keys.update(second, 0, 0f, 0f, newEvents::add)
        assertEquals(2, oldEvents.size)
        assertTrue(oldEvents.last().isCanceled)
        assertTrue(newEvents.isEmpty())
        keys.update(second, ControllerPacket.A_FLAG, 0f, 0f, newEvents::add)
        keys.update(second, 0, 0f, 0f, newEvents::add)
        assertEquals(2, newEvents.size)
    }

    @Test fun directionalRepeatIsBoundedAndStopsAtCancellation() {
        val keys = NativeControllerUiKeys()
        val owner = Any(); val events = mutableListOf<KeyEvent>()
        keys.update(owner, ControllerPacket.DOWN_FLAG, 0f, 0f, events::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399))
        assertEquals(1, events.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(181))
        assertEquals(listOf(0, 1, 2, 3), events.map { it.repeatCount })
        keys.cancel()
        val count = events.size
        assertTrue(events.last().isCanceled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(count, events.size)
    }

    @Test fun aReentrantCloseCannotDeliverMoreButtonsToTheDismissedWindow() {
        val keys = NativeControllerUiKeys()
        val events = mutableListOf<KeyEvent>()
        keys.update(Any(), ControllerPacket.A_FLAG or ControllerPacket.Y_FLAG, 0f, 0f) {
            events += it
            if (it.action == KeyEvent.ACTION_DOWN) keys.cancel()
        }
        assertEquals(1, events.count { it.action == KeyEvent.ACTION_DOWN })
        assertEquals(1, events.count { it.action == KeyEvent.ACTION_UP && it.isCanceled })
    }

    @Test fun leftStickNavigationSharesDpadKeysWithoutDuplicatePresses() {
        val keys = NativeControllerUiKeys()
        val owner = Any(); val events = mutableListOf<KeyEvent>()
        keys.update(owner, ControllerPacket.UP_FLAG, 0f, -0.8f, events::add)
        keys.update(owner, 0, 0f, -0.7f, events::add)
        assertEquals(1, events.size)
        keys.update(owner, 0, 0.1f, 0.1f, events::add)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.map { it.action })
        assertTrue(events.all { it.keyCode == KeyEvent.KEYCODE_DPAD_UP })
    }

    @Test fun independentNativeChannelsHaveIndependentSyntheticDeviceIds() {
        val first = NativeControllerUiKeys(); val second = NativeControllerUiKeys()
        val events = mutableListOf<KeyEvent>(); val owner = Any()
        first.update(owner, ControllerPacket.Y_FLAG, 0f, 0f, events::add)
        second.update(owner, ControllerPacket.Y_FLAG, 0f, 0f, events::add)
        assertNotEquals(events[0].deviceId, events[1].deviceId)
        first.cancel(); second.cancel()
    }
}
