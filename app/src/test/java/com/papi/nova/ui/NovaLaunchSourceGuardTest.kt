package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class NovaLaunchSourceGuardTest {

    @Test
    fun gameDetailLaunchUsesSelectedMangoHudState() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val primaryLaunch = detail.section("onPrimaryLaunch = {", "},\n                    onLaunchOptions")
        val launchOptionsCall = detail.section("onLaunchOptions = {", "},\n                    onProfilePreference")
        val launchOptions = detail.section(
            "private fun showLaunchOptions(",
            "private fun optionLabel("
        )

        assertTrue(
            "primary Play should pass the selected MangoHUD state into the launch request",
            primaryLaunch.contains("currentGame.copy(mangohud = mangoHudEnabled)")
        )
        assertTrue(
            "Launch Options should carry the selected MangoHUD state into the dialog launch",
            launchOptionsCall.contains("mangoHudEnabled") &&
                launchOptions.contains("mangoHudEnabled: Boolean") &&
                launchOptions.contains("game.copy(mangohud = mangoHudEnabled)")
        )
    }

    @Test
    fun libraryLaunchSynchronizesMangoHudBeforeStartingStream() {
        val activity = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val launchGame = activity.section(
            "private fun launchGame(game: PolarisGame, withVirtualDisplay: Boolean)",
            "private fun resumeActiveSession("
        )

        assertTrue(
            "library launch should explicitly sync MangoHUD state before starting the stream",
            launchGame.contains("apiClient.setMangoHud(game.id, game.mangohud)") &&
                launchGame.indexOf("apiClient.setMangoHud(game.id, game.mangohud)") <
                launchGame.indexOf("ServerHelper.doStart(")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
