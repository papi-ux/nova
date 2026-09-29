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
}
