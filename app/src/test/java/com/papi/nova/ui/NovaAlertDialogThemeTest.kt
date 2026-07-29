package com.papi.nova.ui

import android.content.DialogInterface
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
