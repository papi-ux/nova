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
}
