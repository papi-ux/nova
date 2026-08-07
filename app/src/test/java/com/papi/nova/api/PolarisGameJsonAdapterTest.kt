package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.NovaDisplayResolutionPlanner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The display_planner payload below is the exact plan Polaris serves for a
 * 2560x1600x90 host — the same values tests/unit/test_display_planner.cpp pins
 * on the Polaris side. If either side moves, the other moves in the same
 * change, or the Resolution row quietly renders something the host never said.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisGameJsonAdapterTest {

    private fun plannerGameJson(): JSONObject = JSONObject(
        """
        {
          "id": "game-planner",
          "app_id": 42,
          "name": "Planner Game",
          "display_planner": {
            "available": true,
            "source_mode": "2560x1600x90",
            "source_aspect_ratio": "8:5",
            "source_fps": 90,
            "recommended_id": "balanced",
            "recommended_title": "Best for this device",
            "recommended_mode": "1920x1200x90",
            "choices": [
              {"id":"native","title":"Native","intent":"Match the client panel exactly.","target_mode":"2560x1600x90","badge":"2560×1600","reason":"Match the client panel exactly.","advanced":false,"custom":false,"safe":true,"hidden":false,"scale_factor":1,"aspect_ratio":"8:5"},
              {"id":"balanced","title":"Balanced","intent":"Best for this device: preserve aspect ratio while easing encoder and network load.","target_mode":"1920x1200x90","badge":"Best for this device","reason":"Best for this device: preserve aspect ratio while easing encoder and network load.","advanced":false,"custom":false,"safe":true,"hidden":false,"scale_factor":0.75,"aspect_ratio":"8:5"},
              {"id":"sharp","title":"Sharp / Supersampled","intent":"Render above the client panel and downscale for extra clarity when the host has headroom.","target_mode":"3840x2400x90","badge":"1.5x supersample","reason":"Render above the client panel and downscale for extra clarity when the host has headroom.","advanced":true,"custom":false,"safe":true,"hidden":false,"scale_factor":1.5,"aspect_ratio":"8:5"},
              {"id":"performance","title":"Performance","intent":"Favor frame pacing and bandwidth over raw pixel count.","target_mode":"1280x800x90","badge":"0.5x downscale","reason":"Favor frame pacing and bandwidth over raw pixel count.","advanced":false,"custom":false,"safe":true,"hidden":false,"scale_factor":0.5,"aspect_ratio":"8:5"},
              {"id":"custom","title":"Custom","intent":"Advanced manual scale factor using the existing fallback display mode field.","target_mode":"2560x1600x90","badge":"Advanced","reason":"Advanced manual scale factor using the existing fallback display mode field.","advanced":true,"custom":true,"safe":true,"hidden":false,"scale_factor":1,"aspect_ratio":"8:5"}
            ],
            "advanced_scale_factors": [
              {"scale_factor":0.5,"label":"0.5x","target_mode":"1280x800x90","safe":true},
              {"scale_factor":0.75,"label":"0.75x","target_mode":"1920x1200x90","safe":true},
              {"scale_factor":1,"label":"1x","target_mode":"2560x1600x90","safe":true},
              {"scale_factor":1.25,"label":"1.25x","target_mode":"3200x2000x90","safe":true},
              {"scale_factor":1.5,"label":"1.5x","target_mode":"3840x2400x90","safe":true},
              {"scale_factor":2,"label":"2x","target_mode":"5120x3200x90","safe":true}
            ]
          }
        }
        """.trimIndent()
    )

    @Test
    fun decodesTheDisplayPlannerContractPolarisServes() {
        val game = PolarisGameJsonAdapter.fromJson(plannerGameJson())

        val planner = requireNotNull(game.displayPlanner)
        assertTrue(planner.available)
        assertEquals("2560x1600x90", planner.sourceMode)
        assertEquals("8:5", planner.sourceAspectRatio)
        assertEquals(90.0, planner.sourceFps, 0.0)
        assertEquals("balanced", planner.recommendedId)
        assertEquals("Best for this device", planner.recommendedTitle)
        assertEquals("1920x1200x90", planner.recommendedMode)

        assertEquals(listOf("native", "balanced", "sharp", "performance", "custom"), planner.choices.map { it.id })
        val native = planner.choices[0]
        assertEquals("2560×1600", native.badge)
        assertEquals(1.0, native.scaleFactor, 0.0)
        val sharp = planner.choices[2]
        assertTrue(sharp.advanced)
        assertFalse(sharp.custom)
        assertEquals("3840x2400x90", sharp.targetMode)
        val custom = planner.choices[4]
        assertTrue(custom.advanced)
        assertTrue(custom.custom)
        planner.choices.forEach { choice ->
            assertTrue(choice.id, choice.safe)
            assertFalse(choice.id, choice.hidden)
            assertEquals(choice.id, "8:5", choice.aspectRatio)
        }

        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0), planner.advancedScaleFactors.map { it.scaleFactor })
        assertEquals("2x", planner.advancedScaleFactors.last().label)
        assertEquals("5120x3200x90", planner.advancedScaleFactors.last().targetMode)
    }

    @Test
    fun parsedPlannerLightsUpTheResolutionRow() {
        // The exact precondition NovaGameDetailActivity gates the RESOLUTION row on.
        val game = PolarisGameJsonAdapter.fromJson(plannerGameJson())
        val planner = NovaDisplayResolutionPlanner.from(
            contract = game.displayPlanner,
            fallbackMode = "",
            includeAdvanced = true
        )

        assertTrue(planner.available)
        assertEquals(5, planner.visibleChoices.size)
        assertTrue(planner.hasAdvancedChoices)
        assertEquals("balanced", planner.visibleChoices.single { it.recommended }.id)
    }

    @Test
    fun missingDisplayPlannerStaysNullForOlderHosts() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject("""{"id":"game-legacy","app_id":7,"name":"Legacy Game"}""")
        )
        assertNull(game.displayPlanner)
    }

    @Test
    fun sparsePlannerEntriesFallBackToTheContractDefaults() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """
                {
                  "id": "game-sparse",
                  "display_planner": {
                    "available": true,
                    "choices": ["not-an-object", {"id":"balanced","target_mode":"1920x1200x60"}],
                    "advanced_scale_factors": [42, {"label":"1x"}]
                  }
                }
                """.trimIndent()
            )
        )

        val planner = requireNotNull(game.displayPlanner)
        val choice = planner.choices.single()
        assertEquals("balanced", choice.id)
        assertTrue(choice.safe)
        assertFalse(choice.hidden)
        assertEquals(1.0, choice.scaleFactor, 0.0)
        val scale = planner.advancedScaleFactors.single()
        assertEquals("1x", scale.label)
        assertEquals(1.0, scale.scaleFactor, 0.0)
        assertTrue(scale.safe)
    }
}
