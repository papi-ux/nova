package com.papi.nova.preferences

import android.content.DialogInterface
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListAdapter
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.papi.nova.R

class NovaListPreferenceDialogFragment : PreferenceDialogFragmentCompat() {
    private var clickedDialogEntryIndex = 0
    private var entries: Array<CharSequence>? = null
    private var entryValues: Array<CharSequence>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val preference = listPreference
            if (preference.entries == null || preference.entryValues == null) {
                throw IllegalStateException("ListPreference requires entries and entry values")
            }
            clickedDialogEntryIndex = preference.findIndexOfValue(preference.value)
            entries = preference.entries
            entryValues = preference.entryValues
        } else {
            clickedDialogEntryIndex = savedInstanceState.getInt(SAVE_STATE_INDEX, 0)
            entries = savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRIES)
            entryValues = savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRY_VALUES)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SAVE_STATE_INDEX, clickedDialogEntryIndex)
        outState.putCharSequenceArray(SAVE_STATE_ENTRIES, entries)
        outState.putCharSequenceArray(SAVE_STATE_ENTRY_VALUES, entryValues)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        val adapter: ListAdapter = ArrayAdapter(
            requireContext(),
            R.layout.nova_select_dialog_singlechoice,
            android.R.id.text1,
            entries ?: emptyArray()
        )
        builder.setSingleChoiceItems(adapter, clickedDialogEntryIndex) { dialog, which ->
            clickedDialogEntryIndex = which
            onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.dismiss()
        }
        builder.setPositiveButton(null, null)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val activeEntryValues = entryValues
        if (!positiveResult || clickedDialogEntryIndex < 0 || activeEntryValues == null) {
            return
        }

        val value = activeEntryValues[clickedDialogEntryIndex].toString()
        val preference = listPreference
        if (preference.callChangeListener(value)) {
            preference.value = value
        }
    }

    private val listPreference: ListPreference
        get() = preference as ListPreference

    companion object {
        private const val SAVE_STATE_INDEX = "NovaListPreferenceDialogFragment.index"
        private const val SAVE_STATE_ENTRIES = "NovaListPreferenceDialogFragment.entries"
        private const val SAVE_STATE_ENTRY_VALUES = "NovaListPreferenceDialogFragment.entryValues"

        @JvmStatic
        fun newInstance(key: String): NovaListPreferenceDialogFragment {
            val fragment = NovaListPreferenceDialogFragment()
            val args = Bundle(1)
            args.putString(ARG_KEY, key)
            fragment.arguments = args
            return fragment
        }
    }
}
