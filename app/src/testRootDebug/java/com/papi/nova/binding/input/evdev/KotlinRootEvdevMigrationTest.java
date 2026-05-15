package com.papi.nova.binding.input.evdev;

import android.app.Activity;
import android.view.KeyEvent;

import com.papi.nova.binding.input.capture.InputCaptureProvider;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KotlinRootEvdevMigrationTest {
    @Test
    public void rootEvdevClassesAreKotlinSources() {
        String[] paths = {
                "src/root/java/com/papi/nova/binding/input/evdev/EvdevCaptureProvider",
                "src/root/java/com/papi/nova/binding/input/evdev/EvdevEvent",
                "src/root/java/com/papi/nova/binding/input/evdev/EvdevReader",
                "src/root/java/com/papi/nova/binding/input/evdev/EvdevTranslator"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void rootEvdevClassesKeepJavaCompatibleApis() throws NoSuchMethodException, NoSuchFieldException {
        EvdevCaptureProvider.class.getConstructor(Activity.class, EvdevListener.class);
        assertTrue(InputCaptureProvider.class.isAssignableFrom(EvdevCaptureProvider.class));
        EvdevCaptureProvider.class.getMethod("showCursor");
        EvdevCaptureProvider.class.getMethod("hideCursor");
        EvdevCaptureProvider.class.getMethod("enableCapture");
        EvdevCaptureProvider.class.getMethod("destroy");

        EvdevEvent.class.getConstructor(short.class, short.class, int.class);
        assertEquals(short.class, EvdevEvent.class.getField("type").getType());
        assertEquals(short.class, EvdevEvent.class.getField("code").getType());
        assertEquals(int.class, EvdevEvent.class.getField("value").getType());

        assertEquals(16, EvdevEvent.EVDEV_MIN_EVENT_SIZE);
        assertEquals(24, EvdevEvent.EVDEV_MAX_EVENT_SIZE);
        assertEquals((short) 0x00, EvdevEvent.EV_SYN);
        assertEquals((short) 0x01, EvdevEvent.EV_KEY);
        assertEquals((short) 0x02, EvdevEvent.EV_REL);
        assertEquals((short) 0x04, EvdevEvent.EV_MSC);
        assertEquals((short) 0x110, EvdevEvent.BTN_LEFT);
        assertEquals((short) 0x117, EvdevEvent.BTN_TASK);

        EvdevReader.class.getMethod("read", InputStream.class);
        EvdevTranslator.class.getMethod("translateEvdevKeyCode", short.class);
    }

    @Test
    public void evdevReaderParsesNativeEndianMinimumEvents() throws Exception {
        EvdevEvent event = EvdevReader.read(eventStream(
                EvdevEvent.EVDEV_MIN_EVENT_SIZE,
                EvdevEvent.EV_REL,
                EvdevEvent.REL_X,
                12));

        assertNotNull(event);
        assertEquals(EvdevEvent.EV_REL, event.type);
        assertEquals(EvdevEvent.REL_X, event.code);
        assertEquals(12, event.value);
    }

    @Test
    public void evdevReaderParsesNativeEndianMaximumEvents() throws Exception {
        EvdevEvent event = EvdevReader.read(eventStream(
                EvdevEvent.EVDEV_MAX_EVENT_SIZE,
                EvdevEvent.EV_KEY,
                EvdevEvent.BTN_LEFT,
                1));

        assertNotNull(event);
        assertEquals(EvdevEvent.EV_KEY, event.type);
        assertEquals(EvdevEvent.BTN_LEFT, event.code);
        assertEquals(1, event.value);
    }

    @Test
    public void evdevReaderReturnsNullForShortPackets() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
        buffer.putInt(EvdevEvent.EVDEV_MIN_EVENT_SIZE - 1);

        assertNull(EvdevReader.read(new ByteArrayInputStream(buffer.array())));
    }

    @Test
    public void evdevTranslatorKeepsKnownKeyMappingsAndUnknownFallback() {
        assertEquals(KeyEvent.KEYCODE_ESCAPE, EvdevTranslator.translateEvdevKeyCode((short) 1));
        assertEquals(KeyEvent.KEYCODE_ENTER, EvdevTranslator.translateEvdevKeyCode((short) 28));
        assertEquals(0, EvdevTranslator.translateEvdevKeyCode(Short.MAX_VALUE));
    }

    private static ByteArrayInputStream eventStream(int packetLength, short type, short code, int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + packetLength).order(ByteOrder.nativeOrder());
        buffer.putInt(packetLength);

        if (packetLength == EvdevEvent.EVDEV_MAX_EVENT_SIZE) {
            buffer.putLong(0L);
            buffer.putLong(0L);
        } else {
            buffer.putInt(0);
            buffer.putInt(0);
        }

        buffer.putShort(type);
        buffer.putShort(code);
        buffer.putInt(value);

        return new ByteArrayInputStream(buffer.array());
    }
}
