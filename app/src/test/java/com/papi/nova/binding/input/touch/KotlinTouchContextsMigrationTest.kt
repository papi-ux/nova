package com.papi.nova.binding.input.touch

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.preferences.PreferenceConfiguration
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyShort
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinTouchContextsMigrationTest {
    @Test
    fun touchContextClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/binding/input/touch/AbsoluteTouchContext",
            "src/main/java/com/papi/nova/binding/input/touch/RelativeTouchContext",
            "src/main/java/com/papi/nova/binding/input/touch/TrackpadContext"
        )

        for (path in paths) {
            assertFalse("$path should no longer be a Java source", File("$path.java").exists())
            assertTrue("$path should be migrated to Kotlin", File("$path.kt").exists())
        }
    }

    @Test
    fun touchContextClassesKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        AbsoluteTouchContext::class.java.getConstructor(
            NvConnection::class.java,
            intType,
            View::class.java,
            booleanType
        )
        RelativeTouchContext::class.java.getConstructor(
            NvConnection::class.java,
            intType,
            intType,
            intType,
            View::class.java,
            PreferenceConfiguration::class.java
        )
        TrackpadContext::class.java.getConstructor(NvConnection::class.java, intType)
        TrackpadContext::class.java.getConstructor(NvConnection::class.java, intType, booleanType, intType, intType)

        assertTrue(TouchContext::class.java.isAssignableFrom(AbsoluteTouchContext::class.java))
        assertTrue(TouchContext::class.java.isAssignableFrom(RelativeTouchContext::class.java))
        assertTrue(TouchContext::class.java.isAssignableFrom(TrackpadContext::class.java))

        assertTouchContextMethods(AbsoluteTouchContext::class.java)
        assertTouchContextMethods(RelativeTouchContext::class.java)
        assertTouchContextMethods(TrackpadContext::class.java)
    }

    @Test
    fun absoluteTouchKeepsTapScrollAndPointerCancelBehavior() {
        val tapConnection = mock(NvConnection::class.java)
        val tapContext = AbsoluteTouchContext(tapConnection, 0, measuredView(200, 120), false)
        assertEquals(0, tapContext.getActionIndex())

        assertTrue(tapContext.touchDownEvent(30, 40, 1_000L, true))
        assertTrue(tapContext.touchMoveEvent(80, 90, 1_050L))
        tapContext.touchUpEvent(80, 90, 1_100L)

        verify(tapConnection, atLeastOnce()).sendMousePosition(anyShort(), anyShort(), eq(200.toShort()), eq(120.toShort()))
        verify(tapConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(tapConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)

        val scrollConnection = mock(NvConnection::class.java)
        val scrollContext = AbsoluteTouchContext(scrollConnection, 1, measuredView(200, 120), false)
        assertTrue(scrollContext.touchDownEvent(10, 20, 2_000L, true))
        assertTrue(scrollContext.touchMoveEvent(10, 30, 2_010L))
        verify(scrollConnection).sendMouseHighResScroll(30.toShort())

        val cancelConnection = mock(NvConnection::class.java)
        val cancelContext = AbsoluteTouchContext(cancelConnection, 0, measuredView(200, 120), false)
        assertTrue(cancelContext.touchDownEvent(10, 10, 3_000L, true))
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS)

        cancelContext.setPointerCount(2)

        assertTrue(cancelContext.isCancelled())
        verify(cancelConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(cancelConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
    }

    @Test
    fun relativeTouchKeepsTapDragScrollAndMoveBehavior() {
        val moveConnection = mock(NvConnection::class.java)
        val moveContext = RelativeTouchContext(
            moveConnection,
            0,
            200,
            120,
            measuredView(200, 120),
            relativeTouchPreferences(false)
        )
        moveContext.setPointerCount(1)
        assertTrue(moveContext.touchDownEvent(10, 10, 1_000L, true))
        assertTrue(moveContext.touchMoveEvent(40, 60, 1_050L))
        verify(moveConnection).sendMouseMove(30.toShort(), 50.toShort())

        val tapConnection = mock(NvConnection::class.java)
        val tapContext = RelativeTouchContext(
            tapConnection,
            0,
            200,
            120,
            measuredView(200, 120),
            relativeTouchPreferences(false)
        )
        tapContext.setPointerCount(1)
        assertTrue(tapContext.touchDownEvent(12, 18, 2_000L, true))
        tapContext.touchUpEvent(12, 18, 2_050L)
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS)
        verify(tapConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(tapConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)

        val scrollConnection = mock(NvConnection::class.java)
        val scrollContext = RelativeTouchContext(
            scrollConnection,
            0,
            200,
            120,
            measuredView(200, 120),
            relativeTouchPreferences(false)
        )
        scrollContext.setPointerCount(2)
        assertTrue(scrollContext.touchDownEvent(10, 10, 3_000L, true))
        assertTrue(scrollContext.touchMoveEvent(10, 50, 3_050L))
        verify(scrollConnection).sendMouseHighResScroll(200.toShort())

        val dragConnection = mock(NvConnection::class.java)
        val dragContext = RelativeTouchContext(
            dragConnection,
            0,
            200,
            120,
            measuredView(200, 120),
            relativeTouchPreferences(false)
        )
        dragContext.setPointerCount(1)
        assertTrue(dragContext.touchDownEvent(20, 20, 4_000L, true))
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)

        dragContext.cancelTouch()

        assertTrue(dragContext.isCancelled())
        verify(dragConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(dragConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
    }

    @Test
    fun trackpadKeepsTapMoveScrollAndCancelBehavior() {
        val tapConnection = mock(NvConnection::class.java)
        val tapContext = TrackpadContext(tapConnection, 0)
        tapContext.setPointerCount(1)
        assertTrue(tapContext.touchDownEvent(10, 10, 1_000L, true))
        tapContext.touchUpEvent(10, 10, 1_050L)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        verify(tapConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(tapConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)

        val moveConnection = mock(NvConnection::class.java)
        val moveContext = TrackpadContext(moveConnection, 0, false, 100, 100)
        moveContext.setPointerCount(1)
        assertTrue(moveContext.touchDownEvent(10, 10, 2_000L, true))
        assertTrue(moveContext.touchMoveEvent(50, 10, 2_050L))
        verify(moveConnection).sendMouseMove(anyShort(), eq(0.toShort()))

        val scrollConnection = mock(NvConnection::class.java)
        val scrollContext = TrackpadContext(scrollConnection, 1, false, 100, 100)
        scrollContext.setPointerCount(2)
        assertTrue(scrollContext.touchDownEvent(10, 10, 3_000L, true))
        assertTrue(scrollContext.touchMoveEvent(10, 80, 3_050L))
        verify(scrollConnection).sendMouseHighResScroll(anyShort())

        val cancelConnection = mock(NvConnection::class.java)
        val cancelContext = TrackpadContext(cancelConnection, 0)
        cancelContext.setPointerCount(1)
        assertTrue(cancelContext.touchDownEvent(10, 10, 4_000L, true))
        cancelContext.touchUpEvent(10, 10, 4_050L)
        assertTrue(cancelContext.touchDownEvent(10, 10, 4_100L, true))

        cancelContext.cancelTouch()

        assertTrue(cancelContext.isCancelled())
        verify(cancelConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
        verify(cancelConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
    }

    private fun assertTouchContextMethods(contextClass: Class<out TouchContext>) {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!

        contextClass.getMethod("getActionIndex")
        contextClass.getMethod("setPointerCount", intType)
        contextClass.getMethod("touchDownEvent", intType, intType, longType, booleanType)
        contextClass.getMethod("touchMoveEvent", intType, intType, longType)
        contextClass.getMethod("touchUpEvent", intType, intType, longType)
        contextClass.getMethod("cancelTouch")
        contextClass.getMethod("isCancelled")
    }

    private fun measuredView(width: Int, height: Int): View {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = View(context)
        view.layout(0, 0, width, height)
        return view
    }

    private fun relativeTouchPreferences(absoluteMouseMode: Boolean): PreferenceConfiguration {
        val prefs = PreferenceConfiguration()
        prefs.touchPadSensitivity = 100
        prefs.touchPadYSensitity = 100
        prefs.absoluteMouseMode = absoluteMouseMode
        return prefs
    }
}
