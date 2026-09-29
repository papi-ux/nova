package com.papi.nova.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.papi.nova.AppView
import com.papi.nova.Game
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.ShortcutTrampoline
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostHttpResponseException
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateEncodingException
import java.util.ArrayList

object ServerHelper {
    const val CONNECTION_TEST_SERVER: String = "android.conntest.moonlight-stream.org"
    private const val NETWORK_TEST_PAGE_KEY = "nova-network-test"
    private const val NETWORK_RESULT_PAGE_KEY = "nova-network-test-result"

    @JvmStatic
    @Throws(IOException::class)
    fun getCurrentAddressFromComputer(computer: ComputerDetails): ComputerDetails.AddressTuple {
        return computer.activeAddress
            ?: throw IOException("No active address for " + computer.name)
    }

    @JvmStatic
    fun createPcShortcutIntent(parent: Activity, computer: ComputerDetails): Intent {
        return Intent(parent, ShortcutTrampoline::class.java).apply {
            putExtra(AppView.NAME_EXTRA, computer.name)
            putExtra(AppView.UUID_EXTRA, computer.uuid)
            action = Intent.ACTION_DEFAULT
        }
    }

    @JvmStatic
    fun createAppShortcutIntent(parent: Activity, computer: ComputerDetails, app: NvApp): Intent {
        return Intent(parent, ShortcutTrampoline::class.java).apply {
            putExtra(AppView.NAME_EXTRA, computer.name)
            putExtra(AppView.UUID_EXTRA, computer.uuid)
            putExtra(Game.EXTRA_APP_NAME, app.appName)
            putExtra(Game.EXTRA_APP_UUID, app.appUUID)
            putExtra(Game.EXTRA_APP_ID, "" + app.appId)
            putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
            action = Intent.ACTION_DEFAULT
        }
    }

    @JvmStatic
    fun getActiveDisplay(context: Context, prefs: PreferenceConfiguration): Display {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("Default display is unavailable")
        return if (prefs.enableFullExDisplay) {
            getAndroidStreamDisplay(context, prefs) ?: defaultDisplay
        } else {
            defaultDisplay
        }
    }

    @JvmStatic
    fun getAndroidStreamDisplay(context: Context, prefs: PreferenceConfiguration): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val candidateMap = buildDisplayCandidateMap(displayManager.displays)
        val selected = AndroidStreamDisplayTarget.select(
            candidateMap.candidates,
            Display.DEFAULT_DISPLAY,
            prefs.androidStreamDisplayTarget,
        ) ?: return null
        LimeLog.info(
            "Nova: Android display role stream id=${selected.displayId} " +
                "target=${prefs.androidStreamDisplayTarget}"
        )
        return candidateMap.displaysById[selected.displayId]
    }

    @JvmStatic
    fun getSecondaryDisplay(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return selectDisplay(displayManager.displays, AndroidStreamDisplayTarget.EXTERNAL)
    }

    @JvmStatic
    fun getAndroidCompanionDisplay(
        context: Context,
        prefs: PreferenceConfiguration,
        streamDisplayId: Int,
    ): Display? {
        if (!prefs.enableFullExDisplay) return null

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val candidateMap = buildDisplayCandidateMap(displayManager.displays)
        val selected = AndroidStreamDisplayTarget.selectCompanion(
            candidateMap.candidates,
            Display.DEFAULT_DISPLAY,
            streamDisplayId,
        ) ?: return null
        LimeLog.info(
            "Nova: Android display role companion id=${selected.displayId} " +
                "stream_id=$streamDisplayId"
        )

        return candidateMap.displaysById[selected.displayId]
    }

    private data class AndroidDisplayCandidateMap(
        val displaysById: Map<Int, Display>,
        val candidates: List<AndroidStreamDisplayTarget.Candidate>,
    )

    private fun buildDisplayCandidateMap(displays: Array<Display>): AndroidDisplayCandidateMap {
        val displaysById = LinkedHashMap<Int, Display>()
        val candidates = displays.map { display ->
            LimeLog.info(display.toString())
            displaysById[display.displayId] = display
            AndroidDisplayCandidateAdapter.from(display)
        }
        return AndroidDisplayCandidateMap(displaysById, candidates)
    }

    private fun selectDisplay(displays: Array<Display>, target: String?): Display? {
        val candidateMap = buildDisplayCandidateMap(displays)
        val selected = AndroidStreamDisplayTarget.select(
            candidateMap.candidates,
            Display.DEFAULT_DISPLAY,
            target,
        ) ?: return null
        return candidateMap.displaysById[selected.displayId]
    }

    private fun getActivityDisplayId(parent: Activity): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return parent.display?.displayId ?: Display.DEFAULT_DISPLAY
        }

        @Suppress("DEPRECATION")
        return parent.windowManager.defaultDisplay.displayId
    }

    fun createStartIntent(
        parent: Activity,
        app: NvApp,
        host: String,
        port: Int,
        httpsPort: Int,
        uniqueId: String,
        pcUuid: String,
        pcName: String,
        withVDisplay: Boolean,
        displayModeExplicit: Boolean,
        watchOnly: Boolean,
        serverCommands: ArrayList<String>?,
        serverCert: ByteArray?,
        streamWidth: Int = 0,
        streamHeight: Int = 0,
        streamFps: Float = 0f,
        aiProfilePreference: String = "auto",
        launchOptimizationJson: String? = null,
        mirrorDesktop: Boolean = false,
        forcePrivateAfterSteamClose: Boolean = false,
        streamMode: String = "",
        encoderBackend: String = "",
        faceButtonLayout: String = "",
    ): Intent {
        val prefConfig = PreferenceConfiguration.readPreferences(parent)
        val selectedAndroidDisplay = if (prefConfig.enableFullExDisplay) {
            getAndroidStreamDisplay(parent, prefConfig)
        } else {
            null
        }
        val companionAndroidDisplay = if (selectedAndroidDisplay != null) {
            getAndroidCompanionDisplay(parent, prefConfig, selectedAndroidDisplay.displayId)
        } else {
            null
        }
        val currentDisplayId = getActivityDisplayId(parent)
        val useAndroidDisplayLaunch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            AndroidStreamDisplayTarget.shouldUseDisplayLaunchTrampoline(
                selectedDisplayId = selectedAndroidDisplay?.displayId,
                currentDisplayId = currentDisplayId,
                companionDisplayId = companionAndroidDisplay?.displayId,
            )
        val gameIntent = if (useAndroidDisplayLaunch && selectedAndroidDisplay != null) {
            Intent(parent.createDisplayContext(selectedAndroidDisplay), Game::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(parent, Game::class.java)
        }

        gameIntent.putExtra(Game.EXTRA_HOST, host)
        gameIntent.putExtra(Game.EXTRA_PORT, port)
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, httpsPort)
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.appName)
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.appUUID)
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.appId)
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, uniqueId)
        gameIntent.putExtra(Game.EXTRA_PC_UUID, pcUuid)
        gameIntent.putExtra(Game.EXTRA_PC_NAME, pcName)
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay)
        gameIntent.putExtra(Game.EXTRA_DISPLAY_MODE_EXPLICIT, displayModeExplicit)
        gameIntent.putExtra(Game.EXTRA_MIRROR_DESKTOP, mirrorDesktop)
        if (streamMode.isNotBlank()) {
            gameIntent.putExtra(Game.EXTRA_STREAM_MODE, streamMode)
        }
        if (encoderBackend.isNotBlank()) {
            gameIntent.putExtra(Game.EXTRA_ENCODER_BACKEND, encoderBackend)
        }
        if (faceButtonLayout.isNotBlank()) {
            gameIntent.putExtra(Game.EXTRA_FACE_BUTTON_LAYOUT, faceButtonLayout)
        }
        gameIntent.putExtra(Game.EXTRA_FORCE_PRIVATE_AFTER_STEAM_CLOSE, forcePrivateAfterSteamClose)
        gameIntent.putExtra(Game.EXTRA_WATCH_ONLY, watchOnly)
        if (streamWidth > 0 && streamHeight > 0) {
            gameIntent.putExtra(Game.EXTRA_STREAM_WIDTH, streamWidth)
            gameIntent.putExtra(Game.EXTRA_STREAM_HEIGHT, streamHeight)
        }
        if (streamFps > 0f) {
            gameIntent.putExtra(Game.EXTRA_STREAM_FPS, streamFps)
        }
        gameIntent.putExtra(Game.EXTRA_AI_PROFILE_PREFERENCE, aiProfilePreference)
        if (!launchOptimizationJson.isNullOrBlank()) {
            gameIntent.putExtra(Game.EXTRA_LAUNCH_OPTIMIZATION, launchOptimizationJson)
        }

        if (serverCommands != null) {
            gameIntent.putStringArrayListExtra(Game.EXTRA_SERVER_COMMANDS, serverCommands)
        }
        if (serverCert != null) {
            gameIntent.putExtra(Game.EXTRA_SERVER_CERT, serverCert)
        }

        if (selectedAndroidDisplay != null) {
            gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, selectedAndroidDisplay.displayId)
        }

        if (useAndroidDisplayLaunch && selectedAndroidDisplay != null) {
            return Intent(parent, GameDisplayLaunchTrampolineActivity::class.java).apply {
                putExtra(GameDisplayLaunchTrampolineActivity.EXTRA_LAUNCH_INTENT, gameIntent)
            }
        }

        return gameIntent
    }

    @JvmStatic
    fun createStartIntent(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean,
    ): Intent {
        return createStartIntent(parent, app, computer, managerBinder, withVDisplay, false, false)
    }

    @JvmStatic
    fun createStartIntent(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean,
        profilePreference: String,
        launchOptimizationJson: String?,
    ): Intent {
        return createStartIntent(
            parent,
            app,
            computer,
            managerBinder,
            withVDisplay,
            false,
            false,
            profilePreference,
            launchOptimizationJson,
        )
    }

    @JvmStatic
    fun createStartIntent(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean,
        displayModeExplicit: Boolean,
        watchOnly: Boolean,
        profilePreference: String = "auto",
        launchOptimizationJson: String? = null,
        mirrorDesktop: Boolean = false,
        forcePrivateAfterSteamClose: Boolean = false,
        streamWidth: Int = 0,
        streamHeight: Int = 0,
        streamFps: Float = 0f,
        streamMode: String = "",
        encoderBackend: String = "",
        faceButtonLayout: String = "",
    ): Intent {
        var serverCert: ByteArray? = null
        try {
            computer.serverCert?.let {
                serverCert = it.encoded
            }
        } catch (e: CertificateEncodingException) {
            e.printStackTrace()
        }

        val serverCommands = computer.serverCommands?.let { ArrayList(it) }
        val activeAddress = computer.activeAddress
            ?: throw IllegalStateException("No active address for " + computer.name)

        return createStartIntent(
            parent,
            app,
            activeAddress.address,
            activeAddress.port,
            computer.httpsPort,
            managerBinder.uniqueId,
            computer.uuid,
            computer.name,
            withVDisplay,
            displayModeExplicit,
            watchOnly,
            serverCommands,
            serverCert,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
            streamFps = streamFps,
            aiProfilePreference = profilePreference,
            launchOptimizationJson = launchOptimizationJson,
            mirrorDesktop = mirrorDesktop,
            forcePrivateAfterSteamClose = forcePrivateAfterSteamClose,
            streamMode = streamMode,
            encoderBackend = encoderBackend,
            faceButtonLayout = faceButtonLayout,
        )
    }

    @JvmStatic
    fun doStart(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean,
    ) {
        doStart(parent, app, computer, managerBinder, withVDisplay, false, false)
    }

    @JvmStatic
    fun doStart(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean,
        displayModeExplicit: Boolean,
        watchOnly: Boolean,
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            // On a Notice in the screen's right edge panel, where it can be read (audit X2).
            Dialog.displayDialog(
                parent,
                parent.getString(R.string.hosts_offline_title),
                parent.getString(R.string.hosts_offline_message),
                false,
            )
            return
        }

        parent.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit()
            .putInt("last_played_" + computer.uuid, app.appId)
            .apply()

        val intent = createStartIntent(
            parent,
            app,
            computer,
            managerBinder,
            withVDisplay,
            displayModeExplicit,
            watchOnly,
        )
        parent.startActivity(intent)
        NovaThemeManager.applyFadeTransition(parent)
    }

    @JvmStatic
    fun doStart(
        parent: Activity,
        app: NvApp,
        host: String,
        port: Int,
        httpsPort: Int,
        uniqueId: String,
        pcUuid: String,
        pcName: String,
        serverCommands: ArrayList<String>?,
        withVDisplay: Boolean,
        displayModeExplicit: Boolean,
        watchOnly: Boolean,
        serverCert: ByteArray?,
        streamWidth: Int,
        streamHeight: Int,
        streamFps: Float,
        aiProfilePreference: String = "auto",
        launchOptimizationJson: String? = null,
        mirrorDesktop: Boolean = false,
        forcePrivateAfterSteamClose: Boolean = false,
        streamMode: String = "",
        encoderBackend: String = "",
        faceButtonLayout: String = "",
    ) {
        parent.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit()
            .putInt("last_played_$pcUuid", app.appId)
            .apply()

        val intent = createStartIntent(
            parent,
            app,
            host,
            port,
            httpsPort,
            uniqueId,
            pcUuid,
            pcName,
            withVDisplay,
            displayModeExplicit,
            watchOnly,
            serverCommands,
            serverCert,
            streamWidth,
            streamHeight,
            streamFps,
            aiProfilePreference,
            launchOptimizationJson,
            mirrorDesktop,
            forcePrivateAfterSteamClose,
            streamMode = streamMode,
            encoderBackend = encoderBackend,
            faceButtonLayout = faceButtonLayout,
        )
        parent.startActivity(intent)
        NovaThemeManager.applyFadeTransition(parent)
    }

    @JvmStatic
    fun doWatch(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
    ) {
        doStart(parent, app, computer, managerBinder, false, false, true)
    }

    @JvmStatic
    fun doStart(
        parent: Activity,
        app: NvApp,
        host: String,
        port: Int,
        httpsPort: Int,
        uniqueId: String,
        pcUuid: String,
        pcName: String,
        serverCommands: ArrayList<String>?,
        withVDisplay: Boolean,
        serverCert: ByteArray?,
    ) {
        doStart(
            parent,
            app,
            host,
            port,
            httpsPort,
            uniqueId,
            pcUuid,
            pcName,
            serverCommands,
            withVDisplay,
            false,
            false,
            serverCert,
        )
    }

    @JvmStatic
    fun doStart(
        parent: Activity,
        app: NvApp,
        host: String,
        port: Int,
        httpsPort: Int,
        uniqueId: String,
        pcUuid: String,
        pcName: String,
        serverCommands: ArrayList<String>?,
        withVDisplay: Boolean,
        displayModeExplicit: Boolean,
        watchOnly: Boolean,
        serverCert: ByteArray?,
        aiProfilePreference: String = "auto",
        launchOptimizationJson: String? = null,
        mirrorDesktop: Boolean = false,
        forcePrivateAfterSteamClose: Boolean = false,
        streamMode: String = "",
        encoderBackend: String = "",
        faceButtonLayout: String = "",
    ) {
        parent.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit()
            .putInt("last_played_$pcUuid", app.appId)
            .apply()

        val intent = createStartIntent(
            parent,
            app,
            host,
            port,
            httpsPort,
            uniqueId,
            pcUuid,
            pcName,
            withVDisplay,
            displayModeExplicit,
            watchOnly,
            serverCommands,
            serverCert,
            aiProfilePreference = aiProfilePreference,
            launchOptimizationJson = launchOptimizationJson,
            mirrorDesktop = mirrorDesktop,
            forcePrivateAfterSteamClose = forcePrivateAfterSteamClose,
            streamMode = streamMode,
            encoderBackend = encoderBackend,
            faceButtonLayout = faceButtonLayout,
        )
        parent.startActivity(intent)
        NovaThemeManager.applyFadeTransition(parent)
    }

    /**
     * Tests whether this network lets Nova's streaming ports through, as a Busy page pushed in the
     * open panel (or a right-edge panel of its own) that becomes the result once the test ends, so
     * B from the result returns to whatever pushed it. Cancel leaves the page; the test itself
     * cannot be stopped, and an answer that arrives after the page has gone is dropped.
     */
    @JvmStatic
    fun doNetworkTest(parent: Activity) {
        val activity = parent as? ComponentActivity ?: return
        val surfaces = NovaSurfaces.of(activity)
        val resources = activity.resources
        var test: Job? = null
        lateinit var busy: NovaCommonPage.Busy
        busy = NovaCommonPage.Busy(
            key = NETWORK_TEST_PAGE_KEY,
            title = resources.getString(R.string.nettest_title_waiting),
            message = MutableStateFlow(resources.getString(R.string.nettest_text_waiting)),
            cancel = NovaAction(resources.getString(R.string.nova_panel_cancel)) {
                test?.cancel()
                if (surfaces.panel.top === busy && !surfaces.panel.pop()) surfaces.panel.close()
            },
        )
        surfaces.present(busy)
        test = activity.lifecycleScope.launch {
            val summary = networkTest(resources)
            if (surfaces.panel.top === busy) {
                surfaces.panel.replaceTop(
                    NovaCommonPage.Notice(
                        key = NETWORK_RESULT_PAGE_KEY,
                        title = resources.getString(R.string.nettest_title_done),
                        message = summary,
                        closeLabel = resources.getString(R.string.nova_panel_close),
                    ),
                )
            }
        }
    }

    /**
     * Which of Nova's streaming ports this network lets through, as the sentence the result page
     * shows. The test blocks for a few seconds, so it runs on the IO dispatcher.
     */
    suspend fun networkTest(resources: Resources): String = withContext(Dispatchers.IO) {
        val ret = MoonBridge.testClientConnectivity(
            CONNECTION_TEST_SERVER,
            443,
            MoonBridge.ML_PORT_FLAG_ALL,
        )
        when {
            ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE ->
                resources.getString(R.string.nettest_text_inconclusive)
            ret == 0 ->
                resources.getString(R.string.nettest_text_success)
            else ->
                resources.getString(R.string.nettest_text_failure) +
                    MoonBridge.stringifyPortFlags(ret, "\n")
        }
    }

    /**
     * Quits the running app, then runs [onComplete] or [onFail] on the main thread. Nothing floats
     * (audit X2): the caller's own list shows a quit that worked once it refreshes, as the running
     * mark goes, and a refusal is said on a Notice in the screen's right edge panel, where it can be
     * read. It floated as a Toast, as did a "Quitting" Toast before it.
     */
    @JvmStatic
    fun doQuit(
        parent: Activity,
        httpConn: NvHTTP,
        appName: String,
        onComplete: Runnable?,
        onFail: Runnable?,
    ) {
        doQuit(parent, httpConn, appName) { refusal ->
            if (refusal == null) {
                onComplete?.run()
            } else {
                showQuitRefused(parent, appName, refusal.reason)
                onFail?.run()
            }
        }
    }

    /** Why the host did not quit [appName], on a Notice page; [reason] may be blank. Any thread. */
    private fun showQuitRefused(parent: Activity, appName: String, reason: String?) {
        Dialog.displayDialog(
            parent,
            parent.getString(R.string.nova_library_end_failed),
            reason?.takeIf { it.isNotBlank() } ?: parent.getString(R.string.hosts_end_failed_message, appName),
            false,
        )
    }

    /**
     * Why the host did not quit a session: its [reason], in the host's own words where it gave some
     * and in Nova's plain words otherwise; whether the session was [startedElsewhere], by another
     * device, which this one can never quit however often it asks; and whether the game is
     * [stillClosing] after this device's own End, which asking again after a moment can finish.
     */
    data class QuitRefusal(
        val reason: String,
        val startedElsewhere: Boolean = false,
        val stillClosing: Boolean = false,
    )

    /**
     * Quits the running app as the doQuit above does, but floats nothing: [onResult] runs on the
     * main thread with null once the host has quit it, or with the host's refusal when it did not,
     * for the caller to say in place. The library strip says it where End was pressed, and offers
     * Try Again only where asking again could work.
     */
    fun doQuit(
        parent: Activity,
        httpConn: NvHTTP,
        appName: String,
        onResult: (refusal: QuitRefusal?) -> Unit,
    ) {
        Thread {
            val refusal = quitOnHost(parent, httpConn, appName)
            parent.runOnUiThread { onResult(refusal) }
        }.start()
    }

    /**
     * Asks the host to quit, on the calling thread: null once it has, or why it did not (XR3). The
     * reason is the host's own status message where it sent one, and Nova's plain words otherwise:
     * never "Host returned error: ... (Error code: N)". Only the host's own word that the session is
     * not this device's says another device started it. A 599 after this device's own cancel is
     * NvHTTP's, when the game still runs just after the host accepted the cancel: Polaris answers a
     * cancel before it has closed the game, so the game is still closing.
     */
    private fun quitOnHost(parent: Activity, httpConn: NvHTTP, appName: String): QuitRefusal? {
        val resources = parent.resources
        return try {
            val serverInfo = httpConn.getServerInfo(true)
            if (httpConn.getCurrentGameOwned(serverInfo) == false) {
                return QuitRefusal(resources.getString(R.string.nova_library_end_started_elsewhere), startedElsewhere = true)
            }
            val sessionToken = httpConn.getCurrentGameSessionToken(serverInfo)
            if (httpConn.quitApp(sessionToken)) {
                null
            } else {
                QuitRefusal(resources.getString(R.string.applist_quit_fail) + " " + appName)
            }
        } catch (e: HostHttpResponseException) {
            val hostWords = e.getHostStatusMessage()
            when {
                hostWords != null -> QuitRefusal(hostWords)
                e.getErrorCode() == 599 -> QuitRefusal(resources.getString(R.string.nova_library_end_still_closing), stillClosing = true)
                else -> QuitRefusal(resources.getString(R.string.nova_library_end_failed))
            }
        } catch (_: UnknownHostException) {
            QuitRefusal(resources.getString(R.string.error_unknown_host))
        } catch (_: FileNotFoundException) {
            QuitRefusal(resources.getString(R.string.error_404))
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            QuitRefusal(resources.getString(R.string.nova_library_end_failed))
        } catch (e: IOException) {
            e.printStackTrace()
            QuitRefusal(resources.getString(R.string.nova_library_end_unreachable))
        }
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        computer: ComputerDetails,
        app: NvApp,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        onComplete: Runnable?,
    ) {
        doQuit(parent, computer, app, managerBinder, onComplete, null)
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        computer: ComputerDetails,
        app: NvApp,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        onComplete: Runnable?,
        onFail: Runnable?,
    ) {
        try {
            val httpConn = NvHTTP(
                getCurrentAddressFromComputer(computer),
                computer.httpsPort,
                managerBinder.uniqueId,
                computer.serverCert,
                PlatformBinding.getCryptoProvider(parent),
            )
            doQuit(
                parent,
                httpConn,
                app.appName,
                onComplete,
                onFail,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            onFail?.run()
            showQuitRefused(parent, app.appName, e.message)
        }
    }
}
