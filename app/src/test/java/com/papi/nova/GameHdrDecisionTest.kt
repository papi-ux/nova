package com.papi.nova

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class GameHdrDecisionTest {

    @Test
    fun explicitHdrOptInRequestsTenBitStreamOnSdrDisplay() {
        assertTrue(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.TIRAMISU, false))
        assertTrue(Game.shouldShowSdr10BitOptInToast(true, false, Build.VERSION_CODES.TIRAMISU, false))
    }

    @Test
    fun hdr10DisplayDoesNotNeedSdrOptInToast() {
        assertTrue(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.TIRAMISU, true))
        assertFalse(Game.shouldShowSdr10BitOptInToast(true, false, Build.VERSION_CODES.TIRAMISU, true))
    }

    @Test
    fun preNougatDevicesDoNotRequestHdrStream() {
        assertFalse(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.M, false))
        assertTrue(Game.shouldShowHdrRequiresAndroidNToast(true, false, Build.VERSION_CODES.M))
    }

    @Test
    fun privateCompositorStreamSuppressesDesktopLockOverlay() {
        assertTrue(Game.shouldShowPolarisLockOverlay(screenLocked = true, cageRunning = false))
        assertFalse(Game.shouldShowPolarisLockOverlay(screenLocked = true, cageRunning = true))
        assertFalse(Game.shouldShowPolarisLockOverlay(screenLocked = false, cageRunning = true))
    }
}
