package com.papi.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.EditText
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeMacAddressEditorTest {
    private fun input(dialog: AlertDialog, activity: Activity): EditText {
        val matches = arrayListOf<View>()
        dialog.window!!.decorView.findViewsWithText(matches,
            activity.getString(R.string.wol_address_title), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        assertEquals(1, matches.size)
        return matches.single() as EditText
    }

    @Test fun invalidInputStaysOpenWithoutSavingAndFailedSaveKeepsTheDraft() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var attempts = 0
        var complete: ((Boolean) -> Unit)? = null
        val details = ComputerDetails().apply { manualWakeMacAddress = "02:11:22:33:44:55" }
        val dialog = showWakeMacAddressEditor(activity, details) { _, callback ->
            attempts++
            complete = callback
        }
        try {
            val field = input(dialog, activity)
            assertEquals(details.manualWakeMacAddress, field.text.toString())
            field.setText("00:00:00:00:00:00")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(field.error)
            assertEquals(0, attempts)
            field.setText("02:22:33:44:55:66")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(1, attempts)
            assertFalse(field.isEnabled)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            complete!!(false)
            assertTrue(dialog.isShowing)
            assertTrue(field.isEnabled)
            assertEquals("02:22:33:44:55:66", field.text.toString())
            assertEquals(activity.getString(R.string.wol_address_save_failed), field.error.toString())
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun clearingFieldRequestsAutomaticAddressAndOnlySuccessfulSaveCloses() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var savedValue: String? = null
        var complete: ((Boolean) -> Unit)? = null
        val dialog = showWakeMacAddressEditor(activity, ComputerDetails()) { value, callback ->
            savedValue = value; complete = callback
        }
        try {
            input(dialog, activity).setText("")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("", savedValue)
            assertTrue(dialog.isShowing)
            complete!!(true)
            assertFalse(dialog.isShowing)
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }
}
