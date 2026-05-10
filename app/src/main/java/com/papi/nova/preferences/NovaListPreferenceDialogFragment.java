package com.papi.nova.preferences;

import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.papi.nova.R;

public class NovaListPreferenceDialogFragment extends PreferenceDialogFragmentCompat {
    private static final String SAVE_STATE_INDEX =
            "NovaListPreferenceDialogFragment.index";
    private static final String SAVE_STATE_ENTRIES =
            "NovaListPreferenceDialogFragment.entries";
    private static final String SAVE_STATE_ENTRY_VALUES =
            "NovaListPreferenceDialogFragment.entryValues";

    private int clickedDialogEntryIndex;
    private CharSequence[] entries;
    private CharSequence[] entryValues;

    public static NovaListPreferenceDialogFragment newInstance(String key) {
        NovaListPreferenceDialogFragment fragment = new NovaListPreferenceDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            ListPreference preference = getListPreference();
            if (preference.getEntries() == null || preference.getEntryValues() == null) {
                throw new IllegalStateException("ListPreference requires entries and entry values");
            }
            clickedDialogEntryIndex = preference.findIndexOfValue(preference.getValue());
            entries = preference.getEntries();
            entryValues = preference.getEntryValues();
        } else {
            clickedDialogEntryIndex = savedInstanceState.getInt(SAVE_STATE_INDEX, 0);
            entries = savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRIES);
            entryValues = savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRY_VALUES);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVE_STATE_INDEX, clickedDialogEntryIndex);
        outState.putCharSequenceArray(SAVE_STATE_ENTRIES, entries);
        outState.putCharSequenceArray(SAVE_STATE_ENTRY_VALUES, entryValues);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);

        ListAdapter adapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.nova_select_dialog_singlechoice,
                android.R.id.text1,
                entries);
        builder.setSingleChoiceItems(adapter, clickedDialogEntryIndex, (dialog, which) -> {
            clickedDialogEntryIndex = which;
            onClick(dialog, DialogInterface.BUTTON_POSITIVE);
            dialog.dismiss();
        });
        builder.setPositiveButton(null, null);
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (!positiveResult || clickedDialogEntryIndex < 0 || entryValues == null) {
            return;
        }

        String value = entryValues[clickedDialogEntryIndex].toString();
        ListPreference preference = getListPreference();
        if (preference.callChangeListener(value)) {
            preference.setValue(value);
        }
    }

    private ListPreference getListPreference() {
        return (ListPreference) getPreference();
    }
}
