package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class WorkerLaunchContractTest {
    private fun fixture(): JSONObject {
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

    private fun honors(payload: JSONObject = fixture(), app: String = WorkerLaunchContract.APP_UUID,
                       fps: Float = 60f, maximum: Float = 60f, width: Int = 1920,
                       bitrate: Int = 8000, mirror: Boolean = false, force: Boolean = false,
                       encoder: String = "") = WorkerLaunchContract.honors(
        payload, app, width, 1080, fps, maximum, true, true, bitrate, mirror, force, encoder,
    )

    @Test fun exactWorkerContractFlowsThroughResolutionWithoutHostDefaults() {
        val payload = fixture()
        assertNotNull(WorkerLaunchContract.parse(payload))
        assertTrue(StreamSyncManager.hasTrustedResolvedProfile(payload))
        assertTrue(honors(payload))
        assertEquals(8000, StreamSyncManager.resolveAutoSafeBitrateKbps(20000, payload))
        assertFalse(StreamSyncManager.resolveAutoSafeHdr(true, payload))
        assertEquals(60f, StreamSyncManager.resolveAutoSafeTargetFps(120f, payload))
        assertEquals(1920, StreamSyncManager.resolveAutoSafeResolution(1280, 720, payload).width)
    }

    @Test fun highRefreshWorkerContractPreserves120FpsAndRejectsSlowerClientLimits() {
        val payload = fixture()
        val fields = payload.getJSONObject("resolved_profile").getJSONObject("fields")
        fields.getJSONObject("display_mode").put("value", "1920x1080x120")
        fields.getJSONObject("target_fps").put("value", 120)
        assertEquals(120, WorkerLaunchContract.parse(payload)?.fps)
        assertTrue(StreamSyncManager.hasTrustedResolvedProfile(payload))
        assertTrue(honors(payload, fps = 120f, maximum = 120f))
        assertTrue(honors(payload, fps = 120f, maximum = 119.88f))
        assertEquals(120f, StreamSyncManager.resolveAutoSafeTargetFps(120f, payload))
        assertFalse(honors(payload, fps = 60f, maximum = 120f))
        assertFalse(honors(payload, fps = 120f, maximum = 60f))

        fields.getJSONObject("display_mode").put("value", "1920x1080x60")
        assertNull(WorkerLaunchContract.parse(payload))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(payload))
    }

    @Test fun lowerBitratesFlowThroughResolutionAndRespectTheClientLimit() {
        for (bitrate in listOf(1, 1000, 4000, 8000)) {
            val payload = fixture()
            payload.getJSONObject("resolved_profile").getJSONObject("fields")
                .getJSONObject("target_bitrate_kbps").put("value", bitrate)
            assertEquals(bitrate, WorkerLaunchContract.parse(payload)?.bitrateKbps)
            assertTrue(honors(payload, bitrate = bitrate))
            assertFalse(honors(payload, bitrate = bitrate - 1))
            assertEquals(bitrate, StreamSyncManager.resolveAutoSafeBitrateKbps(20000, payload))
        }
        for (invalid in listOf(0, -1, 8001, 4000.5, "4000")) {
            val payload = fixture()
            payload.getJSONObject("resolved_profile").getJSONObject("fields")
                .getJSONObject("target_bitrate_kbps").put("value", invalid)
            assertNull(WorkerLaunchContract.parse(payload))
        }
    }

    @Test fun spaceDisplayOverridesRemainFreeWhileMeteredBitrateIsACeiling() {
        val codec=com.papi.nova.preferences.PreferenceConfiguration.FormatOption.FORCE_PYROWAVE
        for(metered in listOf(false,true)) for(ceiling in listOf(4000,8000)) {
            assertEquals("metered=$metered ceiling=$ceiling",!metered || ceiling>=8000,
                WorkerLaunchContract.honors(fixture(),WorkerLaunchContract.APP_UUID,1280,720,60f,120f,
                    NovaTierLaunchPolicy.displayLocked(true,true),NovaTierLaunchPolicy.bitrateLocked(codec,true,metered),
                    ceiling,false,false,""))
        }
    }

    @Test fun catalogIdentifiersPreserveCaseAndAcceptTheHostTokenFormat() {
        for (id in listOf("02CC2BAD-C86D-0D0E-3F9F-AA51619E432E", "profile-a", "_profile", "a".repeat(128))) {
            val payload = fixture()
            payload.getJSONObject("worker_profile").put("id", id)
            assertEquals(id, WorkerLaunchContract.parse(payload)?.id)
            assertTrue(StreamSyncManager.hasTrustedResolvedProfile(payload))
        }
        for (id in listOf("", "-profile", "../profile", "a".repeat(129), "profile one")) {
            val payload = fixture()
            payload.getJSONObject("worker_profile").put("id", id)
            assertNull(WorkerLaunchContract.parse(payload))
        }
    }

    @Test fun malformedOrUnknownWorkerAuthorityCannotEnterTheHostResolverPath() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("source", "worker_profile_v2") },
            { it.put("status", "true") },
            { it.getJSONObject("worker_profile").put("version", 2) },
            { it.getJSONObject("worker_profile").put("id", "../another-profile") },
            { it.getJSONObject("worker_profile").put("app_uuid", "host-game") },
            { it.getJSONObject("worker_profile").put("codec", "hevc") },
            { it.getJSONObject("worker_profile").put("audio_channels", 6) },
            { it.getJSONObject("topology_resolution").put("resolved", "desktop_display") },
            { it.getJSONObject("resolved_profile").getJSONObject("fields").getJSONObject("hdr").put("value", true) },
            { it.getJSONObject("resolved_profile").getJSONObject("fields").getJSONObject("target_fps").put("value", 59.94) },
            { it.getJSONObject("resolved_profile").getJSONObject("fields").getJSONObject("target_bitrate_kbps").put("value", 8001) },
            { it.getJSONObject("resolved_profile").getJSONObject("fields").getJSONObject("display_width").put("locked", false) },
        )
        for (mutate in mutations) {
            val payload = fixture().also(mutate)
            assertNull(payload.toString(), WorkerLaunchContract.parse(payload))
            assertFalse(payload.toString(), StreamSyncManager.hasTrustedResolvedProfile(payload))
        }
    }

    @Test fun contractCannotOverrideAnotherAppOrClientLocks() {
        assertFalse(honors(app = "host-game"))
        assertFalse(honors(fps = 30f))
        assertFalse(honors(maximum = 30f))
        assertFalse(honors(maximum = Float.NaN))
        assertFalse(honors(width = 1280))
        assertFalse(honors(bitrate = 4000))
        assertFalse(honors(mirror = true))
        assertFalse(honors(force = true))
        assertFalse(honors(encoder = "vulkan"))
        assertTrue(honors(encoder = "auto"))
        assertTrue(honors(maximum = 59.94f))
    }

    @Test fun workerTopologyIsBoundToTheReservedAppAndExcludesHostDisplayActions() {
        val payload = fixture()
        assertTrue(LaunchTopologyEnvelope.matches(payload, WorkerLaunchContract.APP_UUID,
            "gamescope_stream", true, false, false))
        assertFalse(LaunchTopologyEnvelope.matches(payload, "host-game",
            "gamescope_stream", true, false, false))
        assertFalse(LaunchTopologyEnvelope.matches(payload, WorkerLaunchContract.APP_UUID,
            "gamescope_stream", true, true, false))
    }

    @Test fun libraryLaunchCannotTransplantAnotherSpaceOrTitle() {
        val payload = fixture()
        val id = "12345678-1234-4234-8234-123456789abc"
        val identity = "space.$id.3527290"
        payload.getJSONObject("worker_profile").put("target", "3527290").put("game_identity", identity)
        assertNotNull(WorkerLaunchContract.parse(payload))
        assertTrue(honors(payload, app = identity))
        assertFalse(honors(payload, app = "space.other.3527290"))
        assertFalse(honors(payload, app = "space.$id.870780"))
        assertFalse(honors(payload, app = WorkerLaunchContract.APP_UUID))
        payload.getJSONObject("worker_profile").put("game_identity", "space.other.3527290")
        assertNull(WorkerLaunchContract.parse(payload))
    }

    @Test fun libraryTargetsAreBoundedIdentifiersRatherThanCommands() {
        assertEquals("Alex" to "3527290", WorkerLaunchContract.libraryIdentity("space.Alex.3527290"))
        assertNotNull(WorkerLaunchContract.libraryIdentity("space.Alex.big-picture-v1"))
        listOf("space.Alex.0", "space.Alex.01", "space.Alex.4294967296", "space.Alex.1;cmd", "space.Alex.1.extra")
            .forEach { assertNull(it, WorkerLaunchContract.libraryIdentity(it)) }
    }
}
