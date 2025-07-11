package com.limelight;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.limelight.profiles.OptionDiffUtil;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;
import com.limelight.utils.UiHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EditProfileActivity extends AppCompatActivity {
    private String profileUuid;
    private SettingsProfile currentProfile;
    private InMemorySharedPreferences inMemoryPrefs;
    private String pendingProfileName; // Holds new name for unsaved profiles

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        UiHelper.setLocale(this);

        // Setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Get profile UUID from intent
        profileUuid = getIntent().getStringExtra("profileUuid");

        if (profileUuid != null) {
            // Editing existing profile
            currentProfile = ProfilesManager.getInstance().getProfiles().stream()
                .filter(p -> p.getUuid().toString().equals(profileUuid))
                .findFirst()
                .orElse(null);

            if (currentProfile != null) {
                setTitle("Edit Profile: " + currentProfile.getName());
                inMemoryPrefs = new InMemorySharedPreferences(currentProfile.getOptions());
            } else {
                Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else {
            // Creating new profile
            setTitle("New Profile");
            inMemoryPrefs = new InMemorySharedPreferences(new HashMap<>());
        }

        // Load preference fragment
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.preferences_container, new ProfilePreferenceFragment())
            .commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.edit_profile_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_save) {
            saveProfile();
            return true;
        } else if (id == R.id.action_rename) {
            showRenameDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void saveProfile() {
        // Get the profile options from our in-memory prefs
        Map<String, Object> profileOptions = new HashMap<>(inMemoryPrefs.getAll());

        String displayName;

        if (currentProfile != null) {
            // Update existing profile
            currentProfile.setOptions(profileOptions);
            currentProfile.setModifiedUtc(System.currentTimeMillis());
            displayName = currentProfile.getName();
            ProfilesManager.getInstance().update(currentProfile);
        } else {
            // Create new profile
            String profileName = pendingProfileName != null ? pendingProfileName.trim() : null;
            if (profileName == null || profileName.isEmpty()) {
                profileName = "Profile " + (ProfilesManager.getInstance().getProfiles().size() + 1);
            }
            long now = System.currentTimeMillis();
            SettingsProfile newProfile = new SettingsProfile(
                UUID.randomUUID(),
                profileName,
                now,
                now,
                profileOptions
            );
            displayName = profileName;
            ProfilesManager.getInstance().add(newProfile);
        }

        ProfilesManager.getInstance().save(this);

        // Show confirmation Toast
        Toast.makeText(this,
                "Profile '" + displayName + "' saved.",
                Toast.LENGTH_SHORT).show();

        finish();
    }

    private void showRenameDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        String initial = currentProfile != null ? currentProfile.getName() : (pendingProfileName != null ? pendingProfileName : "");
        input.setText(initial);
        input.setSelection(initial.length());

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Edit Profile Name")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        android.widget.Toast.makeText(this, "Name cannot be blank", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (currentProfile != null) {
                        currentProfile.setName(newName);
                        currentProfile.setModifiedUtc(System.currentTimeMillis());
                        ProfilesManager.getInstance().update(currentProfile);
                        setTitle("Edit Profile: " + newName);
                    } else {
                        pendingProfileName = newName;
                        setTitle("New Profile: " + newName);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public SharedPreferences getInMemoryPrefs() {
        return inMemoryPrefs;
    }

    public static class ProfilePreferenceFragment extends PreferenceFragmentCompat {
        private static class InMemoryPreferenceDataStore extends androidx.preference.PreferenceDataStore {
            private final SharedPreferences prefs;
            InMemoryPreferenceDataStore(SharedPreferences prefs) {
                this.prefs = prefs;
            }
            @Override public void putString(String key, String value) { prefs.edit().putString(key, value).apply(); }
            @Override public void putStringSet(String key, java.util.Set<String> values) { prefs.edit().putStringSet(key, values).apply(); }
            @Override public void putInt(String key, int value) { prefs.edit().putInt(key, value).apply(); }
            @Override public void putBoolean(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
            @Override public void putFloat(String key, float value) { prefs.edit().putFloat(key, value).apply(); }
            @Override public void putLong(String key, long value) { prefs.edit().putLong(key, value).apply(); }

            @Override public String getString(String key, String defValue) { return prefs.getString(key, defValue); }
            @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) { return prefs.getStringSet(key, defValues); }
            @Override public int getInt(String key, int defValue) {
                Object value = prefs.getAll().get(key);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                return defValue;
            }
            @Override public boolean getBoolean(String key, boolean defValue) { return prefs.getBoolean(key, defValue); }
            @Override public float getFloat(String key, float defValue) { return prefs.getFloat(key, defValue); }
            @Override public long getLong(String key, long defValue) {
                Object value = prefs.getAll().get(key);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                return defValue;
            }
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            EditProfileActivity act = (EditProfileActivity) requireActivity();
            SharedPreferences memPrefs = act.getInMemoryPrefs();

            // Route preference storage to in-memory prefs
            getPreferenceManager().setPreferenceDataStore(new InMemoryPreferenceDataStore(memPrefs));

            addPreferencesFromResource(R.xml.preferences);

            // Highlight changed preferences
            java.util.Map<String, ?> patch = memPrefs.getAll();
            int accent = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.profileAccent);
            highlightPreferences(getPreferenceScreen(), patch.keySet(), accent);
        }

        private void highlightPreferences(androidx.preference.Preference pref, java.util.Set<String> changedKeys, int accent) {
            if (pref == null) return;

            if (pref instanceof androidx.preference.PreferenceGroup) {
                androidx.preference.PreferenceGroup group = (androidx.preference.PreferenceGroup) pref;
                for (int i = 0; i < group.getPreferenceCount(); i++) {
                    highlightPreferences(group.getPreference(i), changedKeys, accent);
                }
            } else {
                String key = pref.getKey();
                if (key != null && changedKeys.contains(key)) {
                    android.text.SpannableString span = new android.text.SpannableString(pref.getTitle());
                    span.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, span.length(), 0);
                    pref.setTitle(span);
                }
            }
        }
    }

    /**
     * Simple in-memory SharedPreferences implementation for profile editing
     */
    private static class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> values;

        public InMemorySharedPreferences(Map<String, Object> initialValues) {
            this.values = new HashMap<>(initialValues != null ? initialValues : new HashMap<>());
        }

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof java.util.Set ? (java.util.Set<String>) value : defValues;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new InMemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private class InMemoryEditor implements Editor {
            private final Map<String, Object> changes = new HashMap<>();

            @Override
            public Editor putString(String key, String value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, java.util.Set<String> values) {
                changes.put(key, values);
                return this;
            }

            @Override
            public Editor remove(String key) {
                changes.put(key, null);
                return this;
            }

            @Override
            public Editor clear() {
                values.clear();
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                for (Map.Entry<String, Object> entry : changes.entrySet()) {
                    if (entry.getValue() == null) {
                        values.remove(entry.getKey());
                    } else {
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
                changes.clear();
            }
        }
    }
}