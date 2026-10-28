package com.papi.nova.ui

import com.papi.nova.R
import com.papi.nova.binding.input.driver.UsbDriverService.SteamControllerUsbAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Set Up Steam Controller asks Android for USB access only from its own button, and claims no more than access (#354). */
class NovaSteamControllerSetupTest {
    @Test
    fun onlyAReceiverThatNeedsAccessOffersTheRequest() {
        val requesting = SteamControllerUsbAccessStatus.entries.filter {
            steamControllerSetupUi(it).action == NovaSteamControllerSetupAction.REQUEST_ACCESS
        }
        assertEquals(
            setOf(SteamControllerUsbAccessStatus.NEEDS_PERMISSION, SteamControllerUsbAccessStatus.REQUEST_FAILED),
            requesting.toSet(),
        )
        // Before its first read, and while Android's prompt is up, the button only reads again.
        assertEquals(NovaSteamControllerSetupAction.CHECK_AGAIN, steamControllerSetupUi(null).action)
        assertEquals(
            NovaSteamControllerSetupAction.CHECK_AGAIN,
            steamControllerSetupUi(SteamControllerUsbAccessStatus.REQUESTING_PERMISSION).action,
        )
    }

    @Test
    fun usbOffPointsAtSettingsAndAllowedSaysOnlyAccessAllowed() {
        val off = steamControllerSetupUi(SteamControllerUsbAccessStatus.USB_DISABLED)
        assertEquals(NovaSteamControllerSetupAction.OPEN_SETTINGS, off.action)
        assertEquals(R.string.nova_steam_controller_setup_usb_disabled, off.message)
        val allowed = steamControllerSetupUi(SteamControllerUsbAccessStatus.PERMISSION_GRANTED)
        assertEquals(R.string.nova_steam_controller_setup_granted, allowed.message)
        assertEquals(true, allowed.settled)
        // Nothing short of the receiver's input being Nova's reads as more than access.
        assertFalse(steamControllerSetupUi(SteamControllerUsbAccessStatus.NO_DEVICE).settled)
    }
}
