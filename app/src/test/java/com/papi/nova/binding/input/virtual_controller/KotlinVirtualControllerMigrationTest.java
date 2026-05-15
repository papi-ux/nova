package com.papi.nova.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.binding.input.ControllerHandler;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyAnalogStick;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardAnalogStickButton;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardAnalogStickButtonFree;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardController;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardDigitalButton;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardTouchPadButton;
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyboardDigitalPadButton;
import com.papi.nova.binding.input.virtual_controller.keyboard.LayoutSnappingHelper;
import com.papi.nova.binding.input.virtual_controller.keyboard.keyAnalogStickFree;
import com.papi.nova.binding.input.virtual_controller.keyboard.keyBoardVirtualControllerElement;
import com.papi.nova.nvstream.NvConnection;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinVirtualControllerMigrationTest {
    @Test
    public void virtualControllerClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/binding/input/virtual_controller/AnalogStick",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/AnalogStickFree",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/DigitalButton",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/DigitalPad",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/LeftAnalogStick",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/LeftAnalogStickFree",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/LeftTrigger",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/RightAnalogStick",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/RightAnalogStickFree",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/RightTrigger",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/VirtualController",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/VirtualControllerConfigurationLoader",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/VirtualControllerElement",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyAnalogStick",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardAnalogStickButton",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardAnalogStickButtonFree",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardController",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardControllerConfigurationLoader",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardDigitalButton",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardLayoutController",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardTouchPadButton",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyboardDigitalPadButton",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/LayoutSnappingHelper",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/keyAnalogStickFree",
                "src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/keyBoardVirtualControllerElement"
        };

        for (String path : paths) {
            assertFalse(path + " should no longer be a Java source", new File(path + ".java").exists());
            assertTrue(path + " should be migrated to Kotlin", new File(path + ".kt").exists());
        }
    }

    @Test
    public void virtualControllerClassesKeepJavaCompatibleApis() throws Exception {
        VirtualController.class.getConstructor(ControllerHandler.class, FrameLayout.class, Context.class);
        VirtualController.ControllerInputContext.class.getConstructor();
        VirtualController.ControllerInputContext.class.getField("inputMap");
        VirtualController.ControllerInputContext.class.getField("leftTrigger");
        VirtualController.ControllerInputContext.class.getField("rightTrigger");
        VirtualController.ControllerInputContext.class.getField("rightStickX");
        VirtualController.ControllerInputContext.class.getField("rightStickY");
        VirtualController.ControllerInputContext.class.getField("leftStickX");
        VirtualController.ControllerInputContext.class.getField("leftStickY");
        VirtualController.class.getMethod("getControllerInputContext");
        VirtualController.class.getMethod("sendControllerInputContext");
        VirtualController.class.getMethod("sendControllerInputContext", long.class, int.class);

        VirtualControllerElement.class.getDeclaredConstructor(VirtualController.class, Context.class, int.class);
        DigitalButton.class.getConstructor(VirtualController.class, int.class, int.class, Context.class);
        DigitalPad.class.getConstructor(VirtualController.class, Context.class);
        AnalogStick.class.getConstructor(VirtualController.class, Context.class, int.class);
        AnalogStickFree.class.getConstructor(VirtualController.class, Context.class, int.class);
        LeftAnalogStick.class.getConstructor(VirtualController.class, Context.class);
        RightAnalogStick.class.getConstructor(VirtualController.class, Context.class);
        LeftAnalogStickFree.class.getConstructor(VirtualController.class, Context.class);
        RightAnalogStickFree.class.getConstructor(VirtualController.class, Context.class);
        LeftTrigger.class.getConstructor(VirtualController.class, int.class, Context.class);
        RightTrigger.class.getConstructor(VirtualController.class, int.class, Context.class);

        assertEquals(String.class, VirtualControllerConfigurationLoader.class.getField("OSC_PREFERENCE").getType());
        VirtualControllerConfigurationLoader.class.getMethod("getOscPreferenceName", Context.class);
        VirtualControllerConfigurationLoader.class.getMethod("clearProfile", Context.class);
        VirtualControllerConfigurationLoader.class.getMethod("createDefaultLayout", VirtualController.class, Context.class);
        VirtualControllerConfigurationLoader.class.getMethod("saveProfile", VirtualController.class, Context.class);
        VirtualControllerConfigurationLoader.class.getMethod("loadFromPreferences", VirtualController.class, Context.class);

        KeyBoardController.class.getConstructor(NvConnection.class, FrameLayout.class, Context.class);
        keyBoardVirtualControllerElement.class.getDeclaredConstructor(KeyBoardController.class, Context.class, String.class);
        KeyBoardDigitalButton.class.getConstructor(KeyBoardController.class, String.class, int.class, Context.class);
        KeyBoardTouchPadButton.class.getConstructor(KeyBoardController.class, String.class, int.class, Context.class);
        KeyboardDigitalPadButton.class.getDeclaredConstructor(KeyBoardController.class, Context.class, String.class);
        KeyAnalogStick.class.getConstructor(KeyBoardController.class, Context.class, String.class);
        keyAnalogStickFree.class.getConstructor(KeyBoardController.class, Context.class, String.class);
        KeyBoardAnalogStickButton.class.getConstructor(KeyBoardController.class, String.class, Context.class, int[].class);
        KeyBoardAnalogStickButtonFree.class.getConstructor(KeyBoardController.class, String.class, Context.class, int[].class);
        KeyBoardLayoutController.class.getConstructor(FrameLayout.class, Context.class, com.papi.nova.preferences.PreferenceConfiguration.class);

        assertEquals(String.class, KeyBoardControllerConfigurationLoader.class.getField("OSC_PREFERENCE").getType());
        assertEquals(String.class, KeyBoardControllerConfigurationLoader.class.getField("OSC_PREFERENCE_VALUE").getType());
        KeyBoardControllerConfigurationLoader.class.getMethod("isModifierKey", int.class);
        KeyBoardControllerConfigurationLoader.class.getMethod("screenScale", int.class, int.class);
        KeyBoardControllerConfigurationLoader.class.getMethod("screenScaleSwitch", int.class, int.class);
        KeyBoardControllerConfigurationLoader.class.getMethod("saveProfile", KeyBoardController.class, Context.class);
        KeyBoardControllerConfigurationLoader.class.getMethod("loadFromPreferences", KeyBoardController.class, Context.class);

        LayoutSnappingHelper.SnapResult.class.getConstructor(
                int.class, int.class, int.class, int.class, boolean.class, boolean.class, boolean.class);
        LayoutSnappingHelper.class.getMethod("calculateSnappedPosition", View.class, View[].class, int.class, int.class);
    }

    @Test
    public void layoutSnappingHelperKeepsSnapResizeAndSpacingBehavior() {
        Context context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);
        View moving = addView(parent, context, 150, 0, 100, 80);
        View other = addView(parent, context, 0, 20, 120, 80);

        LayoutSnappingHelper.SnapResult spacing = LayoutSnappingHelper.calculateSnappedPosition(
                moving,
                new View[] { other },
                125,
                20);

        assertEquals(124, spacing.newX);
        assertEquals(20, spacing.newY);
        assertEquals(100, spacing.newWidth);
        assertEquals(80, spacing.newHeight);
        assertTrue(spacing.didAdjustSpacing);

        LayoutSnappingHelper.SnapResult resize = LayoutSnappingHelper.calculateSnappedPosition(
                moving,
                new View[] { other },
                10,
                20);

        assertEquals(120, resize.newWidth);
        assertEquals(80, resize.newHeight);
        assertTrue(resize.didResize);
    }

    @Test
    public void virtualControllerElementConfigurationRoundTripsLayoutAndEnabledState() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        TestElement element = new TestElement(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(100, 80);
        params.leftMargin = 12;
        params.topMargin = 34;
        element.setLayoutParams(params);

        JSONObject config = element.getConfiguration();

        TestElement restored = new TestElement(context);
        restored.setLayoutParams(new FrameLayout.LayoutParams(1, 1));
        restored.loadConfiguration(config);
        FrameLayout.LayoutParams restoredParams = (FrameLayout.LayoutParams) restored.getLayoutParams();

        assertEquals(12, restoredParams.leftMargin);
        assertEquals(34, restoredParams.topMargin);
        assertEquals(100, restoredParams.width);
        assertEquals(80, restoredParams.height);
        assertTrue(restored.enabled);
        assertEquals(View.VISIBLE, restored.getVisibility());
    }

    private static View addView(FrameLayout parent, Context context, int x, int y, int width, int height) {
        View view = new View(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = x;
        params.topMargin = y;
        parent.addView(view, params);
        view.layout(x, y, x + width, y + height);
        return view;
    }

    private static final class TestElement extends VirtualControllerElement {
        TestElement(Context context) {
            super(null, context, 99);
        }

        @Override
        protected void onElementDraw(Canvas canvas) {
        }

        @Override
        public boolean onElementTouchEvent(MotionEvent event) {
            return false;
        }
    }
}
