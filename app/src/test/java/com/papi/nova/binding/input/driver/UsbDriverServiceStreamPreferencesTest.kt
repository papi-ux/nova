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

@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class UsbDriverServiceStreamPreferencesTest {
    private lateinit var lifecycle: ServiceController<UsbDriverService>
    private lateinit var service: UsbDriverService
    private lateinit var manager: UsbManager
    private lateinit var binder: UsbDriverService.UsbDriverBinder
    @Before fun setup() {
        lifecycle = Robolectric.buildService(UsbDriverService::class.java)
        service = lifecycle.get()
        PreferenceManager.getDefaultSharedPreferences(service).edit().putBoolean("checkbox_usb_driver", false).commit()
        lifecycle.create()
        manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x28de); `when`(device.productId).thenReturn(0x1302)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/test-controller")
        val devices = hashMapOf("/dev/bus/usb/test-controller" to device)
        `when`(manager.deviceList).thenReturn(devices)
        UsbDriverService::class.java.getDeclaredField("usbManager").apply { isAccessible = true }.set(service, manager)
        binder = service.onBind(Intent()) as UsbDriverService.UsbDriverBinder
    }
    @After fun cleanup() { lifecycle.destroy() }

    @Test fun launchProfileCanEnableUsbWhenGlobalDefaultIsOff() {
        binder.start(PreferenceConfiguration().apply { usbDriver = true })
        assertEquals(1, mockingDetails(manager).invocations.count { it.method.name == "requestPermission" })
        assertFalse(PreferenceManager.getDefaultSharedPreferences(service).getBoolean("checkbox_usb_driver", true))
    }
    @Test fun launchProfileCanDisableUsbEvenWhenBluetoothNeedsTheService() {
        binder.start(PreferenceConfiguration().apply { usbDriver = false; steamControllerBluetooth = true })
        assertEquals(0, mockingDetails(manager).invocations.count { it.method.name == "requestPermission" })
    }
}
