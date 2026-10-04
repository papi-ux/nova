package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisDesktopSteamTest {
    @Test fun incompleteStatusDoesNotAuthorizeAnAction() {
        assertNull(PolarisDesktopSteam.parse(JSONObject()))
        val valid = JSONObject("""{"version":1,"status":true,"state":"running","revision":"observed-root","game_active":false,"effective_keep_running":true}""")
        assertTrue(PolarisDesktopSteam.parse(valid)!!.available)
        valid.put("game_active", "false")
        assertNull(PolarisDesktopSteam.parse(valid))
    }
    @Test fun unavailableStatusKeepsTheHostsReason() {
        val blocked = PolarisDesktopSteam.parse(JSONObject("""{"version":1,"status":false,"state":"unavailable","error":"Steam belongs to Game Mode."}"""))!!
        assertFalse(blocked.available)
        assertFalse(blocked.accepted)
        assertEquals("Steam belongs to Game Mode.", blocked.message)
    }
    @Test fun lifetimeFollowsSelectedModeAndSurvivesIntentSerialization() {
        val game = PolarisGameJsonAdapter.fromJson(JSONObject("""{"id":"steam-game","steam_appid":"620","steam_lifecycle":{"keep_in_host_virtual_display":true,"keep_in_desktop_display":false}}"""))
        val restored = PolarisGameJson.decode(PolarisGameJson.encode(game))!!
        assertTrue(restored.steamLifecycle!!.keep("host_virtual_display"))
        assertFalse(restored.steamLifecycle!!.keep("desktop_display"))
        assertFalse(restored.steamLifecycle!!.keep("headless_stream"))
        assertNull(PolarisGameJsonAdapter.fromJson(JSONObject("""{"steam_lifecycle":{"keep_in_host_virtual_display":"true","keep_in_desktop_display":true}}""")).steamLifecycle)
    }
}
