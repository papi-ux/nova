package com.papi.nova.binding.input

import android.app.Activity
import android.util.SparseArray
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.GameGestures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class ControllerMouseButtonLifecycleTest {
    private lateinit var handler: ControllerHandler
    private lateinit var connection: NvConnection

    @Before
    fun setUp() {
        connection = mock(NvConnection::class.java)
        handler = ControllerHandler(Robolectric.buildActivity(Activity::class.java).setup().get(),
            connection, mock(GameGestures::class.java), PreferenceConfiguration())
    }

    @After
    fun tearDown() { if (::handler.isInitialized) handler.destroy() }

    @Suppress("UNCHECKED_CAST")
    private fun context(id: Int, slot: Short = id.toShort()): ControllerHandler.InputDeviceContext {
        val contexts = ControllerHandler::class.java.getDeclaredField("inputDeviceContexts").run {
            isAccessible = true
            get(handler) as SparseArray<ControllerHandler.InputDeviceContext>
        }
        return handler.InputDeviceContext().also {
            it.id = id
            it.controllerNumber = slot
            it.assignedControllerNumber = true
            contexts.put(id, it)
            it.setControllerMouseEmulationActive(true)
        }
    }

    private fun defaultContext() = ControllerHandler::class.java.getDeclaredField("defaultContext").run {
        isAccessible = true
        (get(handler) as ControllerHandler.GenericControllerContext).also { it.setControllerMouseEmulationActive(true) }
    }

    private fun report(context: ControllerHandler.GenericControllerContext, flags: Int) {
        context.inputMap = flags
        ControllerHandler::class.java.getDeclaredMethod("sendControllerInputPacket", ControllerHandler.GenericControllerContext::class.java).run {
            isAccessible = true
            invoke(handler, context)
        }
    }

    private fun buttons() = mockingDetails(connection).invocations
        .filter { it.method.name == "sendMouseButtonDown" || it.method.name == "sendMouseButtonUp" }
        .map { it.method.name to (it.arguments[0] as Number).toInt() }
    private fun down(button: Byte) = "sendMouseButtonDown" to button.toInt()
    private fun up(button: Byte) = "sendMouseButtonUp" to button.toInt()

    @Test
    fun disablingMouseModeReleasesBothHeldMouseButtonsOnce() {
        val context = context(1)
        report(context, ControllerPacket.A_FLAG or ControllerPacket.B_FLAG)
        context.setControllerMouseEmulationActive(false)
        context.setControllerMouseEmulationActive(false)
        context.destroy()
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), down(MouseButtonPacket.BUTTON_RIGHT),
            up(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_RIGHT)), buttons())
    }

    @Test
    fun unpluggingAControllerReleasesItsHeldClick() {
        val context = context(1)
        report(context, ControllerPacket.A_FLAG)
        handler.onInputDeviceRemoved(1)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT)), buttons())
    }

    @Test
    fun reenablingAfterAReleaseOutsideMouseModeAllowsTheNextClick() {
        val context = context(1)
        report(context, ControllerPacket.A_FLAG)
        context.setControllerMouseEmulationActive(false)
        report(context, 0)
        context.setControllerMouseEmulationActive(true)
        report(context, ControllerPacket.A_FLAG)
        report(context, 0)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT),
            down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT)), buttons())
    }

    @Test
    fun anotherMouseControllerKeepsItsClickWhenOneOwnerStops() {
        val first = context(1)
        val second = context(2)
        report(first, ControllerPacket.A_FLAG)
        report(second, ControllerPacket.A_FLAG)
        first.setControllerMouseEmulationActive(false)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT)), buttons())
        report(second, 0)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT)), buttons())
    }

    @Test
    fun leftAndRightButtonsHaveIndependentOwners() {
        val first = context(1)
        val second = context(2)
        report(first, ControllerPacket.A_FLAG or ControllerPacket.B_FLAG)
        report(second, ControllerPacket.A_FLAG)
        first.destroy()
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), down(MouseButtonPacket.BUTTON_RIGHT),
            up(MouseButtonPacket.BUTTON_RIGHT)), buttons())
        second.destroy()
        assertEquals(up(MouseButtonPacket.BUTTON_LEFT), buttons().last())
    }

    @Test
    fun settingTheCurrentModeAgainDoesNotReleaseAHeldClick() {
        val context = context(1)
        report(context, ControllerPacket.A_FLAG)
        context.setControllerMouseEmulationActive(true)
        report(context, ControllerPacket.A_FLAG)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT)), buttons())
        report(context, 0)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT)), buttons())
    }

    @Test
    fun splitHidContextsSharingAPlayerSlotDoNotDuplicateOrStrandClicks() {
        val first = context(1, 0)
        val second = context(2, 0)
        report(first, ControllerPacket.A_FLAG)
        report(second, ControllerPacket.A_FLAG)
        report(first, 0)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT)), buttons())
        report(second, 0)
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT)), buttons())
    }

    @Test
    fun anInactiveMouseContextCannotBorrowAGamepadClickFromTheSamePlayerSlot() {
        val mouse = context(1, 0)
        val gamepad = context(2, 0)
        gamepad.setControllerMouseEmulationActive(false)
        report(gamepad, ControllerPacket.A_FLAG)
        report(mouse, 0)
        assertEquals(emptyList<Pair<String, Int>>(), buttons())
    }

    @Test
    fun aMouseControllerDoesNotBorrowTheDefaultGamepadContextsClick() {
        val gamepad = defaultContext()
        gamepad.setControllerMouseEmulationActive(false)
        handler.reportOscState(ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0)
        val mouse = context(1, 0)
        report(mouse, 0)
        assertEquals(emptyList<Pair<String, Int>>(), buttons())
    }

    @Test
    fun disablingFineModeWhileOffDoesNotLeaveItLatchedWhenMouseModeReturns() {
        val context = context(1)
        report(context, ControllerPacket.X_FLAG)
        context.setControllerMouseEmulationActive(false)
        report(context, 0)
        context.setControllerMouseEmulationActive(true)
        assertFalse(context.mouseEmulationXDown)
    }

    @Test
    fun handlerStopRetiresTheDefaultContextAndReleasesItsMouseButton() {
        val context = defaultContext()
        handler.reportOscState(ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0)
        context.leftStickX = 16383
        handler.stop()
        context.mouseEmulationRunnable.run()
        assertFalse(context.isControllerMouseEmulationActive())
        assertEquals(listOf(down(MouseButtonPacket.BUTTON_LEFT), up(MouseButtonPacket.BUTTON_LEFT)), buttons())
        assertEquals(0, mockingDetails(connection).invocations.count { it.method.name == "sendMouseMove" })
    }

    @Test
    fun stoppedOrDestroyedContextsCannotReenterMouseModeOrSendClicks() {
        val context = defaultContext()
        handler.stop()
        context.setControllerMouseEmulationActive(true)
        handler.reportOscState(ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0)
        assertFalse(context.isControllerMouseEmulationActive())
        assertEquals(emptyList<Pair<String, Int>>(), buttons())
    }
}
