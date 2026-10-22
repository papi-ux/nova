package com.papi.nova.binding.input

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import com.papi.nova.binding.input.driver.AbstractController
import com.papi.nova.binding.input.driver.SteamController
import com.papi.nova.binding.input.driver.SteamControllerOwnership
import com.papi.nova.binding.input.driver.SteamControllerProtocol
import com.papi.nova.binding.input.driver.SteamControllerTransport
import com.papi.nova.binding.input.driver.UsbDriverListener
import com.papi.nova.binding.input.driver.UsbDriverService
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import java.util.concurrent.ScheduledExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class NativeControllerLibraryInputTest {
    private lateinit var activity: Activity
    private lateinit var lifecycle: ServiceController<UsbDriverService>
    private lateinit var service: UsbDriverService
    private lateinit var manager: UsbManager
    private lateinit var binder: UsbDriverService.UsbDriverBinder
    private lateinit var input: NativeControllerLibraryInput
    private val keys = mutableListOf<FakeKeys>()
    private var panel: Any? = null
    private val toPanel = mutableListOf<KeyEvent>()
    private val toScreen = mutableListOf<KeyEvent>()
    private val pads = mutableListOf<SteamController>()

    private class FakeKeys : NativeControllerLibraryInput.Keys {
        val calls = mutableListOf<String>()
        val targets = mutableListOf<Any>()
        val dispatches = mutableListOf<(KeyEvent) -> Unit>()
        override fun update(target: Any, buttonFlags: Int, leftStickX: Float, leftStickY: Float, dispatch: (KeyEvent) -> Unit) {
            calls += "update:$buttonFlags"; targets += target; dispatches += dispatch
        }
        override fun suppress(buttonFlags: Int, leftStickX: Float, leftStickY: Float) { calls += "suppress:$buttonFlags" }
        override fun cancel() { calls += "cancel" }
    }

    /** Wired-controller bytes: an open link that reports whatever the test sends. */
    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        override fun start(observer: SteamControllerTransport.Observer): Boolean { this.observer = observer; observer.onReady(); return true }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) = completion(true)
        override fun writeRumble(report: ByteArray) = Unit
        override fun close() = Unit
        fun state(raw: Int = 0) = observer.onReport(ByteArray(46).also {
            it[0] = 0x42; for (i in 0..3) it[2 + i] = (raw ushr (8 * i)).toByte()
        })
    }

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        PreferenceManager.getDefaultSharedPreferences(activity).edit()
            .putBoolean("checkbox_usb_driver", true).putBoolean("steam_controller_bluetooth", true).commit()
        lifecycle = Robolectric.buildService(UsbDriverService::class.java).create()
        service = lifecycle.get()
        manager = mock(UsbManager::class.java)
        val wired = mock(UsbDevice::class.java)
        `when`(wired.vendorId).thenReturn(0x28de); `when`(wired.productId).thenReturn(0x1302)
        `when`(wired.deviceName).thenReturn("/dev/bus/usb/library-controller")
        `when`(manager.deviceList).thenReturn(hashMapOf("/dev/bus/usb/library-controller" to wired))
        UsbDriverService::class.java.getDeclaredField("usbManager").apply { isAccessible = true }.set(service, manager)
        binder = service.onBind(Intent()) as UsbDriverService.UsbDriverBinder
        input = NativeControllerLibraryInput(activity, panelOwner = { panel },
            dispatchToPanel = { toPanel += it; true }, dispatchToScreen = { toScreen += it; true },
            newKeys = { FakeKeys().also(keys::add) })
    }
    @After fun cleanup() {
        pads.forEach { it.stop() }
        input.pause()
        lifecycle.destroy()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> serviceField(name: String): T =
        UsbDriverService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as T
    private fun connect() = input.connection.onServiceConnected(ComponentName(activity, UsbDriverService::class.java), binder)
    private fun report(id: Int, flags: Int) = input.reportControllerState(id, flags, 0f, 0f, 0f, 0f, 0f, 0f)
    private fun pad(id: Int, listener: UsbDriverListener = input): Pair<SteamController, Link> {
        val link = Link()
        val pad = SteamController(id, listener, SteamControllerProtocol.WIRED_PID, link, "library-test:$id",
            scheduler = mock(ScheduledExecutorService::class.java))
        pads += pad
        assertTrue(pad.start())
        return pad to link
    }

    @Test fun aResumedLibraryOwnsOnlySteamUsbItMayAlreadyUseWithoutBluetooth() {
        input.resume(); connect()
        assertSame(input, serviceField<Any?>("listener"))
        assertTrue(serviceField<Boolean>("started"))
        assertFalse("Bluetooth stays the stream's", serviceField<PreferenceConfiguration>("prefConfig").steamControllerBluetooth)
        assertFalse(serviceField<Boolean>("promptForPermission"))
        assertTrue(serviceField<Boolean>("steamControllersOnly"))
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "requestPermission" })
    }
    @Test fun aConnectionThatLandsAfterThePauseStartsNothing() {
        input.resume(); input.pause(); connect()
        assertFalse(serviceField<Boolean>("started"))
    }
    @Test fun anOldBindingCannotStealTheServiceAfterANewResume() {
        input.resume()
        val old = input.connection
        input.pause(); input.resume()
        val game = mock(UsbDriverListener::class.java)
        binder.startOwned(game, null, PreferenceConfiguration().apply { usbDriver = true })
        old.onServiceConnected(ComponentName(activity, UsbDriverService::class.java), binder)
        assertSame(game, serviceField<Any?>("listener"))
        connect()
        assertSame(input, serviceField<Any?>("listener"))
    }
    @Test fun anOldDisconnectionDoesNotDropTheCurrentOwnersKeysOrDriver() {
        input.resume()
        val old = input.connection
        input.pause(); input.resume(); connect()
        val (_, link) = pad(7); link.state()
        old.onServiceDisconnected(ComponentName(activity, UsbDriverService::class.java))
        link.state(0x8)
        assertNotEquals("cancel", keys.single().calls.last())
        input.pause()
        assertFalse("the current binder must still be stopped on pause", serviceField<Boolean>("started"))
    }
    @Test fun aStalePauseLeavesTheGameThatTookTheDriver() {
        input.resume(); connect()
        val game = mock(UsbDriverListener::class.java)
        binder.startOwned(game, null, PreferenceConfiguration().apply { usbDriver = true })
        input.pause()
        assertTrue(serviceField<Boolean>("started"))
        assertSame(game, serviceField<Any?>("listener"))
    }
    @Test fun aLibraryWithTheUsbDriverOffNeverBindsIt() {
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putBoolean("checkbox_usb_driver", false).commit()
        input.resume()
        assertFalse(shadowOf(activity.application).boundServiceConnections.contains(input.connection))
    }
    @Test fun onlySteamControllerChannelsBecomeLibraryKeys() {
        input.resume()
        // An Xbox or Pro Controller the service handed over anyway is not the library's.
        input.deviceAdded(mock(AbstractController::class.java)); report(3, ControllerPacket.Y_FLAG)
        assertTrue(keys.isEmpty())
        val (_, link) = pad(7); link.state(); link.state(0x8)
        assertEquals(1, keys.size)
        assertEquals(listOf("suppress:0", "update:0", "update:${ControllerPacket.Y_FLAG}"), keys[0].calls)
    }
    @Test fun buttonsHeldWhenTheLibraryTakesAChannelActOnlyAfterRelease() {
        input.resume()
        val (_, link) = pad(7)
        link.state(0x2 or 0x40) // B and Start, as the stream was left
        link.state()
        val held = ControllerPacket.B_FLAG or ControllerPacket.PLAY_FLAG
        assertEquals(listOf("suppress:$held", "update:$held", "update:0"), keys[0].calls)
        input.resetControllerState(7); report(7, ControllerPacket.A_FLAG)
        assertEquals(listOf("cancel", "suppress:${ControllerPacket.A_FLAG}", "update:${ControllerPacket.A_FLAG}"),
            keys[0].calls.drop(3))
    }
    @Test fun keysGoToTheOpenPanelOtherwiseTheLibraryAndEndWhereTheyBegan() {
        input.resume()
        val (_, link) = pad(7); link.state()
        assertSame(activity, keys[0].targets.last())
        panel = Any(); link.state()
        assertSame(panel, keys[0].targets.last())
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y)
        keys[0].dispatches[0](down); keys[0].dispatches[1](down)
        assertEquals(listOf(down), toScreen); assertEquals(listOf(down), toPanel)
    }
    @Test fun pauseCancelsEveryChannelAndLaterCallbacksDoNothing() {
        input.resume()
        val (_, first) = pad(7); val (second, secondLink) = pad(8)
        first.state(); secondLink.state()
        input.pause()
        assertEquals(listOf("cancel", "cancel"), keys.map { it.calls.last() })
        report(7, ControllerPacket.A_FLAG); input.deviceAdded(second)
        assertEquals(2, keys.size)
        assertTrue(keys.all { it.calls.last() == "cancel" })
    }
    @Test fun aRemovedChannelCancelsOnlyItsOwnKeys() {
        input.resume()
        val (firstPad, first) = pad(7); val (_, second) = pad(8)
        first.state(); second.state()
        input.deviceRemoved(firstPad)
        assertEquals("cancel", keys[0].calls.last()); assertNotEquals("cancel", keys[1].calls.last())
    }
    @Test fun theLibrarySettingsKeepOnlyThePlayersUsbChoice() {
        val config = NativeControllerLibraryInput.libraryConfig(PreferenceConfiguration().apply {
            usbDriver = true; bindAllUsb = true; steamControllerBluetooth = true
            steamControllerGameTouchpad = true; steamControllerGripMappings = intArrayOf(1, 2, 4, 8)
        })
        assertTrue(config.usbDriver)
        assertFalse(config.bindAllUsb); assertFalse(config.steamControllerBluetooth); assertFalse(config.steamControllerGameTouchpad)
        assertArrayEquals(IntArray(4), config.steamControllerGripMappings)
    }
    @Test fun onlyAnOwnedControllersKeyboardAndMouseAreFiltered() {
        fun device(vendor: Int, product: Int, sources: Int): InputDevice = mock(InputDevice::class.java).also {
            `when`(it.vendorId).thenReturn(vendor); `when`(it.productId).thenReturn(product); `when`(it.sources).thenReturn(sources)
        }
        fun from(device: InputDevice?): KeyEvent = mock(KeyEvent::class.java).also { `when`(it.device).thenReturn(device) }
        val lizard = from(device(0x28de, 0x1302, InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE))
        assertFalse(NativeControllerLibraryInput.isOwnedLizard(lizard))
        SteamControllerOwnership.acquire("library-filter:0", 0x1302)
        try {
            assertTrue(NativeControllerLibraryInput.isOwnedLizard(lizard))
            assertFalse(NativeControllerLibraryInput.isOwnedLizard(from(device(0x046d, 0xc31c, InputDevice.SOURCE_KEYBOARD))))
            assertFalse(NativeControllerLibraryInput.isOwnedLizard(from(device(0x28de, 0x1302,
                InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_KEYBOARD))))
            assertFalse("a native key has no device", NativeControllerLibraryInput.isOwnedLizard(from(null)))
        } finally {
            SteamControllerOwnership.release("library-filter:0")
        }
    }
    @Test fun anUnfocusedLibraryGetsNoPressButStillHearsACancelledRelease() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).setup()
        controller.windowFocusChanged(false); shadowOf(Looper.getMainLooper()).idle()
        val screen = controller.get()
        assumeFalse(screen.window.decorView.hasWindowFocus())
        val press = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y, 0, 0, -2048, 0, 0, InputDevice.SOURCE_GAMEPAD)
        val cancel = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_Y, 0, 0, -2048, 0, KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_GAMEPAD)
        NativeControllerLibraryInput.dispatchToWindow(screen, press)
        NativeControllerLibraryInput.dispatchToWindow(screen, cancel)
        assertEquals(listOf(KeyEvent.ACTION_UP), screen.keys.map { it.action })
        assertTrue(screen.keys.single().isCanceled)
    }
    /** End to end through ROOT's shared helper: a native Y reaches the library as a gamepad's Y. */
    @Test fun aNativeYPressReachesTheLibraryAsAGamepadY() {
        val library = NativeControllerLibraryInput(activity, panelOwner = { null },
            dispatchToPanel = { toPanel += it; true }, dispatchToScreen = { toScreen += it; true })
        library.resume()
        try {
            val (_, link) = pad(7, library)
            link.state(); link.state(0x8); link.state()
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), toScreen.map { it.action })
            assertTrue(toScreen.all { it.keyCode == KeyEvent.KEYCODE_BUTTON_Y && it.source == InputDevice.SOURCE_GAMEPAD })
            assertFalse(toScreen.last().isCanceled)
            assertTrue(toPanel.isEmpty())
        } finally {
            library.pause()
        }
    }
    @Test fun aStickHeldWhenTheLibraryTakesTheChannelWaitsForNeutral() {
        val library = NativeControllerLibraryInput(activity, panelOwner = { null },
            dispatchToPanel = { true }, dispatchToScreen = { toScreen += it; true })
        library.resume()
        try {
            pad(7, library)
            fun stick(x: Float) = library.reportControllerState(7, 0, x, 0f, 0f, 0f, 0f, 0f)
            stick(1f); stick(1f)
            assertTrue("held input began outside the library", toScreen.isEmpty())
            stick(0f); stick(1f); stick(0f)
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), toScreen.map { it.action })
            assertTrue(toScreen.all { it.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT })
        } finally {
            library.pause()
        }
    }
}

class RecordingActivity : Activity() {
    val keys = mutableListOf<KeyEvent>()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keys += event
        return true
    }
}
