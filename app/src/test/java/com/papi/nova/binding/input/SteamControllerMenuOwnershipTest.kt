package com.papi.nova.binding.input

import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import com.papi.nova.binding.input.driver.SteamController
import com.papi.nova.binding.input.driver.SteamControllerProtocol
import com.papi.nova.binding.input.driver.SteamControllerTransport
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.GameGestures
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.concurrent.ScheduledExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Drive actual native reports through ControllerHandler while a Nova panel owns a real window. */
@Config(sdk = [30], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class SteamControllerMenuOwnershipTest {
    private lateinit var lifecycle: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private lateinit var connection: NvConnection
    private lateinit var handler: ControllerHandler
    private lateinit var pad: SteamController
    private lateinit var link: Link

    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        override fun start(observer: SteamControllerTransport.Observer): Boolean {
            this.observer = observer
            observer.onReady()
            return true
        }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) = completion(true)
        override fun writeRumble(report: ByteArray) = Unit
        override fun close() = Unit
        fun state(buttons: Int) = observer.onReport(ByteArray(46).also { report ->
            report[0] = 0x42
            for (i in 0..3) report[2 + i] = (buttons ushr (8 * i)).toByte()
        })
    }

    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = lifecycle.get()
        connection = mock(NvConnection::class.java)
        handler = ControllerHandler(activity, connection, mock(GameGestures::class.java),
            PreferenceConfiguration().apply { multiController = true })
        link = Link()
        pad = SteamController(91, handler, SteamControllerProtocol.WIRED_PID, link, "menu-wired",
            scheduler = mock(ScheduledExecutorService::class.java))
        assertTrue(pad.start())
        link.state(1)
        link.state(0)
        clearInvocations(connection)
    }

    @After fun cleanup() {
        pad.stop()
        handler.destroy()
        NovaSurfaces.existing(activity)?.dispose()
        lifecycle.pause().stop().destroy()
    }

    private fun flags(): List<Int> = mockingDetails(connection).invocations
        .filter { it.method.name == "sendControllerInput" }
        .map { (it.arguments[2] as Number).toInt() }

    private fun panel(): ComponentDialog {
        NovaSurfaces.of(activity).open(NovaCommonPage.Menu("test-menu", "Command Center",
            listOf(NovaMenuItem.Action("test-action", "An action", onClick = {}))))
        return ShadowDialog.getLatestDialog() as ComponentDialog
    }

    @Test fun panelOwnedFaceButtonsNeverReachTheHostGame() {
        panel()
        link.state(8) // physical Y
        link.state(0)
        assertTrue("UI-owned Y must not reach the host", flags().all { it == 0 })
    }

    @Test fun nativeDpadAndAcceptNavigateTheActualPanelWindow() {
        val dialog = panel()
        var actions = 0
        val first = Button(activity).apply { text = "First"; isFocusableInTouchMode = true }
        val second = Button(activity).apply {
            text = "Second"; isFocusableInTouchMode = true
            setOnClickListener { actions++ }
        }
        // Keep the production panel window and key gate; use ordinary focus targets so this
        // input-routing regression does not depend on advancing a separate Compose clock.
        dialog.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(first, LinearLayout.LayoutParams(240, 80))
            addView(second, LinearLayout.LayoutParams(240, 80))
        })
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 240, 320)
        // A JVM dialog has no window manager to give it focus. Deliver that actual platform
        // transition, rather than bypassing the production focused-window guard.
        val root = ReflectionHelpers.callInstanceMethod<Any>(decor, "getViewRootImpl")
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("fixture has an attached, focused dialog", decor.hasWindowFocus())
        first.requestFocus()
        link.state(0x400) // down
        link.state(0)
        assertTrue("the native D-pad must move local focus", second.isFocused)
        link.state(1)
        assertEquals("A acts on release", 0, actions)
        link.state(0)
        assertEquals(1, actions)
        assertTrue(flags().all { it == 0 })
    }

    @Test fun closingThePanelDoesNotSendItsHeldAcceptButtonToTheGame() {
        panel()
        link.state(1)
        NovaSurfaces.of(activity).panel.close()
        NovaSurfaces.of(activity).onWindowIdle()
        clearInvocations(connection)
        link.state(1)
        assertTrue("a press begun in UI must not leak across dismissal", flags().all { it == 0 })
        link.state(0)
        link.state(1)
        assertTrue("a fresh press must return to the game", flags().any { it and ControllerPacket.A_FLAG != 0 })
    }

    @Test fun nativeTrackpadMouseCannotMoveOrClickTheGameBehindThePanel() {
        panel()
        handler.reportControllerMouse(91, 12, 8, ControllerPacket.A_FLAG, 120)
        assertTrue("native pointer input belongs to UI while its panel is open",
            mockingDetails(connection).invocations.none {
                it.method.name in setOf("sendMouseMove", "sendMouseButtonDown", "sendMouseHighResScroll")
            })
    }
}
