package com.papi.nova

import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.grid.AppGridAdapter
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaSnackbar
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/**
 * #386: a host with no saved app list loads under a Busy page in its own window, and a snackbar
 * shown under it would not be seen. Dismissing the page is only a request: NovaSurfaces drops it
 * from its states at once, while the window stays through the page's minimum time and exit and
 * keeps focus. The App list says the pairing that opened it once that window has really closed
 * and focus has come back, and says it once.
 *
 * Like NovaPanelWindowTest, this calls NovaSurfaces.onWindowIdle itself, as the window's content
 * does once nothing is left to show, instead of waiting for a composition. Like the native
 * controller window tests, it delivers actual framework focus: Robolectric does not focus the
 * activity on dialog close. ActivityController also drains the queued focus message; calling
 * ShadowViewRootImpl alone only posts it on API 33. This does not advance virtual time past
 * the Busy page's minimum duration; its real modal ownership remains asserted below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewPairingConfirmationTest {
    private val controller = Robolectric.buildActivity(AppView::class.java)
    private val activity = controller.get()
    private val computer = ComputerDetails().apply { uuid = "host-id" }
    private val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java)

    @Before fun setUp() {
        activity.setTheme(R.style.AppTheme)
        activity.setContentView(FrameLayout(activity))
        val adapter = mock(AppGridAdapter::class.java)
        `when`(adapter.itemCount).thenReturn(1)
        `when`(adapter.getItem(0)).thenReturn(AppView.AppObject(NvApp("Example game", "game-id", 7, false)))
        set("appGridAdapter", adapter)
        set("computer", computer)
        set("uuidString", "host-id")
        set("prefConfig", PreferenceConfiguration())
        set("managerBinder", binder)
        set("inForeground", true)
        // What onCreate took off the Hosts launch; NovaConfirmationTest holds that it is taken once.
        set("pairedWith", "Paired with Den")
        NovaSnackbar.dismissActive()
        // As in the existing isolated Game fixtures, attach the window without starting services.
        controller.visible()
        focus(true)
    }

    @After fun tearDown() = NovaSnackbar.dismissActive()

    @Test fun aPairingIsSaidOnceItsLoadingWindowHasReallyClosed() {
        call("loadAppsBlocking")
        val surfaces = NovaSurfaces.existing(activity)!!
        val window = ShadowDialog.getLatestDialog()
        assertTrue("the first load is a Busy page", surfaces.states.value.any { it is NovaStatePage.Busy })
        assertTrue("in its own window", window.isShowing)
        focus(true)
        assertNull("focus before any list is on screen says nothing", active())
        focus(false)

        val listener = pollListener()
        computer.rawAppList = LIST
        listener.notifyComputerUpdated(computer)

        assertNull("the list's arrival asked the Busy page to go", field("blockingLoadSpinner"))
        assertTrue("which took it off the states at once", surfaces.states.value.isEmpty())
        assertTrue("while its window stays for the page's minimum time and exit", window.isShowing)
        assertNotNull(surfaces.controllerInputOwner)
        assertNull("nothing is said under it", active())

        surfaces.onWindowIdle()
        assertFalse("the window really closed", window.isShowing)
        assertNull(surfaces.controllerInputOwner)
        assertNull("it is the focus coming back that says it", active())

        focus(true)
        val said = active()
        assertEquals("Paired with Den", said?.let { text(it) })

        // The next list, and focus coming and going, say nothing more.
        computer.rawAppList = LIST.replace("<App>", "\n<App>")
        listener.notifyComputerUpdated(computer)
        focus(false)
        focus(true)
        assertSame(said, active())
    }

    @Test fun aListNothingCoversSaysItAtOnce() {
        // A saved list is on screen as the grid attaches, with no Busy page or window over it.
        call("onAppListShown")
        assertEquals("Paired with Den", active()?.let { text(it) })
    }

    @Test fun aDelayedListAfterPauseKeepsThePairingUntilForegroundAndFocusReturn() {
        val listener = pollListener()
        call("onPause")
        assertEquals(false, field("inForeground"))
        // Pause can arrive before window focus loss. Foreground is an independent guard.
        assertTrue(activity.hasWindowFocus())
        computer.rawAppList = LIST
        listener.notifyComputerUpdated(computer)

        assertNull("the callback in the paused activity is silent", active())
        assertEquals("Paired with Den", field("pairedWith"))
        assertEquals(true, field("appListShown"))

        // The foreground state onResume restores, without this isolated fixture's theme/service work.
        set("inForeground", true)
        focus(false)
        focus(true)
        val said = active()
        assertEquals("Paired with Den", said?.let { text(it) })
        assertNull(field("pairedWith"))

        computer.rawAppList = LIST.replace("<App>", "\n<App>")
        listener.notifyComputerUpdated(computer)
        focus(false)
        focus(true)
        assertSame("the later list and focus return cannot replay it", said, active())
    }

    @Test fun anUnfocusedCachedGridKeepsThePairingUntilItsWindowHasFocus() {
        focus(false)
        assertEquals(true, field("inForeground"))
        assertNull("no Nova modal is hiding this window", NovaSurfaces.existing(activity)?.controllerInputOwner)
        // The same hook the service's cached-grid attachment calls, after the window lost focus.
        call("onAppListShown")

        assertNull("a list callback alone cannot confirm a covered window", active())
        assertEquals("Paired with Den", field("pairedWith"))

        focus(true)
        val said = active()
        assertEquals("Paired with Den", said?.let { text(it) })
        assertNull(field("pairedWith"))
        call("onAppListShown")
        focus(false)
        focus(true)
        assertSame(said, active())
    }

    private fun focus(hasFocus: Boolean) {
        val root = ReflectionHelpers.callInstanceMethod<Any>(activity.window.decorView, "getViewRootImpl")
        assertNotNull("the activity window is attached before focus is delivered", root)
        controller.windowFocusChanged(hasFocus)
        assertEquals("the framework window, not only the callback, changed focus", hasFocus, activity.hasWindowFocus())
    }

    private fun set(name: String, value: Any) {
        AppView::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun field(name: String): Any? =
        AppView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun call(name: String) {
        AppView::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    private fun pollListener(): ComputerManagerListener {
        call("startComputerUpdates")
        return mockingDetails(binder).invocations.single { it.method.name == "startPolling" }
            .arguments[0] as ComputerManagerListener
    }

    /** The one snackbar NovaSnackbar keeps on screen. */
    private fun active(): Snackbar? = NovaSnackbar::class.java.getDeclaredField("activeSnackbar").run {
        isAccessible = true
        get(null) as Snackbar?
    }

    private fun text(snackbar: Snackbar) =
        snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString()

    private companion object {
        const val LIST = "<root status_code=\"200\"><App><AppTitle>Example game</AppTitle><UUID>game-id</UUID><ID>7</ID></App></root>"
    }
}
