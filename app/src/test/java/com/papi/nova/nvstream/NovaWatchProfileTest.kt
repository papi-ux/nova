package com.papi.nova.nvstream

import java.io.File
import org.junit.Assert.assertEquals
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
            NovaWatchProfile(1280, 800, 90000, tenBit = false),
            NovaWatchProfile.parse("Watch mode must match the active stream profile (1280x800@90 HEVC 8-bit 8000kbps)"),
        )
        val fractional = NovaWatchProfile.parse("Watch mode must match the active stream profile (3840x2160@59.940 AV1 10-bit 80000kbps)")
        assertEquals(NovaWatchProfile(3840, 2160, 59940, tenBit = true), fractional)
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
    fun aRefusedWatcherTakesTheModeAndAsksOnce() {
        val connection = File("src/main/java/com/papi/nova/nvstream/NvConnection.kt").readText()
        val join = connection.substringAfter("private fun resumeOrJoin(").substringBefore("private fun adoptWatchProfile(")
        val adopt = connection.substringAfter("private fun adoptWatchProfile(").substringBefore("protected fun quitAndLaunch(")
        assertTrue(
            "only a watcher, only the 412, and only when the refusal names a mode",
            join.contains("if (!context.watchOnlyRequested || e.getErrorCode() != 412) {") &&
                join.contains("val profile = NovaWatchProfile.parse(e.getErrorMessage()) ?: throw e")
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
}
