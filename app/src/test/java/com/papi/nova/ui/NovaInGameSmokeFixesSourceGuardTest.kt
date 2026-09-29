package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards for the in-game smoke of 2026-09-29 whose fixes live in Game. */
class NovaInGameSmokeFixesSourceGuardTest {
    private fun read(path: String) = File("src/main/java/com/papi/nova/$path").readText()

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
