package com.papi.nova.nvstream.wol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WakeOnLanSenderTest {
    @Test
    public void normalizeMacAddressAcceptsCommonFormats() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:ff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("AA-BB-CC-DD-EE-FF"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabbccddeeff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabb.ccdd.eeff"));
    }

    @Test
    public void normalizeMacAddressRejectsInvalidValues() {
        assertNull(WakeOnLanSender.normalizeMacAddress(null));
        assertNull(WakeOnLanSender.normalizeMacAddress(""));
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee"));
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:xx"));
    }
}
