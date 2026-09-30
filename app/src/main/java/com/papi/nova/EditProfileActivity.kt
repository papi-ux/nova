package com.papi.nova

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModel
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
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaField
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaMenuHeader
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.utils.UiHelper
import java.io.Serializable
import java.util.UUID

/**
 * The preset editor. Every edit lands in a draft ([Draft]) and nothing reaches the preset until
 * Save, which keeps it only once the file has saved. Back with changes not saved asks, in the right
 * edge panel, whether to save them, discard them or keep editing.
 */
class EditProfileActivity : NovaActivity() {
    private var currentProfile: SettingsProfile? = null
    private lateinit var draft: Draft
    private var prefsFragment: ProfilePreferenceFragment? = null
    private var legacyMode = false

    /**
     * The row whose setting only the Legacy screen has, while that screen shows for it. B goes back
     * to that row in the Compose editor instead of leaving the preset.
     */
    private var legacyFallbackRow: String? = null

    /** The editor's title, which Rename changes without rebuilding the screen. */
    private var editorTitle by mutableStateOf("")

    private val leaveCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = leaveEditor()
    }

    private val draftPrefs: InMemorySharedPreferences
        get() = draft.prefs!!

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)
        onBackPressedDispatcher.addCallback(this, leaveCallback)

        val profileUuid = intent.getStringExtra(EXTRA_PROFILE_UUID)
        if (profileUuid != null) {
            currentProfile = ProfilesManager.getInstance().getProfiles().firstOrNull { it.getUuid().toString() == profileUuid }
            if (currentProfile == null) {
                // A state page with Close, not a Toast over a screen that had already closed.
                showProfileNotFound()
                return
            }
        }

        draft = ViewModelProvider(this)[Draft::class.java].also { held ->
            // A recreate keeps the ViewModel and its draft. Only a new one, after the process was
            // stopped or on first open, starts from the saved state or from the preset itself.
            if (held.prefs == null) {
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                val saved = savedInstanceState?.getSerializable(STATE_DRAFT) as? HashMap<String, Any>
                held.prefs = InMemorySharedPreferences(saved ?: currentProfile?.getOptions() ?: emptyMap<String, Any>())
                held.name = savedInstanceState?.getString(STATE_DRAFT_NAME)
            }
        }
        legacyFallbackRow = savedInstanceState?.getString(STATE_LEGACY_FALLBACK_ROW)
        updateTitle()

        val fallbackRow = legacyFallbackRow
        if (fallbackRow != null) {
            showLegacyProfileEditor(fallbackFor = NovaSettingDefinitions.load(this).find(fallbackRow))
        } else if (NovaSettingsFeatureFlags.isComposeSettingsEnabled(this)) {
            showComposeProfileEditor()
        } else {
            showLegacyProfileEditor(fallbackFor = null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::draft.isInitialized) return
        outState.putSerializable(STATE_DRAFT, draftSnapshot())
        draft.name?.let { outState.putString(STATE_DRAFT_NAME, it) }
        legacyFallbackRow?.let { outState.putString(STATE_LEGACY_FALLBACK_ROW, it) }
    }

    /** The draft's values that a saved state can carry: everything a preset stores. */
    private fun draftSnapshot(): HashMap<String, Any> {
        val snapshot = HashMap<String, Any>()
        for ((key, value) in draftPrefs.all) {
            when (value) {
                is Set<*> -> snapshot[key] = HashSet(value)
                is Collection<*> -> snapshot[key] = ArrayList(value)
                is Serializable -> snapshot[key] = value
                else -> Unit
            }
        }
        return snapshot
    }

    private fun showComposeProfileEditor(returnToRow: String? = null) {
        legacyMode = false
        val definitions = NovaSettingsAvailability.filterForProfileEditor(
            NovaSettingsAvailability.filter(this, NovaSettingDefinitions.load(this))
        )
        val store = NovaSharedPreferencesSettingsStore(
            prefs = draftPrefs,
            fallbackPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        )
        val viewModel = ViewModelProvider(
            this,
            NovaSettingsViewModel.Factory(definitions, store)
        )[NovaSettingsViewModel::class.java]
        // The Legacy screen wrote to the same draft, so the rows read it again on the way back.
        if (returnToRow != null) viewModel.refresh()
        val content = ComposeView(this).apply {
            setContent {
                NovaComposeTheme {
                    NovaSettingsScreen(
                        viewModel = viewModel,
                        title = editorTitle,
                        subtitle = getString(R.string.nova_settings_profile_subtitle),
                        onBack = ::leaveEditor,
                        onOpenLegacy = {
                            NovaSettingsFeatureFlags.setComposeSettingsEnabled(this@EditProfileActivity, false)
                            showLegacyProfileEditor(fallbackFor = null)
                        },
                        onAction = ::handleComposeAction,
                        headerActions = listOf(
                            NovaSettingsHeaderAction(getString(R.string.nova_settings_profile_rename)) { showRenamePage() },
                            NovaSettingsHeaderAction(getString(R.string.nova_panel_save)) { saveProfile() }
                        ),
                        returnToRow = returnToRow,
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
                // Up is Back: it asks before changes are lost, and from a row's Legacy screen it
                // returns to that row.
                leaveEditor()
                true
            }
            R.id.action_save -> {
                saveProfile()
                true
            }
            R.id.action_rename -> {
                showRenamePage()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun reloadSettings() {
        val currentPrefs = prefsFragment?.getPrefs() ?: draftPrefs
        prefsFragment = ProfilePreferenceFragment(this, currentPrefs)
        supportFragmentManager.beginTransaction()
            .replace(R.id.preferences_container, prefsFragment!!)
            .commitAllowingStateLoss()
    }

    /**
     * The Legacy editor. For a row the Compose editor cannot show ([fallbackFor]), the toolbar says
     * which setting it is and that Back returns, and the list opens on that setting.
     */
    private fun showLegacyProfileEditor(fallbackFor: NovaSettingDefinition?) {
        legacyMode = true
        setContentView(R.layout.activity_edit_profile)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.subtitle = fallbackFor?.let { getString(R.string.nova_settings_profile_legacy_fallback, it.title) }
        invalidateOptionsMenu()

        prefsFragment = ProfilePreferenceFragment(this, draftPrefs)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.preferences_container, prefsFragment!!)
            .commit()
        fallbackFor?.let { prefsFragment?.scrollToPreference(it.key) }

        UiHelper.notifyNewRootView(this)
    }

    /** A row only the Legacy screen can show opens it there, and B brings the player back to the row. */
    private fun handleComposeAction(definition: NovaSettingDefinition) {
        legacyFallbackRow = definition.key
        showLegacyProfileEditor(fallbackFor = definition)
    }

    /**
     * Back, from B, the header, the toolbar's Up or the back gesture: out of a row's Legacy screen to
     * the row, then out of the editor, asking first when there are changes Save has not kept.
     */
    private fun leaveEditor() {
        if (!::draft.isInitialized) {
            finish()
            return
        }
        val row = legacyFallbackRow
        if (legacyMode && row != null) {
            legacyFallbackRow = null
            showComposeProfileEditor(returnToRow = row)
            return
        }
        if (hasUnsavedChanges()) showLeavePage() else finish()
    }

    /** Whether Save would change the preset: a value edited, or a name it does not have yet. */
    private fun hasUnsavedChanges(): Boolean {
        val saved = persistableOptions(currentProfile?.getOptions().orEmpty())
        val edited = persistableOptions(draftPrefs.all)
        val renamed = draft.name?.let { it != currentProfile?.getName() } ?: false
        return edited.keys != saved.keys || edited.any { (key, value) ->
            val old = saved[key]
            when {
                value is Number && old is Number -> {
                    // SharedPreferences stores fractional values as Float; JSON reads
                    // numbers as Double. Compare at the precision of the stored value.
                    if (value is Float || old is Float) value.toFloat() != old.toFloat()
                    else java.math.BigDecimal(value.toString()).compareTo(java.math.BigDecimal(old.toString())) != 0
                }
                else -> value != old
            }
        } || renamed
    }

    /** Save, Discard or Keep Editing, in the right edge panel. B keeps editing. */
    private fun showLeavePage() {
        NovaSurfaces.of(this).open(
            root = NovaCommonPage.Menu(
                key = LEAVE_PAGE,
                title = getString(R.string.profile_editor_unsaved_title),
                header = NovaMenuHeader(
                    title = draft.name ?: currentProfile?.getName() ?: getString(R.string.profile_manager_new_profile),
                    hint = getString(R.string.profile_editor_unsaved_hint),
                ),
                items = listOf(
                    NovaMenuItem.Action(
                        key = "save",
                        label = getString(R.string.nova_panel_save),
                        emphasis = true,
                        onClick = ::saveProfile,
                    ),
                    NovaMenuItem.Action(
                        key = "keep-editing",
                        label = getString(R.string.profile_editor_keep_editing),
                        onClick = {},
                    ),
                    NovaMenuItem.Destructive(
                        key = "discard",
                        label = getString(R.string.profile_editor_discard),
                        confirmLabel = getString(R.string.profile_editor_discard_confirm),
                        consequence = getString(R.string.profile_editor_discard_consequence),
                        stayLabel = getString(R.string.nova_settings_keep),
                        onConfirm = ::finish,
                    ),
                ),
            ),
            edge = NovaEdge.End,
            returnFocus = currentFocus?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None,
        )
    }

    /**
     * Saves the draft as the preset and closes the editor. The preset list changes only once the
     * file has saved; a failed save leaves the preset as it was, keeps the editor open with every
     * edit, and says so in the right edge panel with Try Again.
     */
    private fun saveProfile() {
        val options = persistableOptions(draftPrefs.all)
        val now = System.currentTimeMillis()
        val profile = currentProfile
        val candidate = if (profile != null) {
            SettingsProfile(profile.getUuid(), draft.name ?: profile.getName(), profile.getCreatedUtc(), now, options)
                .also { it.setActive(profile.isActive()) }
        } else {
            val name = draft.name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: (getString(R.string.profile_manager_profile) + (ProfilesManager.getInstance().getProfiles().size + 1))
            SettingsProfile(UUID.randomUUID(), name, now, now, options)
        }

        if (ProfilesManager.getInstance().commit(this, candidate)) {
            // The preset list shows the saved preset; nothing floats over it to say so.
            finish()
        } else {
            showSaveFailed()
        }
    }

    private fun showSaveFailed() {
        NovaSurfaces.of(this).present(
            NovaCommonPage.Notice(
                key = SAVE_FAILED_PAGE,
                title = getString(R.string.profile_editor_save_failed_title),
                message = getString(R.string.profile_editor_save_failed_message),
                primary = NovaAction(getString(R.string.nova_panel_try_again), run = ::saveProfile),
                closeLabel = getString(R.string.profile_editor_keep_editing),
            ),
        )
    }

    private fun showProfileNotFound() {
        val close = NovaAction(getString(R.string.nova_panel_close)) { finish() }
        NovaSurfaces.of(this).show(
            NovaStatePage.Problem(
                key = PROFILE_NOT_FOUND_PAGE,
                title = getString(R.string.profile_manager_profile_not_found),
                message = getString(R.string.profile_editor_not_found_message),
                primary = close,
                back = NovaProblemBack.Close(close),
            ),
        )
    }

    /**
     * Rename, as a Form page in the right-edge panel: the name at the top with Save under it. A
     * blank name stays on the page with the reason under the field. The name is part of the draft,
     * so it reaches the preset with Save, as every other change does.
     */
    private fun showRenamePage() {
        val initial = draft.name ?: currentProfile?.getName() ?: ""
        NovaSurfaces.of(this).open(
            root = NovaCommonPage.Form(
                key = "rename-profile",
                title = getString(R.string.profile_manager_edit_profile_name),
                fields = listOf(
                    NovaField(key = RENAME_FIELD, label = getString(R.string.nova_settings_profile_name), initial = initial),
                ),
                submitLabel = getString(R.string.nova_panel_save),
                onSubmit = { values ->
                    val newName = values[RENAME_FIELD].orEmpty().trim()
                    if (newName.isEmpty()) {
                        getString(R.string.profile_manager_name_cannot_be_blank)
                    } else {
                        rename(newName)
                        null
                    }
                },
            ),
            edge = NovaEdge.End,
            returnFocus = currentFocus?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None,
        )
    }

    private fun rename(newName: String) {
        draft.name = newName
        updateTitle()
    }

    private fun updateTitle() {
        val name = draft.name ?: currentProfile?.getName()
        val text = when {
            currentProfile != null -> getString(R.string.profile_manager_edit_profile_with, name)
            name != null -> getString(R.string.profile_manager_new_profile_with, name)
            else -> getString(R.string.profile_manager_new_profile)
        }
        title = text
        editorTitle = text
    }

    fun getInMemoryPrefs(): SharedPreferences = draftPrefs

    /**
     * The preset being edited, held outside the screen: a recreate (the device turned, a new text
     * size) keeps it through this ViewModel, and the saved state brings it back after Android has
     * stopped the app in the background.
     */
    class Draft : ViewModel() {
        internal var prefs: InMemorySharedPreferences? = null

        /** A name given with Rename that Save has not kept yet. */
        var name: String? = null
    }

    private companion object {
        const val EXTRA_PROFILE_UUID = "profileUuid"
        const val RENAME_FIELD = "name"
        const val LEAVE_PAGE = "profile-unsaved"
        const val SAVE_FAILED_PAGE = "profile-save-failed"
        const val PROFILE_NOT_FOUND_PAGE = "profile-not-found"
        const val STATE_DRAFT = "com.papi.nova.profile.DRAFT"
        const val STATE_DRAFT_NAME = "com.papi.nova.profile.DRAFT_NAME"
        const val STATE_LEGACY_FALLBACK_ROW = "com.papi.nova.profile.LEGACY_FALLBACK_ROW"

        /** What a preset keeps of [values]: every value set, less the settings that belong to the device. */
        fun persistableOptions(values: Map<String, *>): Map<String, Any> {
            val options = HashMap<String, Any>()
            for ((key, value) in values) {
                if (value != null && NovaSettingsAvailability.shouldPersistProfileOverride(key)) {
                    options[key] = value
                }
            }
            return options
        }
    }

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

        private val correctedStreamKeys = mutableSetOf<String>()

        override fun onStreamPreferenceCorrected(key: String) { correctedStreamKeys += key }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val activity = requireActivity() as EditProfileActivity
            val memPrefs = activity.getInMemoryPrefs()
            preferenceManager.preferenceDataStore = InMemoryPreferenceDataStore(memPrefs)

            // AndroidX persists XML defaults during inflation when a data store is installed.
            // A saved setup must retain only its actual overrides until the player edits it.
            val overrides = memPrefs.all.filterValues { it != null }.mapValues { it.value!! }
            correctedStreamKeys.clear()
            super.onCreatePreferences(savedInstanceState, rootKey)
            val migration = com.papi.nova.preferences.NovaSettingsMigration
            // Keep corrections to actual overrides, then discard XML-only defaults. A bitrate
            // calculated during inflation saw those defaults instead of inherited stream values.
            val corrections = memPrefs.all.filterKeys { it in correctedStreamKeys && it in overrides }
            memPrefs.edit().clear().apply()
            migration.writeDifference(memPrefs, overrides + corrections)
            if (corrections.isNotEmpty()) {
                resetBitrateToDefault(memPrefs, null, null)
            }

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
                for ((key, value) in newPrefs) {
                    val inherited = target[key]
                    val same = if (value is Number && inherited is Number)
                        value.toDouble() == inherited.toDouble() else value == inherited
                    if (!target.containsKey(key) || !same) patch[key] = value
                }
                return patch
            }
        }
    }

    internal class InMemorySharedPreferences(initialValues: Map<String, *>?) : SharedPreferences {
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
            return if (value is Number) value.toFloat() else defValue
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
