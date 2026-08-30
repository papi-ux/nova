package com.papi.nova

import android.app.Service
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.computers.ComputerDatabaseManager
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.wol.WakeOnLanSender
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.AutoQualityProfilePreferences
import com.papi.nova.ui.NovaLaunchPreflight
import com.papi.nova.ui.NovaLaunchStreamOverride
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.CacheHelper
import com.papi.nova.utils.DeviceUtils
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper
import java.security.cert.CertificateEncodingException
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.Objects
import java.util.UUID

class ShortcutTrampoline : NovaActivity() {
    private lateinit var prefConfig: PreferenceConfiguration
    private var uuidString: String = ""
    private var app: NvApp? = null
    private val intentStack = ArrayList<Intent>()

    private var wakeHostTries = 10
    private var computer: ComputerDetails? = null
    private var blockingLoadSpinner: SpinnerDialog? = null

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    private data class ShortcutLaunchPlan(
        val app: NvApp,
        val profilePreference: String = "auto",
        val launchOptimizationJson: String? = null,
        val polarisGame: PolarisGame? = null,
        val usesVirtualDisplay: Boolean? = null,
        val mirrorDesktop: Boolean = false,
        val streamMode: String = "",
        val streamWidth: Int = 0,
        val streamHeight: Int = 0,
        val streamFps: Float = 0f,
    )

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder

            Thread {
                localBinder.waitForReady()
                managerBinder = localBinder

                computer = localBinder.getComputer(uuidString)
                val currentComputer = computer

                if (currentComputer == null) {
                    Dialog.displayDialog(
                        this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_pc_not_found),
                        true,
                    )

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner?.dismiss()
                        blockingLoadSpinner = null
                    }

                    if (managerBinder != null) {
                        unbindService(serviceConnection)
                        managerBinder = null
                    }

                    return@Thread
                }

                localBinder.invalidateStateForComputer(currentComputer.uuid)

                localBinder.startPolling(
                    object : ComputerManagerListener {
                        override fun notifyComputerUpdated(details: ComputerDetails) {
                            if (!details.uuid.equals(uuidString, ignoreCase = true)) {
                                return
                            }

                            val targetComputer = computer
                            if (
                                details.state != ComputerDetails.State.ONLINE &&
                                details.macAddress != null &&
                                --wakeHostTries >= 0
                            ) {
                                try {
                                    if (targetComputer != null) {
                                        WakeOnLanSender.sendWolPacket(targetComputer)
                                        localBinder.invalidateStateForComputer(targetComputer.uuid)
                                    }
                                    return
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                            }

                            if (details.state != ComputerDetails.State.UNKNOWN) {
                                val shortcutLaunchPlan = if (
                                    details.state == ComputerDetails.State.ONLINE &&
                                    details.pairState == PairingManager.PairState.PAIRED &&
                                    app != null
                                ) {
                                    resolvePolarisShortcutLaunchPlan(details, app!!)
                                } else {
                                    null
                                }
                                val readyShortcutLaunchPlan = shortcutLaunchPlan?.takeIf {
                                    canStartShortcutWithoutQuit(details, it)
                                }?.let {
                                    applyPolarisShortcutLaunchPreflight(details, it, prefConfig.useVirtualDisplay)
                                }

                                runOnUiThread {
                                    if (blockingLoadSpinner != null) {
                                        blockingLoadSpinner?.dismiss()
                                        blockingLoadSpinner = null
                                    }

                                    val activeBinder = managerBinder
                                    if (activeBinder == null) {
                                        finish()
                                        return@runOnUiThread
                                    }

                                    if (
                                        details.state == ComputerDetails.State.ONLINE &&
                                        details.pairState == PairingManager.PairState.PAIRED
                                    ) {
                                        val currentApp = app
                                        if (currentApp != null) {
                                            val launchPlan = shortcutLaunchPlan ?: ShortcutLaunchPlan(currentApp)
                                            if (canStartShortcutWithoutQuit(details, launchPlan)) {
                                                val readyLaunchPlan = readyShortcutLaunchPlan ?: launchPlan
                                                intentStack.add(
                                                    ServerHelper.createStartIntent(
                                                        this@ShortcutTrampoline,
                                                        readyLaunchPlan.app,
                                                        details,
                                                        activeBinder,
                                                        readyLaunchPlan.usesVirtualDisplay
                                                            ?: prefConfig.useVirtualDisplay,
                                                        displayModeExplicit = false,
                                                        watchOnly = false,
                                                        profilePreference = readyLaunchPlan.profilePreference,
                                                        launchOptimizationJson = readyLaunchPlan.launchOptimizationJson,
                                                        mirrorDesktop = readyLaunchPlan.mirrorDesktop,
                                                        streamWidth = readyLaunchPlan.streamWidth,
                                                        streamHeight = readyLaunchPlan.streamHeight,
                                                        streamFps = readyLaunchPlan.streamFps,
                                                        streamMode = readyLaunchPlan.streamMode,
                                                    ),
                                                )

                                                finish()
                                                startActivities(intentStack.toTypedArray())
                                            } else {
                                                UiHelper.displayQuitConfirmationDialog(
                                                    this@ShortcutTrampoline,
                                                    Runnable {
                                                        startConfirmedShortcutLaunch(
                                                            details,
                                                            activeBinder,
                                                            launchPlan,
                                                            prefConfig.useVirtualDisplay,
                                                        )
                                                    },
                                                    Runnable {
                                                        finish()
                                                    },
                                                )
                                            }
                                        } else {
                                            finish()

                                            val pcIntent = Intent(this@ShortcutTrampoline, PcView::class.java).apply {
                                                action = Intent.ACTION_MAIN
                                                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            intentStack.add(pcIntent)

                                            val appViewIntent = Intent(intent).apply {
                                                setClass(this@ShortcutTrampoline, AppView::class.java)
                                            }
                                            intentStack.add(appViewIntent)

                                            if (details.runningGameId != 0) {
                                                intentStack.add(
                                                    ServerHelper.createStartIntent(
                                                        this@ShortcutTrampoline,
                                                        NvApp(null, null, details.runningGameId, false),
                                                        details,
                                                        activeBinder,
                                                        prefConfig.useVirtualDisplay,
                                                    ),
                                                )
                                            }

                                            startActivities(intentStack.toTypedArray())
                                        }
                                    } else if (details.state == ComputerDetails.State.OFFLINE) {
                                        Dialog.displayDialog(
                                            this@ShortcutTrampoline,
                                            resources.getString(R.string.conn_error_title),
                                            resources.getString(R.string.error_pc_offline),
                                            true,
                                        )
                                    } else if (details.pairState != PairingManager.PairState.PAIRED) {
                                        Dialog.displayDialog(
                                            this@ShortcutTrampoline,
                                            resources.getString(R.string.conn_error_title),
                                            resources.getString(R.string.scut_not_paired),
                                            true,
                                        )
                                    }

                                    if (managerBinder != null) {
                                        managerBinder?.stopPolling()
                                        unbindService(serviceConnection)
                                        managerBinder = null
                                    }
                                }
                            }
                        }
                    },
                )
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    protected fun validateHostInput(hostUUID: String?, hostName: String?): Boolean {
        if (hostUUID == null && hostName == null) {
            Dialog.displayDialog(
                this@ShortcutTrampoline,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.scut_invalid_uuid),
                true,
            )
            return false
        }

        if (!hostUUID.isNullOrEmpty()) {
            try {
                UUID.fromString(hostUUID)
            } catch (_: IllegalArgumentException) {
                Dialog.displayDialog(
                    this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_uuid),
                    true,
                )
                return false
            }
        } else if (hostName.isNullOrEmpty()) {
            Dialog.displayDialog(
                this@ShortcutTrampoline,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.scut_invalid_uuid),
                true,
            )
            return false
        }

        return true
    }

    protected fun validateAppInput(appUUID: String?, appIDStr: String?, appName: String?): Boolean {
        if (appUUID == null && appIDStr == null && appName == null) {
            return false
        }

        if (!appUUID.isNullOrEmpty()) {
            try {
                UUID.fromString(appUUID)
            } catch (_: IllegalArgumentException) {
                Dialog.displayDialog(
                    this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_app_id),
                    true,
                )
                return false
            }
        } else if (!appIDStr.isNullOrEmpty()) {
            try {
                Integer.parseInt(appIDStr)
            } catch (_: NumberFormatException) {
                Dialog.displayDialog(
                    this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_app_id),
                    true,
                )
                return false
            }
        }

        return true
    }

    private fun isSafeArtFileUri(fileUri: Uri?): Boolean {
        if (fileUri == null) {
            return false
        }

        val scheme = fileUri.scheme
        if (ContentResolver.SCHEME_FILE != scheme) {
            return false
        }

        val path = fileUri.path
        if (path == null || !path.lowercase(Locale.US).endsWith(".art")) {
            return false
        }

        val canonicalPath = try {
            File(path).canonicalPath
        } catch (_: IOException) {
            return false
        }

        if (canonicalPath == "/data" || canonicalPath.startsWith("/data/")) {
            return false
        }
        if (canonicalPath == "/proc" || canonicalPath.startsWith("/proc/")) {
            return false
        }
        if (canonicalPath == "/sys" || canonicalPath.startsWith("/sys/")) {
            return false
        }
        if (canonicalPath == "/dev" || canonicalPath.startsWith("/dev/")) {
            return false
        }
        if (canonicalPath == "/acct" || canonicalPath.startsWith("/acct/")) {
            return false
        }

        return true
    }

    private fun parseArtFileData(fileUri: Uri?): Map<String, String>? {
        if (fileUri == null) {
            return null
        }

        val scheme = fileUri.scheme
        val path = fileUri.path
        val canonicalPath = if (
            ContentResolver.SCHEME_FILE == scheme &&
            path != null &&
            path.lowercase(Locale.US).endsWith(".art")
        ) {
            try {
                File(path).canonicalPath
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }

        if (
            canonicalPath == null ||
            canonicalPath == "/data" ||
            canonicalPath.startsWith("/data/") ||
            canonicalPath == "/proc" ||
            canonicalPath.startsWith("/proc/") ||
            canonicalPath == "/sys" ||
            canonicalPath.startsWith("/sys/") ||
            canonicalPath == "/dev" ||
            canonicalPath.startsWith("/dev/") ||
            canonicalPath == "/acct" ||
            canonicalPath.startsWith("/acct/")
        ) {
            Dialog.displayDialog(
                this@ShortcutTrampoline,
                resources.getString(R.string.conn_error_title),
                "Invalid .art file URI",
                true,
            )
            return null
        }

        val artData = HashMap<String, String>()

        try {
            FileInputStream(File(canonicalPath)).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var charsRead = 0
                    while (true) {
                        val rawLine = reader.readLine() ?: break
                        charsRead += rawLine.length
                        if (charsRead > MAX_ART_FILE_CHARS) {
                            throw IOException(".art file is too large")
                        }

                        var line = rawLine.trim()
                        if (line.startsWith("#") || line.isEmpty()) {
                            continue
                        }

                        if (!line.startsWith("[")) {
                            throw IOException("Invalid .art file format")
                        }

                        val separatorIndex = line.indexOf(' ')
                        if (separatorIndex > 0 && separatorIndex < line.length - 1) {
                            var key = line.substring(0, separatorIndex).trim()
                            val value = line.substring(separatorIndex + 1).trim()
                            if (key.endsWith("]")) {
                                key = key.substring(1, key.length - 1)
                                artData[key] = value
                            } else {
                                throw IOException("Invalid .art file format")
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading .art file", e)
            Dialog.displayDialog(
                this@ShortcutTrampoline,
                resources.getString(R.string.conn_error_title),
                "Error reading .art file: " + e.message,
                true,
            )
        }

        return artData
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        prefConfig = PreferenceConfiguration.readPreferences(this)
        UiHelper.notifyNewRootView(this)

        val dbManager = ComputerDatabaseManager(this)
        val launchIntent = intent
        val action = launchIntent.action
        val dataUri = launchIntent.data

        var hostUUID: String? = null
        var hostName: String? = null
        var appName: String? = null
        var appUUID: String? = null
        var appIDStr: String? = null

        if (Intent.ACTION_VIEW == action && dataUri != null) {
            val artData = parseArtFileData(dataUri)

            if (artData != null) {
                hostUUID = artData[ShortcutHelper.KEY_HOST_UUID]
                hostName = artData[ShortcutHelper.KEY_HOST_NAME]
                appName = artData[ShortcutHelper.KEY_APP_NAME]
                appUUID = artData[ShortcutHelper.KEY_APP_UUID]
                appIDStr = artData[ShortcutHelper.KEY_APP_ID]
            }
        }

        if (hostUUID == null) {
            hostUUID = intent.getStringExtra(AppView.UUID_EXTRA)
        }
        if (hostName == null) {
            hostName = intent.getStringExtra(AppView.NAME_EXTRA)
        }
        if (appUUID == null) {
            appUUID = intent.getStringExtra(Game.EXTRA_APP_UUID)
        }
        if (appIDStr == null) {
            appIDStr = intent.getStringExtra(Game.EXTRA_APP_ID)
        }
        if (appName == null) {
            appName = intent.getStringExtra(Game.EXTRA_APP_NAME)
        }

        if (!validateHostInput(hostUUID, hostName)) {
            return
        }

        if (hostUUID.isNullOrEmpty()) {
            val resolvedComputer = dbManager.getComputerByName(hostName ?: "")
            if (resolvedComputer == null) {
                Dialog.displayDialog(
                    this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_pc_not_found),
                    true,
                )
                return
            }

            hostUUID = resolvedComputer.uuid
        }

        uuidString = hostUUID
        setIntent(Intent(intent).putExtra(AppView.UUID_EXTRA, uuidString))

        if (validateAppInput(appUUID, appIDStr, appName)) {
            if (
                appName.isNullOrEmpty() &&
                (!appUUID.isNullOrEmpty() || !appIDStr.isNullOrEmpty())
            ) {
                val cachedName = findCachedAppName(uuidString, appUUID, appIDStr)
                if (!cachedName.isNullOrEmpty()) {
                    appName = cachedName
                }
            }

            if (!appUUID.isNullOrEmpty()) {
                app = NvApp(
                    appName,
                    appUUID,
                    -1,
                    intent.getBooleanExtra(Game.EXTRA_APP_HDR, false),
                )
            } else if (!appIDStr.isNullOrEmpty()) {
                val appID = Integer.parseInt(appIDStr)
                app = NvApp(
                    appName,
                    null,
                    appID,
                    intent.getBooleanExtra(Game.EXTRA_APP_HDR, false),
                )
            } else if (!appName.isNullOrEmpty()) {
                try {
                    var appID = -1
                    var appUuidFromFile: String? = null
                    val rawAppList = CacheHelper.readInputStreamToString(
                        CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString),
                    )

                    if (rawAppList.isEmpty()) {
                        Dialog.displayDialog(
                            this@ShortcutTrampoline,
                            resources.getString(R.string.conn_error_title),
                            resources.getString(R.string.scut_invalid_app_id) +
                                " (applist cache empty or unreadable)",
                            true,
                        )
                        return
                    }

                    val applist = NvHTTP.getAppListByReader(StringReader(rawAppList))
                    for (candidate in applist) {
                        if (candidate.appName.equals(appName, ignoreCase = true)) {
                            appID = candidate.appId
                            appUuidFromFile = candidate.appUUID
                            break
                        }
                    }

                    if (appID < 0 && appUuidFromFile == null) {
                        Dialog.displayDialog(
                            this@ShortcutTrampoline,
                            resources.getString(R.string.conn_error_title),
                            resources.getString(R.string.scut_invalid_app_id) +
                                " (app not found in cache)",
                            true,
                        )
                        return
                    }

                    val currentIntent = intent
                    if (currentIntent.getStringExtra(Game.EXTRA_APP_ID) == null && appID != -1) {
                        currentIntent.putExtra(Game.EXTRA_APP_ID, appID.toString())
                    }
                    if (currentIntent.getStringExtra(Game.EXTRA_APP_UUID) == null && appUuidFromFile != null) {
                        currentIntent.putExtra(Game.EXTRA_APP_UUID, appUuidFromFile)
                    }

                    app = NvApp(
                        appName,
                        appUuidFromFile,
                        appID,
                        intent.getBooleanExtra(Game.EXTRA_APP_HDR, false),
                    )
                } catch (e: IOException) {
                    displayAppListError(e)
                    return
                } catch (e: XmlPullParserException) {
                    displayAppListError(e)
                    return
                }
            }
        }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE,
        )

        blockingLoadSpinner = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.applist_connect_msg),
            true,
        )
    }

    private fun canStartShortcutWithoutQuit(details: ComputerDetails, launchPlan: ShortcutLaunchPlan): Boolean =
        details.runningGameId == 0 ||
            details.runningGameId == launchPlan.app.appId ||
            Objects.equals(details.runningGameUUID, launchPlan.app.appUUID)

    private fun resolvePolarisShortcutLaunchPlan(
        details: ComputerDetails,
        shortcutApp: NvApp,
    ): ShortcutLaunchPlan {
        val activeAddress = details.activeAddress ?: return ShortcutLaunchPlan(shortcutApp)
        val serverCert = try {
            details.serverCert?.encoded
        } catch (e: CertificateEncodingException) {
            LimeLog.warning("Nova: Shortcut launch could not encode server cert for Polaris metadata lookup: ${e.message}")
            null
        } ?: return ShortcutLaunchPlan(shortcutApp)

        return try {
            val apiClient = PolarisApiClient(this, activeAddress.address, details.httpsPort, serverCert)
            val polarisGame = findPolarisShortcutGame(apiClient, shortcutApp)
                ?: return ShortcutLaunchPlan(shortcutApp)
            val launchApp = NvApp(polarisGame.name, polarisGame.id, polarisGame.appId, polarisGame.hdrSupported)

            ShortcutLaunchPlan(
                app = launchApp,
                polarisGame = polarisGame,
            )
        } catch (e: Exception) {
            LimeLog.warning("Nova: Shortcut launch Polaris metadata lookup failed: ${e.message}")
            ShortcutLaunchPlan(shortcutApp)
        }
    }

    private fun applyPolarisShortcutLaunchPreflight(
        details: ComputerDetails,
        launchPlan: ShortcutLaunchPlan,
        withVirtualDisplay: Boolean,
    ): ShortcutLaunchPlan {
        val polarisGame = launchPlan.polarisGame ?: return launchPlan
        val activeAddress = details.activeAddress ?: return launchPlan
        val serverCert = try {
            details.serverCert?.encoded
        } catch (e: CertificateEncodingException) {
            LimeLog.warning("Nova: Shortcut launch could not encode server cert for Polaris preflight: ${e.message}")
            null
        } ?: return launchPlan

        return try {
            val apiClient = PolarisApiClient(this, activeAddress.address, details.httpsPort, serverCert)
            val mangoHudSynced = apiClient.setMangoHud(polarisGame.id, polarisGame.mangohud)
            if (!mangoHudSynced) {
                LimeLog.warning("Nova: Shortcut launch MangoHUD state sync failed; continuing launch")
            }

            val clientSettings = apiClient.getClientSettings()
            // The per-game Tuning choice, not a fixed "auto": a game pinned to High FPS
            // in Play Setup must launch pinned from a home-screen shortcut too.
            val profilePreference = AutoQualityProfilePreferences.load(this, polarisGame.id, polarisGame.name)
            val preferences = PreferenceConfiguration.readPreferences(this)
            val metered = StreamSyncManager.isMeteredNetwork(this)
            val requestedBitrateKbps = if (metered) preferences.meteredBitrate else preferences.bitrate
            val optimization = apiClient.getOptimization(
                DeviceUtils.getModel(),
                polarisGame.id.ifBlank { polarisGame.name },
                profilePreference,
                mode = PolarisStreamDisplayMode.preflightModeForLaunch(withVirtualDisplay, clientSettings),
                width = preferences.width,
                height = preferences.height,
                fps = preferences.fps,
                bitrateKbps = requestedBitrateKbps,
                bitrateLocked = metered,
                hdr = preferences.enableHdr,
                clientMaxFps = StreamSyncManager.maxSupportedRefreshRate(
                    ServerHelper.getActiveDisplay(this, preferences)
                ),
            )
            val composed = NovaLaunchStreamOverride.compose(
                optimization,
                null,
                NovaLaunchStreamOverride.highFpsPin(profilePreference, preferences.fps),
                preferences.width,
                preferences.height,
                preferences.fps.toInt(),
            )
            val launchUsesVirtualDisplay = withVirtualDisplay
            val launchMirrorDesktop = false
            val launchMode = ""
            val launchResolution = StreamSyncManager.resolveAutoSafeResolution(
                preferences.width,
                preferences.height,
                composed,
            )
            val launchFps = StreamSyncManager.resolveAutoSafeTargetFps(preferences.fps, composed)
            val launchBitrateKbps = StreamSyncManager.resolveAutoSafeBitrateKbps(requestedBitrateKbps, composed)

            syncShortcutLaunchPreflightSettings(
                apiClient = apiClient,
                clientSettings = clientSettings,
                usesVirtualDisplay = launchUsesVirtualDisplay,
                mirrorDesktop = launchMirrorDesktop,
                resolvedMode = launchMode,
                // Do not persist a deterministic preset's one-launch result
                // as the paired client's next-launch policy.
                width = preferences.width,
                height = preferences.height,
                fps = preferences.fps,
                bitrateKbps = preferences.bitrate,
            )

            launchPlan.copy(
                profilePreference = profilePreference,
                launchOptimizationJson = composed?.toString(),
                usesVirtualDisplay = launchUsesVirtualDisplay,
                mirrorDesktop = launchMirrorDesktop,
                streamMode = launchMode,
                streamWidth = launchResolution.width,
                streamHeight = launchResolution.height,
                streamFps = launchFps,
            )
        } catch (e: Exception) {
            LimeLog.warning("Nova: Shortcut launch Polaris preflight failed: ${e.message}")
            launchPlan
        }
    }

    private fun startConfirmedShortcutLaunch(
        details: ComputerDetails,
        activeBinder: ComputerManagerService.ComputerManagerBinder,
        launchPlan: ShortcutLaunchPlan,
        withVirtualDisplay: Boolean,
    ) {
        Thread {
            val readyLaunchPlan = applyPolarisShortcutLaunchPreflight(details, launchPlan, withVirtualDisplay)
            val startIntent = ServerHelper.createStartIntent(
                this@ShortcutTrampoline,
                readyLaunchPlan.app,
                details,
                activeBinder,
                readyLaunchPlan.usesVirtualDisplay ?: withVirtualDisplay,
                displayModeExplicit = false,
                watchOnly = false,
                profilePreference = readyLaunchPlan.profilePreference,
                launchOptimizationJson = readyLaunchPlan.launchOptimizationJson,
                mirrorDesktop = readyLaunchPlan.mirrorDesktop,
                streamWidth = readyLaunchPlan.streamWidth,
                streamHeight = readyLaunchPlan.streamHeight,
                streamFps = readyLaunchPlan.streamFps,
                streamMode = readyLaunchPlan.streamMode,
            )

            runOnUiThread {
                intentStack.add(startIntent)
                finish()
                startActivities(intentStack.toTypedArray())
            }
        }.start()
    }

    private fun findPolarisShortcutGame(apiClient: PolarisApiClient, shortcutApp: NvApp): PolarisGame? {
        val shortcutUuid = shortcutApp.appUUID
        val shortcutName = shortcutApp.appName
        return apiClient.getGames(limit = 100).firstOrNull { game ->
            (!shortcutUuid.isNullOrBlank() && shortcutUuid.equals(game.id, ignoreCase = true)) ||
                (shortcutApp.appId > 0 && game.appId == shortcutApp.appId) ||
                (!shortcutName.isNullOrBlank() && shortcutName.equals(game.name, ignoreCase = true))
        }
    }

    private fun syncShortcutLaunchPreflightSettings(
        apiClient: PolarisApiClient,
        clientSettings: PolarisClientSettings?,
        usesVirtualDisplay: Boolean,
        mirrorDesktop: Boolean,
        resolvedMode: String,
        width: Int,
        height: Int,
        fps: Float,
        bitrateKbps: Int,
    ) {
        val syncedSettings = NovaLaunchPreflight.push(
            apiClient = apiClient,
            clientSettings = clientSettings,
            usesVirtualDisplay = usesVirtualDisplay,
            mirrorDesktop = mirrorDesktop,
            resolvedMode = resolvedMode,
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = bitrateKbps,
        )
        if (syncedSettings == null) {
            LimeLog.warning("Nova: Shortcut launch preflight client settings sync failed; continuing launch")
        }
    }

    private fun displayAppListError(e: Exception) {
        Log.e(TAG, "Error processing app list from cache", e)
        Dialog.displayDialog(
            this@ShortcutTrampoline,
            resources.getString(R.string.conn_error_title),
            resources.getString(R.string.scut_invalid_app_id) + " (error parsing applist cache)",
            true,
        )
    }

    private fun findCachedAppName(hostUUID: String?, appUUID: String?, appIDStr: String?): String? {
        if (hostUUID.isNullOrEmpty()) {
            return null
        }

        var appID = -1
        if (!appIDStr.isNullOrEmpty()) {
            try {
                appID = Integer.parseInt(appIDStr)
            } catch (_: NumberFormatException) {
            }
        }

        try {
            val rawAppList = CacheHelper.readInputStreamToString(
                CacheHelper.openCacheFileForInput(cacheDir, "applist", hostUUID),
            )
            if (rawAppList.isEmpty()) {
                return null
            }

            val applist = NvHTTP.getAppListByReader(StringReader(rawAppList))
            for (candidate in applist) {
                if (
                    !appUUID.isNullOrEmpty() &&
                    appUUID.equals(candidate.appUUID, ignoreCase = true)
                ) {
                    return candidate.appName
                }
                if (appID >= 0 && candidate.appId == appID) {
                    return candidate.appName
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to resolve shortcut app name from cache", e)
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "Unable to resolve shortcut app name from cache", e)
        }

        return null
    }

    override fun onStop() {
        super.onStop()

        if (blockingLoadSpinner != null) {
            blockingLoadSpinner?.dismiss()
            blockingLoadSpinner = null
        }

        Dialog.closeDialogs()

        if (managerBinder != null) {
            managerBinder?.stopPolling()
            unbindService(serviceConnection)
            managerBinder = null
        }

        finish()
    }

    companion object {
        private const val MAX_ART_FILE_CHARS = 64 * 1024
        private const val TAG = "ShortcutTrampoline"
    }
}
