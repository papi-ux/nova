package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovaThemeResourcesTest {
    @Test
    fun themeArraysExposeMiamiInPredictableOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = context.resources.getStringArray(R.array.nova_theme_names).toList()
        val values = context.resources.getStringArray(R.array.nova_theme_values).toList()

        assertEquals(names.size, values.size)
        assertEquals(
            listOf("polaris", "oled", "miami", "high_contrast", "material_you"),
            values
        )
        assertEquals("Miami Nebula", names[values.indexOf("miami")])
    }

    @Test
    fun preferencesThemeSummaryMentionsMiami() {
        val preferencesXml = File("src/main/res/xml/preferences.xml").readText()

        assertTrue(preferencesXml.contains("android:key=\"nova_theme\""))
        assertTrue(preferencesXml.contains("Miami Nebula"))
    }
}
