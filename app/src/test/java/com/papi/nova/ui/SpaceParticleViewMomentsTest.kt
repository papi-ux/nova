package com.papi.nova.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The sky's hidden moments: how long they last, the still image, and silence during a stream (#424). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpaceParticleViewMomentsTest {
    @Before @After fun clear() {
        while (NovaSkyQuiet.active) NovaSkyQuiet.streamEnded()
        setAnimationScale(1f)
    }

    private fun setAnimationScale(scale: Float) {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private fun sky(night: Boolean = false): SpaceParticleView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = SpaceParticleView(activity).apply { today = { night } }
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 800, 480)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun draw(view: SpaceParticleView) = view.draw(Canvas(Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888)))

    @Test fun theBurstLastsThreeSecondsAndGoes() {
        val view = sky()
        view.playSupernova()
        assertTrue(view.burstShowing)
        draw(view)
        advance(2_900L)
        assertTrue(view.burstShowing)
        draw(view)
        advance(200L)
        assertFalse(view.burstShowing)
    }

    @Test fun thePreviewShowsAShowerAndAnOrdinaryNightHasNone() {
        val view = sky(night = false)
        assertFalse(view.showerShowing)
        view.previewMeteors()
        assertTrue(view.showerShowing)
        draw(view)
        advance(6_100L)
        assertFalse(view.showerShowing)
    }

    @Test fun aPeakNightHasAShowerForAsLongAsItIsShown() {
        val view = sky(night = true)
        assertTrue(view.showerShowing)
        draw(view)
        advance(60_000L)
        assertTrue(view.showerShowing)
    }

    @Test fun oneStarBrightensBriefly() {
        val view = sky()
        view.brightenOneStar()
        assertTrue(view.brightenedStarShowing)
        draw(view)
        advance(1_600L)
        assertFalse(view.brightenedStarShowing)
    }

    @Test fun lessMotionMakesEveryMomentAStillImage() {
        setAnimationScale(0f)
        val view = sky(night = true)
        assertTrue(view.stillImageOnly)
        view.northStar = true
        view.playSupernova()
        view.previewMeteors()
        view.brightenOneStar()
        assertTrue(view.burstShowing)
        // Drawing a still frame schedules no next one, and a moment's end is one redraw.
        draw(view)
        advance(3_100L)
        assertFalse(view.burstShowing)
        draw(view)
    }

    @Test fun nothingStartsDuringAStream() {
        val view = sky()
        NovaSkyQuiet.streamStarted()
        view.playSupernova()
        view.previewMeteors()
        view.brightenOneStar()
        assertFalse(view.burstShowing)
        assertFalse(view.brightenedStarShowing)
        assertFalse(view.showerShowing)
        draw(view)
    }

    @Test fun theSkyComesBackWhenTheLastStreamEnds() {
        val view = sky()
        NovaSkyQuiet.streamStarted()
        NovaSkyQuiet.streamStarted()
        NovaSkyQuiet.streamEnded()
        view.playSupernova()
        assertFalse("a replacing Game's stream still runs", view.burstShowing)
        NovaSkyQuiet.streamEnded()
        view.playSupernova()
        assertTrue(view.burstShowing)
    }
}
