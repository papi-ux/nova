package com.papi.nova.shared.polaris.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals(null, game.displayPlanner)
        assertEquals("direct", game.steamLaunchMode)
    }

    @Test
    fun decodesVersionedArtworkManifestAndSelectsKnownKinds() {
        val game = json.decodeFromString<PolarisGame>(
            """
            {
              "id":"game-artwork",
              "name":"Artwork Game",
              "artwork":{
                "version":1,
                "revision":"rev-42",
                "assets":{
                  "poster":{"url":"/polaris/v1/games/game-artwork/artwork/poster","source":"steamgriddb","mime_type":"image/png","cached":true},
                  "hero":{"url":"/polaris/v1/games/game-artwork/artwork/hero","source":"steam","mime_type":"image/jpeg","cached":false},
                  "logo":{"url":"/polaris/v1/games/game-artwork/artwork/logo","source":"local","mime_type":"image/png","cached":true},
                  "icon":{"url":"/polaris/v1/games/game-artwork/artwork/icon","source":"host","mime_type":"image/webp","cached":true}
                }
              }
            }
            """.trimIndent()
        )

        val artwork = assertNotNull(game.artwork)
        assertEquals(1, artwork.version)
        assertEquals("rev-42", artwork.revision)
        assertEquals("/polaris/v1/games/game-artwork/artwork/poster", game.artworkAsset("poster")?.url)
        assertEquals("steamgriddb", game.posterArtwork?.source)
        assertEquals("image/jpeg", game.heroArtwork?.mimeType)
        assertEquals("local", game.logoArtwork?.source)
        assertEquals("image/webp", game.iconArtwork?.mimeType)
    }

    @Test
    fun artworkManifestDefaultsAndMalformedKindsRemainBackwardCompatible() {
        val game = json.decodeFromString<PolarisGame>(
            """
            {
              "id":"game-defaults",
              "cover_url":"/legacy-cover",
              "artwork":{
                "assets":{
                  "poster":{"url":""},
                  "banner":{"url":"https://provider.invalid/banner.png"}
                },
                "future_field":"ignored"
              }
            }
            """.trimIndent()
        )

        val artwork = assertNotNull(game.artwork)
        assertEquals(1, artwork.version)
        assertEquals("", artwork.revision)
        assertNull(game.artworkAsset("poster"))
        assertNull(game.artworkAsset("banner"))
        assertNull(game.artworkAsset("../../poster"))
        assertEquals("/legacy-cover", game.coverUrl)
    }

    @Test
    fun legacyCoverOnlyPayloadDoesNotRequireArtworkManifest() {
        val game = json.decodeFromString<PolarisGame>(
            """{"id":"game-legacy-art","cover_url":"/polaris/v1/games/game-legacy-art/cover"}"""
        )

        assertNull(game.artwork)
        assertNull(game.posterArtwork)
        assertEquals("/polaris/v1/games/game-legacy-art/cover", game.coverUrl)
    }

    @Test
    fun decodesCompleteArtworkManifestV1Shape() {
        val game = json.decodeFromString<PolarisGame>(
            """
            {
              "id":"game-complete-artwork",
              "artwork":{
                "version":1,
                "revision":"rev-complete",
                "state":"cached",
                "match":{"source":"steamgriddb","provider_game_id":"12345","title":"Portal 2","confidence":0.98,"manual":true},
                "cached_at":1785641400000,
                "assets":{
                  "poster":{"url":"/polaris/v1/games/game-complete-artwork/artwork/poster","source":"steamgriddb","mime_type":"image/jpeg","cached":true},
                  "hero":{"url":"/polaris/v1/games/game-complete-artwork/artwork/hero","source":"steam","mime_type":"image/jpeg","cached":true},
                  "screenshots":[
                    {"url":"/polaris/v1/games/game-complete-artwork/artwork/screenshots/0","source":"steam","mime_type":"image/jpeg","cached":true},
                    {"url":"","source":"steam","mime_type":"image/jpeg","cached":true}
                  ],
                  "trailer":{"url":"/polaris/v1/games/game-complete-artwork/artwork/trailer","source":"steam","mime_type":"video/mp4","cached":true}
                },
                "override":{"active":true,"kinds":["poster","logo"],"logo_transform":{"x":0.42,"y":0.76,"scale":1.15}}
              }
            }
            """.trimIndent()
        )

        val artwork = assertNotNull(game.artwork)
        assertEquals("cached", artwork.state)
        assertEquals(1785641400000, artwork.cachedAt)
        assertEquals("12345", artwork.match?.providerGameId)
        assertEquals(0.98, artwork.match?.confidence)
        assertTrue(artwork.match?.manual == true)
        assertEquals(1, game.screenshotArtwork.size)
        assertEquals("video/mp4", game.trailerArtwork?.mimeType)
        assertEquals(listOf("poster", "logo"), artwork.override?.kinds)
        assertEquals(0.42, artwork.override?.logoTransform?.x)
        assertEquals(0.76, artwork.override?.logoTransform?.y)
        assertEquals(1.15, artwork.override?.logoTransform?.scale)
    }

    @Test
    fun decodesDisplayResolutionPlannerContractWhenAdvertised() {
        val game = json.decodeFromString<PolarisGame>(
            """
            {
              "id":"game-planner",
              "app_id":42,
              "name":"Planner Game",
              "display_planner":{
                "available":true,
                "source_mode":"2560x1600x90",
                "source_aspect_ratio":"8:5",
                "recommended_id":"balanced",
                "recommended_title":"Best for this device",
                "recommended_mode":"1920x1200x90",
                "choices":[
                  {"id":"balanced","title":"Best for this device","target_mode":"1920x1200x90","badge":"Best for this device","reason":"Preserve aspect ratio."},
                  {"id":"sharp","title":"Sharp / Supersampled","target_mode":"3840x2400x90","badge":"1.5x supersample","advanced":true,"safe":true}
                ]
              }
            }
            """.trimIndent()
        )

        val planner = game.displayPlanner!!
        assertTrue(planner.available)
        assertEquals("2560x1600x90", planner.sourceMode)
        assertEquals("balanced", planner.recommendedId)
        assertEquals("Best for this device", planner.recommendedTitle)
        assertEquals("1920x1200x90", planner.recommendedMode)
        assertEquals(listOf("balanced", "sharp"), planner.choices.map { it.id })
        assertTrue(planner.choices.last().advanced)
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
