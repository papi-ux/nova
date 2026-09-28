package com.papi.nova.ui.panel

import android.os.Looper
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * On API 34 and later a screen that opts in to OnBackInvokedCallback hands KEYCODE_BACK to the
 * window's back callback, press as onBackStarted and release as onBackInvoked, and never to
 * dispatchKeyEvent. A Back held while a panel or state page appears went down under it, so the
 * panel window sees only the release. These tests drive the callbacks the platform would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovaBackStartGateTest {
    private val controllers = mutableListOf<ActivityController<ComponentActivity>>()

    @After
    fun destroyActivities() {
        controllers.forEach { it.pause().stop().destroy() }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun press() = BackEvent(0f, 0f, 0f, BackEvent.EDGE_LEFT)

    @Test
    fun aReleaseActsOnlyOnceForEachStartSeenHere() {
        val gate = NovaBackStartGate()
        assertFalse("a release alone began somewhere else", gate.admit())
        gate.onStarted()
        assertTrue(gate.admit())
        assertFalse("one start admits one release", gate.admit())
        gate.onStarted()
        gate.onCancelled()
        assertFalse("a cancelled press admits nothing", gate.admit())
        gate.onStarted()
        gate.reset()
        assertFalse("a start is forgotten when the window loses focus", gate.admit())
    }

    @Test
    fun theOwnersAnimationCallbackActsOnlyAfterAStartSeenHere() {
        val window = RecordingDispatcher()
        val dispatcher = NovaStartGatedBackDispatcher(window, NovaBackStartGate())
        val owner = RecordingCallback()
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, owner)

        val (priority, standIn) = window.registered.single()
        assertEquals("the stand in keeps the owner's priority", OnBackInvokedDispatcher.PRIORITY_DEFAULT, priority)
        assertNotSame("the window holds the gate, not the owner's callback", owner, standIn)
        standIn as OnBackAnimationCallback

        standIn.onBackInvoked()
        assertEquals("a release whose press went down in another window does nothing", emptyList<String>(), owner.events)

        standIn.onBackStarted(press())
        standIn.onBackProgressed(press())
        standIn.onBackInvoked()
        assertEquals(listOf("started", "progressed", "invoked"), owner.events)

        standIn.onBackInvoked()
        assertEquals("the start is spent", 3, owner.events.size)

        standIn.onBackStarted(press())
        standIn.onBackCancelled()
        standIn.onBackInvoked()
        assertEquals(listOf("started", "progressed", "invoked", "started", "cancelled"), owner.events)

        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, owner)
        assertSame("registering again reuses the same stand in", standIn, window.registered.last().second)
        dispatcher.unregisterOnBackInvokedCallback(owner)
        assertTrue("unregistering removes the stand in from the window", window.registered.none { it.second === standIn })
    }

    @Test
    fun aPlainCallbackGetsNoStartSoItPassesAsItIs() {
        val window = RecordingDispatcher()
        val dispatcher = NovaStartGatedBackDispatcher(window, NovaBackStartGate())
        val plain = OnBackInvokedCallback { }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, plain)
        assertSame(plain, window.registered.single().second)
        dispatcher.unregisterOnBackInvokedCallback(plain)
        assertTrue(window.registered.isEmpty())
    }

    @Test
    fun aBackHeldWhileThePanelWindowAppearsCannotCloseItOnRelease() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controllers += it }.get()
        NovaSurfaces.of(activity).open(
            NovaCommonPage.Menu(
                key = "host",
                title = "Living Room",
                items = listOf(NovaMenuItem.Action(key = "wake", label = "Wake", onClick = {})),
            ),
        )
        idle()
        val panelWindow = ShadowDialog.getLatestDialog() as ComponentDialog
        val gated = panelWindow.onBackInvokedDispatcher as NovaStartGatedBackDispatcher

        // The newest handler answers Back first, as the page stack host's would.
        var backs = 0
        panelWindow.onBackPressedDispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    backs++
                }
            },
        )
        val platformCallback = gated.registered().single() as OnBackAnimationCallback

        platformCallback.onBackInvoked()
        assertEquals("the release of a Back pressed before the window appeared does nothing", 0, backs)

        platformCallback.onBackStarted(press())
        platformCallback.onBackInvoked()
        assertEquals("a Back pressed and released here goes back once", 1, backs)

        platformCallback.onBackStarted(press())
        panelWindow.onWindowFocusChanged(false)
        platformCallback.onBackInvoked()
        assertEquals("a press the window lost focus in the middle of does nothing", 1, backs)
    }

    @Test
    fun android13StaysOnKeyTrackingBecauseTheApplicationKeepsTheCallbackOff() {
        // Android 13 reads only the application's flag, and gives no start to gate on. With the flag
        // off, KEYCODE_BACK goes through dispatchKeyEvent there, where Dialog acts only on a release
        // whose press it tracked. Turning it on would let a Back held while a panel appears close it
        // on the Retroid Pocket 6, which runs Android 13; screens opt in one by one instead.
        val application = java.io.File("src/main/AndroidManifest.xml").readText()
            .substringAfter("<application").substringBefore(">")
        assertTrue(application, application.contains("android:enableOnBackInvokedCallback=\"false\""))
    }

    private class RecordingDispatcher : OnBackInvokedDispatcher {
        val registered = mutableListOf<Pair<Int, OnBackInvokedCallback>>()

        override fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) {
            registered += priority to callback
        }

        override fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) {
            registered.removeAll { it.second === callback }
        }
    }

    private class RecordingCallback : OnBackAnimationCallback {
        val events = mutableListOf<String>()

        override fun onBackStarted(backEvent: BackEvent) {
            events += "started"
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            events += "progressed"
        }

        override fun onBackCancelled() {
            events += "cancelled"
        }

        override fun onBackInvoked() {
            events += "invoked"
        }
    }
}
