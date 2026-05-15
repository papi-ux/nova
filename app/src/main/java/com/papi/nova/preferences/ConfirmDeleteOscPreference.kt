package com.papi.nova.preferences

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.papi.nova.R
import com.papi.nova.binding.input.virtual_controller.VirtualControllerConfigurationLoader

class ConfirmDeleteOscPreference : DialogPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    class DialogFragmentCompat : PreferenceDialogFragmentCompat() {
        override fun onDialogClosed(positiveResult: Boolean) {
            if (positiveResult) {
                val context = context ?: return
                VirtualControllerConfigurationLoader.clearProfile(context)
                Toast.makeText(context, R.string.toast_reset_osc_success, Toast.LENGTH_SHORT).show()
            }
        }

        companion object {
            @JvmStatic
            fun newInstance(key: String): DialogFragmentCompat {
                val fragment = DialogFragmentCompat()
                val bundle = Bundle(1)
                bundle.putString(ARG_KEY, key)
                fragment.arguments = bundle
                return fragment
            }
        }
    }
}
