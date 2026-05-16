package com.papi.nova

import android.content.Context
import android.view.InflateException
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Modifier
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(
    sdk = [33],
    shadows = [
        com.papi.nova.shadows.ShadowSpaceParticleView::class,
        com.papi.nova.shadows.ShadowBackdropFrameRenderer::class
    ]
)
@RunWith(RobolectricTestRunner::class)
class LayoutInflationTest {
    @Test
    fun allLayoutsInflateSuccessfully() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(base, com.google.android.material.R.style.Theme_MaterialComponents_NoActionBar)
        for (layoutId in getAllLayoutResourceIds()) {
            try {
                LayoutInflater.from(context).inflate(layoutId, null)
            } catch (e: InflateException) {
                val dummyRoot = FrameLayout(context)
                LayoutInflater.from(context).inflate(layoutId, dummyRoot, true)
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }

        private fun getAllLayoutResourceIds(): IntArray {
            return R.layout::class.java.fields
                .filter { field -> Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType }
                .map { field -> field.getInt(null) }
                .toIntArray()
        }
    }
}
