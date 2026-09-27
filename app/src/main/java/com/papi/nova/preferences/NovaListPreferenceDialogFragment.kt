package com.papi.nova.preferences

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.papi.nova.binding.video.PyroWaveAvailability
import android.widget.ArrayAdapter
import android.widget.ListAdapter
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.papi.nova.R
import com.papi.nova.ui.NovaDialogWindows

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

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { NovaDialogWindows.adopt(requireContext(), it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SAVE_STATE_INDEX, clickedDialogEntryIndex)
        outState.putCharSequenceArray(SAVE_STATE_ENTRIES, entries)
        outState.putCharSequenceArray(SAVE_STATE_ENTRY_VALUES, entryValues)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        val availability = if (listPreference.key == "video_format" && entryValues?.any { it == "forcepyrowave" } == true)
            PyroWaveAvailability.inspect(requireContext().applicationContext)
            else PyroWaveAvailability.Status.AVAILABLE
        fun enabled(position: Int): Boolean = listPreference.key != "video_format" ||
            PyroWaveAvailability.canSelect(entryValues?.getOrNull(position)?.toString().orEmpty(), availability)
        val adapter: ListAdapter = object : ArrayAdapter<CharSequence>(
            requireContext(), R.layout.nova_select_dialog_singlechoice,
            android.R.id.text1, entries ?: emptyArray(),
        ) {
            override fun areAllItemsEnabled(): Boolean = false
            override fun isEnabled(position: Int): Boolean = enabled(position)
            override fun getViewTypeCount(): Int = 2
            override fun getItemViewType(position: Int): Int = if (enabled(position)) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                if (enabled(position)) return super.getView(position, convertView, parent)
                // A separate type keeps the multiline explanation out of the single-line row pool.
                val row = convertView ?: LayoutInflater.from(requireContext()).inflate(
                    R.layout.nova_select_dialog_unavailable_choice, parent, false,
                )
                row.findViewById<TextView>(android.R.id.text1).text =
                    "${entries?.get(position)}\n${PyroWaveAvailability.reason(requireContext(), availability)}"
                return row
            }
        }
        builder.setSingleChoiceItems(adapter, clickedDialogEntryIndex) { dialog, which ->
            if (!enabled(which)) return@setSingleChoiceItems
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
        if (preference.key == "video_format" && value == "forcepyrowave" &&
            !PyroWaveAvailability.canSelect(value, PyroWaveAvailability.inspect(requireContext()))) return
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
