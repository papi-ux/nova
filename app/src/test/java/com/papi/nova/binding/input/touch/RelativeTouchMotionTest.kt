package com.papi.nova.binding.input.touch

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise final pointer travel at the actual touch-event and connection boundary. */
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class RelativeTouchMotionTest {
    private val fixtures = mutableListOf<Fixture>()

    private class Fixture(
        val connection: NvConnection,
        val preferences: PreferenceConfiguration,
        val context: RelativeTouchContext,
    ) {
        fun down(x: Int = 0, y: Int = 0) {
            context.setPointerCount(1)
            context.touchDownEvent(x, y, 1_000L, true)
        }

        fun move(x: Int, y: Int = 0) = context.touchMoveEvent(x, y, 1_050L)

        fun moves(): List<Pair<Int, Int>> = mockingDetails(connection).invocations
            .filter { it.method.name == "sendMouseMove" }
            .map { (it.arguments[0] as Short).toInt() to (it.arguments[1] as Short).toInt() }
            .filter { it.first != 0 || it.second != 0 }

        fun total() = moves().sumOf { it.first } to moves().sumOf { it.second }
    }

    private fun fixture(
        width: Int = 200,
        height: Int = 120,
        referenceWidth: Int = width,
        referenceHeight: Int = height,
        sensitivityX: Int = 100,
        sensitivityY: Int = sensitivityX,
    ): Fixture {
        val preferences = PreferenceConfiguration().apply {
            absoluteMouseMode = false
            touchPadSensitivity = sensitivityX
            touchPadYSensitity = sensitivityY
        }
        val view = View(ApplicationProvider.getApplicationContext<Context>()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, width, height)
        }
        val connection = mock(NvConnection::class.java)
        return Fixture(
            connection, preferences,
            RelativeTouchContext(connection, 0, referenceWidth, referenceHeight, view, preferences),
        ).also { fixtures.add(it) }
    }

    @After
    fun cancelOwnedGestures() = fixtures.forEach { it.context.cancelTouch() }

    @Test
    fun halfSensitivityHasTheSameTravelForOneOrManyPositiveEvents() {
        val many = fixture(sensitivityX = 50).apply { down() }
        val one = fixture(sensitivityX = 50).apply { down() }
        (1..10).forEach { many.move(it) }
        one.move(10)
        assertEquals(5 to 0, many.total())
        assertEquals(one.total(), many.total())
    }

    @Test
    fun negativeAxesKeepTheirIndependentFinalFractions() {
        val many = fixture(sensitivityX = 50, sensitivityY = 25).apply { down() }
        val one = fixture(sensitivityX = 50, sensitivityY = 25).apply { down() }
        (1..10).forEach { many.move(-it, -minOf(it, 8)) }
        one.move(-10, -8)
        assertEquals(-5 to -2, many.total())
        assertEquals(one.total(), many.total())
    }

    @Test
    fun defaultSensitivityDoesNotRoundTheGeometrySeparatelyForEveryEvent() {
        val many = fixture(width = 1920, referenceWidth = 1280).apply { down() }
        val one = fixture(width = 1920, referenceWidth = 1280).apply { down() }
        (1..30).forEach { many.move(it) }
        one.move(30)
        assertEquals(20 to 0, many.total())
        assertEquals(one.total(), many.total())
    }

    @Test
    fun geometryAndSensitivityAreAppliedTogetherBeforeQuantizing() {
        val many = fixture(referenceWidth = 300, sensitivityX = 50).apply { down() }
        val one = fixture(referenceWidth = 300, sensitivityX = 50).apply { down() }
        (1..8).forEach { many.move(it) }
        one.move(8)
        assertEquals(6 to 0, many.total())
        assertEquals(one.total(), many.total())
    }

    @Test
    fun reversingAContinuousGestureIntegratesSignedTravel() {
        val f = fixture(sensitivityX = 50).apply { down() }
        f.move(1)
        f.move(0)
        f.move(-1)
        f.move(-2)
        assertEquals(listOf(-1 to 0), f.moves())
    }

    @Test
    fun movingTheOtherAxisDoesNotDiscardPendingTravel() {
        val f = fixture(sensitivityX = 50).apply { down() }
        f.move(1)
        f.move(1, 1)
        f.move(2, 1)
        assertEquals(listOf(1 to 0), f.moves())
        f.move(2, 2)
        assertEquals(listOf(1 to 0, 0 to 1), f.moves())
    }

    @Test
    fun aNewGestureDoesNotSpendThePreviousGesturesRemainder() {
        val f = fixture(sensitivityX = 50).apply { down() }
        f.move(1)
        f.context.touchUpEvent(1, 0, 1_080L)
        f.down()
        f.move(1)
        assertEquals(emptyList<Pair<Int, Int>>(), f.moves())
        f.move(2)
        assertEquals(listOf(1 to 0), f.moves())
    }

    @Test
    fun cancellationResetsPendingTravelAndStillIgnoresCancelledMoves() {
        val f = fixture(sensitivityX = 50).apply { down() }
        f.move(1)
        f.context.cancelTouch()
        f.move(100)
        assertEquals(emptyList<Pair<Int, Int>>(), f.moves())
        f.down()
        f.move(1)
        assertEquals(emptyList<Pair<Int, Int>>(), f.moves())
        f.move(2)
        assertEquals(listOf(1 to 0), f.moves())
    }

    @Test
    fun switchingToScrollDiscardsPointerTravelAndKeepsTheScrollContract() {
        val f = fixture(sensitivityX = 50, sensitivityY = 100).apply { down() }
        f.move(1)
        f.context.setPointerCount(2)
        f.move(1, 40)
        val scrolls = mockingDetails(f.connection).invocations
            .filter { it.method.name == "sendMouseHighResScroll" }
            .map { (it.arguments[0] as Short).toInt() }
        assertEquals(listOf(200), scrolls)
        f.context.setPointerCount(1)
        f.move(2, 40)
        assertEquals(emptyList<Pair<Int, Int>>(), f.moves())
        f.move(3, 40)
        assertEquals(listOf(1 to 0), f.moves())
    }

    @Test
    fun switchingToAbsolutePreservesItsRouteAndDiscardsRelativeTravel() {
        val f = fixture(sensitivityX = 50).apply { down() }
        f.move(1)
        f.preferences.absoluteMouseMode = true
        f.move(2)
        val absolute = mockingDetails(f.connection).invocations
            .filter { it.method.name == "sendMouseMoveAsMousePosition" }
            .map { it.arguments.map { value -> (value as Short).toInt() } }
        assertEquals(listOf(listOf(1, 0, 200, 120)), absolute)
        f.preferences.absoluteMouseMode = false
        f.move(3)
        assertEquals(emptyList<Pair<Int, Int>>(), f.moves())
        f.move(4)
        assertEquals(listOf(1 to 0), f.moves())
    }
}
