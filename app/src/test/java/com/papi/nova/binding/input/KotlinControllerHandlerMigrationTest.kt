package com.papi.nova.binding.input

import android.app.Activity
import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.papi.nova.binding.input.driver.AbstractController
import com.papi.nova.binding.input.driver.UsbDriverListener
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.GameGestures
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KotlinControllerHandlerMigrationTest {
    @Test
    fun controllerHandlerIsKotlinSource() {
        val javaFile = File("src/main/java/com/papi/nova/binding/input/ControllerHandler.java")
        val kotlinFile = File("src/main/java/com/papi/nova/binding/input/ControllerHandler.kt")

        assertFalse("ControllerHandler should no longer be a Java source", javaFile.exists())
        assertTrue("ControllerHandler should be migrated to Kotlin", kotlinFile.exists())
    }

    @Test
    fun controllerHandlerKeepsJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!
        val shortType = Short::class.javaPrimitiveType!!
        val byteType = Byte::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!

        assertTrue(InputManager.InputDeviceListener::class.java.isAssignableFrom(ControllerHandler::class.java))
        assertTrue(UsbDriverListener::class.java.isAssignableFrom(ControllerHandler::class.java))

        ControllerHandler::class.java.getConstructor(
            Activity::class.java,
            NvConnection::class.java,
            GameGestures::class.java,
            PreferenceConfiguration::class.java
        )
        ControllerHandler::class.java.getMethod("hasController")
        ControllerHandler::class.java.getMethod("stop")
        ControllerHandler::class.java.getMethod("destroy")
        ControllerHandler::class.java.getMethod("disableSensors")
        ControllerHandler::class.java.getMethod("enableSensors")
        ControllerHandler::class.java.getMethod("onInputDeviceAdded", intType)
        ControllerHandler::class.java.getMethod("onInputDeviceRemoved", intType)
        ControllerHandler::class.java.getMethod("onInputDeviceChanged", intType)
        ControllerHandler::class.java.getMethod("isGameControllerDevice", InputDevice::class.java)
        ControllerHandler::class.java.getMethod("getAttachedControllerMask", Context::class.java)
        ControllerHandler::class.java.getMethod("tryHandleTouchpadEvent", MotionEvent::class.java)
        ControllerHandler::class.java.getMethod("handleMotionEvent", MotionEvent::class.java)
        ControllerHandler::class.java.getMethod("handleRumble", shortType, shortType, shortType)
        ControllerHandler::class.java.getMethod("handleRumbleTriggers", shortType, shortType, shortType)
        ControllerHandler::class.java.getMethod("handleSetMotionEventState", shortType, byteType, shortType)
        ControllerHandler::class.java.getMethod("handleSetControllerLED", shortType, byteType, byteType, byteType)
        ControllerHandler::class.java.getMethod("handleButtonUp", KeyEvent::class.java)
        ControllerHandler::class.java.getMethod("handleButtonDown", KeyEvent::class.java)
        ControllerHandler::class.java.getMethod(
            "reportOscState",
            intType,
            shortType,
            shortType,
            shortType,
            shortType,
            byteType,
            byteType
        )
        ControllerHandler::class.java.getMethod(
            "reportControllerState",
            intType,
            intType,
            floatType,
            floatType,
            floatType,
            floatType,
            floatType,
            floatType
        )
        ControllerHandler::class.java.getMethod(
            "reportControllerMotion",
            intType,
            byteType,
            floatType,
            floatType,
            floatType
        )
        ControllerHandler::class.java.getMethod("deviceRemoved", AbstractController::class.java)
        ControllerHandler::class.java.getMethod("deviceAdded", AbstractController::class.java)
    }
}
