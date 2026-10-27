package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.Game
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #386: a pairing that saved the host's certificate, and a stream the player asked for, are each
 * confirmed once. The pairing's words travel on the launch Intent of the screen it opens, and
 * Android hands that Intent back on recreate(), after the process was killed and from Recents.
 * Only the first launch says them. A replacement stream is a new Game, and it says nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaConfirmationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pairingLaunch() = Intent(context, NovaLibraryActivity::class.java)
        .putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "Den")
        .putExtra(NovaConfirmation.EXTRA_PAIRED_WITH, NovaConfirmation.pairedWith(context, "Den"))

    @Test
    fun theScreenAPairingOpensSaysItOnceAcrossRecreate() {
        val launch = pairingLaunch()

        assertEquals("Paired with Den", NovaConfirmation.takePairedWith(launch, null))
        assertFalse("taken off the Intent", launch.hasExtra(NovaConfirmation.EXTRA_PAIRED_WITH))
        assertEquals("the rest of the launch stays", "Den", launch.getStringExtra(NovaLibraryActivity.EXTRA_SERVER_NAME))
        // recreate(), for a theme change, gives the new instance this same Intent and its saved state.
        assertNull(NovaConfirmation.takePairedWith(launch, Bundle()))
        assertNull(NovaConfirmation.takePairedWith(launch, null))
    }

    @Test
    fun aRestoredOrRecentsLaunchIsNotAPairing() {
        // After the process was killed, the system brings the original extras back with the saved state.
        val restored = pairingLaunch()
        assertNull(NovaConfirmation.takePairedWith(restored, Bundle()))
        assertFalse("nor is it kept for a later recreate", restored.hasExtra(NovaConfirmation.EXTRA_PAIRED_WITH))

        val recents = pairingLaunch().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(NovaConfirmation.takePairedWith(recents, null))
        assertFalse(recents.hasExtra(NovaConfirmation.EXTRA_PAIRED_WITH))

        // An ordinary open of the same screen has nothing to say.
        assertNull(NovaConfirmation.takePairedWith(Intent(context, NovaLibraryActivity::class.java), null))
    }

    @Test
    fun aHostWithNoNameIsConfirmedByTheHapticAlone() {
        assertNull(NovaConfirmation.pairedWith(context, ""))
        assertNull(NovaConfirmation.pairedWith(context, null))
        assertEquals("the overlay keeps its own Ready", "", NovaConfirmation.connectedTo(context, " "))
        assertEquals("Connected to Den", NovaConfirmation.connectedTo(context, "Den"))
    }

    @Test
    fun onlyTheStreamThePlayerAskedForIsConfirmedAndOnlyOnce() {
        val launch = Intent(context, Game::class.java).putExtra(Game.EXTRA_PC_NAME, "Den")
        val asked = NovaConfirmation.Connection().apply { arm(launch) }
        assertTrue("its first connectionStarted", asked.take())
        assertFalse("a second connectionStarted in the same Game", asked.take())

        // launchReplacementStream copies the launch and marks it, for Reconnect and the resilience path alike.
        val replacement = Intent(launch)
            .putExtra(Game.EXTRA_RECONNECT_REQUESTED, true)
            .putExtra(Game.EXTRA_RECONNECT_ATTEMPT, 1)
        assertFalse(NovaConfirmation.Connection().apply { arm(replacement) }.take())

        // Game arms it once, from the launch it reads pcName from, so a later onNewIntent cannot
        // re-arm it; and only connectionStarted takes it.
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val started = game.substringAfter("override fun connectionStarted()").substringBefore("fun handleStreamStartedState()")
        assertEquals(1, Regex("""connectionConfirmation\.arm\(""").findAll(game).count())
        assertTrue(game.contains("pcName = this@Game.getIntent().getStringExtra(EXTRA_PC_NAME)\nconnectionConfirmation.arm(this@Game.getIntent())"))
        assertEquals(1, Regex("""connectionConfirmation\.take\(\)""").findAll(game).count())
        assertTrue(started.contains("val confirmed = connectionConfirmation.take()"))
    }
}
