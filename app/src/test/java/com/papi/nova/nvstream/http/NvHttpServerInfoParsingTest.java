package com.papi.nova.nvstream.http;

import org.junit.runner.RunWith;
import org.junit.Test;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

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
}
