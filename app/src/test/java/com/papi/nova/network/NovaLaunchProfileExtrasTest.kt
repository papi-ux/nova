package com.papi.nova.network

import com.papi.nova.manager.WorkerLaunchContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Game's own check that a network-rule profile never reaches watch, resume or Space launches. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaLaunchProfileExtrasTest {
    private val profile = "22222222-2222-2222-2222-222222222222"
    private val ordinaryApp = "7f9c2ba4-e88f-41b2-9a3a-0f3a8b8c1d10"

    /** The same accepted worker contract WorkerLaunchContractTest uses. */
    private fun workerContract(): JSONObject {
        val fields = JSONObject()
        for ((name, value) in mapOf(
            "display_mode" to "1920x1080x60", "display_width" to 1920,
            "display_height" to 1080, "target_fps" to 60,
            "target_bitrate_kbps" to 8000, "hdr" to false, "preferred_codec" to "h264",
        )) fields.put(name, JSONObject().put("value", value).put("locked", true)
            .put("normalized", false).put("source", "capability_validation")
            .put("reason_code", "worker_media_contract"))
        return JSONObject().put("status", true).put("source", "worker_profile_v1")
            .put("worker_profile", JSONObject().put("version", 1)
                .put("id", "12345678-1234-4234-8234-123456789abc")
                .put("app_uuid", WorkerLaunchContract.APP_UUID).put("app_id", WorkerLaunchContract.APP_ID)
                .put("codec", "h264").put("audio_channels", 2))
            .put("resolved_profile", JSONObject().put("policy_version", 1).put("preset", "worker").put("fields", fields))
            .put("topology_resolution", JSONObject().put("resolved", "gamescope_stream"))
    }

    private fun applicable(
        uuid: String? = profile, watch: Boolean = false, resume: Boolean = false,
        appUuid: String? = ordinaryApp, appId: Int = 42, optimization: String? = null,
    ) = NovaLaunchProfileExtras.applicableProfile(uuid, watch, resume, appUuid, appId, optimization)

    @Test fun anOrdinaryLaunchKeepsItsProfile() {
        assertNull(WorkerLaunchContract.parse(JSONObject().put("source", "polaris_ai")))
        assertEquals(profile, applicable())
        assertEquals(profile, applicable(optimization = """{"source":"polaris_ai"}"""))
        assertEquals(profile, applicable(optimization = "not json"))
        assertEquals(profile, applicable(appUuid = null, appId = 42))
    }

    @Test fun noProfileMeansNone() {
        assertNull(applicable(uuid = null))
        assertNull(applicable(uuid = ""))
        assertNull(applicable(uuid = "  "))
    }

    @Test fun watchingAndResumingKeepTheActiveProfile() {
        assertNull(applicable(watch = true))
        assertNull(applicable(resume = true))
    }

    @Test fun everyWayASpaceIsNamedKeepsTheActiveProfile() {
        assertNull(applicable(appUuid = WorkerLaunchContract.APP_UUID))
        assertNull(applicable(appUuid = null, appId = WorkerLaunchContract.APP_ID))
        assertNull(applicable(appUuid = "", appId = WorkerLaunchContract.APP_ID))
        assertNull(applicable(appUuid = "space.living-room.big-picture-v1"))
        val contract = workerContract()
        assertNotNull("the fixture is an accepted contract", WorkerLaunchContract.parse(contract))
        // A worker media contract in the launch blob is a Space even when the app looks ordinary.
        assertNull(applicable(optimization = contract.toString()))
    }
}
