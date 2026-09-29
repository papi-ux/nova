package com.papi.nova.ui
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class NovaLaunchLockTest {
    @Test fun allThreeLaunchSitesUseCodecSpaceAndMeteredLocks() {
        val root=File("src/main/java/com/papi/nova")
        assertTrue(File(root,"ui/NovaGameDetailActivity.kt").readText().contains("NovaTierLaunchPolicy.bitrateLocked(effectiveCodec(), spaceGame != null, metered)"))
        assertTrue(File(root,"ShortcutTrampoline.kt").readText().contains("NovaTierLaunchPolicy.bitrateLocked(codec, isWorkerProfile, metered)"))
        val game=File(root,"Game.kt").readText()
        assertTrue(game.contains("NovaTierLaunchPolicy.bitrateLocked(prefConfig.videoFormat,"))
        assertTrue(game.contains("WorkerLaunchContract.isProfileApp(safeAppIdentity), bitrateLocked)"))
    }
    @Test fun gameUsesStreamWindowAndSharedSessionLockDecision() {
        val game=File("src/main/java/com/papi/nova/Game.kt").readText()
        assertEquals(3,Regex("getMaxSupportedRefreshRate\\(streamingDisplay\\)").findAll(game).count())
        assertFalse(game.contains("displayLocked = watchStreamWidth > 0 && watchStreamHeight > 0"))
    }

    @Test fun coldGeneratedLaunchWaitsForMetadataAndRefusesAnUnavailableTier() {
        val game=File("src/main/java/com/papi/nova/Game.kt").readText()
        val gate=game.indexOf("NovaTierRuntime.prepare(applicationContext)")
        val refusal=game.indexOf("R.string.nova_tier_unavailable")
        val decoder=game.indexOf("MediaCodecHelper.initialize(this")
        assertTrue(gate>0 && gate<refusal && refusal<decoder)
        assertTrue(game.contains("if (plan == null || !plan.available)"))
        assertTrue(game.contains("NovaTierRuntime.snapshot()?.tiers != tierSnapshotAtRead?.tiers"))
    }
}
