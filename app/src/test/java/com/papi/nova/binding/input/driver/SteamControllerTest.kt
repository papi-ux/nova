package com.papi.nova.binding.input.driver

import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.jni.MoonBridge
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.ScheduledExecutorService

class SteamControllerTest {
    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        val features = mutableListOf<Pair<ByteArray, (Boolean) -> Unit>>()
        val rumble = mutableListOf<ByteArray>()
        var closes = 0
        var accepts = true
        override fun start(observer: SteamControllerTransport.Observer): Boolean { this.observer = observer; return accepts }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) { features += report.copyOf() to completion }
        override fun writeRumble(report: ByteArray) { rumble += report.copyOf() }
        override fun close() { closes++ }
        fun confirm(ok: Boolean = true) { features.removeAt(0).second(ok) }
    }
    private class Events : UsbDriverListener {
        val order = mutableListOf<String>()
        val states = mutableListOf<Int>()
        val motions = mutableListOf<Byte>()
        val touches = mutableListOf<Triple<Byte, Int, Float>>()
        val mouse = mutableListOf<Int>()
        override fun deviceAdded(controller: AbstractController) { order += "added" }
        override fun deviceRemoved(controller: AbstractController) { order += "removed" }
        override fun resetControllerState(controllerId: Int) { order += "reset" }
        override fun reportControllerState(controllerId: Int, buttonFlags: Int, leftStickX: Float, leftStickY: Float, rightStickX: Float, rightStickY: Float, leftTrigger: Float, rightTrigger: Float) { states += buttonFlags; order += "state" }
        override fun reportControllerMotion(controllerId: Int, motionType: Byte, motionX: Float, motionY: Float, motionZ: Float) { motions += motionType }
        override fun reportControllerTouch(controllerId: Int, eventType: Byte, pointerId: Int, x: Float, y: Float, pressure: Float) { touches += Triple(eventType, pointerId, x) }
        override fun reportControllerMouse(controllerId: Int, deltaX: Short, deltaY: Short, buttons: Int, scroll: Short) { mouse += buttons }
        override fun reportControllerQuickMenu(controllerId: Int) { order += "menu" }
    }
    private class Rig(game: Boolean = false, grips: IntArray = IntArray(4)) {
        val link = Link(); val events = Events()
        var now = 1_000_000_000L
        val pad = SteamController(7, events, SteamControllerProtocol.PUCK_PID, link, "test-puck:2", game, grips,
            mock(ScheduledExecutorService::class.java), { now })
        fun ready() { assertTrue(pad.start()); link.observer.onReady() }
        fun playing(report: ByteArray = report()) { ready(); link.observer.onReport(report); link.confirm() }
    }

    @Test fun waitsForActualFeatureWriteBeforeAdvertisingPlayer() {
        val r = Rig(); r.ready(); r.link.observer.onReport(report())
        assertFalse(r.pad.hasNativeOwnership()); assertTrue(r.events.order.isEmpty())
        r.link.confirm()
        assertEquals(listOf("added", "state"), r.events.order)
        assertTrue(r.pad.hasNativeOwnership())
    }
    @Test fun failedModeCommandNeverAddsPlayerAndClosesChannel() {
        val r = Rig(); r.ready(); r.link.observer.onReport(report()); r.link.confirm(false)
        assertTrue(r.events.order.isEmpty()); assertTrue(r.pad.isClosed()); assertEquals(1, r.link.closes)
    }
    @Test fun emptyPuckChannelDoesNotReservePlayer() {
        val r = Rig(); r.ready(); r.link.confirm(); assertFalse(r.pad.hasNativeOwnership())
        r.link.observer.onReport(byteArrayOf(0x46, 2)); assertTrue(r.events.order.isEmpty())
    }
    @Test fun malformedReportCannotCreatePlayer() {
        val r = Rig(); r.ready(); r.link.confirm(); r.link.observer.onReport(ByteArray(45))
        assertFalse(r.pad.hasNativeOwnership()); assertTrue(r.events.states.isEmpty())
    }
    @Test fun disconnectCancelsWithoutGuideTapThenReconnectAddsOnePlayer() {
        val r = Rig(); r.playing(report(0x00010001)); r.link.observer.onReport(byteArrayOf(0x46, 1))
        assertEquals(listOf("added", "state", "reset", "removed"), r.events.order)
        assertFalse(r.pad.hasNativeOwnership())
        r.link.observer.onReport(report()); r.link.confirm()
        assertEquals(2, r.events.order.count { it == "added" })
    }
    @Test fun lateFeatureConfirmationCannotReviveLostLink() {
        val r = Rig(); r.ready(); r.link.observer.onReport(report())
        r.link.observer.onLinkLost(); r.link.confirm()
        assertFalse(r.pad.hasNativeOwnership()); assertTrue(r.events.states.isEmpty())
    }
    @Test fun repeatedStopAndLateReportsCannotRecreateInput() {
        val r = Rig(); r.playing(); r.pad.stop(); r.pad.stop()
        val before = r.events.order.toList()
        r.link.observer.onReport(report(1)); r.link.observer.onReady()
        assertEquals(before, r.events.order); assertEquals(1, r.link.closes)
        assertEquals(1, before.count { it == "removed" })
    }
    @Test fun mouseClickIsReleasedBeforePlayerRemoval() {
        val r = Rig(); r.playing(report(0x00400000)); assertEquals(ControllerPacket.A_FLAG, r.events.mouse.last())
        r.link.observer.onLinkLost()
        assertEquals(0, r.events.mouse.last()); assertEquals(listOf("reset", "removed"), r.events.order.takeLast(2))
    }
    @Test fun gamePadsHaveSeparateContactsAndDoNotClaimDualPadWireCapability() {
        val r = Rig(game = true); r.playing(report(0x02200000))
        assertEquals(listOf(Triple(MoonBridge.LI_TOUCH_EVENT_DOWN, 0, 0.25f),
            Triple(MoonBridge.LI_TOUCH_EVENT_DOWN, 1, 0.75f)), r.events.touches)
        assertEquals(0, r.pad.getCapabilities().toInt() and 0x100)
        r.pad.stop()
        assertEquals(listOf(0, 1), r.events.touches.takeLast(2).map { it.second })
        assertTrue(r.events.touches.takeLast(2).all { it.first == MoonBridge.LI_TOUCH_EVENT_UP })
    }
    @Test fun gripsMapWithoutReleasingAnAlreadyHeldTarget() {
        val r = Rig(grips = intArrayOf(ControllerPacket.A_FLAG, 0, 0, 0))
        r.playing(report(0x81)); r.link.observer.onReport(report(1))
        assertEquals(listOf(ControllerPacket.A_FLAG, ControllerPacket.A_FLAG), r.events.states)
        assertEquals(0, r.pad.getSupportedButtonFlags() and ControllerPacket.PADDLE1_FLAG)
    }
    @Test fun quickMenuIsLocalAndEdgeTriggered() {
        val r = Rig(); r.playing(report(0x10)); r.link.observer.onReport(report(0x10))
        assertEquals(1, r.events.order.count { it == "menu" }); assertEquals(0, r.events.states.last())
        r.link.observer.onReport(report()); r.link.observer.onReport(report(0x10))
        assertEquals(2, r.events.order.count { it == "menu" })
    }
    @Test fun motionNeedsHostRequestAndConfirmedModeAndRespectsIndependentRates() {
        val r = Rig(); r.playing(); assertTrue(r.events.motions.isEmpty())
        r.pad.setMotionEventState(MoonBridge.LI_MOTION_TYPE_GYRO, 100)
        r.link.observer.onReport(report()); assertTrue(r.events.motions.isEmpty())
        r.link.confirm(); r.link.observer.onReport(report())
        r.now += 5_000_000; r.link.observer.onReport(report())
        r.now += 5_000_000; r.link.observer.onReport(report())
        assertEquals(listOf(MoonBridge.LI_MOTION_TYPE_GYRO, MoonBridge.LI_MOTION_TYPE_GYRO), r.events.motions)
        r.pad.setMotionEventState(MoonBridge.LI_MOTION_TYPE_GYRO, 0)
        r.now += 20_000_000; r.link.observer.onReport(report())
        assertEquals(2, r.events.motions.size)
    }
    @Test fun stopSendsZeroRumbleAndNeverTriggerRumbleCapability() {
        val r = Rig(); r.playing(); r.pad.rumble(123, 456); r.pad.stop(); r.pad.rumble(20, 20)
        assertEquals(2, r.link.rumble.size)
        assertArrayEquals(SteamControllerProtocol.rumble(0, 0), r.link.rumble.last())
        assertEquals(0, r.pad.getCapabilities().toInt() and MoonBridge.LI_CCAP_TRIGGER_RUMBLE.toInt())
    }
    @Test fun startupRefusalClosesWithoutObserverPlayerEvents() {
        val r = Rig(); r.link.accepts = false
        assertFalse(r.pad.start()); assertTrue(r.pad.isClosed()); assertTrue(r.events.order.isEmpty())
    }

    companion object {
        private fun report(buttons: Int = 0) = ByteArray(46).also {
            it[0] = 0x42
            for (i in 0..3) it[2 + i] = (buttons ushr (8 * i)).toByte()
        }
    }
}
