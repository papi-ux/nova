package com.papi.nova.preferences

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.DialogPreference
import com.papi.nova.R
import com.papi.nova.binding.input.virtual_controller.VirtualControllerConfigurationLoader

/**
 * Reset on-screen controls. It stays a DialogPreference so its XML title, message and labels
 * still inflate, and StreamSettings.SettingsFragment.onDisplayPreferenceDialog opens it as a
 * destructive Confirm page: a preference row has no button to split in place.
 */
class ConfirmDeleteOscPreference : DialogPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    companion object {
        /** Clears the saved on-screen control layout and says so. */
        @JvmStatic
        fun resetControls(context: Context) {
            VirtualControllerConfigurationLoader.clearProfile(context)
            Toast.makeText(context, R.string.toast_reset_osc_success, Toast.LENGTH_SHORT).show()
        }
    }
}
