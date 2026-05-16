package com.papi.nova.utils

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.papi.nova.Game
import com.papi.nova.preferences.PreferenceConfiguration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinInputRenderUtilsMigrationTest {
    @Test
    fun inputAndRenderUtilsAreKotlinSources() {
        val names = arrayOf(
            "PanZoomHandler",
            "ShaderUtils"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/utils/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/utils/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun migratedInputAndRenderUtilsKeepJavaCompatibleApis() {
        PanZoomHandler::class.java.getConstructor(
            Context::class.java,
            Game::class.java,
            View::class.java,
            View::class.java,
            PreferenceConfiguration::class.java
        )
        PanZoomHandler::class.java.getMethod("handleTouchEvent", MotionEvent::class.java)
        PanZoomHandler::class.java.getMethod("handleSurfaceChange")
        PanZoomHandler::class.java.getMethod(
            "setInitialZoomAndPan",
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!
        )
        assertEquals(Float::class.javaPrimitiveType!!, PanZoomHandler::class.java.getMethod("getScaleFactor").returnType)
        assertEquals(Float::class.javaPrimitiveType!!, PanZoomHandler::class.java.getMethod("getChildX").returnType)
        assertEquals(Float::class.javaPrimitiveType!!, PanZoomHandler::class.java.getMethod("getChildY").returnType)

        val shaderFields = arrayOf(
            "VERTEX_SHADER",
            "FRAGMENT_SHADER_3D",
            "OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER",
            "SIMPLE_VERTEX_SHADER",
            "EDGE_AWARE_VERTEX_SHADER",
            "EDGE_AWARE_DEPTH_BLUR_SHADER",
            "SIMPLE_FRAGMENT_SHADER"
        )

        for (fieldName in shaderFields) {
            assertEquals(String::class.java, ShaderUtils::class.java.getField(fieldName).type)
        }
        assertTrue(ShaderUtils.VERTEX_SHADER.contains("a_Position"))
        assertTrue(ShaderUtils.SIMPLE_FRAGMENT_SHADER.contains("samplerExternalOES"))
    }
}
