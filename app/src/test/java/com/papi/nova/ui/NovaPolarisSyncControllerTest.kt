package com.papi.nova.ui

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPanelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Polaris Sync's engine lives with its page in the System panel, not with a fragment: it starts
 * when the page is pushed, survives a page pushed on top of it, and closes when the page leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPolarisSyncControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = NovaPolarisSyncController(
        context = context,
        apiClient = null,
        serverUuid = null,
        scope = scope,
        onSettingsChanged = {},
    )

    @After
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test
    fun openingTwiceKeepsOneEngineAndClosingForgetsIt() {
        assertFalse(controller.isOpen)

        controller.open(initialSettings = null)
        val first = controller.engine
        assertNotNull(first)
        assertTrue("with no host client the page says Polaris Sync is unavailable", first!!.settingsUnavailable)
        controller.open(initialSettings = null)
        assertSame("a second open keeps the engine already running", first, controller.engine)

        controller.close()
        assertNull(controller.engine)
        assertFalse(controller.isOpen)

        controller.open(initialSettings = null)
        assertNotSame("a fresh page starts a fresh engine", first, controller.engine)
    }

    @Test
    fun theEngineOutlivesAPagePushedOnTopAndClosesWhenItsPageLeaves() {
        val panel = NovaPanelState()
        panel.open(Page("system"))
        controller.open(initialSettings = null)
        controller.closeWhenGone(panel, LibraryPage.KEY_POLARIS_SYNC)
        settle()
        assertTrue("the watch waits for the page to arrive before it can see it leave", controller.isOpen)

        panel.push(LibraryPage.PolarisSync("Polaris Sync"))
        settle()
        panel.push(Page("play-in"))
        settle()
        assertTrue("Where It Runs on top keeps the engine", controller.isOpen)

        panel.pop()
        settle()
        assertTrue(controller.isOpen)

        panel.pop()
        settle()
        assertFalse("the page left the stack, so the engine closed with it", controller.isOpen)
    }

    @Test
    fun closingThePanelClosesTheEngineToo() {
        val panel = NovaPanelState()
        panel.open(Page("system"))
        panel.push(LibraryPage.PolarisSync("Polaris Sync"))
        controller.open(initialSettings = null)
        controller.closeWhenGone(panel, LibraryPage.KEY_POLARIS_SYNC)
        settle()

        panel.close()
        settle()

        assertFalse(controller.isOpen)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Page(override val key: String) : NovaPage {
        override val title: String = key
    }
}
