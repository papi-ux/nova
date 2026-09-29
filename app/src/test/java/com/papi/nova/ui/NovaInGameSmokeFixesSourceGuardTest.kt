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
     * In-game #12: every panel in the stream's own window takes the solid floor, and every other
     * panel keeps its glass. Keyed on the light scrim, the floor also held Play Setup on the game
     * page and the companion display's Command Center; keyed on a flag the Command Center set, it
     * left a notice, Add Keys and the Leave Space confirm as glass over the game (review finding 3).
     * NovaPanelOverStreamComposeTest draws the window's content; this pins that the window draws it.
     */
    @Test
    fun thePanelOverTheStreamDrawsItsFillFromTheStreamSurfaces() {
        val frame = read("ui/panel/NovaPanelFrame.kt")
        assertTrue(frame.contains("if (overStream) surfaces.overStream() else surfaces"))
        assertTrue(!frame.contains("if (scrim == NovaScrim.Stream) surfaces.overStream()"))
        assertTrue(frame.contains("CompositionLocalProvider(LocalNovaLibrarySurfaces provides panelSurfaces)"))
        val window = read("ui/panel/NovaPanelWindow.kt")
        assertTrue("the window draws the content its placement keys", window.contains("NovaSurfacesWindowContent(surfaces"))
        assertTrue(
            "the floor follows the window's placement, not an opening's flag",
            read("ui/panel/NovaSurfaces.kt").contains("overStream = surfaces.placement.overStream") &&
                !read("ui/panel/NovaPanelState.kt").contains("overStream"),
        )
    }

    /**
     * In-game #4, the caption half: the HUD stores its position on every mode change, so asking
     * only whether a position was stored hid "the HUD is under this panel" on the RP6, whose stored
     * position is the corner itself. Game hands the rows where the HUD is, read with the HUD's own
     * key (NovaCommandCenterHudCornerTest), and the rows compare it with what the panel covers.
     */
    @Test
    fun theHudCaptionComparesTheStoredPositionWithTheCorner() {
        val left = read("Game.kt").substringAfter("val novaHudLeftPx:Float").substringBefore("override fun cycleNovaHudFromController()")
        assertTrue("the stored position, or the corner", left.contains("NovaCommandCenterHudCorner.leftPx("))
        assertTrue("read with the HUD's own key", left.contains("prefs.getFloat(com.papi.nova.ui.NovaCommandCenterHudCorner.PREF_HUD_X, Float.NaN)"))
        assertTrue("and handed to the rows", read("ui/NovaQuickMenu.kt").contains("hudLeftPx = game.novaHudLeftPx"))
    }

    /** In-game #14: the action surface takes its focus ring from the one rule, not its label colour. */
    @Test
    fun theActionSurfaceTakesItsRingFromTheOneRule() {
        val surface = read("ui/compose/NovaFocusComponents.kt").substringAfter("fun NovaActionSurface(").substringBefore(".semantics {")
        assertTrue(surface.contains("ring = novaActionRing(fills = fills, destructive = destructive"))
        assertTrue("a filled surface no longer rings in its label colour", !surface.contains("ring = if (fills) onFill else surfaces.focusRing"))
    }

    /** In-game #16: the Command Center's host keeps where its root was left and hands it to every opening. */
    @Test
    fun theCommandCenterRemembersWhereItWasLeft() {
        val menu = read("ui/NovaQuickMenu.kt")
        assertTrue(menu.contains("private val rootPlace = NovaQuickMenuPlace()"))
        assertTrue(menu.contains("NovaQuickMenuContent(state = uiState, callbacks = callbacks, place = rootPlace)"))
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
