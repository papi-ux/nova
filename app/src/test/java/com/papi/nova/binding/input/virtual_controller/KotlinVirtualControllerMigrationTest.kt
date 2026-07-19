package com.papi.nova.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyAnalogStick
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardAnalogStickButton
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardAnalogStickButtonFree
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardController
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardDigitalButton
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardTouchPadButton
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyboardDigitalPadButton
import com.papi.nova.binding.input.virtual_controller.keyboard.LayoutSnappingHelper
import com.papi.nova.binding.input.virtual_controller.keyboard.keyAnalogStickFree
import com.papi.nova.binding.input.virtual_controller.keyboard.keyBoardVirtualControllerElement
import com.papi.nova.nvstream.NvConnection
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinVirtualControllerMigrationTest {
    @Test
    fun virtualControllerClassesAreKotlinSources() {
        val paths = arrayOf(
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
        )

        for (path in paths) {
            assertFalse("$path should no longer be a Java source", File("$path.java").exists())
            assertTrue("$path should be migrated to Kotlin", File("$path.kt").exists())
        }
    }

    @Test
    fun virtualControllerClassesKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!

        VirtualController::class.java.getConstructor(ControllerHandler::class.java, FrameLayout::class.java, Context::class.java)
        VirtualController.ControllerInputContext::class.java.getConstructor()
        VirtualController.ControllerInputContext::class.java.getField("inputMap")
        VirtualController.ControllerInputContext::class.java.getField("leftTrigger")
        VirtualController.ControllerInputContext::class.java.getField("rightTrigger")
        VirtualController.ControllerInputContext::class.java.getField("rightStickX")
        VirtualController.ControllerInputContext::class.java.getField("rightStickY")
        VirtualController.ControllerInputContext::class.java.getField("leftStickX")
        VirtualController.ControllerInputContext::class.java.getField("leftStickY")
        VirtualController::class.java.getMethod("getControllerInputContext")
        VirtualController::class.java.getMethod("sendControllerInputContext")
        VirtualController::class.java.getMethod("sendControllerInputContext", longType, intType)

        VirtualControllerElement::class.java.getDeclaredConstructor(VirtualController::class.java, Context::class.java, intType)
        DigitalButton::class.java.getConstructor(VirtualController::class.java, intType, intType, Context::class.java)
        DigitalPad::class.java.getConstructor(VirtualController::class.java, Context::class.java)
        AnalogStick::class.java.getConstructor(VirtualController::class.java, Context::class.java, intType)
        AnalogStickFree::class.java.getConstructor(VirtualController::class.java, Context::class.java, intType)
        LeftAnalogStick::class.java.getConstructor(VirtualController::class.java, Context::class.java)
        RightAnalogStick::class.java.getConstructor(VirtualController::class.java, Context::class.java)
        LeftAnalogStickFree::class.java.getConstructor(VirtualController::class.java, Context::class.java)
        RightAnalogStickFree::class.java.getConstructor(VirtualController::class.java, Context::class.java)
        LeftTrigger::class.java.getConstructor(VirtualController::class.java, intType, Context::class.java)
        RightTrigger::class.java.getConstructor(VirtualController::class.java, intType, Context::class.java)

        assertEquals(String::class.java, VirtualControllerConfigurationLoader::class.java.getField("OSC_PREFERENCE").type)
        VirtualControllerConfigurationLoader::class.java.getMethod("getOscPreferenceName", Context::class.java)
        VirtualControllerConfigurationLoader::class.java.getMethod("clearProfile", Context::class.java)
        VirtualControllerConfigurationLoader::class.java.getMethod(
            "createDefaultLayout",
            VirtualController::class.java,
            Context::class.java
        )
        VirtualControllerConfigurationLoader::class.java.getMethod(
            "saveProfile",
            VirtualController::class.java,
            Context::class.java
        )
        VirtualControllerConfigurationLoader::class.java.getMethod(
            "loadFromPreferences",
            VirtualController::class.java,
            Context::class.java
        )

        KeyBoardController::class.java.getConstructor(NvConnection::class.java, FrameLayout::class.java, Context::class.java)
        keyBoardVirtualControllerElement::class.java.getDeclaredConstructor(
            KeyBoardController::class.java,
            Context::class.java,
            String::class.java
        )
        KeyBoardDigitalButton::class.java.getConstructor(KeyBoardController::class.java, String::class.java, intType, Context::class.java)
        KeyBoardTouchPadButton::class.java.getConstructor(KeyBoardController::class.java, String::class.java, intType, Context::class.java)
        KeyboardDigitalPadButton::class.java.getDeclaredConstructor(KeyBoardController::class.java, Context::class.java, String::class.java)
        KeyAnalogStick::class.java.getConstructor(KeyBoardController::class.java, Context::class.java, String::class.java)
        keyAnalogStickFree::class.java.getConstructor(KeyBoardController::class.java, Context::class.java, String::class.java)
        KeyBoardAnalogStickButton::class.java.getConstructor(
            KeyBoardController::class.java,
            String::class.java,
            Context::class.java,
            IntArray::class.java
        )
        KeyBoardAnalogStickButtonFree::class.java.getConstructor(
            KeyBoardController::class.java,
            String::class.java,
            Context::class.java,
            IntArray::class.java
        )
        KeyBoardLayoutController::class.java.getConstructor(
            FrameLayout::class.java,
            Context::class.java,
            com.papi.nova.preferences.PreferenceConfiguration::class.java
        )

        assertEquals(String::class.java, KeyBoardControllerConfigurationLoader::class.java.getField("OSC_PREFERENCE").type)
        assertEquals(String::class.java, KeyBoardControllerConfigurationLoader::class.java.getField("OSC_PREFERENCE_VALUE").type)
        KeyBoardControllerConfigurationLoader::class.java.getMethod("isModifierKey", intType)
        KeyBoardControllerConfigurationLoader::class.java.getMethod("screenScale", intType, intType)
        KeyBoardControllerConfigurationLoader::class.java.getMethod("screenScaleSwitch", intType, intType)
        KeyBoardControllerConfigurationLoader::class.java.getMethod(
            "saveProfile",
            KeyBoardController::class.java,
            Context::class.java
        )
        KeyBoardControllerConfigurationLoader::class.java.getMethod(
            "loadFromPreferences",
            KeyBoardController::class.java,
            Context::class.java
        )

        LayoutSnappingHelper.SnapResult::class.java.getConstructor(
            intType,
            intType,
            intType,
            intType,
            booleanType,
            booleanType,
            booleanType
        )
        LayoutSnappingHelper::class.java.getMethod(
            "calculateSnappedPosition",
            View::class.java,
            Array<View>::class.java,
            intType,
            intType
        )
    }

    @Test
    fun skinnedDpadAlphaMapsCurrentAssetOpacityToFullAndroidRange() {
        assertEquals(0, DigitalPad.skinnedDpadAlpha(0))
        assertEquals(128, DigitalPad.skinnedDpadAlpha(50))
        assertEquals(255, DigitalPad.skinnedDpadAlpha(100))
        assertEquals(0, DigitalPad.skinnedDpadAlpha(-20))
        assertEquals(255, DigitalPad.skinnedDpadAlpha(140))
    }

    @Test
    fun layoutSnappingHelperKeepsSnapResizeAndSpacingBehavior() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(context)
        val moving = addView(parent, context, 150, 0, 100, 80)
        val other = addView(parent, context, 0, 20, 120, 80)

        val spacing = LayoutSnappingHelper.calculateSnappedPosition(
            moving,
            arrayOf(other),
            125,
            20
        )

        assertEquals(124, spacing.newX)
        assertEquals(20, spacing.newY)
        assertEquals(100, spacing.newWidth)
        assertEquals(80, spacing.newHeight)
        assertTrue(spacing.didAdjustSpacing)

        val resize = LayoutSnappingHelper.calculateSnappedPosition(
            moving,
            arrayOf(other),
            10,
            20
        )

        assertEquals(120, resize.newWidth)
        assertEquals(80, resize.newHeight)
        assertTrue(resize.didResize)
    }

    @Test
    fun virtualControllerElementConfigurationRoundTripsLayoutAndEnabledState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val element = TestElement(context)
        val params = FrameLayout.LayoutParams(100, 80)
        params.leftMargin = 12
        params.topMargin = 34
        element.layoutParams = params

        val config: JSONObject = element.getConfiguration()

        val restored = TestElement(context)
        restored.layoutParams = FrameLayout.LayoutParams(1, 1)
        restored.loadConfiguration(config)
        val restoredParams = restored.layoutParams as FrameLayout.LayoutParams

        assertEquals(12, restoredParams.leftMargin)
        assertEquals(34, restoredParams.topMargin)
        assertEquals(100, restoredParams.width)
        assertEquals(80, restoredParams.height)
        assertTrue(restored.enabled)
        assertEquals(View.VISIBLE, restored.visibility)
    }

    private fun addView(parent: FrameLayout, context: Context, x: Int, y: Int, width: Int, height: Int): View {
        val view = View(context)
        val params = FrameLayout.LayoutParams(width, height)
        params.leftMargin = x
        params.topMargin = y
        parent.addView(view, params)
        view.layout(x, y, x + width, y + height)
        return view
    }

    private class TestElement(context: Context) : VirtualControllerElement(null, context, 99) {
        override fun onElementDraw(canvas: Canvas) = Unit

        override fun onElementTouchEvent(event: MotionEvent): Boolean = false
    }
}
