package com.papi.nova.binding.input.driver

import android.content.Intent
import android.app.PendingIntent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Looper
import androidx.preference.PreferenceManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.binding.input.driver.UsbDriverService.SteamControllerUsbAccessStatus
import org.junit.After
import org.junit.Assert.*
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

    private fun setupPuck(): UsbDevice {
        val puck = usbDevice(0x28de, 0x1304, "/dev/bus/usb/test-puck")
        val endpoint = mock(UsbEndpoint::class.java)
        `when`(endpoint.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_INT)
        `when`(endpoint.direction).thenReturn(UsbConstants.USB_DIR_IN)
        val channel = mock(UsbInterface::class.java)
        `when`(channel.id).thenReturn(2); `when`(channel.interfaceClass).thenReturn(UsbConstants.USB_CLASS_HID)
        `when`(channel.endpointCount).thenReturn(1); `when`(channel.getEndpoint(0)).thenReturn(endpoint)
        `when`(puck.interfaceCount).thenReturn(1); `when`(puck.getInterface(0)).thenReturn(channel)
        devices.clear(); devices[puck.deviceName] = puck
        return puck
    }

    private fun library(owner: UsbDriverListener, enabled: Boolean = true) =
        binder.startOwned(owner, null, PreferenceConfiguration().apply { usbDriver = enabled },
            promptForPermission = false, steamControllersOnly = true)

    private fun lastPermissionRequest(): PendingIntent =
        mockingDetails(manager).invocations.last { it.method.name == "requestPermission" }.arguments[1] as PendingIntent

    private fun permissionResult(request: PendingIntent, device: UsbDevice, granted: Boolean) {
        request.send(service, 0, Intent().putExtra(UsbManager.EXTRA_DEVICE, device)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun explicitSteamSetupRequestsOnlyItsReceiverOnceAndDenialDoesNotOpenIt() {
        val puck = setupPuck()
        val pro = usbDevice(0x057e, 0x2009, "/dev/bus/usb/other-pad")
        devices[pro.deviceName] = pro
        val owner = mock(UsbDriverListener::class.java)
        library(owner)
        assertEquals(SteamControllerUsbAccessStatus.NEEDS_PERMISSION, binder.steamControllerUsbAccessStatus(owner))
        assertEquals("passive library startup and status must stay silent", 0, requests())
        assertEquals(SteamControllerUsbAccessStatus.REQUESTING_PERMISSION, binder.requestSteamControllerUsbAccess(owner))
        assertEquals(SteamControllerUsbAccessStatus.REQUESTING_PERMISSION, binder.requestSteamControllerUsbAccess(owner))
        assertEquals(1, requests())
        assertSame(puck, mockingDetails(manager).invocations.single { it.method.name == "requestPermission" }.arguments[0])
        permissionResult(lastPermissionRequest(), puck, false)
        assertEquals(SteamControllerUsbAccessStatus.NEEDS_PERMISSION, binder.steamControllerUsbAccessStatus(owner))
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "openDevice" })
        assertFalse("setup must not rewrite the saved USB choice", PreferenceManager.getDefaultSharedPreferences(service)
            .getBoolean("checkbox_usb_driver", true))
    }

    @Test fun setupGrantUsesNormalDeviceHandlingButIsNotNativeInputReadiness() {
        val puck = setupPuck()
        val owner = mock(UsbDriverListener::class.java)
        library(owner)
        binder.requestSteamControllerUsbAccess(owner)
        `when`(manager.hasPermission(puck)).thenReturn(true)
        permissionResult(lastPermissionRequest(), puck, true)
        verify(manager).openDevice(puck) // null open: no transport thread or native report is fabricated
        assertEquals(SteamControllerUsbAccessStatus.PERMISSION_GRANTED, binder.steamControllerUsbAccessStatus(owner))
        assertEquals(0, mockingDetails(owner).invocations.count { it.method.name == "deviceAdded" })
        assertEquals(1, requests())
    }

    @Test fun lateSetupGrantCannotReviveAnOwnerOrCompleteAnotherGamesPermissionPrompt() {
        val puck = setupPuck()
        val owner = mock(UsbDriverListener::class.java)
        library(owner)
        binder.requestSteamControllerUsbAccess(owner)
        val pending = lastPermissionRequest()
        val game = mock(UsbDriverListener::class.java)
        val gameState = mock(UsbDriverService.UsbDriverStateListener::class.java)
        binder.startOwned(game, gameState, PreferenceConfiguration().apply { usbDriver = false })
        `when`(manager.hasPermission(puck)).thenReturn(true)
        permissionResult(pending, puck, true)
        assertSame(game, field<Any?>("listener"))
        assertEquals(SteamControllerUsbAccessStatus.OWNER_INACTIVE, binder.requestSteamControllerUsbAccess(owner))
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "openDevice" })
        verify(gameState, never()).onUsbPermissionPromptCompleted()
        assertEquals(1, requests())
    }

    @Test fun setupGrantForAnOldDeviceCannotOpenAReplacementAtTheSameAddress() {
        val puck = setupPuck()
        val owner = mock(UsbDriverListener::class.java)
        library(owner)
        binder.requestSteamControllerUsbAccess(owner)
        val pending = lastPermissionRequest()
        val replacement = usbDevice(0x28de, 0x1302, puck.deviceName)
        val channel = puck.getInterface(0)
        `when`(replacement.interfaceCount).thenReturn(1)
        `when`(replacement.getInterface(0)).thenReturn(channel)
        `when`(manager.hasPermission(replacement)).thenReturn(true)
        devices[puck.deviceName] = replacement
        permissionResult(pending, puck, true)
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "openDevice" })
        assertEquals(SteamControllerUsbAccessStatus.PERMISSION_GRANTED, binder.steamControllerUsbAccessStatus(owner))
    }

    @Test fun setupRefusesInactiveDisabledMissingAndAmbiguousTargetsWithoutPrompting() {
        val puck = setupPuck()
        val owner = mock(UsbDriverListener::class.java)
        assertEquals(SteamControllerUsbAccessStatus.OWNER_INACTIVE, binder.requestSteamControllerUsbAccess(owner))
        library(owner, enabled = false)
        assertEquals(SteamControllerUsbAccessStatus.USB_DISABLED, binder.requestSteamControllerUsbAccess(owner))
        library(owner)
        devices.clear()
        assertEquals(SteamControllerUsbAccessStatus.NO_DEVICE, binder.requestSteamControllerUsbAccess(owner))
        devices[puck.deviceName] = puck
        val second = usbDevice(0x28de, 0x1304, "/dev/bus/usb/second-puck")
        devices[second.deviceName] = second
        assertEquals(SteamControllerUsbAccessStatus.AMBIGUOUS, binder.requestSteamControllerUsbAccess(owner))
        assertEquals(0, requests())
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "openDevice" })
    }

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
