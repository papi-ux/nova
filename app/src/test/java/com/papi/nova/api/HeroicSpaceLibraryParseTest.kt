package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Space library entry as a host sends it for a launcher that is not Steam.
 * Nova refused the whole response when it met one, which reads on the handheld
 * as a library that will not load with nothing said about why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeroicSpaceLibraryParseTest {
    private val profile = "1e3242a9-948d-4a53-a243-276e428c4529"

    private fun entry(target: String, name: String, appid: String = ""): JSONObject =
        JSONObject()
            .put("id", "space.$profile.$target")
            .put("app_id", "1347244801")
            .put("name", name)
            .put("source", "heroic")
            .put("steam_appid", appid)
            .put("installed", true)
            .put("hdr_supported", false)
            .put("space", JSONObject().put("id", profile).put("name", "papi - heroic").put("target", target))
            .put("launch_mode", JSONObject()
                .put("preferred_mode", "gamescope_stream")
                .put("recommended_mode", "gamescope_stream")
                .put("mode_reason", "Runs in papi - heroic"))

    @Test fun readsTheTileThatOpensHeroic() {
        val game = PolarisGameJsonAdapter.fromJson(entry("library-v1", "Heroic"))
        assertEquals("Heroic", game.name)
        assertEquals("heroic", game.source)
        assertEquals("library-v1", game.space?.target)
        assertEquals(profile, game.space?.id)
    }

    @Test fun readsATitleWhoseTargetCarriesItsOwnDot() {
        val game = PolarisGameJsonAdapter.fromJson(entry("epic.AlanWake2", "Alan Wake 2"))
        assertEquals("Alan Wake 2", game.name)
        assertEquals("epic.AlanWake2", game.space?.target)
        assertEquals("", game.steamAppid)
    }

    @Test fun stillReadsASteamSpaceTheSameWay() {
        val steam = JSONObject(entry("big-picture-v1", "Steam Big Picture").toString())
            .put("source", "steam")
        val game = PolarisGameJsonAdapter.fromJson(steam)
        assertEquals("Steam Big Picture", game.name)
        assertEquals("big-picture-v1", game.space?.target)
    }
}
