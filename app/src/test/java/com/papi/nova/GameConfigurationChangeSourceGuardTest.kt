package com.papi.nova

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GameConfigurationChangeSourceGuardTest {

    @Test
    fun configurationChangesBeforeTheControllerExistsDoNotCrashTheStream() {
        // A rotation (portrait to landscape at stream start) delivers
        // onConfigurationChanged before the connection has created the
        // ControllerHandler, so the sensor toggles must tolerate a null handler.
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val onConfigurationChanged = game.section(
            "override fun onConfigurationChanged(newConfig:Configuration)",
            "private fun getPictureInPictureParams("
        )

        assertTrue(
            "PiP entry must disable sensors through a safe call",
            onConfigurationChanged.contains("controllerHandler?.disableSensors()")
        )
        assertTrue(
            "PiP exit must enable sensors through a safe call",
            onConfigurationChanged.contains("controllerHandler?.enableSensors()")
        )
        assertTrue(
            "onConfigurationChanged must never force-unwrap the controller handler",
            !onConfigurationChanged.contains("controllerHandler!!")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
