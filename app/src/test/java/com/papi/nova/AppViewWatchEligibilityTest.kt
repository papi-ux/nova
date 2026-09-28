package com.papi.nova

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.grid.AppGridAdapter
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewWatchEligibilityTest {
    private class Fixture(watchable: Boolean?, owned: Boolean? = false) {
        val activity = Robolectric.buildActivity(AppView::class.java).get()
        val app = AppView.AppObject(NvApp("Example game", "game-id", 7, false))
        val computer = ComputerDetails().apply {
            uuid = "host-id"
            runningGameId = 7
            currentGameOwnedByClient = owned
            currentGameWatchable = watchable
        }
        val card = LinearLayout(activity).apply { id = R.id.recently_played_card }
        val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java)

        init {
            activity.setTheme(R.style.AppTheme)
            for (viewId in listOf(R.id.recently_played_name, R.id.recently_played_kicker,
                R.id.recently_played_meta, R.id.recently_played_action, R.id.recently_played_end_session)) {
                card.addView(TextView(activity).apply { id = viewId })
            }
            activity.setContentView(card)
            val adapter = mock(AppGridAdapter::class.java)
            `when`(adapter.itemCount).thenReturn(1)
            `when`(adapter.getItem(0)).thenReturn(app)
            set("appGridAdapter", adapter)
            set("computer", computer)
            set("uuidString", "host-id")
            set("lastRunningAppId", 7)
            set("prefConfig", PreferenceConfiguration())
            set("managerBinder", binder)
        }

        fun set(name: String, value: Any) {
            AppView::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        }

        fun refresh() {
            AppView::class.java.getDeclaredMethod("updateUiWithServerInfo", ComputerDetails::class.java)
                .apply { isAccessible = true }.invoke(activity, computer)
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun sheet(selected: AppView.AppObject = app): List<TextView> {
            AppView::class.java.getDeclaredMethod("showAppBottomSheet", AppView.AppObject::class.java)
                .apply { isAccessible = true }.invoke(activity, selected)
            val actions = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.sheet_actions)
            return (0 until actions.childCount).mapNotNull { actions.getChildAt(it) as? TextView }
        }

        fun label() = activity.findViewById<TextView>(R.id.recently_played_action).text.toString()

        fun pollListener(): ComputerManagerListener {
            set("inForeground", true)
            AppView::class.java.getDeclaredMethod("startComputerUpdates").apply { isAccessible = true }.invoke(activity)
            return mockingDetails(binder).invocations.single { it.method.name == "startPolling" }
                .arguments[0] as ComputerManagerListener
        }
    }

    @Test
    fun anUnstreamedGameOffersNeitherWatchNorControlOfItsOwner() {
        val fixture = Fixture(watchable = false)
        fixture.refresh()
        assertEquals("In Use", fixture.label())
        assertFalse(fixture.card.isEnabled)
        assertEquals(View.GONE, fixture.activity.findViewById<View>(R.id.recently_played_end_session).visibility)
        for (selected in listOf(fixture.app, AppView.AppObject(NvApp("Other game", "other", 8, false)))) {
            val labels = fixture.sheet(selected).filter { it.isEnabled }.map { it.text.toString() }
            assertFalse(labels.any { it.startsWith("Watch") || it.startsWith("Resume") || it.startsWith("Quit") || it == "End Session" })
            ShadowDialog.getLatestDialog().dismiss()
        }
    }

    @Test
    fun liveAndOlderHostsKeepWatchWhileOwnersKeepResume() {
        for (watchable in listOf(true, null)) {
            val fixture = Fixture(watchable)
            fixture.refresh()
            assertEquals("Watch Stream", fixture.label())
            assertTrue(fixture.sheet().any { it.text == "Watch Stream" && it.isEnabled })
            ShadowDialog.getLatestDialog().dismiss()
        }
        val owner = Fixture(watchable = false, owned = true)
        owner.refresh()
        assertEquals("Resume", owner.label())
        assertTrue(owner.card.isEnabled)
        assertTrue(owner.sheet().any { it.text == "Resume Stream" })
        ShadowDialog.getLatestDialog().dismiss()
    }

    @Test
    fun sameGameRefreshUpdatesWatchabilityAndOwnership() {
        val fixture = Fixture(watchable = true)
        val listener = fixture.pollListener()
        fixture.refresh()
        assertTrue(fixture.app.isRunning)
        assertEquals("Watch Stream", fixture.label())
        fixture.computer.currentGameWatchable = false
        listener.notifyComputerUpdated(fixture.computer)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("In Use", fixture.label())
        fixture.computer.currentGameOwnedByClient = true
        listener.notifyComputerUpdated(fixture.computer)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Resume", fixture.label())
        assertTrue(fixture.card.isEnabled)
    }

    @Test
    fun aWatchActionOpenedEarlierRechecksTheCurrentHostAnswer() {
        val fixture = Fixture(watchable = true)
        val watch = fixture.sheet().first { it.text == "Watch Stream" }
        fixture.computer.currentGameWatchable = false
        watch.performClick()
        assertNull(shadowOf(fixture.activity).nextStartedActivity)
        assertEquals("Nobody is streaming this game, so there is nothing to watch.", ShadowToast.getTextOfLatestToast())
    }
}
