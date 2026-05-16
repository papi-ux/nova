package com.papi.nova.binding.input.capture

import android.app.Activity
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.papi.nova.BuildConfig
import com.papi.nova.binding.input.evdev.EvdevCaptureProviderShim
import com.papi.nova.binding.input.evdev.EvdevListener
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinInputCaptureMigrationTest {
    @Test
    fun inputCaptureClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/binding/input/capture/InputCaptureProvider",
            "src/main/java/com/papi/nova/binding/input/capture/InputCaptureManager",
            "src/main/java/com/papi/nova/binding/input/capture/AndroidPointerIconCaptureProvider",
            "src/main/java/com/papi/nova/binding/input/capture/AndroidNativePointerCaptureProvider",
            "src/main/java/com/papi/nova/binding/input/capture/ShieldCaptureProvider",
            "src/main/java/com/papi/nova/binding/input/evdev/EvdevCaptureProviderShim"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun inputCaptureClassesKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        assertTrue(Modifier.isAbstract(InputCaptureProvider::class.java.modifiers))
        InputCaptureProvider::class.java.getMethod("enableCapture")
        InputCaptureProvider::class.java.getMethod("disableCapture")
        InputCaptureProvider::class.java.getMethod("destroy")
        assertEquals(booleanType, InputCaptureProvider::class.java.getMethod("isCapturingEnabled").returnType)
        assertEquals(booleanType, InputCaptureProvider::class.java.getMethod("isCapturingActive").returnType)
        InputCaptureProvider::class.java.getMethod("showCursor")
        InputCaptureProvider::class.java.getMethod("hideCursor")
        assertEquals(
            booleanType,
            InputCaptureProvider::class.java.getMethod("eventHasRelativeMouseAxes", MotionEvent::class.java).returnType
        )
        assertEquals(
            floatType,
            InputCaptureProvider::class.java.getMethod("getRelativeAxisX", MotionEvent::class.java, intType).returnType
        )
        assertEquals(
            floatType,
            InputCaptureProvider::class.java.getMethod("getRelativeAxisX", MotionEvent::class.java).returnType
        )
        assertEquals(
            floatType,
            InputCaptureProvider::class.java.getMethod("getRelativeAxisY", MotionEvent::class.java, intType).returnType
        )
        assertEquals(
            floatType,
            InputCaptureProvider::class.java.getMethod("getRelativeAxisY", MotionEvent::class.java).returnType
        )
        InputCaptureProvider::class.java.getMethod("onWindowFocusChanged", booleanType)

        AndroidPointerIconCaptureProvider::class.java.getConstructor(Activity::class.java, View::class.java)
        AndroidPointerIconCaptureProvider::class.java.getMethod("isCaptureProviderSupported")
        assertTrue(InputCaptureProvider::class.java.isAssignableFrom(AndroidPointerIconCaptureProvider::class.java))

        AndroidNativePointerCaptureProvider::class.java.getConstructor(Activity::class.java, View::class.java)
        AndroidNativePointerCaptureProvider::class.java.getMethod("isCaptureProviderSupported")
        assertTrue(AndroidPointerIconCaptureProvider::class.java.isAssignableFrom(AndroidNativePointerCaptureProvider::class.java))
        assertTrue(InputManager.InputDeviceListener::class.java.isAssignableFrom(AndroidNativePointerCaptureProvider::class.java))

        ShieldCaptureProvider::class.java.getConstructor(Context::class.java)
        ShieldCaptureProvider::class.java.getMethod("isCaptureProviderSupported")
        assertTrue(InputCaptureProvider::class.java.isAssignableFrom(ShieldCaptureProvider::class.java))

        InputCaptureManager::class.java.getMethod("getInputCaptureProvider", Activity::class.java, EvdevListener::class.java)
        EvdevCaptureProviderShim::class.java.getMethod("isCaptureProviderSupported")
        EvdevCaptureProviderShim::class.java.getMethod(
            "createEvdevCaptureProvider",
            Activity::class.java,
            EvdevListener::class.java
        )
    }

    @Test
    fun baseInputCaptureProviderKeepsStateTransitionsAndDefaults() {
        val provider: InputCaptureProvider = NullCaptureProvider()

        assertFalse(provider.isCapturingEnabled())
        assertFalse(provider.isCapturingActive())
        assertFalse(provider.eventHasRelativeMouseAxes(null))
        assertEquals(0f, provider.getRelativeAxisX(null), 0.001f)
        assertEquals(0f, provider.getRelativeAxisX(null, 3), 0.001f)
        assertEquals(0f, provider.getRelativeAxisY(null), 0.001f)
        assertEquals(0f, provider.getRelativeAxisY(null, 3), 0.001f)

        provider.enableCapture()

        assertTrue(provider.isCapturingEnabled())
        assertTrue(provider.isCapturingActive())

        provider.disableCapture()

        assertFalse(provider.isCapturingEnabled())
        assertFalse(provider.isCapturingActive())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun pointerAndNativeCaptureAreUnsupportedBeforeAndroidN() {
        assertFalse(AndroidPointerIconCaptureProvider.isCaptureProviderSupported())
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureProviderSupported())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun pointerIconCaptureIsSupportedFromAndroidN() {
        assertTrue(AndroidPointerIconCaptureProvider.isCaptureProviderSupported())
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureProviderSupported())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun nativePointerCaptureIsSupportedFromAndroidO() {
        assertTrue(AndroidPointerIconCaptureProvider.isCaptureProviderSupported())
        assertTrue(AndroidNativePointerCaptureProvider.isCaptureProviderSupported())
    }

    @Test
    fun evdevShimSupportTracksRootBuildFlag() {
        assertEquals(BuildConfig.ROOT_BUILD, EvdevCaptureProviderShim.isCaptureProviderSupported())
    }
}
