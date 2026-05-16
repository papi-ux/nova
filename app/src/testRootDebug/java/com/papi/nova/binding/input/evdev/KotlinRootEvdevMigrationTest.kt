package com.papi.nova.binding.input.evdev

import android.app.Activity
import android.view.KeyEvent
import com.papi.nova.binding.input.capture.InputCaptureProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinRootEvdevMigrationTest {

    @Test
    fun rootEvdevClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/root/java/com/papi/nova/binding/input/evdev/EvdevCaptureProvider",
            "src/root/java/com/papi/nova/binding/input/evdev/EvdevEvent",
            "src/root/java/com/papi/nova/binding/input/evdev/EvdevReader",
            "src/root/java/com/papi/nova/binding/input/evdev/EvdevTranslator"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun rootEvdevClassesKeepJavaCompatibleApis() {
        EvdevCaptureProvider::class.java.getConstructor(Activity::class.java, EvdevListener::class.java)
        assertTrue(InputCaptureProvider::class.java.isAssignableFrom(EvdevCaptureProvider::class.java))
        EvdevCaptureProvider::class.java.getMethod("showCursor")
        EvdevCaptureProvider::class.java.getMethod("hideCursor")
        EvdevCaptureProvider::class.java.getMethod("enableCapture")
        EvdevCaptureProvider::class.java.getMethod("destroy")

        EvdevEvent::class.java.getConstructor(
            Short::class.javaPrimitiveType!!,
            Short::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
        assertEquals(Short::class.javaPrimitiveType!!, EvdevEvent::class.java.getField("type").type)
        assertEquals(Short::class.javaPrimitiveType!!, EvdevEvent::class.java.getField("code").type)
        assertEquals(Int::class.javaPrimitiveType!!, EvdevEvent::class.java.getField("value").type)

        assertEquals(16, EvdevEvent.EVDEV_MIN_EVENT_SIZE)
        assertEquals(24, EvdevEvent.EVDEV_MAX_EVENT_SIZE)
        assertEquals(0x00.toShort(), EvdevEvent.EV_SYN)
        assertEquals(0x01.toShort(), EvdevEvent.EV_KEY)
        assertEquals(0x02.toShort(), EvdevEvent.EV_REL)
        assertEquals(0x04.toShort(), EvdevEvent.EV_MSC)
        assertEquals(0x110.toShort(), EvdevEvent.BTN_LEFT)
        assertEquals(0x117.toShort(), EvdevEvent.BTN_TASK)

        EvdevReader::class.java.getMethod("read", InputStream::class.java)
        EvdevTranslator::class.java.getMethod("translateEvdevKeyCode", Short::class.javaPrimitiveType!!)
    }

    @Test
    fun evdevReaderParsesNativeEndianMinimumEvents() {
        val event = EvdevReader.read(
            eventStream(
                EvdevEvent.EVDEV_MIN_EVENT_SIZE,
                EvdevEvent.EV_REL,
                EvdevEvent.REL_X,
                12
            )
        )

        assertNotNull(event)
        assertEquals(EvdevEvent.EV_REL, event!!.type)
        assertEquals(EvdevEvent.REL_X, event.code)
        assertEquals(12, event.value)
    }

    @Test
    fun evdevReaderParsesNativeEndianMaximumEvents() {
        val event = EvdevReader.read(
            eventStream(
                EvdevEvent.EVDEV_MAX_EVENT_SIZE,
                EvdevEvent.EV_KEY,
                EvdevEvent.BTN_LEFT,
                1
            )
        )

        assertNotNull(event)
        assertEquals(EvdevEvent.EV_KEY, event!!.type)
        assertEquals(EvdevEvent.BTN_LEFT, event.code)
        assertEquals(1, event.value)
    }

    @Test
    fun evdevReaderReturnsNullForShortPackets() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        buffer.putInt(EvdevEvent.EVDEV_MIN_EVENT_SIZE - 1)

        assertNull(EvdevReader.read(ByteArrayInputStream(buffer.array())))
    }

    @Test
    fun evdevTranslatorKeepsKnownKeyMappingsAndUnknownFallback() {
        assertEquals(KeyEvent.KEYCODE_ESCAPE.toShort(), EvdevTranslator.translateEvdevKeyCode(1.toShort()))
        assertEquals(KeyEvent.KEYCODE_ENTER.toShort(), EvdevTranslator.translateEvdevKeyCode(28.toShort()))
        assertEquals(0.toShort(), EvdevTranslator.translateEvdevKeyCode(Short.MAX_VALUE))
    }

    private fun eventStream(packetLength: Int, type: Short, code: Short, value: Int): ByteArrayInputStream {
        val buffer = ByteBuffer.allocate(4 + packetLength).order(ByteOrder.nativeOrder())
        buffer.putInt(packetLength)

        if (packetLength == EvdevEvent.EVDEV_MAX_EVENT_SIZE) {
            buffer.putLong(0L)
            buffer.putLong(0L)
        } else {
            buffer.putInt(0)
            buffer.putInt(0)
        }

        buffer.putShort(type)
        buffer.putShort(code)
        buffer.putInt(value)

        return ByteArrayInputStream(buffer.array())
    }
}
