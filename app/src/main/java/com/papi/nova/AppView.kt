package com.papi.nova

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.grid.AppGridAdapter
import com.papi.nova.grid.RecyclerItemClickListener
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.runtime.NovaRuntimeTasks
import com.papi.nova.ui.AdapterFragment
import com.papi.nova.ui.AdapterFragmentCallbacks
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaMenuHeader
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaTone
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.novaSurfaces
import com.papi.nova.ui.panel.setNovaSplitConfirm
import com.papi.nova.utils.CacheHelper
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper
import java.io.IOException
import java.io.StringReader
import java.util.Locale
import org.xmlpull.v1.XmlPullParserException

/** What each row of an app's menu does. The screen answers them; the menu decides which it offers. */
internal interface NovaAppMenuActions {
    fun start(withVirtualDisplay: Boolean)
    fun confirmVirtualDisplayThenStart()
    fun watch()
    fun resume()
    fun endSession()
    fun quitAndStart()
    fun setHidden(hidden: Boolean)
    fun setPinned(pinned: Boolean)
    fun exportLauncher()
}

/**
 * The rows of [app]'s menu: one column of what can be done with it. Ending the running game, or
 * quitting it to start this one, splits in its own row and closes the panel through [closePanel]
 * once confirmed. Hidden and Pin to Top are switches that change in place. A virtual display that
 * is not ready asks first, on a Confirm page pushed in the same panel.
 */
internal fun novaAppMenuItems(
    context: Context,
    app: NvApp,
    runningAppId: Int,
    ownedByOtherClient: Boolean,
    useVirtualDisplay: Boolean,
    virtualDisplayReady: Boolean,
    hidden: Boolean,
    pinned: Boolean,
    actions: NovaAppMenuActions,
    closePanel: () -> Unit = {},
): List<NovaMenuItem> {
    val items = mutableListOf<NovaMenuItem>()
    fun action(key: String, label: Int, run: () -> Unit) {
        items += NovaMenuItem.Action(key = key, label = context.getString(label), onClick = run)
    }
    // A confirmed split leaves the panel first, so what it starts is not started under it.
    fun split(key: String, label: Int, confirmLabel: Int, consequence: Int, run: () -> Unit) {
        items += NovaMenuItem.Destructive(
            key = key,
            label = context.getString(label),
            confirmLabel = context.getString(confirmLabel),
            consequence = context.getString(consequence),
        ) {
            closePanel()
            run()
        }
    }

    if (runningAppId == 0) {
        if (useVirtualDisplay) {
            action("start_host_display", R.string.applist_menu_start_primarydisplay) { actions.start(withVirtualDisplay = false) }
        } else if (virtualDisplayReady) {
            action("start_virtual_display", R.string.applist_menu_start_vdisplay) { actions.start(withVirtualDisplay = true) }
        } else {
            items += NovaMenuItem.Action(
                key = "start_virtual_display",
                label = context.getString(R.string.applist_menu_start_vdisplay),
                closesPanel = false,
                onClick = actions::confirmVirtualDisplayThenStart,
            )
        }
    } else if (runningAppId == app.appId) {
        if (ownedByOtherClient) {
            action("watch", R.string.applist_menu_watch, actions::watch)
        } else {
            action("resume", R.string.applist_menu_resume, actions::resume)
            split(
                key = "end_session",
                label = R.string.applist_menu_quit,
                confirmLabel = R.string.game_dialog_action_end_session,
                consequence = R.string.nova_panel_end_session_message,
                run = actions::endSession,
            )
        }
    } else if (ownedByOtherClient) {
        action("watch_active", R.string.applist_menu_watch_active, actions::watch)
    } else {
        split(
            key = "quit_and_start",
            label = R.string.applist_menu_quit_and_start,
            confirmLabel = R.string.hosts_quit_and_start_confirm,
            consequence = R.string.hosts_quit_and_start_consequence,
            run = actions::quitAndStart,
        )
    }

    val offOn = listOf(
        NovaOption(false, context.getString(R.string.hosts_value_off)),
        NovaOption(true, context.getString(R.string.hosts_value_on)),
    )
    // The running game cannot be hidden, though a hidden one can still be shown again.
    if (runningAppId != app.appId || hidden) {
        items += NovaMenuItem.Value(
            key = "hidden",
            label = context.getString(R.string.applist_menu_hide_app),
            options = offOn,
            current = hidden,
            onChange = actions::setHidden,
            style = NovaValueStyle.Switch,
        )
    }
    items += NovaMenuItem.Value(
        key = "pinned",
        label = context.getString(R.string.hosts_app_pin_to_top),
        options = offOn,
        current = pinned,
        onChange = actions::setPinned,
        style = NovaValueStyle.Switch,
    )
    items += NovaMenuItem.Opens(key = "details", label = context.getString(R.string.applist_menu_details)) {
        NovaCommonPage.Notice(
            key = "details",
            title = context.getString(R.string.title_details),
            message = app.toString(),
            closeLabel = context.getString(R.string.nova_panel_close),
            monospace = true,
        )
    }
    action("export_launcher", R.string.applist_menu_export_launcher, actions::exportLauncher)
    return items
}

/** The app above its menu: its name, and whether the host is running it and for whom. */
internal fun novaAppMenuHeader(context: Context, app: NvApp, runningAppId: Int, ownedByOtherClient: Boolean): NovaMenuHeader {
    val running = runningAppId == app.appId
    return NovaMenuHeader(
        title = app.appName,
        status = when {
            running && ownedByOtherClient -> context.getString(R.string.hosts_app_status_watchable)
            running -> context.getString(R.string.hosts_app_status_running)
            else -> null
        },
        tone = if (running) NovaTone.Active else NovaTone.Neutral,
    )
}

class AppView : NovaActivity(), AdapterFragmentCallbacks {
    private var appGridAdapter: AppGridAdapter? = null
    private var uuidString: String? = null
    private lateinit var shortcutHelper: ShortcutHelper
    private var computer: ComputerDetails? = null
    private var poller: ComputerManagerService.ApplistPoller? = null
    private var blockingLoadSpinner: SpinnerDialog? = null
    private var lastRawAppList: String? = null
    private var lastRunningAppId = 0
    private var suspendGridUpdates = false
    private var inForeground = false
    private val runtimeTasks = NovaRuntimeTasks(this, "Nova app list")
    private var showHiddenApps = false
    private val hiddenAppIds = HashSet<Int>()
    private val polarisMetadataLock = Object()
    private var polarisGamesByUuid: Map<String, PolarisGame> = HashMap()
    private var polarisGamesByAppId: Map<Int, PolarisGame> = HashMap()
    private var polarisMetadataRefreshInFlight = false
    private var prefConfig: PreferenceConfiguration? = null
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var appliedTheme: String? = null

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val localBinder = binder as ComputerManagerService.ComputerManagerBinder
                Thread {
                    localBinder.waitForReady()

                    val uuid = uuidString
                    if (uuid == null) {
                        showAppListError(getString(R.string.applist_error_invalid_host))
                        return@Thread
                    }

                    val loadedComputer = localBinder.getComputer(uuid)
                    if (loadedComputer == null) {
                        showAppListError(getString(R.string.applist_error_host_missing))
                        return@Thread
                    }
                    computer = loadedComputer

                    shortcutHelper.createAppViewShortcut(
                        loadedComputer,
                        true,
                        intent.getBooleanExtra(NEW_PAIR_EXTRA, false),
                    )
                    shortcutHelper.reportComputerShortcutUsed(loadedComputer)

                    try {
                        appGridAdapter =
                            AppGridAdapter(
                                this@AppView,
                                PreferenceConfiguration.readPreferences(this@AppView),
                                loadedComputer,
                                localBinder.uniqueId,
                                showHiddenApps,
                            )
                    } catch (e: Exception) {
                        LimeLog.warning(Log.getStackTraceString(e))
                        finish()
                        return@Thread
                    }

                    appGridAdapter?.updateHiddenApps(hiddenAppIds, true)

                    val pinnedIds = HashSet<Int>()
                    val pinnedStrings =
                        getSharedPreferences("nova_prefs", MODE_PRIVATE)
                            .getStringSet("pinned_$uuidString", HashSet()) ?: HashSet()
                    for (s in pinnedStrings) {
                        pinnedIds.add(Integer.parseInt(s))
                    }
                    appGridAdapter?.updatePinnedApps(pinnedIds)

                    managerBinder = localBinder
                    populateAppGridWithCache()
                    startComputerUpdates()

                    runOnUiThread {
                        if (isFinishing || isChangingConfigurations) {
                            return@runOnUiThread
                        }

                        try {
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.appFragmentContainer, AdapterFragment())
                                .commitAllowingStateLoss()
                        } catch (e: IllegalStateException) {
                            LimeLog.warning(Log.getStackTraceString(e))
                        }
                    }
                }.start()
            }

            override fun onServiceDisconnected(className: ComponentName) {
                managerBinder = null
            }
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        prefConfig = PreferenceConfiguration.readPreferences(this)
        val adapter = appGridAdapter
        val prefs = prefConfig
        if (adapter != null && prefs != null) {
            adapter.updateLayoutWithPreferences(this, prefs)

            try {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.appFragmentContainer, AdapterFragment())
                    .commitAllowingStateLoss()
            } catch (e: IllegalStateException) {
                LimeLog.warning(Log.getStackTraceString(e))
            }
        }
    }

    private fun startComputerUpdates() {
        val binder = managerBinder ?: return
        val activeComputer = computer ?: return
        if (!inForeground) {
            return
        }

        binder.startPolling(
            object : ComputerManagerListener {
                override fun notifyComputerUpdated(details: ComputerDetails) {
                    if (suspendGridUpdates) {
                        return
                    }
                    if (!details.uuid.equals(uuidString, ignoreCase = true)) {
                        return
                    }

                    if (details.state == ComputerDetails.State.OFFLINE) {
                        runOnUiThread {
                            Toast.makeText(this@AppView, R.string.lost_connection, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        return
                    }

                    if (details.state == ComputerDetails.State.ONLINE &&
                        details.pairState != PairingManager.PairState.PAIRED
                    ) {
                        runOnUiThread {
                            shortcutHelper.disableComputerShortcut(details, resources.getString(R.string.scut_not_paired))
                            Toast.makeText(this@AppView, R.string.scut_not_paired, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        return
                    }

                    if (details.appListLoadError != null &&
                        (appGridAdapter?.getTotalAppCount() ?: 0) == 0
                    ) {
                        activeComputer.update(details)
                        showAppListError(details.appListLoadError ?: getString(R.string.applist_error_message))
                        return
                    }

                    if (details.rawAppList == null || details.rawAppList == lastRawAppList) {
                        activeComputer.update(details)
                        if (details.runningGameId != lastRunningAppId) {
                            lastRunningAppId = details.runningGameId
                            updateUiWithServerInfo(details)
                        }
                        return
                    }

                    activeComputer.update(details)
                    lastRunningAppId = details.runningGameId
                    lastRawAppList = details.rawAppList

                    try {
                        clearAppListError()
                        updateUiWithAppList(NvHTTP.getAppListByReader(StringReader(details.rawAppList)))
                        updateUiWithServerInfo(details)

                        runOnUiThread { dismissBlockingLoadSpinner() }
                    } catch (e: XmlPullParserException) {
                        handleAppListLoadFailure(e)
                    } catch (e: IOException) {
                        handleAppListLoadFailure(e)
                    } catch (e: RuntimeException) {
                        handleAppListLoadFailure(e)
                    }
                }
            },
        )

        if (poller == null) {
            poller = binder.createAppListPoller(activeComputer)
        }
        poller?.start()
    }

    private fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        appGridAdapter?.cancelQueuedOperations()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        appliedTheme = NovaThemeManager.getTheme(this)
        super.onCreate(savedInstanceState)

        inForeground = true
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)
        setContentView(R.layout.activity_app_view)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.appSwipeRefresh)
        if (swipeRefresh != null) {
            if (UiHelper.isTvDevice(this)) {
                swipeRefresh.isEnabled = false
            }
            swipeRefresh.setColorSchemeColors(NovaThemeManager.getAccentColor(this))
            swipeRefresh.setProgressBackgroundColorSchemeColor(
                NovaThemeManager.getCardBackgroundColor(this),
            )
            swipeRefresh.setOnRefreshListener {
                poller?.pollNow()
                swipeRefresh.postDelayed({ swipeRefresh.isRefreshing = false }, 2000)
            }
        }

        UiHelper.notifyNewRootView(this)

        val header = findViewById<View>(R.id.appListHeader)
        if (header != null) {
            header.setOnApplyWindowInsetsListener { v, insets ->
                val topInset =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                    } else {
                        insets.systemWindowInsetTop
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

        findViewById<View>(R.id.profilesButton)
            .setOnClickListener { startActivity(Intent(this, ProfilesActivity::class.java)) }
        findViewById<View>(R.id.appListRetryButton)
            ?.setOnClickListener { retryAppListLoad() }

        showHiddenApps = intent.getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false)
        uuidString = intent.getStringExtra(UUID_EXTRA)

        val hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        val hiddenAppStrings = hiddenAppsPrefs.getStringSet(uuidString, HashSet()) ?: HashSet()
        for (hiddenAppIdStr in hiddenAppStrings) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr))
        }

        val computerName = intent.getStringExtra(NAME_EXTRA)
        val label = findViewById<TextView>(R.id.appListText)
        title = computerName
        label.text = computerName

        val searchBar = findViewById<android.widget.EditText>(R.id.app_search)
        searchBar?.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    appGridAdapter?.filterByName(s.toString())
                }
            },
        )

        prefConfig = PreferenceConfiguration.readPreferences(this)

        bindService(Intent(this, ComputerManagerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)
    }

    private fun updateHiddenApps() {
        val hiddenAppIdStringSet = HashSet<String>()
        for (hiddenAppId in hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString())
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit()
            .putStringSet(uuidString, hiddenAppIdStringSet)
            .apply()

        appGridAdapter?.updateHiddenApps(hiddenAppIds, false)
    }

    private fun populateAppGridWithCache() {
        try {
            lastRawAppList =
                CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString),
                )
            val appList = NvHTTP.getAppListByReader(StringReader(lastRawAppList))
            updateUiWithAppList(appList)
            LimeLog.info("Loaded applist from cache")
        } catch (e: IOException) {
            handleCachedAppListLoadFailure(e)
        } catch (e: XmlPullParserException) {
            handleCachedAppListLoadFailure(e)
        } catch (e: RuntimeException) {
            handleCachedAppListLoadFailure(e)
        }
    }

    private fun handleCachedAppListLoadFailure(e: Exception) {
        if (lastRawAppList != null) {
            LimeLog.warning("Saved applist corrupted: $lastRawAppList")
            LimeLog.warning(Log.getStackTraceString(e))
        }
        LimeLog.info("Loading applist from the network")
        loadAppsBlocking()
    }

    private fun loadAppsBlocking() {
        blockingLoadSpinner =
            SpinnerDialog.displayDialog(
                this,
                resources.getString(R.string.applist_refresh_title),
                resources.getString(R.string.applist_refresh_msg),
                true,
            )
    }

    private fun dismissBlockingLoadSpinner() {
        if (blockingLoadSpinner != null) {
            blockingLoadSpinner?.dismiss()
            blockingLoadSpinner = null
        }
    }

    private fun handleAppListLoadFailure(e: Exception) {
        LimeLog.warning(Log.getStackTraceString(e))
        val detail = e.message ?: e.javaClass.simpleName
        showAppListError(detail)
    }

    private fun showAppListError(message: String) {
        runOnUiThread {
            dismissBlockingLoadSpinner()
            val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.appSwipeRefresh)
            swipeRefresh?.isRefreshing = false

            val errorCard = findViewById<View>(R.id.appListErrorCard) ?: return@runOnUiThread
            val detailView = findViewById<TextView>(R.id.appListErrorDetail)
            val retryButton = findViewById<View>(R.id.appListRetryButton)

            detailView?.text = getString(R.string.applist_error_detail_format, message)
            detailView?.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            retryButton?.requestFocus()
            errorCard.visibility = View.VISIBLE
        }
    }

    private fun clearAppListError() {
        runOnUiThread {
            findViewById<View>(R.id.appListErrorCard)?.visibility = View.GONE
        }
    }

    private fun retryAppListLoad() {
        clearAppListError()
        loadAppsBlocking()
        poller?.pollNow()
        if (poller == null) {
            startComputerUpdates()
        }
    }

    override fun finish() {
        super.finish()
        NovaThemeManager.applyBackTransition(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        runtimeTasks.cancelAll()
        SpinnerDialog.closeDialogs(this)
        Dialog.closeDialogs()

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

        UiHelper.showDecoderCrashDialog(this)

        inForeground = true
        startComputerUpdates()

        val profilesButton = findViewById<ExtendedFloatingActionButton>(R.id.profilesButton) ?: return
        val activeProfileName = ProfilesManager.getInstance().getActiveName()
        profilesButton.contentDescription = getString(R.string.profile_manager_choose_profile)
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink()
        } else {
            profilesButton.text = getString(R.string.profile_manager_preset_name, activeProfileName)
            profilesButton.extend()
        }
    }

    override fun onPause() {
        super.onPause()

        inForeground = false
        stopComputerUpdates()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri: Uri = data.data!!
                ShortcutHelper.writeArtFileToUri(this, uri)
            } else {
                ShortcutHelper.artFileContentToExport = null
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUiWithServerInfo(details: ComputerDetails) {
        runOnUiThread {
            val adapter = appGridAdapter ?: return@runOnUiThread
            var updated = false

            for (i in 0 until adapter.itemCount) {
                val existingApp = adapter.getItem(i) as? AppObject ?: continue

                if (existingApp.isRunning && existingApp.app.appId == details.runningGameId) {
                    return@runOnUiThread
                } else if (existingApp.app.appId == details.runningGameId) {
                    existingApp.isRunning = true
                    updated = true
                } else if (existingApp.isRunning) {
                    existingApp.isRunning = false
                    updated = true
                }
            }

            if (updated) {
                adapter.notifyDataSetChanged()
            }

            updateRecentlyPlayedCard()
        }
    }

    private fun updateRecentlyPlayedCard() {
        val card = findViewById<View>(R.id.recently_played_card) ?: return
        val adapter = appGridAdapter

        var targetAppId = lastRunningAppId
        if (targetAppId == 0) {
            targetAppId = getSharedPreferences("nova_prefs", MODE_PRIVATE).getInt("last_played_$uuidString", 0)
        }

        if (targetAppId == 0 || adapter == null) {
            card.visibility = View.GONE
            return
        }

        var targetApp: AppObject? = null
        for (i in 0 until adapter.itemCount) {
            val app = adapter.getItem(i) as? AppObject
            if (app?.app?.appId == targetAppId) {
                targetApp = app
                break
            }
        }

        val finalTargetApp = targetApp
        if (finalTargetApp == null) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE
        UiHelper.applyTvFocusStyle(this, card)
        val nameView = findViewById<TextView>(R.id.recently_played_name)
        val kickerView = findViewById<TextView>(R.id.recently_played_kicker)
        val metaView = findViewById<TextView>(R.id.recently_played_meta)
        val actionView = findViewById<TextView>(R.id.recently_played_action)
        val endSessionView = findViewById<ComposeView>(R.id.recently_played_end_session)
        val artView = findViewById<ImageView>(R.id.recently_played_art)

        val activeComputer = computer
        val appIsRunning = lastRunningAppId == finalTargetApp.app.appId
        val appOwnedByAnotherClient = appIsRunning && activeComputer?.currentGameOwnedByClient == false

        nameView?.text = finalTargetApp.app.appName
        kickerView?.setText(
            if (appOwnedByAnotherClient) {
                R.string.applist_hero_watch
            } else if (appIsRunning) {
                R.string.applist_hero_live
            } else {
                R.string.applist_hero_continue
            },
        )
        metaView?.text =
            finalTargetApp.app.metadataLabel.ifEmpty {
                getString(
                    if (appOwnedByAnotherClient) {
                        R.string.applist_hero_summary_watch
                    } else if (appIsRunning) {
                        R.string.applist_hero_summary_resume
                    } else {
                        R.string.applist_hero_summary_continue
                    },
                )
            }
        actionView?.setText(
            when {
                appOwnedByAnotherClient -> R.string.applist_menu_watch
                // Resume is only honest while the host is still running the game. Otherwise
                // this button launches it, and the kicker above already says Continue.
                appIsRunning -> R.string.pcview_card_action_resume
                else -> R.string.applist_hero_action_play
            },
        )
        endSessionView?.visibility = if (appIsRunning && !appOwnedByAnotherClient) {
            View.VISIBLE
        } else {
            View.GONE
        }
        // End splits in its own place into Stay and End Session: Stay is focused, and End ignores a
        // press for its first 400ms, so no single, held or mashed A ends the stream.
        endSessionView?.setNovaSplitConfirm(
            label = getString(R.string.applist_menu_quit),
            confirmLabel = getString(R.string.game_dialog_action_end_session),
            consequence = getString(R.string.nova_panel_end_session_message),
            icon = R.drawable.ic_close,
            itemKey = finalTargetApp.app.appId,
        ) { endRunningSessionFromLibrary(finalTargetApp.app) }
        if (artView != null) {
            adapter.populateFeaturedArt(finalTargetApp, artView)
        }

        card.setOnClickListener { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            } else {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            getSharedPreferences("nova_prefs", MODE_PRIVATE)
                .edit()
                .putInt("last_played_$uuidString", finalTargetApp.app.appId)
                .apply()

            val binder = managerBinder ?: return@setOnClickListener
            val pc = computer ?: return@setOnClickListener
            val prefs = prefConfig ?: return@setOnClickListener
            if (lastRunningAppId != 0 && pc.currentGameOwnedByClient == false) {
                ServerHelper.doWatch(this, createWatchTargetApp(finalTargetApp.app), pc, binder)
            } else {
                ServerHelper.doStart(this, finalTargetApp.app, pc, binder, prefs.useVirtualDisplay)
            }
        }
    }

    private fun endRunningSessionFromLibrary(app: NvApp) {
        val activeComputer = computer ?: return
        val binder = managerBinder ?: return
        quitRunningSessionAndRefresh(activeComputer, app, binder)
    }

    private fun quitRunningSessionAndRefresh(
        activeComputer: ComputerDetails,
        app: NvApp,
        binder: ComputerManagerService.ComputerManagerBinder,
    ) {
        suspendGridUpdates = true
        val resumeGridUpdates = Runnable {
            suspendGridUpdates = false
            poller?.pollNow()
        }
        ServerHelper.doQuit(this, activeComputer, app, binder, resumeGridUpdates, resumeGridUpdates)
    }

    private fun updateUiWithAppList(appList: List<NvApp>) {
        runOnUiThread {
            val adapter = appGridAdapter ?: return@runOnUiThread
            val activeComputer = computer ?: return@runOnUiThread
            var updated = false

            val incomingMap = HashMap<Int, NvApp>(appList.size)
            for (app in appList) {
                applyPolarisMetadata(app)
                incomingMap[app.appId] = app
            }

            val existingMap = HashMap<Int, AppObject>(adapter.itemCount)
            for (i in 0 until adapter.itemCount) {
                val existingApp = adapter.getItem(i) as? AppObject
                if (existingApp != null) {
                    existingMap[existingApp.app.appId] = existingApp
                }
            }

            for (app in appList) {
                val existing = existingMap[app.appId]
                if (existing != null) {
                    if (existing.app.appName != app.appName) {
                        existing.app.appName = app.appName
                        updated = true
                    }
                    if (applyPolarisMetadata(existing.app)) {
                        updated = true
                    }
                } else {
                    adapter.addApp(AppObject(app))
                    shortcutHelper.enableAppShortcut(activeComputer, app)
                    updated = true
                }
            }

            for ((appId, appObject) in existingMap) {
                if (!incomingMap.containsKey(appId)) {
                    shortcutHelper.disableAppShortcut(
                        activeComputer,
                        appObject.app,
                        getString(R.string.app_removed_from_pc),
                    )
                    adapter.removeApp(appObject)
                    updated = true
                }
            }

            if (updated) {
                adapter.notifyDataSetChanged()
            }

            val searchView = findViewById<android.widget.EditText>(R.id.app_search)
            searchView?.visibility = if (adapter.getTotalAppCount() > 5) View.VISIBLE else View.GONE

            refreshPolarisGameMetadataAsync()
        }
    }

    /** An app's menu, as the first page of a right-edge panel opened from its tile. */
    private fun showAppPanel(selectedApp: AppObject) {
        val activeComputer = computer ?: return
        val binder = managerBinder ?: return
        val prefs = prefConfig ?: return
        val adapter = appGridAdapter ?: return
        val surfaces = novaSurfaces
        val app = selectedApp.app
        val actions = object : NovaAppMenuActions {
            override fun start(withVirtualDisplay: Boolean) =
                ServerHelper.doStart(this@AppView, app, activeComputer, binder, withVirtualDisplay, true, false)

            override fun confirmVirtualDisplayThenStart() = UiHelper.displayVdisplayConfirmationDialog(
                this@AppView,
                activeComputer,
                {
                    surfaces.panel.close()
                    start(withVirtualDisplay = true)
                },
                null,
            )

            override fun watch() =
                ServerHelper.doWatch(this@AppView, createWatchTargetApp(app), activeComputer, binder)

            override fun resume() =
                ServerHelper.doStart(this@AppView, app, activeComputer, binder, prefs.useVirtualDisplay)

            override fun endSession() = quitRunningSessionAndRefresh(activeComputer, app, binder)

            override fun quitAndStart() =
                ServerHelper.doStart(this@AppView, app, activeComputer, binder, prefs.useVirtualDisplay)

            override fun setHidden(hidden: Boolean) {
                if (hidden) hiddenAppIds.add(app.appId) else hiddenAppIds.remove(app.appId)
                updateHiddenApps()
            }

            override fun setPinned(pinned: Boolean) = setAppPinned(adapter, app.appId, pinned)

            override fun exportLauncher() = shortcutHelper.exportLauncherFile(activeComputer, app)
        }
        val ownedByOtherClient = lastRunningAppId != 0 && activeComputer.currentGameOwnedByClient == false
        val items = novaAppMenuItems(
            context = this,
            app = app,
            runningAppId = lastRunningAppId,
            ownedByOtherClient = ownedByOtherClient,
            useVirtualDisplay = prefs.useVirtualDisplay,
            virtualDisplayReady = activeComputer.vDisplaySupported && activeComputer.vDisplayDriverReady,
            hidden = selectedApp.isHidden,
            pinned = adapter.isAppPinned(app.appId),
            actions = actions,
            closePanel = surfaces.panel::close,
        )
        surfaces.open(
            NovaCommonPage.Menu(
                key = "app",
                title = getString(R.string.hosts_panel_app_title),
                items = items,
                header = novaAppMenuHeader(this, app, lastRunningAppId, ownedByOtherClient),
            ),
            NovaEdge.End,
            currentFocus?.let { NovaFocusReturn.View(it) } ?: NovaFocusReturn.None,
        )
    }

    /** Keeps [appId] at the top of the list, or lets it go back to its place. */
    private fun setAppPinned(adapter: AppGridAdapter, appId: Int, pinned: Boolean) {
        val prefs = getSharedPreferences("nova_prefs", MODE_PRIVATE)
        val pinnedIds = HashSet<Int>()
        for (s in prefs.getStringSet("pinned_$uuidString", HashSet()) ?: HashSet()) {
            pinnedIds.add(Integer.parseInt(s))
        }
        if (pinned) pinnedIds.add(appId) else pinnedIds.remove(appId)
        prefs.edit()
            .putStringSet("pinned_$uuidString", pinnedIds.mapTo(HashSet()) { it.toString() })
            .apply()
        adapter.updatePinnedApps(pinnedIds)
    }

    private fun createWatchTargetApp(fallbackApp: NvApp): NvApp {
        val activeComputer = computer
        val adapter = appGridAdapter
        if (activeComputer != null && activeComputer.runningGameId != 0) {
            if (adapter != null) {
                for (i in 0 until adapter.itemCount) {
                    val appObject = adapter.getItem(i) as? AppObject
                    if (appObject?.app?.appId == activeComputer.runningGameId) {
                        return appObject.app
                    }
                }
            }

            if (fallbackApp.appId == activeComputer.runningGameId) {
                return fallbackApp
            }

            return NvApp(
                getString(R.string.applist_menu_watch_active_name),
                activeComputer.runningGameUUID,
                activeComputer.runningGameId,
                false,
            )
        }

        return fallbackApp
    }

    private fun applyPolarisMetadata(app: NvApp?): Boolean {
        if (app == null) {
            return false
        }

        var metadata: PolarisGame? = null
        val uuid = app.appUUID
        if (!uuid.isNullOrEmpty()) {
            metadata = polarisGamesByUuid[uuid.lowercase(Locale.US)]
        }
        if (metadata == null && app.appId != 0) {
            metadata = polarisGamesByAppId[app.appId]
        }
        return metadata != null && app.applyPolarisMetadata(metadata)
    }

    private fun applyPolarisMetadataToVisibleApps(): Boolean {
        val adapter = appGridAdapter ?: return false
        var changed = false
        for (i in 0 until adapter.itemCount) {
            val item = adapter.getItem(i) as? AppObject
            if (item != null) {
                changed = applyPolarisMetadata(item.app) || changed
            }
        }
        return changed
    }

    private fun refreshPolarisGameMetadataAsync() {
        val activeComputer = computer ?: return
        val address = activeComputer.activeAddress ?: activeComputer.localAddress ?: return
        if (activeComputer.httpsPort <= 0) {
            return
        }

        synchronized(polarisMetadataLock) {
            if (polarisMetadataRefreshInFlight) {
                return
            }
            polarisMetadataRefreshInFlight = true
        }

        val host = address.address
        val httpsPort = activeComputer.httpsPort
        val serverCert = activeComputer.serverCert

        runtimeTasks.launchIo("PolarisGameMetadata") {
            try {
                val client = PolarisApiClient(this@AppView, host, httpsPort, serverCert)
                val games = client.getGames("", "", 500)
                if (games.isEmpty()) {
                    return@launchIo
                }

                val byUuid = HashMap<String, PolarisGame>()
                val byAppId = HashMap<Int, PolarisGame>()
                for (game in games) {
                    if (!game.id.isNullOrEmpty()) {
                        byUuid[game.id!!.lowercase(Locale.US)] = game
                    }
                    if (game.appId != 0) {
                        byAppId[game.appId] = game
                    }
                }

                runtimeTasks.runOnMainIfActive {
                    polarisGamesByUuid = byUuid
                    polarisGamesByAppId = byAppId
                    val adapter = appGridAdapter
                    if (adapter != null && applyPolarisMetadataToVisibleApps()) {
                        adapter.notifyDataSetChanged()
                        updateRecentlyPlayedCard()
                    }
                }
            } catch (e: Exception) {
                LimeLog.warning("Nova: Polaris game metadata fetch failed: " + e.message)
            } finally {
                synchronized(polarisMetadataLock) {
                    polarisMetadataRefreshInFlight = false
                }
            }
        }
    }

    override fun getAdapterFragmentLayoutId(): Int = R.layout.app_grid_view

    override fun receiveAbsListView(gridView: View) {
        if (gridView is RecyclerView) {
            val adapter = appGridAdapter
            val prefs = prefConfig
            if (adapter == null || prefs == null) {
                LimeLog.warning("App grid view attached before AppView was ready; waiting for service binding")
                return
            }
            val widthDp = if (prefs.smallIconMode) 110 else 170
            val spanCount = maxOf(
                1,
                resources.displayMetrics.widthPixels / (widthDp * resources.displayMetrics.density).toInt(),
            )
            gridView.layoutManager = GridLayoutManager(this, spanCount)
            gridView.adapter = adapter
            adapter.setOnItemClickListener { app ->
                val activeComputer = computer ?: return@setOnItemClickListener
                val binder = managerBinder ?: return@setOnItemClickListener
                val activePrefs = prefConfig ?: return@setOnItemClickListener
                if (lastRunningAppId != 0) {
                    if (activePrefs.resumeWithoutConfirm && lastRunningAppId == app.app.appId) {
                        if (activeComputer.currentGameOwnedByClient == false) {
                            ServerHelper.doWatch(this@AppView, createWatchTargetApp(app.app), activeComputer, binder)
                        } else {
                            ServerHelper.doStart(
                                this@AppView,
                                app.app,
                                activeComputer,
                                binder,
                                activePrefs.useVirtualDisplay,
                            )
                        }
                    } else {
                        showAppPanel(app)
                    }
                } else {
                    if (activePrefs.useVirtualDisplay &&
                        !(activeComputer.vDisplaySupported && activeComputer.vDisplayDriverReady)
                    ) {
                        UiHelper.displayVdisplayConfirmationDialog(
                            this@AppView,
                            activeComputer,
                            {
                                ServerHelper.doStart(
                                    this@AppView,
                                    app.app,
                                    activeComputer,
                                    binder,
                                    true,
                                    true,
                                    false,
                                )
                            },
                            null,
                        )
                    } else {
                        ServerHelper.doStart(
                            this@AppView,
                            app.app,
                            activeComputer,
                            binder,
                            activePrefs.useVirtualDisplay,
                        )
                    }
                }
            }
            gridView.addOnItemTouchListener(
                RecyclerItemClickListener(
                    this,
                    gridView,
                    object : RecyclerItemClickListener.OnItemClickListener {
                        override fun onItemClick(view: View, position: Int) = Unit

                        override fun onLongItemClick(view: View, position: Int) {
                            val app = adapter.getItem(position) as? AppObject
                            if (app != null) {
                                showAppPanel(app)
                            }
                        }
                    },
                ),
            )
            UiHelper.applyStatusBarPadding(gridView)
            gridView.requestFocus()
        }
    }

    class AppObject(app: NvApp?) {
        @JvmField val app: NvApp
        @JvmField var isRunning = false
        @JvmField var isHidden = false
        @JvmField var isPinned = false

        init {
            if (app == null) {
                throw IllegalArgumentException("app must not be null")
            }
            this.app = app
        }

        override fun toString(): String = app.appName
    }

    companion object {
        const val HIDDEN_APPS_PREF_FILENAME = "HiddenApps"
        const val NAME_EXTRA = "Name"
        const val UUID_EXTRA = "UUID"
        const val NEW_PAIR_EXTRA = "NewPair"
        const val SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps"
    }
}
