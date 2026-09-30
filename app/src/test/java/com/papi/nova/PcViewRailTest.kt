package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The Hosts rail in landscape: A on its toggle left the caption naming the old action (audit N3),
 * and its labels came back while it was still widening, so "Add Server" wrapped and was cut for a
 * frame (audit P2).
 */
@Config(
    sdk = [33],
    qualifiers = "w900dp-h480dp-land",
    shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class],
)
@RunWith(RobolectricTestRunner::class)
class PcViewRailTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(collapsed: Boolean): PcView {
        context.getSharedPreferences("GlPreferences", 0).edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()
        val app = Shadows.shadowOf(context as Application)
        app.setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            mock(ComputerManagerService.ComputerManagerBinder::class.java),
        )
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("nova_dashboard_rail_collapsed", collapsed)
            .commit()
        return Robolectric.buildActivity(PcView::class.java).setup().get().also { idle() }
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(millis: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun aOnTheRailToggleRedrawsItsCaptionInPlace() {
        val hosts = open(collapsed = false)
        val toggle = hosts.findViewById<View>(R.id.dashboardRailToggle)
        val caption = hosts.findViewById<TextView>(R.id.topActionFocusLabel)
        assertTrue(toggle.requestFocus())
        assertEquals(context.getString(R.string.pcview_rail_collapse), caption.text.toString())

        toggle.performClick()
        idleFor(RAIL_SETTLE_MS)

        assertTrue("A leaves focus on the toggle", toggle.hasFocus())
        assertEquals(context.getString(R.string.pcview_rail_expand), caption.text.toString())
        assertEquals(View.VISIBLE, caption.visibility)
    }

    @Test
    fun expandingPutsTheLabelsBackOnceTheRailHasItsWidth() {
        val hosts = open(collapsed = true)
        val addServer = hosts.findViewById<TextView>(R.id.actionAddServer)
        assertEquals("", addServer.text.toString())

        hosts.findViewById<View>(R.id.dashboardRailToggle).performClick()
        assertEquals("no label in a rail still widening", "", addServer.text.toString())

        idleFor(RAIL_SETTLE_MS)
        assertEquals(context.getString(R.string.pcview_quick_add_server), addServer.text.toString())
    }

    private companion object {
        const val RAIL_SETTLE_MS = 400L

        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
