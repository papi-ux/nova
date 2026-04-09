package com.papi.nova;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Config(sdk = {33}, shadows = {
    com.papi.nova.shadows.ShadowSpaceParticleView.class,
    com.papi.nova.shadows.ShadowBackdropFrameRenderer.class
})
@RunWith(RobolectricTestRunner.class)
public class LayoutInflationTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void allLayoutsInflateSuccessfully() throws IllegalAccessException {
        Context base = ApplicationProvider.getApplicationContext();
        Context context = new androidx.appcompat.view.ContextThemeWrapper(base,
                com.google.android.material.R.style.Theme_MaterialComponents_NoActionBar);
        for (int layoutId : getAllLayoutResourceIds()) {
            try {
                LayoutInflater.from(context).inflate(layoutId, null);
            } catch (android.view.InflateException e) {
                // Retry with a dummy FrameLayout for <merge> root layouts
                android.widget.FrameLayout dummyRoot = new android.widget.FrameLayout(context);
                LayoutInflater.from(context).inflate(layoutId, dummyRoot, true);
            }
        }
    }

    private static int[] getAllLayoutResourceIds() throws IllegalAccessException {
        Field[] fields = com.papi.nova.R.layout.class.getFields();
        int[] ids = new int[fields.length];
        int idx = 0;
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                ids[idx++] = f.getInt(null);
            }
        }
        // Trim array if necessary
        if (idx != ids.length) {
            int[] trimmed = new int[idx];
            System.arraycopy(ids, 0, trimmed, 0, idx);
            return trimmed;
        }
        return ids;
    }
}