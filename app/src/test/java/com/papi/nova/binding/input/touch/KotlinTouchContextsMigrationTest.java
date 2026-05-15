package com.papi.nova.binding.input.touch;

import android.content.Context;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.nvstream.NvConnection;
import com.papi.nova.nvstream.input.MouseButtonPacket;
import com.papi.nova.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinTouchContextsMigrationTest {
    @Test
    public void touchContextClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/binding/input/touch/AbsoluteTouchContext",
                "src/main/java/com/papi/nova/binding/input/touch/RelativeTouchContext",
                "src/main/java/com/papi/nova/binding/input/touch/TrackpadContext"
        };

        for (String path : paths) {
            assertFalse(path + " should no longer be a Java source", new File(path + ".java").exists());
            assertTrue(path + " should be migrated to Kotlin", new File(path + ".kt").exists());
        }
    }

    @Test
    public void touchContextClassesKeepJavaCompatibleApis() throws Exception {
        AbsoluteTouchContext.class.getConstructor(NvConnection.class, int.class, View.class, boolean.class);
        RelativeTouchContext.class.getConstructor(
                NvConnection.class,
                int.class,
                int.class,
                int.class,
                View.class,
                PreferenceConfiguration.class);
        TrackpadContext.class.getConstructor(NvConnection.class, int.class);
        TrackpadContext.class.getConstructor(NvConnection.class, int.class, boolean.class, int.class, int.class);

        assertTrue(TouchContext.class.isAssignableFrom(AbsoluteTouchContext.class));
        assertTrue(TouchContext.class.isAssignableFrom(RelativeTouchContext.class));
        assertTrue(TouchContext.class.isAssignableFrom(TrackpadContext.class));

        assertTouchContextMethods(AbsoluteTouchContext.class);
        assertTouchContextMethods(RelativeTouchContext.class);
        assertTouchContextMethods(TrackpadContext.class);
    }

    @Test
    public void absoluteTouchKeepsTapScrollAndPointerCancelBehavior() {
        NvConnection tapConnection = mock(NvConnection.class);
        AbsoluteTouchContext tapContext = new AbsoluteTouchContext(tapConnection, 0, measuredView(200, 120), false);
        assertEquals(0, tapContext.getActionIndex());

        assertTrue(tapContext.touchDownEvent(30, 40, 1_000L, true));
        assertTrue(tapContext.touchMoveEvent(80, 90, 1_050L));
        tapContext.touchUpEvent(80, 90, 1_100L);

        verify(tapConnection, atLeastOnce()).sendMousePosition(anyShort(), anyShort(), eq((short) 200), eq((short) 120));
        verify(tapConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(tapConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        NvConnection scrollConnection = mock(NvConnection.class);
        AbsoluteTouchContext scrollContext = new AbsoluteTouchContext(scrollConnection, 1, measuredView(200, 120), false);
        assertTrue(scrollContext.touchDownEvent(10, 20, 2_000L, true));
        assertTrue(scrollContext.touchMoveEvent(10, 30, 2_010L));
        verify(scrollConnection).sendMouseHighResScroll((short) 30);

        NvConnection cancelConnection = mock(NvConnection.class);
        AbsoluteTouchContext cancelContext = new AbsoluteTouchContext(cancelConnection, 0, measuredView(200, 120), false);
        assertTrue(cancelContext.touchDownEvent(10, 10, 3_000L, true));
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS);

        cancelContext.setPointerCount(2);

        assertTrue(cancelContext.isCancelled());
        verify(cancelConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(cancelConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void relativeTouchKeepsTapDragScrollAndMoveBehavior() {
        NvConnection moveConnection = mock(NvConnection.class);
        RelativeTouchContext moveContext = new RelativeTouchContext(
                moveConnection,
                0,
                200,
                120,
                measuredView(200, 120),
                relativeTouchPreferences(false));
        moveContext.setPointerCount(1);
        assertTrue(moveContext.touchDownEvent(10, 10, 1_000L, true));
        assertTrue(moveContext.touchMoveEvent(40, 60, 1_050L));
        verify(moveConnection).sendMouseMove((short) 30, (short) 50);

        NvConnection tapConnection = mock(NvConnection.class);
        RelativeTouchContext tapContext = new RelativeTouchContext(
                tapConnection,
                0,
                200,
                120,
                measuredView(200, 120),
                relativeTouchPreferences(false));
        tapContext.setPointerCount(1);
        assertTrue(tapContext.touchDownEvent(12, 18, 2_000L, true));
        tapContext.touchUpEvent(12, 18, 2_050L);
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS);
        verify(tapConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(tapConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        NvConnection scrollConnection = mock(NvConnection.class);
        RelativeTouchContext scrollContext = new RelativeTouchContext(
                scrollConnection,
                0,
                200,
                120,
                measuredView(200, 120),
                relativeTouchPreferences(false));
        scrollContext.setPointerCount(2);
        assertTrue(scrollContext.touchDownEvent(10, 10, 3_000L, true));
        assertTrue(scrollContext.touchMoveEvent(10, 50, 3_050L));
        verify(scrollConnection).sendMouseHighResScroll((short) 200);

        NvConnection dragConnection = mock(NvConnection.class);
        RelativeTouchContext dragContext = new RelativeTouchContext(
                dragConnection,
                0,
                200,
                120,
                measuredView(200, 120),
                relativeTouchPreferences(false));
        dragContext.setPointerCount(1);
        assertTrue(dragContext.touchDownEvent(20, 20, 4_000L, true));
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS);

        dragContext.cancelTouch();

        assertTrue(dragContext.isCancelled());
        verify(dragConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(dragConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void trackpadKeepsTapMoveScrollAndCancelBehavior() {
        NvConnection tapConnection = mock(NvConnection.class);
        TrackpadContext tapContext = new TrackpadContext(tapConnection, 0);
        tapContext.setPointerCount(1);
        assertTrue(tapContext.touchDownEvent(10, 10, 1_000L, true));
        tapContext.touchUpEvent(10, 10, 1_050L);
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS);
        verify(tapConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(tapConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        NvConnection moveConnection = mock(NvConnection.class);
        TrackpadContext moveContext = new TrackpadContext(moveConnection, 0, false, 100, 100);
        moveContext.setPointerCount(1);
        assertTrue(moveContext.touchDownEvent(10, 10, 2_000L, true));
        assertTrue(moveContext.touchMoveEvent(50, 10, 2_050L));
        verify(moveConnection).sendMouseMove(anyShort(), eq((short) 0));

        NvConnection scrollConnection = mock(NvConnection.class);
        TrackpadContext scrollContext = new TrackpadContext(scrollConnection, 1, false, 100, 100);
        scrollContext.setPointerCount(2);
        assertTrue(scrollContext.touchDownEvent(10, 10, 3_000L, true));
        assertTrue(scrollContext.touchMoveEvent(10, 80, 3_050L));
        verify(scrollConnection).sendMouseHighResScroll(anyShort());

        NvConnection cancelConnection = mock(NvConnection.class);
        TrackpadContext cancelContext = new TrackpadContext(cancelConnection, 0);
        cancelContext.setPointerCount(1);
        assertTrue(cancelContext.touchDownEvent(10, 10, 4_000L, true));
        cancelContext.touchUpEvent(10, 10, 4_050L);
        assertTrue(cancelContext.touchDownEvent(10, 10, 4_100L, true));

        cancelContext.cancelTouch();

        assertTrue(cancelContext.isCancelled());
        verify(cancelConnection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(cancelConnection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    private static void assertTouchContextMethods(Class<? extends TouchContext> contextClass) throws NoSuchMethodException {
        contextClass.getMethod("getActionIndex");
        contextClass.getMethod("setPointerCount", int.class);
        contextClass.getMethod("touchDownEvent", int.class, int.class, long.class, boolean.class);
        contextClass.getMethod("touchMoveEvent", int.class, int.class, long.class);
        contextClass.getMethod("touchUpEvent", int.class, int.class, long.class);
        contextClass.getMethod("cancelTouch");
        contextClass.getMethod("isCancelled");
    }

    private static View measuredView(int width, int height) {
        Context context = ApplicationProvider.getApplicationContext();
        View view = new View(context);
        view.layout(0, 0, width, height);
        return view;
    }

    private static PreferenceConfiguration relativeTouchPreferences(boolean absoluteMouseMode) {
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.touchPadSensitivity = 100;
        prefs.touchPadYSensitity = 100;
        prefs.absoluteMouseMode = absoluteMouseMode;
        return prefs;
    }
}
