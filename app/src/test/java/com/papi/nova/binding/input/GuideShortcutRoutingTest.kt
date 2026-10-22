package com.papi.nova.binding.input

import android.app.Activity
import android.view.KeyEvent
import com.papi.nova.binding.input.driver.SteamController
import com.papi.nova.binding.input.driver.SteamControllerProtocol
import com.papi.nova.binding.input.driver.SteamControllerTransport
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.GameGestures
import java.util.concurrent.ScheduledExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Assert actual host packet ordering, rather than only shortcut-state return values. */
@Config(sdk = [30], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class GuideShortcutRoutingTest {
    private lateinit var handler: ControllerHandler
    private lateinit var connection: NvConnection
    private lateinit var gestures: GameGestures
    private var pad: SteamController? = null

    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        override fun start(observer: SteamControllerTransport.Observer): Boolean {
            this.observer = observer; observer.onReady(); return true
        }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) = completion(true)
        override fun writeRumble(report: ByteArray) = Unit
        override fun close() = Unit
        fun state(buttons: Int) = observer.onReport(ByteArray(46).also {
            it[0] = 0x42
            for (i in 0..3) it[2+i] = (buttons ushr (8*i)).toByte()
        })
    }

    @Before fun setup() {
        connection = mock(NvConnection::class.java)
        gestures = mock(GameGestures::class.java)
        handler = ControllerHandler(Robolectric.buildActivity(Activity::class.java).setup().get(),
            connection, gestures, PreferenceConfiguration().apply { guideShortcuts = false })
    }
    @After fun cleanup() { pad?.stop(); handler.destroy() }

    private fun flags(): List<Int> = mockingDetails(connection).invocations
        .filter { it.method.name == "sendControllerInput" }
        .map { (it.arguments[2] as Number).toInt() }
        .fold(mutableListOf<Int>()) { out, value -> out.apply { if (lastOrNull() != value) add(value) } }

    private fun native(): Link = Link().also { link ->
        pad = SteamController(91, handler, SteamControllerProtocol.WIRED_PID, link, "guide-routing",
            scheduler = mock(ScheduledExecutorService::class.java)).also { assertTrue(it.start()) }
    }

    @Test fun nativeGuideDownAndBothReleaseOrdersReachTheHostImmediately() {
        val link = native()
        link.state(0x10000)
        assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG), flags())
        link.state(0x10001); link.state(1); link.state(0)
        assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG,
            ControllerPacket.SPECIAL_BUTTON_FLAG or ControllerPacket.A_FLAG, ControllerPacket.A_FLAG, 0), flags())
        clearInvocations(connection)
        link.state(0x10000); link.state(0x10001); link.state(0x10000); link.state(0)
        assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG,
            ControllerPacket.SPECIAL_BUTTON_FLAG or ControllerPacket.A_FLAG, ControllerPacket.SPECIAL_BUTTON_FLAG, 0), flags())
        verifyNoInteractions(gestures)
    }

    @Test fun nativeGuideStartAndGuideYAreHostChordsWhenDisabled() {
        val link = native()
        for ((raw, host) in listOf(0x40 to ControllerPacket.PLAY_FLAG, 8 to ControllerPacket.Y_FLAG)) {
            clearInvocations(connection)
            link.state(0x10000); link.state(0x10000 or raw); link.state(0x10000); link.state(0)
            assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG,
                ControllerPacket.SPECIAL_BUTTON_FLAG or host, ControllerPacket.SPECIAL_BUTTON_FLAG, 0), flags())
        }
        verifyNoInteractions(gestures)
    }

    @Test fun androidControllerGuideChordsUseTheSameImmediatePacketRoute() {
        var time = 1000L
        fun key(code: Int, down: Boolean) {
            val event = KeyEvent(1L, time, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0, 0, 0)
            time += 100
            assertTrue(if (down) handler.handleButtonDown(event) else handler.handleButtonUp(event))
        }
        for ((code, host) in listOf(KeyEvent.KEYCODE_BUTTON_A to ControllerPacket.A_FLAG,
            KeyEvent.KEYCODE_BUTTON_START to ControllerPacket.PLAY_FLAG, KeyEvent.KEYCODE_BUTTON_Y to ControllerPacket.Y_FLAG)) {
            clearInvocations(connection)
            key(KeyEvent.KEYCODE_BUTTON_MODE, true)
            assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG), flags())
            key(code, true); key(code, false); key(KeyEvent.KEYCODE_BUTTON_MODE, false)
            assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG,
                ControllerPacket.SPECIAL_BUTTON_FLAG or host, ControllerPacket.SPECIAL_BUTTON_FLAG, 0), flags())
        }
        verifyNoInteractions(gestures)
    }

    @Test fun disablingGuideShortcutsStillDropsInputAfterFocusLossAndSuppressesHeldReturn() {
        val link = native()
        link.state(0x10000)
        handler.nativeWindowLostFocus()
        assertEquals(0, flags().last())
        clearInvocations(connection)
        link.state(0x10001)
        assertTrue(flags().isEmpty())
        handler.routeNativeToHost()
        clearInvocations(connection)
        link.state(0x10001)
        assertTrue(flags().all { it == 0 })
        link.state(0); clearInvocations(connection)
        link.state(0x10000)
        assertEquals(listOf(ControllerPacket.SPECIAL_BUTTON_FLAG), flags())
    }

    @Test fun nativeDisconnectReleasesHeldGuideWithoutInventingATap() {
        val link = native()
        link.state(0x10000); clearInvocations(connection, gestures)
        link.observer.onLinkLost()
        assertEquals(listOf(0), flags())
        verifyNoInteractions(gestures)
    }
}
