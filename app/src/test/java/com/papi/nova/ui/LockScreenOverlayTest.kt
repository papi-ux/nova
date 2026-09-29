package com.papi.nova.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The locked host is a full-screen state page over the stream. These drive it through the state
 * layer: the page's actions are what A and B run on the page.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class LockScreenOverlayTest {

    @Test
    fun unlockIsTheFocusedActionAndAPressDuringAnUnlockDoesNotSendAnother() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
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
        idle()

        val page = lockPage(activity)
        assertEquals("Host screen is locked", page.title)
        assertEquals("the primary action is the one the page focuses, so A unlocks", "Unlock host", page.primary.label)
        assertTrue("B carries on with the stream, never the unlock", page.back is NovaProblemBack.Continue)
        assertEquals("B runs the least destructive way out, never the unlock", "Not Now", page.back.action?.label)

        page.primary.run()
        idle()
        assertTrue(unlockStarted.await(1, TimeUnit.SECONDS))
        assertEquals("Unlocking…", lockPage(activity).primary.label)

        lockPage(activity).primary.run()
        verify(client, timeout(1000).times(1)).unlockScreen()

        finishUnlock.countDown()
        repeat(20) {
            idle()
            if (lockPage(activity).primary.label == "Unlock host") return@repeat
            Thread.sleep(50)
        }

        assertEquals("a failed unlock can be asked for again", "Unlock host", lockPage(activity).primary.label)
        assertTrue(overlay.isShowing)
    }

    @Test
    fun successfulUnlockTakesThePageDown() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val client = Mockito.mock(PolarisApiClient::class.java)
        Mockito.`when`(client.unlockScreen()).thenReturn(true)

        val overlay = LockScreenOverlay(activity, client)
        overlay.show()
        idle()

        lockPage(activity).primary.run()

        verify(client, timeout(1000)).unlockScreen()
        repeat(20) {
            idle()
            if (!overlay.isShowing) return@repeat
            Thread.sleep(50)
        }

        assertFalse(overlay.isShowing)
        assertNull(findLockPage(activity))
    }

    @Test
    fun notNowLeavesTheLockScreenUntilTheHostLocksAgain() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val client = Mockito.mock(PolarisApiClient::class.java)

        val overlay = LockScreenOverlay(activity, client)
        overlay.show()
        idle()

        lockPage(activity).back.action!!.run()
        idle()
        assertFalse(overlay.isShowing)
        assertNull(findLockPage(activity))
        verify(client, never()).unlockScreen()

        // Still locked: the next state update must not bring the page straight back.
        overlay.show()
        idle()
        assertNull(findLockPage(activity))

        // Unlocked, then locked again: the page is back.
        overlay.dismiss()
        overlay.show()
        idle()
        assertTrue(overlay.isShowing)
        assertEquals("Unlock host", lockPage(activity).primary.label)
    }

    @Test
    fun dismissedPageIgnoresALateUnlockFailure() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
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
        idle()

        lockPage(activity).primary.run()
        assertTrue(unlockStarted.await(1, TimeUnit.SECONDS))

        overlay.dismiss()
        idle()
        finishUnlock.countDown()
        repeat(10) {
            idle()
            Thread.sleep(25)
        }

        assertFalse(overlay.isShowing)
        assertNull(findLockPage(activity))
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findLockPage(activity: ComponentActivity): NovaStatePage.Problem? =
        NovaSurfaces.of(activity).states.value.filterIsInstance<NovaStatePage.Problem>()
            .firstOrNull { it.key == "nova-host-locked" }

    private fun lockPage(activity: ComponentActivity): NovaStatePage.Problem =
        requireNotNull(findLockPage(activity)) { "the host-locked page is not showing" }
}
