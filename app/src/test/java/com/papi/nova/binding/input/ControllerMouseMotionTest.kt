package com.papi.nova.binding.input

import android.app.Activity
import android.view.MotionEvent
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.GameGestures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class ControllerMouseMotionTest {
    private lateinit var handler: ControllerHandler
    private lateinit var connection: NvConnection
    private lateinit var preferences: PreferenceConfiguration
    private val contexts = mutableListOf<ControllerHandler.GenericControllerContext>()

    @Before
    fun setUp() {
        connection = mock(NvConnection::class.java)
        preferences = PreferenceConfiguration().apply {
            analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.NONE
            deadzonePercentage = 10
        }
        handler = ControllerHandler(
            Robolectric.buildActivity(Activity::class.java).setup().get(),
            connection, mock(GameGestures::class.java), preferences,
        )
    }

    @After
    fun tearDown() {
        contexts.forEach { it.destroy() }
        if (::handler.isInitialized) handler.destroy()
    }

    private fun context() = handler.GenericControllerContext().also {
        contexts.add(it)
        it.setControllerMouseEmulationActive(true)
    }

    private fun tick(context: ControllerHandler.GenericControllerContext, count: Int = 1) {
        repeat(count) { context.mouseEmulationRunnable.run() }
    }

    private fun moves(): List<Pair<Int, Int>> = mockingDetails(connection).invocations
        .filter { it.method.name == "sendMouseMove" }
        .map { (it.arguments[0] as Short).toInt() to (it.arguments[1] as Short).toInt() }

    private fun defaultContext() = (ControllerHandler::class.java.getDeclaredField("defaultContext").run {
        isAccessible = true
        get(handler)
    } as ControllerHandler.GenericControllerContext).also {
        contexts.add(it)
        it.setControllerMouseEmulationActive(true)
    }

    @Test
    fun smallHeldMovementAccumulatesInsteadOfDisappearingEveryTick() {
        val context = context()
        context.leftStickX = 6553 // 20% deflection: about half a pixel per 50 ms tick.
        tick(context, 10)
        assertEquals(5, moves().sumOf { it.first })
        assertTrue(moves().all { it.second == 0 && it.first > 0 })
    }

    @Test
    fun diagonalSubpixelComponentsPreserveBothDirections() {
        val context = context()
        context.leftStickX = 3277
        context.leftStickY = 3277
        tick(context, 8)
        assertEquals(listOf(1 to -1), moves())
    }

    @Test
    fun independentSticksDoNotCancelEachOthersFractionalMovement() {
        val context = context()
        context.leftStickX = 6553
        context.rightStickX = -6553
        tick(context, 2)
        assertEquals(listOf(1 to 0, -1 to 0), moves())
    }

    @Test
    fun controllersKeepSeparateFractionalMovement() {
        val first = context().apply { leftStickX = 6553 }
        val second = context().apply { leftStickX = 6553 }
        tick(first)
        tick(second)
        assertTrue(moves().isEmpty())
        tick(first)
        tick(second)
        assertEquals(listOf(1 to 0, 1 to 0), moves())
    }

    @Test
    fun returningToNeutralDiscardsTheOldFraction() {
        val context = context().apply { leftStickX = 6553 }
        tick(context)
        context.leftStickX = 0
        tick(context, 10)
        context.leftStickX = 6553
        tick(context)
        assertTrue(moves().isEmpty())
        tick(context)
        assertEquals(listOf(1 to 0), moves())
    }

    @Test
    fun reversingDirectionDoesNotSpendTicksRepayingTheOldFraction() {
        val context = context().apply { leftStickX = 6553 }
        tick(context)
        context.leftStickX = -6553
        tick(context, 2)
        assertEquals(listOf(-1 to 0), moves())
    }

    @Test
    fun togglingMouseModeDoesNotReuseAnOldFraction() {
        val context = context().apply { leftStickX = 6553 }
        tick(context)
        context.setControllerMouseEmulationActive(false)
        tick(context, 5)
        context.setControllerMouseEmulationActive(true)
        tick(context)
        assertTrue(moves().isEmpty())
        tick(context)
        assertEquals(listOf(1 to 0), moves())
    }

    @Test
    fun switchingAStickToScrollDiscardsItsPointerFraction() {
        val context = context().apply { leftStickX = 6553 }
        tick(context)
        preferences.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.LEFT
        tick(context)
        preferences.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.NONE
        tick(context)
        assertTrue(moves().isEmpty())
        tick(context)
        assertEquals(listOf(1 to 0), moves())
    }

    @Test
    fun normalFastMotionKeepsTheExistingCurveAndYAxisDirection() {
        val context = context().apply { leftStickX = 16383 }
        tick(context)
        context.leftStickX = 0
        context.leftStickY = 32766
        tick(context)
        assertEquals(listOf(8 to 0, 0 to -64), moves())
    }

    @Test
    fun fineModeMovesSmallDeflectionsByTheSelectedStepAndClearsNormalRemainders() {
        val context = context().apply { leftStickX = 6553 }
        tick(context)
        context.mouseEmulationXDown = true
        context.mouseEmulationPixelMultiplier = 4
        tick(context)
        context.mouseEmulationXDown = false
        tick(context)
        assertEquals(listOf(4 to 0), moves())
        tick(context)
        assertEquals(listOf(4 to 0, 1 to 0), moves())
    }

    @Test
    fun destroyedContextCannotSendMoreMovement() {
        val context = context().apply { leftStickX = 16383 }
        context.destroy()
        tick(context, 10)
        assertTrue(moves().isEmpty())
    }

    @Test
    fun configuredDeadzoneStillSuppressesDriftBeforeAccumulation() {
        val context = defaultContext()
        val motion = mock(MotionEvent::class.java)
        `when`(motion.deviceId).thenReturn(0)
        `when`(motion.getAxisValue(MotionEvent.AXIS_X)).thenReturn(0.09f)
        handler.handleMotionEvent(motion)
        tick(context, 100)
        assertTrue(moves().isEmpty())
        `when`(motion.getAxisValue(MotionEvent.AXIS_X)).thenReturn(0.2f)
        handler.handleMotionEvent(motion)
        tick(context, 2)
        assertEquals(listOf(1 to 0), moves())
    }

    @Test
    fun mouseButtonsStillClickWhileControllerPacketsStayNeutral() {
        defaultContext()
        handler.reportOscState(ControllerPacket.A_FLAG or ControllerPacket.B_FLAG, 0, 0, 0, 0, 0, 0)
        handler.reportOscState(0, 0, 0, 0, 0, 0, 0)
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
        val packets = mockingDetails(connection).invocations.filter { it.method.name == "sendControllerInput" }
        assertEquals(2, packets.size)
        assertTrue(packets.all { call -> call.arguments.drop(2).all { (it as Number).toInt() == 0 } })
    }

    @Test
    fun scrollingStickKeepsItsExistingScrollOutput() {
        preferences.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.RIGHT
        val context = context().apply { rightStickY = 16383 }
        tick(context)
        verify(connection).sendMouseHighResScroll(8)
        assertTrue(moves().isEmpty())
    }
}
