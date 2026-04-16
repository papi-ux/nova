package com.papi.nova.nvstream.http;

import org.junit.runner.RunWith;
import org.junit.Test;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class NvHttpServerInfoParsingTest {

    @Test
    public void parsesAdvertisedServerMaxLaunchRefreshRate() throws Exception {
        String serverInfo = "<root status_code=\"200\">" +
                "<hostname>pc</hostname>" +
                "<uniqueid>uuid</uniqueid>" +
                "<state>POLARIS_SERVER_FREE</state>" +
                "<ServerMaxLaunchRefreshRate>120</ServerMaxLaunchRefreshRate>" +
                "</root>";

        assertEquals(120, NvHTTP.parseServerMaxLaunchRefreshRate(serverInfo));
    }

    @Test
    public void missingAdvertisedServerMaxLaunchRefreshRateFallsBackToZero() throws Exception {
        String serverInfo = "<root status_code=\"200\"><hostname>pc</hostname><uniqueid>uuid</uniqueid><state>POLARIS_SERVER_FREE</state></root>";

        assertEquals(0, NvHTTP.parseServerMaxLaunchRefreshRate(serverInfo));
    }

    @Test
    public void malformedAdvertisedServerMaxLaunchRefreshRateFallsBackToZero() throws Exception {
        String serverInfo = "<root status_code=\"200\"><ServerMaxLaunchRefreshRate>abc</ServerMaxLaunchRefreshRate></root>";

        assertEquals(0, NvHTTP.parseServerMaxLaunchRefreshRate(serverInfo));
    }

    @Test
    public void parsesCurrentGameOwnershipAndSessionToken() throws Exception {
        String serverInfo = "<root status_code=\"200\">" +
                "<currentgameowned>1</currentgameowned>" +
                "<currentgamesessiontoken>token-123</currentgamesessiontoken>" +
                "</root>";

        assertTrue(NvHTTP.parseCurrentGameOwned(serverInfo));
        assertEquals("token-123", NvHTTP.parseCurrentGameSessionToken(serverInfo));
    }

    @Test
    public void missingCurrentGameOwnershipFallsBackToNull() throws Exception {
        String serverInfo = "<root status_code=\"200\"></root>";

        assertNull(NvHTTP.parseCurrentGameOwned(serverInfo));
    }

    @Test
    public void parsesCurrentGameOwnershipFalse() throws Exception {
        String serverInfo = "<root status_code=\"200\"><currentgameowned>0</currentgameowned></root>";

        assertFalse(NvHTTP.parseCurrentGameOwned(serverInfo));
    }
}
