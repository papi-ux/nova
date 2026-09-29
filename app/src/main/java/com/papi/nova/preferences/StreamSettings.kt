@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.papi.nova.preferences

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Vibrator
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.NovaActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.papi.nova.BuildConfig
import com.papi.nova.DebugInfoActivity
import com.papi.nova.LimeLog
import com.papi.nova.PcView
import com.papi.nova.R
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader
import com.papi.nova.binding.video.MediaCodecHelper
import com.papi.nova.ui.NovaSnackbar
import com.papi.nova.ui.NovaSpecialKeyPrefs
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.binding.video.PyroWaveAvailability
import androidx.compose.ui.text.AnnotatedString
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.FileUriUtils
import com.papi.nova.utils.HelpLauncher
import com.papi.nova.utils.PerformanceDataTracker
import com.papi.nova.utils.ServerHelper.getActiveDisplay
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StreamSettings : NovaActivity() {
    private lateinit var previousPrefs: PreferenceConfiguration
    private var previousDisplayPixelCount = 0
    private var prefsFragment: SettingsFragment? = null
    private var legacyMode = false
    /**
     * The row whose setting only the legacy screen has, while that screen shows for it, so B goes
     * back to that row in Compose.
     */
    private var legacyFallbackRow: String? = null

    /**
     * Back leaves Settings, from the legacy screen and from Compose Settings when its pane has
     * nothing left to go back through. The key gate and the back gesture reach the dispatcher
     * directly, never an onBackPressed override, so the language check lives here.
     */
    private val leaveCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val row = legacyFallbackRow
            if (legacyMode && row != null) {
                // B goes back one level, to the Compose row that opened the legacy screen, not out
                // of Settings altogether, and focus lands on that row again.
                legacyFallbackRow = null
                showComposeSettings(returnToRow = row)
            } else {
                leaveSettings()
            }
        }
    }

    fun reloadSettings() {
        if (!legacyMode) {
            showComposeSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = getActiveDisplay(this, previousPrefs).mode
            previousDisplayPixelCount = mode.physicalWidth * mode.physicalHeight
        }
        prefsFragment = SettingsFragment(
            PreferenceConfiguration.readPreferences(
                this,
                PreferenceManager.getDefaultSharedPreferences(this)
            )
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.stream_settings, prefsFragment!!)
            .commitAllowingStateLoss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        previousPrefs = PreferenceConfiguration.readPreferences(this)
        UiHelper.setLocale(this)
        onBackPressedDispatcher.addCallback(this, leaveCallback)
        if (shouldShowComposeSettings()) {
            showComposeSettings()
        } else {
            showLegacySettings()
        }
    }

    private fun shouldShowComposeSettings(): Boolean {
        if (intent.getBooleanExtra(NovaSettingsFeatureFlags.EXTRA_FORCE_LEGACY, false)) {
            return false
        }
        return NovaSettingsFeatureFlags.isComposeSettingsEnabled(this)
    }

    private fun showComposeSettings(returnToRow: String? = null) {
        val fromLegacy = legacyMode
        legacyMode = false
        val maxPanelFps = NovaDisplayFpsCapability.maxSupportedFps(windowManager.defaultDisplay)
        coerceStoredFpsToPanel(maxPanelFps)
        val canonicalDefinitions = NovaSettingDefinitions.load(this)
        val definitions = NovaSettingsAvailability.filter(this, canonicalDefinitions).let { filtered ->
            filtered.copy(
                settings = filtered.settings
                    // Custom bitrate saved a string only the legacy listener turned into the
                    // bitrate, so on this screen it saved nothing. The bitrate row's exact page
                    // types the same number, in Mbps.
                    .filterNot {
                        it.key == NovaSettingsFeatureFlags.COMPOSE_SETTINGS_KEY ||
                            it.key == PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING
                    }
                    .map { definition -> cullFpsOptionsToPanel(definition, maxPanelFps) }
            )
        }
        val store = NovaSettingsRepository.create(this)
        val viewModel = ViewModelProvider(
            this,
            NovaSettingsViewModel.Factory(
                definitions = definitions,
                store = store,
                resetDefinitions = canonicalDefinitions
            )
        )[NovaSettingsViewModel::class.java]
        // The legacy screen wrote the same preferences, so the rows read them again on the way back.
        if (fromLegacy) viewModel.refresh()
        val content = ComposeView(this).apply {
            setContent {
                NovaComposeTheme {
                    NovaSettingsScreen(
                        viewModel = viewModel,
                        title = getString(R.string.pcview_quick_settings),
                        subtitle = getString(
                            R.string.nova_settings_subtitle_with_version,
                            NovaAppVersion.current()
                        ),
                        onBack = ::leaveSettings,
                        onOpenLegacy = {
                            NovaSettingsFeatureFlags.setComposeSettingsEnabled(this@StreamSettings, false)
                            showLegacySettings()
                        },
                        onAction = ::handleComposeAction,
                        returnToRow = returnToRow,
                    )
                }
            }
        }
        setContentView(content)
        UiHelper.notifyNewRootView(this)
    }

    // The legacy fragment culls the fps ListPreference to what the panel can present
    // (removeEntryFromListAndSetValue below); the Compose screen reads the raw XML
    // definitions, so it applies the same panel rules here or 90/120 get offered on
    // panels that cannot show them.
    private fun cullFpsOptionsToPanel(
        definition: NovaSettingDefinition,
        maxPanelFps: Float
    ): NovaSettingDefinition {
        if (definition.key != PreferenceConfiguration.FPS_PREF_STRING) {
            return definition
        }
        val allowed = NovaDisplayFpsCapability.allowedFpsValues(maxPanelFps).map(Int::toString)
        return definition.copy(options = definition.options.filter { it.value in allowed })
    }

    private fun coerceStoredFpsToPanel(maxPanelFps: Float) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val stored = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, null)?.toIntOrNull() ?: return
        val coerced = NovaDisplayFpsCapability.coerce(stored, maxPanelFps)
        if (coerced != stored) {
            prefs.edit().putString(PreferenceConfiguration.FPS_PREF_STRING, coerced.toString()).apply()
        }
    }

    /**
     * The legacy screen. For a row Compose cannot handle ([fallbackFor]) it says which setting it is
     * showing and that Back returns, and its list opens on that setting.
     */
    private fun showLegacySettings(fallbackFor: NovaSettingDefinition? = null) {
        legacyMode = true
        setContentView(R.layout.activity_stream_settings)

        findViewById<View>(R.id.modernSettingsButton)?.setOnClickListener {
            NovaSettingsFeatureFlags.setComposeSettingsEnabled(this@StreamSettings, true)
            legacyFallbackRow = null
            showComposeSettings()
        }
        findViewById<TextView>(R.id.legacyFallbackNote)?.let { note ->
            note.text = fallbackFor?.let { getString(R.string.nova_settings_legacy_fallback, it.title) }
            note.visibility = if (fallbackFor != null) View.VISIBLE else View.GONE
        }

        findViewById<View>(R.id.settingsHeader)?.let { header ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                header.setOnApplyWindowInsetsListener { view, insets ->
                    val topInset = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                            insets.getInsets(WindowInsets.Type.statusBars()).top
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                            insets.systemWindowInsetTop
                        else -> 0
                    }
                    view.setPadding(
                        view.paddingLeft,
                        topInset + UiHelper.dpToPx(this, 16f).toInt(),
                        view.paddingRight,
                        view.paddingBottom
                    )
                    insets
                }
                header.requestApplyInsets()
            }
        }
        reloadSettings()
        fallbackFor?.let { prefsFragment?.scrollToPreference(it.key) }
        UiHelper.notifyNewRootView(this)
    }

    private fun handleComposeAction(definition: NovaSettingDefinition) {
        when (definition.key) {
            "nova_app_version" -> Unit
            "pref_debug_info" -> startActivity(Intent(this, DebugInfoActivity::class.java))
            "nova_report_problem" -> com.papi.nova.diagnostics.SupportReportSharing.share(
                this,
                com.papi.nova.BuildConfig.VERSION_NAME,
            )
            "option_software_release" -> checkForNovaUpdate()
            "option_follow_update" -> HelpLauncher.launchUrl(this, getString(R.string.obtainium_app_url))
            // Confirmed in its own row already; it had sent the player to the legacy screen.
            "option_reset_osc_preference" ->
                com.papi.nova.binding.input.virtual_controller.VirtualControllerConfigurationLoader.clearProfile(this)
            else -> {
                legacyFallbackRow = definition.key
                showLegacySettings(fallbackFor = definition)
            }
        }
    }

    private fun checkForNovaUpdate() {
        NovaSnackbar.show(this, getString(R.string.nova_update_checking))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    NovaUpdateChecker.checkLatest()
                }
            }

            result.onSuccess { updateResult ->
                showNovaUpdateResult(updateResult)
            }.onFailure { error ->
                showNovaUpdateError(error)
            }
        }
    }

    private fun showNovaUpdateResult(result: NovaUpdateCheckResult) {
        when (result) {
            is NovaUpdateCheckResult.UpdateAvailable -> showNovaUpdateAvailable(result.release)
            is NovaUpdateCheckResult.UpToDate -> showNovaUpdateCurrent(result.release)
        }
    }

    private fun showNovaUpdateAvailable(release: NovaUpdateRelease) {
        val message = if (release.apkAssetName != null) {
            getString(
                R.string.nova_update_available_message_with_apk,
                release.versionName,
                NovaAppVersion.current(),
                release.apkAssetName
            )
        } else {
            getString(
                R.string.nova_update_available_message,
                release.versionName,
                NovaAppVersion.current()
            )
        }
        val primary = if (release.apkDownloadUrl != null) {
            NovaAction(getString(R.string.nova_update_download_apk)) { startNovaUpdateInstall(release) }
        } else {
            NovaAction(getString(R.string.nova_update_open_release)) { HelpLauncher.launchUrl(this, release.releaseUrl) }
        }
        NovaSurfaces.of(this).present(
            NovaCommonPage.Notice(
                key = "nova-update-available",
                title = getString(R.string.nova_update_available_title),
                message = message,
                primary = primary,
                closeLabel = getString(R.string.nova_panel_close),
                help = NovaAction(getString(R.string.nova_update_release_notes)) {
                    HelpLauncher.launchUrl(this, release.releaseUrl)
                },
            ),
        )
    }

    private fun startNovaUpdateInstall(release: NovaUpdateRelease) {
        val spinner = SpinnerDialog.displayDialog(
            this,
            getString(R.string.nova_update_downloading_title),
            getString(R.string.nova_update_downloading_message, release.versionName, 0),
            false
        )
        lifecycleScope.launch {
            try {
                val result = NovaUpdateInstaller.downloadValidateAndInstall(this@StreamSettings, release) { progress ->
                    spinner.setMessage(
                        if (progress >= 100) {
                            getString(R.string.nova_update_verifying_message)
                        } else {
                            getString(R.string.nova_update_downloading_message, release.versionName, progress)
                        }
                    )
                }
                NovaUpdateInstaller.showInstallResult(
                    this@StreamSettings,
                    release,
                    result,
                    onRetry = { startNovaUpdateInstall(it) },
                    onViewReleases = {
                        HelpLauncher.launchUrl(this@StreamSettings, "https://github.com/papi-ux/nova/releases")
                    },
                )
            } finally {
                NovaUpdateInstaller.dismissIfAlive(this@StreamSettings) { spinner.dismiss() }
            }
        }
    }

    private fun showNovaUpdateCurrent(release: NovaUpdateRelease) {
        NovaSurfaces.of(this).present(
            NovaCommonPage.Notice(
                key = "nova-update-current",
                title = getString(R.string.nova_update_current_title),
                message = getString(
                    R.string.nova_update_current_message,
                    NovaAppVersion.current(),
                    release.tagName
                ),
                closeLabel = getString(R.string.nova_panel_close),
                help = NovaAction(getString(R.string.nova_update_view_releases)) {
                    HelpLauncher.launchUrl(this, release.releaseUrl)
                },
            ),
        )
    }

    private fun showNovaUpdateError(error: Throwable) {
        NovaUpdateInstaller.showCheckError(
            this,
            error,
            onRetry = { checkForNovaUpdate() },
            onViewReleases = {
                HelpLauncher.launchUrl(this, "https://github.com/papi-ux/nova/releases")
            },
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            val insets = window.decorView.rootWindowInsets
            if (insets != null) {
                displayCutoutP = insets.displayCutout
            }
        }
        if (legacyMode) {
            reloadSettings()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (legacyMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = getActiveDisplay(this, previousPrefs).mode
            if (mode.physicalWidth * mode.physicalHeight != previousDisplayPixelCount) {
                reloadSettings()
            }
        }
    }

    /**
     * Leaves Settings. A changed language restarts the app so every screen picks it up: before
     * Android 13 at the host list, and from 13 on only when the language went back to the system
     * default, which a running process cannot take.
     */
    private fun leaveSettings() {
        finish()

        val newPrefs = PreferenceConfiguration.readPreferences(this)
        if (newPrefs.language != previousPrefs.language) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(this, PcView::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent, null)
            } else if (newPrefs.language == PreferenceConfiguration.DEFAULT_LANGUAGE) {
                // Deliberately still a Toast. A Toast is a system window that outlives the
                // process; a Snackbar lives in this activity's view hierarchy, and the
                // System.exit below kills the process before one could ever be drawn. The
                // message would simply vanish.
                Toast.makeText(
                    this,
                    getString(R.string.nova_language_reset_restart),
                    Toast.LENGTH_LONG
                ).show()
                System.exit(0)
            }
        }
    }

    open class SettingsFragment() : PreferenceFragmentCompat() {
        private var nativeResolutionStartIndex = Int.MAX_VALUE
        private var nativeFramerateShown = false
        private var prevPrefConfig: PreferenceConfiguration? = null

        constructor(prefCfg: PreferenceConfiguration) : this() {
            prevPrefConfig = prefCfg
        }

        private fun removeIfExists(category: PreferenceCategory?, key: String) {
            val pref = findPreference<Preference>(key)
            if (category != null && pref != null) {
                category.removePreference(pref)
            }
        }

        open fun getPrefs(): SharedPreferences = preferenceManager.sharedPreferences!!

        private fun setValue(preferenceKey: String, value: String) {
            findPreference<ListPreference>(preferenceKey)!!.value = value
        }

        private fun appendPreferenceEntry(
            pref: ListPreference,
            newEntryName: String,
            newEntryValue: String
        ) {
            val newEntries = Arrays.copyOf(pref.entries, pref.entries.size + 1)
            val newValues = Arrays.copyOf(pref.entryValues, pref.entryValues.size + 1)
            newEntries[newEntries.size - 1] = newEntryName
            newValues[newValues.size - 1] = newEntryValue
            pref.entries = newEntries
            pref.entryValues = newValues
        }

        private fun addNativeResolutionEntry(
            nativeWidth: Int,
            nativeHeight: Int,
            insetsRemoved: Boolean,
            portrait: Boolean,
            isCustom: Boolean
        ) {
            val pref = findPreference<ListPreference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!
            var newName = if (insetsRemoved) {
                resources.getString(R.string.resolution_prefix_native_fullscreen)
            } else if (isCustom) {
                resources.getString(R.string.resolution_prefix_custom)
            } else {
                resources.getString(R.string.resolution_prefix_native)
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                newName += if (portrait) {
                    " " + resources.getString(R.string.resolution_prefix_native_portrait)
                } else {
                    " " + resources.getString(R.string.resolution_prefix_native_landscape)
                }
            }

            newName += " (${nativeWidth}x$nativeHeight)"
            val newValue = "${nativeWidth}x$nativeHeight"

            for (value in pref.entryValues) {
                if (newValue == value.toString()) {
                    return
                }
            }

            if (pref.entryValues.size < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.entryValues.size
            }
            appendPreferenceEntry(pref, newName, newValue)
        }

        private fun addNativeResolutionEntries(
            nativeWidth: Int,
            nativeHeight: Int,
            insetsRemoved: Boolean,
            isCustom: Boolean
        ) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true, isCustom)
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false, isCustom)
        }

        private fun addNativeFrameRateEntry(framerate: Float, isCustom: Boolean) {
            var activeFramerate = framerate
            if (!isCustom) {
                activeFramerate = Math.round(activeFramerate).toFloat()
                if (activeFramerate == 0f) {
                    return
                }
            }

            val pref = findPreference<ListPreference>(PreferenceConfiguration.FPS_PREF_STRING)!!
            val fpsValue = if (isCustom) activeFramerate.toString() else Math.round(activeFramerate).toString()
            val fpsName = (
                if (isCustom) resources.getString(R.string.resolution_prefix_custom)
                else resources.getString(R.string.resolution_prefix_native)
                ) + " ($fpsValue ${resources.getString(R.string.fps_suffix_fps)})"

            for (value in pref.entryValues) {
                if (fpsValue == value.toString()) {
                    nativeFramerateShown = false
                    return
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue)
            nativeFramerateShown = true
        }

        private fun removeValue(preferenceKey: String, value: String, onMatched: Runnable) {
            val pref = findPreference<ListPreference>(preferenceKey)!!
            var matchingCount = 0
            for (seq in pref.entryValues) {
                if (seq.toString().equals(value, ignoreCase = true)) {
                    matchingCount++
                }
            }

            val entries = arrayOfNulls<CharSequence>(pref.entries.size - matchingCount)
            val entryValues = arrayOfNulls<CharSequence>(pref.entryValues.size - matchingCount)
            var outIndex = 0
            for (i in pref.entryValues.indices) {
                if (pref.entryValues[i].toString().equals(value, ignoreCase = true)) {
                    continue
                }
                entries[outIndex] = pref.entries[i]
                entryValues[outIndex] = pref.entryValues[i]
                outIndex++
            }

            if (pref.value?.equals(value, ignoreCase = true) == true) {
                onMatched.run()
            }

            pref.entries = entries.requireNoNulls()
            pref.entryValues = entryValues.requireNoNulls()
        }

        private fun resetBitrateToDefault(prefs: SharedPreferences, res: String?, fps: String?) {
            val activeRes = res ?: prefs.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.DEFAULT_RESOLUTION
            ) ?: PreferenceConfiguration.DEFAULT_RESOLUTION
            val activeFps = fps ?: prefs.getString(
                PreferenceConfiguration.FPS_PREF_STRING,
                PreferenceConfiguration.DEFAULT_FPS
            ) ?: PreferenceConfiguration.DEFAULT_FPS

            prefs.edit()
                .putInt(
                    PreferenceConfiguration.BITRATE_PREF_STRING,
                    PreferenceConfiguration.getDefaultBitrate(activeRes, activeFps)
                )
                .apply()
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            UiHelper.applyStatusBarPadding(view)
            return view
        }

        fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
            unused: Boolean
        ): View {
            return super.onCreateView(inflater, container, savedInstanceState)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            initializePreferences()
        }

        private fun applySemanticPreferenceLayouts(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                if (preference !is PreferenceCategory) {
                    preference.layoutResource = R.layout.nova_preference_semantic
                }
                if (preference is PreferenceGroup) {
                    applySemanticPreferenceLayouts(preference)
                }
            }
        }

        fun initializePreferences() {
            if (prevPrefConfig == null) {
                prevPrefConfig = PreferenceConfiguration.readPreferences(requireContext())
            }
            val prefConfig = prevPrefConfig!!

            addPreferencesFromResource(R.xml.preferences)
            if (NovaThemeManager.getTheme(requireContext()) == NovaThemeManager.THEME_MATERIAL_YOU) {
                applySemanticPreferenceLayouts(preferenceScreen)
            }

            findPreference<Preference>("nova_app_version")?.summary = NovaAppVersion.current()

            findPreference<Preference>("nova_theme")?.setOnPreferenceChangeListener { _, newValue ->
                NovaThemeManager.setTheme(requireContext(), newValue as String)
                val activity = requireActivity()
                val intent = activity.intent
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.finish()
                activity.startActivity(intent)
                true
            }

            findPreference<Preference>(NovaSettingsFeatureFlags.COMPOSE_SETTINGS_KEY)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        NovaSettingsFeatureFlags.setComposeSettingsEnabled(requireContext(), true)
                        requireActivity().recreate()
                    }
                    true
                }

            findPreference<Preference>("nova_stream_preset")?.setOnPreferenceChangeListener { _, newValue ->
                val preset = StreamPreset.fromKey(newValue as String)
                if (preset != null) {
                    getPrefs().edit()
                        .putString("list_resolution", preset.resolution)
                        .putInt("seekbar_bitrate_kbps", preset.bitrateKbps)
                        .putString("video_format", preset.codec)
                        .apply()

                    preferenceScreen.removeAll()
                    initializePreferences()
                }
                true
            }

            val activity = requireActivity() as AppCompatActivity
            val pm = activity.packageManager

            if (BuildConfig.FDROID_BUILD) {
                val advanced = findPreference<PreferenceCategory>("category_advanced")
                removeIfExists(advanced, "option_software_release")
                removeIfExists(advanced, "option_follow_update")
            } else {
                findPreference<Preference>("option_software_release")?.setOnPreferenceClickListener {
                    (requireActivity() as? StreamSettings)?.checkForNovaUpdate()
                    true
                }
            }

            if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                val overlays = findPreference<PreferenceCategory>("category_overlays")
                if (overlays != null) {
                    removeIfExists(overlays, "list_onscreen_controls_layout_preset")
                    removeIfExists(overlays, "checkbox_hide_osc_when_has_gamepad")
                    removeIfExists(overlays, "checkbox_vibrate_osc")
                    removeIfExists(overlays, "seekbar_osc_opacity")
                    removeIfExists(overlays, "checkbox_only_show_L3R3")
                    removeIfExists(overlays, "checkbox_show_guide_button")
                    removeIfExists(overlays, "seekbar_osc_free_analog_stick_opacity")
                    removeIfExists(overlays, "checkbox_enable_analog_stick_new")
                    removeIfExists(overlays, "option_reset_osc_preference")
                    removeIfExists(overlays, "checkbox_show_onscreen_controls")
                    removeIfExists(overlays, "keyboard_axi_list")
                    removeIfExists(overlays, "import_keyboard_file")
                    removeIfExists(overlays, "export_keyboard_file")
                    removeIfExists(overlays, "checkbox_enable_keyboard")
                }
            }

            val inputCategory = findPreference<PreferenceCategory>("category_input")

            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                requireActivity().packageManager.hasSystemFeature("com.nvidia.feature.shield")
            ) {
                removeIfExists(inputCategory, "checkbox_absolute_mouse_mode")
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                removeIfExists(inputCategory, "checkbox_gamepad_motion_sensors")
            }

            if (
                !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
            ) {
                removeIfExists(inputCategory, "checkbox_force_device_motion")
                removeIfExists(inputCategory, "checkbox_gamepad_motion_fallback")
            }

            if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                removeIfExists(inputCategory, "checkbox_usb_bind_all")
                removeIfExists(inputCategory, "checkbox_usb_driver")
            }

            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !pm.hasSystemFeature("android.software.picture_in_picture") ||
                pm.hasSystemFeature("com.amazon.software.fireos")
            ) {
                val advanced = findPreference<PreferenceCategory>("category_advanced")
                removeIfExists(advanced, "checkbox_enable_pip")
            }

            val vibrator = requireActivity().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vibrator.hasVibrator()) {
                removeIfExists(inputCategory, "checkbox_vibrate_fallback")
                removeIfExists(inputCategory, "seekbar_vibrate_fallback_strength")
                removeIfExists(inputCategory, "checkbox_enable_device_rumble")
                val overlays = findPreference<PreferenceCategory>("category_overlays")
                if (overlays != null) {
                    removeIfExists(overlays, "checkbox_vibrate_osc")
                }
            } else if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !vibrator.hasAmplitudeControl()
            ) {
                removeIfExists(inputCategory, "seekbar_vibrate_fallback_strength")
            }

            val customResStr = prefConfig.customResolution
            if (!customResStr.isNullOrEmpty()) {
                val resolutionSegments = customResStr.split("x")
                if (resolutionSegments.size == 2) {
                    try {
                        addNativeResolutionEntries(
                            resolutionSegments[0].toInt(),
                            resolutionSegments[1].toInt(),
                            false,
                            true
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            val customRefreshRateStr = prefConfig.customRefreshRate
            if (!customRefreshRateStr.isNullOrEmpty()) {
                try {
                    val customRefreshRateValue = customRefreshRateStr.toFloat()
                    if (customRefreshRateValue > 0) {
                        addNativeFrameRateEntry(customRefreshRateValue, true)
                    }
                } catch (e: NumberFormatException) {
                    getPrefs().edit().remove(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING).apply()
                }
            }

            val display = activity.windowManager.defaultDisplay
            val maxSupportedFps = NovaDisplayFpsCapability.maxSupportedFps(display)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var maxSupportedResW = 0
                var hasInsets = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val cutout: DisplayCutout? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        display.cutout
                    } else {
                        displayCutoutP
                    }

                    if (cutout != null) {
                        val widthInsets = cutout.safeInsetLeft + cutout.safeInsetRight
                        val heightInsets = cutout.safeInsetBottom + cutout.safeInsetTop

                        if (widthInsets != 0 || heightInsets != 0) {
                            val metrics = DisplayMetrics()
                            display.getRealMetrics(metrics)

                            val width = maxOf(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)
                            val height = minOf(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)

                            addNativeResolutionEntries(width, height, false, false)
                            hasInsets = true
                        }
                    }
                }

                for (candidate in display.supportedModes) {
                    val width = maxOf(candidate.physicalWidth, candidate.physicalHeight)
                    val height = minOf(candidate.physicalWidth, candidate.physicalHeight)

                    if (
                        !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                        width > 3840 ||
                        height > 2160
                    ) {
                        addNativeResolutionEntries(width, height, hasInsets, false)
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840
                    } else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560
                    } else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920
                    }
                }

                MediaCodecHelper.initialize(
                    requireContext(),
                    GlPreferences.readPreferences(requireContext()).glRenderer
                )

                val avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1)
                val hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)

                if (avcDecoder != null) {
                    val avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc")
                        .videoCapabilities!!
                        .supportedWidths

                    LimeLog.info("AVC supported width range: ${avcWidthRange.lower} - ${avcWidthRange.upper}")

                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840
                        } else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920
                        } else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280
                        }
                    }
                }

                if (hevcDecoder != null) {
                    val hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc")
                        .videoCapabilities!!
                        .supportedWidths

                    LimeLog.info("HEVC supported width range: ${hevcWidthRange.lower} - ${hevcWidthRange.upper}")

                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840
                        } else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920
                        } else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: $maxSupportedResW")

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        removeEntryFromListAndSetValue(
                            PreferenceConfiguration.RESOLUTION_PREF_STRING,
                            PreferenceConfiguration.RES_4K,
                            PreferenceConfiguration.RES_1440P
                        )
                    }
                    if (maxSupportedResW < 2560) {
                        removeEntryFromListAndSetValue(
                            PreferenceConfiguration.RESOLUTION_PREF_STRING,
                            PreferenceConfiguration.RES_1440P,
                            PreferenceConfiguration.RES_1080P
                        )
                    }
                    if (maxSupportedResW < 1920) {
                        removeEntryFromListAndSetValue(
                            PreferenceConfiguration.RESOLUTION_PREF_STRING,
                            PreferenceConfiguration.RES_1080P,
                            PreferenceConfiguration.RES_720P
                        )
                    }
                }
            } else {
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                val width = maxOf(metrics.widthPixels, metrics.heightPixels)
                val height = minOf(metrics.widthPixels, metrics.heightPixels)
                addNativeResolutionEntries(width, height, false, false)
            }

            val allowedFpsValues = NovaDisplayFpsCapability.allowedFpsValues(maxSupportedFps)
            // Cull every standard option this panel cannot present, fastest first, and
            // move a stored value straight to the fastest option that survives: the
            // same answer NovaDisplayFpsCapability.coerce() gives Play Setup.
            NovaDisplayFpsCapability.STANDARD_FPS_VALUES
                .filter { it > 60 && it !in allowedFpsValues }
                .sortedDescending()
                .forEach { fps ->
                    removeEntryFromListAndSetValue(
                        PreferenceConfiguration.FPS_PREF_STRING,
                        fps.toString(),
                        NovaDisplayFpsCapability.coerce(fps, maxSupportedFps).toString(),
                    )
                }
            addNativeFrameRateEntry(maxSupportedFps, false)

            findPreference<CheckBoxPreference>(PreferenceConfiguration.UNLOCK_FPS_STRING)?.let { unlockFpsPref ->
                if (90 !in allowedFpsValues) {
                    unlockFpsPref.isEnabled = false
                    unlockFpsPref.summary = getString(
                        R.string.summary_unlock_fps_display_cap,
                        Math.round(maxSupportedFps)
                    )
                } else {
                    unlockFpsPref.isEnabled = true
                    unlockFpsPref.setSummary(R.string.summary_unlock_fps)
                }

                unlockFpsPref.setOnPreferenceChangeListener { _, _ ->
                    reloadSettings()
                    true
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS")
                val category = findPreference<PreferenceCategory>("category_stream_quality")
                findPreference<Preference>("checkbox_enable_hdr")?.let {
                    category?.removePreference(it)
                }
            } else {
                val hdrCaps = display.hdrCapabilities
                Log.d("HDR CAP", display.toString())
                var foundHdr10 = false
                if (hdrCaps != null) {
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true
                            break
                        }
                    }
                }

                val category = findPreference<PreferenceCategory>("category_stream_quality")
                val hdrPref = category?.findPreference<CheckBoxPreference>("checkbox_enable_hdr")

                if (!foundHdr10 && hdrPref != null) {
                    LimeLog.info("Keeping HDR toggle visible for 10-bit SDR opt-in")
                    hdrPref.setSummary(R.string.summary_enable_hdr_sdr_10bit)
                } else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware")
                    if (hdrPref != null) {
                        hdrPref.isEnabled = false
                        hdrPref.isChecked = false
                        hdrPref.summary = "Update the firmware on your NVIDIA SHIELD Android TV to enable HDR"
                    }
                }
            }

            findPreference<Preference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!
                .setOnPreferenceChangeListener { preference, newValue ->
                    val prefs = getPrefs()
                    val valueStr = newValue as String
                    val values = (preference as ListPreference).entryValues
                    var isNativeRes = true
                    for (i in values.indices) {
                        if (valueStr == values[i].toString() && i < nativeResolutionStartIndex) {
                            isNativeRes = false
                            break
                        }
                    }

                    if (isNativeRes) {
                        Dialog.displayDialog(
                            activity,
                            resources.getString(R.string.title_native_res_dialog),
                            resources.getString(R.string.text_native_res_dialog),
                            false
                        )
                    }

                    resetBitrateToDefault(prefs, valueStr, null)
                    true
                }

            findPreference<Preference>(PreferenceConfiguration.FPS_PREF_STRING)!!
                .setOnPreferenceChangeListener { preference, newValue ->
                    val prefs = getPrefs()
                    val valueStr = newValue as String
                    val values = (preference as ListPreference).entryValues
                    if (nativeFramerateShown && values[values.size - 1].toString() == valueStr) {
                        Dialog.displayDialog(
                            activity,
                            resources.getString(R.string.title_native_fps_dialog),
                            resources.getString(R.string.text_native_res_dialog),
                            false
                        )
                    }

                    resetBitrateToDefault(prefs, null, valueStr)
                    true
                }

            findPreference<Preference>("checkbox_enable_perf_logging")!!
                .setOnPreferenceChangeListener { preference, newValue ->
                    val loggingEnabled = newValue as Boolean
                    if (!loggingEnabled) {
                        PerformanceDataTracker().clearLogs(preference.context)
                    }
                    true
                }

            findPreference<Preference>("import_keyboard_file")?.setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "application/json"
                startActivityForResult(intent, READ_REQUEST_CODE)
                false
            }

            findPreference<Preference>("import_special_button_file")?.setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "application/json"
                startActivityForResult(intent, READ_REQUEST_SPECIAL_CODE)
                false
            }

            findPreference<Preference>("share_performance_logs")?.setOnPreferenceClickListener { preference ->
                val context = preference.context
                val tracker = PerformanceDataTracker()
                val logs = tracker.getLog(context)

                if (logs.trim().isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_no_logs), Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener false
                }

                val prefixMessage = context.getString(R.string.email_prefix_message)
                val emailRecipient = context.getString(R.string.email_recipient).trim()
                val emailSubject = context.getString(R.string.email_subject)
                val chooserTitle = context.getString(R.string.email_chooser_title)
                val noEmailClientsMsg = context.getString(R.string.toast_no_email_clients)

                try {
                    val logFile = File(context.cacheDir, "artemistics_logs.txt")
                    FileOutputStream(logFile).use { fos ->
                        fos.write(logs.toByteArray(StandardCharsets.UTF_8))
                    }

                    val logFileUri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        logFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND)
                    shareIntent.type = "text/plain"
                    if (emailRecipient.isNotEmpty()) {
                        shareIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(emailRecipient))
                    }
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, emailSubject)
                    shareIntent.putExtra(Intent.EXTRA_TEXT, prefixMessage)
                    shareIntent.putExtra(Intent.EXTRA_STREAM, logFileUri)
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
                } catch (e: IOException) {
                    Log.d("PerformanceDataTracker", "Error creating log file")
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, noEmailClientsMsg, Toast.LENGTH_SHORT).show()
                }
                false
            }

            findPreference<Preference>("export_keyboard_file")?.setOnPreferenceClickListener {
                val file = File(requireActivity().externalCacheDir, "export_settings")
                if (!file.exists()) {
                    file.mkdir()
                }
                val file1 = getJsonContent(requireActivity(), file)
                if (file1 == null) {
                    Toast.makeText(requireActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener false
                }
                val intent = Intent(Intent.ACTION_SEND)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val authority = BuildConfig.APPLICATION_ID + ".fileprovider"
                val uri = FileProvider.getUriForFile(requireActivity(), authority, file1)
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.type = "application/json"
                startActivity(Intent.createChooser(intent, getString(R.string.pref_save_keyboard_profile)))
                false
            }

            findPreference<Preference>("pref_debug_info")?.setOnPreferenceClickListener {
                val intent = Intent(requireActivity(), DebugInfoActivity::class.java)
                requireActivity().startActivity(intent)
                false
            }

            // Reachable at any time rather than only after a crash, because the
            // failure worth reporting is often one where nothing crashed at all.
            findPreference<Preference>("nova_report_problem")?.setOnPreferenceClickListener {
                com.papi.nova.diagnostics.SupportReportSharing.share(
                    requireActivity(),
                    com.papi.nova.BuildConfig.VERSION_NAME,
                )
                false
            }

            findPreference<EditTextPreference>(PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING)?.let { bitrateEditPref ->
                bitrateEditPref.setOnPreferenceChangeListener { _, newValue ->
                    val value = newValue as String
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(activity, getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }
                    val bitrate = (value.toFloat() * 1000).toInt()
                    getPrefs().edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, bitrate).apply()
                    Toast.makeText(activity, getString(R.string.pref_set_success), Toast.LENGTH_SHORT).show()
                    true
                }
            }

            findPreference<EditTextPreference>(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING)?.let { resolutionEditPref ->
                resolutionEditPref.setOnPreferenceChangeListener { _, newValue ->
                    val value = newValue as String
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(activity, getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }

                    val resolutionSegments = value.split("x")
                    if (resolutionSegments.size != 2) {
                        Toast.makeText(activity, getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }

                    try {
                        val width = resolutionSegments[0].toInt()
                        val height = resolutionSegments[1].toInt()
                        if (width <= 0 || height <= 0) {
                            Toast.makeText(activity, getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceChangeListener false
                        }

                        editAndReload(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, value)
                        true
                    } catch (e: NumberFormatException) {
                        Toast.makeText(activity, getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }

            val maxDisplayRefreshRate = maxSupportedFps
            findPreference<EditTextPreference>(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING)?.let { customRefreshRatePref ->
                customRefreshRatePref.summary = getString(
                    R.string.summary_custom_refresh_rate_display_cap,
                    Math.round(maxDisplayRefreshRate)
                )

                customRefreshRatePref.setOnPreferenceChangeListener { _, newValue ->
                    val value = newValue as String
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(activity, getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }

                    try {
                        val refreshRate = value.toFloat()
                        if (refreshRate <= 0) {
                            Toast.makeText(activity, getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceChangeListener false
                        }
                        if (maxDisplayRefreshRate > 0 && refreshRate > maxDisplayRefreshRate + 0.5f) {
                            Toast.makeText(
                                activity,
                                getString(
                                    R.string.pref_refresh_rate_exceeds_display_cap,
                                    Math.round(maxDisplayRefreshRate)
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnPreferenceChangeListener false
                        }

                        val formattedValue = String.format("%.3f", refreshRate)
                            .replace(Regex("0+$"), "")
                            .replace(Regex("\\.$"), "")

                        editAndReload(
                            PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING,
                            formattedValue
                        )
                        true
                    } catch (e: NumberFormatException) {
                        Toast.makeText(activity, getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }
        }

        private fun removeEntryFromListAndSetValue(
            resolutionPrefString: String,
            entryToRemove: String,
            nextDefault: String
        ) {
            removeValue(
                resolutionPrefString,
                entryToRemove,
                Runnable {
                    val prefs = getPrefs()
                    setValue(resolutionPrefString, nextDefault)
                    resetBitrateToDefault(prefs, null, null)
                }
            )
        }

        private fun editAndReload(prefKey: String, newVal: String) {
            getPrefs().edit().putString(prefKey, newVal).apply()
            reloadSettings()
        }

        open fun reloadSettings() {
            Handler().postDelayed(
                {
                    val settingsActivity = activity as? StreamSettings
                    settingsActivity?.reloadSettings()
                },
                500
            )
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    val json = FileUriUtils.openUriForRead(requireActivity(), data.data)
                    if (TextUtils.isEmpty(json)) {
                        Toast.makeText(activity, getString(R.string.pref_empty_file), Toast.LENGTH_SHORT).show()
                        return
                    }
                    val name = getPrefs().getString(
                        KeyBoardControllerConfigurationLoader.OSC_PREFERENCE,
                        KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE
                    ) ?: KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE
                    val prefEditor = requireActivity()
                        .getSharedPreferences(name, Activity.MODE_PRIVATE)
                        .edit()
                    val obj = JSONObject(json)
                    val iterator = obj.keys()
                    prefEditor.clear()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val value = obj.getString(key)
                        prefEditor.putString(key, value)
                    }
                    prefEditor.apply()
                    Toast.makeText(activity, getString(R.string.pref_import_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(
                        activity,
                        getString(R.string.pref_error_occurred) + e.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            if (requestCode == READ_REQUEST_SPECIAL_CODE && resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    val json = FileUriUtils.openUriForRead(requireActivity(), data.data)
                    if (TextUtils.isEmpty(json)) {
                        Toast.makeText(activity, getString(R.string.pref_empty_file), Toast.LENGTH_SHORT).show()
                        return
                    }
                    val prefEditor = requireActivity()
                        .getSharedPreferences(NovaSpecialKeyPrefs.PREF_NAME, Activity.MODE_PRIVATE)
                        .edit()
                    prefEditor.putString(NovaSpecialKeyPrefs.KEY_NAME, json)
                    prefEditor.apply()
                    Toast.makeText(activity, getString(R.string.pref_import_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(
                        activity,
                        getString(R.string.pref_error_occurred) + e.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        /**
         * The one route for the legacy screen's dialogs: each opens as a page in the right-edge
         * panel, and focus returns to its preference row when the panel closes. Lists are Choice
         * pages that open on the current value, text is a Form page, the display target is the
         * role composer, and resetting the on-screen controls is a destructive Confirm, because a
         * preference row has no button to split.
         */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            val activity = requireActivity()
            val page = when {
                preference is ConfirmDeleteOscPreference -> resetControlsPage(preference)
                preference is ListPreference &&
                    preference.key == PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING -> displayRolePage(preference)
                preference is ListPreference -> listChoicePage(preference)
                preference is EditTextPreference -> textFormPage(preference)
                else -> null
            }
            if (page == null) {
                super.onDisplayPreferenceDialog(preference)
                return
            }
            NovaSurfaces.of(activity).open(
                root = page,
                edge = NovaEdge.End,
                returnFocus = activity.currentFocus?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None,
            ) { shown ->
                if (shown is SettingsPage.DisplayRole) NovaDisplayRolePage(shown)
            }
        }

        private fun listChoicePage(preference: ListPreference): NovaCommonPage {
            val context = requireContext()
            val values = preference.entryValues.orEmpty().map { it.toString() }
            val options = preference.entries.orEmpty().zip(values).map { (label, value) ->
                NovaSettingOption(label = label.toString(), value = value)
            }
            val pyroWave = if (needsPyroWaveCheck(preference.key, values)) {
                PyroWaveAvailability.inspect(context.applicationContext)
            } else {
                null
            }
            return novaSelectChoicePage(
                key = preference.key,
                title = preference.title?.toString().orEmpty(),
                options = novaSelectOptions(context, preference.key, options, pyroWave),
                current = preference.value,
                onChoose = { value -> if (preference.callChangeListener(value)) preference.value = value },
            )
        }

        private fun textFormPage(preference: EditTextPreference): NovaCommonPage = novaTextFormPage(
            context = requireContext(),
            key = preference.key,
            title = preference.title?.toString().orEmpty(),
            current = preference.text.orEmpty(),
            risky = true,
            onSave = { value -> if (preference.callChangeListener(value)) preference.text = value },
        )

        private fun displayRolePage(preference: ListPreference): SettingsPage = SettingsPage.DisplayRole(
            title = getString(R.string.title_display_role_composer),
            currentTarget = preference.value ?: com.papi.nova.utils.AndroidStreamDisplayTarget.AUTO,
            onApply = { target -> if (preference.callChangeListener(target)) preference.value = target },
        )

        private fun resetControlsPage(preference: ConfirmDeleteOscPreference): NovaCommonPage {
            val context = requireContext()
            return NovaCommonPage.Confirm(
                key = "reset-osc",
                title = (preference.dialogTitle ?: preference.title)?.toString().orEmpty(),
                message = AnnotatedString(preference.dialogMessage?.toString().orEmpty()),
                stayLabel = getString(R.string.nova_panel_keep),
                actionLabel = getString(R.string.nova_settings_reset_controls),
                destructive = true,
                onConfirm = { ConfirmDeleteOscPreference.resetControls(context) },
            )
        }

        private fun getJsonContent(context: Context, file: File): File? {
            val name = getPrefs().getString(
                KeyBoardControllerConfigurationLoader.OSC_PREFERENCE,
                KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE
            ) ?: KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE
            val pref = context.getSharedPreferences(name, Activity.MODE_PRIVATE)
            val map = pref.all
            val file1 = File(file, "$name.json")
            val jsonStr = Gson().toJson(map)
            if (!FileUriUtils.writerFileString(file1, jsonStr)) {
                return null
            }
            return file1
        }

        private fun getAllJsonData(file: File): File? {
            val pref = getPrefs()
            val map = pref.all
            val file1 = File(file, "allJSON.json")
            val jsonStr = Gson().toJson(map)
            if (!FileUriUtils.writerFileString(file1, jsonStr)) {
                return null
            }
            return file1
        }

        companion object {
            private const val READ_REQUEST_CODE = 1001
            private const val READ_REQUEST_SPECIAL_CODE = 1002
        }
    }

    companion object {
        @JvmField
        var displayCutoutP: DisplayCutout? = null
    }
}
