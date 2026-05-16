package com.papi.nova.nvstream.wol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanSenderTest {
    @Test
    fun normalizeMacAddressAcceptsCommonFormats() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabbccddeeff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabb.ccdd.eeff"))
    }

    @Test
    fun normalizeMacAddressRejectsInvalidValues() {
        assertNull(WakeOnLanSender.normalizeMacAddress(null))
        assertNull(WakeOnLanSender.normalizeMacAddress(""))
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee"))
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:xx"))
    }
}
