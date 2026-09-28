package com.papi.nova.ui

import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaMenuBlurOwnershipTest {
    @Test
    fun releasingOneOwnerKeepsTheStrongestRemainingBlur() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)

        val strong = NovaMenuBlur.acquire(view, 25)
        val medium = NovaMenuBlur.acquire(view, 64)

        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(view)), 0.001f)

        strong.release()

        assertEquals(8.64f, requireNotNull(NovaMenuBlur.currentRadiusDp(view)), 0.001f)

        medium.release()

        assertNull(NovaMenuBlur.currentRadiusDp(view))
    }

    @Test
    fun separateDialogWindowKeepsPanelContentOutsideTheBlurredActivityTree() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView
        val dialog = Dialog(activity)
        val panelContent = View(activity)
        dialog.setContentView(panelContent)
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // NovaPanelWindow's frame leases the backdrop of the activity under it, found from the
        // window's own context, and never blurs its own window.
        val lease = requireNotNull(NovaMenuBlur.acquireActivityBackground(dialog.context, 25))

        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(activityDecor)), 0.001f)
        assertNull(NovaMenuBlur.currentRadiusDp(dialog.window!!.decorView))
        assertNull(NovaMenuBlur.currentRadiusDp(panelContent))
        assertTrue(panelContent.rootView === dialog.window!!.decorView)
        assertTrue(panelContent.rootView !== activityDecor)

        lease.release()
        dialog.dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(NovaMenuBlur.currentRadiusDp(activityDecor))
    }

    @Test
    fun backdropLeaseRejectsBackgroundMutation() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val failure = AtomicReference<Throwable?>()

        Thread {
            runCatching { NovaMenuBlur.acquireActivityBackground(activity, 25) }
                .onFailure(failure::set)
        }.apply {
            start()
            join()
        }

        assertTrue(failure.get() is IllegalStateException)
    }

    @Test
    fun backgroundReleaseIsRejectedWithoutDiscardingOwnership() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)
        val lease = NovaMenuBlur.acquire(view, 25)
        val failure = AtomicReference<Throwable?>()

        Thread {
            runCatching { lease.release() }
                .onFailure(failure::set)
        }.apply {
            start()
            join()
        }

        assertTrue(failure.get() is IllegalStateException)
        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(view)), 0.001f)

        lease.release()
        assertNull(NovaMenuBlur.currentRadiusDp(view))
    }

    @Test
    fun unexpectedOverlayDetachReleasesBackgroundLease() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root: ViewGroup = activity.window.decorView.findViewById(android.R.id.content)
        val background = View(activity)
        val overlay = View(activity)
        root.addView(background)
        val lease = NovaMenuBlur.acquire(background, 25)
        NovaMenuBlur.releaseOnUnexpectedDetach(overlay) { lease.release() }

        root.addView(overlay)
        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(background)), 0.001f)

        root.removeView(overlay)

        assertNull(NovaMenuBlur.currentRadiusDp(background))
    }

    @Test
    fun releaseIsIdempotentAndZeroBlurOwnerDoesNotClearAnotherOwner() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)

        val compatibilityOwner = NovaMenuBlur.acquire(view, 100)
        val blurredOwner = NovaMenuBlur.acquire(view, 25)

        compatibilityOwner.release()
        compatibilityOwner.release()

        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(view)), 0.001f)

        blurredOwner.release()

        assertNull(NovaMenuBlur.currentRadiusDp(view))
    }
}
