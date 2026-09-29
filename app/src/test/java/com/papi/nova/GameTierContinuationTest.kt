package com.papi.nova

import android.app.Application
import com.papi.nova.preferences.*
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,shadows=[ShadowMoonBridge::class])
class GameTierContinuationTest {
    @After fun clean() { NovaTierRuntime.installForTest(null) }
    private fun prepared():NovaTierRuntime.Snapshot {
        NovaTierRuntime.installForTest(NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",
                listOf(NovaDecodePoint(NovaSize(1920,1080),120)))))))
        return NovaTierRuntime.snapshot()!!
    }
    private fun game()=Mockito.spy(Robolectric.buildActivity(Game::class.java).get()).also {
        Mockito.doNothing().`when`(it).recreate()
    }
    @Test fun unchangedPreparedTierContinuesOnceWithoutRecreating() {
        val prepared=prepared(); val game=game(); var launches=0
        game.completeTierPreparation(prepared,prepared.tiers) { launches++ }
        assertEquals(1,launches); assertFalse(game.isFinishing)
        Mockito.verify(game,Mockito.never()).recreate()
    }
    @Test fun changedTierRecreatesWithoutRunningTheOldContinuation() {
        val prepared=prepared(); val game=game(); var launches=0
        game.completeTierPreparation(prepared,prepared.tiers.copy(inputsHash="previous")) { launches++ }
        assertEquals(0,launches); Mockito.verify(game).recreate()
    }
    @Test fun failedProbeFinishesWithoutInitializing() {
        val prepared=prepared(); val game=game(); var launches=0
        val failed=prepared.copy(tiers=prepared.tiers.copy(inputsHash="failed",
            recommended=prepared.tiers.recommended.copy(limits=listOf(NovaReason("probe","Decoder probe failed")))))
        game.completeTierPreparation(failed,prepared.tiers) { launches++ }
        assertEquals(0,launches); assertTrue(game.isFinishing)
        Mockito.verify(game,Mockito.never()).recreate()
    }
    @Test fun continuationExceptionFinishesInsteadOfLeavingTheProgressOverlay() {
        val prepared=prepared(); val game=game()
        game.completeTierPreparation(prepared,prepared.tiers) { throw IllegalStateException("decoder creation failed") }
        assertTrue(game.isFinishing); Mockito.verify(game,Mockito.never()).recreate()
    }
    @Test fun invalidationAfterIoCompletesDoesNotLaunchAStalePlan() {
        val prepared=prepared(); val game=game(); var launches=0
        NovaTierRuntime.installForTest(null)
        game.completeTierPreparation(prepared,prepared.tiers) { launches++ }
        assertEquals(0,launches); Mockito.verify(game).recreate()
    }
    @Test fun newerPublishedPlanRecreatesEvenWhenIoResultMatchesTheOriginalRead() {
        val prepared=prepared(); val game=game(); var launches=0
        NovaTierRuntime.installForTest(prepared.inputs.copy(panel=NovaSize(1280,720)))
        game.completeTierPreparation(prepared,prepared.tiers) { launches++ }
        assertEquals(0,launches); Mockito.verify(game).recreate()
    }
    @Test fun continuedConfigurationRevotesAnAlreadyCreatedSurface() {
        val game=game()
        game.prefConfig=PreferenceConfiguration().apply {
            fps=60f; framePacing=PreferenceConfiguration.FRAME_PACING_BALANCED
        }
        val holder=Mockito.mock(android.view.SurfaceHolder::class.java)
        val surface=Mockito.mock(android.view.Surface::class.java)
        Mockito.`when`(holder.surface).thenReturn(surface)
        Mockito.`when`(surface.isValid).thenReturn(true)
        game.surfaceCreated(holder)
        Mockito.verify(surface).setFrameRate(60f,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            android.view.Surface.CHANGE_FRAME_RATE_ALWAYS)
        val view=Mockito.mock(android.view.SurfaceView::class.java)
        Mockito.`when`(view.holder).thenReturn(holder)
        val container=Mockito.mock(com.papi.nova.ui.StreamContainer::class.java)
        Mockito.`when`(container.getSurfaceView()).thenReturn(view)
        org.robolectric.util.ReflectionHelpers.setField(game,"streamContainer",container)
        org.robolectric.util.ReflectionHelpers.setField(game,"configuredStreamFrameRateFps",60f)
        org.robolectric.util.ReflectionHelpers.setField(game,"desiredRefreshRate",120f)
        game.refreshLaunchSurfaceFrameRate()
        Mockito.verify(surface).setFrameRate(120f,android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            android.view.Surface.CHANGE_FRAME_RATE_ALWAYS)
        // The host can also settle on a different cadence after the first surface vote.
        org.robolectric.util.ReflectionHelpers.setField(game,"configuredStreamFrameRateFps",90f)
        org.robolectric.util.ReflectionHelpers.setField(game,"desiredRefreshRate",90f)
        game.refreshLaunchSurfaceFrameRate()
        Mockito.verify(surface).setFrameRate(90f,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            android.view.Surface.CHANGE_FRAME_RATE_ALWAYS)
    }
    @Test fun configuredBitrateIsClampedAfterTheSavedRequestAndHostOverride() {
        for ((maximum,resolved,expected) in listOf(Triple(300000,500000,300000),
            Triple(500000,400000,400000),Triple(200000,400000,200000))) {
            val game=game()
            game.prefConfig=PreferenceConfiguration().apply { bitrate=450000;meteredBitrate=12000 }
            org.robolectric.util.ReflectionHelpers.setField(game,"launchManualBitrateMaximumKbps",maximum)
            game.configureLaunchBitrate(false,null)
            assertEquals(minOf(450000,maximum),org.robolectric.util.ReflectionHelpers.getField<Int>(game,"configuredStreamBitrateKbps"))
            val fields=org.json.JSONObject()
            for ((key,value) in listOf("display_mode" to "1920x1080x60", "display_width" to 1920,
                "display_height" to 1080, "target_fps" to 60, "target_bitrate_kbps" to resolved, "hdr" to false)) {
                fields.put(key,org.json.JSONObject().put("value",value).put("source","client_launch_request")
                    .put("reason_code","requested").put("locked",true).put("normalized",false))
            }
            val payload=org.json.JSONObject().put("source","deterministic_preset_v1")
                .put("resolved_profile",org.json.JSONObject().put("policy_version",1).put("fields",fields))
            assertTrue("Exercise an accepted host override, not the saved fallback",
                com.papi.nova.manager.StreamSyncManager.hasTrustedResolvedProfile(payload))
            game.configureLaunchBitrate(false,payload)
            assertEquals(expected,org.robolectric.util.ReflectionHelpers.getField<Int>(game,"configuredStreamBitrateKbps"))
            game.configureLaunchBitrate(true,null)
            assertEquals(12000,org.robolectric.util.ReflectionHelpers.getField<Int>(game,"configuredStreamBitrateKbps"))
            assertEquals(450000,game.prefConfig.bitrate)
        }
    }

}
