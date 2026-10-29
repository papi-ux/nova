package com.papi.nova.binding.input.driver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamControllerBluetoothNameTest {
    @Test fun acceptsTheBondedBluetoothFirmwareName() {
        assertTrue(UsbDriverService.isSteamControllerBluetoothName("Steam Ctrl (BT) FXA99611022C0"))
        assertTrue(UsbDriverService.isSteamControllerBluetoothName("sTeAm CtRl (bt) fixture"))
    }

    @Test fun preservesExistingNameFormsIgnoringCase() {
        for (name in listOf("Steam Controller", "steam controller (BT)", "Valve Steam Controller",
            "SteamCtrl", "steamctrl (BT)", "Valve SteamCtrl")) {
            assertTrue(name, UsbDriverService.isSteamControllerBluetoothName(name))
        }
    }

    @Test fun rejectsMissingAndUnrelatedNames() {
        for (name in listOf(null, "", "NVIDIA SHIELD Remote", "Xbox Wireless Controller", "Steam Link")) {
            assertFalse(name, UsbDriverService.isSteamControllerBluetoothName(name))
        }
    }

    @Test fun theNewAbbreviatedFormMustBeAPrefix() {
        assertFalse(UsbDriverService.isSteamControllerBluetoothName("Unrelated Steam Ctrl (BT) fixture"))
    }
}
