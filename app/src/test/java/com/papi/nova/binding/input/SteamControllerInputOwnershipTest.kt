package com.papi.nova.binding.input

import android.content.ComponentName
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.papi.nova.binding.input.driver.SteamController
import com.papi.nova.binding.input.driver.SteamControllerProtocol
import com.papi.nova.binding.input.driver.SteamControllerTransport
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.jni.MoonBridge
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

/** The same synchronous route handoff Game invokes, with no further report needed to release input. */
@Config(sdk = [30], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class SteamControllerInputOwnershipTest {
    private lateinit var lifecycle: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private lateinit var connection: NvConnection
    private lateinit var gestures: GameGestures
    private lateinit var handler: ControllerHandler
    private val pads = mutableListOf<SteamController>()

    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        val features = mutableListOf<ByteArray>()
        override fun start(observer: SteamControllerTransport.Observer): Boolean {
            this.observer = observer; observer.onReady(); return true
        }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) {
            features += report.copyOf(); completion(true)
        }
        override fun writeRumble(report: ByteArray) = Unit
        override fun close() = Unit
        fun state(buttons: Int = 0) = observer.onReport(ByteArray(46).also {
            it[0] = 0x42; for (i in 0..3) it[2 + i] = (buttons ushr (8 * i)).toByte()
        })
    }

    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = lifecycle.get()
        shadowOf(activity.application).declareComponentUnbindable(
            ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"))
        connection = mock(NvConnection::class.java); gestures = mock(GameGestures::class.java)
        handler = ControllerHandler(activity, connection, gestures,
            PreferenceConfiguration().apply { multiController = true; gamepadMotionSensors = true })
    }

    @After fun cleanup() {
        pads.forEach { it.stop() }; handler.destroy()
        NovaSurfaces.existing(activity)?.dispose()
        lifecycle.pause().stop().destroy()
    }

    private fun pad(id: Int): Link {
        val link = Link()
        val pad = SteamController(id, handler, SteamControllerProtocol.WIRED_PID, link, "ownership:$id",
            scheduler = mock(ScheduledExecutorService::class.java))
        pads += pad; assertTrue(pad.start()); return link
    }

    private fun report(buttons: Int, trigger: Float = 0f) =
        handler.reportControllerState(91, buttons, 0f, 0f, 0f, 0f, 0f, trigger)

    private fun calls(name: String) = mockingDetails(connection).invocations.filter { it.method.name == name }

    private fun showPanel() = NovaSurfaces.of(activity).apply {
        open(NovaCommonPage.Menu("ownership", "Command Center",
            listOf(NovaMenuItem.Action("action", "An action", onClick = {}))))
    }

    @Test fun idleModalEntryReleasesHostInputAndHeldInputsWaitForReleaseOnReturn() {
        val link = pad(91); link.state(1); link.state()
        val player = (calls("sendControllerArrivalEvent").single().arguments[0] as Number).toShort()
        handler.handleSetMotionEventState(player, MoonBridge.LI_MOTION_TYPE_GYRO, 100)
        val featuresBefore = link.features.map { it.toList() }
        report(ControllerPacket.A_FLAG, 0.8f)
        handler.reportControllerMouse(91, 0, 0, ControllerPacket.A_FLAG, 0)
        handler.reportControllerTouch(91, MoonBridge.LI_TOUCH_EVENT_DOWN, 0, 0.2f, 0.3f, 1f)
        val surfaces = showPanel()
        clearInvocations(connection)

        // Game yields before showing its dialog. No new hardware packet releases these states.
        handler.routeNativeToLocal { true }
        assertTrue(calls("sendControllerInput").single().arguments.drop(2).all { (it as Number).toInt() == 0 })
        assertEquals(1, calls("sendMouseButtonUp").size)
        assertEquals(MoonBridge.LI_TOUCH_EVENT_UP,
            (calls("sendControllerTouchEvent").single().arguments[1] as Number).toByte())
        assertTrue(calls("sendControllerMotionEvent").single().arguments.drop(2).all { (it as Number).toFloat() == 0f })
        assertEquals("route changes preserve the host's motion request", featuresBefore, link.features.map { it.toList() })
        clearInvocations(connection)
        report(ControllerPacket.A_FLAG, 0.8f)
        handler.reportControllerQuickMenu(91)
        verifyNoInteractions(gestures)
        assertTrue(calls("sendControllerInput").isEmpty())

        surfaces.panel.close(); surfaces.onWindowIdle()
        handler.routeNativeToHost()
        clearInvocations(connection)
        report(ControllerPacket.A_FLAG, 0.8f)
        handler.reportControllerMouse(91, 0, 0, ControllerPacket.A_FLAG, 0)
        handler.reportControllerTouch(91, MoonBridge.LI_TOUCH_EVENT_MOVE, 0, 0.4f, 0.5f, 1f)
        assertTrue(calls("sendControllerInput").all { call -> call.arguments.drop(2).all { (it as Number).toInt() == 0 } })
        assertTrue(calls("sendMouseButtonDown").isEmpty())
        assertTrue(calls("sendControllerTouchEvent").isEmpty())
        assertTrue(calls("sendControllerArrivalEvent").isEmpty())

        report(0)
        handler.reportControllerMouse(91, 0, 0, 0, 0)
        handler.reportControllerTouch(91, MoonBridge.LI_TOUCH_EVENT_UP, 0, 0.4f, 0.5f, 0f)
        report(ControllerPacket.A_FLAG, 0.8f)
        handler.reportControllerMouse(91, 0, 0, ControllerPacket.A_FLAG, 0)
        handler.reportControllerTouch(91, MoonBridge.LI_TOUCH_EVENT_DOWN, 0, 0.4f, 0.5f, 1f)
        assertTrue(calls("sendControllerInput").any { (it.arguments[2] as Number).toInt() and ControllerPacket.A_FLAG != 0 })
        assertTrue(calls("sendControllerInput").any { (it.arguments[4] as Number).toByte().toInt() and 255 > 0 })
        assertEquals(1, calls("sendMouseButtonDown").size)
        assertEquals(1, calls("sendControllerTouchEvent").size)
    }

    @Test fun focusLossDropsNativeReportsUntilTheOwnerReturnsAndFreshButtonsWork() {
        val link = pad(91); link.state(1); link.state()
        handler.nativeWindowLostFocus()
        clearInvocations(connection)
        link.state(8)
        assertTrue(calls("sendControllerInput").isEmpty())
        handler.routeNativeToHost(); link.state(8)
        assertTrue(calls("sendControllerInput").all { (it.arguments[2] as Number).toInt() == 0 })
        link.state(); link.state(8)
        assertTrue(calls("sendControllerInput").any { (it.arguments[2] as Number).toInt() and ControllerPacket.Y_FLAG != 0 })
    }

    @Test fun aChannelAddedToAnOpenMenuDoesNotActOnItsInitialHeldButton() {
        showPanel()
        val keys = mutableListOf<KeyEvent>()
        handler.routeNativeToLocal { keys += it; true }
        val link = pad(92)
        link.state(1); link.state(1)
        assertTrue("the first held report is fenced", keys.isEmpty())
        link.state(); link.state(1); link.state()
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), keys.map { it.action })
        assertTrue(keys.all { it.keyCode == KeyEvent.KEYCODE_BUTTON_A })
        assertTrue(calls("sendControllerInput").isEmpty())
    }
}
