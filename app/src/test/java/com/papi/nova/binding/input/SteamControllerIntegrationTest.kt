package com.papi.nova.binding.input

import android.app.Activity
import android.util.SparseArray
import android.view.InputDevice
import com.papi.nova.binding.input.driver.*
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.GameGestures
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
import org.robolectric.shadows.InputDeviceBuilder
import java.util.concurrent.ScheduledExecutorService

@Config(sdk = [30], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class SteamControllerIntegrationTest {
    private lateinit var handler: ControllerHandler
    private lateinit var connection: NvConnection
    private lateinit var gestures: GameGestures
    private lateinit var link: Link
    private lateinit var pad: SteamController
    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        val features = mutableListOf<ByteArray>()
        var closes = 0
        override fun start(observer: SteamControllerTransport.Observer): Boolean { this.observer = observer; observer.onReady(); return true }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) { features += report; completion(true) }
        override fun writeRumble(report: ByteArray) = Unit
        override fun close() { closes++ }
        fun state(raw: Int = 0) { observer.onReport(ByteArray(46).also {
            it[0] = 0x42; for (i in 0..3) it[2 + i] = (raw ushr (8 * i)).toByte()
        }) }
    }
    @Before fun setup() {
        connection = mock(NvConnection::class.java); gestures = mock(GameGestures::class.java)
        handler = ControllerHandler(Robolectric.buildActivity(Activity::class.java).setup().get(), connection,
            gestures, PreferenceConfiguration().apply { multiController = true; gamepadMotionSensors = true })
        link = Link()
        pad = SteamController(91, handler, SteamControllerProtocol.PUCK_PID, link, "integration-puck:2",
            scheduler = mock(ScheduledExecutorService::class.java))
        assertTrue(pad.start())
    }
    @After fun cleanup() { pad.stop(); handler.destroy() }
    private fun flags() = mockingDetails(connection).invocations.filter { it.method.name == "sendControllerInput" }
        .map { (it.arguments[2] as Number).toInt() }
    @Suppress("UNCHECKED_CAST")
    private fun context(): ControllerHandler.UsbDeviceContext = ControllerHandler::class.java.getDeclaredField("usbDeviceContexts").run {
        isAccessible = true; (get(handler) as SparseArray<ControllerHandler.UsbDeviceContext>)[91]
    }

    @Test fun nativeGuideTapUsesExistingDeferredGuideRoute() {
        link.state(0x10000); assertTrue(flags().all { it and ControllerPacket.SPECIAL_BUTTON_FLAG == 0 })
        link.state(0)
        assertTrue(flags().any { it and ControllerPacket.SPECIAL_BUTTON_FLAG != 0 })
        assertEquals(0, flags().last())
    }
    @Test fun nativeGuideStartOpensCommandCenterWithoutLeavingHostButtonsHeld() {
        link.state(0x10000); link.state(0x10040); link.state()
        verify(gestures, times(1)).showGameMenu(any())
        assertEquals(0, flags().last())
        assertTrue(flags().all { it and (ControllerPacket.SPECIAL_BUTTON_FLAG or ControllerPacket.PLAY_FLAG) == 0 })
    }
    @Test fun quickAccessIsLocalAndNeverSendsAnInventedHostChord() {
        link.state(0x10); link.state(0x10); link.state()
        verify(gestures, times(1)).showGameMenu(any())
        assertTrue(flags().all { it == 0 })
    }
    @Test fun quickAccessWhileGuideHeldStillOpensLocallyAndConsumesGuideRelease() {
        link.state(0x10000); link.state(0x10010); link.state()
        verify(gestures, times(1)).showGameMenu(any())
        assertTrue(flags().all { it == 0 })
    }
    @Test fun nativeImuOnAndroid11KeepsSteamTypeAndUsesControllerRatherThanPhone() {
        link.state()
        val arrival = mockingDetails(connection).invocations.single { it.method.name == "sendControllerArrivalEvent" }
        assertEquals(4, (arrival.arguments[2] as Number).toInt())
        assertEquals(MoonBridge.LI_CCAP_GYRO.toInt(), (arrival.arguments[4] as Number).toInt() and MoonBridge.LI_CCAP_GYRO.toInt())
        handler.handleSetMotionEventState(context().controllerNumber, MoonBridge.LI_MOTION_TYPE_GYRO, 100)
        assertArrayEquals(SteamControllerProtocol.motionEnabled(true), link.features.last())
        link.state()
        assertTrue(mockingDetails(connection).invocations.any { it.method.name == "sendControllerMotionEvent" })
    }
    @Test fun reassignStopsNativeMotionAndLateUnassignedMotionCannotReachOldPlayer() {
        link.state()
        handler.handleSetMotionEventState(context().controllerNumber, MoonBridge.LI_MOTION_TYPE_GYRO, 100)
        handler.reassignPlayers()
        assertArrayEquals(SteamControllerProtocol.motionEnabled(false), link.features.last())
        clearInvocations(connection)
        handler.reportControllerMotion(91, MoonBridge.LI_MOTION_TYPE_GYRO, 1f, 2f, 3f)
        assertTrue(mockingDetails(connection).invocations.none { it.method.name == "sendControllerMotionEvent" })
    }
    @Test fun lostGuideIsCancellationAndCannotTapGuideOrOpenMenu() {
        link.state(0x10000); clearInvocations(connection, gestures)
        link.observer.onLinkLost()
        assertTrue(flags().all { it and ControllerPacket.SPECIAL_BUTTON_FLAG == 0 })
        verifyNoInteractions(gestures)
        assertTrue(handler.players().isEmpty())
    }
    @Test fun streamStopClosesNativeTimerTransportAndOwnership() {
        link.state(); handler.stop()
        assertEquals(1, link.closes); assertTrue(pad.isClosed())
        clearInvocations(connection); link.state(1)
        verifyNoInteractions(connection)
    }
    @Test fun ownedLizardIsFilteredButUnrelatedMouseAndKernelGamepadRemainAvailable() {
        fun device(pid: Int, sources: Int) = InputDeviceBuilder.newBuilder().setId(pid)
            .setName("Controller").setVendorId(0x28de).setProductId(pid).setSources(sources).build()
        val lizard = device(0x1304, InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE)
        val other = device(0x1303, InputDevice.SOURCE_MOUSE)
        val gamepad = device(0x1304, InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD)
        assertFalse(SteamControllerOwnership.owns(lizard)); link.state()
        assertTrue(SteamControllerOwnership.owns(lizard)); assertFalse(SteamControllerOwnership.owns(other))
        assertFalse(SteamControllerOwnership.owns(gamepad)); pad.stop()
        assertFalse(SteamControllerOwnership.owns(lizard))
    }
}
