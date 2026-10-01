package com.papi.nova

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.papi.nova.PcViewModel.ComputerObject
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.api.PolarisHostSleepResult
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.binding.crypto.AndroidCryptoProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.computers.HostForget
import com.papi.nova.grid.NovaHostPlaySurface
import com.papi.nova.grid.NovaHostRowFocusMove
import com.papi.nova.grid.PcGridAdapter
import com.papi.nova.grid.novaHostPlaySurface
import com.papi.nova.grid.novaHostRowFocusMove
import com.papi.nova.grid.assets.DiskAssetLoader
import com.papi.nova.manager.HoldToConfirm
import com.papi.nova.manager.HostPowerAction
import com.papi.nova.manager.HostPowerPolicy
import com.papi.nova.manager.HostSleepSequence
import com.papi.nova.manager.HostSleepUnavailable
import com.papi.nova.manager.PolarisStartupCoordinator
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.manager.PolarisStartupStatus
import com.papi.nova.manager.TcpHostReachabilityProbe
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.http.PairingManager.PairState
import com.papi.nova.nvstream.wol.WakeOnLanSender
import com.papi.nova.preferences.AddComputerManually
import com.papi.nova.binding.video.PyroWave
import com.papi.nova.preferences.GlPreferences
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.NovaUpdateCheckResult
import com.papi.nova.preferences.NovaUpdateChecker
import com.papi.nova.preferences.NovaUpdateInstaller
import com.papi.nova.preferences.NovaUpdateInstallResult
import com.papi.nova.preferences.NovaUpdatePromptPreferences
import com.papi.nova.preferences.NovaUpdateRelease
import com.papi.nova.preferences.StreamSettings
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.runtime.NovaRuntimeTasks
import com.papi.nova.ui.AdapterFragment
import com.papi.nova.ui.AdapterFragmentCallbacks
import com.papi.nova.ui.NovaControlSize
import com.papi.nova.ui.NovaControlSizePreferences
import com.papi.nova.ui.NovaHostsViewMetrics
import com.papi.nova.ui.NovaLibraryActivity
import com.papi.nova.ui.NovaServerGridLayoutManager
import com.papi.nova.ui.NovaQrScanActivity
import com.papi.nova.ui.NovaHostMenuActions
import com.papi.nova.ui.novaHostMenuHeader
import com.papi.nova.ui.NovaHostConsole
import com.papi.nova.ui.NovaHostConsoleLink
import com.papi.nova.ui.NovaHostConsolePage
import com.papi.nova.ui.novaHostMenuItems
import com.papi.nova.ui.compose.NovaThemeSwatch
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaField
import com.papi.nova.ui.panel.NovaFieldKind
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaProblemBack
import android.content.pm.PackageManager
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.novaSurfaces
import com.papi.nova.ui.NovaSnackbar
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.NovaWelcomeActivity
import com.papi.nova.ui.SpaceParticleView
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.HelpLauncher
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateEncodingException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException

/**
 * The themes as a Choice page: each with its swatch and a caption, the current one marked and
 * focused. One A applies a theme and closes the panel. Settings passes [useDefault] while a
 * preset overrides the theme, as the page's last row (C02).
 */
internal fun novaThemePickerPage(
    context: Context,
    themes: List<String>,
    current: String,
    useDefault: com.papi.nova.ui.panel.NovaUseDefault? = null,
    onChoose: (String) -> Unit,
): NovaCommonPage.Choice<String> = NovaCommonPage.Choice(
    key = "theme",
    title = context.getString(R.string.pcview_theme_picker_title),
    options = themes.map { theme ->
        NovaOption(theme, NovaThemeManager.getThemeLabel(context, theme), caption = context.getString(novaThemePickerCaption(theme)))
    },
    current = current,
    onChoose = onChoose,
    leading = { option -> NovaThemeSwatch(option.value) },
    // Two to a line on a landscape handheld, as the picker's cards were before it became a page.
    width = NovaPanelWidth.Grid,
    useDefault = useDefault,
)

/** A theme's caption on the picker page, kept to two lines beside its swatch. */
@StringRes
internal fun novaThemePickerCaption(theme: String): Int = when (theme) {
    NovaThemeManager.THEME_POLARIS -> R.string.hosts_theme_caption_polaris
    NovaThemeManager.THEME_PORTABLE_CHROME -> R.string.hosts_theme_caption_portable_chrome
    NovaThemeManager.THEME_OLED -> R.string.hosts_theme_caption_oled
    NovaThemeManager.THEME_MIAMI -> R.string.hosts_theme_caption_miami
    NovaThemeManager.THEME_HIGH_CONTRAST -> R.string.hosts_theme_caption_high_contrast
    NovaThemeManager.THEME_MATERIAL_YOU -> R.string.hosts_theme_caption_material_you
    else -> R.string.hosts_theme_caption_polaris
}

/**
 * OTP pairing as a Form page: the PIN and the passphrase, then Pair. A short PIN or passphrase
 * keeps the page and says why; a good pair runs [onPair] and leaves the page to it.
 */
internal fun novaOtpPairPage(context: Context, onPair: (pin: String, passphrase: String) -> Unit): NovaCommonPage.Form =
    NovaCommonPage.Form(
        key = "pair_otp",
        title = context.getString(R.string.pcview_menu_pair_pc_otp),
        fields = listOf(
            NovaField(key = NOVA_OTP_PIN, label = context.getString(R.string.hosts_otp_pin), kind = NovaFieldKind.Number, maxLength = 4),
            NovaField(key = NOVA_OTP_PASSPHRASE, label = context.getString(R.string.pair_passphrase_hint), kind = NovaFieldKind.Password),
        ),
        submitLabel = context.getString(R.string.hosts_pair),
        onSubmit = { values ->
            val pin = values[NOVA_OTP_PIN].orEmpty()
            val passphrase = values[NOVA_OTP_PASSPHRASE].orEmpty()
            when {
                pin.length != 4 -> context.getString(R.string.pair_pin_length_msg)
                passphrase.length < 4 -> context.getString(R.string.pair_passphrase_length_msg)
                else -> {
                    onPair(pin, passphrase)
                    null
                }
            }
        },
    )

private const val NOVA_OTP_PIN = "pin"
private const val NOVA_OTP_PASSPHRASE = "passphrase"

/**
 * The pairing PIN, full screen while the host waits for it to be typed there. [onClose] hides the
 * page; pairing goes on until the host answers. [note], when there is one, says first why it came
 * to a PIN, such as automatic pairing not finishing: that floated in a snackbar under the page.
 */
internal fun novaPairingCodePage(
    context: Context,
    key: String,
    pin: String,
    note: String? = null,
    onClose: () -> Unit,
): NovaStatePage.Code =
    NovaStatePage.Code(
        key = key,
        title = context.getString(R.string.hosts_pairing_code_title),
        code = pin,
        message = listOfNotNull(note, context.getString(R.string.hosts_pairing_code_message)).joinToString("\n\n"),
        close = NovaAction(context.getString(R.string.nova_panel_close), run = onClose),
    )

/**
 * Pairing on its way before there is a PIN to show: Nova finding a scanned host, or asking a host
 * to pair. A full screen Busy page in the pairing page's place, which the PIN or the OTP wait then
 * takes over and which goes when pairing ends; its Close hides it while pairing goes on. "Connecting
 * to" and "Pairing" floated as snackbars and were gone in two seconds (audit X2).
 */
internal fun novaPairingProgressPage(context: Context, key: String, message: String, onClose: () -> Unit): NovaStatePage.Busy =
    NovaStatePage.Busy(
        key = key,
        title = context.getString(R.string.pair_pairing_title),
        message = MutableStateFlow(message),
        cancel = NovaAction(context.getString(R.string.nova_panel_close), run = onClose),
    )

/**
 * OTP pairing has nothing to type on the host, so it waits instead of showing a code: a full
 * screen Busy page whose Close hides the wait while pairing goes on, as the code page's Close
 * does. The page also goes when pairing ends, however it ends.
 */
internal fun novaOtpPairingWaitPage(context: Context, key: String, onClose: () -> Unit): NovaStatePage.Busy =
    NovaStatePage.Busy(
        key = key,
        title = context.getString(R.string.pair_pairing_title),
        message = MutableStateFlow(context.getString(R.string.pair_otp_pairing_help)),
        cancel = NovaAction(context.getString(R.string.nova_panel_close), run = onClose),
    )

/**
 * Scan Pair with no camera to scan with, or with camera access refused: a state page that says
 * so and offers the way that needs no camera. zxing's scanner had opened on a black void.
 */
internal fun novaScanPairCameraPage(
    context: Context,
    key: String,
    denied: Boolean,
    retry: () -> Unit,
    addServer: () -> Unit,
    takeDown: () -> Unit,
): NovaStatePage.Problem {
    fun leaving(label: Int, run: () -> Unit) = NovaAction(context.getString(label)) {
        takeDown()
        run()
    }
    val add = leaving(R.string.pcview_quick_add_server, addServer)
    val back = leaving(R.string.nova_panel_back) {}
    return NovaStatePage.Problem(
        key = key,
        title = context.getString(if (denied) R.string.nova_scan_pair_camera_denied_title else R.string.nova_scan_pair_no_camera_title),
        message = context.getString(if (denied) R.string.nova_scan_pair_camera_denied_message else R.string.nova_scan_pair_no_camera_message),
        primary = if (denied) leaving(R.string.nova_scan_pair_try_again, retry) else add,
        back = NovaProblemBack.Continue(back),
        secondary = if (denied) listOf(add, back) else listOf(back),
    )
}

internal fun dashboardSetupActionHeight(collapsed: Boolean, compactHeight: Int): Int =
    if (collapsed) compactHeight else LinearLayout.LayoutParams.WRAP_CONTENT

class PcView : NovaActivity(), AdapterFragmentCallbacks {
    private var noPcFoundLayout: View? = null
    private lateinit var pcGridAdapter: PcGridAdapter
    private var serverGridView: RecyclerView? = null
    private lateinit var shortcutHelper: ShortcutHelper
    private lateinit var viewModel: PcViewModel
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false
    private var autoNavigated = false
    /** Set before a theme recreate, so the new activity puts focus back on Theme. */
    private var returnFocusToTheme = false
    private var automaticUpdatePromptShown = false
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
    private val hostPowerCapabilities: MutableMap<String, PolarisCapabilities> = ConcurrentHashMap()
    private val hostPowerProbedAtMs: MutableMap<String, Long> = ConcurrentHashMap()
    private val hostPowerProbeInFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hostSleepHold = HoldToConfirm()
    private val hostSleepHandler = Handler(Looper.getMainLooper())
    private var hostSleepHoldTicker: Runnable? = null
    private var hostSleepPending: Runnable? = null
    private var hostSleepHoldConsumed = false
    private var hostSleepHoldFill: ClipDrawable? = null
    private val hostSleepSequence = HostSleepSequence()
    private var hostSleepCancelledByLeaving = false
    private var hostSleepAccessibilityActionId = View.NO_ID
    private var appliedTheme: String? = null
    private var spaceParticleView: SpaceParticleView? = null
    private enum class DashboardUpdatePillStatus { CURRENT, AVAILABLE, CHECKING, ERROR }
    private var dashboardUpdatePillStatus = DashboardUpdatePillStatus.CURRENT
    private var dashboardUpdatePillRelease: NovaUpdateRelease? = null
    private var portraitMenuExpanded = false
    private var hostsViewMetrics: NovaHostsViewMetrics? = null
    private var hostsControlSize: NovaControlSize? = null
    private data class HostsFocus(val actionId: Int = View.NO_ID, val uuid: String? = null, val manage: Boolean = false)
    private var pendingHostsFocus: HostsFocus? = null
    private var hostsViewGeneration = 0
    private val portraitMenuBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { setPortraitMenuExpanded(false, focusToggle = true) }
    }
    private var dashboardRailCollapsed = false
    /** Whether the rail's labels are off, which trails [dashboardRailCollapsed] while the rail widens. */
    private var dashboardRailLabelsHidden = false
    private var dashboardRailAnimator: ValueAnimator? = null
    private var hostPanelWatch: Job? = null
    private val dashboardRailButtonText = mutableMapOf<Int, CharSequence>()

    /** Puts the caption under the top actions back in step with the focused action, as it is now. */
    private var refreshTopActionCaption: () -> Unit = {}

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pendingHostsFocus = null
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) pendingHostsFocus = null
        if (event.action == KeyEvent.ACTION_DOWN && moveFocusWithinHostRow(event.keyCode)) {
            return true
        }
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

    private fun moveFocusWithinHostRow(keyCode: Int): Boolean {
        val focus = currentFocus ?: return false
        val row = serverGridView?.findContainingItemView(focus) ?: return false
        val move = novaHostRowFocusMove(
            right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT,
            left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT,
            onRow = focus === row,
            onManage = focus.id == R.id.server_actions_button,
        )
        return when (move) {
            NovaHostRowFocusMove.TO_MANAGE -> row.findViewById<View>(R.id.server_actions_button)?.requestFocus() == true
            NovaHostRowFocusMove.TO_ROW -> row.requestFocus()
            NovaHostRowFocusMove.NONE -> false
        }
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
        return focus.id == R.id.dashboardRailToggle ||
            focus.id == R.id.profilesButton ||
            focus.id == R.id.actionNovaUpdate ||
            focus.id == R.id.actionStartPolaris ||
            focus.id == R.id.actionTheme ||
            focus.id == R.id.actionGithub ||
            focus.id == R.id.actionSettings
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
                // Already paired, so there is nothing to pair: the wait from the scan goes.
                hidePairingPage()
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

    // Asked here, before the scanner opens, so a refusal gets a page that says so.
    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startQrScanner() else showScanPairCameraPage(denied = true)
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
            pendingHostsFocus = captureHostsFocus()
            initializeViews(PreferenceConfiguration.readPreferences(this))
        }

        refreshProfileButton()
    }

    private fun initializeViews(prefs: PreferenceConfiguration) {
        hostsViewGeneration++
        dashboardRailAnimator?.cancel()
        dashboardRailButtonText.clear()
        setContentView(R.layout.activity_pc_view)

        // The particle field and the background run under the system bars; only the
        // dashboard's controls are kept clear of them.
        UiHelper.notifyNewRootView(
            this,
            findViewById<View>(R.id.dashboardCockpit)
                ?: findViewById<View>(R.id.dashboardContent)
                ?: findViewById<View>(android.R.id.content),
        )

        val header = findViewById<View>(R.id.pcViewHeader)
        if (header != null) {
            // Breathing room only. The root above already keeps the dashboard clear of the status
            // bar, and the header used to add the bar's height a second time when upright: on a
            // Pixel 10 Pro that is 80dp, so "Nova" started 180dp down a screen with nothing over it.
            val headerTopPadding =
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    UiHelper.dpToPx(this, 4f).toInt()
                } else {
                    UiHelper.dpToPx(this, 16f).toInt()
                }
            header.setPadding(
                header.paddingLeft,
                headerTopPadding,
                header.paddingRight,
                header.paddingBottom,
            )
        }

        hostsViewMetrics = NovaHostsViewMetrics(this).apply {
            findViewById<View>(R.id.dashboardCockpitRail)?.let(::capture)
            findViewById<View>(R.id.pcViewHeader)?.let(::capture)
            findViewById<View>(R.id.pcFragmentContainer)?.let(::capture)
        }
        hostsControlSize = null

        spaceParticleView = findViewById(R.id.space_particles)
        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(NovaThemeManager.getAccentColor(this))
            swipeRefresh.setProgressBackgroundColorSchemeColor(
                NovaThemeManager.getCardBackgroundColor(this),
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
        val startPolarisAction = findViewById<View>(R.id.actionStartPolaris)
        val updateAction = findViewById<View>(R.id.actionNovaUpdate)
        val themeAction = findViewById<View>(R.id.actionTheme)
        val settingsAction = findViewById<View>(R.id.actionSettings)
        val githubAction = findViewById<View>(R.id.actionGithub)
        val dashboardRailToggle = findViewById<MaterialButton>(R.id.dashboardRailToggle)
        dashboardRailCollapsed = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            loadDashboardRailCollapsedPreference()
        val topActionFocusLabel = findViewById<TextView>(R.id.topActionFocusLabel)
        val emptyRefresh = findViewById<TextView>(R.id.emptyRefresh)
        val emptyAddServer = findViewById<TextView>(R.id.emptyAddServer)
        val emptyScanPair = findViewById<TextView>(R.id.emptyScanPair)
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
        // A device with no camera has nothing to scan with, so it is not offered.
        scanPairAction?.visibility = if (hasCamera()) scanPairAction?.visibility ?: View.VISIBLE else View.GONE
        bindHostPowerAction(startPolarisAction)
        updateAction?.setOnClickListener { checkNovaUpdateFromDashboard() }
        themeAction?.setOnClickListener { v ->
            showThemePicker(v)
        }
        settingsAction?.setOnClickListener {
            startActivity(Intent(this@PcView, StreamSettings::class.java))
            NovaThemeManager.applyFadeTransition(this@PcView)
        }
        githubAction?.setOnClickListener { HelpLauncher.launchGithub(this@PcView) }
        dashboardRailToggle?.setOnClickListener {
            if (isPortraitHosts()) setPortraitMenuExpanded(!portraitMenuExpanded, focusToggle = true)
            else setDashboardRailCollapsed(!dashboardRailCollapsed)
        }
        emptyRefresh?.setOnClickListener {
            resetLibraryReadiness()
            stopComputerUpdates(false)
            startComputerUpdates()
        }
        emptyAddServer?.setOnClickListener {
            startActivity(Intent(this@PcView, AddComputerManually::class.java))
        }
        emptyScanPair?.setOnClickListener { launchQrScanner() }
        if (!hasCamera()) emptyScanPair?.visibility = View.GONE
        profilesButton?.setOnClickListener {
            startActivity(Intent(this@PcView, ProfilesActivity::class.java))
        }
        bindTopActionFocusLabel(
            topActionFocusLabel,
            dashboardRailToggle to R.string.pcview_rail_collapse,
            profilesButton to R.string.pcview_quick_profiles,
            updateAction to R.string.pcview_quick_update_check,
            startPolarisAction to R.string.pcview_quick_start_polaris,
            themeAction to R.string.pcview_quick_theme,
            githubAction to R.string.pcview_quick_github,
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
        }

        applyThemeToServerBrowser()
        applyHostsControlSize()
        updateDashboardUpdatePill()
        updateModeTabs()
        updateServerFilterTabs()
        syncComputerList()

        supportFragmentManager.beginTransaction()
            .replace(R.id.pcFragmentContainer, AdapterFragment())
            .commitAllowingStateLoss()

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout)
        updateEmptyState()
        scheduleHostsFocusRestore()
    }

    private fun isPortraitHosts(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun setPortraitMenuExpanded(expanded: Boolean, focusToggle: Boolean = false) {
        portraitMenuExpanded = expanded
        val navigation = findViewById<View>(R.id.dashboardPortraitNavigation)
        val toggle = findViewById<MaterialButton>(R.id.dashboardRailToggle)
        if (isPortraitHosts() && navigation != null) {
            if (focusToggle || !expanded && navigation.hasFocus()) toggle?.requestFocus()
            navigation.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle?.setText(if (expanded) R.string.nova_hosts_hide_menu else R.string.nova_hosts_menu)
            toggle?.contentDescription = getString(if (expanded) R.string.nova_hosts_hide_menu else R.string.nova_hosts_menu)
            toggle?.setIconResource(if (expanded) R.drawable.ic_menu_collapse else R.drawable.ic_menu)
            refreshTopActionCaption()
        }
        // Nova surfaces own their separate dialog window's Back; this is the underlying Activity.
        portraitMenuBack.isEnabled = isPortraitHosts() && expanded
    }

    private fun applyHostsControlSize() {
        val size = NovaControlSizePreferences.read(PreferenceManager.getDefaultSharedPreferences(this))
        if (hostsControlSize == size) return
        hostsControlSize = size
        hostsViewMetrics?.apply(size)
        if (isPortraitHosts()) setPortraitMenuExpanded(portraitMenuExpanded)
        else setDashboardRailCollapsed(dashboardRailCollapsed, persist = false, animate = false)
        // Rebind the retained rows, keeping their stable UUID IDs and action callbacks.
        if (::pcGridAdapter.isInitialized) pcGridAdapter.refreshControlSize()
    }

    private fun captureHostsFocus(): HostsFocus? {
        val focus = currentFocus ?: return null
        val row = serverGridView?.findContainingItemView(focus)
        if (row != null) {
            val position = serverGridView?.getChildAdapterPosition(row) ?: RecyclerView.NO_POSITION
            if (position in 0 until pcGridAdapter.itemCount) {
                return HostsFocus(uuid = pcGridAdapter.getItem(position).details.uuid, manage = focus.id == R.id.server_actions_button)
            }
        }
        return focus.id.takeIf { it != View.NO_ID }?.let { HostsFocus(actionId = it) }
    }

    private fun scheduleHostsFocusRestore() {
        val generation = hostsViewGeneration
        fun restore(last: Boolean) {
            if (generation != hostsViewGeneration || isFinishing || isDestroyed) return
            // initializeViews also runs during onCreate, before the window is attached. An
            // unavailable target at that point is not a missing action: retain its saved focus.
            if (!window.decorView.isAttachedToWindow || !window.decorView.isShown) return
            val saved = pendingHostsFocus ?: return
            val target = if (saved.uuid != null) {
                val position = pcGridAdapter.itemList.indexOfFirst { it.details.uuid == saved.uuid }
                val row = if (position >= 0) serverGridView?.findViewHolderForItemId(pcGridAdapter.getItemId(position))?.itemView else null
                if (saved.manage) row?.findViewById<View>(R.id.server_actions_button) else row
            } else findViewById<View>(saved.actionId)
            if (target?.isShown == true && target.requestFocus()) pendingHostsFocus = null
            else if (saved.uuid == null || last) {
                findViewById<View>(R.id.dashboardRailToggle)?.requestFocus()
                pendingHostsFocus = null
            }
        }
        window.decorView.doOnLayout { restore(false) }
        window.decorView.post { restore(false) }
        window.decorView.postDelayed({ restore(false) }, 150)
        window.decorView.postDelayed({ restore(true) }, 500)
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
        findViewById<TextView>(R.id.pcViewHostsLabel)?.setTextColor(textPrimary)
        findViewById<TextView>(R.id.pcViewHostsSummary)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.dashboardModeStatus)?.setTextColor(textSecondary)
        findViewById<TextView>(R.id.topActionFocusLabel)?.setTextColor(textSecondary)
        findViewById<TextView>(R.id.pcViewEmptyTitle)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.pcViewEmptyHint)?.setTextColor(textMuted)

        styleModeSegment(findViewById(R.id.modeServers), true, accent, surface, divider, textPrimary, textMuted)
        styleModeSegment(findViewById(R.id.modeLibrary), false, accent, surface, divider, textPrimary, textMuted)

        styleActionButton(findViewById(R.id.actionAddServer), ColorUtils.blendARGB(surface, accent, 0.26f), textPrimary)
        styleActionButton(findViewById(R.id.actionScanPair), surface, textPrimary)
        styleDashboardUpdatePill(findViewById(R.id.actionNovaUpdate), surface, accent, divider, textPrimary, textMuted)
        styleActionButton(findViewById(R.id.actionStartPolaris), ColorUtils.blendARGB(surface, accent, 0.18f), textPrimary)
        styleActionButton(findViewById(R.id.actionTheme), surface, textPrimary)
        styleActionButton(findViewById(R.id.actionGithub), surface, textPrimary)
        styleActionButton(findViewById(R.id.actionSettings), surface, textPrimary)
        findViewById<MaterialCardView>(R.id.hostsNavigationActions)?.let { group ->
            group.setCardBackgroundColor(surface)
            group.strokeColor = divider
            group.strokeWidth = UiHelper.dpToPx(this, 1f).toInt()
        }
        styleActionButton(findViewById(R.id.dashboardRailToggle), surface, textPrimary)

        tintChipRow(
            intArrayOf(
                R.id.emptyRefresh,
                R.id.emptyAddServer,
                R.id.emptyScanPair,
            ),
            textPrimary,
        )

        styleActionButton(findViewById(R.id.profilesButton), surface, textPrimary)
        if (dashboardRailCollapsed) {
            setDashboardRailCollapsed(true, persist = false, animate = false)
        }
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

    private fun styleDashboardUpdatePill(
        pill: MaterialCardView?,
        surface: Int,
        accent: Int,
        divider: Int,
        textPrimary: Int,
        textMuted: Int,
    ) {
        if (pill == null) {
            return
        }
        val available = dashboardUpdatePillStatus == DashboardUpdatePillStatus.AVAILABLE
        val checking = dashboardUpdatePillStatus == DashboardUpdatePillStatus.CHECKING
        pill.setCardBackgroundColor(
            if (available || checking) ColorUtils.blendARGB(surface, accent, 0.18f) else ColorUtils.blendARGB(surface, accent, 0.08f)
        )
        pill.strokeColor = if (available || checking || pill.hasFocus()) accent else divider
        pill.strokeWidth = UiHelper.dpToPx(this, if (available || checking || pill.hasFocus()) 2f else 1f).toInt()
        findViewById<TextView>(R.id.updateStatusLabel)?.setTextColor(if (available || checking) accent else textMuted)
        findViewById<TextView>(R.id.updateVersionLabel)?.setTextColor(textPrimary)
        updateDashboardUpdatePill()
    }

    private fun updateDashboardUpdatePill(
        status: DashboardUpdatePillStatus = dashboardUpdatePillStatus,
        release: NovaUpdateRelease? = dashboardUpdatePillRelease,
    ) {
        dashboardUpdatePillStatus = status
        dashboardUpdatePillRelease = release
        val pill = findViewById<MaterialCardView>(R.id.actionNovaUpdate) ?: return
        val statusLabel = findViewById<TextView>(R.id.updateStatusLabel)
        val versionLabel = findViewById<TextView>(R.id.updateVersionLabel)
        val current = BuildConfig.VERSION_NAME
        val latest = release?.versionName
        val accent = NovaThemeManager.getAccentColor(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val divider = NovaThemeManager.getDividerColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textMuted = NovaThemeManager.getTextMutedColor(this)
        val statusColor = when (status) {
            DashboardUpdatePillStatus.AVAILABLE -> accent
            DashboardUpdatePillStatus.CHECKING -> accent
            DashboardUpdatePillStatus.ERROR -> ContextCompat.getColor(this, R.color.nova_warning)
            DashboardUpdatePillStatus.CURRENT -> ContextCompat.getColor(this, R.color.nova_success)
        }
        statusLabel?.text = when (status) {
            DashboardUpdatePillStatus.AVAILABLE -> getString(R.string.pcview_update_status_available)
            DashboardUpdatePillStatus.CHECKING -> getString(R.string.pcview_update_status_checking)
            DashboardUpdatePillStatus.ERROR -> getString(R.string.pcview_update_status_retry)
            DashboardUpdatePillStatus.CURRENT -> getString(R.string.pcview_update_status_current)
        }
        versionLabel?.text = when {
            status == DashboardUpdatePillStatus.AVAILABLE && latest != null ->
                getString(R.string.pcview_update_pill_available_version, current, latest)
            status == DashboardUpdatePillStatus.CHECKING ->
                getString(R.string.pcview_update_pill_checking_version)
            else -> getString(R.string.pcview_update_pill_current_version, current)
        }
        pill.contentDescription = when {
            status == DashboardUpdatePillStatus.AVAILABLE && latest != null ->
                getString(R.string.pcview_update_pill_content_available, current, latest)
            status == DashboardUpdatePillStatus.CHECKING -> getString(R.string.pcview_update_pill_content_checking)
            status == DashboardUpdatePillStatus.ERROR -> getString(R.string.pcview_update_pill_content_retry)
            else -> getString(R.string.pcview_update_pill_content_current, current)
        }
        pill.setCardBackgroundColor(
            if (status == DashboardUpdatePillStatus.AVAILABLE || status == DashboardUpdatePillStatus.CHECKING) {
                ColorUtils.blendARGB(surface, accent, 0.18f)
            } else {
                ColorUtils.blendARGB(surface, accent, 0.08f)
            }
        )
        pill.strokeColor = if (status == DashboardUpdatePillStatus.AVAILABLE || status == DashboardUpdatePillStatus.CHECKING || pill.hasFocus()) accent else divider
        pill.strokeWidth = UiHelper.dpToPx(this, if (status == DashboardUpdatePillStatus.AVAILABLE || status == DashboardUpdatePillStatus.CHECKING || pill.hasFocus()) 2f else 1f).toInt()
        statusLabel?.setTextColor(if (status == DashboardUpdatePillStatus.AVAILABLE || status == DashboardUpdatePillStatus.CHECKING) accent else textMuted)
        versionLabel?.setTextColor(textPrimary)
        setUpdateStatusLight(findViewById(R.id.updateStatusLight), statusColor)
    }

    private fun setUpdateStatusLight(light: View?, color: Int) {
        if (light == null) {
            return
        }
        light.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(UiHelper.dpToPx(this@PcView, 1f).toInt(), ColorUtils.setAlphaComponent(color, 150))
        }
    }

    private fun styleModeSegment(
        card: MaterialCardView?,
        active: Boolean,
        accent: Int,
        surface: Int,
        divider: Int,
        textPrimary: Int,
        textMuted: Int,
    ) {
        if (card == null) {
            return
        }

        val focused = card.hasFocus()
        // The current segment is marked by its accent dot and a SemiBold label (R9); only focus
        // gets the ring. Servers rested in a 2dp accent outline and read as focused.
        card.setCardBackgroundColor(ColorUtils.blendARGB(surface, textMuted, 0.05f))
        updateModeSegmentStroke(card, focused, accent, divider)
        card.setOnFocusChangeListener { _, hasFocus ->
            updateModeSegmentStroke(card, hasFocus, accent, divider)
        }

        val layout = card.getChildAt(0) as? LinearLayout ?: return
        for (index in 0 until layout.childCount) {
            val child = layout.getChildAt(index)
            if (child is TextView) {
                child.setTextColor(
                    if (index == 0) {
                        if (active) accent else textMuted
                    } else {
                        if (active) textPrimary else textMuted
                    },
                )
                if (index != 0) {
                    child.typeface = android.graphics.Typeface.create(
                        "sans-serif-medium",
                        if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                    )
                }
            }
        }
    }

    private fun updateModeSegmentStroke(card: MaterialCardView, highlighted: Boolean, accent: Int, divider: Int) {
        card.strokeColor = if (highlighted) accent else divider
        card.strokeWidth = UiHelper.dpToPx(this, if (highlighted) 3f else 1f).toInt()
    }

    private fun tintChipRow(ids: IntArray, color: Int) {
        for (id in ids) {
            findViewById<TextView>(id)?.setTextColor(color)
        }
    }


    private fun setDashboardRailCollapsed(collapsed: Boolean, persist: Boolean = true, animate: Boolean = true) {
        dashboardRailCollapsed = collapsed
        val rail = findViewById<ViewGroup>(R.id.dashboardCockpitRail) ?: return
        if (persist) {
            saveDashboardRailCollapsedPreference(collapsed)
        }
        val size = hostsControlSize ?: NovaControlSizePreferences.read(PreferenceManager.getDefaultSharedPreferences(this))
        val floor = UiHelper.dpToPx(this, 48f).toInt()
        val limit = ((resources.getDimensionPixelSize(R.dimen.nova_dashboard_rail_collapsed_width) - floor) / 2).coerceAtLeast(0)
        val startPadding = ((hostsViewMetrics?.originalPaddingStart(rail) ?: rail.paddingStart) * size.layoutScale).toInt()
        val endPadding = ((hostsViewMetrics?.originalPaddingEnd(rail) ?: rail.paddingEnd) * size.layoutScale).toInt()
        rail.setPaddingRelative(if (collapsed) startPadding.coerceAtMost(limit) else startPadding, rail.paddingTop,
            if (collapsed) endPadding.coerceAtMost(limit) else endPadding, rail.paddingBottom)
        val targetWidth = resources.getDimensionPixelSize(
            if (collapsed) R.dimen.nova_dashboard_rail_collapsed_width else R.dimen.nova_dashboard_rail_width,
        )
        // Collapsing takes the labels off before the rail narrows, and expanding puts them back once
        // it has its width: laid out in a rail still on its way, "Add Server" and "Scan Pair" wrapped
        // to two lines and were cut for a frame.
        if (collapsed) {
            applyDashboardRailLabels(rail, collapsed = true)
            animateDashboardRailWidth(rail, targetWidth, animate) {}
        } else {
            animateDashboardRailWidth(rail, targetWidth, animate) {
                if (!dashboardRailCollapsed) applyDashboardRailLabels(rail, collapsed = false)
            }
        }

        findViewById<MaterialButton>(R.id.dashboardRailToggle)?.let { toggle ->
            toggle.contentDescription = getString(if (collapsed) R.string.pcview_rail_expand else R.string.pcview_rail_collapse)
            toggle.setIconResource(if (collapsed) R.drawable.ic_menu else R.drawable.ic_menu_collapse)
            toggle.gravity = Gravity.CENTER
            toggle.iconPadding = 0
            toggle.setPadding(0, 0, 0, 0)
        }
        // A keeps focus on the toggle, whose caption had kept naming what it did before the press.
        refreshTopActionCaption()
    }

    /** The rail's labels, on or off: its tagged text, the mode and update rows, and the button labels. */
    private fun applyDashboardRailLabels(rail: ViewGroup, collapsed: Boolean) {
        dashboardRailLabelsHidden = collapsed
        setDashboardRailTaggedLabelsVisible(rail, !collapsed)
        val labelVisibility = if (collapsed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dashboardModeStatus)?.visibility = labelVisibility
        findViewById<View>(R.id.dashboardModeSelector)?.visibility = labelVisibility
        findViewById<View>(R.id.actionNovaUpdate)?.visibility = labelVisibility

        listOf(
            R.id.actionStartPolaris,
            R.id.profilesButton,
            R.id.actionTheme,
            R.id.actionGithub,
            R.id.actionSettings,
            R.id.actionAddServer,
            R.id.actionScanPair,
        ).forEach { id ->
            val button = findViewById<MaterialButton>(id) ?: return@forEach
            if (!dashboardRailButtonText.containsKey(id)) {
                dashboardRailButtonText[id] = button.text
            }
            button.text = if (collapsed) "" else dashboardRailButtonText[id]
            button.iconPadding = if (collapsed) 0 else UiHelper.dpToPx(this, 6f * (hostsControlSize?.layoutScale ?: 1f)).toInt()
            button.gravity = if (collapsed) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
            button.iconGravity = if (collapsed) MaterialButton.ICON_GRAVITY_TEXT_START else MaterialButton.ICON_GRAVITY_START
        }
        setDashboardRailSetupActionsCollapsed(collapsed)
        // A button that lost its label shows the caption now, and one that got it back hides it.
        refreshTopActionCaption()
    }

    /** Moves the rail to [targetWidth], then runs [onEnd]; a newer move cancels this one first. */
    private fun animateDashboardRailWidth(rail: ViewGroup, targetWidth: Int, animate: Boolean, onEnd: () -> Unit) {
        dashboardRailAnimator?.cancel()
        val startWidth = rail.width.takeIf { it > 0 } ?: rail.layoutParams.width
        if (!animate || startWidth <= 0 || startWidth == targetWidth) {
            rail.layoutParams = rail.layoutParams.apply { width = targetWidth }
            rail.requestLayout()
            onEnd()
            return
        }
        dashboardRailAnimator = ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = DASHBOARD_RAIL_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                rail.layoutParams = rail.layoutParams.apply { width = animator.animatedValue as Int }
                rail.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dashboardRailAnimator === animation) dashboardRailAnimator = null
                    onEnd()
                }
            })
            start()
        }
    }

    private fun setDashboardRailSetupActionsCollapsed(collapsed: Boolean) {
        val setupRow = findViewById<LinearLayout>(R.id.setupActionRow) ?: return
        val addServer = findViewById<MaterialButton>(R.id.actionAddServer)
        val scanPair = findViewById<MaterialButton>(R.id.actionScanPair)
        setupRow.orientation = if (collapsed) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val scale = hostsControlSize?.layoutScale ?: 1f
        val collapsedSpacing = UiHelper.dpToPx(this, 6f * scale).toInt()
        val compactHeight = UiHelper.dpToPx(this, 48f * scale.coerceAtLeast(1f)).toInt()

        addServer?.let { button ->
            val addParams = button.layoutParams as? LinearLayout.LayoutParams ?: return@let
            addParams.width = if (collapsed) LinearLayout.LayoutParams.MATCH_PARENT else 0
            addParams.height = dashboardSetupActionHeight(collapsed, compactHeight)
            addParams.weight = if (collapsed) 0f else 1f
            addParams.marginStart = 0
            addParams.topMargin = 0
            button.layoutParams = addParams
        }
        scanPair?.let { button ->
            val scanParams = button.layoutParams as? LinearLayout.LayoutParams ?: return@let
            scanParams.width = if (collapsed) LinearLayout.LayoutParams.MATCH_PARENT else 0
            scanParams.height = dashboardSetupActionHeight(collapsed, compactHeight)
            scanParams.weight = if (collapsed) 0f else 1f
            scanParams.marginStart = if (collapsed) 0 else collapsedSpacing
            scanParams.topMargin = if (collapsed) collapsedSpacing else 0
            button.layoutParams = scanParams
        }
    }

    private fun loadDashboardRailCollapsedPreference(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_DASHBOARD_RAIL_COLLAPSED, false)

    private fun saveDashboardRailCollapsedPreference(collapsed: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(PREF_DASHBOARD_RAIL_COLLAPSED, collapsed)
            .apply()
    }

    private fun setDashboardRailTaggedLabelsVisible(root: ViewGroup, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.tag == "dashboardRailLabel") {
                child.visibility = visibility
            }
            if (child is ViewGroup) {
                setDashboardRailTaggedLabelsVisible(child, visible)
            }
        }
    }

    private fun bindTopActionFocusLabel(label: TextView?, vararg actions: Pair<View?, Int>) {
        label ?: return
        fun caption(view: View, hasFocus: Boolean, labelRes: Int) {
            // Only a button that shows no label of its own gets the caption, and it says what
            // the button does now: a collapsed rail's toggle said "Collapse rail", Sleep Host
            // said Wake Host, and an expanded rail repeated the labels on its buttons.
            val ownLabelShown = !(view as? TextView)?.text.isNullOrBlank()
            if (hasFocus && !ownLabelShown) {
                label.text = view.contentDescription?.takeIf { it.isNotBlank() }
                    ?: dashboardRailButtonText[view.id]?.takeIf { it.isNotBlank() }
                    ?: getString(labelRes)
                label.visibility = View.VISIBLE
            } else if (hasFocus || actions.none { it.first?.hasFocus() == true }) {
                label.visibility = View.INVISIBLE
            }
        }
        for ((action, labelRes) in actions) {
            action?.setOnFocusChangeListener { view, hasFocus ->
                caption(view, hasFocus, labelRes)
                if (view.id == R.id.actionNovaUpdate) {
                    updateDashboardUpdatePill()
                }
            }
        }
        // Focus that stays put while its button changes, as A on the rail toggle does, gets no
        // focus change: the rail asks for the caption again instead.
        refreshTopActionCaption = {
            actions.firstOrNull { it.first?.hasFocus() == true }?.let { (view, labelRes) ->
                if (view != null) caption(view, hasFocus = true, labelRes = labelRes)
            }
        }
    }

    /** The theme picker, in a right-edge panel opened from the dashboard's theme action. */
    private fun showThemePicker(anchor: View?) {
        novaSurfaces.open(
            novaThemePickerPage(this, buildThemePickerThemes(), NovaThemeManager.getTheme(this), onChoose = ::applyThemeSelection),
            NovaEdge.End,
            anchor?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None,
        )
        anchor?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }

    private fun buildThemePickerThemes(): List<String> {
        // The set of themes and their order live in R.array.nova_theme_values, which the
        // Compose settings screen and the legacy preference screen already read. This kept a
        // second copy in Kotlin, so a theme added to the array would not have appeared here
        // and nothing would have said so -- the order below happened to match, which is
        // exactly how a duplicate survives long enough to drift.
        return resources.getStringArray(R.array.nova_theme_values)
            .filter { theme ->
                theme != NovaThemeManager.THEME_MATERIAL_YOU ||
                    NovaThemeManager.isMaterialYouAvailable()
            }
    }

    private fun applyThemeSelection(theme: String) {
        if (theme == NovaThemeManager.getTheme(this)) {
            return
        }
        NovaThemeManager.setTheme(this, theme)
        // The new theme is its own confirmation; a floating Toast broke R6. The recreate keeps
        // whether the library was already opened, so it does not throw the player into it (the
        // RP6) or at an offline host (the Shield), and focus comes back to Theme.
        returnFocusToTheme = true
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
        styleModeSegment(findViewById(R.id.modeServers), true, accent, surface, divider, textPrimary, textMuted)
        styleModeSegment(findViewById(R.id.modeLibrary), false, accent, surface, divider, textPrimary, textMuted)
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
                    val emptyAction = findViewById<View>(R.id.emptyRefresh)
                    if (noPcFoundLayout?.visibility == View.VISIBLE && emptyAction?.isShown == true) {
                        emptyAction.requestFocus()
                    } else {
                        scheduleServerRowFocus(view)
                    }
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
        setFocusable(R.id.dashboardRailToggle, focusable)
        setFocusable(R.id.profilesButton, focusable)
        setFocusable(R.id.actionNovaUpdate, focusable)
        setFocusable(R.id.actionStartPolaris, focusable)
        setFocusable(R.id.actionTheme, focusable)
        setFocusable(R.id.actionGithub, focusable)
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
        val surface = novaHostPlaySurface(
            runningGame = computer.runningGameId != 0,
            ownedByThisDevice = computer.currentGameOwnedByClient,
            library = computer.libraryState,
            watchable = computer.currentGameWatchable,
        )
        when (surface) {
            NovaHostPlaySurface.RESUME, NovaHostPlaySurface.WATCH -> resumeOrWatchRunningGame(computer)
            NovaHostPlaySurface.LIBRARY -> doNovaLibrary(computer)
            NovaHostPlaySurface.CHECK_LIBRARY -> {
                val computerObject = findComputerObject(computer.uuid)
                if (computerObject != null) {
                    maybeProbeLibraryReadiness(computerObject)
                }
                NovaSnackbar.show(this, getString(R.string.pcview_library_checking))
            }
            NovaHostPlaySurface.APP_LIST -> doAppList(computer, false, false)
        }
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

        val libraryStatus = when {
            libraryReadyCount <= 0 -> getString(R.string.pcview_destination_library_meta_empty)
            libraryReadyCount == 1 -> getString(R.string.pcview_destination_library_meta_one)
            else -> getString(R.string.pcview_destination_library_meta_many, libraryReadyCount)
        }
        findViewById<TextView>(R.id.dashboardModeStatus)?.text =
            if (totalCount <= 0) {
                getString(R.string.pcview_dashboard_mode_status_empty)
            } else {
                getString(R.string.pcview_dashboard_mode_status_format, onlineCount, totalCount, libraryStatus)
            }    }

    private fun updateEmptyState() {
        if (noPcFoundLayout == null || !::viewModel.isInitialized || !::pcGridAdapter.isInitialized) {
            return
        }

        val emptyTitle = findViewById<TextView>(R.id.pcViewEmptyTitle)
        val emptyHint = findViewById<TextView>(R.id.pcViewEmptyHint)
        val computers = viewModel.computersLiveData.value
        // The spinner is for a search in progress only. A filter with nothing in it is an answer,
        // and the spinner had turned forever under "Needs Pairing".
        findViewById<View>(R.id.pcs_loading)?.visibility =
            if ((computers == null || computers.isEmpty()) && runningPolling) View.VISIBLE else View.GONE
        // Down from the filter chips reaches the empty state's first action, not the grey box
        // around the chips.
        // Once rows are listed the first row takes over again (setServerFilterNextFocusDown).
        val emptyShowing = computers.isNullOrEmpty() || pcGridAdapter.itemCount == 0
        if (emptyShowing) {
            for (filterId in SERVER_FILTER_IDS) setNextFocusDown(filterId, R.id.emptyRefresh)
        }

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
            showHostsNotice(getString(R.string.pcview_quick_library), getString(R.string.pcview_library_no_server))
            return
        }
        doNovaLibrary(selected.details)
    }

    private fun launchPolarisStartupForPreferredHost() {
        val selected = selectPreferredPolarisStartupComputer(
            if (::viewModel.isInitialized) viewModel.computersLiveData.value else null,
        )
        if (selected == null) {
            showHostsNotice(getString(R.string.pcview_quick_start_polaris), getString(R.string.pcview_polaris_start_no_server))
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
            if (details.wakeMacAddress != null && firstWakeable == null) {
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
        // A recreate (a theme applied, a configuration change) is the same visit: the library was
        // opened once already, or the player chose to stay here.
        portraitMenuExpanded = savedInstanceState?.getBoolean(STATE_PORTRAIT_MENU, false) ?: false
        savedInstanceState?.let { saved ->
            val actionId = saved.getInt(STATE_HOSTS_FOCUS_ID, View.NO_ID)
            val uuid = saved.getString(STATE_HOSTS_FOCUS_UUID)
            if (actionId != View.NO_ID || uuid != null) pendingHostsFocus = HostsFocus(actionId, uuid, saved.getBoolean(STATE_HOSTS_FOCUS_MANAGE))
        }
        onBackPressedDispatcher.addCallback(this, portraitMenuBack)
        autoNavigated = savedInstanceState?.getBoolean(STATE_AUTO_NAVIGATED, false) ?: false
        val focusTheme = savedInstanceState?.getBoolean(STATE_FOCUS_THEME, false) ?: false

        UiHelper.setLocale(this)
        inForeground = true

        // Beside the GL renderer probe, which asks the same kind of question: something about this
        // device that only the driver can answer and that never changes until the driver does. Off
        // the main thread because making a Vulkan device costs about a tenth of a second, and the
        // answer is wanted long before anyone presses Play, not during the frame that starts the app.
        Thread { PyroWave.probe(applicationContext) }.start()

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
        if (focusTheme) {
            window.decorView.post { findViewById<View>(R.id.actionTheme)?.requestFocus() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val focus = captureHostsFocus() ?: pendingHostsFocus
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AUTO_NAVIGATED, autoNavigated)
        outState.putBoolean(STATE_FOCUS_THEME, returnFocusToTheme)
        outState.putBoolean(STATE_PORTRAIT_MENU, portraitMenuExpanded)
        focus?.let { focus ->
            outState.putInt(STATE_HOSTS_FOCUS_ID, focus.actionId)
            outState.putString(STATE_HOSTS_FOCUS_UUID, focus.uuid)
            outState.putBoolean(STATE_HOSTS_FOCUS_MANAGE, focus.manage)
        }
    }

    private fun completeOnCreate() {
        completeOnCreateCalled = true

        val showingWelcome = NovaWelcomeActivity.shouldShow(this)
        if (showingWelcome) {
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
                refreshHostPowerState()
                if (newList != null) {
                    maybeRunPendingQrPairing(newList)
                    checkAutoNavigation(newList)
                }
            }
        }

        initializeViews(prefs)
        handleWelcomeAction(intent.getStringExtra(NovaWelcomeActivity.EXTRA_WELCOME_ACTION))
        if (!showingWelcome) {
            window.decorView.post { maybeRunAutomaticNovaUpdateCheck() }
        }
    }


    private fun checkNovaUpdateFromDashboard() {
        val updateButton = findViewById<View>(R.id.actionNovaUpdate)
        updateButton?.isEnabled = false
        updateDashboardUpdatePill(DashboardUpdatePillStatus.CHECKING)

        runtimeTasks.launchIo("NovaDashboardUpdateCheck") {
            val result = runCatching { NovaUpdateChecker.checkLatest() }
            runtimeTasks.runOnMainIfActive {
                updateButton?.isEnabled = true
                result.onSuccess { updateResult ->
                    when (updateResult) {
                        is NovaUpdateCheckResult.UpdateAvailable -> updateDashboardUpdatePill(DashboardUpdatePillStatus.AVAILABLE, updateResult.release)
                        is NovaUpdateCheckResult.UpToDate -> updateDashboardUpdatePill(DashboardUpdatePillStatus.CURRENT, updateResult.release)
                    }
                    showNovaUpdateDashboardResult(updateResult)
                }.onFailure { error ->
                    updateDashboardUpdatePill(DashboardUpdatePillStatus.ERROR)
                    showNovaUpdateDashboardError(error)
                }
            }
        }
    }

    private fun showNovaUpdateDashboardResult(result: NovaUpdateCheckResult) {
        when (result) {
            is NovaUpdateCheckResult.UpdateAvailable -> showNovaUpdateDashboardAvailable(result.release)
            is NovaUpdateCheckResult.UpToDate -> showNovaUpdateDashboardCurrent(result.release)
        }
    }

    private fun showNovaUpdateDashboardAvailable(release: NovaUpdateRelease) {
        val message = if (release.apkAssetName != null) {
            getString(
                R.string.nova_update_available_message_with_apk,
                release.versionName,
                NovaUpdateChecker.currentVersionLabel(),
                release.apkAssetName
            )
        } else {
            getString(
                R.string.nova_update_available_message,
                release.versionName,
                NovaUpdateChecker.currentVersionLabel()
            )
        }
        // A Notice has one action beside its primary and Close: the release notes, where the
        // primary is the APK. Without an APK the primary already opens the release page.
        val releaseNotes = release.apkDownloadUrl?.let {
            NovaAction(getString(R.string.nova_update_release_notes)) { HelpLauncher.launchUrl(this, release.releaseUrl) }
        }
        novaSurfaces.present(
            NovaCommonPage.Notice(
                key = UPDATE_NOTICE_KEY,
                title = getString(R.string.nova_update_available_title),
                message = message,
                primary = novaUpdatePrimaryAction(release),
                help = releaseNotes,
                closeLabel = getString(R.string.nova_panel_cancel),
            ),
        )
    }

    /** Download APK when the release has one for this device, otherwise Open Release. */
    private fun novaUpdatePrimaryAction(release: NovaUpdateRelease): NovaAction =
        if (release.apkDownloadUrl != null) {
            NovaAction(getString(R.string.nova_update_download_apk)) { startNovaUpdateInstall(release) }
        } else {
            NovaAction(getString(R.string.nova_update_open_release)) { HelpLauncher.launchUrl(this, release.releaseUrl) }
        }

    private fun showNovaUpdateDashboardCurrent(release: NovaUpdateRelease) {
        // The pill read Current before the check and Current after it, so a check that found
        // nothing newer said nothing at all. It says so in place, on the pill that was pressed.
        val current = BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.updateStatusLabel)?.text = getString(R.string.pcview_update_status_up_to_date)
        findViewById<TextView>(R.id.updateVersionLabel)?.text = getString(R.string.pcview_update_pill_latest_version, current)
        findViewById<View>(R.id.actionNovaUpdate)?.let { pill ->
            pill.contentDescription = getString(R.string.pcview_update_pill_content_latest, current)
            pill.announceForAccessibility(pill.contentDescription)
        }
    }

    /** The pill already says Retry where the check was asked for; a snackbar said it again (X2). */
    private fun showNovaUpdateDashboardError(error: Throwable) {
        LimeLog.warning("Nova dashboard: manual update check failed: ${error.message}")
    }

    private fun maybeRunAutomaticNovaUpdateCheck() {
        if (BuildConfig.FDROID_BUILD || automaticUpdatePromptShown) {
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val nowMs = System.currentTimeMillis()
        if (!NovaUpdatePromptPreferences.shouldRunAutomaticCheck(prefs, nowMs)) {
            return
        }

        runtimeTasks.launchIo("NovaAutomaticUpdateCheck") {
            val result = runCatching { NovaUpdateChecker.checkLatest() }
            NovaUpdatePromptPreferences.recordAutomaticCheckResult(prefs, nowMs, result)
            result.onSuccess { updateResult ->
                if (updateResult !is NovaUpdateCheckResult.UpdateAvailable) {
                    return@onSuccess
                }
                val release = updateResult.release
                if (!NovaUpdatePromptPreferences.shouldShowAutomaticPrompt(prefs, release)) {
                    return@onSuccess
                }
                runtimeTasks.runOnMainIfActive {
                    if (!inForeground || isFinishing || automaticUpdatePromptShown) {
                        return@runOnMainIfActive
                    }
                    updateDashboardUpdatePill(DashboardUpdatePillStatus.AVAILABLE, release)
                    automaticUpdatePromptShown = true
                    showNovaAutomaticUpdatePrompt(release)
                }
            }.onFailure { error ->
                LimeLog.warning("Nova dashboard: automatic update check failed: ${error.message}")
            }
        }
    }

    private fun showNovaAutomaticUpdatePrompt(release: NovaUpdateRelease) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val message = release.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
            getString(
                R.string.nova_update_popup_message_with_notes,
                release.versionName,
                NovaUpdateChecker.currentVersionLabel(),
                notes.trim()
            )
        } ?: getString(
            R.string.nova_update_popup_message,
            release.versionName,
            NovaUpdateChecker.currentVersionLabel()
        )
        novaSurfaces.present(
            NovaCommonPage.Notice(
                key = UPDATE_NOTICE_KEY,
                title = getString(R.string.nova_update_available_title),
                message = message,
                primary = novaUpdatePrimaryAction(release),
                // The one action beside Download and Later.
                help = NovaAction(getString(R.string.nova_update_skip_version)) {
                    NovaUpdatePromptPreferences.skipRelease(prefs, release)
                    // The result on a page in the right edge panel, not a Toast.
                    showHostsNotice(getString(R.string.nova_update_skip_version), getString(R.string.nova_update_skipped_toast))
                },
                closeLabel = getString(R.string.nova_update_later),
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
        runtimeTasks.launchMain("NovaUpdateInstall") {
            try {
                val result = NovaUpdateInstaller.downloadValidateAndInstall(this@PcView, release) { progress ->
                    spinner.setMessage(
                        if (progress >= 100) {
                            getString(R.string.nova_update_verifying_message)
                        } else {
                            getString(R.string.nova_update_downloading_message, release.versionName, progress)
                        }
                    )
                }
                NovaUpdateInstaller.showInstallResult(
                    this@PcView,
                    release,
                    result,
                    onRetry = { startNovaUpdateInstall(it) },
                    onViewReleases = {
                        HelpLauncher.launchUrl(this@PcView, "https://github.com/papi-ux/nova/releases")
                    },
                )
            } finally {
                NovaUpdateInstaller.dismissIfAlive(this@PcView) { spinner.dismiss() }
            }
        }
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
        serverGridView?.adapter = null
        serverGridView = null
        // The pending sleep's Keep Awake is a page on this screen's panel. Once
        // the screen is gone there is nothing left to stop it with, so a
        // request nobody can call off must not still be waiting to fire.
        cancelPendingHostSleep()
        hostSleepHoldTicker?.let { hostSleepHandler.removeCallbacks(it) }
        hostSleepHoldTicker = null
        super.onDestroy()

        runtimeTasks.cancelAll()
        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
    }

    private fun recreateForThemeChangeIfNeeded(): Boolean {
        val currentTheme = NovaThemeManager.getTheme(this)
        if (appliedTheme == currentTheme) return false
        appliedTheme = currentTheme
        recreate()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (recreateForThemeChangeIfNeeded()) return
        if (completeOnCreateCalled) applyHostsControlSize()

        UiHelper.showCrashReportDialog(this)
        UiHelper.showDecoderCrashDialog(this)
        refreshProfileButton()

        inForeground = true
        spaceParticleView?.resume()
        startComputerUpdates()
        if (hostSleepCancelledByLeaving) {
            hostSleepCancelledByLeaving = false
            showSleepNotice(getString(R.string.pcview_sleep_cancelled_on_leave))
        }
    }

    override fun onPause() {
        super.onPause()

        inForeground = false
        spaceParticleView?.pause()
        stopComputerUpdates(false)
        // The pending sleep's Keep Awake is a page on this screen. A player who
        // has left the screen can no longer reach it, so leaving calls the sleep
        // off, and coming back says so.
        findViewById<View>(R.id.actionStartPolaris)?.let { cancelHostSleepHold(it) }
        if (cancelPendingHostSleep()) {
            takeDownSleepCountdown()
            hostSleepCancelledByLeaving = true
        }
    }

    override fun onStop() {
        super.onStop()

        Dialog.closeDialogs()
    }

    /**
     * A host's menu, as the first page of a right-edge panel: who the host is and how it is, the
     * one thing it is most likely opened for, then the rest, two to a line on a landscape handheld
     * and one column elsewhere. Polling waits while the panel is open, so the card it returns
     * focus to is still the card it came from.
     */
    private fun showHostPanel(computer: ComputerObject) {
        stopComputerUpdates(false)
        val details = computer.details
        val surfaces = novaSurfaces
        val actions = object : NovaHostMenuActions {
            override fun wake() = startPolarisFromNova(details)
            override fun sendWakeOnLan() = doWakeOnLan(details)
            override fun pair() = doPair(details, null, null)
            override fun otpPairPage(): NovaPage = buildOtpPairPage(details)
            override fun scanQr() = launchQrScanner()
            override fun hostConsolePage(): NovaPage = hostConsolePageFor(computer)
            override fun openLibrary() = doNovaLibrary(details)
            override fun checkLibrary() = maybeProbeLibraryReadiness(computer)
            override fun resume() = resumeOrWatchRunningGame(details)
            // Its split is the confirm, so the request goes out without a countdown after it.
            override fun sleep() = sleepHostNow()
            override fun appList() = doAppList(details, false, false)
            override fun testNetwork() = ServerHelper.doNetworkTest(this@PcView)
            override fun editWakeAddress() = showWakeAddressDialog(details)
            override fun delete() = removeComputer(details)

            override fun watch() {
                val binder = managerBinder ?: return
                ServerHelper.doWatch(this@PcView, createWatchTargetApp(details), details, binder)
            }

            override fun endSession() {
                val binder = managerBinder ?: return
                val runningApp = NvApp()
                runningApp.appId = details.runningGameId
                ServerHelper.doQuit(this@PcView, details, runningApp, binder, null)
            }
        }
        val sleepOffered = details.uuid == preferredHostPowerComputer()?.uuid && currentHostPowerAction() == HostPowerAction.SLEEP
        val menu = NovaCommonPage.Menu(
            key = "host",
            title = getString(R.string.hosts_panel_host_title),
            items = novaHostMenuItems(
                context = this,
                details = details,
                needsPairing = needsPairing(details),
                sleepOffered = sleepOffered,
                actions = actions,
                closePanel = surfaces.panel::close,
                leave = ::leaveHostPanel,
            ),
            header = novaHostMenuHeader(this, details),
            width = NovaPanelWidth.Grid,
        )
        surfaces.open(menu, NovaEdge.End, currentFocus?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None) { page ->
            // The only page of its own the host menu pushes: the host's console (N6).
            if (page is NovaHostConsolePage) NovaHostConsole(page)
        }
        hostPanelWatch?.cancel()
        hostPanelWatch = lifecycleScope.launch {
            // Closed without an action: B, the scrim, Start or a drag.
            snapshotFlow { surfaces.panel.isOpen }.first { !it }
            startComputerUpdates()
        }
    }

    /**
     * The host menu is left for one of its actions: polling resumes before the action runs, so an
     * action that pauses it again, as pairing does, pauses it last.
     */
    private fun leaveHostPanel() {
        hostPanelWatch?.cancel()
        hostPanelWatch = null
        startComputerUpdates()
    }

    /**
     * The host's console, a page pushed in the host's menu (N6). A browser met the host's
     * self-signed certificate and asked the player to click through its warning; the page trusts
     * only the certificate Nova paired with. With no address to open, the page says so instead.
     * The links it opens for a client app are followed here ([followHostConsoleLink]).
     */
    internal fun hostConsolePageFor(computer: ComputerObject): NovaPage {
        val title = getString(R.string.nova_host_console_title)
        val url = computer.guessManagementUrl()
            ?: return NovaCommonPage.Notice(
                key = NovaHostConsolePage.KEY,
                title = title,
                message = getString(R.string.pcview_error_no_management_url),
                closeLabel = getString(R.string.nova_panel_close),
            )
        return NovaHostConsolePage(
            title = title,
            url = url,
            pinnedCertificate = runCatching { computer.details.serverCert?.encoded }.getOrNull(),
            hostUuid = computer.details.uuid,
            onLink = { link -> followHostConsoleLink(computer.details, link) },
        )
    }

    /**
     * A link the console of [details] opened for a client app (N6), and what to say in the console
     * instead, or null once it is followed. Pair Now's address pairs as the host's QR code does:
     * the host's menu closes as it does for any pairing, and the address goes where a scanned code
     * goes, so the PIN and passphrase the console made are the ones Nova pairs with. A host Nova is
     * paired with already has nothing to pair, as a scanned code finds. A launch link starts the
     * app through the launch any art:// launch link takes.
     */
    private fun followHostConsoleLink(details: ComputerDetails, link: NovaHostConsoleLink): String? = when (link) {
        is NovaHostConsoleLink.Pair -> if (details.pairState == PairState.PAIRED && hasPinnedServerCert(details)) {
            getString(R.string.nova_host_console_link_paired)
        } else {
            novaSurfaces.panel.close()
            leaveHostPanel()
            handleQrScanResult(link.address)
            null
        }
        is NovaHostConsoleLink.Launch -> {
            novaSurfaces.panel.close()
            leaveHostPanel()
            startActivity(link.intent(this))
            null
        }
        NovaHostConsoleLink.Elsewhere -> getString(R.string.nova_host_console_link_elsewhere)
    }

    /**
     * A result or a reason to read, on a Notice page in the right edge panel: the Toasts that stood
     * in for these floated over the Hosts screen and were gone before they could be read. Any thread.
     */
    private fun showHostsNotice(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        Dialog.displayDialog(this, title, message, false)
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
            showHostsNotice(getString(R.string.hosts_not_ready_title), getString(R.string.error_manager_not_running))
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

    private fun hasCamera(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun launchQrScanner() {
        if (!hasCamera()) {
            showScanPairCameraPage(denied = false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }
        startQrScanner()
    }

    private fun showScanPairCameraPage(denied: Boolean) {
        val surfaces = novaSurfaces
        surfaces.show(
            novaScanPairCameraPage(
                context = this,
                key = SCAN_PAIR_CAMERA_PAGE,
                denied = denied,
                retry = { launchQrScanner() },
                addServer = { startActivity(Intent(this, AddComputerManually::class.java)) },
                takeDown = { surfaces.dismiss(SCAN_PAIR_CAMERA_PAGE) },
            ),
        )
    }

    private fun startQrScanner() {
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
            showHostsNotice(getString(R.string.hosts_qr_title), getString(R.string.nova_qr_invalid_code))
            return
        }

        val pin = uri.getQueryParameter("pin")
        val passphrase = uri.getQueryParameter("passphrase")
        val host = uri.host
        val port = if (uri.port != -1) uri.port else NvHTTP.DEFAULT_HTTP_PORT

        if (pin == null || passphrase == null || host == null) {
            showHostsNotice(getString(R.string.hosts_qr_title), getString(R.string.nova_qr_missing_pairing_data))
            return
        }

        val binder = managerBinder
        if (binder == null) {
            showHostsNotice(getString(R.string.hosts_not_ready_title), getString(R.string.error_manager_not_running))
            return
        }

        pendingPairingPin = pin
        pendingPairingPassphrase = passphrase
        pendingPairingAddress = ComputerDetails.AddressTuple(host, port)

        showPairingProgress(getString(R.string.hosts_qr_connecting, host))

        Thread {
            val details = ComputerDetails()
            details.manualAddress = ComputerDetails.AddressTuple(host, port)
            val added = try {
                binder.addComputerBlocking(details)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!added) {
                // Nothing will come to pair with, so the wait goes and says why, where it stood.
                hidePairingPage()
                runOnUiThread {
                    clearPendingPairing()
                    showHostsNotice(getString(R.string.hosts_pairing_failed_title), getString(R.string.hosts_qr_unreachable, host))
                }
            }
        }.start()
    }

    private fun doPair(computer: ComputerDetails, otp: String?, passphrase: String?) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showHostsNotice(getString(R.string.hosts_pairing_failed_title), getString(R.string.pair_pc_offline))
            return
        }
        val binder = managerBinder
        if (binder == null) {
            showHostsNotice(getString(R.string.hosts_pairing_failed_title), getString(R.string.error_manager_not_running))
            return
        }

        showPairingProgress(getString(R.string.hosts_pairing_asking))
        Thread {
            var pinNote: String? = null
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
                val isNewPairing = existingPairState != PairState.PAIRED && !hasPinnedServerCert(computer)
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
                                pairedComputer = applyPairedCertificate(computer, pm, isNewPairing)
                                if (pairedComputer == null) {
                                    message = resources.getString(R.string.pair_fail)
                                    success = false
                                }

                                hidePairingPage()
                                val launchedComputer = pairedComputer
                                runOnUiThread {
                                    if (launchedComputer != null) {
                                        // The host's library or apps opening is the answer.
                                        openBestPlaySurface(launchedComputer)
                                    } else {
                                        showHostsNotice(getString(R.string.hosts_pairing_failed_title), getString(R.string.pair_fail))
                                        startComputerUpdates()
                                    }
                                }
                                return@Thread
                            }
                            LimeLog.info("TOFU: Auto-pair failed, falling back to PIN pairing")
                            pinNote = getString(R.string.nova_pairing_auto_fallback_pin)
                            serverInfo = httpConn.getServerInfo(true)
                        } else {
                            LimeLog.info("TOFU: Server does not advertise TofuEnabled, rebuild Polaris to enable")
                            pinNote = getString(R.string.nova_pairing_auto_unsupported)
                        }
                    }

                    val pinStr = otp ?: PairingManager.generatePinString()
                    if (passphrase == null) {
                        // Why it came to a PIN is said on the PIN page, not in a snackbar under it.
                        showPairingCode(pinStr, pinNote)
                    } else {
                        showOtpPairingWait()
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
                            pairedComputer = applyPairedCertificate(computer, pm, isNewPairing)
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

            hidePairingPage()

            val failure = message
            val toastSuccess = success
            val launchedComputer = pairedComputer
            runOnUiThread {
                if (failure != null) {
                    Dialog.displayDialog(
                        this,
                        getString(R.string.hosts_pairing_failed_title),
                        failure,
                        false,
                        actionText = null,
                        action = null,
                        help = true,
                    )
                }

                if (toastSuccess && launchedComputer != null) {
                    openBestPlaySurface(launchedComputer)
                } else {
                    startComputerUpdates()
                }
            }
        }.start()
    }

    private fun applyPairedCertificate(computer: ComputerDetails, pm: PairingManager, isNewPairing: Boolean): ComputerDetails? {
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
        if (isNewPairing) {
            PolarisProfileSync.initializeAutoSyncForNewPairing(this, computer.uuid)
        }
        binder.invalidateStateForComputer(computer.uuid)

        return managedComputer ?: computer
    }

    /**
     * The pairing PIN, full screen over the Hosts screen while the host waits for it. Close hides
     * the page and pairing goes on; the page also goes when pairing ends, however it ends.
     * Any thread.
     */
    private fun showPairingCode(pin: String, note: String? = null) {
        val surfaces = novaSurfaces
        surfaces.show(novaPairingCodePage(this, PAIRING_PAGE_KEY, pin, note) { surfaces.dismiss(PAIRING_PAGE_KEY) })
    }

    /** Pairing on its way with no PIN to show yet, in the pairing page's place. Any thread. */
    private fun showPairingProgress(message: String) {
        val surfaces = novaSurfaces
        surfaces.show(novaPairingProgressPage(this, PAIRING_PAGE_KEY, message) { surfaces.dismiss(PAIRING_PAGE_KEY) })
    }

    /**
     * OTP pairing has nothing to type on the host, so it waits instead of showing a code. Close
     * hides the wait as it hides the code. Any thread.
     */
    private fun showOtpPairingWait() {
        val surfaces = novaSurfaces
        surfaces.show(novaOtpPairingWaitPage(this, PAIRING_PAGE_KEY) { surfaces.dismiss(PAIRING_PAGE_KEY) })
    }

    /** Takes the pairing page down, where pairing used to close its dialogs. Any thread. */
    private fun hidePairingPage() {
        NovaSurfaces.existing(this)?.dismiss(PAIRING_PAGE_KEY)
    }

    /** OTP pairing, pushed in the host panel: a good pair closes the panel and pairs. */
    private fun buildOtpPairPage(computer: ComputerDetails): NovaPage = novaOtpPairPage(this) { pin, passphrase ->
        novaSurfaces.panel.close()
        leaveHostPanel()
        doPair(computer, pin, passphrase)
    }

    /** Wake on LAN from the host menu; what came of it is a Notice, where it can be read (X2). */
    private fun showWakeAddressDialog(computer: ComputerDetails) {
        com.papi.nova.ui.showWakeMacAddressEditor(this, computer) { value, complete ->
            val binder = managerBinder
            if (binder == null) {
                complete(false)
            } else {
                runtimeTasks.launchIo("NovaWakeAddress") {
                    val saved = try {
                        binder.setWakeMacAddress(computer.uuid, value)
                    } catch (error: RuntimeException) {
                        LimeLog.warning("Could not save wake address (${error.javaClass.simpleName})")
                        false
                    }
                    runtimeTasks.runOnMainIfActive {
                        if (saved) computer.manualWakeMacAddress = WakeOnLanSender.usableMacAddress(value)
                        complete(saved)
                    }
                }
            }
        }
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        val title = getString(R.string.pcview_quick_start_polaris)
        if (computer.state == ComputerDetails.State.ONLINE) {
            showHostsNotice(title, getString(R.string.wol_pc_online))
            return
        }

        if (computer.wakeMacAddress == null) {
            showHostsNotice(title, getString(R.string.wol_no_mac))
            return
        }

        Thread {
            val message =
                try {
                    WakeOnLanSender.sendWolPacket(computer)
                    getString(R.string.wol_waking_msg)
                } catch (e: IOException) {
                    getString(R.string.wol_fail)
                }

            showHostsNotice(title, message)
        }.start()
    }

    private fun startPolarisFromNova(computer: ComputerDetails) {
        val binder = managerBinder
        if (binder == null) {
            showHostsNotice(getString(R.string.hosts_not_ready_title), getString(R.string.error_manager_not_running))
            return
        }
        if (needsPairing(computer)) {
            showHostsNotice(getString(R.string.pcview_quick_start_polaris), getString(R.string.pcview_polaris_start_pair_first))
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
                },
                reachabilityProbe = TcpHostReachabilityProbe()
            )
            val result = coordinator.start(computer)
            runtimeTasks.runOnMainIfActive {
                polarisStartupInFlight.remove(uuid)
                handlePolarisStartupResult(result)
            }
        }
    }

    /**
     * The dashboard's power control.
     *
     * Wake stays an ordinary tap, it is harmless. Sleep is a press and hold:
     * the two share this button in the same spot, so muscle memory on the way
     * into the library would eventually take the host down, and nothing about
     * a stray tap can complete a hold.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHostPowerAction(button: View?) {
        if (button == null) {
            return
        }
        button.setOnClickListener {
            if (hostSleepHoldConsumed) {
                // The hold already fired; this is the click that follows it.
                hostSleepHoldConsumed = false
                return@setOnClickListener
            }
            if (hostSleepSequence.isBusy) {
                // Locked on Sleeping... until the host answers.
                return@setOnClickListener
            }
            if (currentHostPowerAction() == HostPowerAction.SLEEP) {
                val hint = if (isTouchExplorationEnabled()) {
                    R.string.pcview_sleep_hold_hint_accessibility
                } else {
                    R.string.pcview_sleep_hold_hint
                }
                showSleepNotice(getString(hint))
            } else if (preferredHostIsReachable()) {
                // Awake, and sleep is not on offer: waking it again would do nothing, so the
                // press says why the control cannot do what it is named for.
                showSleepNotice(hostSleepRefusal())
            } else {
                launchPolarisStartupForPreferredHost()
            }
        }
        // Holding Wake Host, the way Sleep Host is held, says why the host is
        // not offering sleep. Nothing else on the dashboard does, and a player
        // who knows the host can sleep will look for it here.
        button.setOnLongClickListener {
            if (hostSleepSequence.isBusy || currentHostPowerAction() == HostPowerAction.SLEEP) {
                return@setOnLongClickListener false
            }
            if (!preferredHostIsReachable()) {
                return@setOnLongClickListener false
            }
            showSleepNotice(hostSleepRefusal())
            true
        }
        button.setOnTouchListener { view, event ->
            if (hostSleepSequence.isBusy || currentHostPowerAction() != HostPowerAction.SLEEP) {
                cancelHostSleepHold(view)
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startHostSleepHold(view)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHostSleepHold(view)
            }
            // Never consume: the click listener still owns the tap, which is
            // what shows the hint instead of silently doing nothing.
            false
        }
        // A controller's A arrives here as the center press the screen's key gate makes of it,
        // once: the gate consumes the A itself, so Android never adds a fallback press to count.
        button.setOnKeyListener { view, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                keyCode != KeyEvent.KEYCODE_ENTER
            ) {
                return@setOnKeyListener false
            }
            if (hostSleepSequence.isBusy || currentHostPowerAction() != HostPowerAction.SLEEP) {
                cancelHostSleepHold(view)
                return@setOnKeyListener false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) startHostSleepHold(view)
                KeyEvent.ACTION_UP -> cancelHostSleepHold(view)
            }
            false
        }
        widenHostPowerTouchTarget(button)
        updateHostPowerAction()
    }

    /**
     * Sleep is held, and a thumb that drifts off a small target ends the hold.
     * The landscape rail draws its pills 34dp tall; this counts touches up to
     * 48dp around the button as the button, without changing how it looks.
     */
    private fun widenHostPowerTouchTarget(button: View) {
        val parent = button.parent as? View ?: return
        val minimum = resources.getDimensionPixelSize(R.dimen.nova_min_touch_target)
        button.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val bounds = Rect()
            view.getHitRect(bounds)
            val growX = ((minimum - bounds.width() + 1) / 2).coerceAtLeast(0)
            val growY = ((minimum - bounds.height() + 1) / 2).coerceAtLeast(0)
            if (growX == 0 && growY == 0) {
                if (parent.touchDelegate?.let { it is HostPowerTouchDelegate } == true) {
                    parent.touchDelegate = null
                }
                return@addOnLayoutChangeListener
            }
            bounds.inset(-growX, -growY)
            parent.touchDelegate = HostPowerTouchDelegate(bounds, view)
        }
    }

    private class HostPowerTouchDelegate(bounds: Rect, delegate: View) : TouchDelegate(bounds, delegate)

    private fun isTouchExplorationEnabled(): Boolean =
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

    /** What holding Wake Host says, or null when there is nothing to explain. */
    private fun hostSleepUnavailableMessage(): String? {
        val details = preferredHostPowerComputer() ?: return null
        val reason = HostPowerPolicy.unavailableReason(
            reachable = details.state == ComputerDetails.State.ONLINE,
            capabilities = details.uuid?.let { hostPowerCapabilities[it] },
        ) ?: return null
        return when (reason) {
            HostSleepUnavailable.TurnedOff -> getString(R.string.pcview_sleep_unavailable_off)
            HostSleepUnavailable.WatchOnly -> getString(R.string.pcview_sleep_unavailable_watch_only)
            is HostSleepUnavailable.HostCannot -> if (reason.message.isBlank()) {
                getString(R.string.pcview_sleep_unavailable_host)
            } else {
                getString(R.string.pcview_sleep_unavailable_host_says, reason.message)
            }
        }
    }

    private fun currentHostPowerAction(): HostPowerAction {
        val details = preferredHostPowerComputer() ?: return HostPowerAction.WAKE
        return HostPowerPolicy.resolve(
            reachable = details.state == ComputerDetails.State.ONLINE,
            capabilities = hostPowerCapabilities[details.uuid],
        )
    }

    private fun preferredHostIsReachable(): Boolean =
        preferredHostPowerComputer()?.state == ComputerDetails.State.ONLINE

    /** Why the awake host under the control cannot be put to sleep from here, in a sentence. */
    private fun hostSleepRefusal(): String =
        hostSleepUnavailableMessage() ?: getString(R.string.pcview_sleep_unavailable_not_offered)

    private fun preferredHostPowerComputer(): ComputerDetails? {
        if (!::viewModel.isInitialized) {
            return null
        }
        return selectPreferredPolarisStartupComputer(viewModel.computersLiveData.value)?.details
    }

    private fun updateHostPowerAction() {
        val button = findViewById<View>(R.id.actionStartPolaris) ?: return
        val action = currentHostPowerAction()
        // A request counting down or out holds the button on Sleeping... until
        // the host answers, even as the host drops off the network and would
        // otherwise turn the button back into Wake Host mid-check.
        val busy = hostSleepSequence.isBusy
        // Named for what the machine is doing: one that answers is awake, and Wake Host on an
        // awake machine offered nothing. What a hold may do is still the host's answer above.
        val named = HostPowerPolicy.label(preferredHostIsReachable())
        val label = when {
            busy -> R.string.pcview_sleep_in_progress
            named == HostPowerAction.SLEEP -> R.string.pcview_quick_sleep_host
            else -> R.string.pcview_quick_start_polaris
        }
        // The icon follows the label, as an open and closed pair rather than two
        // unrelated glyphs: a play arrow next to Sleep Host read as though the
        // button started something, and one icon cannot carry both states.
        val icon = if (busy || named == HostPowerAction.SLEEP) {
            R.drawable.ic_eye_closed
        } else {
            R.drawable.ic_eye_open
        }
        // A collapsed rail shows icons only and puts each label back on the way
        // out, so the label goes where it will be put back from.
        val text = getString(label)
        dashboardRailButtonText[R.id.actionStartPolaris] = text
        (button as? MaterialButton)?.let {
            it.text = if (dashboardRailLabelsHidden) "" else text
            it.setIconResource(icon)
        }
        button.contentDescription = text
        updateHostSleepAccessibilityAction(button, offer = !busy && action == HostPowerAction.SLEEP)
        if (busy || action != HostPowerAction.SLEEP) {
            cancelHostSleepHold(button)
            // The click that follows a completed hold still has to be spent.
            if (!busy) {
                hostSleepHoldConsumed = false
            }
        }
    }

    /**
     * TalkBack cannot perform a timed hold, so Sleep Host is also a named
     * action in its actions menu. It goes through the same countdown with
     * Keep Awake as the hold does.
     */
    private fun updateHostSleepAccessibilityAction(button: View, offer: Boolean) {
        // The dashboard refreshes often; only an actual change is worth an
        // accessibility event.
        if (offer == (hostSleepAccessibilityActionId != View.NO_ID)) {
            return
        }
        if (hostSleepAccessibilityActionId != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(button, hostSleepAccessibilityActionId)
            hostSleepAccessibilityActionId = View.NO_ID
        }
        if (offer) {
            hostSleepAccessibilityActionId = ViewCompat.addAccessibilityAction(
                button,
                getString(R.string.pcview_quick_sleep_host),
            ) { _, _ ->
                beginHostSleep()
                true
            }
        }
    }

    /**
     * Ask the preferred host what it can do, at most once a minute. The answer
     * is what decides whether the button offers sleep, so a host Nova has not
     * heard from keeps the wake button rather than a guess.
     */
    private fun refreshHostPowerState() {
        val details = preferredHostPowerComputer()
        if (details == null) {
            updateHostPowerAction()
            return
        }
        val uuid = details.uuid
        if (uuid == null || details.state != ComputerDetails.State.ONLINE) {
            updateHostPowerAction()
            return
        }
        val probedAt = hostPowerProbedAtMs[uuid] ?: 0L
        val now = SystemClock.elapsedRealtime()
        if (probedAt != 0L && now - probedAt < HoldToConfirm.POWER_PROBE_INTERVAL_MILLIS) {
            updateHostPowerAction()
            return
        }
        if (!hostPowerProbeInFlight.add(uuid)) {
            return
        }
        runtimeTasks.launchIo("NovaHostPower") {
            val known = hostPowerCapabilities[uuid]
            // Whether the host speaks host power at all only changes when it
            // restarts, so once Nova has that, later passes ask the small
            // endpoint instead of pulling the whole capabilities payload.
            val capabilities = if (known != null) {
                hostPowerFor(details)?.let { known.copy(hostPower = it) }
            } else {
                polarisCapabilitiesFor(details)
            }
            runtimeTasks.runOnMainIfActive {
                hostPowerProbeInFlight.remove(uuid)
                hostPowerProbedAtMs[uuid] = SystemClock.elapsedRealtime()
                if (capabilities == null) {
                    hostPowerCapabilities.remove(uuid)
                } else {
                    hostPowerCapabilities[uuid] = capabilities
                }
                updateHostPowerAction()
            }
        }
    }

    private fun hostPowerFor(computer: ComputerDetails): PolarisCapabilities.HostPower? {
        val activeAddress = computer.activeAddress ?: return null
        val serverCert = computer.serverCert ?: return null
        val httpsPort = if (computer.httpsPort > 0) computer.httpsPort else 47984
        return try {
            PolarisApiClient(this@PcView, activeAddress.address, httpsPort, serverCert).getHostPower()
        } catch (e: Exception) {
            LimeLog.warning("Nova: host power refresh failed for ${computer.name}: " + e.message)
            null
        }
    }

    private fun polarisCapabilitiesFor(computer: ComputerDetails): PolarisCapabilities? {
        val activeAddress = computer.activeAddress ?: return null
        val serverCert = computer.serverCert ?: return null
        val httpsPort = if (computer.httpsPort > 0) computer.httpsPort else 47984
        return try {
            PolarisApiClient(this@PcView, activeAddress.address, httpsPort, serverCert).getCapabilities()
        } catch (e: Exception) {
            LimeLog.warning("Nova: host power probe failed for ${computer.name}: " + e.message)
            null
        }
    }

    private fun startHostSleepHold(button: View) {
        cancelHostSleepHold(button)
        // A finger that slid off after the hold completed leaves the flag set
        // with no click behind it to spend it. A new press is the end of that.
        hostSleepHoldConsumed = false
        hostSleepHold.press(SystemClock.uptimeMillis())
        val ticker = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                setHostSleepHoldProgress(button, hostSleepHold.progress(now))
                if (hostSleepHold.isComplete(now)) {
                    hostSleepHold.cancel()
                    setHostSleepHoldProgress(button, 0f)
                    hostSleepHoldTicker = null
                    hostSleepHoldConsumed = true
                    beginHostSleep()
                    return
                }
                hostSleepHandler.postDelayed(this, HoldToConfirm.HOLD_TICK_MILLIS)
            }
        }
        hostSleepHoldTicker = ticker
        hostSleepHandler.post(ticker)
    }

    private fun cancelHostSleepHold(button: View) {
        hostSleepHoldTicker?.let { hostSleepHandler.removeCallbacks(it) }
        hostSleepHoldTicker = null
        hostSleepHold.cancel()
        setHostSleepHoldProgress(button, 0f)
    }

    /**
     * The fill behind the label while a hold is in progress, so the press shows
     * how far along it is. A foreground drawable needs API 23; below that the
     * hold still works and dims instead.
     */
    private fun setHostSleepHoldProgress(button: View, progress: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            button.alpha = if (progress > 0f) 0.7f else 1f
            return
        }
        if (progress <= 0f) {
            // Only clear what this put there. A style is free to own the
            // button's foreground and must get it back untouched.
            if (button.foreground === hostSleepHoldFill) {
                button.foreground = null
            }
            hostSleepHoldFill = null
            return
        }
        val fill = hostSleepHoldFill?.takeIf { button.foreground === it } ?: ClipDrawable(
            ColorDrawable(
                ColorUtils.setAlphaComponent(NovaThemeManager.getAccentColor(this), 0x66)
            ),
            Gravity.START,
            ClipDrawable.HORIZONTAL
        ).also {
            button.foreground = it
            hostSleepHoldFill = it
        }
        fill.level = (progress * 10000f).toInt().coerceIn(0, 10000)
    }

    /**
     * The hold is done, so the request is coming. It does not go out yet: once
     * the host is down there is nothing on the couch that can wake it, so the
     * undo has to sit in front of the request rather than after it.
     *
     * The undo is a Notice in the right edge panel with Keep Awake focused. A or
     * B there, or the panel closing any other way, calls the sleep off, and the
     * page goes quietly when the grace runs out. It was a snackbar with a timer
     * that floated over the rail (M12).
     */
    private fun beginHostSleep() {
        val details = preferredHostPowerComputer()
        if (details == null) {
            showSleepNotice(getString(R.string.pcview_polaris_start_no_server))
            return
        }
        // One request at a time: a second hold, or the TalkBack action, while
        // one is counting down or out does nothing.
        if (!hostSleepSequence.startCountdown()) {
            return
        }
        updateHostPowerAction()
        novaSurfaces.present(
            NovaCommonPage.Notice(
                key = SLEEP_COUNTDOWN_PAGE,
                title = getString(R.string.pcview_quick_sleep_host),
                message = getString(R.string.pcview_sleep_pending),
                closeLabel = getString(R.string.pcview_sleep_keep_awake),
                onClose = { cancelPendingHostSleep() },
            ),
        )
        val pending = Runnable {
            hostSleepPending = null
            if (!hostSleepSequence.countdownElapsed()) {
                return@Runnable
            }
            takeDownSleepCountdown()
            requestHostSleep(details)
        }
        hostSleepPending = pending
        hostSleepHandler.postDelayed(pending, HoldToConfirm.SLEEP_GRACE_MILLIS)
    }

    /**
     * Sleep Host from the host menu. Its split was the confirm, A, Right and A
     * with Keep Awake focused, so the request goes out now, with no countdown
     * after it (M12).
     */
    private fun sleepHostNow() {
        val details = preferredHostPowerComputer()
        if (details == null) {
            showSleepNotice(getString(R.string.pcview_polaris_start_no_server))
            return
        }
        if (!hostSleepSequence.startRequest()) {
            return
        }
        updateHostPowerAction()
        requestHostSleep(details)
    }

    /** Takes the countdown's page down quietly: when the grace runs out, Keep Awake is not the answer. */
    private fun takeDownSleepCountdown() {
        novaSurfaces.panel.removeWhere { it.key == SLEEP_COUNTDOWN_PAGE }
    }

    /** What Sleep Host came to, or why it cannot, on a Notice in the right edge panel. */
    private fun showSleepNotice(message: String) {
        showHostsNotice(getString(R.string.pcview_quick_sleep_host), message)
    }

    /** @return true when a countdown was running and is now stopped. */
    private fun cancelPendingHostSleep(): Boolean {
        hostSleepPending?.let { hostSleepHandler.removeCallbacks(it) }
        hostSleepPending = null
        val stopped = hostSleepSequence.cancelCountdown()
        if (stopped) {
            updateHostPowerAction()
        }
        return stopped
    }

    private fun requestHostSleep(computer: ComputerDetails) {
        val activeAddress = computer.activeAddress
        val serverCert = computer.serverCert
        if (activeAddress == null || serverCert == null) {
            hostSleepSequence.finish()
            updateHostPowerAction()
            showSleepNotice(getString(R.string.pcview_sleep_failed))
            return
        }
        val httpsPort = if (computer.httpsPort > 0) computer.httpsPort else 47984
        runtimeTasks.launchIo("NovaHostSleep") {
            val result = try {
                PolarisApiClient(this@PcView, activeAddress.address, httpsPort, serverCert).sleepHost()
            } catch (e: Exception) {
                LimeLog.warning("Nova: host sleep failed for ${computer.name}: " + e.message)
                PolarisHostSleepResult(accepted = false)
            }
            // Accepted is not asleep. Only say the host is going to sleep once
            // it has actually stopped answering, and when it has not, ask the
            // host why rather than leaving a false promise on screen.
            val wentDown = if (result.accepted) awaitHostAsleep(computer) else false
            val stillAwakeReason = if (result.accepted && !wentDown) {
                hostPowerFor(computer)?.lastSleepMessage.orEmpty()
            } else {
                ""
            }
            runtimeTasks.runOnMainIfActive {
                computer.uuid?.let {
                    hostPowerCapabilities.remove(it)
                    hostPowerProbedAtMs.remove(it)
                }
                hostSleepSequence.finish()
                updateHostPowerAction()
                // On a Notice, where it can be read: each of these floated as a snackbar (M12).
                if (result.accepted && !wentDown) {
                    showSleepNotice(stillAwakeReason.ifBlank { getString(R.string.pcview_sleep_did_not_sleep) })
                } else if (result.accepted) {
                    showSleepNotice(getString(R.string.pcview_sleep_requested))
                } else {
                    // The host knows whether this was polkit, a running stream
                    // or a setting nobody turned on. Prefer its sentence.
                    showSleepNotice(result.message.ifBlank { getString(R.string.pcview_sleep_failed) })
                }
            }
        }
    }

    /**
     * @return true once the host has stopped answering, false if it is still up
     *   when the attempts run out. The host accepting a sleep request is not the
     *   same as the host sleeping: a task that will not freeze aborts a suspend
     *   after logind has agreed to it, and then the machine is still running.
     */
    private fun awaitHostAsleep(computer: ComputerDetails): Boolean {
        val probe = TcpHostReachabilityProbe()
        repeat(HoldToConfirm.SLEEP_CONFIRM_ATTEMPTS) {
            if (!probe.isAwake(computer)) {
                return true
            }
            try {
                Thread.sleep(HoldToConfirm.SLEEP_CONFIRM_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
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

    /**
     * What Wake Host came to. Ready opens the library, which is the answer; anything else is said
     * on a Notice, where it can be read: each floated as a snackbar and was gone in seconds (X2).
     */
    private fun handlePolarisStartupResult(result: com.papi.nova.manager.PolarisStartupResult) {
        val failure = when (result.status) {
            PolarisStartupStatus.READY -> {
                val computer = updatePolarisStartupComputer(result.computer)
                if (computer != null) {
                    doNovaLibrary(computer)
                    return
                }
                R.string.pcview_polaris_start_failed
            }
            PolarisStartupStatus.NEEDS_PAIRING -> R.string.pcview_polaris_start_pair_first
            PolarisStartupStatus.MISSING_MAC -> R.string.wol_no_mac
            PolarisStartupStatus.WAKE_FAILED -> R.string.wol_fail
            PolarisStartupStatus.TIMEOUT -> R.string.pcview_polaris_start_timeout
            PolarisStartupStatus.POLARIS_UNAVAILABLE -> R.string.pcview_polaris_start_unavailable
            PolarisStartupStatus.POLARIS_NOT_RUNNING -> R.string.pcview_polaris_start_not_running
        }
        showHostsNotice(getString(R.string.pcview_quick_start_polaris), getString(failure))
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
            showHostsNotice(getString(R.string.hosts_offline_title), getString(R.string.hosts_offline_message))
            return
        }
        if (managerBinder == null) {
            showHostsNotice(getString(R.string.hosts_not_ready_title), getString(R.string.error_manager_not_running))
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
            showHostsNotice(getString(R.string.hosts_offline_title), getString(R.string.hosts_offline_message))
            return
        }
        val binder = managerBinder
        if (binder == null) {
            showHostsNotice(getString(R.string.hosts_not_ready_title), getString(R.string.error_manager_not_running))
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
        // The card already knows whether this device has Spaces here; the library polls only when it does.
        intent.putExtra(NovaLibraryActivity.EXTRA_SPACES_AVAILABLE, computer.spacesAvailable)
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
        val appContext = applicationContext
        val deleteTitle = getString(R.string.pcview_menu_delete_pc)
        val failureMessage = getString(R.string.nova_server_remove_failed)
        val deletedShortcutReason = getString(R.string.scut_deleted_pc)
        val removalViewModel = if (::viewModel.isInitialized) viewModel else null
        val removalShortcutHelper = shortcutHelper

        lateinit var removalConnection: ServiceConnection
        removalConnection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as? ComputerManagerService.ComputerManagerBinder
                if (binder == null) {
                    runCatching { appContext.unbindService(removalConnection) }
                    showHostsNotice(deleteTitle, failureMessage)
                    return
                }

                serverRemovalScope.launch {
                    try {
                        // Asked first: the pinned certificate that proves to the host who is
                        // asking goes away with the PC.
                        val forgotten = HostForget.ask(details, binder.uniqueId, appContext)

                        val removed = runCatching {
                            binder.removeComputer(details)
                        }.onFailure { error ->
                            LimeLog.warning("Nova: Remove server request failed (${error.javaClass.simpleName})")
                        }.getOrDefault(false)

                        if (!removed) {
                            // The host stays in the list, and the page says why. With the screen
                            // gone there is nowhere to say it, and nothing floats over another app.
                            withContext(Dispatchers.Main.immediate) {
                                showHostsNotice(deleteTitle, failureMessage)
                            }
                            return@launch
                        }

                        runCatching {
                            DiskAssetLoader(appContext).deleteAssetsForComputer(details.uuid)
                        }.onFailure { error ->
                            LimeLog.warning("Nova: Server asset cleanup failed (${error.javaClass.simpleName})")
                        }

                        appContext.getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                            .edit()
                            .remove(details.uuid)
                            .apply()
                        removalViewModel?.removeComputer(details.uuid)

                        withContext(Dispatchers.Main.immediate) {
                            runCatching {
                                removalShortcutHelper.disableComputerShortcut(details, deletedShortcutReason)
                            }.onFailure { error ->
                                LimeLog.warning("Nova: Shortcut cleanup failed (${error.javaClass.simpleName})")
                            }
                            if (!isFinishing && !isDestroyed) {
                                syncComputerList()
                            }
                            HostForget.stillListedMessage(forgotten)?.let { message ->
                                val text = appContext.getString(message, details.name)
                                // This is the only word the player gets about the host, so it stays on
                                // a page until it is closed; a Toast was cut at two lines and a snackbar
                                // timed out. With the screen gone, the log keeps it.
                                if (!isFinishing && !isDestroyed) {
                                    showHostsNotice(deleteTitle, text)
                                } else {
                                    LimeLog.info("Nova: $text")
                                }
                            }
                        }
                    } finally {
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { appContext.unbindService(removalConnection) }
                        }
                    }
                }
            }

            override fun onServiceDisconnected(className: ComponentName) = Unit
        }

        if (!appContext.bindService(
                Intent(appContext, ComputerManagerService::class.java),
                removalConnection,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            showHostsNotice(deleteTitle, failureMessage)
        }
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
            if (serverGridView !== rv) {
                serverGridView?.adapter = null
                serverGridView = rv
            }
            rv.itemAnimator = null
            if (rv.layoutManager !is NovaServerGridLayoutManager) {
                rv.layoutManager = NovaServerGridLayoutManager(this)
            }
            if (rv.adapter !== pcGridAdapter) {
                rv.adapter = pcGridAdapter
            }
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
                    showHostPanel(computer)
                } else if (needsPairing(computer.details)) {
                    showHostPanel(computer)
                } else {
                    openBestPlaySurface(computer.details)
                }
            }
            pcGridAdapter.setOnServerActionListener { computer ->
                showHostPanel(computer)
            }
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

    override fun releaseAbsListView(gridView: View) {
        if (gridView is RecyclerView && serverGridView === gridView) {
            gridView.adapter = null
            gridView.layoutManager = null
            serverGridView = null
        }
    }

    companion object {
        private val serverRemovalScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
                LimeLog.warning("Nova: Server removal task failed (${error.javaClass.simpleName})")
            },
        )
        private const val SCAN_PAIR_CAMERA_PAGE = "scan-pair-camera"
        private const val SLEEP_COUNTDOWN_PAGE = "host-sleep-countdown"
        private const val STATE_AUTO_NAVIGATED = "nova.pcview.autoNavigated"
        private const val STATE_FOCUS_THEME = "nova.pcview.focusTheme"
        private const val STATE_PORTRAIT_MENU = "nova.pcview.portraitMenu"
        private const val STATE_HOSTS_FOCUS_ID = "nova.pcview.focusAction"
        private const val STATE_HOSTS_FOCUS_UUID = "nova.pcview.focusHost"
        private const val STATE_HOSTS_FOCUS_MANAGE = "nova.pcview.focusManage"
        private val SERVER_FILTER_IDS = intArrayOf(
            R.id.filterAllServers,
            R.id.filterOnlineServers,
            R.id.filterStreamingServers,
            R.id.filterNeedsPairingServers,
        )
        private const val FILTER_ALL = 0
        private const val FILTER_ONLINE = 1
        private const val FILTER_STREAMING = 2
        private const val FILTER_NEEDS_PAIRING = 3
        private const val PREF_LAST_LIBRARY_PC_UUID = "nova_last_library_pc_uuid"
        private const val PREF_DASHBOARD_RAIL_COLLAPSED = "nova_dashboard_rail_collapsed"
        private const val DASHBOARD_RAIL_ANIMATION_MS = 160L
        private const val UPDATE_NOTICE_KEY = "nova-update"
        private const val PAIRING_PAGE_KEY = "nova-pairing"
    }
}
