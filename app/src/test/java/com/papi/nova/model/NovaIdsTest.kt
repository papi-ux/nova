package com.papi.nova.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaIdsTest {
    @Test
    fun gameIdNormalizesBlankValues() {
        assertEquals(GameId.NONE, GameId(""))
        assertEquals(GameId.NONE, GameId("   "))
        assertEquals("abc", GameId(" abc ").value)
    }

    @Test
    fun computerUuidKeepsStableStringValue() {
        val uuid = ComputerUuid("host-1")

        assertEquals("host-1", uuid.value)
        assertTrue(uuid.toString().contains("host-1"))
    }

    @Test
    fun streamPrimitivesKeepTypedValues() {
        assertEquals(25_000, BitrateKbps(25_000).value)
        assertEquals(59.94f, RefreshRateHz(59.94f).value)
    }
}
