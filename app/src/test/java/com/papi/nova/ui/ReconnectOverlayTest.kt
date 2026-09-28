package com.papi.nova.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Reconnecting is a Busy state page over the stream with Disconnect as its way out, because a host
 * set to unlimited retries would otherwise hold the player on it for good.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class ReconnectOverlayTest {

    @Test
    fun reconnectingIsABusyPageWhoseWayOutIsDisconnect() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var disconnects = 0
        val overlay = ReconnectOverlay(activity) { disconnects++ }

        overlay.show(attempt = 2, maxAttempts = 5)
        idle()

        val page = requireNotNull(busyPage(activity))
        assertEquals("Reconnecting stream…", page.title)
        assertTrue(page.message.value.contains("Attempt 2 of 5"))
        val cancel = requireNotNull(page.cancel) { "a reconnect with no way out holds the player" }
        assertEquals("Disconnect", cancel.label)

        overlay.show(attempt = 3, maxAttempts = 5)
        idle()
        assertTrue("a later attempt updates the same page", busyPage(activity)!!.message.value.contains("Attempt 3 of 5"))

        cancel.run()
        idle()
        assertEquals(1, disconnects)
        assertFalse(overlay.isShowing)
        assertNull(busyPage(activity))

        overlay.show(attempt = 4, maxAttempts = 5)
        idle()
        assertNull("once the player has left, later attempts show nothing", busyPage(activity))
    }

    @Test
    fun aReconnectThatLandsTakesThePageDown() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val overlay = ReconnectOverlay(activity) {}

        overlay.show(attempt = 1, maxAttempts = 3)
        idle()
        assertNotNull(busyPage(activity))

        overlay.dismiss()
        idle()
        assertFalse(overlay.isShowing)
        assertNull(busyPage(activity))
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun busyPage(activity: ComponentActivity): NovaStatePage.Busy? =
        NovaSurfaces.of(activity).states.value.filterIsInstance<NovaStatePage.Busy>()
            .firstOrNull { it.key == "nova-reconnecting" }
}
