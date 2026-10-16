package com.papi.nova.ui

import android.content.DialogInterface
import android.widget.TextView
import org.junit.Assert.assertEquals
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import androidx.core.graphics.ColorUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaAlertDialogThemeTest {

    @Test
    @Config(sdk = [30, 33])
    fun directorSettingsDialogUsesItsCharcoalSurfaceWarmWhiteButtonsAndRedSignals() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.SettingsTheme_Director)
        controller.setup()
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Change theme")
            .setSingleChoiceItems(arrayOf("< Congratulations, Director >"), 0, null)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        try {
            dialog.show()
            // The buttons stay warm white, as Polaris's primary actions do, rather than following
            // the red accent.
            val expected = activity.getColor(R.color.nova_director_text_primary)
            assertEquals(expected, dialog.getButton(DialogInterface.BUTTON_POSITIVE).currentTextColor)
            assertEquals(expected, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).currentTextColor)
            val title = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            assertNotNull(title)
            assertEquals(expected, title!!.currentTextColor)
            val value = android.util.TypedValue()
            dialog.context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
            val surface = if (value.resourceId != 0) dialog.context.getColor(value.resourceId) else value.data
            assertEquals(activity.getColor(R.color.nova_director_dialog_bg), surface)
            assertTrue("the buttons read on the dialog", ColorUtils.calculateContrast(expected, surface) >= 4.5)
            // Red still marks the choice: the dialog's own accent tints its radio buttons.
            val accent = android.util.TypedValue()
            assertTrue(dialog.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, accent, true))
            val signal = if (accent.resourceId != 0) dialog.context.getColor(accent.resourceId) else accent.data
            assertEquals(activity.getColor(R.color.nova_director_accent), signal)
            assertTrue("the red choice marker stands out on the dialog", ColorUtils.calculateContrast(signal, surface) >= 3.0)
        } finally {
            dialog.dismiss()
            controller.destroy()
        }
    }

    @Test
    fun settingsThemeInflatesListPreferenceDialogButtons() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.SettingsTheme)
        controller.setup()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Change codec settings")
            .setSingleChoiceItems(arrayOf("Automatic", "Prefer H.264"), 0, null)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        assertNotNull(dialog.getButton(DialogInterface.BUTTON_POSITIVE))
        assertNotNull(dialog.getButton(DialogInterface.BUTTON_NEGATIVE))
        dialog.dismiss()
        controller.destroy()
    }
}
