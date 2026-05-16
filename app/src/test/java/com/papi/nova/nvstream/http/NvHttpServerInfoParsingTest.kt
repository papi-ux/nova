package com.papi.nova.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NvHttpServerInfoParsingTest {

    @Test
    fun parsesAdvertisedServerMaxLaunchRefreshRate() {
        val serverInfo = "<root status_code=\"200\">" +
            "<hostname>pc</hostname>" +
            "<uniqueid>uuid</uniqueid>" +
            "<state>POLARIS_SERVER_FREE</state>" +
            "<ServerMaxLaunchRefreshRate>120</ServerMaxLaunchRefreshRate>" +
            "</root>"

        assertEquals(120, NvHTTP.parseServerMaxLaunchRefreshRate(serverInfo))
    }

    @Test
    fun missingAdvertisedServerMaxLaunchRefreshRateFallsBackToZero() {
        val serverInfo =
            "<root status_code=\"200\"><hostname>pc</hostname><uniqueid>uuid</uniqueid><state>POLARIS_SERVER_FREE</state></root>"

        assertEquals(0, NvHTTP.parseServerMaxLaunchRefreshRate(serverInfo))
    }

    @Test
    fun malformedAdvertisedServerMaxLaunchRefreshRateFallsBackToZero() {
        val serverInfo = "<root status_code=\"200\"><ServerMaxLaunchRefreshRate>abc</ServerMaxLaunchRefreshRate></root>"

        assertEquals(0, NvHTTP.parseServerMaxLaunchRefreshRate(serverInfo))
    }

    @Test
    fun parsesCurrentGameOwnershipAndSessionToken() {
        val serverInfo = "<root status_code=\"200\">" +
            "<currentgameowned>1</currentgameowned>" +
            "<currentgameowner>Retroid</currentgameowner>" +
            "<currentgameviewercount>2</currentgameviewercount>" +
            "<currentgamesessiontoken>token-123</currentgamesessiontoken>" +
            "</root>"

        assertTrue(NvHTTP.parseCurrentGameOwned(serverInfo)!!)
        assertEquals("Retroid", NvHTTP.parseCurrentGameOwner(serverInfo))
        assertEquals(2, NvHTTP.parseCurrentGameViewerCount(serverInfo))
        assertEquals("token-123", NvHTTP.parseCurrentGameSessionToken(serverInfo))
    }

    @Test
    fun missingCurrentGameOwnershipFallsBackToNull() {
        val serverInfo = "<root status_code=\"200\"></root>"

        assertNull(NvHTTP.parseCurrentGameOwned(serverInfo))
    }

    @Test
    fun parsesCurrentGameOwnershipFalse() {
        val serverInfo = "<root status_code=\"200\"><currentgameowned>0</currentgameowned></root>"

        assertFalse(NvHTTP.parseCurrentGameOwned(serverInfo)!!)
    }
}
