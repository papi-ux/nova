package com.papi.nova.binding.input.capture;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import com.papi.nova.BuildConfig;
import com.papi.nova.binding.input.evdev.EvdevCaptureProviderShim;
import com.papi.nova.binding.input.evdev.EvdevListener;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class KotlinInputCaptureMigrationTest {
    @Test
    public void inputCaptureClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/binding/input/capture/InputCaptureProvider",
                "src/main/java/com/papi/nova/binding/input/capture/InputCaptureManager",
                "src/main/java/com/papi/nova/binding/input/capture/AndroidPointerIconCaptureProvider",
                "src/main/java/com/papi/nova/binding/input/capture/AndroidNativePointerCaptureProvider",
                "src/main/java/com/papi/nova/binding/input/capture/ShieldCaptureProvider",
                "src/main/java/com/papi/nova/binding/input/evdev/EvdevCaptureProviderShim"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void inputCaptureClassesKeepJavaCompatibleApis() throws NoSuchMethodException {
        assertTrue(Modifier.isAbstract(InputCaptureProvider.class.getModifiers()));
        InputCaptureProvider.class.getMethod("enableCapture");
        InputCaptureProvider.class.getMethod("disableCapture");
        InputCaptureProvider.class.getMethod("destroy");
        assertEquals(boolean.class, InputCaptureProvider.class.getMethod("isCapturingEnabled").getReturnType());
        assertEquals(boolean.class, InputCaptureProvider.class.getMethod("isCapturingActive").getReturnType());
        InputCaptureProvider.class.getMethod("showCursor");
        InputCaptureProvider.class.getMethod("hideCursor");
        assertEquals(boolean.class, InputCaptureProvider.class.getMethod("eventHasRelativeMouseAxes", MotionEvent.class).getReturnType());
        assertEquals(float.class, InputCaptureProvider.class.getMethod("getRelativeAxisX", MotionEvent.class, int.class).getReturnType());
        assertEquals(float.class, InputCaptureProvider.class.getMethod("getRelativeAxisX", MotionEvent.class).getReturnType());
        assertEquals(float.class, InputCaptureProvider.class.getMethod("getRelativeAxisY", MotionEvent.class, int.class).getReturnType());
        assertEquals(float.class, InputCaptureProvider.class.getMethod("getRelativeAxisY", MotionEvent.class).getReturnType());
        InputCaptureProvider.class.getMethod("onWindowFocusChanged", boolean.class);

        AndroidPointerIconCaptureProvider.class.getConstructor(Activity.class, View.class);
        AndroidPointerIconCaptureProvider.class.getMethod("isCaptureProviderSupported");
        assertTrue(InputCaptureProvider.class.isAssignableFrom(AndroidPointerIconCaptureProvider.class));

        AndroidNativePointerCaptureProvider.class.getConstructor(Activity.class, View.class);
        AndroidNativePointerCaptureProvider.class.getMethod("isCaptureProviderSupported");
        assertTrue(AndroidPointerIconCaptureProvider.class.isAssignableFrom(AndroidNativePointerCaptureProvider.class));
        assertTrue(InputManager.InputDeviceListener.class.isAssignableFrom(AndroidNativePointerCaptureProvider.class));

        ShieldCaptureProvider.class.getConstructor(Context.class);
        ShieldCaptureProvider.class.getMethod("isCaptureProviderSupported");
        assertTrue(InputCaptureProvider.class.isAssignableFrom(ShieldCaptureProvider.class));

        InputCaptureManager.class.getMethod("getInputCaptureProvider", Activity.class, EvdevListener.class);
        EvdevCaptureProviderShim.class.getMethod("isCaptureProviderSupported");
        EvdevCaptureProviderShim.class.getMethod("createEvdevCaptureProvider", Activity.class, EvdevListener.class);
    }

    @Test
    public void baseInputCaptureProviderKeepsStateTransitionsAndDefaults() {
        InputCaptureProvider provider = new NullCaptureProvider();

        assertFalse(provider.isCapturingEnabled());
        assertFalse(provider.isCapturingActive());
        assertFalse(provider.eventHasRelativeMouseAxes(null));
        assertEquals(0f, provider.getRelativeAxisX(null), 0.001f);
        assertEquals(0f, provider.getRelativeAxisX(null, 3), 0.001f);
        assertEquals(0f, provider.getRelativeAxisY(null), 0.001f);
        assertEquals(0f, provider.getRelativeAxisY(null, 3), 0.001f);

        provider.enableCapture();

        assertTrue(provider.isCapturingEnabled());
        assertTrue(provider.isCapturingActive());

        provider.disableCapture();

        assertFalse(provider.isCapturingEnabled());
        assertFalse(provider.isCapturingActive());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.M)
    public void pointerAndNativeCaptureAreUnsupportedBeforeAndroidN() {
        assertFalse(AndroidPointerIconCaptureProvider.isCaptureProviderSupported());
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureProviderSupported());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.N)
    public void pointerIconCaptureIsSupportedFromAndroidN() {
        assertTrue(AndroidPointerIconCaptureProvider.isCaptureProviderSupported());
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureProviderSupported());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.O)
    public void nativePointerCaptureIsSupportedFromAndroidO() {
        assertTrue(AndroidPointerIconCaptureProvider.isCaptureProviderSupported());
        assertTrue(AndroidNativePointerCaptureProvider.isCaptureProviderSupported());
    }

    @Test
    public void evdevShimSupportTracksRootBuildFlag() {
        assertEquals(BuildConfig.ROOT_BUILD, EvdevCaptureProviderShim.isCaptureProviderSupported());
    }
}
