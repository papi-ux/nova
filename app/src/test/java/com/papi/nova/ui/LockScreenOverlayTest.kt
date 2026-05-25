package com.papi.nova.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.papi.nova.api.PolarisApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class LockScreenOverlayTest {

    @Test
    fun overlayTapRequestsUnlockAndPreventsDuplicateRequests() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val client = Mockito.mock(PolarisApiClient::class.java)
        val unlockStarted = CountDownLatch(1)
        val finishUnlock = CountDownLatch(1)

        Mockito.doAnswer {
            unlockStarted.countDown()
            assertTrue(finishUnlock.await(1, TimeUnit.SECONDS))
            false
        }.`when`(client).unlockScreen()

        val overlay = LockScreenOverlay(activity, client)
        overlay.show()
        shadowOf(Looper.getMainLooper()).idle()

        val root = overlayRoot(activity)
        val button = requireNotNull(findButton(root))
        assertEquals("Tap to unlock", button.text.toString())

        root.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(unlockStarted.await(1, TimeUnit.SECONDS))
        assertFalse(button.isEnabled)
        assertEquals("Unlocking…", button.text.toString())

        root.performClick()
        verify(client, timeout(1000).times(1)).unlockScreen()

        finishUnlock.countDown()
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            if (button.isEnabled) return@repeat
            Thread.sleep(50)
        }

        assertTrue(button.isEnabled)
        assertEquals("Tap to unlock", button.text.toString())
    }

    @Test
    fun successfulUnlockDismissesOverlay() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val client = Mockito.mock(PolarisApiClient::class.java)
        Mockito.`when`(client.unlockScreen()).thenReturn(true)

        val overlay = LockScreenOverlay(activity, client)
        overlay.show()
        shadowOf(Looper.getMainLooper()).idle()

        overlayRoot(activity).performClick()

        verify(client, timeout(1000)).unlockScreen()
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!overlay.isShowing) return@repeat
            Thread.sleep(50)
        }

        assertFalse(overlay.isShowing)
    }

    @Test
    fun dismissedOverlayIgnoresLateUnlockFailure() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val client = Mockito.mock(PolarisApiClient::class.java)
        val unlockStarted = CountDownLatch(1)
        val finishUnlock = CountDownLatch(1)

        Mockito.doAnswer {
            unlockStarted.countDown()
            assertTrue(finishUnlock.await(1, TimeUnit.SECONDS))
            false
        }.`when`(client).unlockScreen()

        val overlay = LockScreenOverlay(activity, client)
        overlay.show()
        shadowOf(Looper.getMainLooper()).idle()

        overlayRoot(activity).performClick()
        assertTrue(unlockStarted.await(1, TimeUnit.SECONDS))

        overlay.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        finishUnlock.countDown()
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }

        assertFalse(overlay.isShowing)
    }

    private fun overlayRoot(activity: Activity): ViewGroup {
        val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(content.childCount - 1) as ViewGroup
    }

    private fun findButton(view: View): Button? {
        if (view is Button) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
