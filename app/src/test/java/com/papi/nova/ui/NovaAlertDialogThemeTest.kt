package com.papi.nova.ui

import android.content.DialogInterface
import android.widget.TextView
import org.junit.Assert.assertEquals
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.R
import org.junit.Assert.assertNotNull
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
    fun directorSettingsDialogUsesItsRedSurfaceAndReadableWarmWhiteControls() {
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
