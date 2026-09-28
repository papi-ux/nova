package com.papi.nova.utils

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import com.papi.nova.Game
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompanionActivityRoutingTest {
    private fun game(streamDisplayId: Int): Game {
        val game = Robolectric.buildActivity(Game::class.java).get()
        Game::class.java.getDeclaredField("prefConfig").apply { isAccessible = true }
            .set(game, PreferenceConfiguration().apply { enableFullExDisplay = true })
        Game::class.java.getDeclaredField("streamingDisplayId").apply { isAccessible = true }
            .setInt(game, streamDisplayId)
        Game.isStreamActive = true
        return game
    }

    private fun controls(game: Game, displayId: Int): ExternalDisplayControlActivity {
        val displays = game.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return mock(ExternalDisplayControlActivity::class.java).also {
            `when`(it.controlDisplay).thenReturn(displays.getDisplay(displayId))
        }
    }

    @After
    fun resetStreamState() {
        Game.isStreamActive = false
    }

    @Test
    fun activityCanAttachOnTheSelectedNonDefaultCompanion() {
        val companion = ShadowDisplayManager.addDisplay("w600dp-h900dp")
        val game = game(Display.DEFAULT_DISPLAY)
        assertTrue(game.attachExternalDisplayControlActivity(controls(game, companion)))
    }

    @Test
    fun activityCannotAttachIfAndroidOpenedItOnTheStreamDisplay() {
        val stream = ShadowDisplayManager.addDisplay("w600dp-h900dp")
        val game = game(stream)
        assertFalse(game.attachExternalDisplayControlActivity(controls(game, stream)))
    }

    @Test
    fun defaultCompanionStillAttachesWhileStreamIsActive() {
        val stream = ShadowDisplayManager.addDisplay("w600dp-h900dp")
        val game = game(stream)
        assertTrue(game.attachExternalDisplayControlActivity(controls(game, Display.DEFAULT_DISPLAY)))
        Game.isStreamActive = false
        assertFalse(game.attachExternalDisplayControlActivity(controls(game, Display.DEFAULT_DISPLAY)))
    }

    @Test
    fun removedCompanionCannotAttach() {
        val companion = ShadowDisplayManager.addDisplay("w600dp-h900dp")
        val game = game(Display.DEFAULT_DISPLAY)
        val controls = controls(game, companion)
        ShadowDisplayManager.removeDisplay(companion)
        assertFalse(game.attachExternalDisplayControlActivity(controls))
    }

    @Test
    fun presentationFailureDisposesItThenLaunchesActivityOnTheSameDisplay() {
        val companion = ShadowDisplayManager.addDisplay("w600dp-h900dp")
        val game = game(Display.DEFAULT_DISPLAY)
        shadowOf(game.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val displays = game.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        shadowOf(displays.getDisplay(companion)).setFlags(Display.FLAG_PRESENTATION)
        mockConstruction(ExternalDisplayControlPresentation::class.java) { presentation, _ ->
            doThrow(WindowManager.InvalidDisplayException("display cannot present"))
                .`when`(presentation).show()
        }.use { constructed ->
            Game::class.java.getDeclaredMethod("launchCompanionControlsIfAvailable")
                .apply { isAccessible = true }.invoke(game)
            assertEquals(1, constructed.constructed().size)
            verify(constructed.constructed().single()).disposeAfterFailedShow()
            val started = shadowOf(game).nextStartedActivityForResult
            assertEquals(ExternalDisplayControlActivity::class.java.name, started.intent.component!!.className)
            val options = ReflectionHelpers.callStaticMethod<ActivityOptions>(
                ActivityOptions::class.java, "fromBundle", ClassParameter.from(Bundle::class.java, started.options),
            )
            assertEquals(companion, options.launchDisplayId)
        }
    }

    @Test
    fun activityLaunchRefusalDoesNotEscapeIntoTheStream() {
        val realGame = game(Display.DEFAULT_DISPLAY)
        val displays = realGame.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val game = mock(Game::class.java)
        `when`(game.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(displays)
        doThrow(SecurityException("display access denied"))
            .`when`(game).startActivity(any(Intent::class.java), any(Bundle::class.java))
        assertFalse(ExternalDisplayControlActivity.launch(game, Display.DEFAULT_DISPLAY))
        assertFalse(ExternalDisplayControlActivity.launch(game, 98765))
    }

    @Test
    fun failedPresentationBeforeOnCreateCanBeDisposed() {
        val companion = ShadowDisplayManager.addDisplay("w600dp-h900dp")
        val game = game(Display.DEFAULT_DISPLAY)
        val displays = game.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentation = ExternalDisplayControlPresentation(game, displays.getDisplay(companion))
        presentation.disposeAfterFailedShow()
        assertFalse(presentation.isShowing)
    }
}
