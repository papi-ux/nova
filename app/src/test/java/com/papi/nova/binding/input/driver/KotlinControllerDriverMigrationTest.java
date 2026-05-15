package com.papi.nova.binding.input.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Service;
import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.papi.nova.GameMenu;
import com.papi.nova.binding.input.GameInputDevice;
import com.papi.nova.nvstream.input.ControllerPacket;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class KotlinControllerDriverMigrationTest {
    @Test
    public void controllerDriverLeavesAreKotlinSources() {
        String[] names = {
                "binding/input/GameInputDevice",
                "binding/input/driver/AbstractController",
                "binding/input/driver/AbstractXboxController",
                "binding/input/driver/Xbox360Controller",
                "binding/input/driver/Xbox360WirelessDongle",
                "binding/input/driver/XboxOneController",
                "binding/input/driver/ProConController",
                "binding/input/driver/UsbDriverService"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void controllerDriverLeavesKeepJavaCompatibleApis() throws Exception {
        assertTrue(GameInputDevice.class.isInterface());
        assertEquals(List.class, GameInputDevice.class.getMethod("getGameMenuOptions").getReturnType());
        GameInputDevice.class.getMethod("supportsControllerMouseEmulation");
        GameInputDevice.class.getMethod("isControllerMouseEmulationActive");
        GameInputDevice.class.getMethod("setControllerMouseEmulationActive", boolean.class);

        AbstractController.class.getMethod("getControllerId");
        AbstractController.class.getMethod("getVendorId");
        AbstractController.class.getMethod("getProductId");
        AbstractController.class.getMethod("getSupportedButtonFlags");
        AbstractController.class.getMethod("getCapabilities");
        AbstractController.class.getMethod("getType");
        AbstractController.class.getMethod("start");
        AbstractController.class.getMethod("stop");
        AbstractController.class.getMethod("rumble", short.class, short.class);
        AbstractController.class.getMethod("rumbleTriggers", short.class, short.class);

        assertTrue(AbstractController.class.isAssignableFrom(AbstractXboxController.class));
        assertTrue(AbstractXboxController.class.isAssignableFrom(Xbox360Controller.class));
        assertTrue(AbstractController.class.isAssignableFrom(Xbox360WirelessDongle.class));
        assertTrue(AbstractXboxController.class.isAssignableFrom(XboxOneController.class));
        assertTrue(AbstractController.class.isAssignableFrom(ProConController.class));

        Xbox360Controller.class.getMethod("canClaimDevice", UsbDevice.class);
        Xbox360WirelessDongle.class.getMethod("canClaimDevice", UsbDevice.class);
        XboxOneController.class.getMethod("canClaimDevice", UsbDevice.class);
        ProConController.class.getMethod("canClaimDevice", UsbDevice.class);

        assertTrue(Service.class.isAssignableFrom(UsbDriverService.class));
        UsbDriverService.class.getMethod("isRecognizedInputDevice", UsbDevice.class);
        UsbDriverService.class.getMethod("kernelSupportsXboxOne");
        UsbDriverService.class.getMethod("kernelSupportsXbox360W");
        UsbDriverService.class.getMethod("shouldClaimDevice", UsbDevice.class, boolean.class);
        UsbDriverService.UsbDriverBinder.class.getMethod("setListener", UsbDriverListener.class);
        UsbDriverService.UsbDriverBinder.class.getMethod("setStateListener", UsbDriverService.UsbDriverStateListener.class);
        UsbDriverService.UsbDriverBinder.class.getMethod("start");
        UsbDriverService.UsbDriverBinder.class.getMethod("stop");
        UsbDriverService.UsbDriverStateListener.class.getMethod("onUsbPermissionPromptStarting");
        UsbDriverService.UsbDriverStateListener.class.getMethod("onUsbPermissionPromptCompleted");
    }

    @Test
    public void defaultGameInputDeviceDoesNotEnableMouseEmulation() {
        GameInputDevice device = new GameInputDevice() {
            @Override
            public List<GameMenu.MenuOption> getGameMenuOptions() {
                return java.util.Collections.emptyList();
            }
        };

        assertFalse(device.supportsControllerMouseEmulation());
        assertFalse(device.isControllerMouseEmulationActive());
        device.setControllerMouseEmulationActive(true);
        assertFalse(device.isControllerMouseEmulationActive());
    }

    @Test
    public void abstractControllerFlagSetterKeepsBitmaskSemantics() {
        TestController controller = new TestController();
        controller.applyButton(ControllerPacket.A_FLAG, 1);
        controller.applyButton(ControllerPacket.B_FLAG, 0);
        controller.applyButton(ControllerPacket.X_FLAG, 1);

        assertEquals(ControllerPacket.A_FLAG | ControllerPacket.X_FLAG, controller.reportedFlags);
    }

    private static class TestController extends AbstractController {
        int reportedFlags;

        TestController() {
            super(1, null, 2, 3);
        }

        void applyButton(int flag, int data) {
            setButtonFlag(flag, data);
            reportInput();
        }

        @Override
        protected void reportInput() {
            reportedFlags = buttonFlags;
        }

        @Override
        public boolean start() {
            return true;
        }

        @Override
        public void stop() {
        }

        @Override
        public void rumble(short lowFreqMotor, short highFreqMotor) {
        }

        @Override
        public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        }
    }
}
