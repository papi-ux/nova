package com.papi.nova.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The name an install pairs under. Nova Debug installed beside the release app paired as the
 * same model, so the host listed two identical RetroidPocket6 devices and the only way to tell
 * them apart was to rename one.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class DeviceUtilsPairingNameTest {
    @Test
    fun theReleaseAppPairsUnderItsModel() {
        assertEquals("RetroidPocket6", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova"))
    }

    @Test
    fun aBuildBesideTheReleaseAppAddsWhatItIs() {
        assertEquals("RetroidPocket6 Debug", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.debug"))
        assertEquals(
            "The existing .pre package is named Beta for players without changing its install identity",
            "RetroidPocket6 Beta", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.pre"),
        )
        assertEquals("RetroidPocket6 Dirty", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.dirty"))
        assertEquals("RetroidPocket6 Benchmark", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.benchmark"))
        assertEquals("RetroidPocket6 Root", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.root"))
        assertEquals("RetroidPocket6 Root Debug", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.root.debug"))
        assertEquals("RetroidPocket6 Root Beta", DeviceUtils.pairingName("RetroidPocket6", "com.papi.nova.root.pre"))
    }

    @Test
    fun anotherApplicationKeepsTheModel() {
        assertEquals("RetroidPocket6", DeviceUtils.pairingName("RetroidPocket6", "com.papi.novax"))
        assertEquals("RetroidPocket6", DeviceUtils.pairingName("RetroidPocket6", "com.example.nova.debug"))
    }
}
