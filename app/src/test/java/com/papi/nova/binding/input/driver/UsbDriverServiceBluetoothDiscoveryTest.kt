package com.papi.nova.binding.input.driver

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.preference.PreferenceManager
import com.papi.nova.LimeLog
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.After
import org.junit.Assert.assertEquals
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

@Config(sdk = [30, 33], shadows = [ShadowMoonBridge::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class UsbDriverServiceBluetoothDiscoveryTest {
    private lateinit var lifecycle: ServiceController<UsbDriverService>
    private lateinit var service: UsbDriverService
    private lateinit var binder: UsbDriverService.UsbDriverBinder
    private val discoveryLogs = mutableListOf<String>()
    private val logHandler = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.message.startsWith("Steam Controller BLE:")) discoveryLogs += record.message
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Before fun setup() {
        lifecycle = Robolectric.buildService(UsbDriverService::class.java)
        service = lifecycle.get()
        PreferenceManager.getDefaultSharedPreferences(service).edit()
            .putBoolean("checkbox_usb_driver", false).commit()
        shadowOf(service.application).grantPermissions(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(service.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        lifecycle.create()
        binder = service.onBind(Intent()) as UsbDriverService.UsbDriverBinder
        Logger.getLogger(LimeLog::class.java.name).addHandler(logHandler)
    }

    @After fun cleanup() {
        lifecycle.destroy()
        Logger.getLogger(LimeLog::class.java.name).removeHandler(logHandler)
    }

    private fun bonded(name: String, address: String = "00:11:22:33:44:55"): BluetoothDevice =
        mock(BluetoothDevice::class.java).also {
            `when`(it.name).thenReturn(name)
            `when`(it.address).thenReturn(address)
            `when`(it.type).thenReturn(BluetoothDevice.DEVICE_TYPE_LE)
            `when`(it.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        }

    private fun paired(vararg devices: BluetoothDevice) {
        shadowOf(service.getSystemService(BluetoothManager::class.java).adapter).setBondedDevices(devices.toSet())
    }

    private fun start(bluetooth: Boolean = true) {
        binder.startOwned(mock(UsbDriverListener::class.java), null,
            PreferenceConfiguration().apply { usbDriver = false; steamControllerBluetooth = bluetooth })
    }

    @Test fun theBondedFirmwareNameStartsTheNativeBluetoothTransport() {
        val device = bonded("Steam Ctrl (BT) FXA99611022C0")
        paired(device)
        val selected = mutableListOf<BluetoothDevice>()
        mockConstruction(SteamControllerBleTransport::class.java) { _, context ->
            selected += context.arguments()[1] as BluetoothDevice
        }.use { transports ->
            start()
            assertEquals("the opted-in stream must reach the BLE transport", listOf(device), selected)
            assertEquals(1, mockingDetails(transports.constructed().single()).invocations.count { it.method.name == "start" })
            // Exact messages also prove the bonded serial and address are not logged.
            assertEquals(listOf("Steam Controller BLE: 1 bonded candidates", "Steam Controller BLE: connecting"), discoveryLogs)
        }
    }

    @Test fun theOptInStillControlsDiscovery() {
        paired(bonded("Steam Ctrl (BT) fixture"))
        mockConstruction(SteamControllerBleTransport::class.java).use { transports ->
            start(bluetooth = false)
            assertEquals(0, transports.constructed().size)
            assertEquals(listOf("Steam Controller BLE: off"), discoveryLogs)
        }
    }

    @Test fun anUnrelatedBondedRemoteDoesNotStartTheTransport() {
        paired(bonded("NVIDIA SHIELD Remote"))
        mockConstruction(SteamControllerBleTransport::class.java).use { transports ->
            start()
            assertEquals(0, transports.constructed().size)
            assertEquals(0, ShadowToast.shownToastCount())
            assertEquals(listOf("Steam Controller BLE: 0 bonded candidates"), discoveryLogs)
        }
    }

    @Test fun multipleBondedControllersStillRefuseAmbiguousOwnership() {
        paired(bonded("Steam Controller"), bonded("SteamCtrl", "00:11:22:33:44:66"))
        mockConstruction(SteamControllerBleTransport::class.java).use { transports ->
            start()
            assertEquals(0, transports.constructed().size)
            assertEquals(1, ShadowToast.shownToastCount())
            assertEquals(listOf("Steam Controller BLE: 2 bonded candidates",
                "Steam Controller BLE: ambiguous bonded controllers"), discoveryLogs)
        }
    }

    @Test fun aDisabledAdapterExplainsWhyDiscoveryDidNotRun() {
        paired(bonded("Steam Ctrl (BT) fixture"))
        shadowOf(service.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        mockConstruction(SteamControllerBleTransport::class.java).use { transports ->
            start()
            assertEquals(0, transports.constructed().size)
            assertEquals(listOf("Steam Controller BLE: adapter off"), discoveryLogs)
        }
    }
}
