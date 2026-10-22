package com.papi.nova.binding.input.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SteamControllerPointerAccumulatorTest {
    private fun pair(x: Int, y: Int) = Pair(x.toShort(), y.toShort())

    @Test
    fun twentySubpixelDeltasAccumulateInsteadOfTruncatingToZero() {
        val pad = SteamControllerPointerAccumulator()
        // 0.0005 of the pad is 0.6 pointer units and 0.48 scroll units: zero on its own.
        assertEquals(pair(0, 0), pad.move(0.0005f, 0.0005f))
        assertEquals(0, pad.scroll(0.0005f).toInt())

        var x = 0
        var y = 0
        var wheel = 0
        repeat(19) {
            val (dx, dy) = pad.move(0.0005f, 0.0005f)
            x += dx; y += dy
            wheel += pad.scroll(0.0005f)
        }

        assertEquals(12, x)
        assertEquals(12, y)
        assertEquals(9, wheel)
    }

    @Test
    fun signsAndReversalsPreserveTheTotal() {
        val pad = SteamControllerPointerAccumulator()
        var forward = 0
        repeat(10) { forward += pad.move(0.0005f, 0f).first }
        var back = 0
        repeat(10) { back += pad.move(-0.0005f, 0f).first }
        assertEquals(6, forward)
        assertEquals(-6, back)

        // Alternating tiny jitter never moves the pointer.
        val jitter = SteamControllerPointerAccumulator()
        repeat(20) { i -> assertEquals(0, jitter.move(if (i % 2 == 0) 0.0005f else -0.0005f, 0f).first.toInt()) }

        // A mixed sequence never drifts a whole unit from the exact scaled sum.
        val mixed = SteamControllerPointerAccumulator()
        val deltas = floatArrayOf(0.0003f, -0.0001f, 0.0009f, -0.0007f, -0.0004f, 0.002f, -0.0026f, 0.00005f, -0.0011f, 0.0008f)
        var exact = 0.0
        var emitted = 0
        for (delta in deltas) {
            exact += delta * 1200.0
            emitted += mixed.move(delta, -delta).first
            assertTrue("emitted $emitted vs exact $exact", abs(exact - emitted) < 1.0)
        }
    }

    @Test
    fun axesAndScrollAccumulateIndependently() {
        val pad = SteamControllerPointerAccumulator()
        var x = 0
        var y = 0
        var wheel = 0
        repeat(20) {
            val (dx, dy) = pad.move(0.0005f, -0.0005f)
            x += dx; y += dy
            wheel += pad.scroll(-0.0005f)
        }
        assertEquals(12, x)
        assertEquals(-12, y)
        assertEquals(-9, wheel)
        assertEquals(pair(0, 0), pad.move(0f, 0f))
    }

    @Test
    fun resetsClearOnlyTheirOwnFractions() {
        // 0.0008 leaves 0.96 pointer units; 0.001 leaves 0.96 scroll units.
        fun primed() = SteamControllerPointerAccumulator().also {
            assertEquals(pair(0, 0), it.move(0.0008f, -0.0008f))
            assertEquals(0, it.scroll(0.001f).toInt())
        }

        val kept = primed()
        assertEquals(pair(1, -1), kept.move(0.00005f, -0.00005f))
        assertEquals(1, kept.scroll(0.0001f).toInt())

        val pointer = primed()
        pointer.resetPointer()
        assertEquals(pair(0, 0), pointer.move(0.00005f, -0.00005f))
        assertEquals(1, pointer.scroll(0.0001f).toInt())

        val scroll = primed()
        scroll.resetScroll()
        assertEquals(pair(1, -1), scroll.move(0.00005f, -0.00005f))
        assertEquals(0, scroll.scroll(0.0001f).toInt())

        val all = primed()
        all.reset()
        assertEquals(pair(0, 0), all.move(0.00005f, -0.00005f))
        assertEquals(0, all.scroll(0.0001f).toInt())
    }

    @Test
    fun saturationClampsAndDiscardsTheExcess() {
        val pad = SteamControllerPointerAccumulator()
        assertEquals(pair(32767, -32767), pad.move(30f, -30f))
        // The 3233 units above the limit are not released afterwards.
        assertEquals(pair(0, 0), pad.move(0.0005f, -0.0005f))
        assertEquals(pair(32767, -32767), pad.move(Float.MAX_VALUE, -Float.MAX_VALUE))
        assertEquals(pair(0, 0), pad.move(0f, 0f))

        assertEquals(32767, pad.scroll(100f).toInt())
        assertEquals(-32767, pad.scroll(-Float.MAX_VALUE).toInt())
        assertEquals(0, pad.scroll(0.0005f).toInt())

        // Just below the limit, the fraction is still carried.
        val edge = SteamControllerPointerAccumulator()
        assertEquals(pair(32766, 0), edge.move(27.3058f, 0f))
        assertEquals(pair(1, 0), edge.move(0.0001f, 0f))
    }

    @Test
    fun nonFiniteDeltaClearsThatFractionAndEmitsZero() {
        val pad = SteamControllerPointerAccumulator()
        assertEquals(pair(0, 0), pad.move(0.0008f, 0.0008f))
        assertEquals(0, pad.scroll(0.001f).toInt())

        // x is rejected and loses its 0.96; y keeps its own and moves.
        assertEquals(pair(0, 1), pad.move(Float.NaN, 0.00005f))
        assertEquals(pair(0, 0), pad.move(0.00005f, 0f))

        for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val axis = SteamControllerPointerAccumulator()
            axis.move(0.0008f, 0.0008f)
            assertEquals(pair(0, 0), axis.move(bad, bad))
            assertEquals(pair(0, 0), axis.move(0.00005f, 0.00005f))

            val wheel = SteamControllerPointerAccumulator()
            wheel.scroll(0.001f)
            assertEquals(0, wheel.scroll(bad).toInt())
            assertEquals(0, wheel.scroll(0.0001f).toInt())
        }
    }
}
