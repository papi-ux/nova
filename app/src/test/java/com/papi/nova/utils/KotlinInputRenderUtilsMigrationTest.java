package com.papi.nova.utils;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import com.papi.nova.Game;
import com.papi.nova.preferences.PreferenceConfiguration;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KotlinInputRenderUtilsMigrationTest {
    @Test
    public void inputAndRenderUtilsAreKotlinSources() {
        String[] names = {
                "PanZoomHandler",
                "ShaderUtils"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/utils/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/utils/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void migratedInputAndRenderUtilsKeepJavaCompatibleApis() throws NoSuchMethodException, NoSuchFieldException {
        PanZoomHandler.class.getConstructor(Context.class, Game.class, View.class, View.class, PreferenceConfiguration.class);
        PanZoomHandler.class.getMethod("handleTouchEvent", MotionEvent.class);
        PanZoomHandler.class.getMethod("handleSurfaceChange");
        PanZoomHandler.class.getMethod("setInitialZoomAndPan", float.class, float.class, float.class);
        assertEquals(float.class, PanZoomHandler.class.getMethod("getScaleFactor").getReturnType());
        assertEquals(float.class, PanZoomHandler.class.getMethod("getChildX").getReturnType());
        assertEquals(float.class, PanZoomHandler.class.getMethod("getChildY").getReturnType());

        String[] shaderFields = {
                "VERTEX_SHADER",
                "FRAGMENT_SHADER_3D",
                "OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER",
                "SIMPLE_VERTEX_SHADER",
                "EDGE_AWARE_VERTEX_SHADER",
                "EDGE_AWARE_DEPTH_BLUR_SHADER",
                "SIMPLE_FRAGMENT_SHADER"
        };

        for (String fieldName : shaderFields) {
            assertEquals(String.class, ShaderUtils.class.getField(fieldName).getType());
        }
        assertTrue(ShaderUtils.VERTEX_SHADER.contains("a_Position"));
        assertTrue(ShaderUtils.SIMPLE_FRAGMENT_SHADER.contains("samplerExternalOES"));
    }
}
