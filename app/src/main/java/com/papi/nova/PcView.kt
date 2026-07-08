package com.papi.nova

import android.app.AlertDialog
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.papi.nova.PcViewModel.ComputerObject
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.binding.crypto.AndroidCryptoProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.grid.PcGridAdapter
import com.papi.nova.grid.RecyclerItemClickListener
import com.papi.nova.grid.assets.DiskAssetLoader
import com.papi.nova.manager.PolarisStartupCoordinator
import com.papi.nova.manager.PolarisStartupStatus
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.http.PairingManager.PairState
import com.papi.nova.nvstream.wol.WakeOnLanSender
import com.papi.nova.preferences.AddComputerManually
import com.papi.nova.preferences.GlPreferences
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.StreamSettings
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.runtime.NovaRuntimeTasks
import com.papi.nova.ui.AdapterFragment
import com.papi.nova.ui.AdapterFragmentCallbacks
import com.papi.nova.ui.NovaLibraryActivity
import com.papi.nova.ui.NovaQrScanActivity
import com.papi.nova.ui.NovaSheetChrome
import com.papi.nova.ui.NovaSnackbar
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.NovaWelcomeActivity
import com.papi.nova.ui.SpaceParticleView
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.HelpLauncher
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import com.papi.nova.utils.UiHelper
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateEncodingException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.xmlpull.v1.XmlPullParserException

class PcView : AppCompatActivity(), AdapterFragmentCallbacks {
    private val THEME_PICKER_GRID_GAP_DP = 8
    private var noPcFoundLayout: View? = null
    private lateinit var pcGridAdapter: PcGridAdapter
    private lateinit var shortcutHelper: ShortcutHelper
    private lateinit var viewModel: PcViewModel
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false
    private var autoNavigated = false
    private var pendingPairingAddress: ComputerDetails.AddressTuple? = null
    private var pendingPairingPin: String? = null
    private var pendingPairingPassphrase: String? = null
    private var currentServerFilter = FILTER_ALL
    private var lastServerFilterFocusMs = 0L
    private val runtimeTasks = NovaRuntimeTasks(this, "Nova dashboard")
    private val libraryProbeInFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val polarisStartupInFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var appliedTheme: String? = null
    private var spaceParticleView: SpaceParticleView? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            val focus = currentFocus
            if (isServerFilterFocus(focus) ||
                isHeaderFocusFallback(focus) && wasServerFilterFocusedRecently()
            ) {
                scheduleServerRowFocus(focus)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isServerFilterFocus(focus: View?): Boolean {
        if (focus == null) {
            return false
        }
        return when (focus.id) {
            R.id.filterAllServers,
            R.id.filterOnlineServers,
            R.id.filterStreamingServers,
            R.id.filterNeedsPairingServers,
            -> true
            else -> false
        }
    }

    private fun bindServerFilterFocusFallback() {
        val root = window.decorView ?: return
        root.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (isServerFilterFocus(newFocus)) {
                lastServerFilterFocusMs = SystemClock.uptimeMillis()
            }
            if (newFocus != null && newFocus.id == R.id.serverListFocusBridge) {
                scheduleServerRowFocus(newFocus)
                return@addOnGlobalFocusChangeListener
            }
            if (isServerFilterFocus(oldFocus) &&
                isHeaderFocusFallback(newFocus) &&
                wasServerFilterFocusedRecently()
            ) {
                scheduleServerRowFocus(newFocus)
                return@addOnGlobalFocusChangeListener
            }
            if (!isServerListFocus(newFocus)) {
                setHeaderQuickActionsFocusable(true)
            }
        }
    }

    private fun wasServerFilterFocusedRecently(): Boolean =
        lastServerFilterFocusMs != 0L &&
            SystemClock.uptimeMillis() - lastServerFilterFocusMs < 500

    private fun isHeaderFocusFallback(focus: View?): Boolean {
        if (focus == null) {
            return false
        }
        return focus.id == R.id.profilesButton || focus.id == R.id.actionSettings
    }

    private fun scheduleServerRowFocus(anchor: View?) {
        val target = anchor ?: window.decorView ?: return
        setHeaderQuickActionsFocusable(false)
        target.post { moveFocusToFirstServerRow() }
        target.postDelayed({ moveFocusToFirstServerRow() }, 150)
        target.postDelayed({ moveFocusToFirstServerRow() }, 500)
        target.postDelayed({ moveFocusToFirstServerRow() }, 1000)
        target.postDelayed({ setHeaderQuickActionsFocusable(true) }, 1200)
    }

    private fun isServerListFocus(focus: View?): Boolean {
        var current = focus
        while (current != null) {
            if (isServerFilterFocus(current)) {
                return true
            }
            when (current.id) {
                R.id.fragmentView,
                R.id.pcFragmentContainer,
                R.id.serverListFocusBridge,
                R.id.serverFilterTabs,
                -> return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun clearPendingPairing() {
        pendingPairingAddress = null
        pendingPairingPin = null
        pendingPairingPassphrase = null
    }

    private fun matchesPendingPairingAddress(address: ComputerDetails.AddressTuple?): Boolean {
        val pendingAddress = pendingPairingAddress ?: return false
        return address != null &&
            address.port == pendingAddress.port &&
            address.address.equals(pendingAddress.address, ignoreCase = true)
    }

    private fun maybeRunPendingQrPairing(computers: List<ComputerObject>) {
        pendingPairingAddress ?: return
        val otp = pendingPairingPin ?: return
        val passphrase = pendingPairingPassphrase ?: return

        for (computer in computers) {
            val details = computer.details
            if (details.state != ComputerDetails.State.ONLINE) {
                continue
            }

            val matchesPendingHost =
                matchesPendingPairingAddress(details.manualAddress) ||
                    matchesPendingPairingAddress(details.activeAddress) ||
                    matchesPendingPairingAddress(details.localAddress) ||
                    matchesPendingPairingAddress(details.remoteAddress) ||
                    matchesPendingPairingAddress(details.ipv6Address)

            if (!matchesPendingHost) {
                continue
            }

            if (details.pairState == PairState.PAIRED && hasPinnedServerCert(details)) {
                clearPendingPairing()
                return
            }

            clearPendingPairing()
            doPair(details, otp, passphrase)
            return
        }
    }

    private val qrScanLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { handleQrScanResult(it) }
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val localBinder = binder as ComputerManagerService.ComputerManagerBinder
                Thread {
                    localBinder.waitForReady()

                    runOnUiThread {
                        managerBinder = localBinder
                        startComputerUpdates()
                    }

                    AndroidCryptoProvider(this@PcView).clientCertificate
                }.start()
            }

            override fun onServiceDisconnected(className: ComponentName) {
                managerBinder = null
            }
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (completeOnCreateCalled) {
            initializeViews(PreferenceConfiguration.readPreferences(this))
        }

        refreshProfileButton()
    }

    private fun initializeViews(prefs: PreferenceConfiguration) {
        setContentView(R.layout.activity_pc_view)

        UiHelper.notifyNewRootView(this)

        val header = findViewById<View>(R.id.pcViewHeader)
        if (header != null) {
            header.setOnApplyWindowInsetsListener { v, insets ->
                val topInset =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        insets.systemWindowInsetTop
                    } else {
                        0
                    }
                v.setPadding(
                    v.paddingLeft,
                    topInset + UiHelper.dpToPx(this, 16f).toInt(),
                    v.paddingRight,
                    v.paddingBottom,
                )
                insets
            }
            header.requestApplyInsets()
        }

        spaceParticleView = findViewById(R.id.space_particles)
        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(NovaThemeManager.getAccentColor(this))
            swipeRefresh.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(this, R.color.nova_bg_elevated),
            )
            swipeRefresh.setOnRefreshListener {
                resetLibraryReadiness()
                stopComputerUpdates(false)
                startComputerUpdates()
                swipeRefresh.postDelayed({ swipeRefresh.isRefreshing = false }, 2000)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        pcGridAdapter.updateLayoutWithPreferences(this, prefs)

        val modeServers = findViewById<View>(R.id.modeServers)
        val modeLibrary = findViewById<View>(R.id.modeLibrary)
        val addServerAction = findViewById<View>(R.id.actionAddServer)
        val scanPairAction = findViewById<View>(R.id.actionScanPair)
        val polarisSyncAction = findViewById<View>(R.id.actionPolarisSync)
        val themeAction = findViewById<View>(R.id.actionTheme)
        val settingsAction = findViewById<View>(R.id.actionSettings)
        val helpAction = findViewById<View>(R.id.actionHelp)
        val topActionFocusLabel = findViewById<TextView>(R.id.topActionFocusLabel)
        val emptyRefresh = findViewById<TextView>(R.id.emptyRefresh)
        val emptyAddServer = findViewById<TextView>(R.id.emptyAddServer)
        val emptyScanPair = findViewById<TextView>(R.id.emptyScanPair)
        val emptyHelp = findViewById<TextView>(R.id.emptyHelp)
        val filterAllServers = findViewById<TextView>(R.id.filterAllServers)
        val filterOnlineServers = findViewById<TextView>(R.id.filterOnlineServers)
        val filterStreamingServers = findViewById<TextView>(R.id.filterStreamingServers)
        val filterNeedsPairingServers = findViewById<TextView>(R.id.filterNeedsPairingServers)
        val serverListFocusBridge = findViewById<View>(R.id.serverListFocusBridge)
        val profilesButton = findViewById<MaterialButton>(R.id.profilesButton)

        modeServers?.setOnClickListener {
            setServerFilter(FILTER_ALL)
            updateModeTabs()
        }
        modeLibrary?.setOnClickListener { launchQuickLibrary() }
        addServerAction?.setOnClickListener {
            startActivity(Intent(this@PcView, AddComputerManually::class.java))
        }
        scanPairAction?.setOnClickListener { launchQrScanner() }
        polarisSyncAction?.setOnClickListener { launchPolarisStartupForPreferredHost() }
        themeAction?.setOnClickListener { v ->
            showThemePicker(v)
        }
        settingsAction?.setOnClickListener {
            startActivity(Intent(this@PcView, StreamSettings::class.java))
            NovaThemeManager.applyFadeTransition(this@PcView)
        }
        helpAction?.setOnClickListener { HelpLauncher.launchSetupGuide(this@PcView) }
        emptyRefresh?.setOnClickListener {
            resetLibraryReadiness()
            stopComputerUpdates(false)
            startComputerUpdates()
        }
        emptyAddServer?.setOnClickListener {
            startActivity(Intent(this@PcView, AddComputerManually::class.java))
        }
        emptyScanPair?.setOnClickListener { launchQrScanner() }
        emptyHelp?.setOnClickListener { HelpLauncher.launchSetupGuide(this@PcView) }
        profilesButton?.setOnClickListener {
            startActivity(Intent(this@PcView, ProfilesActivity::class.java))
        }
        bindTopActionFocusLabel(
            topActionFocusLabel,
            profilesButton to R.string.pcview_quick_profiles,
            polarisSyncAction to R.string.pcview_quick_polaris_sync,
            settingsAction to R.string.pcview_quick_settings,
        )

        filterAllServers?.setOnClickListener { setServerFilter(FILTER_ALL) }
        filterOnlineServers?.setOnClickListener { setServerFilter(FILTER_ONLINE) }
        filterStreamingServers?.setOnClickListener { setServerFilter(FILTER_STREAMING) }
        filterNeedsPairingServers?.setOnClickListener { setServerFilter(FILTER_NEEDS_PAIRING) }
        bindServerFilterFocusDown(filterAllServers, filterOnlineServers, filterStreamingServers, filterNeedsPairingServers)
        bindServerFilterFocusFallback()
        serverListFocusBridge?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                moveFocusToFirstServerRow()
            }
        }

        if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
            helpAction?.visibility = View.GONE
            emptyHelp?.visibility = View.GONE
        }

        applyThemeToServerBrowser()
        updateModeTabs()
        updateServerFilterTabs()
        syncComputerList()

        supportFragmentManager.beginTransaction()
            .replace(R.id.pcFragmentContainer, AdapterFragment())
            .commitAllowingStateLoss()

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout)
        updateEmptyState()
    }

    private fun applyThemeToServerBrowser() {
        val accent = NovaThemeManager.getAccentColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textSecondary = NovaThemeManager.getTextSecondaryColor(this)
        val textMuted = NovaThemeManager.getTextMutedColor(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val divider = NovaThemeManager.getDividerColor(this)

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(accent)
            swipeRefresh.setProgressBackgroundColorSchemeColor(surface)
        }

        findViewById<TextView>(R.id.pcViewTitle)?.setTextColor(textPrimary)
        findViewById<TextView>(R.id.pcViewSectionLabel)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.pcViewToolsLabel)?.setTextColor(textPrimary)
        findViewById<TextView>(R.id.pcViewToolsHint)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.pcViewHostsLabel)?.setTextColor(textPrimary)
        findViewById<TextView>(R.id.pcViewHostsSummary)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.topActionFocusLabel)?.setTextColor(textSecondary)
        findViewById<TextView>(R.id.pcViewEmptyTitle)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.pcViewEmptyHint)?.setTextColor(textMuted)

        styleDestinationCard(findViewById(R.id.modeServers), true, accent, surface, divider, textPrimary, textSecondary, textMuted)
        styleDestinationCard(findViewById(R.id.modeLibrary), false, accent, surface, divider, textPrimary, textSecondary, textMuted)

        styleActionButton(findViewById(R.id.actionAddServer), ColorUtils.blendARGB(surface, accent, 0.26f), textPrimary)
        styleActionButton(findViewById(R.id.actionScanPair), surface, textPrimary)
        styleActionButton(findViewById(R.id.actionSettings), ColorUtils.blendARGB(surface, accent, 0.18f), textPrimary)

        tintChipRow(
            intArrayOf(
                R.id.actionTheme,
                R.id.actionHelp,
                R.id.emptyRefresh,
                R.id.emptyAddServer,
                R.id.emptyScanPair,
                R.id.emptyHelp,
            ),
            textPrimary,
        )

        styleActionButton(findViewById(R.id.profilesButton), surface, textPrimary)
    }

    private fun styleActionButton(button: MaterialButton?, backgroundColor: Int, foregroundColor: Int) {
        if (button == null) {
            return
        }
        button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        button.setTextColor(foregroundColor)
        button.iconTint = ColorStateList.valueOf(foregroundColor)
        button.strokeColor = ContextCompat.getColorStateList(this, R.color.nova_focus_stroke_selector)
        button.strokeWidth = UiHelper.dpToPx(this, 2f).toInt()
    }

    private fun styleDestinationCard(
        card: MaterialCardView?,
        active: Boolean,
        accent: Int,
        surface: Int,
        divider: Int,
        textPrimary: Int,
        textSecondary: Int,
        textMuted: Int,
    ) {
        if (card == null) {
            return
        }

        card.setCardBackgroundColor(if (active) ColorUtils.blendARGB(surface, accent, 0.12f) else surface)
        updateDestinationCardStroke(card, active || card.hasFocus(), accent, divider)
        card.setOnFocusChangeListener { _, hasFocus ->
            updateDestinationCardStroke(card, active || hasFocus, accent, divider)
        }

        val layout = card.getChildAt(0) as? LinearLayout ?: return
        if (layout.childCount < 4) {
            return
        }

        val badge = layout.getChildAt(0) as TextView
        val title = layout.getChildAt(1) as TextView
        val summary = layout.getChildAt(2) as TextView
        val meta = layout.getChildAt(3) as TextView

        badge.setTextColor(if (active) accent else textMuted)
        title.setTextColor(textPrimary)
        summary.setTextColor(textSecondary)
        meta.setTextColor(if (active) textPrimary else textMuted)
    }

    private fun updateDestinationCardStroke(card: MaterialCardView, highlighted: Boolean, accent: Int, divider: Int) {
        card.strokeColor = if (highlighted) accent else divider
        card.strokeWidth = UiHelper.dpToPx(this, if (highlighted) 2f else 1f).toInt()
    }

    private fun tintChipRow(ids: IntArray, color: Int) {
        for (id in ids) {
            findViewById<TextView>(id)?.setTextColor(color)
        }
    }

    private fun bindTopActionFocusLabel(label: TextView?, vararg actions: Pair<View?, Int>) {
        label ?: return
        for ((action, labelRes) in actions) {
            action?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    label.text = getString(labelRes)
                    label.visibility = View.VISIBLE
                } else if (actions.none { it.first?.hasFocus() == true }) {
                    label.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun showThemePicker(anchor: View?) {
        val themes = buildThemePickerThemes()
        val currentTheme = NovaThemeManager.getTheme(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textSecondary = NovaThemeManager.getTextSecondaryColor(this)
        val textMuted = NovaThemeManager.getTextMutedColor(this)

        val dialog = BottomSheetDialog(this, R.style.NovaBottomSheet)
        var focusTarget: View? = null
        lateinit var themePickerFocusLabel: TextView

        val content = NovaSheetChrome.createSheetContainer(this)
        content.clipChildren = false
        content.clipToPadding = false

        content.addView(
            TextView(this).apply {
                text = getString(R.string.pcview_theme_picker_title)
                setTextColor(textPrimary)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            },
        )
        content.addView(
            TextView(this).apply {
                text = getString(R.string.pcview_theme_picker_hint)
                setTextColor(textMuted)
                textSize = 11f
                setPadding(0, dp(4), 0, dp(8))
            },
        )

        themePickerFocusLabel = TextView(this).apply {
            text = getString(
                R.string.pcview_theme_picker_focus_format,
                NovaThemeManager.getThemeLabel(this@PcView, currentTheme),
                getThemePickerSubtitle(currentTheme),
            )
            setTextColor(NovaThemeManager.getAccentColor(this@PcView))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        content.addView(themePickerFocusLabel)

        val themeGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gridGap = dp(THEME_PICKER_GRID_GAP_DP)
            setPadding(gridGap, gridGap, gridGap, gridGap)
            clipChildren = false
            clipToPadding = false
        }
        content.addView(themeGrid)
        themes.chunked(2).forEach { themePair ->
            val gridRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            themePair.forEachIndexed { index, theme ->
                val row = createThemePickerRow(theme, currentTheme, themePickerFocusLabel, surface, textPrimary, textSecondary, dialog)
                row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) {
                        marginEnd = dp(THEME_PICKER_GRID_GAP_DP)
                    }
                    bottomMargin = dp(THEME_PICKER_GRID_GAP_DP)
                }
                if (focusTarget == null || theme == currentTheme) {
                    focusTarget = row
                }
                gridRow.addView(row)
            }
            if (themePair.size == 1) {
                gridRow.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    },
                )
            }
            themeGrid.addView(gridRow)
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            NovaSheetChrome.applyBottomSheetChrome(dialog, content)
            content.post {
                focusTarget?.requestFocus()
            }
        }
        dialog.show()
        anchor?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }

    private fun buildThemePickerThemes(): List<String> {
        val themes = mutableListOf(
            NovaThemeManager.THEME_POLARIS,
            NovaThemeManager.THEME_PORTABLE_CHROME,
            NovaThemeManager.THEME_OLED,
            NovaThemeManager.THEME_MIAMI,
            NovaThemeManager.THEME_HIGH_CONTRAST,
        )
        if (NovaThemeManager.isMaterialYouAvailable()) {
            themes.add(NovaThemeManager.THEME_MATERIAL_YOU)
        }
        return themes
    }

    private fun createThemePickerRow(
        theme: String,
        currentTheme: String,
        themePickerFocusLabel: TextView,
        surface: Int,
        textPrimary: Int,
        textSecondary: Int,
        dialog: BottomSheetDialog,
    ): MaterialCardView {
        val label = NovaThemeManager.getThemeLabel(this, theme)
        val subtitle = getThemePickerSubtitle(theme)
        val rowAccent = getThemePickerPreviewAccent(theme)
        val divider = NovaThemeManager.getDividerColor(this)
        val selected = theme == currentTheme

        val card = MaterialCardView(this).apply {
            useCompatPadding = true
            clipToOutline = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
            radius = dp(18).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                applyThemeSelection(theme)
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A)
                ) {
                    performClick()
                    true
                } else {
                    false
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        row.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(rowAccent)
                    setStroke(dp(2), ColorUtils.blendARGB(rowAccent, textPrimary, 0.32f))
                }
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(14)
                }
            },
        )
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(
                    TextView(this@PcView).apply {
                        text = label
                        setTextColor(textPrimary)
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                    },
                )
                addView(
                    TextView(this@PcView).apply {
                        text = subtitle
                        setTextColor(textSecondary)
                        textSize = 10f
                        setPadding(0, dp(3), dp(8), 0)
                    },
                )
            },
        )
        if (selected) {
            row.addView(
                TextView(this).apply {
                    text = getString(R.string.pcview_theme_picker_current_badge)
                    setTextColor(textPrimary)
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        setColor(ColorUtils.blendARGB(surface, rowAccent, 0.30f))
                        setStroke(dp(1), rowAccent)
                        cornerRadius = dp(999).toFloat()
                    }
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                },
            )
        }
        card.addView(row)
        updateThemePickerRowState(card, selected, false, rowAccent, surface, divider, themePickerFocusLabel, label, subtitle)
        card.setOnFocusChangeListener { _, hasFocus ->
            updateThemePickerRowState(card, selected, hasFocus, rowAccent, surface, divider, themePickerFocusLabel, label, subtitle)
        }
        return card
    }

    private fun updateThemePickerRowState(
        card: MaterialCardView,
        selected: Boolean,
        focused: Boolean,
        rowAccent: Int,
        surface: Int,
        divider: Int,
        themePickerFocusLabel: TextView,
        label: String,
        subtitle: String,
    ) {
        card.setCardBackgroundColor(
            when {
                focused -> ColorUtils.blendARGB(surface, rowAccent, 0.18f)
                selected -> ColorUtils.blendARGB(surface, rowAccent, 0.14f)
                else -> surface
            },
        )
        card.strokeColor = if (focused || selected) rowAccent else divider
        card.strokeWidth = dp(
            when {
                focused -> 4
                selected -> 3
                else -> 1
            },
        )
        if (focused) {
            themePickerFocusLabel.text = getString(R.string.pcview_theme_picker_focus_format, label, subtitle)
            themePickerFocusLabel.setTextColor(rowAccent)
        }
    }

    private fun getThemePickerSubtitle(theme: String): String {
        return when (theme) {
            NovaThemeManager.THEME_PORTABLE_CHROME -> getString(R.string.pcview_theme_portable_chrome_subtitle)
            NovaThemeManager.THEME_OLED -> getString(R.string.pcview_theme_oled_subtitle)
            NovaThemeManager.THEME_MIAMI -> getString(R.string.pcview_theme_miami_subtitle)
            NovaThemeManager.THEME_HIGH_CONTRAST -> getString(R.string.pcview_theme_high_contrast_subtitle)
            NovaThemeManager.THEME_MATERIAL_YOU -> getString(R.string.pcview_theme_material_you_subtitle)
            else -> getString(R.string.pcview_theme_polaris_subtitle)
        }
    }

    private fun getThemePickerPreviewAccent(theme: String): Int {
        return ContextCompat.getColor(
            this,
            when (theme) {
                NovaThemeManager.THEME_PORTABLE_CHROME -> R.color.nova_portable_accent
                NovaThemeManager.THEME_OLED -> R.color.nova_oled_accent
                NovaThemeManager.THEME_MIAMI -> R.color.nova_miami_accent
                NovaThemeManager.THEME_HIGH_CONTRAST -> R.color.nova_hc_accent
                else -> R.color.nova_polaris_accent
            },
        )
    }

    private fun dp(value: Int): Int = UiHelper.dpToPx(this, value.toFloat()).toInt()

    private fun applyThemeSelection(theme: String) {
        if (theme == NovaThemeManager.getTheme(this)) {
            return
        }
        NovaThemeManager.setTheme(this, theme)
        Toast.makeText(
            this,
            getString(R.string.nova_theme_switched_to, NovaThemeManager.getThemeLabel(this, theme)),
            Toast.LENGTH_SHORT,
        ).show()
        recreate()
        NovaThemeManager.applyFadeTransition(this)
    }

    private fun updateModeTabs() {
        val accent = NovaThemeManager.getAccentColor(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val divider = NovaThemeManager.getDividerColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textSecondary = NovaThemeManager.getTextSecondaryColor(this)
        val textMuted = NovaThemeManager.getTextMutedColor(this)
        styleDestinationCard(findViewById(R.id.modeServers), true, accent, surface, divider, textPrimary, textSecondary, textMuted)
        styleDestinationCard(findViewById(R.id.modeLibrary), false, accent, surface, divider, textPrimary, textSecondary, textMuted)
    }

    private fun updateServerFilterTabs() {
        val selectedId =
            when (currentServerFilter) {
                FILTER_ONLINE -> R.id.filterOnlineServers
                FILTER_STREAMING -> R.id.filterStreamingServers
                FILTER_NEEDS_PAIRING -> R.id.filterNeedsPairingServers
                else -> R.id.filterAllServers
            }

        findViewById<ChipGroup>(R.id.serverFilterTabs)?.check(selectedId)
    }

    private fun setServerFilter(filter: Int) {
        if (currentServerFilter == filter) {
            return
        }

        currentServerFilter = filter
        updateServerFilterTabs()
        syncComputerList()
    }

    private fun bindServerFilterFocusDown(vararg filters: View?) {
        for (filter in filters) {
            if (filter == null) {
                continue
            }
            filter.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    lastServerFilterFocusMs = SystemClock.uptimeMillis()
                }
            }
            filter.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    scheduleServerRowFocus(view)
                    return@setOnKeyListener true
                }
                false
            }
        }
    }

    private fun moveFocusToFirstServerRow(): Boolean {
        val rv = findViewById<RecyclerView>(R.id.fragmentView) ?: return false

        setHeaderQuickActionsFocusable(false)
        rv.postDelayed({ setHeaderQuickActionsFocusable(true) }, 600)
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (rv.childCount == 0) {
            rv.requestFocus()
            return false
        }

        val firstRow = rv.getChildAt(0)
        UiHelper.applyTvFocusStyle(firstRow)
        setServerFilterNextFocusDown(firstRow)
        firstRow.requestFocus()
        return firstRow.hasFocus()
    }

    private fun setHeaderQuickActionsFocusable(focusable: Boolean) {
        setFocusable(R.id.profilesButton, focusable)
        setFocusable(R.id.actionSettings, focusable)
    }

    private fun setFocusable(viewId: Int, focusable: Boolean) {
        val view = findViewById<View>(viewId) ?: return
        view.isFocusable = focusable
        view.isFocusableInTouchMode = false
    }

    private fun setServerFilterNextFocusDown(firstRow: View?) {
        if (firstRow == null) {
            return
        }

        var targetId = firstRow.id
        if (targetId == View.NO_ID) {
            targetId = View.generateViewId()
            firstRow.id = targetId
        }

        setNextFocusDown(R.id.filterAllServers, targetId)
        setNextFocusDown(R.id.filterOnlineServers, targetId)
        setNextFocusDown(R.id.filterStreamingServers, targetId)
        setNextFocusDown(R.id.filterNeedsPairingServers, targetId)
    }

    private fun setNextFocusDown(viewId: Int, targetId: Int) {
        val view = findViewById<View>(viewId)
        if (view != null) {
            view.setNextFocusDownId(targetId)
        }
    }

    private fun matchesCurrentFilter(computer: ComputerObject): Boolean =
        when (currentServerFilter) {
            FILTER_ONLINE -> computer.details.state != ComputerDetails.State.OFFLINE
            FILTER_STREAMING -> computer.details.runningGameId != 0
            FILTER_NEEDS_PAIRING -> needsPairing(computer.details)
            else -> true
        }

    private fun canUseLibrary(details: ComputerDetails): Boolean =
        details.state == ComputerDetails.State.ONLINE &&
            !needsPairing(details) &&
            details.activeAddress != null &&
            details.libraryState == ComputerDetails.LibraryState.AVAILABLE

    private fun canProbeLibrary(details: ComputerDetails): Boolean =
        details.state == ComputerDetails.State.ONLINE &&
            !needsPairing(details) &&
            details.activeAddress != null &&
            details.libraryState == ComputerDetails.LibraryState.UNKNOWN

    private fun resetLibraryReadiness() {
        if (!::viewModel.isInitialized) {
            return
        }
        val computers = viewModel.computersLiveData.value ?: return
        libraryProbeInFlight.clear()
        for (computer in computers) {
            computer.details.libraryState = ComputerDetails.LibraryState.UNKNOWN
        }
    }

    private fun encodeServerCert(details: ComputerDetails): ByteArray? =
        try {
            details.serverCert?.encoded
        } catch (e: CertificateEncodingException) {
            LimeLog.warning("Nova: Failed to encode server cert for Polaris probe: " + e.message)
            null
        }

    private fun hasPinnedServerCert(details: ComputerDetails?): Boolean =
        details?.serverCert != null

    private fun needsPairing(details: ComputerDetails?): Boolean =
        details == null ||
            details.pairState != PairState.PAIRED ||
            !hasPinnedServerCert(details)

    private fun maybeProbeLibraryReadiness(computer: ComputerObject) {
        val details = computer.details
        if (!canProbeLibrary(details) || libraryProbeInFlight.contains(details.uuid)) {
            return
        }

        val address = details.activeAddress ?: return
        libraryProbeInFlight.add(details.uuid)
        val uuid = details.uuid
        val host = address.address
        val httpsPort = if (details.httpsPort > 0) details.httpsPort else 47984
        val serverCert = encodeServerCert(details)

        runtimeTasks.launchIo("NovaLibraryProbe") {
            var state = ComputerDetails.LibraryState.UNAVAILABLE
            try {
                val client = PolarisApiClient(this@PcView, host, httpsPort, serverCert)
                val capabilities = client.getCapabilities()
                if (capabilities != null) {
                    state =
                        if (capabilities.features.gameLibrary) {
                            ComputerDetails.LibraryState.AVAILABLE
                        } else {
                            ComputerDetails.LibraryState.UNAVAILABLE
                        }
                } else if (client.getSessionStatus() != null) {
                    state = ComputerDetails.LibraryState.UNAVAILABLE
                    LimeLog.info("Nova: Polaris session API detected without game-library capability on $host")
                }
            } catch (e: Exception) {
                LimeLog.warning("Nova: Library capability probe failed for $host: " + e.message)
            }

            val finalState = state
            runtimeTasks.runOnMainIfActive {
                libraryProbeInFlight.remove(uuid)
                val computers = if (::viewModel.isInitialized) viewModel.computersLiveData.value else null
                if (computers != null) {
                    for (candidate in computers) {
                        if (uuid == candidate.details.uuid) {
                            candidate.details.libraryState = finalState
                            break
                        }
                    }
                }
                syncComputerList()
                checkAutoNavigation(computers)
            }
        }
    }

    private fun findComputerObject(uuid: String?): ComputerObject? {
        if (uuid == null || !::viewModel.isInitialized) {
            return null
        }
        val computers = viewModel.computersLiveData.value ?: return null
        for (computer in computers) {
            if (uuid == computer.details.uuid) {
                return computer
            }
        }
        return null
    }

    private fun openBestPlaySurface(computer: ComputerDetails) {
        if (computer.runningGameId != 0) {
            resumeOrWatchRunningGame(computer)
            return
        }

        if (computer.libraryState == ComputerDetails.LibraryState.AVAILABLE) {
            doNovaLibrary(computer)
            return
        }

        if (computer.libraryState == ComputerDetails.LibraryState.UNKNOWN) {
            val computerObject = findComputerObject(computer.uuid)
            if (computerObject != null) {
                maybeProbeLibraryReadiness(computerObject)
            }
            Toast.makeText(this, R.string.pcview_library_checking, Toast.LENGTH_SHORT).show()
            return
        }

        doAppList(computer, false, false)
    }

    private fun syncComputerList() {
        if (!::pcGridAdapter.isInitialized || !::viewModel.isInitialized) {
            return
        }

        val allComputers = viewModel.computersLiveData.value
        if (allComputers == null) {
            pcGridAdapter.setItems(ArrayList())
            updateHomeSummaries(0, 0, 0, 0)
            updateEmptyState()
            return
        }

        val visibleComputers = ArrayList<ComputerObject>()
        var onlineCount = 0
        var libraryReadyCount = 0

        for (computer in allComputers) {
            if (canProbeLibrary(computer.details)) {
                maybeProbeLibraryReadiness(computer)
            }
            if (computer.details.state == ComputerDetails.State.ONLINE) {
                onlineCount++
            }
            if (canUseLibrary(computer.details)) {
                libraryReadyCount++
            }
            if (matchesCurrentFilter(computer)) {
                visibleComputers.add(computer)
            }
        }

        pcGridAdapter.setItems(visibleComputers)
        updateHomeSummaries(visibleComputers.size, allComputers.size, onlineCount, libraryReadyCount)
        updateEmptyState()
    }

    private fun updateHomeSummaries(visibleCount: Int, totalCount: Int, onlineCount: Int, libraryReadyCount: Int) {
        findViewById<TextView>(R.id.pcViewHostsSummary)
            ?.text = getString(R.string.pcview_hosts_summary_format, visibleCount, totalCount)

        val serversMeta = findViewById<TextView>(R.id.pcViewServersMeta)
        if (serversMeta != null) {
            if (totalCount <= 0) {
                serversMeta.setText(R.string.pcview_destination_servers_meta_empty)
            } else {
                serversMeta.text = getString(R.string.pcview_destination_servers_meta_format, onlineCount, totalCount)
            }
        }

        val libraryMeta = findViewById<TextView>(R.id.pcViewLibraryMeta)
        if (libraryMeta != null) {
            if (libraryReadyCount <= 0) {
                libraryMeta.setText(R.string.pcview_destination_library_meta_empty)
            } else if (libraryReadyCount == 1) {
                libraryMeta.setText(R.string.pcview_destination_library_meta_one)
            } else {
                libraryMeta.text = getString(R.string.pcview_destination_library_meta_many, libraryReadyCount)
            }
        }
    }

    private fun updateEmptyState() {
        if (noPcFoundLayout == null || !::viewModel.isInitialized || !::pcGridAdapter.isInitialized) {
            return
        }

        val emptyTitle = findViewById<TextView>(R.id.pcViewEmptyTitle)
        val emptyHint = findViewById<TextView>(R.id.pcViewEmptyHint)
        val computers = viewModel.computersLiveData.value

        if (computers == null || computers.isEmpty()) {
            noPcFoundLayout?.visibility = View.VISIBLE
            emptyTitle?.setText(
                if (runningPolling) {
                    R.string.pcview_empty_title_searching
                } else {
                    R.string.pcview_empty_title_no_servers
                },
            )
            emptyHint?.setText(
                if (runningPolling) {
                    R.string.pcview_empty_hint_searching
                } else {
                    R.string.pcview_empty_hint_no_servers
                },
            )
            return
        }

        if (pcGridAdapter.itemCount > 0) {
            noPcFoundLayout?.visibility = View.INVISIBLE
            return
        }

        noPcFoundLayout?.visibility = View.VISIBLE
        if (emptyTitle == null || emptyHint == null) {
            return
        }

        when (currentServerFilter) {
            FILTER_ONLINE -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_online)
                emptyHint.setText(R.string.pcview_empty_hint_no_online)
            }
            FILTER_STREAMING -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_streaming)
                emptyHint.setText(R.string.pcview_empty_hint_no_streaming)
            }
            FILTER_NEEDS_PAIRING -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_pairing)
                emptyHint.setText(R.string.pcview_empty_hint_no_pairing)
            }
            else -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_servers)
                emptyHint.setText(R.string.pcview_empty_hint_no_servers)
            }
        }
    }

    private fun launchQuickLibrary() {
        val selected = selectPreferredLibraryComputer(
            if (::viewModel.isInitialized) viewModel.computersLiveData.value else null,
        )
        if (selected == null) {
            Toast.makeText(this, R.string.pcview_library_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        doNovaLibrary(selected.details)
    }

    private fun launchPolarisStartupForPreferredHost() {
        val selected = selectPreferredPolarisStartupComputer(
            if (::viewModel.isInitialized) viewModel.computersLiveData.value else null,
        )
        if (selected == null) {
            Toast.makeText(this, R.string.pcview_polaris_start_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        startPolarisFromNova(selected.details)
    }

    private fun selectPreferredPolarisStartupComputer(computers: List<ComputerObject>?): ComputerObject? {
        if (computers.isNullOrEmpty()) {
            return null
        }

        val rememberedUuid =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREF_LAST_LIBRARY_PC_UUID, null)
        var remembered: ComputerObject? = null
        var firstReady: ComputerObject? = null
        var firstOnline: ComputerObject? = null
        var firstWakeable: ComputerObject? = null
        var firstPaired: ComputerObject? = null

        for (candidate in computers) {
            val details = candidate.details
            if (needsPairing(details)) {
                continue
            }
            if (firstPaired == null) {
                firstPaired = candidate
            }
            if (details.libraryState == ComputerDetails.LibraryState.AVAILABLE && firstReady == null) {
                firstReady = candidate
            }
            if (details.state == ComputerDetails.State.ONLINE && firstOnline == null) {
                firstOnline = candidate
            }
            if (details.macAddress != null && firstWakeable == null) {
                firstWakeable = candidate
            }
            if (rememberedUuid != null && rememberedUuid == details.uuid) {
                remembered = candidate
            }
        }

        return remembered ?: firstReady ?: firstOnline ?: firstWakeable ?: firstPaired
    }

    private fun selectPreferredLibraryComputer(computers: List<ComputerObject>?): ComputerObject? {
        if (computers.isNullOrEmpty()) {
            return null
        }

        val rememberedUuid =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREF_LAST_LIBRARY_PC_UUID, null)
        var firstReady: ComputerObject? = null
        for (candidate in computers) {
            if (!canUseLibrary(candidate.details)) {
                continue
            }
            if (firstReady == null) {
                firstReady = candidate
            }
            if (rememberedUuid != null && rememberedUuid == candidate.details.uuid) {
                return candidate
            }
        }
        return firstReady
    }

    private fun getGlSurfaceView(glPrefs: GlPreferences): GLSurfaceView {
        val surfaceView = GLSurfaceView(this)
        surfaceView.setRenderer(
            object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER)
                    glPrefs.savedFingerprint = Build.FINGERPRINT
                    glPrefs.writePreferences()

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer)
                    runOnUiThread { completeOnCreate() }
                }

                override fun onSurfaceChanged(gl10: GL10, width: Int, height: Int) = Unit

                override fun onDrawFrame(gl10: GL10) = Unit
            },
        )
        return surfaceView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        appliedTheme = NovaThemeManager.getTheme(this)
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)
        inForeground = true

        val glPrefs = GlPreferences.readPreferences(this)
        if (glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isEmpty()) {
            setContentView(getGlSurfaceView(glPrefs))
        } else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer)
            completeOnCreate()
        }

        val hostname = intent.getStringExtra("hostname")
        val port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT)
        pendingPairingPin = intent.getStringExtra("pin")
        pendingPairingPassphrase = intent.getStringExtra("passphrase")

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = ComputerDetails.AddressTuple(hostname, port)
        } else {
            clearPendingPairing()
        }
    }

    private fun completeOnCreate() {
        completeOnCreateCalled = true

        if (NovaWelcomeActivity.shouldShow(this)) {
            startActivity(Intent(this, NovaWelcomeActivity::class.java))
        }

        shortcutHelper = ShortcutHelper(this)

        UiHelper.setLocale(this)

        bindService(
            Intent(this@PcView, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE,
        )

        val prefs = PreferenceConfiguration.readPreferences(this)
        pcGridAdapter = PcGridAdapter(this, prefs)

        viewModel = ViewModelProvider(this)[PcViewModel::class.java]
        viewModel.computersLiveData.observe(this) { newList ->
            if (!freezeUpdates) {
                syncComputerList()
                if (newList != null) {
                    maybeRunPendingQrPairing(newList)
                    checkAutoNavigation(newList)
                }
            }
        }

        initializeViews(prefs)
        handleWelcomeAction(intent.getStringExtra(NovaWelcomeActivity.EXTRA_WELCOME_ACTION))
    }

    private fun handleWelcomeAction(action: String?) {
        if (action != NovaWelcomeActivity.ACTION_SCAN_QR) {
            return
        }

        intent.removeExtra(NovaWelcomeActivity.EXTRA_WELCOME_ACTION)
        window.decorView.post { launchQrScanner() }
    }

    private fun startComputerUpdates() {
        val binder = managerBinder ?: return
        if (!runningPolling && inForeground && ::viewModel.isInitialized) {
            freezeUpdates = false
            viewModel.startPolling(binder)
            runningPolling = true
            updateEmptyState()
        }
    }

    private fun stopComputerUpdates(wait: Boolean) {
        val binder = managerBinder ?: return
        if (!runningPolling || !::viewModel.isInitialized) {
            return
        }

        freezeUpdates = true
        viewModel.stopPolling(binder)

        if (wait) {
            binder.waitForPollingStopped()
        }

        runningPolling = false
        updateEmptyState()
    }

    private fun refreshProfileButton() {
        val profilesButton = findViewById<MaterialButton>(R.id.profilesButton) ?: return
        val activeProfileName = ProfilesManager.getInstance().getActiveName()
        if (activeProfileName.isEmpty()) {
            profilesButton.contentDescription = getString(R.string.profile_manager_choose_profile)
        } else {
            profilesButton.contentDescription =
                getString(R.string.profile_manager_choose_profile) + ": " + activeProfileName
        }
    }

    public override fun onDestroy() {
        super.onDestroy()

        runtimeTasks.cancelAll()
        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
    }

    override fun onResume() {
        super.onResume()

        val currentTheme = NovaThemeManager.getTheme(this)
        if (appliedTheme != null && appliedTheme != currentTheme) {
            appliedTheme = currentTheme
            NovaThemeManager.applyTheme(this)
            applyThemeToServerBrowser()
        }

        UiHelper.showDecoderCrashDialog(this)
        refreshProfileButton()

        inForeground = true
        spaceParticleView?.resume()
        startComputerUpdates()
    }

    override fun onPause() {
        super.onPause()

        inForeground = false
        spaceParticleView?.pause()
        stopComputerUpdates(false)
    }

    override fun onStop() {
        super.onStop()

        Dialog.closeDialogs()
    }

    private fun showServerBottomSheet(computer: ComputerObject) {
        stopComputerUpdates(false)

        val sheet = BottomSheetDialog(this, R.style.NovaBottomSheet)
        sheet.setContentView(R.layout.nova_app_context_sheet)
        val sheetRoot = sheet.findViewById<View>(R.id.nova_sheet_root)
        sheet.setOnShowListener {
            NovaSheetChrome.applyBottomSheetChrome(sheet, sheetRoot)
            sheet.findViewById<TextView>(R.id.sheet_app_name)?.let(NovaSheetChrome::styleSheetTitle)
        }
        sheet.setOnDismissListener { startComputerUpdates() }

        val titleView = sheet.findViewById<TextView>(R.id.sheet_app_name)
        if (titleView != null) {
            val status =
                when (computer.details.state) {
                    ComputerDetails.State.ONLINE -> getString(R.string.pcview_menu_header_online)
                    ComputerDetails.State.OFFLINE -> getString(R.string.pcview_menu_header_offline)
                    else -> getString(R.string.pcview_menu_header_unknown)
                }
            titleView.text = getString(R.string.pcview_menu_header_format, computer.details.name, status)
        }

        val actions = sheet.findViewById<LinearLayout>(R.id.sheet_actions)
        if (actions == null) {
            sheet.show()
            return
        }

        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN
        ) {
            if (!needsPairing(computer.details)) {
                addPcSheetAction(actions, getString(R.string.pcview_menu_start_polaris)) {
                    sheet.dismiss()
                    startPolarisFromNova(computer.details)
                }
            }
            addPcSheetAction(actions, getString(R.string.pcview_menu_send_wol)) {
                sheet.dismiss()
                doWakeOnLan(computer.details)
            }
        } else if (needsPairing(computer.details)) {
            addPcSheetAction(actions, getString(R.string.pcview_menu_pair_pc)) {
                sheet.dismiss()
                doPair(computer.details, null, null)
            }
            addPcSheetAction(actions, getString(R.string.pcview_menu_pair_pc_otp)) {
                sheet.dismiss()
                doOTPPair(computer.details)
            }
            addPcSheetAction(actions, getString(R.string.pcview_menu_scan_qr)) {
                sheet.dismiss()
                launchQrScanner()
            }
            if (!computer.details.nvidiaServer) {
                addPcSheetAction(actions, getString(R.string.pcview_menu_open_management_page)) {
                    sheet.dismiss()
                    val url = computer.guessManagementUrl()
                    if (url != null) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } else {
                        Toast.makeText(this, R.string.pcview_error_no_management_url, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            addPcSheetSection(actions, getString(R.string.pcview_menu_section_play))
            if (computer.details.runningGameId != 0) {
                if (computer.details.currentGameOwnedByClient == false) {
                    addPcSheetAction(actions, getString(R.string.applist_menu_watch)) {
                        sheet.dismiss()
                        val binder = managerBinder ?: return@addPcSheetAction
                        ServerHelper.doWatch(this, createWatchTargetApp(computer.details), computer.details, binder)
                    }
                } else {
                    addPcSheetAction(actions, getString(R.string.applist_menu_resume)) {
                        sheet.dismiss()
                        resumeOrWatchRunningGame(computer.details)
                    }
                    addPcSheetAction(actions, getString(R.string.applist_menu_quit)) {
                        sheet.dismiss()
                        val runningApp = NvApp()
                        runningApp.appId = computer.details.runningGameId
                        val binder = managerBinder ?: return@addPcSheetAction
                        UiHelper.displayQuitConfirmationDialog(
                            this,
                            { ServerHelper.doQuit(this, computer.details, runningApp, binder, null) },
                            null,
                        )
                    }
                }
            }
            if (computer.details.libraryState == ComputerDetails.LibraryState.AVAILABLE) {
                addPcSheetAction(actions, getString(R.string.pcview_menu_nova_library)) {
                    sheet.dismiss()
                    doNovaLibrary(computer.details)
                }
            } else if (computer.details.libraryState == ComputerDetails.LibraryState.UNKNOWN) {
                addPcSheetAction(actions, getString(R.string.pcview_library_checking)) {
                    sheet.dismiss()
                    maybeProbeLibraryReadiness(computer)
                }
            }

            addPcSheetSection(actions, getString(R.string.pcview_menu_section_manage))
            addPcSheetAction(actions, getString(R.string.pcview_menu_start_polaris)) {
                sheet.dismiss()
                startPolarisFromNova(computer.details)
            }
            addPcSheetAction(actions, getString(R.string.pcview_menu_app_list)) {
                sheet.dismiss()
                doAppList(computer.details, false, false)
            }
            if (!computer.details.nvidiaServer) {
                addPcSheetAction(actions, getString(R.string.pcview_menu_open_management_page)) {
                    sheet.dismiss()
                    val url = computer.guessManagementUrl()
                    if (url != null) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } else {
                        Toast.makeText(this, R.string.pcview_error_no_management_url, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        addPcSheetAction(actions, getString(R.string.pcview_menu_test_network)) {
            sheet.dismiss()
            ServerHelper.doNetworkTest(this)
        }
        addPcSheetAction(actions, getString(R.string.pcview_menu_details)) {
            sheet.dismiss()
            Dialog.displayDialog(this, getString(R.string.title_details), computer.details.toString(), false)
        }

        val deleteItem = TextView(this)
        deleteItem.text = getString(R.string.pcview_menu_delete_pc)
        deleteItem.textSize = 15f
        NovaSheetChrome.styleSheetAction(deleteItem, destructive = true)
        val pad = UiHelper.dpToPx(this, 24f).toInt()
        val padV = UiHelper.dpToPx(this, 14f).toInt()
        deleteItem.setPadding(pad, padV, pad, padV)
        UiHelper.applyTvFocusStyle(deleteItem)
        deleteItem.setOnClickListener {
            sheet.dismiss()
            UiHelper.displayDeletePcConfirmationDialog(
                this,
                computer.details,
                { removeComputer(computer.details) },
                null,
            )
        }
        actions.addView(deleteItem)

        sheet.show()
    }

    private fun createWatchTargetApp(details: ComputerDetails): NvApp =
        NvApp(
            getString(R.string.applist_menu_watch_active_name),
            details.runningGameUUID,
            details.runningGameId,
            false,
        )

    private fun resumeOrWatchRunningGame(computer: ComputerDetails) {
        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        if (computer.currentGameOwnedByClient == false) {
            ServerHelper.doWatch(this, createWatchTargetApp(computer), computer, binder)
            return
        }

        val runningApp = NvApp()
        runningApp.appId = computer.runningGameId
        ServerHelper.doStart(this, runningApp, computer, binder, false)
    }

    private fun addPcSheetAction(container: LinearLayout, label: String, action: Runnable) {
        val item = TextView(this)
        item.text = label
        item.textSize = 15f
        NovaSheetChrome.styleSheetAction(item)
        val pad = UiHelper.dpToPx(this, 24f).toInt()
        val padV = UiHelper.dpToPx(this, 14f).toInt()
        item.setPadding(pad, padV, pad, padV)
        UiHelper.applyTvFocusStyle(item)
        item.setOnClickListener { action.run() }
        container.addView(item)
    }

    private fun addPcSheetSection(container: LinearLayout, label: String) {
        val item = TextView(this)
        item.text = label
        item.textSize = 11f
        item.setAllCaps(true)
        item.letterSpacing = 0.05f
        item.setTextColor(NovaThemeManager.getTextMutedColor(this))
        val pad = UiHelper.dpToPx(this, 24f).toInt()
        item.setPadding(pad, UiHelper.dpToPx(this, 14f).toInt(), pad, UiHelper.dpToPx(this, 4f).toInt())
        container.addView(item)
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt(getString(R.string.pcview_menu_scan_qr))
        options.setBeepEnabled(false)
        options.setOrientationLocked(false)
        options.setCaptureActivity(NovaQrScanActivity::class.java)
        qrScanLauncher.launch(options)
    }

    private fun handleQrScanResult(contents: String) {
        val uri = Uri.parse(contents)
        if (uri.scheme != "art") {
            Toast.makeText(this, "Invalid QR code — expected Polaris pairing code", Toast.LENGTH_SHORT).show()
            return
        }

        val pin = uri.getQueryParameter("pin")
        val passphrase = uri.getQueryParameter("passphrase")
        val host = uri.host
        val port = if (uri.port != -1) uri.port else NvHTTP.DEFAULT_HTTP_PORT

        if (pin == null || passphrase == null || host == null) {
            Toast.makeText(this, "QR code is missing pairing data", Toast.LENGTH_SHORT).show()
            return
        }

        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        pendingPairingPin = pin
        pendingPairingPassphrase = passphrase
        pendingPairingAddress = ComputerDetails.AddressTuple(host, port)

        NovaSnackbar.show(this, "Connecting to $host...")

        Thread {
            val details = ComputerDetails()
            details.manualAddress = ComputerDetails.AddressTuple(host, port)
            try {
                binder.addComputerBlocking(details)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    private fun doPair(computer: ComputerDetails, otp: String?, passphrase: String?) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, resources.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        NovaSnackbar.show(this, resources.getString(R.string.pairing))
        Thread {
            var message: String? = null
            var success = false
            var pairedComputer: ComputerDetails? = computer
            try {
                stopComputerUpdates(true)

                val httpConn =
                    NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort,
                        binder.uniqueId,
                        computer.serverCert,
                        PlatformBinding.getCryptoProvider(this@PcView),
                    )

                val existingPairState = httpConn.getPairState()
                if (existingPairState == PairState.PAIRED && hasPinnedServerCert(computer)) {
                    computer.pairState = PairState.PAIRED
                    binder.persistComputer(computer)
                    message = null
                    success = true
                } else {
                    if (existingPairState == PairState.PAIRED) {
                        LimeLog.warning(
                            "Nova: Server reports paired, but no pinned certificate is saved. Repairing pairing before launch.",
                        )
                    }
                    val pm = httpConn.getPairingManager()
                    var serverInfo = httpConn.getServerInfo(true)

                    if (otp == null && passphrase == null) {
                        if (serverInfo.contains("<TofuEnabled>1</TofuEnabled>")) {
                            LimeLog.info("TOFU: Server supports trusted subnet pairing, attempting auto-pair")
                            val tofuState = pm.pair(serverInfo, "0000", null, true)
                            if (tofuState == PairState.PAIRED) {
                                message = null
                                success = true
                                pairedComputer = applyPairedCertificate(computer, pm)
                                if (pairedComputer == null) {
                                    message = resources.getString(R.string.pair_fail)
                                    success = false
                                }

                                Dialog.closeDialogs()
                                val launchedComputer = pairedComputer
                                runOnUiThread {
                                    if (launchedComputer != null) {
                                        NovaSnackbar.showSuccess(this@PcView, "Paired successfully via TOFU")
                                        openBestPlaySurface(launchedComputer)
                                    } else {
                                        Toast.makeText(this@PcView, R.string.pair_fail, Toast.LENGTH_LONG).show()
                                        startComputerUpdates()
                                    }
                                }
                                return@Thread
                            }
                            LimeLog.info("TOFU: Auto-pair failed, falling back to PIN pairing")
                            runOnUiThread {
                                NovaSnackbar.showError(this@PcView, "TOFU auto-pair failed — trying PIN pairing")
                            }
                            serverInfo = httpConn.getServerInfo(true)
                        } else {
                            LimeLog.info("TOFU: Server does not advertise TofuEnabled — rebuild Polaris to enable")
                            runOnUiThread {
                                NovaSnackbar.showError(this@PcView, "Server doesn’t support TOFU — update Polaris or use OTP/PIN")
                            }
                        }
                    }

                    val pinStr = otp ?: PairingManager.generatePinString()
                    if (passphrase == null) {
                        Dialog.displayDialog(
                            this,
                            resources.getString(R.string.pair_pairing_title),
                            resources.getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                resources.getString(R.string.pair_pairing_help),
                            false,
                        )
                    } else {
                        Dialog.displayDialog(
                            this,
                            resources.getString(R.string.pair_pairing_title),
                            resources.getString(R.string.pair_otp_pairing_msg) + "\n\n" +
                                resources.getString(R.string.pair_otp_pairing_help),
                            false,
                        )
                    }

                    when (pm.pair(serverInfo, pinStr, passphrase)) {
                        PairState.PIN_WRONG -> message = resources.getString(R.string.pair_incorrect_pin)
                        PairState.FAILED ->
                            message =
                                if (computer.runningGameId != 0) {
                                    resources.getString(R.string.pair_pc_ingame)
                                } else {
                                    resources.getString(R.string.pair_fail)
                                }
                        PairState.ALREADY_IN_PROGRESS -> message = resources.getString(R.string.pair_already_in_progress)
                        PairState.PAIRED -> {
                            pairedComputer = applyPairedCertificate(computer, pm)
                            if (pairedComputer != null) {
                                message = null
                                success = true
                            } else {
                                message = resources.getString(R.string.pair_fail)
                                success = false
                            }
                        }
                        else -> message = null
                    }
                }
            } catch (e: UnknownHostException) {
                message = resources.getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = resources.getString(R.string.error_404)
            } catch (e: XmlPullParserException) {
                LimeLog.warning(e.toString())
                message = e.message
            } catch (e: IOException) {
                LimeLog.warning(e.toString())
                message = e.message
            }

            Dialog.closeDialogs()

            val toastMessage = message
            val toastSuccess = success
            val launchedComputer = pairedComputer
            runOnUiThread {
                if (toastMessage != null) {
                    Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
                }

                if (toastSuccess && launchedComputer != null) {
                    openBestPlaySurface(launchedComputer)
                } else {
                    startComputerUpdates()
                }
            }
        }.start()
    }

    private fun applyPairedCertificate(computer: ComputerDetails, pm: PairingManager): ComputerDetails? {
        val binder = managerBinder ?: return null
        val pairedCert = pm.getPairedCert()
        if (pairedCert == null) {
            LimeLog.warning("Nova: Pairing completed without a server certificate; refusing to mark host as paired.")
            return null
        }

        val managedComputer = binder.getComputer(computer.uuid)

        computer.serverCert = pairedCert
        computer.pairState = PairState.PAIRED

        if (managedComputer != null) {
            managedComputer.serverCert = pairedCert
            managedComputer.pairState = PairState.PAIRED
        }

        binder.persistComputer(managedComputer ?: computer)
        binder.invalidateStateForComputer(computer.uuid)

        return managedComputer ?: computer
    }

    private fun doOTPPair(computer: ComputerDetails) {
        val context: Context = this

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 40)

        val otpInput = EditText(context)
        otpInput.hint = "PIN"
        otpInput.inputType = InputType.TYPE_CLASS_NUMBER
        otpInput.filters = arrayOf(InputFilter.LengthFilter(4))

        val passphraseInput = EditText(context)
        passphraseInput.hint = getString(R.string.pair_passphrase_hint)
        passphraseInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        layout.addView(otpInput)
        layout.addView(passphraseInput)

        val dialog =
            AlertDialog.Builder(context)
                .setTitle(R.string.pcview_menu_pair_pc_otp)
                .setView(layout)
                .setPositiveButton(getString(R.string.proceed), null)
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = otpInput.text.toString()
            val passphrase = passphraseInput.text.toString()
            if (pin.length != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (passphrase.length < 4) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doPair(computer, pin, passphrase)
            dialog.dismiss()
        }
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            NovaSnackbar.show(this, resources.getString(R.string.wol_pc_online))
            return
        }

        if (computer.macAddress == null) {
            NovaSnackbar.showError(this, resources.getString(R.string.wol_no_mac))
            return
        }

        Thread {
            val message =
                try {
                    WakeOnLanSender.sendWolPacket(computer)
                    resources.getString(R.string.wol_waking_msg)
                } catch (e: IOException) {
                    resources.getString(R.string.wol_fail)
                }

            runOnUiThread { NovaSnackbar.show(this, message) }
        }.start()
    }

    private fun startPolarisFromNova(computer: ComputerDetails) {
        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }
        if (needsPairing(computer)) {
            Toast.makeText(this, R.string.pcview_polaris_start_pair_first, Toast.LENGTH_SHORT).show()
            return
        }
        val uuid = computer.uuid
        if (!polarisStartupInFlight.add(uuid)) {
            NovaSnackbar.show(this, getString(R.string.pcview_polaris_starting))
            return
        }

        NovaSnackbar.show(this, getString(R.string.pcview_polaris_starting))
        runtimeTasks.launchIo("NovaPolarisStartup") {
            val coordinator = PolarisStartupCoordinator(
                wakeSender = object : PolarisStartupCoordinator.WakeSender {
                    override fun wake(computer: ComputerDetails) {
                        WakeOnLanSender.sendWolPacket(computer)
                    }
                },
                hostPoller = object : PolarisStartupCoordinator.HostPoller {
                    override fun poll(computer: ComputerDetails): ComputerDetails {
                        return binder.pollComputerNow(computer.uuid) ?: computer
                    }
                },
                polarisProbe = object : PolarisStartupCoordinator.PolarisProbe {
                    override fun hasGameLibrary(computer: ComputerDetails): Boolean {
                        return probePolarisGameLibrary(computer)
                    }
                }
            )
            val result = coordinator.start(computer)
            runtimeTasks.runOnMainIfActive {
                polarisStartupInFlight.remove(uuid)
                handlePolarisStartupResult(result)
            }
        }
    }

    private fun probePolarisGameLibrary(computer: ComputerDetails): Boolean {
        val activeAddress = computer.activeAddress ?: return false
        val serverCert = computer.serverCert ?: return false
        val httpsPort = if (computer.httpsPort > 0) computer.httpsPort else 47984
        return try {
            val client = PolarisApiClient(this@PcView, activeAddress.address, httpsPort, serverCert)
            client.getCapabilities()?.features?.gameLibrary == true
        } catch (e: Exception) {
            LimeLog.warning("Nova: Polaris startup probe failed for ${computer.name}: " + e.message)
            false
        }
    }

    private fun handlePolarisStartupResult(result: com.papi.nova.manager.PolarisStartupResult) {
        when (result.status) {
            PolarisStartupStatus.READY -> {
                val computer = updatePolarisStartupComputer(result.computer)
                if (computer == null) {
                    NovaSnackbar.showError(this, getString(R.string.pcview_polaris_start_failed))
                    return
                }
                NovaSnackbar.show(this, getString(R.string.pcview_polaris_started))
                doNovaLibrary(computer)
            }
            PolarisStartupStatus.NEEDS_PAIRING ->
                NovaSnackbar.showError(this, getString(R.string.pcview_polaris_start_pair_first))
            PolarisStartupStatus.MISSING_MAC ->
                NovaSnackbar.showError(this, getString(R.string.wol_no_mac))
            PolarisStartupStatus.WAKE_FAILED ->
                NovaSnackbar.showError(this, getString(R.string.wol_fail))
            PolarisStartupStatus.TIMEOUT ->
                NovaSnackbar.showError(this, getString(R.string.pcview_polaris_start_timeout))
            PolarisStartupStatus.POLARIS_UNAVAILABLE ->
                NovaSnackbar.showError(this, getString(R.string.pcview_polaris_start_unavailable))
        }
    }

    private fun updatePolarisStartupComputer(started: ComputerDetails?): ComputerDetails? {
        if (started == null) {
            return null
        }
        started.libraryState = ComputerDetails.LibraryState.AVAILABLE
        val binder = managerBinder
        val managedComputer = binder?.getComputer(started.uuid)
        if (managedComputer != null) {
            managedComputer.update(started)
            managedComputer.libraryState = ComputerDetails.LibraryState.AVAILABLE
        }

        val computers = if (::viewModel.isInitialized) viewModel.computersLiveData.value else null
        var selected = managedComputer ?: started
        if (computers != null) {
            for (candidate in computers) {
                if (candidate.details.uuid == started.uuid) {
                    candidate.details.update(started)
                    candidate.details.libraryState = ComputerDetails.LibraryState.AVAILABLE
                    selected = candidate.details
                    break
                }
            }
        }
        syncComputerList()
        return selected
    }

    private fun doAppList(computer: ComputerDetails, newlyPaired: Boolean, showHiddenGames: Boolean) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(this, resources.getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, AppView::class.java)
        intent.putExtra(AppView.NAME_EXTRA, computer.name)
        intent.putExtra(AppView.UUID_EXTRA, computer.uuid)
        intent.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired)
        intent.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames)
        startActivity(intent)
        NovaThemeManager.applyForwardTransition(this)
    }

    private fun doNovaLibrary(computer: ComputerDetails) {
        val activeAddress = computer.activeAddress
        if (computer.state == ComputerDetails.State.OFFLINE || activeAddress == null) {
            Toast.makeText(this, resources.getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }
        val binder = managerBinder
        if (binder == null) {
            Toast.makeText(this, resources.getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show()
            return
        }

        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(PREF_LAST_LIBRARY_PC_UUID, computer.uuid)
            .apply()

        val intent = Intent(this, NovaLibraryActivity::class.java)
        intent.putExtra(NovaLibraryActivity.EXTRA_HOST, activeAddress.address)
        intent.putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, computer.name)
        intent.putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, activeAddress.port)
        intent.putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, computer.httpsPort)
        intent.putExtra(NovaLibraryActivity.EXTRA_UNIQUE_ID, binder.uniqueId)
        intent.putExtra(NovaLibraryActivity.EXTRA_PC_UUID, computer.uuid)
        val serverCommands = computer.serverCommands
        if (serverCommands != null) {
            intent.putStringArrayListExtra(
                NovaLibraryActivity.EXTRA_SERVER_COMMANDS,
                ArrayList(serverCommands),
            )
        }
        try {
            val serverCert = computer.serverCert
            if (serverCert != null) {
                intent.putExtra(NovaLibraryActivity.EXTRA_SERVER_CERT, serverCert.encoded)
            }
        } catch (e: CertificateEncodingException) {
            LimeLog.warning("Nova: Failed to encode server cert for library launch: " + e.message)
        }
        startActivity(intent)
        NovaThemeManager.applyForwardTransition(this)
    }

    private fun removeComputer(details: ComputerDetails) {
        val binder = managerBinder ?: return
        binder.removeComputer(details)

        DiskAssetLoader(this).deleteAssetsForComputer(details.uuid)

        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit()
            .remove(details.uuid)
            .apply()

        shortcutHelper.disableComputerShortcut(
            details,
            resources.getString(R.string.scut_deleted_pc),
        )

        syncComputerList()
    }

    private fun checkAutoNavigation(computers: List<ComputerObject>?) {
        if (autoNavigated || pendingPairingAddress != null || computers == null) {
            return
        }

        val libraryTarget = selectPreferredLibraryComputer(computers)
        if (libraryTarget != null) {
            autoNavigated = true
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    if (inForeground && !isFinishing) {
                        doNovaLibrary(libraryTarget.details)
                    }
                },
                400,
            )
            return
        }

        val autoConnectEnabled =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("nova_auto_connect", false)
        if (!autoConnectEnabled) {
            return
        }

        var pairedOnlineCount = 0
        var singleServer: ComputerObject? = null
        for (computer in computers) {
            if (computer.details.state == ComputerDetails.State.ONLINE &&
                !needsPairing(computer.details) &&
                computer.details.libraryState == ComputerDetails.LibraryState.UNAVAILABLE
            ) {
                pairedOnlineCount++
                singleServer = computer
            }
        }
        if (pairedOnlineCount == 1) {
            val target = singleServer ?: return
            autoNavigated = true
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    if (inForeground && !isFinishing) {
                        doAppList(target.details, false, false)
                    }
                },
                400,
            )
        }
    }

    override fun getAdapterFragmentLayoutId(): Int = R.layout.pc_grid_view

    override fun receiveAbsListView(gridView: View) {
        if (gridView is RecyclerView) {
            if (!::pcGridAdapter.isInitialized) {
                return
            }

            val rv = gridView
            rv.layoutManager = GridLayoutManager(this, 1)
            rv.adapter = pcGridAdapter
            rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    moveFocusToFirstServerRow()
                }
            }
            rv.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(firstRow: View) {
                        UiHelper.applyTvFocusStyle(firstRow)
                        if (rv.getChildAdapterPosition(firstRow) == 0) {
                            setServerFilterNextFocusDown(firstRow)
                        }
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                },
            )
            pcGridAdapter.setOnItemClickListener { computer ->
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE
                ) {
                    showServerBottomSheet(computer)
                } else if (needsPairing(computer.details)) {
                    showServerBottomSheet(computer)
                } else {
                    openBestPlaySurface(computer.details)
                }
            }
            rv.addOnItemTouchListener(
                RecyclerItemClickListener(
                    this,
                    rv,
                    object : RecyclerItemClickListener.OnItemClickListener {
                        override fun onItemClick(view: View, position: Int) = Unit

                        override fun onLongItemClick(view: View, position: Int) {
                            val computer = pcGridAdapter.getItem(position)
                            showServerBottomSheet(computer)
                        }
                    },
                ),
            )
            UiHelper.applyStatusBarPadding(rv)
            rv.post {
                for (i in 0 until rv.childCount) {
                    UiHelper.applyTvFocusStyle(rv.getChildAt(i))
                }
                if (rv.childCount > 0) {
                    val firstRow = rv.getChildAt(0)
                    setServerFilterNextFocusDown(firstRow)
                }
            }
        }
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_ONLINE = 1
        private const val FILTER_STREAMING = 2
        private const val FILTER_NEEDS_PAIRING = 3
        private const val PREF_LAST_LIBRARY_PC_UUID = "nova_last_library_pc_uuid"
    }
}
