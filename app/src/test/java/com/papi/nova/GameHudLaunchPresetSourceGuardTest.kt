package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD's stream line said "Auto profile" through a Quality launch: nothing told the HUD the
 * preset the host resolved (in-game #11). Game binds it when it sets the HUD up, from the launch
 * it resolved, and never from the host's prose or a preference that can change mid stream.
 */
class GameHudLaunchPresetSourceGuardTest {
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()

    @Test
    fun theHudIsToldTheResolvedLaunchPreset() {
        val configure = game.substringAfter("private fun configureNovaHud(").substringBefore("\n}\n")
        assertTrue(configure.contains("hud.setLaunchPresetLabel(novaHudLaunchPresetLabel)"))
        val resolved = game.substringAfter("novaHudLaunchPresetLabel = ").substringBefore("\n")
        assertTrue(resolved, resolved.startsWith("com.papi.nova.ui.NovaLaunchPresetLabel.resolved(resources, launchOptimization, launchResolvedProfileTrusted)"))
    }

    @Test
    fun itIsSetOnceTheLaunchIsResolved() {
        val committed = game.indexOf("launchInitializationCommitted = true")
        val set = game.indexOf("novaHudLaunchPresetLabel = com.papi.nova.ui.NovaLaunchPresetLabel.resolved(")
        assertTrue("set with the rest of the resolved launch", set > committed && committed > 0)
        assertFalse("never from the host's prose", game.substringAfter("novaHudLaunchPresetLabel = ").substringBefore("\n").contains("reasoning"))
    }
}
