package com.papi.nova.shared.polaris.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolarisGameContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun decodesPolarisGameSnakeCaseContract() {
        val game = json.decodeFromString<PolarisGame>(
            """
            {
              "id": "game-123",
              "app_id": 456,
              "name": "Portal 2",
              "source": "steam",
              "launcher_source": "steam",
              "launcher_detail": "library",
              "platform": "linux",
              "runtime": "proton",
              "platform_label": "Linux",
              "runtime_label": "Proton",
              "steam_appid": "620",
              "category": "fast_action",
              "installed": true,
              "cover_url": "/polaris/v1/games/game-123/cover",
              "genres": ["Action", "Puzzle"],
              "last_launched": 1718187600000,
              "mangohud": true,
              "hdr_supported": true,
              "launch_mode": {
                "preferred_mode": "virtual_display",
                "recommended_mode": "headless",
                "allowed_modes": ["headless", "virtual_display"],
                "mode_reason": "Host default is headless."
              },
              "steam_launch": {
                "available": true,
                "mode": "big-picture",
                "recommended_mode": "direct",
                "allowed_modes": ["direct", "big-picture"],
                "mode_reason": "Steam Input fallback."
              }
            }
            """.trimIndent()
        )

        assertEquals("game-123", game.id)
        assertEquals(456, game.appId)
        assertEquals("Portal 2", game.name)
        assertEquals("steam", game.source)
        assertEquals("steam", game.launcherSource)
        assertEquals("library", game.launcherDetail)
        assertEquals("linux", game.platform)
        assertEquals("proton", game.runtime)
        assertEquals("620", game.steamAppid)
        assertEquals(listOf("Action", "Puzzle"), game.genres)
        assertTrue(game.mangohud)
        assertTrue(game.hdrSupported)
        assertEquals("Action", game.categoryLabel)
        assertTrue(game.supportsSteamLaunchMode)
        val launchMode = assertNotNull(game.launchMode)
        assertEquals("virtual_display", launchMode.preferredMode)
        assertEquals("headless", launchMode.recommendedMode)
        assertEquals(listOf("headless", "virtual_display"), launchMode.allowedModes)
        val steamLaunch = assertNotNull(game.steamLaunch)
        assertTrue(steamLaunch.available)
        assertEquals("big-picture", steamLaunch.mode)
        assertEquals("direct", steamLaunch.recommendedMode)
    }

    @Test
    fun appliesDefaultsWhenLaunchContractsAreMissingForBackwardCompatibility() {
        val game = json.decodeFromString<PolarisGame>("""{"id":"game-legacy","app_id":7,"name":"Legacy Game"}""")
        assertEquals("game-legacy", game.id)
        assertEquals(7, game.appId)
        assertEquals("Legacy Game", game.name)
        assertEquals("other", game.source)
        assertEquals("other", game.launcherSource)
        assertEquals("unknown", game.platform)
        assertTrue(game.installed)
        assertFalse(game.mangohud)
        assertEquals(null, game.launchMode)
        assertEquals(null, game.steamLaunch)
        assertEquals("direct", game.steamLaunchMode)
    }

    @Test
    fun serializesLaunchModeAndSteamLaunchUsingServerSnakeCaseKeys() {
        val encoded = json.encodeToString(
            PolarisGame(
                id = "game-1",
                name = "Steam Game",
                source = "steam",
                steamAppid = "400",
                launchMode = PolarisGame.LaunchModeContract("headless", "virtual_display", listOf("headless", "virtual_display"), "Virtual display is available."),
                steamLaunch = PolarisGame.SteamLaunchContract(true, "big-picture", "direct", listOf("direct", "big-picture"), "Big Picture is controller-friendly.")
            )
        )
        assertTrue(encoded.contains("\"launch_mode\""))
        assertTrue(encoded.contains("\"preferred_mode\":\"headless\""))
        assertTrue(encoded.contains("\"steam_launch\""))
        assertTrue(encoded.contains("\"recommended_mode\":\"direct\""))
    }

    @Test
    fun normalizesBackwardCompatibleLaunchModeAliases() {
        assertEquals("virtual_display", PolarisGame.normalizeLaunchMode("host_virtual_display"))
        assertEquals("headless", PolarisGame.normalizeLaunchMode("headless_stream"))
        assertEquals("headless", PolarisGame.normalizeLaunchMode("windowed_stream"))
        assertEquals(listOf("headless", "virtual_display"), PolarisGame.normalizeLaunchModes(emptyList(), defaultWhenEmpty = true))
        assertEquals("big-picture", PolarisGame.SteamLaunchContract.normalizeMode("gamepadui"))
    }
}
