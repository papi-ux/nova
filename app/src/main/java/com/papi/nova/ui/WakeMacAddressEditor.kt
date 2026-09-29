package com.papi.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.wol.WakeOnLanSender

/** Save runs outside the UI thread; completion returns on the UI thread. */
internal fun showWakeMacAddressEditor(
    activity: Activity,
    computer: ComputerDetails,
    save: (String, (Boolean) -> Unit) -> Unit,
): AlertDialog {
    val input = EditText(activity).apply {
        hint = activity.getString(R.string.wol_address_hint)
        contentDescription = activity.getString(R.string.wol_address_title)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(32))
        setSingleLine(true)
        setText(computer.manualWakeMacAddress.orEmpty())
    }
    val discovered = WakeOnLanSender.usableMacAddress(computer.macAddress)
        ?: activity.getString(R.string.wol_address_unknown)
    val side = (20 * activity.resources.displayMetrics.density).toInt()
    val content = FrameLayout(activity).apply {
        setPadding(side, 0, side, 0)
        addView(input)
    }
    val dialog = AlertDialog.Builder(activity)
        .setTitle(R.string.wol_address_title)
        .setMessage(activity.getString(R.string.wol_address_description, discovered))
        .setView(content)
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.show()
    NovaSheetChrome.applyMenuOpacityToLegacyAlert(dialog)
    val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    button.setOnClickListener {
        val value = input.text.toString()
        if (value.isNotBlank() && WakeOnLanSender.usableMacAddress(value) == null) {
            input.error = activity.getString(R.string.wol_address_invalid)
        } else {
            button.isEnabled = false
            input.isEnabled = false
            save(value) { saved ->
                if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                    if (saved) {
                        dialog.dismiss()
                    } else {
                        button.isEnabled = true
                        input.isEnabled = true
                        input.error = activity.getString(R.string.wol_address_save_failed)
                    }
                }
            }
        }
    }
    return dialog
}
