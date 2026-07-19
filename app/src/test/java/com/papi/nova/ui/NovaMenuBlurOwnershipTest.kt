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
    fun dialogAcquiresOnlyWhenAttachedAndReleasesOnDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val target = activity.window.decorView
        val dialog = Dialog(activity)

        NovaMenuBlur.attachBehindDialog(dialog, 25)

        assertNull(NovaMenuBlur.currentRadiusDp(target))

        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(target)), 0.001f)

        dialog.dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(NovaMenuBlur.currentRadiusDp(target))
    }

    @Test
    fun reattachingShownDialogReplacesItsLeaseWithoutLeakingTheOldRadius() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val target = activity.window.decorView
        val dialog = Dialog(activity)

        NovaMenuBlur.attachBehindDialog(dialog, 25)
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(18f, requireNotNull(NovaMenuBlur.currentRadiusDp(target)), 0.001f)

        NovaMenuBlur.attachBehindDialog(dialog, 64)
        assertEquals(8.64f, requireNotNull(NovaMenuBlur.currentRadiusDp(target)), 0.001f)

        dialog.dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(NovaMenuBlur.currentRadiusDp(target))
    }

    @Test
    fun dialogBindingRejectsBackgroundMutation() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val dialog = Dialog(activity)
        val failure = AtomicReference<Throwable?>()

        Thread {
            runCatching { NovaMenuBlur.attachBehindDialog(dialog, 25) }
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
