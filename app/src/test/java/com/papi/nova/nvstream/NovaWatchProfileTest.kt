package com.papi.nova.nvstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watching works whatever this device would have asked for.
 *
 * papi, 2026-09-21: "i would like for the Watch Stream to function properly in general, even if
 * the resolution isnt calibrated". The host hands a watcher the owner's stream as it is and
 * refuses a request for any other mode, so a Retroid at 1920x1080@60 could not watch a Deck at
 * 1280x800@90. The sentences below are the host's own: nvhttp.cpp format_watch_profile writes
 * "{}x{}@{} {} {}-bit {}kbps", with a whole rate as "90" and a fractional one as "59.940".
 */
class NovaWatchProfileTest {

    @Test
    fun theRefusalNamesTheModeToTake() {
        assertEquals(
            NovaWatchProfile(1280, 800, 90000, tenBit = false, codec = "hevc"),
            NovaWatchProfile.parse("Watch mode must match the active stream profile (1280x800@90 HEVC 8-bit 8000kbps)"),
        )
        val fractional = NovaWatchProfile.parse("Watch mode must match the active stream profile (3840x2160@59.940 AV1 10-bit 80000kbps)")
        assertEquals(NovaWatchProfile(3840, 2160, 59940, tenBit = true, codec = "av1"), fractional)
        assertEquals(59.94f, fractional!!.fps, 0.001f)
        assertEquals(60f, NovaWatchProfile.parse("(1920x1080@60 H264 8-bit 20000kbps)")!!.fps, 0f)
    }

    @Test
    fun anythingElseIsNotAMode() {
        assertNull(NovaWatchProfile.parse(null))
        assertNull(NovaWatchProfile.parse(""))
        assertNull(NovaWatchProfile.parse("Watch mode must match the active stream profile."))
        assertNull(NovaWatchProfile.parse("No active owner stream is available to watch"))
        assertNull("a size no stream has", NovaWatchProfile.parse("(8x8@60 H264 8-bit 1kbps)"))
    }

    @Test
    fun aHostsFieldsAreAModeOnlyWhenTheyArePlainlyOne() {
        assertEquals(
            NovaWatchProfile(1280, 800, 90000, tenBit = false, codec = "hevc"),
            NovaWatchProfile.fromFields("1280", "800", "90000", "8", "HEVC"),
        )
        assertEquals(
            NovaWatchProfile(3840, 2160, 59940, tenBit = true, codec = "av1"),
            NovaWatchProfile.fromFields(" 3840 ", "2160", "59940", "10", "av1"),
        )
        assertNull("a codec nobody has heard of is not said", NovaWatchProfile.fromFields("1280", "800", "90000", "8", "vvc")!!.codec)
        assertNull("an older host sends none of them", NovaWatchProfile.fromFields(null, null, null, null, null))
        assertNull(NovaWatchProfile.fromFields("1280", "800", null, "8", "hevc"))
        assertNull(NovaWatchProfile.fromFields("8", "8", "60000", "8", "h264"))
        assertNull("a rate given in whole frames is not this field", NovaWatchProfile.fromFields("1280", "800", "90", "8", "hevc"))
        assertNull(NovaWatchProfile.fromFields("1280", "800", "90000", "12", "hevc"))
        assertNull(NovaWatchProfile.fromFields("1280x", "800", "90000", "8", "hevc"))
        // The sentence says the codec too, in the host's lower case.
        assertEquals("hevc", NovaWatchProfile.parse("(1280x800@90 hevc 8-bit 8000kbps)")!!.codec)
    }

    @Test
    fun aWatcherOffersTheStreamsOwnCodecAtItsOwnDepth() {
        val h264 = 0x0001
        val hevcMain = 0x0100
        val hevcMain10 = 0x0200
        val av1Main8 = 0x1000
        val av1Main10 = 0x2000
        val everything = h264 or hevcMain or hevcMain10 or av1Main8 or av1Main10
        // A device with HDR on watching an 8-bit HEVC stream offers HEVC Main and nothing else.
        assertEquals(hevcMain, novaWatchVideoFormats(everything, "hevc", tenBit = false))
        assertEquals(hevcMain or hevcMain10, novaWatchVideoFormats(everything, "hevc", tenBit = true))
        assertEquals(av1Main8, novaWatchVideoFormats(everything, "av1", tenBit = false))
        assertEquals(h264, novaWatchVideoFormats(everything, "h264", tenBit = false))
        // A host that did not say the codec narrows only the depth.
        assertEquals(h264 or hevcMain or av1Main8, novaWatchVideoFormats(everything, null, tenBit = false))
        val adopt = File("src/main/java/com/papi/nova/nvstream/NvConnection.kt").readText()
            .substringAfter("private fun adoptWatchProfile(").substringBefore("protected fun quitAndLaunch(")
        assertTrue(
            "the formats are narrowed before the early return for a matching mode, which is where a same-size watch went on offering 10-bit",
            adopt.indexOf("adoptWatchVideoFormats(") in 0 until adopt.indexOf("profile.fps == context.negotiatedLaunchRefreshRate")
        )
        // The first adoption narrows the handshake; a later refusal for another codec or depth
        // must still see what the device decodes, or it is told it cannot decode what it can.
        assertTrue(
            "capability is read from the device's formats, captured before the first narrowing",
            adopt.contains("context.deviceVideoFormats\n            ?: streamConfig.getSupportedVideoFormats().also { context.deviceVideoFormats = it }")
        )
        assertTrue(
            "the ten-bit, codec and narrowing checks all use the device's formats",
            adopt.contains("(deviceFormats and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0") &&
                adopt.contains("(deviceFormats and codecMask) == 0") &&
                adopt.contains("novaWatchVideoFormats(deviceFormats, profile.codec, profile.tenBit)") &&
                !adopt.contains("streamConfig.getSupportedVideoFormats() and")
        )
    }

    @Test
    fun aWatcherAsksForTheStreamsModeTheFirstTimeWhenTheHostHasSaidIt() {
        val connection = File("src/main/java/com/papi/nova/nvstream/NvConnection.kt").readText()
        val before = connection.substringAfter("if (context.watchOnlyRequested) {\n                        if (hostSaysWatchable == false) {")
            .substringBefore("if (shouldReplaceCurrentSession(")
        assertTrue(
            "a host that has just said nobody is streaming is not asked for the 409 that says so again",
            before.contains("listener.displayMessage(nobodyIsStreaming)") && before.contains("return false")
        )
        assertTrue(
            "the mode from serverinfo is taken before the request, so there is no refusal to recover from",
            before.contains("if (hostWatchProfile != null && !adoptWatchProfile(context, hostWatchProfile)) {")
        )
        val adopt = connection.substringAfter("private fun adoptWatchProfile(").substringBefore("protected fun quitAndLaunch(")
        assertTrue(
            "a codec this device cannot decode is said in so many words, like HDR",
            adopt.contains("\"av1\" -> MoonBridge.VIDEO_FORMAT_MASK_AV1") &&
                adopt.contains("if (codecMask != 0 && (deviceFormats and codecMask) == 0) {")
        )
        assertTrue(
            "an id is never put on screen as the owner's name",
            connection.contains("context.currentGameOwnerName = NvHTTP.parseCurrentGameOwnerDeviceName(serverInfo)")
        )
    }

    @Test
    fun aRefusedWatcherTakesTheModeAndAsksOnce() {
        val connection = File("src/main/java/com/papi/nova/nvstream/NvConnection.kt").readText()
        val join = connection.substringAfter("private fun resumeOrJoin(").substringBefore("private fun adoptWatchProfile(")
        val adopt = connection.substringAfter("private fun adoptWatchProfile(").substringBefore("protected fun quitAndLaunch(")
        assertTrue(
            "only a watcher, only the 412, and only when the refusal names a mode",
            join.contains("if (!context.watchOnlyRequested || e.getErrorCode() != 412) {") &&
                join.contains("val profile = e.getWatchProfile() ?: NovaWatchProfile.parse(e.getErrorMessage()) ?: throw e")
        )
        assertEquals("one request, and one more after taking the mode", 2, join.split("h.launchApp(context, \"resume\"").size - 1)
        assertTrue(
            "everything set up after the request reads the mode from the context and the configuration, so both take it",
            adopt.contains("context.negotiatedWidth = profile.width") &&
                adopt.contains("context.negotiatedLaunchRefreshRate = profile.fps") &&
                adopt.contains("context.negotiatedHdr = profile.tenBit") &&
                adopt.contains("streamConfig.adoptWatchMode(profile.width, profile.height, profile.fps)")
        )
        assertTrue(
            "an HDR stream on a device that cannot decode it is said in so many words",
            adopt.contains("if (profile.tenBit && !decodesTenBit) {") && adopt.contains("watchRefusalExplained = true")
        )
        assertTrue(
            "a game can be open on the host with nobody streaming it, and the 409 for that says so",
            connection.contains("but nobody is streaming it, so there is nothing to watch.")
        )
    }

    @Test
    fun aLaunchNovaGaveUpOnSaysWhyOnTheSheetThatStays() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        assertTrue(
            "the reason went to a toast and the sheet said \"Failed to start Active Stream (error 0)\"",
            game.contains("lastConnectionMessage = message") &&
                game.contains("if (errorCode == 0 && portFlags == 0 && !givenUpWith.isNullOrBlank())")
        )
    }

    @Test
    fun theConfigurationAndTheScreenTakeTheStreamsShape() {
        // The configuration loads the native library, so it is read here rather than built.
        val config = File("src/main/java/com/papi/nova/nvstream/StreamConfiguration.kt").readText()
            .substringAfter("fun adoptWatchMode(width: Int, height: Int, refreshRate: Float) {").substringBefore("fun getWidth(): Int")
        assertTrue(
            "the connection and the decoder are set up from the configuration after the request, so it takes all of the mode",
            listOf("this.width = width", "this.height = height", "this.refreshRate = refreshRate", "this.launchRefreshRate = refreshRate")
                .all(config::contains)
        )

        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        assertTrue(
            "the picture was fitted to the shape this device asked for; a 16:10 stream in a 16:9 box is stretched by a tenth",
            game.contains("override fun streamModeAdopted(width: Int, height: Int) {") &&
                game.contains("streamContainer?.setDesiredAspectRatio(width.toDouble() / height.toDouble())")
        )
    }

    @Test
    fun aWatcherRefusedATenBitStreamIsToldWhichReasonHolds() {
        // Seen live: a handheld with HDR off in its settings was told "this device cannot decode"
        // a 10-bit stream that its decoder takes without trouble.
        assertTrue(
            "a decoder with no 10-bit profile for the stream's codec cannot decode it",
            novaTenBitWatchRefusal("av1", decodesHevcTenBit = true, decodesAv1TenBit = false, hdrRequestedInSettings = false)
                .contains("which this device cannot decode"),
        )
        assertTrue(
            "a decoder that can, on a device with HDR off, is pointed at the setting",
            novaTenBitWatchRefusal("hevc", decodesHevcTenBit = true, decodesAv1TenBit = false, hdrRequestedInSettings = false)
                .contains("Turn on Request HDR in Settings"),
        )
        val setUpForSdr = novaTenBitWatchRefusal("hevc", decodesHevcTenBit = true, decodesAv1TenBit = false, hdrRequestedInSettings = true)
        assertFalse(
            "with HDR already on, the setting is not the answer, and neither is the decoder",
            setUpForSdr.contains("Settings") || setUpForSdr.contains("cannot decode"),
        )

        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        assertTrue(
            "offering 10-bit to every watcher had moonlight-common-c pick Main10 for an 8-bit stream, " +
                "which the host refuses for its dynamic range: only a session that asked for HDR offers it",
            game.contains("if (willStreamHdr && decoderRenderer!!.isHevcMain10Hdr10Supported)") &&
                game.contains("if (willStreamHdr && decoderRenderer!!.isAv1Main10Supported)"),
        )
        assertTrue(
            "the connection is told what the decoder can do and what was asked for",
            game.contains("newConn.setTenBitAbility(\ndecoderRenderer!!.isHevcMain10Hdr10Supported,\ndecoderRenderer!!.isAv1Main10Supported,\nprefConfig!!.enableHdr\n)"),
        )
    }
}
