package com.papi.nova.binding.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.papi.nova.binding.input.driver.AbstractController;
import com.papi.nova.binding.input.driver.UsbDriverListener;
import com.papi.nova.nvstream.NvConnection;
import com.papi.nova.preferences.PreferenceConfiguration;
import com.papi.nova.ui.GameGestures;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class KotlinControllerHandlerMigrationTest {
    @Test
    public void controllerHandlerIsKotlinSource() {
        File javaFile = new File("src/main/java/com/papi/nova/binding/input/ControllerHandler.java");
        File kotlinFile = new File("src/main/java/com/papi/nova/binding/input/ControllerHandler.kt");

        assertFalse("ControllerHandler should no longer be a Java source", javaFile.exists());
        assertTrue("ControllerHandler should be migrated to Kotlin", kotlinFile.exists());
    }

    @Test
    public void controllerHandlerKeepsJavaCompatibleApis() throws Exception {
        assertTrue(InputManager.InputDeviceListener.class.isAssignableFrom(ControllerHandler.class));
        assertTrue(UsbDriverListener.class.isAssignableFrom(ControllerHandler.class));

        ControllerHandler.class.getConstructor(Activity.class, NvConnection.class, GameGestures.class,
                PreferenceConfiguration.class);
        ControllerHandler.class.getMethod("hasController");
        ControllerHandler.class.getMethod("stop");
        ControllerHandler.class.getMethod("destroy");
        ControllerHandler.class.getMethod("disableSensors");
        ControllerHandler.class.getMethod("enableSensors");
        ControllerHandler.class.getMethod("onInputDeviceAdded", int.class);
        ControllerHandler.class.getMethod("onInputDeviceRemoved", int.class);
        ControllerHandler.class.getMethod("onInputDeviceChanged", int.class);
        ControllerHandler.class.getMethod("isGameControllerDevice", InputDevice.class);
        ControllerHandler.class.getMethod("getAttachedControllerMask", Context.class);
        ControllerHandler.class.getMethod("tryHandleTouchpadEvent", MotionEvent.class);
        ControllerHandler.class.getMethod("handleMotionEvent", MotionEvent.class);
        ControllerHandler.class.getMethod("handleRumble", short.class, short.class, short.class);
        ControllerHandler.class.getMethod("handleRumbleTriggers", short.class, short.class, short.class);
        ControllerHandler.class.getMethod("handleSetMotionEventState", short.class, byte.class, short.class);
        ControllerHandler.class.getMethod("handleSetControllerLED", short.class, byte.class, byte.class, byte.class);
        ControllerHandler.class.getMethod("handleButtonUp", KeyEvent.class);
        ControllerHandler.class.getMethod("handleButtonDown", KeyEvent.class);
        ControllerHandler.class.getMethod("reportOscState",
                int.class, short.class, short.class, short.class, short.class, byte.class, byte.class);
        ControllerHandler.class.getMethod("reportControllerState",
                int.class, int.class, float.class, float.class, float.class, float.class, float.class, float.class);
        ControllerHandler.class.getMethod("reportControllerMotion",
                int.class, byte.class, float.class, float.class, float.class);
        ControllerHandler.class.getMethod("deviceRemoved", AbstractController.class);
        ControllerHandler.class.getMethod("deviceAdded", AbstractController.class);
    }
}
