package com.papi.nova.binding.input.driver

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.preference.PreferenceManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class UsbDriverServiceStreamPreferencesTest {
    private lateinit var lifecycle: ServiceController<UsbDriverService>
    private lateinit var service: UsbDriverService
    private lateinit var manager: UsbManager
    private lateinit var binder: UsbDriverService.UsbDriverBinder
    private val devices = HashMap<String, UsbDevice>()
    @Before fun setup() {
        lifecycle = Robolectric.buildService(UsbDriverService::class.java)
        service = lifecycle.get()
        PreferenceManager.getDefaultSharedPreferences(service).edit().putBoolean("checkbox_usb_driver", false).commit()
        lifecycle.create()
        manager = mock(UsbManager::class.java)
        devices["/dev/bus/usb/test-controller"] = usbDevice(0x28de, 0x1302, "/dev/bus/usb/test-controller")
        `when`(manager.deviceList).thenReturn(devices)
        UsbDriverService::class.java.getDeclaredField("usbManager").apply { isAccessible = true }.set(service, manager)
        binder = service.onBind(Intent()) as UsbDriverService.UsbDriverBinder
    }
    @After fun cleanup() { lifecycle.destroy() }

    private fun usbDevice(vendor: Int, product: Int, name: String): UsbDevice = mock(UsbDevice::class.java).also {
        `when`(it.vendorId).thenReturn(vendor); `when`(it.productId).thenReturn(product)
        `when`(it.deviceName).thenReturn(name)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        UsbDriverService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as T
    private fun requests() = mockingDetails(manager).invocations.count { it.method.name == "requestPermission" }
    private fun openChannel(): AbstractController =
        mock(AbstractController::class.java).also { field<ArrayList<AbstractController>>("controllers").add(it) }

    @Test fun launchProfileCanEnableUsbWhenGlobalDefaultIsOff() {
        binder.start(PreferenceConfiguration().apply { usbDriver = true })
        assertEquals(1, mockingDetails(manager).invocations.count { it.method.name == "requestPermission" })
        assertFalse(PreferenceManager.getDefaultSharedPreferences(service).getBoolean("checkbox_usb_driver", true))
    }
    @Test fun launchProfileCanDisableUsbEvenWhenBluetoothNeedsTheService() {
        binder.start(PreferenceConfiguration().apply { usbDriver = false; steamControllerBluetooth = true })
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "requestPermission" })
    }
    @Test fun staleGameOwnerCannotStopTheCurrentGamesDriverChannels() {
        val owner = mock(UsbDriverListener::class.java)
        binder.setListener(owner)
        binder.start(PreferenceConfiguration().apply { usbDriver = true })
        fun requests() = mockingDetails(manager).invocations.count { it.method.name == "requestPermission" }
        assertEquals(1, requests())
        binder.stop(mock(UsbDriverListener::class.java))
        binder.start()
        assertEquals("wrong owner must leave the running service alone", 1, requests())
        binder.stop(owner)
        binder.start()
        assertEquals("the actual owner can stop and restart its service", 2, requests())
    }
    @Test fun aGameTakingTheDriverRetiresTheLibraryChannelsFirstAndUsesItsOwnSettings() {
        val library = mock(UsbDriverListener::class.java)
        val game = mock(UsbDriverListener::class.java)
        val gameState = mock(UsbDriverService.UsbDriverStateListener::class.java)
        binder.startOwned(library, null, PreferenceConfiguration().apply { usbDriver = true },
            promptForPermission = false, steamControllersOnly = true)
        val libraryChannel = openChannel()
        var listenerAtRetire: Any? = null
        doAnswer { listenerAtRetire = field<Any?>("listener"); null }.`when`(libraryChannel).stop()
        binder.startOwned(game, gameState, PreferenceConfiguration().apply {
            usbDriver = true; steamControllerGameTouchpad = true; steamControllerGripMappings = intArrayOf(1, 2, 4, 8)
        })
        verify(libraryChannel).stop()
        assertSame("old channels retire while their owner still hears it", library, listenerAtRetire)
        assertSame(game, field<Any?>("listener"))
        val config = field<PreferenceConfiguration>("prefConfig")
        assertTrue("the game's own touchpad choice", config.steamControllerGameTouchpad)
        assertArrayEquals("the game's own grips", intArrayOf(1, 2, 4, 8), config.steamControllerGripMappings)
        assertEquals("the game asks for the access the library never asked for", 1, requests())
        verify(gameState).onUsbPermissionPromptStarting()
    }
    @Test fun theLibraryNeverAsksForUsbAccessOrClaimsAnotherController() {
        val pro = usbDevice(0x057e, 0x2009, "/dev/bus/usb/test-pro")
        devices[pro.deviceName] = pro
        `when`(manager.hasPermission(pro)).thenReturn(true)
        binder.startOwned(mock(UsbDriverListener::class.java), null,
            PreferenceConfiguration().apply { usbDriver = true; bindAllUsb = true },
            promptForPermission = false, steamControllersOnly = true)
        assertEquals("no prompt for the Steam Controller without access", 0, requests())
        assertEquals("a Pro Controller stays an ordinary Android gamepad", 0,
            mockingDetails(manager).invocations.count { it.method.name == "openDevice" })
        assertEquals(0, ShadowToast.shownToastCount())
    }
    @Test fun theSameOwnerAndSettingsKeepTheirChannelsButNewSettingsReopenThem() {
        val game = mock(UsbDriverListener::class.java)
        binder.startOwned(game, null, PreferenceConfiguration().apply { usbDriver = true })
        val channel = openChannel()
        binder.startOwned(game, null, PreferenceConfiguration().apply { usbDriver = true })
        verify(channel, never()).stop()
        assertEquals(1, requests())
        binder.startOwned(game, null, PreferenceConfiguration().apply { usbDriver = true; steamControllerGameTouchpad = true })
        verify(channel).stop()
        assertEquals("new settings for the same owner reopen its channels", 2, requests())
    }
    @Test fun aStaleLibraryPauseCannotStopTheGameThatReplacedIt() {
        val library = mock(UsbDriverListener::class.java)
        val game = mock(UsbDriverListener::class.java)
        binder.startOwned(library, null, PreferenceConfiguration().apply { usbDriver = true },
            promptForPermission = false, steamControllersOnly = true)
        binder.startOwned(game, null, PreferenceConfiguration().apply { usbDriver = true })
        binder.stop(library)
        assertTrue("the game's driver keeps running", field<Boolean>("started"))
        assertSame(game, field<Any?>("listener"))
        binder.stop(game)
        assertFalse(field<Boolean>("started"))
    }
    @Test fun aGameStartedTheOldWayAfterTheLibraryStillAsksForAccess() {
        val library = mock(UsbDriverListener::class.java)
        binder.startOwned(library, null, PreferenceConfiguration().apply { usbDriver = true },
            promptForPermission = false, steamControllersOnly = true)
        binder.stop(library)
        binder.setListener(mock(UsbDriverListener::class.java))
        binder.start(PreferenceConfiguration().apply { usbDriver = true })
        assertEquals(1, requests())
    }
}
