package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards for the in-game smoke of 2026-09-29 whose fixes live in Game. */
class NovaInGameSmokeFixesSourceGuardTest {
    private fun read(path: String) = File("src/main/java/com/papi/nova/$path").readText()

    /**
     * In-game #9: "Slow connection to PC / Reduce your bitrate" showed over the game while Live
     * Tuning was lowering the bitrate itself and Doctor said not to lower quality.
     */
    @Test
    fun theLegacyConnectionWarningGivesWayToLiveTuningAndDoctor() {
        val update = read("Game.kt").substringAfter("override fun connectionStatusUpdate(").substringBefore("override fun connectionStarted()")
        val quiet = update.indexOf("NovaLegacyConnectionWarning.suppressed(lastPolarisSessionStatus)")
        val slow = update.indexOf("R.string.slow_connection_msg")
        assertTrue("the host's own reading is asked first, and the warning is only set without it", quiet in 0 until slow)
    }

    /**
     * In-game #19: the stream only laid itself out for immersive mode in onCreate, and the bars
     * were hidden a second after the connection started, so the gesture handle sat over the first
     * frames.
     */
    @Test
    fun theStreamIsImmersiveBeforeItsFirstFrame() {
        val game = read("Game.kt")
        val fullScreen = game.substringAfter("if (prefConfig!!.fullScreen)").substringBefore("setContentView(R.layout.activity_game)")
        assertTrue("the full-screen setup hides the bars at once, before the content is set", fullScreen.contains("hideSystemUi.run()"))
    }
}
