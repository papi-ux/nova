package com.papi.nova

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingsAvailability
import com.papi.nova.preferences.NovaSettingsFeatureFlags
import com.papi.nova.preferences.NovaSettingsHeaderAction
import com.papi.nova.preferences.NovaSettingsScreen
import com.papi.nova.preferences.NovaSettingsViewModel
import com.papi.nova.preferences.NovaSharedPreferencesSettingsStore
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.StreamSettings
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.utils.UiHelper
import java.util.UUID

class EditProfileActivity : NovaActivity() {
    private var profileUuid: String? = null
    private var currentProfile: SettingsProfile? = null
    private lateinit var inMemoryPrefs: InMemorySharedPreferences
    private var prefsFragment: ProfilePreferenceFragment? = null
    private var pendingProfileName: String? = null
    private var legacyMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)

        profileUuid = intent.getStringExtra("profileUuid")

        if (profileUuid != null) {
            for (profile in ProfilesManager.getInstance().getProfiles()) {
                if (profile.getUuid().toString() == profileUuid) {
                    currentProfile = profile
                    break
                }
            }

            val profile = currentProfile
            if (profile != null) {
                title = getString(R.string.profile_manager_edit_profile) + profile.getName()
                inMemoryPrefs = InMemorySharedPreferences(profile.getOptions())
            } else {
                Toast.makeText(this, R.string.profile_manager_profile_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        } else {
            title = getString(R.string.profile_manager_new_profile)
            inMemoryPrefs = InMemorySharedPreferences(emptyMap<String, Any>())
        }

        if (NovaSettingsFeatureFlags.isComposeSettingsEnabled(this)) {
            showComposeProfileEditor()
        } else {
            showLegacyProfileEditor()
        }
    }

    private fun showComposeProfileEditor() {
        legacyMode = false
        val definitions = NovaSettingsAvailability.filterForProfileEditor(
            NovaSettingsAvailability.filter(this, NovaSettingDefinitions.load(this))
        )
        val store = NovaSharedPreferencesSettingsStore(
            prefs = inMemoryPrefs,
            fallbackPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        )
        val viewModel = ViewModelProvider(
            this,
            NovaSettingsViewModel.Factory(definitions, store)
        )[NovaSettingsViewModel::class.java]
        val content = ComposeView(this).apply {
            setContent {
                NovaComposeTheme {
                    NovaSettingsScreen(
                        viewModel = viewModel,
                        title = title.toString(),
                        subtitle = "Profile overrides",
                        onBack = { finish() },
                        onOpenLegacy = {
                            NovaSettingsFeatureFlags.setComposeSettingsEnabled(this@EditProfileActivity, false)
                            showLegacyProfileEditor()
                        },
                        onAction = ::handleComposeAction,
                        headerActions = listOf(
                            NovaSettingsHeaderAction("Rename") { showRenameDialog() },
                            NovaSettingsHeaderAction("Save") { saveProfile() }
                        )
                    )
                }
            }
        }
        setContentView(content)

        UiHelper.notifyNewRootView(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!legacyMode) return false
        menuInflater.inflate(R.menu.edit_profile_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                saveProfile()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun reloadSettings() {
        val currentPrefs = prefsFragment?.getPrefs() ?: inMemoryPrefs
        prefsFragment = ProfilePreferenceFragment(this, currentPrefs)
        supportFragmentManager.beginTransaction()
            .replace(R.id.preferences_container, prefsFragment!!)
            .commitAllowingStateLoss()
    }

    private fun showLegacyProfileEditor() {
        legacyMode = true
        setContentView(R.layout.activity_edit_profile)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefsFragment = ProfilePreferenceFragment(this, inMemoryPrefs)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.preferences_container, prefsFragment!!)
            .commit()

        UiHelper.notifyNewRootView(this)
    }

    private fun handleComposeAction(definition: NovaSettingDefinition) {
        Toast.makeText(
            this,
            "Opening legacy profile settings for ${definition.title}",
            Toast.LENGTH_SHORT
        ).show()
        showLegacyProfileEditor()
    }

    private fun saveProfile() {
        val profileOptions = HashMap<String, Any>()
        for ((key, value) in inMemoryPrefs.all) {
            if (value != null && NovaSettingsAvailability.shouldPersistProfileOverride(key)) {
                profileOptions[key] = value
            }
        }

        val displayName: String
        val profile = currentProfile
        if (profile != null) {
            profile.setOptions(profileOptions)
            profile.setModifiedUtc(System.currentTimeMillis())
            displayName = profile.getName()
            ProfilesManager.getInstance().update(profile)
        } else {
            var profileName = pendingProfileName?.trim()
            if (profileName.isNullOrEmpty()) {
                profileName = getString(R.string.profile_manager_profile) +
                    (ProfilesManager.getInstance().getProfiles().size + 1)
            }
            val now = System.currentTimeMillis()
            val newProfile = SettingsProfile(
                UUID.randomUUID(),
                profileName,
                now,
                now,
                profileOptions,
            )
            displayName = profileName
            ProfilesManager.getInstance().add(newProfile)
        }

        if (ProfilesManager.getInstance().save(this)) {
            Toast.makeText(
                this,
                getString(R.string.profile_manager_profile_saved, displayName),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            Toast.makeText(this, R.string.profile_manager_failed_to_save, Toast.LENGTH_LONG).show()
        }

        finish()
    }

    private fun showRenameDialog() {
        val input = EditText(this)
        val initial = currentProfile?.getName() ?: pendingProfileName ?: ""
        input.setText(initial)
        input.setSelection(initial.length)

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_manager_edit_profile_name)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.profile_manager_name_cannot_be_blank, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val profile = currentProfile
                if (profile != null) {
                    profile.setName(newName)
                    profile.setModifiedUtc(System.currentTimeMillis())
                    ProfilesManager.getInstance().update(profile)
                    title = getString(R.string.profile_manager_edit_profile_with, newName)
                } else {
                    pendingProfileName = newName
                    title = getString(R.string.profile_manager_new_profile_with, newName)
                }

                if (!legacyMode) {
                    showComposeProfileEditor()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    fun getInMemoryPrefs(): SharedPreferences = inMemoryPrefs

    class ProfilePreferenceFragment(
        context: EditProfileActivity,
        prefs: SharedPreferences,
    ) : StreamSettings.SettingsFragment(PreferenceConfiguration.readPreferences(context, prefs)) {
        private class InMemoryPreferenceDataStore(
            private val prefs: SharedPreferences,
        ) : PreferenceDataStore() {
            override fun putString(key: String?, value: String?) {
                if (key != null) prefs.edit().putString(key, value).apply()
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?) {
                if (key != null) prefs.edit().putStringSet(key, values).apply()
            }

            override fun putInt(key: String?, value: Int) {
                if (key != null) prefs.edit().putInt(key, value).apply()
            }

            override fun putBoolean(key: String?, value: Boolean) {
                if (key != null) prefs.edit().putBoolean(key, value).apply()
            }

            override fun putFloat(key: String?, value: Float) {
                if (key != null) prefs.edit().putFloat(key, value).apply()
            }

            override fun putLong(key: String?, value: Long) {
                if (key != null) prefs.edit().putLong(key, value).apply()
            }

            override fun getString(key: String?, defValue: String?): String? = prefs.getString(key, defValue)

            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
                return prefs.getStringSet(key, defValues)
            }

            override fun getInt(key: String?, defValue: Int): Int {
                val value = prefs.all[key]
                if (value is Number) {
                    return value.toInt()
                }
                return defValue
            }

            override fun getBoolean(key: String?, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

            override fun getFloat(key: String?, defValue: Float): Float = prefs.getFloat(key, defValue)

            override fun getLong(key: String?, defValue: Long): Long {
                val value = prefs.all[key]
                if (value is Number) {
                    return value.toLong()
                }
                return defValue
            }

            fun getPrefs(): SharedPreferences = prefs
        }

        public override fun getPrefs(): SharedPreferences {
            return (preferenceManager.preferenceDataStore as InMemoryPreferenceDataStore).getPrefs()
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return super.onCreateView(inflater, container, savedInstanceState, true)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val activity = requireActivity() as EditProfileActivity
            val memPrefs = activity.getInMemoryPrefs()
            preferenceManager.preferenceDataStore = InMemoryPreferenceDataStore(memPrefs)

            super.onCreatePreferences(savedInstanceState, rootKey)

            findPreference<Preference>("nova_ui_font_scale_percent")?.isVisible = false
            findPreference<Preference>("option_reset_osc_preference")?.isVisible = false
            findPreference<Preference>("import_keyboard_file")?.isVisible = false
            findPreference<Preference>("export_keyboard_file")?.isVisible = false
            findPreference<Preference>("import_special_button_file")?.isVisible = false
            findPreference<Preference>("option_help_custom_keys")?.isVisible = false

            val patch = diff(
                PreferenceManager.getDefaultSharedPreferences(activity).all,
                memPrefs.all,
            )
            highlightPreferences(preferenceScreen, patch.keys)
        }

        override fun reloadSettings() {
            (requireActivity() as EditProfileActivity).reloadSettings()
        }

        private fun highlightPreferences(pref: Preference?, changedKeys: Set<String>) {
            if (pref == null) return

            if (pref is PreferenceGroup) {
                for (i in 0 until pref.preferenceCount) {
                    highlightPreferences(pref.getPreference(i), changedKeys)
                }
            } else {
                val key = pref.key
                if (key != null && changedKeys.contains(key)) {
                    pref.title = "*" + pref.title
                }
            }
        }

        private companion object {
            private fun diff(target: Map<String, *>, newPrefs: Map<String, *>): Map<String, Any?> {
                val patch = HashMap<String, Any?>()
                for ((key, value) in target) {
                    if (newPrefs.containsKey(key)) {
                        val defaultValue = newPrefs[key]
                        if (value == null || value != defaultValue) {
                            patch[key] = value
                        }
                    } else {
                        patch[key] = value
                    }
                }
                return patch
            }
        }
    }

    private class InMemorySharedPreferences(initialValues: Map<String, *>?) : SharedPreferences {
        private val values: MutableMap<String, Any?> = HashMap()

        init {
            if (initialValues != null) {
                values.putAll(initialValues)
            }
        }

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getString(key: String?, defValue: String?): String? {
            val value = values[key]
            return if (value is String) value else defValue
        }

        override fun getInt(key: String?, defValue: Int): Int {
            val value = values[key]
            if (value is Number) {
                return value.toInt()
            }
            return defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            val value = values[key]
            if (value is Number) {
                return value.toLong()
            }
            return defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            val value = values[key]
            return if (value is Float) value else defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            val value = values[key]
            return if (value is Boolean) value else defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val value = values[key]
            return if (value is MutableSet<*>) value as MutableSet<String> else defValues
        }

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
        }

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val changes: MutableMap<String, Any?> = HashMap()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                if (key != null) changes[key] = values
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                for ((key, value) in changes) {
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
                changes.clear()
            }
        }
    }
}
