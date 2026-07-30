package com.papi.nova.preferences

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatButton
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class StreamSettingsModernButtonSourceTest {
    private val main = File("src/main")

    @Test
    fun legacyHeaderExposesTopRightModernAction() {
        val layout = File(main, "res/layout/activity_stream_settings.xml").readText()
        val strings = File(main, "res/values/strings.xml").readText()

        assertTrue(layout.contains("androidx.appcompat.widget.AppCompatButton"))
        assertTrue(layout.contains("@+id/modernSettingsButton"))
        assertTrue(layout.contains("@string/nova_settings_modern"))
        assertTrue(layout.contains("android:layout_gravity=\"end|center_vertical\""))
        assertTrue(strings.contains("name=\"nova_settings_modern\""))
    }

    @Test
    fun legacyHeaderInflatesWithFocusableModernButton() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(baseContext, R.style.SettingsTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.activity_stream_settings, null, false)
        val button = view.findViewById<AppCompatButton>(R.id.modernSettingsButton)

        assertEquals(context.getString(R.string.nova_settings_modern), button.text.toString())
        assertTrue(button.isFocusable)
    }

    @Test
    fun modernActionPersistsComposeModeAndReturnsWithoutScrolling() {
        val source = File(main, "java/com/papi/nova/preferences/StreamSettings.kt").readText()
        val legacy = source.substringAfter("private fun showLegacySettings()").substringBefore("private fun handleComposeAction")

        assertTrue(legacy.contains("R.id.modernSettingsButton"))
        assertTrue(legacy.contains("setComposeSettingsEnabled(this@StreamSettings, true)"))
        assertTrue(legacy.contains("showComposeSettings()"))
    }
}
