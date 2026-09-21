package com.papi.nova.nvstream.http

import java.io.StringReader
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
    fun parsesPrettyPrintedApolloAppListWithWhitespace() {
        val appList = """
            <root status_code="200">
              <App>
                <AppTitle>Steam Big Picture</AppTitle>
                <UUID>steam-uuid</UUID>
                <ID>123</ID>
                <IDX>1</IDX>
                <IsHdrSupported>1</IsHdrSupported>
              </App>
            </root>
        """.trimIndent()

        val apps = NvHTTP.getAppListByReader(StringReader(appList))

        assertEquals(1, apps.size)
        val app = apps.first()
        assertEquals("Steam Big Picture", app.appName)
        assertEquals("steam-uuid", app.appUUID)
        assertEquals(123, app.appId)
        assertEquals(1, app.appIndex)
        assertTrue(app.isHdrSupported)
    }

    @Test
    fun prettyPrintedEmptyApolloAppListReturnsEmptyList() {
        val appList = """
            <root status_code="200">
            </root>
        """.trimIndent()

        assertTrue(NvHTTP.getAppListByReader(StringReader(appList)).isEmpty())
    }

    @Test
    fun malformedApolloAppListIndexDoesNotAbortParse() {
        val appList = """
            <root status_code="200"><App><AppTitle>Bad IDX</AppTitle><ID>44</ID><IDX>not-a-number</IDX></App></root>
        """.trimIndent()

        val apps = NvHTTP.getAppListByReader(StringReader(appList))

        assertEquals(1, apps.size)
        assertEquals("Bad IDX", apps.first().appName)
        assertEquals(44, apps.first().appId)
        assertEquals(0, apps.first().appIndex)
    }

    @Test
    fun appListEntryWithOnlyIndexIsDroppedAsIncomplete() {
        val appList = """
            <root status_code="200"><App><AppTitle>Missing ID</AppTitle><IDX>7</IDX></App></root>
        """.trimIndent()

        assertTrue(NvHTTP.getAppListByReader(StringReader(appList)).isEmpty())
    }

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

    // A <root> without a status_code used to crash the whole app with an NPE on the
    // connection thread (verifyResponseStatus called .toLong() on the null attribute),
    // which is how a malformed launch response took down the benchmark client. It must
    // now surface as a HostHttpResponseException the connect/launch path already handles.
    @Test(expected = HostHttpResponseException::class)
    fun rootWithoutStatusCodeThrowsHostHttpResponseExceptionNotNpe() {
        NvHTTP.getXmlString("<root><sessionUrl0>x</sessionUrl0></root>", "sessionUrl0", false)
    }

    @Test(expected = HostHttpResponseException::class)
    fun rootWithNonNumericStatusCodeThrowsHostHttpResponseException() {
        NvHTTP.getXmlString("<root status_code=\"not-a-number\"></root>", "sessionUrl0", false)
    }

    @Test
    fun rootWithoutStatusCodeReportsMissingStatusCode() {
        try {
            NvHTTP.getXmlString("<root></root>", "sessionUrl0", false)
            throw AssertionError("expected HostHttpResponseException")
        } catch (e: HostHttpResponseException) {
            assertTrue(e.getErrorMessage().contains("status_code"))
        }
    }

    @Test
    fun rootWithErrorStatusCodeButNoMessageStillThrowsWithoutNpe() {
        try {
            NvHTTP.getXmlString("<root status_code=\"401\"></root>", "sessionUrl0", false)
            throw AssertionError("expected HostHttpResponseException")
        } catch (e: HostHttpResponseException) {
            assertEquals(401, e.getErrorCode())
        }
    }

    @Test
    fun wellFormedSuccessRootParsesTheRequestedField() {
        val body = "<root status_code=\"200\"><sessionUrl0>rtsp://host</sessionUrl0></root>"

        assertEquals("rtsp://host", NvHTTP.getXmlString(body, "sessionUrl0", false))
    }

    @Test
    fun aRefusedLaunchCarriesTheHostCodeAndAction() {
        // polaris: every refused launch says why, as attributes Moonlight ignores.
        val refused = """
            <root status_code="503" status_message="No video encoder could start on this host. Check the host Doctor." error_code="encoder_probe_failed" error_action="Check the host Doctor.">
              <gamesession>0</gamesession>
            </root>
        """.trimIndent()

        try {
            NvHTTP.getXmlString(StringReader(refused), "gamesession", true)
            throw AssertionError("a 503 root must throw")
        } catch (e: HostHttpResponseException) {
            assertEquals(503, e.getErrorCode())
            assertEquals("No video encoder could start on this host. Check the host Doctor.", e.getErrorMessage())
            assertEquals("encoder_probe_failed", e.getHostCode())
            assertEquals("Check the host Doctor.", e.getHostAction())
        }

        // A host that sends only the old attributes still parses, with nothing invented.
        val plain = """<root status_code="503" status_message="Failed to initialize video capture/encoding."><gamesession>0</gamesession></root>"""
        try {
            NvHTTP.getXmlString(StringReader(plain), "gamesession", true)
            throw AssertionError("a 503 root must throw")
        } catch (e: HostHttpResponseException) {
            assertEquals(503, e.getErrorCode())
            assertNull(e.getHostCode())
            assertNull(e.getHostAction())
        }
    }

    /**
     * Polaris 1.4.12 says whether the running game is being streamed, whose it is and at what mode,
     * so a watcher asks for that mode the first time. The owner field that was there before is an id.
     */
    @Test
    fun serverinfoSaysWhatThereIsToWatch() {
        val streaming = """
            <root status_code="200">
              <currentgame>7</currentgame>
              <currentgameowner>028454EC-5E39-B32D-2B87-83132A127653</currentgameowner>
              <currentgameowned>0</currentgameowned>
              <currentgameownername>Steam Deck</currentgameownername>
              <currentgamewatchable>1</currentgamewatchable>
              <currentgamewatchwidth>1280</currentgamewatchwidth>
              <currentgamewatchheight>800</currentgamewatchheight>
              <currentgamewatchfpsx1000>90000</currentgamewatchfpsx1000>
              <currentgamewatchbitdepth>8</currentgamewatchbitdepth>
              <currentgamewatchcodec>hevc</currentgamewatchcodec>
            </root>
        """.trimIndent()
        assertEquals(true, NvHTTP.parseCurrentGameWatchable(streaming))
        assertEquals("Steam Deck", NvHTTP.parseCurrentGameOwnerDeviceName(streaming))
        assertEquals(
            com.papi.nova.nvstream.NovaWatchProfile(1280, 800, 90000, tenBit = false, codec = "hevc"),
            NvHTTP.parseCurrentGameWatchProfile(streaming),
        )

        val leftOpen = """
            <root status_code="200"><currentgame>7</currentgame><currentgameownername>Steam Deck</currentgameownername><currentgamewatchable>0</currentgamewatchable></root>
        """.trimIndent()
        assertEquals(false, NvHTTP.parseCurrentGameWatchable(leftOpen))
        assertNull("no stream, so no mode", NvHTTP.parseCurrentGameWatchProfile(leftOpen))

        val olderHost = """<root status_code="200"><currentgame>7</currentgame><currentgameowned>0</currentgameowned></root>"""
        assertNull("a host that does not say is not read as saying no", NvHTTP.parseCurrentGameWatchable(olderHost))
        assertNull(NvHTTP.parseCurrentGameOwnerDeviceName(olderHost))
        assertNull(NvHTTP.parseCurrentGameWatchProfile(olderHost))
    }

    @Test
    fun aRefusedWatchCarriesTheModeToAskForOnItsRootTag() {
        val refusal = """
            <root status_code="412" status_message="Watch mode must match the active stream profile (1280x800@90 hevc 8-bit 8000kbps)"
                  watch_width="1280" watch_height="800" watch_fps_x1000="90000" watch_bit_depth="8" watch_codec="hevc">
              <resume>0</resume>
            </root>
        """.trimIndent()
        try {
            NvHTTP.getXmlString(refusal, "resume", true)
            org.junit.Assert.fail("a 412 is a refusal")
        } catch (e: HostHttpResponseException) {
            assertEquals(412, e.getErrorCode())
            assertEquals(
                com.papi.nova.nvstream.NovaWatchProfile(1280, 800, 90000, tenBit = false, codec = "hevc"),
                e.getWatchProfile(),
            )
        }

        val released = """<root status_code="412" status_message="Watch mode must match the active stream profile (1280x800@90 hevc 8-bit 8000kbps)"><resume>0</resume></root>"""
        try {
            NvHTTP.getXmlString(released, "resume", true)
            org.junit.Assert.fail("a 412 is a refusal")
        } catch (e: HostHttpResponseException) {
            assertNull("a released host names the mode only in its sentence, which is read as the fallback", e.getWatchProfile())
            assertEquals(
                com.papi.nova.nvstream.NovaWatchProfile(1280, 800, 90000, tenBit = false, codec = "hevc"),
                com.papi.nova.nvstream.NovaWatchProfile.parse(e.getErrorMessage()),
            )
        }
    }
}
