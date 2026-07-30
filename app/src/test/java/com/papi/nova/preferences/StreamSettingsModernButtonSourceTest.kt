package com.papi.nova.preferences

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatButton
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val manageLayout = File(main, "res/layout/pc_grid_item.xml").readText()
        val styles = File(main, "res/values/styles.xml").readText()
        val pill = File(main, "res/drawable/nova_tonal_pill_button.xml")
        val pillXml = if (pill.exists()) pill.readText() else ""
        val textStates = File(main, "res/color/nova_tonal_pill_text.xml").readText()
        val qualifiedBaseStyles = listOf("values-v21", "values-v24", "values-v29").associateWith {
            File(main, "res/$it/styles.xml").readText()
        }
        val api31Styles = File(main, "res/values-v31/styles.xml").readText()
        fun styleBody(xml: String, name: String): String =
            xml.substringAfter("<style name=\"$name\"").substringBefore("</style>")

        assertTrue(layout.contains("androidx.appcompat.widget.AppCompatButton"))
        assertTrue(layout.contains("@+id/modernSettingsButton"))
        assertTrue(layout.contains("@string/nova_settings_modern"))
        assertTrue(layout.contains("android:layout_gravity=\"end|center_vertical\""))
        assertTrue(strings.contains("name=\"nova_settings_modern\""))
        assertTrue(layout.contains("style=\"@style/NovaTonalPillButton\""))
        assertTrue(manageLayout.contains("style=\"@style/NovaTonalPillButton\""))
        assertTrue(styles.contains("name=\"NovaTonalPillButton\""))
        assertTrue(styles.contains("@color/nova_tonal_pill_text"))
        assertTrue(pill.exists())
        assertTrue(pillXml.contains("?attr/colorPrimaryContainer"))
        assertTrue(pillXml.contains("android:color=\"?attr/novaTonalPillPressedColor\""))
        assertFalse(pillXml.contains("@color/nova_tonal_pill_state_layer"))
        assertTrue(pillXml.contains("android:radius=\"24dp\""))
        assertTrue(pillXml.contains("android:state_focused=\"true\""))
        assertTrue(pillXml.contains("android:color=\"?attr/colorOnPrimaryContainer\""))
        assertTrue(textStates.contains("android:state_enabled=\"false\""))
        assertTrue(textStates.contains("android:alpha=\"0.38\""))
        assertTrue(textStates.contains("?attr/colorOnPrimaryContainer"))
        assertTrue(styles.contains("<attr name=\"novaTonalPillPressedColor\" format=\"color\""))
        qualifiedBaseStyles.forEach { (qualifier, xml) ->
            val baseTheme = styleBody(xml, "AppBaseTheme")
            assertTrue("$qualifier primary container", baseTheme.contains("colorPrimaryContainer"))
            assertTrue("$qualifier on-primary container", baseTheme.contains("colorOnPrimaryContainer"))
            assertTrue("$qualifier pressed color", baseTheme.contains("novaTonalPillPressedColor"))
        }
        val api31AppTheme = styleBody(api31Styles, "AppTheme")
        assertTrue(api31AppTheme.contains("colorPrimaryContainer"))
        assertTrue(api31AppTheme.contains("colorOnPrimaryContainer"))
        assertTrue(api31AppTheme.contains("novaTonalPillPressedColor"))
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
