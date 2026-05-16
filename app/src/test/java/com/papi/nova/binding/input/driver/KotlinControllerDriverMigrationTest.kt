package com.papi.nova.binding.input.driver

import android.app.Service
import android.hardware.usb.UsbDevice
import com.papi.nova.GameMenu
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.nvstream.input.ControllerPacket
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinControllerDriverMigrationTest {
    @Test
    fun controllerDriverLeavesAreKotlinSources() {
        val names = arrayOf(
            "binding/input/GameInputDevice",
            "binding/input/driver/AbstractController",
            "binding/input/driver/AbstractXboxController",
            "binding/input/driver/Xbox360Controller",
            "binding/input/driver/Xbox360WirelessDongle",
            "binding/input/driver/XboxOneController",
            "binding/input/driver/ProConController",
            "binding/input/driver/UsbDriverService"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun controllerDriverLeavesKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val shortType = Short::class.javaPrimitiveType!!

        assertTrue(GameInputDevice::class.java.isInterface)
        assertEquals(List::class.java, GameInputDevice::class.java.getMethod("getGameMenuOptions").returnType)
        GameInputDevice::class.java.getMethod("supportsControllerMouseEmulation")
        GameInputDevice::class.java.getMethod("isControllerMouseEmulationActive")
        GameInputDevice::class.java.getMethod("setControllerMouseEmulationActive", booleanType)

        AbstractController::class.java.getMethod("getControllerId")
        AbstractController::class.java.getMethod("getVendorId")
        AbstractController::class.java.getMethod("getProductId")
        AbstractController::class.java.getMethod("getSupportedButtonFlags")
        AbstractController::class.java.getMethod("getCapabilities")
        AbstractController::class.java.getMethod("getType")
        AbstractController::class.java.getMethod("start")
        AbstractController::class.java.getMethod("stop")
        AbstractController::class.java.getMethod("rumble", shortType, shortType)
        AbstractController::class.java.getMethod("rumbleTriggers", shortType, shortType)

        assertTrue(AbstractController::class.java.isAssignableFrom(AbstractXboxController::class.java))
        assertTrue(AbstractXboxController::class.java.isAssignableFrom(Xbox360Controller::class.java))
        assertTrue(AbstractController::class.java.isAssignableFrom(Xbox360WirelessDongle::class.java))
        assertTrue(AbstractXboxController::class.java.isAssignableFrom(XboxOneController::class.java))
        assertTrue(AbstractController::class.java.isAssignableFrom(ProConController::class.java))

        Xbox360Controller::class.java.getMethod("canClaimDevice", UsbDevice::class.java)
        Xbox360WirelessDongle::class.java.getMethod("canClaimDevice", UsbDevice::class.java)
        XboxOneController::class.java.getMethod("canClaimDevice", UsbDevice::class.java)
        ProConController::class.java.getMethod("canClaimDevice", UsbDevice::class.java)

        assertTrue(Service::class.java.isAssignableFrom(UsbDriverService::class.java))
        UsbDriverService::class.java.getMethod("isRecognizedInputDevice", UsbDevice::class.java)
        UsbDriverService::class.java.getMethod("kernelSupportsXboxOne")
        UsbDriverService::class.java.getMethod("kernelSupportsXbox360W")
        UsbDriverService::class.java.getMethod("shouldClaimDevice", UsbDevice::class.java, booleanType)
        UsbDriverService.UsbDriverBinder::class.java.getMethod("setListener", UsbDriverListener::class.java)
        UsbDriverService.UsbDriverBinder::class.java.getMethod(
            "setStateListener",
            UsbDriverService.UsbDriverStateListener::class.java
        )
        UsbDriverService.UsbDriverBinder::class.java.getMethod("start")
        UsbDriverService.UsbDriverBinder::class.java.getMethod("stop")
        UsbDriverService.UsbDriverStateListener::class.java.getMethod("onUsbPermissionPromptStarting")
        UsbDriverService.UsbDriverStateListener::class.java.getMethod("onUsbPermissionPromptCompleted")
    }

    @Test
    fun defaultGameInputDeviceDoesNotEnableMouseEmulation() {
        val device = object : GameInputDevice {
            override fun getGameMenuOptions(): List<GameMenu.MenuOption> = emptyList()
        }

        assertFalse(device.supportsControllerMouseEmulation())
        assertFalse(device.isControllerMouseEmulationActive())
        device.setControllerMouseEmulationActive(true)
        assertFalse(device.isControllerMouseEmulationActive())
    }

    @Test
    fun abstractControllerFlagSetterKeepsBitmaskSemantics() {
        val controller = TestController()
        controller.applyButton(ControllerPacket.A_FLAG, 1)
        controller.applyButton(ControllerPacket.B_FLAG, 0)
        controller.applyButton(ControllerPacket.X_FLAG, 1)

        assertEquals(ControllerPacket.A_FLAG or ControllerPacket.X_FLAG, controller.reportedFlags)
    }

    private class TestController : AbstractController(1, null, 2, 3) {
        var reportedFlags = 0

        fun applyButton(flag: Int, data: Int) {
            setButtonFlag(flag, data)
            reportInput()
        }

        override fun reportInput() {
            reportedFlags = buttonFlags
        }

        override fun start(): Boolean = true

        override fun stop() = Unit

        override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) = Unit

        override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit
    }
}
