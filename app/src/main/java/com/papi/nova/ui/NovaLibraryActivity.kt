package com.papi.nova.ui

import com.papi.nova.api.PolarisSpaces
import kotlinx.coroutines.isActive
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.activity.OnBackPressedCallback
import com.papi.nova.NovaActivity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.papi.nova.LimeLog
import com.papi.nova.Game
import com.papi.nova.NovaSessionEndSignal
import com.papi.nova.NovaSpaceRetrySignal
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisApiRejectedException
import com.papi.nova.api.PolarisGameJson
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRadius
import org.json.JSONObject
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.preferences.NovaAppVersion
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.StreamSettings
import com.papi.nova.utils.HelpLauncher
import com.papi.nova.utils.ServerHelper
import com.papi.nova.ui.SpaceParticleView
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaFocusMotionSpec
import com.papi.nova.ui.compose.novaFocusMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaShoulder
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaStateScreen
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.novaSurfaces
import com.papi.nova.ui.panel.rememberNovaSplitConfirmState
import java.util.Locale
import kotlin.math.abs

private data class LibraryLoadResult(
    val games: List<PolarisGame>,
    val settings: PolarisClientSettings?,
)

class NovaLibraryActivity : NovaActivity() {

    private lateinit var apiClient: PolarisApiClient
    private lateinit var streamHost: String
    private var streamHttpPort: Int = 47989
    private var streamHttpsPort: Int = 47984
    private var streamUniqueId: String? = null
    private var streamPcUuid: String? = null
    private var streamPcName: String = ""
    private var streamServerCommands: ArrayList<String>? = null
    private var streamServerCert: ByteArray? = null
    private var spacesExpected: Boolean = false
    private val gameDetailLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result -> onGameDetailResult(result) }

    private var allGames by mutableStateOf<List<PolarisGame>>(emptyList())
    private var filterState by mutableStateOf(NovaLibraryFilterState())
    private var searchQuery by mutableStateOf("")
    private var isInitialLoading by mutableStateOf(true)
    private var isRefreshing by mutableStateOf(false)
    private var loadErrorMessage by mutableStateOf<String?>(null)
    private var launchErrorMessage by mutableStateOf<String?>(null)
    private var clientSettings by mutableStateOf<PolarisClientSettings?>(null)
    private var activeSession by mutableStateOf<NovaLibraryActiveSessionUiState?>(null)
    /** Whether the last key came from a remote, so the hint bar names a remote's keys (C04). */
    private var lastInputRemote by mutableStateOf(false)
    /** An End asked for from the library, until the host answers or the session goes (XR3). */
    private var endStatus by mutableStateOf<NovaLibraryEndStatus?>(null)
    private var optionsState by mutableStateOf(NovaLibraryOptionsState())

    /** Polaris Sync's engine, running while its page is on the System panel's stack. */
    private var polarisSync: NovaPolarisSyncController? = null
    private var spaceFocusEpoch by mutableStateOf(0)
    private var spaceOpenPending by mutableStateOf(false)
    private var spaceOpenJob: Job? = null
    private var spaceOpenEpoch = 0
    private var spacesSnapshot by mutableStateOf<PolarisSpaces?>(null)
    private var spacesChecked by mutableStateOf(false)
    private var spacesError by mutableStateOf<String?>(null)
    private var chooseSpaceVisible by mutableStateOf(false)
    private var choosingSpace by mutableStateOf(false)
    private var spacesEpoch = 0
    private var spacesPoll: Job? = null
    private var libraryPollEpoch = 0
    private var libraryPoll: Job? = null
    // The blocking host calls behind an open run here, off the lifecycle scope, so the
    // screen can stop waiting for them at a deadline instead of for as long as OkHttp does.
    private val spacesIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastFocusedGameId by mutableStateOf<String?>(null)
    private var controllerHintChromeState by mutableStateOf(NovaControllerHintChromeState())
    private val activeSessionRefreshGate = NovaActiveSessionRefreshGate()
    private var activeSessionImmediateRefreshJob: Job? = null
    private var activeSessionRefreshJob: Job? = null
    private var controllerHintIdleJob: Job? = null
    private lateinit var artworkLibraryUpdateViewModel: NovaArtworkLibraryUpdateViewModel
    private var artworkLibraryUpdateState by mutableStateOf<NovaArtworkLibraryUpdateUiState>(
        NovaArtworkLibraryUpdateUiState.Idle
    )
    private var appliedTheme: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        appliedTheme = NovaThemeManager.getTheme(this)
        super.onCreate(savedInstanceState)

        streamHost = intent.getStringExtra(EXTRA_HOST).orEmpty()
        streamPcName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
        streamHttpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 47984)
        streamHttpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 47989)
        streamUniqueId = intent.getStringExtra(EXTRA_UNIQUE_ID)
        streamPcUuid = intent.getStringExtra(EXTRA_PC_UUID)
        streamServerCommands = intent.getStringArrayListExtra(EXTRA_SERVER_COMMANDS)
        streamServerCert = intent.getByteArrayExtra(EXTRA_SERVER_CERT)
        spacesExpected = intent.getBooleanExtra(EXTRA_SPACES_AVAILABLE, false)

        if (streamHost.isBlank()) {
            finish()
            return
        }

        apiClient = PolarisApiClient(this, streamHost, streamHttpsPort, streamServerCert)
        artworkLibraryUpdateViewModel = ViewModelProvider(
            this,
            NovaArtworkLibraryUpdateViewModel.Factory(
                context = applicationContext,
                serverAddress = streamHost,
                httpsPort = streamHttpsPort,
                serverCertDer = streamServerCert,
            ),
        )[NovaArtworkLibraryUpdateViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                artworkLibraryUpdateViewModel.snapshot.collect { snapshot ->
                    artworkLibraryUpdateState = snapshot.state
                    if (snapshot.committedArtwork.isNotEmpty()) {
                        allGames = artworkLibraryUpdateViewModel.mergeCommittedArtwork(allGames)
                    }
                }
            }
        }
        val libraryPreferences = libraryPreferences()
        optionsState = NovaLibraryPreferences.loadOptions(libraryPreferences)
        filterState = NovaLibraryPreferences.loadFilterState(libraryPreferences)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!dismissActiveLibraryOverlay()) {
                        finishWithTransition()
                    }
                }
            }
        )

        val content = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NovaComposeTheme {
                    // An End's status belongs to the session it was asked for: once that session
                    // has gone, or another has taken its place, the status goes with it.
                    LaunchedEffect(activeSession?.gameId) {
                        if (endStatus != null && endStatus?.gameId != activeSession?.gameId) endStatus = null
                    }
                    val model = rememberNovaLibraryUiModel(allGames, searchQuery, filterState, activeSession, optionsState)
                    NovaLibraryScreen(
                        serverName = streamPcName,
                        serverHost = streamHost,
                        model = model,
                        filterState = filterState,
                        searchQuery = searchQuery,
                        isInitialLoading = isInitialLoading,
                        isRefreshing = isRefreshing,
                        loadErrorMessage = loadErrorMessage,
                        launchErrorMessage = launchErrorMessage,
                        clientSettings = clientSettings,
                        activeSession = activeSession,
                        apiClient = apiClient,
                        controllerHintsVisible = controllerHintChromeState.visible,
                        restoreFocusGameId = lastFocusedGameId,
                        onBack = ::finishWithTransition,
                        onRefresh = { loadGames(forceRefresh = true) },
                        onResumeSession = ::resumeActiveSession,
                        onEndSession = ::endActiveSession,
                        onManageServer = ::openServerManagement,
                        onOpenDetail = ::showGameDetail,
                        onGameFocused = { lastFocusedGameId = it.id },
                        onOpenOptions = ::openLibraryOptions,
                        onOpenSystemMenu = ::openLibrarySystem,
                        onClearFilters = ::clearFilters
                    )
                }
            }
        }
        setContentView(content)
        refreshActiveSession(scheduleFollowUps = true)
        loadGames(forceRefresh = false)
    }

    /** Whether Library Options or System is on screen. Read live, so composition follows it. */
    private val libraryPanelOpen: Boolean
        get() = novaSurfaces.panel.isOpen

    private fun openLibraryOptions() {
        openLibraryPanel(LibraryPage.Options(getString(R.string.nova_library_options_title)))
    }

    private fun openLibrarySystem() {
        openLibraryPanel(LibraryPage.System(getString(R.string.nova_system_menu_title)))
    }

    /**
     * Opens Library Options (start edge) or System (end edge) in the library's panel window. When
     * the other is showing, the window swaps to this one in place, so the two are never on screen
     * together and nothing of one peeks behind the other.
     */
    private fun openLibraryPanel(root: LibraryPage) {
        val surfaces = novaSurfaces
        if (surfaces.panel.swapToLibraryPeer(root)) return
        surfaces.open(
            root = root,
            edge = root.edge,
            hints = listOf(
                NovaControllerHint(
                    key = getString(R.string.nova_controller_hint_lb_rb),
                    label = getString(R.string.nova_controller_hint_library_system),
                ),
            ),
            onShoulder = ::onLibraryPanelShoulder,
        ) { page -> LibraryPanelPage(page) }
    }

    /** L1 and R1 swap between the two peers; the Space screen has no grid for Options to arrange. */
    private fun onLibraryPanelShoulder(side: NovaShoulder) {
        when (side) {
            NovaShoulder.Left -> if (NovaSpaceUiState.singleSpace(allGames) == null) openLibraryOptions()
            NovaShoulder.Right -> openLibrarySystem()
        }
    }

    private fun startArtworkLibraryUpdate(gameIds: List<String>? = null) {
        val selectedGames = if (gameIds == null) {
            allGames
        } else {
            val requested = gameIds.toSet()
            allGames.filter { it.id in requested }
        }
        artworkLibraryUpdateViewModel.start(selectedGames)
    }

    private fun cancelArtworkLibraryUpdate() {
        artworkLibraryUpdateViewModel.cancel()
    }

    /** The Space chooser is drawn in the library itself; B leaves it before it leaves the library. */
    private fun dismissActiveLibraryOverlay(): Boolean {
        return when {
            chooseSpaceVisible -> { chooseSpaceVisible = false; spaceFocusEpoch++; true }
            else -> false
        }
    }

    private fun libraryPreferences(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(this)

    private fun updateLibraryOptions(
        transform: (NovaLibraryOptionsState) -> NovaLibraryOptionsState
    ) {
        val nextState = transform(optionsState)
        optionsState = nextState
        NovaLibraryPreferences.persistOptions(libraryPreferences(), nextState)
    }

    private fun selectLibraryLayoutMode(layoutMode: NovaLibraryLayoutMode) {
        updateLibraryOptions { it.copy(layoutMode = layoutMode) }
        revealControllerHints(NovaControllerHintChromeEvent.LAYOUT_CHANGED)
    }

    private fun updateLibraryFilterState(nextState: NovaLibraryFilterState) {
        val normalized = NovaLibraryPreferences.normalizeFilterState(nextState)
        filterState = normalized
        NovaLibraryPreferences.persistFilterState(libraryPreferences(), normalized)
    }

    override fun onResume() {
        super.onResume()
        spaceFocusEpoch++
        startSpacesPolling()
        if (NovaSpaceRetrySignal.consume(this, streamPcUuid, streamHost)) retrySpaceOpenWhenChecked()
        if (recreateForThemeChangeIfNeeded()) return
        startLibraryPolling()
        revealControllerHints(NovaControllerHintChromeEvent.EXPLICIT_REVEAL)
        if (
            ::apiClient.isInitialized &&
            activeSessionRefreshGate.shouldRefreshOnResume(isInitialLoading)
        ) {
            refreshActiveSession(scheduleFollowUps = true)
        }
    }

    override fun onPause() {
        cancelPendingSpaceOpen()
        spacesEpoch++; spacesPoll?.cancel(); spacesPoll = null
        libraryPollEpoch++; libraryPoll?.cancel(); libraryPoll = null
        super.onPause()
    }

    private fun cancelPendingSpaceOpen() {
        // Retire the user's open action even if its blocking HTTP call completes
        // after returning to Library or choosing another Space.
        spaceOpenEpoch++
        spaceOpenJob?.cancel()
        spaceOpenJob = null
        spaceOpenPending = false
    }

    /**
     * Re-reads the host's library while it is on screen, at the pace [NovaLibraryLiveRefresh]
     * sets, so a change made on the host shows without pull-to-refresh. Leaving the library
     * stops it; coming back reads at once.
     */
    private fun startLibraryPolling() {
        libraryPoll?.cancel()
        if (!::apiClient.isInitialized || !::artworkLibraryUpdateViewModel.isInitialized) return
        val epoch = ++libraryPollEpoch
        libraryPoll = lifecycleScope.launch {
            var failures = 0
            var first = true
            while (isActive) {
                delay(NovaLibraryLiveRefresh.delayBeforeRead(first, failures))
                first = false
                if (epoch != libraryPollEpoch) return@launch
                if (!NovaLibraryLiveRefresh.mayRead(isInitialLoading, isRefreshing, choosingSpace || spaceOpenPending)) continue
                failures = if (readLibraryQuietly(epoch, failures)) 0 else failures + 1
            }
        }
    }

    /**
     * One background read, published through the same refresh token a pull-to-refresh uses, so
     * artwork committed from Artwork Studio meanwhile is kept and a newer load wins. It never
     * clears decoded covers and changes the screen only when the library differs. A read that
     * succeeds after a failed load also clears that failure and fetches the settings it missed.
     * @return whether the host answered.
     */
    private suspend fun readLibraryQuietly(epoch: Int, failures: Int): Boolean {
        val token = artworkLibraryUpdateViewModel.beginRefresh()
        val settingsMissing = clientSettings == null
        val (games, settings) = try {
            withContext(Dispatchers.IO) {
                val games = apiClient.getAllGames()
                val settings = if (!settingsMissing) null else try {
                    apiClient.getClientSettings()
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Failed to load client settings: ${e.message}")
                    null
                }
                games to settings
            }
        } catch (e: CancellationException) {
            artworkLibraryUpdateViewModel.discardRefresh(token)
            throw e
        } catch (e: Exception) {
            artworkLibraryUpdateViewModel.discardRefresh(token)
            if (failures == 0) LimeLog.warning("Nova: Background library read failed: ${e.message}")
            return false
        }
        // A load that started while this read was out owns the library.
        if (epoch != libraryPollEpoch ||
            !NovaLibraryLiveRefresh.mayRead(isInitialLoading, isRefreshing, choosingSpace || spaceOpenPending)
        ) {
            artworkLibraryUpdateViewModel.discardRefresh(token)
            return true
        }
        artworkLibraryUpdateViewModel.publishRefresh(token, games) { read ->
            NovaLibraryLiveRefresh.changedLibrary(allGames, read)?.let { allGames = it }
            if (loadErrorMessage != null) loadErrorMessage = null
            if (settings != null) clientSettings = settings
        }
        return true
    }

    /**
     * Watches the host's Spaces at the pace the answer deserves.
     *
     * A host that answered 404 or enabled:false has nothing to watch, so the poll stops for
     * the session; only when the host card promised Spaces does it re-probe slowly, since a
     * restarting host answers 404 for a while. A device with no Space listed is watched
     * slowly too, so the state clears itself once one is assigned in Polaris. An open chooser
     * refreshes at once and then every 5 s; a closed one every 15 s. Failures back off,
     * doubling to a minute, and recover on the first good answer. Before this, every host
     * was polled every 5 s for the life of the library, Sunshine included.
     */
    private fun startSpacesPolling(delayFirst: Boolean = false) {
        spacesPoll?.cancel()
        if (choosingSpace || spaceOpenPending || !::apiClient.isInitialized) return
        val epoch = ++spacesEpoch
        spacesPoll = lifecycleScope.launch {
            var failures = 0
            var first = true
            while (isActive) {
                val wait = when {
                    first && !delayFirst -> 0L
                    first -> SPACES_POLL_OPEN_MS
                    failures > 0 -> (SPACES_POLL_CLOSED_MS shl (failures - 1).coerceAtMost(3)).coerceAtMost(SPACES_POLL_MAX_MS)
                    else -> spacesPollInterval() ?: return@launch
                }
                first = false
                if (wait > 0L) delay(wait)
                failures = if (refreshSpaces(epoch)) 0 else failures + 1
            }
        }
    }

    /** The idle interval for the current answer, or null when there is nothing left to watch. */
    private fun spacesPollInterval(): Long? {
        val snapshot = spacesSnapshot
        return when {
            snapshot == null || !snapshot.enabled -> if (spacesExpected) SPACES_POLL_SLOW_MS else null
            snapshot.spaces.isEmpty() -> SPACES_POLL_SLOW_MS
            chooseSpaceVisible || NovaSpaceUiState.singleSpace(allGames) != null -> SPACES_POLL_OPEN_MS
            else -> SPACES_POLL_CLOSED_MS
        }
    }

    private suspend fun refreshSpaces(epoch: Int): Boolean {
        return try {
            val next = withContext(Dispatchers.IO) { apiClient.getSpaces() }
            if (epoch != spacesEpoch) false else {
                val previous = spacesSnapshot?.selectedId
                spacesSnapshot = next; spacesChecked = true; spacesError = null
                if (previous != null && previous != next?.selectedId) replaceLibraryForSpace()
                true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (epoch == spacesEpoch) { spacesChecked = false; spacesError = getString(R.string.nova_space_check_failed) }
            false
        }
    }

    private fun showSpaceChooser() {
        cancelPendingSpaceOpen()
        chooseSpaceVisible = true
        // The chooser shows live status, so it opens on a fresh answer rather than one up
        // to 15 s old.
        startSpacesPolling()
    }

    private fun chooseSpace(id: String) {
        val snapshot = spacesSnapshot ?: return
        // A stale snapshot is safe to choose from: the host checks previous_space_id and
        // refuses with its own sentence if the Space moved under us.
        if (choosingSpace || !snapshot.canSwitch ||
            (snapshot.spaces.none { it.id == id } && !(id == "desktop" && snapshot.desktopAllowed))) return
        if (id == snapshot.selectedId) { chooseSpaceVisible = false; spaceFocusEpoch++; return }
        choosingSpace = true; spacesEpoch++; spacesPoll?.cancel()
        lifecycleScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { apiClient.selectSpace(id, snapshot.selectedId) }
                spacesSnapshot = next; spacesChecked = true; spacesError = null
                launchErrorMessage = null; activeSession = null; chooseSpaceVisible = false; spaceFocusEpoch++
                replaceLibraryForSpace()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // The host's own sentence when it sent one (the Space changed under us, a
                // stream is still up); otherwise what happens next, since the refresh that
                // follows is automatic and there is no button for it.
                spacesError = (e as? PolarisApiRejectedException)?.rejection?.error?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.nova_space_change_failed)
            } finally {
                choosingSpace = false
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) startSpacesPolling()
            }
        }
    }

    /**
     * The Space changed under the library. The old games go, the saved filters are left
     * alone (only the in-memory ones reset, so the next visit keeps what the person chose),
     * and the loading grid shows until the new library lands rather than "No games yet".
     */
    private fun replaceLibraryForSpace() {
        allGames = emptyList()
        resetFiltersInMemory()
        loadGames(forceRefresh = true, replaceLibrary = true)
    }

    private fun resetFiltersInMemory() {
        filterState = NovaLibraryFilterState()
        searchQuery = ""
    }

    /** Whether the device's current Space can be opened or resumed, by the host's account. */
    private fun spaceOpenable(snapshot: PolarisSpaces): Boolean =
        snapshot.available && snapshot.selected?.openable == true

    /** The person gave up on a check the host has not answered; polling resumes. */
    private fun cancelSpaceOpen() {
        cancelPendingSpaceOpen()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) startSpacesPolling()
    }

    /** A failed Space stream asked to try again: open the Space once the check after returning lands. */
    private fun retrySpaceOpenWhenChecked() {
        lifecycleScope.launch {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (!spacesChecked && System.nanoTime() < deadline) delay(200)
            NovaSpaceUiState.singleSpace(allGames)?.let { openSpace(it) }
        }
    }

    private fun recreateForThemeChangeIfNeeded(): Boolean {
        val currentTheme = NovaThemeManager.getTheme(this)
        if (appliedTheme == currentTheme) return false
        appliedTheme = currentTheme
        recreate()
        return true
    }

    // B never arrives here: the key gate turns it into Back. While a panel is open its window
    // has the keys, so these open panels only from the library itself.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (chooseSpaceVisible) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> {
                // The Space screen has no grid for Library Options to filter or lay out.
                if (NovaSpaceUiState.singleSpace(allGames) == null) openLibraryOptions()
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> {
                openLibrarySystem()
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                val space = NovaSpaceUiState.singleSpace(allGames)
                if (space != null && !libraryPanelOpen) showDetail(space, spaceSettings = true)
                else openLibraryOptions()
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> if (NovaSpaceUiState.singleSpace(allGames) != null) true else cycleLibraryLayoutMode()
            KeyEvent.KEYCODE_HELP,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_F1 -> {
                revealControllerHints(NovaControllerHintChromeEvent.HELP_REQUESTED)
                true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                revealControllerHints(NovaControllerHintChromeEvent.EXPLICIT_REVEAL)
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> {
                openLibrarySystem()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The hint bar names the keys of whatever was pressed last: a remote has no X or L1.
        // A phone's own Back gesture comes from a virtual device, which is neither.
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            event.device?.isVirtual != true &&
            event.keyCode in CONTROLLER_BROWSE_KEYS + REMOTE_ANSWER_KEYS
        ) {
            lastInputRemote = com.papi.nova.ui.panel.NovaRemoteInput.isRemote(event.device?.sources ?: event.source)
        }
        val handled = super.dispatchKeyEvent(event)
        if (
            handled &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in CONTROLLER_BROWSE_KEYS
        ) {
            registerSuccessfulLibraryInput(NovaControllerHintChromeEvent.CONTROLLER_INPUT)
        }
        return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val hasBrowseIntent =
            isJoystick &&
                event.action == MotionEvent.ACTION_MOVE &&
                event.hasControllerBrowseMotion()
        val handled = super.dispatchGenericMotionEvent(event)
        if (handled && hasBrowseIntent) {
            registerSuccessfulLibraryInput(NovaControllerHintChromeEvent.CONTROLLER_INPUT)
        }
        return handled
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (handled && event.actionMasked == MotionEvent.ACTION_UP) {
            registerSuccessfulLibraryInput(NovaControllerHintChromeEvent.TOUCH_INPUT)
        }
        return handled
    }

    private fun MotionEvent.hasControllerBrowseMotion(): Boolean {
        return CONTROLLER_BROWSE_AXES.any { axis ->
            abs(getAxisValue(axis)) >= CONTROLLER_AXIS_INTENT_THRESHOLD
        }
    }

    private fun registerSuccessfulLibraryInput(event: NovaControllerHintChromeEvent) {
        if (libraryPanelOpen) return
        controllerHintChromeState = controllerHintChromeState.reduce(event)
        controllerHintIdleJob?.cancel()
        controllerHintIdleJob = lifecycleScope.launch {
            delay(CONTROLLER_HINT_IDLE_REVEAL_MS)
            controllerHintChromeState = controllerHintChromeState.reduce(
                NovaControllerHintChromeEvent.IDLE
            )
        }
    }

    private fun revealControllerHints(event: NovaControllerHintChromeEvent) {
        controllerHintIdleJob?.cancel()
        controllerHintIdleJob = null
        controllerHintChromeState = controllerHintChromeState.reduce(event)
    }

    private fun cycleLibraryLayoutMode(): Boolean {
        if (libraryPanelOpen) {
            return false
        }
        val nextMode = optionsState.layoutMode.next()
        // The grid changing is the answer; a floating "Layout: Grid" snackbar broke R6.
        selectLibraryLayoutMode(nextMode)
        return true
    }

    override fun onDestroy() {
        spacesIo.cancel()
        polarisSync?.close()
        super.onDestroy()
    }

    override fun onStop() {
        activeSessionRefreshGate.invalidateForStop()
        activeSessionImmediateRefreshJob?.cancel()
        activeSessionImmediateRefreshJob = null
        activeSessionRefreshJob?.cancel()
        activeSessionRefreshJob = null
        controllerHintIdleJob?.cancel()
        controllerHintIdleJob = null
        super.onStop()
    }

    private fun loadGames(forceRefresh: Boolean, replaceLibrary: Boolean = false) {
        if (forceRefresh) refreshActiveSession(scheduleFollowUps = true)
        // A refresh the person asked for also re-asks the host about Spaces at once.
        if (forceRefresh && !replaceLibrary) startSpacesPolling()
        // A Space switch replaces the whole library, so it shows the loading grid rather
        // than a pull-to-refresh spinner over "No games yet".
        if (forceRefresh && !replaceLibrary) {
            isRefreshing = true
        } else {
            isInitialLoading = true
        }
        loadErrorMessage = null
        launchErrorMessage = null
        val artworkRefreshToken = artworkLibraryUpdateViewModel.beginRefresh()

        lifecycleScope.launch {
            var ownsVisibleRefreshState = false
            try {
                val result = withContext(Dispatchers.IO) {
                    val games = apiClient.getAllGames()
                    val settings = try {
                        apiClient.getClientSettings()
                    } catch (e: Exception) {
                        LimeLog.warning("Nova: Failed to load client settings: ${e.message}")
                        null
                    }
                    LibraryLoadResult(
                        games = games,
                        settings = settings,
                    )
                }
                // Artwork keys carry the Space, so a switch keeps what is already decoded.
                if (forceRefresh && !replaceLibrary) apiClient.clearCoverCache()
                val published = artworkLibraryUpdateViewModel.publishRefresh(
                    token = artworkRefreshToken,
                    games = result.games,
                ) { publishedGames ->
                    ownsVisibleRefreshState = true
                    allGames = publishedGames
                    clientSettings = result.settings
                    loadErrorMessage = null
                    LimeLog.info("Nova: Loaded ${allGames.size} games")
                }
                if (!published) return@launch
            } catch (e: CancellationException) {
                ownsVisibleRefreshState =
                    artworkLibraryUpdateViewModel.discardRefresh(artworkRefreshToken) ||
                    ownsVisibleRefreshState
                throw e
            } catch (e: Exception) {
                ownsVisibleRefreshState =
                    artworkLibraryUpdateViewModel.discardRefresh(artworkRefreshToken) ||
                    ownsVisibleRefreshState
                if (ownsVisibleRefreshState) {
                    val message = e.localizedMessage ?: e.javaClass.simpleName
                    loadErrorMessage = message
                    LimeLog.severe("Nova: Failed to load games: ${e.message}")
                    NovaSnackbar.showError(this@NovaLibraryActivity, message)
                }
            } finally {
                if (ownsVisibleRefreshState) {
                    isInitialLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    private fun beginActiveSessionRefresh(): Long {
        val generation = activeSessionRefreshGate.begin()
        activeSessionImmediateRefreshJob?.cancel()
        activeSessionImmediateRefreshJob = null
        activeSessionRefreshJob?.cancel()
        activeSessionRefreshJob = null
        return generation
    }

    private fun refreshActiveSession(scheduleFollowUps: Boolean = false) {
        val generation = beginActiveSessionRefresh()
        if (consumeLocalSessionEndSignal()) {
            activeSession = null
            if (scheduleFollowUps) {
                scheduleActiveSessionFollowUpRefreshes(
                    clearOnly = true,
                    generation = generation,
                )
            }
            return
        }

        lateinit var launched: Job
        launched = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val refreshed = try {
                withContext(Dispatchers.IO) { queryActiveSession() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeSessionRefreshGate.isCurrent(generation)) {
                    LimeLog.warning("Nova: Failed to refresh active session: ${e.message}")
                }
                return@launch
            }
            val published = activeSessionRefreshGate.publishIfCurrent(generation) {
                activeSession = refreshed
            }
            if (published && scheduleFollowUps && refreshed != null) {
                scheduleActiveSessionFollowUpRefreshes(
                    clearOnly = false,
                    generation = generation,
                )
            }
        }
        activeSessionImmediateRefreshJob = launched
        launched.invokeOnCompletion {
            if (activeSessionImmediateRefreshJob === launched) {
                activeSessionImmediateRefreshJob = null
            }
        }
        launched.start()
    }

    private fun scheduleActiveSessionFollowUpRefreshes(
        clearOnly: Boolean = false,
        generation: Long,
    ) {
        activeSessionRefreshJob?.cancel()
        if (!activeSessionRefreshGate.isCurrent(generation)) return
        lateinit var launched: Job
        launched = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            for (delayMillis in ACTIVE_SESSION_RESUME_REFRESH_DELAYS_MS) {
                delay(delayMillis)
                val refreshed = try {
                    withContext(Dispatchers.IO) { queryActiveSession() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (activeSessionRefreshGate.isCurrent(generation)) {
                        LimeLog.warning(
                            "Nova: Failed to refresh active session after stream return: ${e.message}"
                        )
                    }
                    continue
                }
                if (clearOnly && refreshed != null) {
                    if (!activeSessionRefreshGate.isCurrent(generation)) return@launch
                    continue
                }
                if (!activeSessionRefreshGate.publishIfCurrent(generation) {
                        activeSession = refreshed
                    }
                ) {
                    return@launch
                }
                if (refreshed == null) return@launch
            }
        }
        activeSessionRefreshJob = launched
        launched.invokeOnCompletion {
            if (activeSessionRefreshJob === launched) {
                activeSessionRefreshJob = null
            }
        }
        launched.start()
    }

    private fun consumeLocalSessionEndSignal(): Boolean {
        val consumed = NovaSessionEndSignal.consume(this, streamPcUuid, streamHost)
        if (consumed) {
            LimeLog.info("Nova: Clearing active session card after local End request")
        }
        return consumed
    }

    private fun queryActiveSessionAsync(onResult: (NovaLibraryActiveSessionUiState?) -> Unit) {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { runCatching { queryActiveSession() }.getOrNull() }
            onResult(session)
        }
    }

    private fun queryActiveSession(): NovaLibraryActiveSessionUiState? {
        return NovaLibraryActiveSessionUiState.from(apiClient.getSessionStatus())
    }

    private fun handlePrimaryFilter(filter: NovaLibraryPrimaryFilter) {
        when (filter) {
            NovaLibraryPrimaryFilter.ALL -> updateLibraryFilterState(NovaLibraryFilterState())
            NovaLibraryPrimaryFilter.RECENT -> updateLibraryFilterState(
                NovaLibraryFilterState(primary = filter)
            )
            NovaLibraryPrimaryFilter.HDR -> updateLibraryFilterState(
                NovaLibraryFilterState(primary = filter)
            )
            // Chosen on their own pages, never stepped to in the filter row.
            NovaLibraryPrimaryFilter.SOURCES,
            NovaLibraryPrimaryFilter.MORE -> Unit
        }
    }

    private fun applySourceFilter(source: String?) {
        updateLibraryFilterState(
            if (source == null) {
                NovaLibraryFilterState()
            } else {
                NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.SOURCES, source = source)
            }
        )
    }

    private fun applyCategoryFilter(category: String) {
        updateLibraryFilterState(
            NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.MORE, category = category)
        )
    }

    private fun applyGenreFilter(genre: String) {
        updateLibraryFilterState(
            NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.MORE, genre = genre)
        )
    }

    private fun clearFilters() {
        updateLibraryFilterState(NovaLibraryFilterState())
        searchQuery = ""
    }

    private fun clearSearch() {
        searchQuery = ""
    }

    private fun hasClearableFilters(
        searchQuery: String,
        filterState: NovaLibraryFilterState
    ): Boolean = searchQuery.isNotBlank() || filterState.hasActiveConstraint

    private fun showGameDetail(game: PolarisGame) = showDetail(game)

    private fun openSpace(game: PolarisGame) {
        if (!NovaSpaceUiState.isSpace(game) || spaceOpenPending || choosingSpace || !spacesChecked ||
            (spacesSnapshot != null && !spaceOpenable(spacesSnapshot!!))) return
        val expected = spacesSnapshot?.selectedId
        spaceOpenPending = true; spacesEpoch++; spacesPoll?.cancel()
        val openEpoch = ++spaceOpenEpoch
        spaceOpenJob = lifecycleScope.launch {
            // The host calls block their thread until OkHttp gives up, which can be most of a
            // minute. They run on their own scope and the screen waits with a deadline; a
            // late answer finds its epoch retired and changes nothing. Null means the
            // deadline passed; a failure inside is carried as a Result so it cannot be
            // mistaken for the 404 that getSpaces reports as null.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SPACE_OPEN_CHECK_TIMEOUT_MS)
            suspend fun <T> withinDeadline(work: () -> T): Result<T>? {
                val pending = spacesIo.async { runCatching(work) }
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1L)
                return withTimeoutOrNull(remaining) { pending.await() }
            }
            try {
                val checked = withinDeadline { apiClient.getSpaces() }
                if (openEpoch != spaceOpenEpoch) return@launch
                if (checked == null) {
                    launchErrorMessage = getString(R.string.nova_space_check_timed_out)
                    return@launch
                }
                val fresh = checked.getOrThrow()
                spacesSnapshot = fresh; spacesChecked = true; spacesError = null
                if (fresh?.selectedId != expected || (fresh != null && !spaceOpenable(fresh))) {
                    launchErrorMessage = getString(R.string.nova_space_status_changed)
                    return@launch
                }
                val sessionCheck = withinDeadline { queryActiveSession() }
                if (openEpoch != spaceOpenEpoch || isFinishing || isDestroyed ||
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
                if (sessionCheck == null) {
                    launchErrorMessage = getString(R.string.nova_space_check_timed_out)
                    return@launch
                }
                val session = sessionCheck.getOrThrow()
                when (NovaSpaceUiState.availability(game, session)) {
                    NovaSpaceUiState.Availability.RESUMABLE -> resumeActiveSession(requireNotNull(session))
                    NovaSpaceUiState.Availability.IN_USE -> {
                        activeSession = session; launchErrorMessage = getString(R.string.nova_space_in_use)
                    }
                    NovaSpaceUiState.Availability.AVAILABLE -> showDetail(game, openSpace = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A blocking call can fail after cancellation. Ignore its error
                // just as we ignore a successful result from a retired request.
                if (openEpoch == spaceOpenEpoch) {
                    spacesChecked = false; spacesError = getString(R.string.nova_space_check_failed)
                }
            } finally {
                // A retired request must not clear a newer action's pending state
                // or restart polling over that action.
                if (openEpoch == spaceOpenEpoch) {
                    spaceOpenJob = null
                    spaceOpenPending = false
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) startSpacesPolling()
                }
            }
        }
    }

    private fun showDetail(game: PolarisGame, openSpace: Boolean = false, spaceSettings: Boolean = false) {
        launchErrorMessage = null
        val preferences = PreferenceConfiguration.readPreferences(this)
        gameDetailLauncher.launch(
            NovaGameDetailActivity.newIntent(
                context = this,
                game = if (com.papi.nova.manager.WorkerLaunchContract.isLegacyProfileApp(game.id)) spacesSnapshot?.selected?.let { game.copy(name = it.name) } ?: game else game,
                host = streamHost,
                httpsPort = streamHttpsPort,
                serverCert = streamServerCert,
                defaultToVirtualDisplay = preferences.useVirtualDisplay,
                // Carried so the host settings opened from the detail window are the same
                // surface, with the same auto-match state, as the one in the System drawer.
                serverName = streamPcName.ifBlank { streamHost },
                serverUuid = streamPcUuid,
            ).putExtra(NovaGameDetailActivity.EXTRA_OPEN_SPACE, openSpace)
                .putExtra(NovaGameDetailActivity.EXTRA_SPACE_SETTINGS, spaceSettings)
                // A host without Spaces gets no Play Setup destination fan-out.
                .putExtra(NovaGameDetailActivity.EXTRA_SPACES_ENABLED, spacesSnapshot?.enabled == true),
        )
        NovaThemeManager.applyForwardTransition(this)
    }

    /**
     * The detail window returns the launch it chose rather than performing it, so the
     * stream starts from the library after that window is gone.
     */
    private fun onGameDetailResult(result: androidx.activity.result.ActivityResult) {
        val data = result.data ?: return
        data.getStringExtra(NovaGameDetailActivity.EXTRA_RESULT_GAME)
            ?.let { PolarisGameJson.decode(it) }
            ?.let { updated -> allGames = allGames.map { if (it.id == updated.id) updated else it } }
        if (data.getBooleanExtra(NovaGameDetailActivity.EXTRA_RESULT_MANAGE_SERVER, false)) {
            openServerDisplaySettings()
        }
        val requestedSpaceJson = data.getStringExtra(NovaGameDetailActivity.EXTRA_RESULT_SPACE)
        val requestedSpace = requestedSpaceJson?.let(PolarisGameJson::decode)
        fun sessionMatchesRequest(session: NovaLibraryActiveSessionUiState): Boolean =
            requestedSpaceJson == null || (requestedSpace != null &&
                NovaSpaceUiState.matchingSession(requestedSpace, session)?.ownedByClient == true)
        when (data.getStringExtra(NovaGameDetailActivity.EXTRA_RESULT_SESSION)) {
            // The window saw the session but cannot act on it: resuming and ending both
            // need stream credentials that live here.
            NovaGameDetailActivity.RESULT_SESSION_RESUME ->
                queryActiveSessionAsync { session -> session?.takeIf(::sessionMatchesRequest)?.let { resumeActiveSession(it) } }
            NovaGameDetailActivity.RESULT_SESSION_END ->
                queryActiveSessionAsync { session -> session?.takeIf(::sessionMatchesRequest)?.let { endActiveSession(it) } }
        }

        val launch = data.getStringExtra(NovaGameDetailActivity.EXTRA_RESULT_LAUNCH) ?: return
        val request = try {
            JSONObject(launch)
        } catch (e: Exception) {
            LimeLog.warning("Nova: Unreadable launch result from the game detail window: ${e.message}")
            return
        }
        val selected = data.getStringExtra(NovaGameDetailActivity.EXTRA_RESULT_LAUNCH_GAME)
            ?.let { PolarisGameJson.decode(it) }
            ?: return
        launchGame(
            game = selected,
            withVirtualDisplay = request.optBoolean(NovaGameDetailActivity.RESULT_KEY_VIRTUAL_DISPLAY),
            mirrorDesktop = request.optBoolean(NovaGameDetailActivity.RESULT_KEY_MIRROR_DESKTOP),
            forcePrivateAfterSteamClose = request.optBoolean(NovaGameDetailActivity.RESULT_KEY_FORCE_PRIVATE),
            profilePreference = request.optString(NovaGameDetailActivity.RESULT_KEY_PROFILE_PREFERENCE, "auto"),
            resolvedMode = request.optString(NovaGameDetailActivity.RESULT_KEY_STREAM_MODE, ""),
            encoderBackend = request.optString(NovaGameDetailActivity.RESULT_KEY_ENCODER_BACKEND, ""),
            presentationMode = request.optString(NovaGameDetailActivity.RESULT_KEY_PRESENTATION_MODE, ""),
            preflightOptimization = request.optJSONObject(NovaGameDetailActivity.RESULT_KEY_PREFLIGHT),
        )
    }

    private fun launchGame(
        game: PolarisGame,
        withVirtualDisplay: Boolean,
        mirrorDesktop: Boolean = false,
        resolvedMode: String = "",
        presentationMode: String = "",
        forcePrivateAfterSteamClose: Boolean = false,
        profilePreference: String = "auto",
        encoderBackend: String = "",
        preflightOptimization: org.json.JSONObject? = null
    ) {
        if (game.appId <= 0) {
            val message = getString(R.string.nova_library_launch_missing_id)
            launchErrorMessage = message
            NovaSnackbar.showError(this, message)
            return
        }
        val uniqueId = streamUniqueId
        val pcUuid = streamPcUuid
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || pcUuid.isNullOrBlank() || serverCert == null) {
            val message = getString(R.string.nova_library_launch_missing_session)
            launchErrorMessage = message
            NovaSnackbar.showError(this, message)
            LimeLog.warning("Nova: Cannot launch from library; missing uniqueId, pcUuid, or server cert")
            return
        }
        launchErrorMessage = null

        val launchUsesVirtualDisplay = withVirtualDisplay
        val launchMirrorsDesktop = mirrorDesktop
        val launchMode = resolvedMode
        val presentedLaunchMode = launchMode.ifBlank { presentationMode }
        val resolvedLaunchModeLabel = when (PolarisGame.normalizeLaunchMode(presentedLaunchMode)) {
            PolarisGame.MODE_HEADLESS_STREAM -> getString(R.string.nova_library_launch_headless)
            PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> getString(R.string.nova_library_launch_virtual_display)
            PolarisGame.MODE_DESKTOP_DISPLAY -> getString(R.string.nova_library_launch_desktop_display)
            PolarisGame.MODE_DESKTOP_TAKEOVER -> getString(R.string.nova_library_launch_desktop_takeover)
            PolarisGame.MODE_WINDOWED_STREAM -> getString(R.string.nova_library_launch_gpu_native_test)
            PolarisGame.MODE_GAMESCOPE_STREAM -> getString(R.string.nova_library_launch_gamescope)
            PolarisGame.MODE_HEADLESS_DONGLE -> getString(R.string.nova_library_launch_dongle)
            else -> ""
        }

        NovaSnackbar.show(
            this,
            if (NovaSpaceUiState.isSpace(game)) getString(R.string.nova_space_opening) else getString(
                R.string.nova_library_launching_mode,
                game.name,
                when {
                    launchUsesVirtualDisplay -> getString(R.string.nova_library_launch_virtual_display)
                    launchMirrorsDesktop -> getString(R.string.nova_desktop_steam_mirror_desktop)
                    forcePrivateAfterSteamClose -> getString(R.string.nova_desktop_steam_force_private)
                    resolvedLaunchModeLabel.isNotBlank() -> resolvedLaunchModeLabel
                    else -> getString(R.string.nova_library_launch_headless)
                }
            )
        )

        lifecycleScope.launch {
            try {
                val mangoHudSynced = withContext(Dispatchers.IO) {
                    apiClient.setMangoHud(game.id, game.mangohud)
                }
                if (!mangoHudSynced) {
                    LimeLog.warning("Nova: MangoHUD launch state sync failed; continuing launch")
                }
                val preferences = com.papi.nova.preferences.PreferenceConfiguration.readPreferences(this@NovaLibraryActivity)
                val metered = StreamSyncManager.isMeteredNetwork(this@NovaLibraryActivity)
                val requestedBitrateKbps = if (metered) preferences.meteredBitrate else preferences.bitrate
                val launchResolution = StreamSyncManager.resolveAutoSafeResolution(
                    preferences.width,
                    preferences.height,
                    preflightOptimization
                )
                val launchFps = StreamSyncManager.resolveAutoSafeTargetFps(
                    preferences.fps,
                    preflightOptimization
                )
                val launchBitrateKbps = StreamSyncManager.resolveAutoSafeBitrateKbps(
                    requestedBitrateKbps,
                    preflightOptimization
                )
                LimeLog.info(
                    "Nova: Launch resolved stream mode " +
                        launchResolution.width + "x" + launchResolution.height + "x" + launchFps + " " +
                        "source=" + (preflightOptimization?.optString("source", "") ?: "") + " " +
                        "effectiveFps=" + (preflightOptimization?.optDouble("effective_target_fps", 0.0) ?: 0.0) + " " +
                        "displayMode=" + (preflightOptimization?.optString("display_mode", "") ?: "")
                )
                // Space settings were accepted by the worker resolver. Writing
                // ordinary device defaults here would replace that media contract.
                if (!NovaSpaceUiState.isSpace(game)) {
                    val syncedSettings = withContext(Dispatchers.IO) {
                        NovaLaunchPreflight.push(
                            apiClient = apiClient,
                            clientSettings = clientSettings,
                            usesVirtualDisplay = launchUsesVirtualDisplay,
                            mirrorDesktop = launchMirrorsDesktop,
                            resolvedMode = launchMode,
                            // Paired settings describe the user's durable Nova
                            // preferences. Preset-normalized values belong only to
                            // the resolved launch envelope passed to Game below.
                            width = preferences.width,
                            height = preferences.height,
                            fps = preferences.fps,
                            bitrateKbps = preferences.bitrate
                        )
                    }
                    if (syncedSettings == null) {
                        LimeLog.warning("Nova: Preflight client settings sync failed; continuing launch")
                    }
                }

                val app = NvApp(game.name, game.id, game.appId, game.hdrSupported)
                ServerHelper.doStart(
                    this@NovaLibraryActivity,
                    app,
                    streamHost,
                    streamHttpPort,
                    streamHttpsPort,
                    uniqueId,
                    pcUuid,
                    streamPcName,
                    streamServerCommands,
                    launchUsesVirtualDisplay,
                    true,
                    false,
                    serverCert,
                    launchResolution.width,
                    launchResolution.height,
                    launchFps,
                    aiProfilePreference = profilePreference,
                    launchOptimizationJson = preflightOptimization?.toString(),
                    mirrorDesktop = launchMirrorsDesktop,
                    forcePrivateAfterSteamClose = forcePrivateAfterSteamClose,
                    streamMode = launchMode,
                    encoderBackend = encoderBackend,
                    faceButtonLayout = NovaFaceButtonLayoutOverrides.load(this@NovaLibraryActivity, game).orEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                launchErrorMessage = message
                LimeLog.severe("Nova: Failed to launch ${game.name}: ${e.message}")
                NovaSnackbar.showError(this@NovaLibraryActivity, message)
            }
        }
    }

    private fun resumeActiveSession(session: NovaLibraryActiveSessionUiState) {
        // Resuming answers a refused End: the strip should not still say it on the way back.
        endStatus = null
        val uniqueId = streamUniqueId
        val pcUuid = streamPcUuid
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || pcUuid.isNullOrBlank() || serverCert == null) {
            val message = getString(R.string.nova_library_resume_missing_session)
            launchErrorMessage = message
            NovaSnackbar.showError(this, message)
            LimeLog.warning("Nova: Cannot resume from library; missing uniqueId, pcUuid, or server cert")
            return
        }
        launchErrorMessage = null

        val app = NvApp(
            session.gameName.ifBlank { getString(R.string.applist_menu_watch_active_name) },
            session.gameUuid,
            session.gameId,
            false
        )
        val resumeIntent = ServerHelper.createStartIntent(
            this,
            app,
            streamHost,
            streamHttpPort,
            streamHttpsPort,
            uniqueId,
            pcUuid,
            streamPcName,
            session.virtualDisplay,
            session.displayModeExplicit,
            session.watchOnly,
            streamServerCommands,
            serverCert,
            session.streamWidth,
            session.streamHeight,
            session.streamFps,
            streamMode = session.streamMode,
            mirrorDesktop = session.mirrorDesktop,
            forcePrivateAfterSteamClose = session.forcePrivateAfterSteamClose
        )
        resumeIntent.putExtra(Game.EXTRA_RESUME_EXISTING, true)
        startActivity(resumeIntent)
        NovaThemeManager.applyFadeTransition(this)
    }

    /**
     * Ends the running session on the host. Every End that reaches this, in the library and in
     * game detail, has already been confirmed in its own slot by a split, so this asks nothing more.
     * What happens is said where End was pressed, never in a Toast: Ending while the host is
     * asked, and a refusal with Try Again, so the strip never stays on Ending (XR3).
     */
    private fun endActiveSession(session: NovaLibraryActiveSessionUiState) {
        val uniqueId = streamUniqueId
        val serverCert = streamServerCert
        val refused = getString(R.string.nova_library_end_failed)
        if (uniqueId.isNullOrBlank() || serverCert == null) {
            endStatus = NovaLibraryEndStatus.Failed(session.gameId, refused)
            LimeLog.warning("Nova: Cannot end session from library; missing uniqueId or server cert")
            return
        }
        endStatus = NovaLibraryEndStatus.Ending(session.gameId)

        val gameName = session.gameName.ifBlank { getString(R.string.applist_menu_watch_active_name) }
        val httpConn = NvHTTP(
            ComputerDetails.AddressTuple(streamHost, streamHttpPort),
            streamHttpsPort,
            uniqueId,
            PolarisApiClient.decodeCertificate(serverCert),
            PlatformBinding.getCryptoProvider(this)
        )
        ServerHelper.doQuit(this, httpConn, gameName) { failure ->
            if (failure == null) {
                val generation = beginActiveSessionRefresh()
                activeSession = null
                endStatus = null
                scheduleActiveSessionFollowUpRefreshes(
                    clearOnly = true,
                    generation = generation,
                )
            } else {
                LimeLog.warning("Nova: The host did not end the session: $failure")
                endStatus = NovaLibraryEndStatus.Failed(session.gameId, refused)
                refreshActiveSession(scheduleFollowUps = true)
            }
        }
    }

    private fun openServerManagement() {
        openServerManagementAt("")
    }

    private fun openServerDisplaySettings() {
        // ConfigView recognizes the Audio/Video tab id in its hash target and lands
        // where the host-wide Headless Dongle choice lives.
        openServerManagementAt("/#/config#av")
    }

    private fun openServerManagementAt(path: String) {
        val managementPort = if (streamHttpPort > 0) streamHttpPort + 1 else 47990
        val managementUrl = "https://$streamHost:$managementPort$path"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(managementUrl)))
        } catch (e: Exception) {
            LimeLog.warning("Nova: Failed to open server management: ${e.message}")
            // On a Notice in the edge panel, where it can be read: a Toast floated and was gone.
            novaSurfaces.present(
                NovaCommonPage.Notice(
                    key = MANAGE_FAILED_NOTICE_KEY,
                    title = getString(R.string.nova_system_menu_manage_server),
                    message = getString(R.string.nova_library_manage_failed),
                    closeLabel = getString(R.string.nova_panel_close),
                ),
            )
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, StreamSettings::class.java))
        NovaThemeManager.applyFadeTransition(this)
    }

    /**
     * Polaris Sync's page, pushed from System. Its engine starts here and closes once the page
     * has left the stack, whichever way it left: B, L1 or R1 to a peer, or the panel closing.
     */
    private fun polarisSyncPage(): NovaPage {
        val controller = polarisSync ?: NovaPolarisSyncController(
            context = this,
            apiClient = apiClient,
            serverUuid = streamPcUuid,
            scope = lifecycleScope,
            onSettingsChanged = { settings -> clientSettings = settings },
        ).also { polarisSync = it }
        controller.open(clientSettings)
        controller.closeWhenGone(novaSurfaces.panel, LibraryPage.KEY_POLARIS_SYNC)
        return LibraryPage.PolarisSync(getString(R.string.nova_polaris_sync_title))
    }

    /** Default Display's choices inside Polaris Sync, as the Where It Runs page. */
    private fun polarisPlayInPage(picker: NovaPlaySetupModePickerState): NovaPage = PlaySetupPage.PlayIn(
        title = picker.title,
        picker = { picker },
        onPick = { mode -> polarisSync?.engine?.setStreamDisplayMode(mode) },
    )

    /** The host profile's verbs inside Polaris Sync, each with what it does. */
    private fun polarisProfilePage(): NovaPage = PlaySetupPage.Options(
        title = getString(R.string.nova_play_setup_host_profile_row),
        row = NovaPlaySetupRow.HOST_PROFILE,
        bands = {
            val model = polarisSync?.let { controller ->
                @Suppress("DEPRECATION")
                rememberNovaPolarisSyncModel(controller, streamPcUuid, windowManager.defaultDisplay)
            }
            listOfNotNull(
                model?.rows?.firstOrNull { it.row == NovaPlaySetupRow.HOST_PROFILE }
                    ?.let { NovaPlaySetupBand(null, it.options) },
            )
        },
    )

    private fun openHelpDiagnostics() {
        HelpLauncher.launchTroubleshooting(this)
    }

    /** About Nova, pushed over System: its version, read in place, and B back to System. */
    private fun aboutNovaPage(): NovaPage = NovaCommonPage.Notice(
        key = ABOUT_NOTICE_KEY,
        title = getString(R.string.nova_system_menu_about),
        message = getString(R.string.nova_system_menu_about_version, NovaAppVersion.current()),
        closeLabel = getString(R.string.nova_panel_close),
    )

    private fun openSponsor() {
        HelpLauncher.launchSponsor(this)
    }

    private fun openMatrixCommunity() {
        HelpLauncher.launchMatrixCommunity(this)
    }

    private fun finishWithTransition() {
        finish()
        NovaThemeManager.applyBackTransition(this)
    }

    private fun sourceLabelFor(source: String?): String {
        if (source.isNullOrBlank()) return getString(R.string.nova_library_filter_other_source)
        return when (source.lowercase(Locale.US)) {
            "steam" -> "Steam"
            "lutris" -> "Lutris"
            "heroic" -> "Heroic"
            "emulator" -> "Emulator"
            else -> source
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                    }
                }
                .ifBlank { getString(R.string.nova_library_filter_other_source) }
        }
    }

    private fun categoryLabelFor(category: String): String {
        return when (category.lowercase(Locale.US)) {
            "fast_action" -> getString(R.string.nova_library_filter_action)
            "cinematic" -> getString(R.string.nova_library_filter_cinematic)
            "desktop" -> getString(R.string.nova_library_filter_desktop)
            "vr" -> getString(R.string.nova_library_filter_vr)
            else -> getString(R.string.nova_library_filter_more)
        }
    }

    @Composable
    private fun rememberNovaLibraryUiModel(
        games: List<PolarisGame>,
        searchQuery: String,
        filterState: NovaLibraryFilterState,
        activeSession: NovaLibraryActiveSessionUiState?,
        optionsState: NovaLibraryOptionsState
    ): NovaLibraryUiModel {
        val model = remember(games, searchQuery, filterState, activeSession, optionsState) {
            NovaLibraryUiStateMapper.build(
                games = games,
                search = searchQuery,
                filterState = filterState,
                optionsState = optionsState,
                activeSession = activeSession,
            )
        }
        val tryAgain = stringResource(R.string.nova_panel_try_again)
        return remember(model, lastFocusedGameId, endStatus, tryAgain) {
            NovaLibraryUiStateMapper.withEndStatus(
                NovaLibraryUiStateMapper.focusSpace(model, lastFocusedGameId),
                activeSession,
                endStatus,
                tryAgain,
            )
        }
    }

    /** The sentence under a disabled Open: what is wrong, in the host's words, never a bare disabled button. */
    @Composable
    private fun spaceScreenReason(snapshot: PolarisSpaces?, checked: Boolean): String? {
        if (!checked) return stringResource(R.string.nova_space_checking_status)
        snapshot ?: return null
        NovaSpacesCopy.unavailableReason(snapshot)?.let { return stringResource(it) }
        val selected = snapshot.selected ?: return null
        // The screen already carries the in-use sentence next to its chip.
        if (selected.state == "in_use") return null
        return NovaSpacesCopy.openBlockedReason(selected)?.let { stringResource(it) }
    }

    @Composable
    private fun spacesRecoveryState(snapshot: PolarisSpaces): NovaLibraryRecoveryUiState? =
        when (NovaSpacesCopy.emptyLibraryCase(snapshot)) {
            NovaSpacesCopy.EmptyLibraryCase.NO_SPACE_ASSIGNED -> NovaLibraryRecoveryUiState(
                eyebrow = stringResource(R.string.nova_space_bar_spaces),
                title = stringResource(R.string.nova_space_no_space_title),
                message = stringResource(R.string.nova_space_no_space_message),
                primaryActionLabel = stringResource(R.string.nova_space_open_in_polaris),
                primaryAction = NovaLibraryRecoveryAction.OPEN_SPACES,
                secondaryActionLabel = stringResource(R.string.nova_space_retry_check),
                secondaryAction = NovaLibraryRecoveryAction.RETRY,
            )
            NovaSpacesCopy.EmptyLibraryCase.HOST_UNAVAILABLE -> NovaLibraryRecoveryUiState(
                eyebrow = stringResource(R.string.nova_space_bar_spaces),
                title = stringResource(R.string.nova_space_unavailable_title),
                message = stringResource(NovaSpacesCopy.unavailableReason(snapshot) ?: R.string.nova_space_unavailable_generic),
                primaryActionLabel = stringResource(R.string.nova_space_retry_check),
                primaryAction = NovaLibraryRecoveryAction.RETRY,
                secondaryActionLabel = stringResource(R.string.nova_space_open_in_polaris),
                secondaryAction = NovaLibraryRecoveryAction.OPEN_SPACES,
            )
            null -> null
        }

    @Composable
    private fun NovaLibraryScreen(
        serverName: String?,
        serverHost: String,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        searchQuery: String,
        isInitialLoading: Boolean,
        isRefreshing: Boolean,
        loadErrorMessage: String?,
        launchErrorMessage: String?,
        clientSettings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?,
        apiClient: PolarisApiClient,
        controllerHintsVisible: Boolean,
        restoreFocusGameId: String?,
        onBack: () -> Unit,
        onRefresh: () -> Unit,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onEndSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onManageServer: () -> Unit,
        onOpenDetail: (PolarisGame) -> Unit,
        onGameFocused: (PolarisGame) -> Unit,
        onOpenOptions: () -> Unit,
        onOpenSystemMenu: () -> Unit,
        onClearFilters: () -> Unit,
    ) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val space = NovaSpaceUiState.singleSpace(model.allGames).takeUnless { isInitialLoading || loadErrorMessage != null }
            ?.let { game -> spacesSnapshot?.selected?.let { game.copy(name = it.name) } ?: game }
        val stageMode = model.optionsState.layoutMode == NovaLibraryLayoutMode.STAGE
        val showLandscapeControlRail = NovaLibraryUiStateMapper.showLandscapeControlRail()
        val layoutSpec = NovaLibraryUiStateMapper.layoutSpec(
            configuration.screenWidthDp,
            configuration.screenHeightDp,
            model.optionsState.layoutMode,
            largeText = largeText,
        )
        val columns = layoutSpec.gridColumns
        val railWidth = NovaLibraryUiStateMapper.railWidthDp(configuration.screenWidthDp).dp
        val showLandscapeRecentRail = !stageMode &&
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = configuration.screenHeightDp,
                heroReason = model.hero.reason,
                recentCount = model.recentGames.size
            )
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val controllerHintBarBottomPadding = NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp
        val restoreFocusGameInRecent = !stageMode && restoreFocusGameId != null &&
            model.recentGames.any { it.id == restoreFocusGameId }
        val focusedBackdropGame = remember(
            model.filteredGames,
            model.recentGames,
            model.allGames,
            model.hero,
            restoreFocusGameId,
        ) {
            restoreFocusGameId
                ?.let { focusedId -> model.filteredGames.firstOrNull { it.id == focusedId } }
                ?: model.hero.game
                ?: model.filteredGames.firstOrNull()
                ?: model.recentGames.firstOrNull()
        }
        val controllerHints = if (lastInputRemote) novaLibraryRemoteHints() else novaLibraryControllerHints(isLandscape)
        val visibleControllerHints = when {
            largeText -> controllerHints.filterIndexed { index, _ -> index in LARGE_TEXT_HINT_INDICES }
            // A landscape screen has the room for every key that does something here: the bar
            // showed A, B and X, and Y, Start and the shoulders were left for the player to find.
            // Below 720dp the bar scrolled its last hint under the edge, so a narrower screen
            // keeps the primary verbs.
            isLandscape && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720 -> controllerHints
            // Upright only the primary verbs earn footer space; the rest stay reachable on their
            // buttons and remain in the accessibility description below.
            else -> controllerHints.filterIndexed { index, _ -> index in PRIMARY_HINT_INDICES }
        }
        val controllerHintDescription = controllerHints.joinToString(separator = " · ") { hint ->
            "${hint.key} ${hint.label}"
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.window)
        ) {
            NovaLibraryCinematicBackdrop(
                game = focusedBackdropGame,
                apiClient = apiClient,
                strength = if (model.optionsState.layoutMode == NovaLibraryLayoutMode.STAGE) {
                    1f
                } else {
                    NovaLibraryGridBackdropStrength
                },
            )
            if (surfaces.particlesEnabled) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(surfaces.particleAlpha),
                    factory = { context ->
                        SpaceParticleView(context).apply {
                            dense = true
                            // Overlays the cinematic backdrop; must not fill its canvas.
                            paintsOpaqueBackground = false
                        }
                    },
                    // The chooser and the Space screen paint an opaque window over it; no
                    // reason to keep drawing 300 stars nobody can see.
                    update = { view -> view.setCovered(chooseSpaceVisible || space != null) },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(surfaces.backgroundScrim)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(com.papi.nova.ui.panel.novaScreenPadding(NovaLibraryUiStateMapper.screenPaddingDp(isLandscape).dp))
            ) {
                val environments = spacesSnapshot?.takeIf { it.spaces.isNotEmpty() }
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (chooseSpaceVisible && spacesSnapshot != null) {
                        NovaSpaceChooser(
                            snapshot = spacesSnapshot!!,
                            busy = choosingSpace,
                            statusKnown = spacesChecked,
                            error = spacesError,
                            onChoose = ::chooseSpace,
                            onBack = { chooseSpaceVisible = false; spaceFocusEpoch++ },
                        )
                    } else if (space != null) {
                        NovaSpaceContent(
                            game = space,
                            displayName = spacesSnapshot?.selected?.name,
                            spaceState = spacesSnapshot?.selected?.state,
                            // Always offered: the chooser says why when there is nowhere else to go.
                            onChoose = ::showSpaceChooser,
                            hostName = serverName.orEmpty().ifBlank { serverHost },
                            activeSession = activeSession,
                            onOpen = { openSpace(space) },
                            onSettings = { showDetail(space, spaceSettings = true) },
                            onBack = onBack,
                            onSystem = onOpenSystemMenu,
                            onCancel = if (spaceOpenPending) ::cancelSpaceOpen else null,
                            primaryEnabled = !spaceOpenPending && !choosingSpace && spacesChecked &&
                                (spacesSnapshot == null || spaceOpenable(spacesSnapshot!!)),
                            primaryLabel = if (spaceOpenPending) getString(R.string.nova_space_checking) else null,
                            message = spacesError ?: launchErrorMessage ?: spaceScreenReason(spacesSnapshot, spacesChecked),
                            focusEpoch = spaceFocusEpoch,
                            focusEnabled = !chooseSpaceVisible && !libraryPanelOpen,
                        )
                    } else if (isLandscape) {
                        NovaLibraryLandscapeStageShell(
                            modifier = Modifier.fillMaxSize(),
                            reserveControllerHintSpace = true,
                        ) {
                            // The strip's card is for something to act on now, not the grid's selection.
                            val showContinue = NovaLibraryUiStateMapper.showStandaloneHomeHero(
                                layoutMode = model.optionsState.layoutMode,
                                hasActiveSession = activeSession != null,
                            ) && NovaLibraryUiStateMapper.showTopBarCard(model.hero)
                            NovaLibraryLandscapeShowcaseStripContent(
                                environments = environments,
                                environmentEnabled = !choosingSpace,
                                environmentStatusKnown = spacesChecked,
                                environmentChanging = choosingSpace,
                                onChooseEnvironment = ::showSpaceChooser,
                                hostLabel = serverName?.takeIf { it.isNotBlank() } ?: serverHost,
                                polarisReady = clientSettings != null,
                                onOpenOptions = onOpenOptions,
                                onOpenSystemMenu = onOpenSystemMenu,
                                continueCard = model.hero.topBarContinue(),
                                continueSlot = if (showContinue) {
                                    { fit ->
                                        NovaLibraryShowcaseContinue(
                                            hero = model.hero,
                                            apiClient = apiClient,
                                            fit = fit,
                                            onPrimaryAction = {
                                                when (model.hero.primaryAction) {
                                                    NovaLibraryHeroPrimaryAction.RESUME,
                                                    NovaLibraryHeroPrimaryAction.WATCH ->
                                                        activeSession?.let(onResumeSession)
                                                    NovaLibraryHeroPrimaryAction.OPEN_SPACE -> model.hero.game?.let { openSpace(it) }
                                                    NovaLibraryHeroPrimaryAction.OPEN_DETAIL ->
                                                        model.hero.game?.let(onOpenDetail)
                                                    NovaLibraryHeroPrimaryAction.MANAGE_LIBRARY -> onManageServer()
                                                    NovaLibraryHeroPrimaryAction.CLEAR_FILTERS -> onClearFilters()
                                                }
                                            },
                                            onSecondaryAction = when (model.hero.secondaryAction) {
                                                NovaLibraryHeroSecondaryAction.END_SESSION ->
                                                    { { activeSession?.let(onEndSession) } }
                                                null -> null
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                            // A search in force says so above what it narrows, and A there clears it (N12).
                            if (searchQuery.isNotBlank()) NovaLibrarySearchChip(
                                query = searchQuery,
                                resultCount = model.resultCount,
                                onClear = ::clearSearch,
                                modifier = Modifier.padding(
                                    horizontal = NovaLibraryUiStateMapper.libraryBarContentInsetDp().dp,
                                    vertical = 4.dp,
                                ),
                            )
                            NovaLibraryContent(
                                modifier = Modifier.weight(1f),
                                model = model,
                                filterState = filterState,
                                columns = columns,
                                windowClass = layoutSpec.windowClass,
                                isLandscape = true,
                                isInitialLoading = isInitialLoading,
                                isRefreshing = isRefreshing,
                                loadErrorMessage = loadErrorMessage,
                                launchErrorMessage = launchErrorMessage,
                                apiClient = apiClient,
                                activeSession = activeSession,
                                onResumeSession = onResumeSession,
                                onEndSession = onEndSession,
                                restoreFocusGameId = restoreFocusGameId,
                                onRefresh = onRefresh,
                                onManageServer = onManageServer,
                                onClearFilters = onClearFilters,
                                onGameFocused = onGameFocused,
                                onOpenDetail = onOpenDetail
                            )
                            if (showLandscapeRecentRail) {
                                NovaLibraryRecentRail(
                                    games = model.recentGames,
                                    apiClient = apiClient,
                                    restoreFocusGameId = restoreFocusGameId,
                                    showPosterTitles = model.optionsState.showPosterTitles,
                                    onGameFocused = onGameFocused,
                                    onOpenDetail = onOpenDetail
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = controllerHintBarBottomPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NovaLibraryTopHeader(
                                serverName = serverName,
                                serverHost = serverHost,
                                model = model,
                                filterState = filterState,
                                searchQuery = searchQuery,
                                clientSettings = clientSettings,
                                activeSession = activeSession,
                                onOpenOptions = onOpenOptions,
                                onOpenSystemMenu = onOpenSystemMenu
                            )
                            if (environments != null) {
                                // Its own row under the header. The 60 dp header cannot hold
                                // the bar, and the landscape strip it used to borrow pushed
                                // Options and System off the right edge of a phone.
                                NovaEnvironmentBar(
                                    spaces = environments,
                                    enabled = !choosingSpace,
                                    statusKnown = spacesChecked,
                                    changing = choosingSpace,
                                    onChoose = ::showSpaceChooser,
                                    compact = true,
                                    framed = true,
                                )
                            }
                            if (
                                NovaLibraryUiStateMapper.showStandaloneHomeHero(
                                    layoutMode = model.optionsState.layoutMode,
                                    hasActiveSession = activeSession != null,
                                )
                            ) {
                                NovaLibraryHomeHero(
                                    hero = model.hero,
                                    compact = false,
                                    apiClient = apiClient,
                                    onPrimaryAction = {
                                        when (model.hero.primaryAction) {
                                            NovaLibraryHeroPrimaryAction.RESUME,
                                            NovaLibraryHeroPrimaryAction.WATCH -> activeSession?.let(onResumeSession)
                                            NovaLibraryHeroPrimaryAction.OPEN_SPACE -> model.hero.game?.let { openSpace(it) }
                                            NovaLibraryHeroPrimaryAction.OPEN_DETAIL -> model.hero.game?.let(onOpenDetail)
                                            NovaLibraryHeroPrimaryAction.MANAGE_LIBRARY -> onManageServer()
                                            NovaLibraryHeroPrimaryAction.CLEAR_FILTERS -> onClearFilters()
                                        }
                                    },
                                    onSecondaryAction = {
                                        when (model.hero.secondaryAction) {
                                            NovaLibraryHeroSecondaryAction.END_SESSION -> activeSession?.let(onEndSession)
                                            null -> Unit
                                        }
                                    },
                                    onOpenDetail = model.hero.game?.let { game -> { onOpenDetail(game) } },
                                    onGameFocused = onGameFocused
                                )
                            }
                            if (!stageMode && model.recentGames.isNotEmpty()) {
                                NovaLibraryRecentRail(
                                    games = model.recentGames,
                                    apiClient = apiClient,
                                    restoreFocusGameId = restoreFocusGameId,
                                    showPosterTitles = model.optionsState.showPosterTitles,
                                    onGameFocused = onGameFocused,
                                    onOpenDetail = onOpenDetail
                                )
                            }
                            // A search in force says so above what it narrows, and A there clears it (N12).
                            if (searchQuery.isNotBlank()) NovaLibrarySearchChip(
                                query = searchQuery,
                                resultCount = model.resultCount,
                                onClear = ::clearSearch,
                                modifier = Modifier.padding(
                                    horizontal = NovaLibraryUiStateMapper.libraryBarContentInsetDp().dp,
                                    vertical = 4.dp,
                                ),
                            )
                            NovaLibraryContent(
                                modifier = Modifier.weight(1f),
                                model = model,
                                filterState = filterState,
                                columns = columns,
                                windowClass = layoutSpec.windowClass,
                                isLandscape = false,
                                isInitialLoading = isInitialLoading,
                                isRefreshing = isRefreshing,
                                loadErrorMessage = loadErrorMessage,
                                launchErrorMessage = launchErrorMessage,
                                apiClient = apiClient,
                                activeSession = activeSession,
                                onResumeSession = onResumeSession,
                                onEndSession = onEndSession,
                                restoreFocusGameId = restoreFocusGameId.takeUnless { restoreFocusGameInRecent },
                                onRefresh = onRefresh,
                                onManageServer = onManageServer,
                                onClearFilters = onClearFilters,
                                onGameFocused = onGameFocused,
                                onOpenDetail = onOpenDetail
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = space == null && (stageMode || controllerHintsVisible),
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(durationMillis = CONTROLLER_HINT_ANIMATION_MS)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = CONTROLLER_HINT_ANIMATION_MS),
                            initialOffsetY = { it / 2 }
                        ),
                    exit = fadeOut(tween(durationMillis = CONTROLLER_HINT_ANIMATION_MS)) +
                        slideOutVertically(
                            animationSpec = tween(durationMillis = CONTROLLER_HINT_ANIMATION_MS),
                            targetOffsetY = { it / 2 }
                        )
                ) {
                    // The chooser carries its own A and B hints; the library's "X Library"
                    // underneath it was a second bar for a screen that has no library.
                    if (!chooseSpaceVisible) NovaLibraryCinematicControllerHints(
                        hints = visibleControllerHints,
                        compact = isLandscape,
                        semanticsDescription = controllerHintDescription,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    /**
     * The hints for a remote, which has a center key and Back and none of a controller's face keys
     * or shoulders: the bar named X, Y and L1/R1 on a TV remote (C04). Options and System are the
     * strip's own buttons there, reached with the D-pad.
     */
    @Composable
    private fun novaLibraryRemoteHints(): List<NovaControllerHint> = listOf(
        NovaControllerHint(
            key = stringResource(R.string.nova_controller_hint_remote_center),
            label = stringResource(R.string.nova_controller_hint_select),
        ),
        NovaControllerHint(
            key = stringResource(R.string.nova_controller_hint_remote_back),
            label = stringResource(R.string.nova_controller_hint_remote_back_label),
        ),
    )

    @Composable
    private fun novaLibraryControllerHints(isLandscape: Boolean): List<NovaControllerHint> {
        val coreHints = mutableListOf(
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_a),
                label = stringResource(R.string.nova_controller_hint_select)
            ),
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_b),
                label = stringResource(R.string.nova_controller_hint_back)
            ),
            // X opens Library Options, and the bar says so by that name.
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_x),
                label = stringResource(R.string.nova_controller_hint_options)
            ),
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_y),
                label = stringResource(R.string.nova_controller_hint_layout)
            ),
            NovaControllerHint(
                key = stringResource(R.string.menu_button),
                label = stringResource(R.string.nova_controller_hint_system)
            )
        )
        if (isLandscape) {
            coreHints += NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_lb_rb),
                label = stringResource(R.string.nova_controller_hint_library_system)
            )
        }
        return coreHints
    }

    /**
     * The home hero above the grid in portrait. [NovaLibraryHeroCard] draws it, in its own file
     * beside the strip's card, so a test can drive it.
     */
    @Composable
    private fun NovaLibraryHomeHero(
        hero: NovaLibraryHeroState,
        compact: Boolean,
        apiClient: PolarisApiClient,
        onPrimaryAction: () -> Unit,
        onSecondaryAction: (() -> Unit)? = null,
        onOpenDetail: (() -> Unit)? = null,
        onGameFocused: (PolarisGame) -> Unit
    ) {
        NovaLibraryHeroCard(
            hero = hero,
            compact = compact,
            apiClient = apiClient,
            onPrimaryAction = onPrimaryAction,
            onSecondaryAction = onSecondaryAction,
            onOpenDetail = onOpenDetail,
            onGameFocused = onGameFocused,
        )
    }

    @Composable
    private fun NovaLibraryLandscapeToolbar(
        serverName: String?,
        serverHost: String,
        model: NovaLibraryUiModel,
        clientSettings: PolarisClientSettings?,
        onOpenOptions: () -> Unit,
        onOpenSystemMenu: () -> Unit,
    ) {
        val hostLabel = serverName?.takeIf { it.isNotBlank() } ?: serverHost
        NovaLibraryLandscapeToolbarContent(
            hostLabel = hostLabel,
            resultCount = model.resultCount,
            layoutLabel = layoutModeLabel(model.optionsState.layoutMode),
            polarisReady = clientSettings != null,
            // The library bar is the same furniture whichever layout is below it, so it
            // keeps one treatment across Grid, Compact and Stage rather than changing
            // weight and shape as the content does.
            cinematic = true,
            onOpenOptions = onOpenOptions,
            onOpenSystemMenu = onOpenSystemMenu,
        )
    }

    /** The continue action inside the landscape strip, drawn by [NovaLibraryStripContinue]. */
    @Composable
    private fun RowScope.NovaLibraryShowcaseContinue(
        hero: NovaLibraryHeroState,
        apiClient: PolarisApiClient,
        fit: NovaTopBarFit,
        onPrimaryAction: () -> Unit,
        onSecondaryAction: (() -> Unit)?,
    ) {
        NovaLibraryStripContinue(
            hero = hero,
            apiClient = apiClient,
            fit = fit,
            onPrimaryAction = onPrimaryAction,
            onSecondaryAction = onSecondaryAction,
        )
    }

    @Composable
    private fun NovaLibraryTopHeader(
        serverName: String?,
        serverHost: String,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        searchQuery: String,
        clientSettings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?,
        onOpenOptions: () -> Unit,
        onOpenSystemMenu: () -> Unit
    ) {
        val hasFilters = hasClearableFilters(searchQuery, filterState)
        NovaLibraryPortraitToolbarContent(
            hostLabel = serverName?.takeIf { it.isNotBlank() } ?: serverHost,
            resultCount = model.resultCount,
            layoutLabel = layoutModeLabel(model.optionsState.layoutMode),
            polarisReady = clientSettings != null,
            identityStatus = {
                NovaLibraryCompactMetaRow(
                    clientSettings = clientSettings,
                    activeSession = activeSession,
                    searchQuery = searchQuery,
                    hasFilters = hasFilters,
                )
            },
            onOpenOptions = onOpenOptions,
            onOpenSystemMenu = onOpenSystemMenu,
        )
    }

    @Composable
    private fun NovaLibraryCompactMetaRow(
        clientSettings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?,
        searchQuery: String,
        hasFilters: Boolean
    ) {
        val colors = LocalNovaComposeColors.current
        val metaItems = buildList {
            if (searchQuery.isNotBlank()) add("Search active")
            if (hasFilters) add("Filters active")
            add(
                if (clientSettings != null) {
                    stringResource(R.string.nova_system_menu_status_polaris_ready)
                } else {
                    stringResource(R.string.nova_library_status_checking)
                }
            )
            if (activeSession != null) add(stringResource(R.string.nova_library_resume_ready))
        }

        Text(
            text = metaItems.joinToString(" · "),
            color = colors.textSecondary,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun NovaLibraryTitle(serverName: String?, serverHost: String) {
        Text(
            text = stringResource(R.string.nova_library_title),
            color = LocalNovaComposeColors.current.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = serverName?.takeIf { it.isNotBlank() } ?: serverHost,
            color = LocalNovaComposeColors.current.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun NovaLibraryStatusStrip(
        settings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?
    ) {
        val autoQualityEnabled = settings?.let {
            it.desired.aiAutoQualityEnabled == true ||
                it.effective.aiAutoQualityEnabled == true ||
                it.desired.adaptiveBitrateEnabled == true ||
                it.effective.adaptiveBitrateEnabled == true ||
                it.desired.aiOptimizerEnabled == true ||
                it.effective.aiOptimizerEnabled == true
        }
        val polarisReady = settings != null
        val polarisText = stringResource(
            if (polarisReady) R.string.nova_library_polaris_ready
            else R.string.nova_library_polaris_checking
        )
        val autoQualityText = when (autoQualityEnabled) {
            true -> stringResource(R.string.nova_library_auto_quality_on)
            false -> stringResource(R.string.nova_library_auto_quality_off)
            null -> stringResource(R.string.nova_library_status_checking)
        }
        val modeText = compactStatusModeLabel(settings)
            ?: stringResource(R.string.nova_library_mode_checking)

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NovaStatusPill(text = polarisText, enabled = polarisReady)
                NovaStatusPill(text = autoQualityText, enabled = autoQualityEnabled == true)
                NovaStatusPill(
                    text = modeText,
                    enabled = settings != null
                )
            }
            if (activeSession != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NovaStatusPill(
                        text = stringResource(R.string.nova_library_resume_ready),
                        enabled = true
                    )
                }
            }
        }
    }

    private fun compactStatusModeLabel(settings: PolarisClientSettings?): String? {
        settings ?: return null
        // Desired first — the same answer game detail gives, so a host that fell back
        // no longer reads two ways depending on which screen asked. When the session
        // in flight runs something else, both are said, with the arrow the sync
        // mapper always computed and nothing ever rendered.
        val desired = compactModeName(settings.desired.streamDisplayMode)
            ?: settings.desiredModeLabel.ifBlank { null }
        val effective = compactModeName(settings.effective.streamDisplayMode)
            ?: settings.effectiveModeLabel.ifBlank { null }
        return when {
            desired == null -> effective
            effective == null || effective == desired -> desired
            else -> "$desired → $effective"
        }
    }

    private fun compactModeName(mode: String?): String? = when (mode.orEmpty()) {
        PolarisClientSettings.MODE_HEADLESS_STREAM, "headless" -> "Headless"
        PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY, "virtual_display" -> "Virtual"
        PolarisClientSettings.MODE_DESKTOP_DISPLAY -> "Desktop"
        PolarisClientSettings.MODE_DESKTOP_TAKEOVER -> "Takeover"
        PolarisClientSettings.MODE_GPU_NATIVE_TEST -> "GPU-native"
        else -> null
    }

    @Composable
    private fun NovaLibrarySummary(model: NovaLibraryUiModel, compact: Boolean = false) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NovaMetricPill(
                    label = "Games",
                    value = model.summary.totalCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                NovaMetricPill(
                    label = stringResource(R.string.nova_library_filter_recent),
                    value = model.summary.recentCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            return
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NovaMetricBox(
                label = "Games",
                value = model.summary.totalCount.toString(),
                modifier = Modifier.weight(1f)
            )
            NovaMetricBox(
                label = stringResource(R.string.nova_library_filter_recent),
                value = model.summary.recentCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    private fun NovaMetricPill(label: String, value: String, modifier: Modifier = Modifier) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val description = "$value $label"
        Text(
            text = description,
            color = colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .clip(RoundedCornerShape(NovaRadius.pill))
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.pill))
                .semantics { contentDescription = description }
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }

    @Composable
    private fun NovaMetricBox(label: String, value: String, modifier: Modifier = Modifier) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(NovaRadius.row))
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.row))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = value, color = colors.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text = label, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }

    @Composable
    private fun NovaStatusPill(text: String, enabled: Boolean) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        // A status pill reports state; it is never focused and never selected. Its enabled
        // fill used to be selectedControl, which is the focus fill, so an enabled pill sat on
        // screen looking like the thing the d-pad was on. The accent stroke below was already
        // saying "enabled" correctly, so the fill just had to stop contradicting it.
        NovaBadge(
            text = text,
            color = if (enabled) colors.accent else colors.textSecondary,
            backgroundColor = if (enabled) colors.accentSurface else surfaces.control,
            borderColor = if (enabled) colors.accent.copy(alpha = 0.68f) else surfaces.tileBorder,
            fontSize = 11.sp,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp)
        )
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun NovaLibraryContent(
        modifier: Modifier,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        columns: Int,
        windowClass: NovaLibraryWindowClass,
        isLandscape: Boolean,
        isInitialLoading: Boolean,
        isRefreshing: Boolean,
        loadErrorMessage: String?,
        launchErrorMessage: String?,
        apiClient: PolarisApiClient,
        activeSession: NovaLibraryActiveSessionUiState?,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onEndSession: (NovaLibraryActiveSessionUiState) -> Unit,
        restoreFocusGameId: String?,
        onRefresh: () -> Unit,
        onManageServer: () -> Unit,
        onClearFilters: () -> Unit,
        onGameFocused: (PolarisGame) -> Unit,
        onOpenDetail: (PolarisGame) -> Unit
    ) {
        val layoutMode = model.optionsState.layoutMode
        val stablePosterLoader = remember(apiClient) {
            { view: ImageView, targetGame: PolarisGame -> apiClient.loadCoverInto(view, targetGame) }
        }
        val onRecoveryAction: (NovaLibraryRecoveryAction) -> Unit = { action ->
            when (action) {
                NovaLibraryRecoveryAction.RETRY -> onRefresh()
                NovaLibraryRecoveryAction.MANAGE_LIBRARY -> onManageServer()
                NovaLibraryRecoveryAction.CLEAR_FILTERS -> onClearFilters()
                NovaLibraryRecoveryAction.OPEN_SPACES -> openServerManagementAt("/#/spaces")
            }
        }
        // No panel frames the posters: they sit on the backdrop under the top bar, and the
        // grid lines its artwork up with the bar's content instead of a box's inner edge.
        Box(modifier = modifier) {
            if (
                NovaLibraryUiStateMapper.shouldShowLoadFailure(
                    loadErrorMessage = loadErrorMessage,
                    allGamesEmpty = model.allGames.isEmpty(),
                    heroReason = model.hero.reason,
                )
            ) {
                val recoveryState = NovaLibraryUiStateMapper.loadFailureRecoveryState(
                    loadErrorMessage.orEmpty()
                )
                NovaLibraryRecoveryState(
                    recoveryState = recoveryState,
                    onAction = onRecoveryAction
                )
            } else if (
                isInitialLoading &&
                model.allGames.isEmpty() &&
                model.hero.reason != NovaLibraryHeroReason.ACTIVE_SESSION
            ) {
                NovaLibraryLoadingGrid(
                    columns = columns,
                    layoutMode = layoutMode,
                )
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val launchRecoveryState = launchErrorMessage
                        ?.let(NovaLibraryUiStateMapper::launchFailureRecoveryState)
                    val showStageContent = NovaLibraryUiStateMapper.shouldRenderStageContent(
                        layoutMode = layoutMode,
                        filteredGamesEmpty = model.filteredGames.isEmpty(),
                        heroReason = model.hero.reason,
                    )
                    if (launchRecoveryState != null) {
                        NovaLibraryRecoveryState(
                            recoveryState = launchRecoveryState,
                            onAction = onRecoveryAction
                        )
                    } else if (model.filteredGames.isEmpty() && !showStageContent) {
                        // An empty library with Spaces as the reason says so; "No games yet.
                        // Manage Library" was a diagnosis of the wrong thing.
                        val spacesRecoveryState = spacesSnapshot
                            ?.takeIf { model.summary.totalCount == 0 }
                            ?.let { spacesRecoveryState(it) }
                        val emptyRecoveryState = spacesRecoveryState ?: NovaLibraryUiStateMapper.emptyRecoveryState(
                            emptyState = model.emptyState,
                            totalCount = model.summary.totalCount,
                            sourceName = filterState.source
                        )
                        NovaLibraryRecoveryState(
                            recoveryState = emptyRecoveryState,
                            onAction = onRecoveryAction
                        )
                    } else if (showStageContent) {
                        val targetGame = NovaLibraryUiStateMapper.stageFocusedGame(
                            hero = model.hero,
                            filteredGames = model.filteredGames,
                            restoreFocusGameId = restoreFocusGameId,
                        )
                        NovaLibraryStage(
                            games = model.filteredGames,
                            apiClient = apiClient,
                            focusedGame = targetGame,
                            primaryActionLabel = stringResource(R.string.nova_library_review_and_launch),
                            sessionTitle = model.hero.title.takeIf {
                                model.hero.reason == NovaLibraryHeroReason.ACTIVE_SESSION
                            },
                            // A refused End says so where the session's line was, beside Try Again.
                            sessionSupportingLine = (
                                (model.hero.endStatus as? NovaLibraryEndStatus.Failed)?.line
                                    ?: model.hero.supportingLine
                                ).takeIf { model.hero.reason == NovaLibraryHeroReason.ACTIVE_SESSION },
                            sessionActionLabel = if (
                                model.hero.primaryAction == NovaLibraryHeroPrimaryAction.RESUME ||
                                model.hero.primaryAction == NovaLibraryHeroPrimaryAction.WATCH
                            ) {
                                model.hero.actionLabel
                            } else {
                                null
                            },
                            secondaryActionLabel = model.hero.secondaryActionLabel,
                            restoreFocusGameId = restoreFocusGameId,
                            showPosterTitles = model.optionsState.showPosterTitles,
                            onPrimaryAction = { targetGame?.let(onOpenDetail) },
                            onSessionAction = if (
                                model.hero.primaryAction == NovaLibraryHeroPrimaryAction.RESUME ||
                                model.hero.primaryAction == NovaLibraryHeroPrimaryAction.WATCH
                            ) {
                                { activeSession?.let(onResumeSession) }
                            } else {
                                null
                            },
                            onSecondaryAction = if (
                                model.hero.secondaryAction == NovaLibraryHeroSecondaryAction.END_SESSION
                            ) {
                                { activeSession?.let(onEndSession) }
                            } else {
                                null
                            },
                            onGameFocused = onGameFocused,
                            onOpenDetail = onOpenDetail,
                            artworkLoader = { view, targetGame, artworkKind ->
                                if (artworkKind == PolarisGame.ARTWORK_KIND_HERO) {
                                    apiClient.loadArtworkInto(view, targetGame, PolarisGame.ARTWORK_KIND_HERO)
                                } else {
                                    apiClient.loadArtworkInto(view, targetGame, artworkKind)
                                }
                            },
                            posterLoader = stablePosterLoader
                        )
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // Measure the grid's real viewport and pick the columns that fit
                        // whole rows in it. Taking a fixed column count and letting the
                        // remainder fall where it may is how the second row came to land
                        // a few dp short and read as clipped rather than as more below.
                        val gridSidePaddingDp = NovaLibraryUiStateMapper.gridSidePaddingDp(layoutMode)
                        val viewportSpec = NovaLibraryUiStateMapper.gridViewportSpec(
                            contentWidthDp = (maxWidth.value.toInt() - gridSidePaddingDp * 2)
                                .coerceAtLeast(1),
                            viewportHeightDp = maxHeight.value.toInt().coerceAtLeast(1),
                            layoutMode = layoutMode,
                            windowClass = windowClass,
                        )
                        // The grid clips at its top edge, so the top inset is the focus rise:
                        // a focused first-row poster stays whole and never reaches the bar.
                        // A focus scroll keeps the same margin for rows it brings to the top.
                        val focusRisePx = with(LocalDensity.current) { viewportSpec.topInsetDp.dp.toPx() }
                        val focusScrollSpec = remember(focusRisePx) { NovaGridFocusRiseScrollSpec(focusRisePx) }
                        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                        // A layout change, Compact from Grid, left the focused card under the header
                        // and the Shield's grid cut its second row at rest. The card that has focus
                        // comes into view with a row of context above it (R13).
                        LaunchedEffect(layoutMode, viewportSpec.columns) {
                            val focusedIndex = model.filteredGames.indexOfFirst { it.id == restoreFocusGameId }
                            gridState.scrollToItem(
                                novaLibraryGridContextIndex(focusedIndex, viewportSpec.columns),
                            )
                        }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides focusScrollSpec) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(viewportSpec.columns),
                            // The last visible row fades out on the grid's own layer; nothing is
                            // painted over the backdrop, so no box or seam appears where the grid ends.
                            modifier = Modifier.fillMaxSize().novaLibraryGridBottomFade(NovaLibraryGridScrollFadeHeight),
                            contentPadding = PaddingValues(
                                start = gridSidePaddingDp.dp,
                                top = viewportSpec.topInsetDp.dp,
                                end = gridSidePaddingDp.dp,
                                bottom = NovaLibraryUiStateMapper.gridBottomContentPaddingDp(isLandscape).dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(
                                NovaLibraryUiStateMapper.gridItemSpacingDp().dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(
                                NovaLibraryUiStateMapper.gridItemSpacingDp().dp,
                            ),
                        ) {
                            items(
                                model.filteredGames,
                                key = { it.id },
                                contentType = { "library-game" }
                            ) { game ->
                                val focusRequester = rememberLibraryPosterFocusRequester(
                                    restoreFocus = game.id == restoreFocusGameId,
                                    coldStartFocus = restoreFocusGameId == null &&
                                        game.id == model.filteredGames.firstOrNull()?.id,
                                )
                                NovaLibraryPosterCard(
                                    game = game,
                                    layoutMode = layoutMode,
                                    apiClient = apiClient,
                                    modifier = Modifier.fillMaxWidth(),
                                    showPosterTitle = model.optionsState.showPosterTitles,
                                    focusRequester = focusRequester,
                                    onFocused = { onGameFocused(game) },
                                    onOpenDetail = { onOpenDetail(game) },
                                )
                            }
                        }
                        }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NovaLibraryRecentRail(
        games: List<PolarisGame>,
        apiClient: PolarisApiClient,
        restoreFocusGameId: String?,
        showPosterTitles: Boolean,
        onGameFocused: (PolarisGame) -> Unit,
        onOpenDetail: (PolarisGame) -> Unit
    ) {
        NovaLibraryPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nova_library_continue_label),
                        color = LocalNovaComposeColors.current.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = stringResource(R.string.nova_library_continue_count, games.size),
                        color = LocalNovaComposeColors.current.textSecondary,
                        fontSize = 12.sp
                    )
                }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val visibleColumns = NovaLibraryUiStateMapper.RECENT_RAIL_VISIBLE_COLUMNS
                    val cardWidth = NovaLibraryUiStateMapper.recentRailCardWidthDp(
                        availableWidthDp = maxWidth.value.toInt(),
                        visibleColumns = visibleColumns
                    ).dp
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            games,
                            key = { it.id },
                            contentType = { "recent-game" }
                        ) { game ->
                            val focusRequester = rememberLibraryPosterFocusRequester(
                                restoreFocus = game.id == restoreFocusGameId,
                            )
                            NovaLibraryPosterCard(
                                game = game,
                                layoutMode = NovaLibraryLayoutMode.COMPACT,
                                apiClient = apiClient,
                                modifier = Modifier.width(cardWidth),
                                showPosterTitle = showPosterTitles,
                                focusRequester = focusRequester,
                                onFocused = { onGameFocused(game) },
                                onOpenDetail = { onOpenDetail(game) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun rememberLibraryPosterFocusRequester(
        restoreFocus: Boolean,
        coldStartFocus: Boolean = false,
    ): FocusRequester {
        val focusRequester = remember { FocusRequester() }
        val inputModeManager = LocalInputModeManager.current
        var restoreAttempted by remember { mutableStateOf(false) }
        LaunchedEffect(restoreFocus, coldStartFocus) {
            if ((restoreFocus || coldStartFocus) && !restoreAttempted) {
                // Settle first, for the same reason novaHoldsFirstFocus does: this effect
                // runs on the card's first composition, before the grid has placed it, so
                // an immediate request lands on nothing -- and a false return was recorded
                // as "attempted" and never retried. One frame (the old cold-start wait) is
                // not enough while the grid is still measuring a cold library.
                delay(NOVA_FIRST_FOCUS_SETTLE_MS)
                // Declare keyboard intent so the ring is visible immediately and the first
                // dpad press moves focus instead of being consumed revealing it. Restores
                // after a tap-opened detail want this just as much as a cold start does.
                inputModeManager.requestInputMode(InputMode.Keyboard)
                restoreAttempted = focusRequester.requestFocus()
                // Still unplaced after the settle: give layout a few more frames rather
                // than silently surrendering the session's first press.
                var retriesLeft = 8
                while (!restoreAttempted && retriesLeft > 0) {
                    withFrameNanos { }
                    restoreAttempted = focusRequester.requestFocus()
                    retriesLeft--
                }
            } else if (!restoreFocus && !coldStartFocus) {
                restoreAttempted = false
            }
        }
        return focusRequester
    }

    @Composable
    private fun NovaLibraryLoadingGrid(
        columns: Int,
        layoutMode: NovaLibraryLayoutMode,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(12, contentType = { "loading-card" }) {
                NovaLoadingCard(layoutMode = layoutMode)
            }
        }
    }

    @Composable
    private fun NovaLoadingCard(layoutMode: NovaLibraryLayoutMode) {
        val surfaces = LocalNovaLibrarySurfaces.current
        val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)
        val transition = rememberInfiniteTransition(label = "nova-library-loading")
        val shimmerOffset by transition.animateFloat(
            initialValue = -0.45f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400),
                repeatMode = RepeatMode.Restart
            ),
            label = "nova-library-card-shimmer"
        )
        val shimmerBrush = Brush.linearGradient(
            colors = listOf(
                surfaces.mediaPlaceholder.copy(alpha = 0.42f),
                surfaces.focusRing.copy(alpha = 0.18f),
                surfaces.mediaPlaceholder.copy(alpha = 0.42f)
            ),
            start = Offset(x = shimmerOffset * 620f, y = 0f),
            end = Offset(x = (shimmerOffset + 0.32f) * 620f, y = 260f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = presentationSpec.focusGutterDp.dp)
                .aspectRatio(NovaLibraryUiStateMapper.posterAspectRatio())
                .clip(RoundedCornerShape(NovaRadius.row))
                .background(shimmerBrush)
        )
    }

    /**
     * A library that cannot show games says why and what to do, as the one state page: no card, the
     * recovery action focused, and B leaving the library, never running that action (R5).
     */
    @Composable
    private fun NovaLibraryRecoveryState(
        recoveryState: NovaLibraryRecoveryUiState,
        onAction: (NovaLibraryRecoveryAction) -> Unit
    ) {
        val action by rememberUpdatedState(onAction)
        val secondaryLabel = recoveryState.secondaryActionLabel
        val secondaryAction = recoveryState.secondaryAction
        NovaStateScreen(
            page = NovaStatePage.Problem(
                key = "library-recovery-${recoveryState.title}",
                eyebrow = recoveryState.eyebrow,
                title = recoveryState.title,
                message = recoveryState.message,
                detail = recoveryState.detail?.takeIf { it.isNotBlank() },
                primary = NovaAction(recoveryState.primaryActionLabel) { action(recoveryState.primaryAction) },
                secondary = if (secondaryLabel != null && secondaryAction != null) {
                    listOf(NovaAction(secondaryLabel) { action(secondaryAction) })
                } else {
                    emptyList()
                },
                // Leaving the library is the way out that changes nothing; it is what B did here before.
                back = NovaProblemBack.Close(NovaAction(getString(R.string.nova_panel_back)) { finishWithTransition() }),
            ),
        )
    }

    @Composable
    private fun NovaLibraryPanel(
        modifier: Modifier = Modifier,
        subtle: Boolean = false,
        cinematic: Boolean = false,
        content: @Composable () -> Unit
    ) {
        val surfaces = LocalNovaLibrarySurfaces.current
        Surface(
            modifier = modifier,
            // One corner radius for library surfaces whichever layout is showing. Only the
            // fill still varies: the cinematic stage lets the backdrop through, while the
            // grid keeps a panel behind its poster wall.
            shape = RoundedCornerShape(NovaLibrarySurfaceCornerRadius),
            color = if (cinematic) {
                androidx.compose.ui.graphics.Color.Transparent
            } else if (subtle) {
                surfaces.panel.copy(alpha = 0.34f * LocalNovaMenuOpacityScale.current)
            } else {
                surfaces.panel
            },
            border = BorderStroke(
                1.dp,
                if (subtle) surfaces.panelBorder.copy(alpha = 0.30f * LocalNovaMenuOpacityScale.current) else surfaces.panelBorder
            ),
            content = content
        )
    }

    private fun sortModeLabel(sortMode: NovaLibrarySortMode): String = getString(
        when (sortMode) {
            NovaLibrarySortMode.LIBRARY_ORDER -> R.string.nova_library_options_sort_library_order
            NovaLibrarySortMode.RECENT -> R.string.nova_library_options_sort_recent
            NovaLibrarySortMode.NAME_ASC -> R.string.nova_library_options_sort_name_asc
            NovaLibrarySortMode.NAME_DESC -> R.string.nova_library_options_sort_name_desc
            NovaLibrarySortMode.SOURCE -> R.string.nova_library_options_sort_source
            NovaLibrarySortMode.HDR_FIRST -> R.string.nova_library_options_sort_hdr_first
        },
    )

    private fun sortModeDetail(sortMode: NovaLibrarySortMode): String = getString(
        when (sortMode) {
            NovaLibrarySortMode.LIBRARY_ORDER -> R.string.nova_library_options_sort_library_order_hint
            NovaLibrarySortMode.RECENT -> R.string.nova_library_options_sort_recent_hint
            NovaLibrarySortMode.NAME_ASC -> R.string.nova_library_options_sort_name_asc_hint
            NovaLibrarySortMode.NAME_DESC -> R.string.nova_library_options_sort_name_desc_hint
            NovaLibrarySortMode.SOURCE -> R.string.nova_library_options_sort_source_hint
            NovaLibrarySortMode.HDR_FIRST -> R.string.nova_library_options_sort_hdr_first_hint
        },
    )

    private fun layoutModeLabel(layoutMode: NovaLibraryLayoutMode): String =
        getString(layoutModeLabelRes(layoutMode))

    private fun layoutModeLabelRes(layoutMode: NovaLibraryLayoutMode): Int = when (layoutMode) {
        NovaLibraryLayoutMode.STAGE -> R.string.nova_library_options_layout_stage
        NovaLibraryLayoutMode.GRID -> R.string.nova_library_options_layout_grid
        NovaLibraryLayoutMode.COMPACT -> R.string.nova_library_options_layout_compact
    }

    private fun layoutModeDetail(layoutMode: NovaLibraryLayoutMode): String = getString(
        when (layoutMode) {
            NovaLibraryLayoutMode.STAGE -> R.string.nova_library_options_layout_stage_hint
            NovaLibraryLayoutMode.GRID -> R.string.nova_library_options_layout_grid_hint
            NovaLibraryLayoutMode.COMPACT -> R.string.nova_library_options_layout_compact_hint
        },
    )

    private fun genreLabel(genre: String): String =
        genre.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    /** The library as the grid sees it now, for pages built when they are pushed. */
    private fun currentLibraryModel(): NovaLibraryUiModel = NovaLibraryUiStateMapper.build(
        games = allGames,
        search = searchQuery,
        filterState = filterState,
        optionsState = optionsState,
        activeSession = activeSession,
    )

    /** The source, category or genre narrowing the grid, while one does. */
    private fun narrowedFilterLabel(state: NovaLibraryFilterState): String? = when (state.primary) {
        NovaLibraryPrimaryFilter.SOURCES -> state.source.takeIf { it.isNotBlank() }?.let(::sourceLabelFor)
        NovaLibraryPrimaryFilter.MORE -> when {
            state.category.isNotBlank() -> categoryLabelFor(state.category)
            state.genre.isNotBlank() -> genreLabel(state.genre)
            else -> null
        }
        else -> null
    }

    /**
     * What each page of the library's panel window draws. It composes in the panel's own window
     * and reads the library's state directly, so the grid behind and the page stay in step.
     */
    @Composable
    private fun NovaPageScope.LibraryPanelPage(page: NovaPage) {
        val model = remember(allGames, searchQuery, filterState, optionsState, activeSession) { currentLibraryModel() }
        when (page) {
            is LibraryPage.Options -> NovaLibraryOptionsPage(
                ui = NovaLibraryOptionsUi(
                    resultCount = model.resultCount,
                    searchQuery = searchQuery,
                    filter = filterState.primary,
                    filterCaption = stringResource(
                        R.string.nova_library_panel_filter_counts,
                        model.summary.totalCount,
                        model.summary.recentCount,
                        model.summary.hdrCount,
                    ),
                    narrowedLabel = narrowedFilterLabel(filterState),
                    sourceValue = filterState.source
                        .takeIf { filterState.primary == NovaLibraryPrimaryFilter.SOURCES && it.isNotBlank() }
                        ?.let(::sourceLabelFor)
                        ?: stringResource(R.string.nova_library_filter_all_sources),
                    moreValue = narrowedFilterLabel(filterState)
                        .takeIf { filterState.primary == NovaLibraryPrimaryFilter.MORE }
                        ?: stringResource(R.string.nova_library_panel_more_none),
                    clearable = hasClearableFilters(searchQuery, filterState),
                    sortLabel = sortModeLabel(optionsState.sortMode),
                    layoutMode = optionsState.layoutMode,
                    layoutCaption = layoutModeDetail(optionsState.layoutMode),
                    showPosterTitles = optionsState.showPosterTitles,
                    artwork = artworkLibraryUpdateState,
                ),
                actions = libraryOptionsActions,
            )
            is LibraryPage.System -> NovaLibrarySystemPage(ui = librarySystemUi(), actions = librarySystemActions)
            is LibraryPage.Search -> NovaLibrarySearchPage(
                query = searchQuery,
                resultCount = model.resultCount,
                onQueryChange = { searchQuery = it },
            )
            is LibraryPage.PolarisSync -> polarisSync?.let { controller ->
                @Suppress("DEPRECATION")
                NovaPolarisSyncPage(
                    controller = controller,
                    serverName = streamPcName.ifBlank { streamHost },
                    serverUuid = streamPcUuid,
                    display = windowManager.defaultDisplay,
                    playInPage = ::polarisPlayInPage,
                    profilePage = ::polarisProfilePage,
                )
            }
            is PlaySetupPage.PlayIn -> NovaPlayInPage(page)
            is PlaySetupPage.Options -> NovaPlaySetupOptionsPage(page)
            is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)
            else -> Unit
        }
    }

    private val libraryOptionsActions by lazy {
        NovaLibraryOptionsActions(
            onFilter = ::handlePrimaryFilter,
            searchPage = { LibraryPage.Search(getString(R.string.nova_library_panel_search)) },
            sourcesPage = ::librarySourcesPage,
            morePage = ::libraryMorePage,
            sortPage = ::librarySortPage,
            onClearFilters = ::clearFilters,
            onLayoutMode = ::selectLibraryLayoutMode,
            onPosterTitles = { show -> updateLibraryOptions { it.copy(showPosterTitles = show) } },
            onRefresh = { loadGames(forceRefresh = true) },
            onStartArtwork = { startArtworkLibraryUpdate() },
            onCancelArtwork = ::cancelArtworkLibraryUpdate,
            onRetryArtwork = { ids -> startArtworkLibraryUpdate(ids) },
        )
    }

    private val librarySystemActions by lazy {
        NovaLibrarySystemActions(
            onSwitchHost = ::finishWithTransition,
            onSettings = ::openSettings,
            polarisSyncPage = ::polarisSyncPage,
            onManageServer = ::openServerManagement,
            onHelp = ::openHelpDiagnostics,
            aboutPage = ::aboutNovaPage,
            onMatrix = ::openMatrixCommunity,
            onSponsor = ::openSponsor,
        )
    }

    /** The host System speaks for, read live: it changes as the host answers or goes away. */
    private fun librarySystemUi(): NovaLibrarySystemUi {
        val serverDisplayName = streamPcName.takeIf { it.isNotBlank() && it != streamHost }
        return NovaLibrarySystemUi(
            hostLabel = if (serverDisplayName == null) {
                getString(R.string.nova_system_menu_host_format, streamHost)
            } else {
                getString(R.string.nova_system_menu_host_named_format, streamHost, serverDisplayName)
            },
            status = getString(
                when {
                    clientSettings != null -> R.string.nova_system_menu_status_polaris_ready
                    !loadErrorMessage.isNullOrBlank() -> R.string.nova_system_menu_status_offline
                    else -> R.string.nova_system_menu_status_checking
                },
            ),
            ready = clientSettings != null,
            mode = compactStatusModeLabel(clientSettings),
        )
    }

    /** Sources: every launcher or store in the library, with how many games each has. */
    private fun librarySourcesPage(): NovaPage {
        val model = currentLibraryModel()
        val options = listOf(
            NovaOption<String?>(
                value = null,
                label = getString(R.string.nova_library_filter_all_sources),
                caption = getString(R.string.nova_library_filter_source_count, model.summary.totalCount),
            ),
        ) + NovaLibraryUiStateMapper.sourceFilters(model.allGames).map { source ->
            NovaOption<String?>(
                value = source,
                label = sourceLabelFor(source),
                caption = getString(
                    R.string.nova_library_filter_source_count,
                    model.allGames.count { it.source.equals(source, ignoreCase = true) },
                ),
            )
        }
        return NovaCommonPage.Choice(
            key = LibraryPage.KEY_SOURCES,
            title = getString(R.string.nova_library_filter_sheet_sources),
            options = options,
            current = filterState.source.takeIf { filterState.primary == NovaLibraryPrimaryFilter.SOURCES && it.isNotBlank() },
            onChoose = ::applySourceFilter,
        )
    }

    /** More filters: back to the whole library, then each category, then each genre. */
    private fun libraryMorePage(): NovaPage {
        val model = currentLibraryModel()
        val options = buildList {
            add(
                NovaOption<NovaLibraryMoreFilter>(
                    value = NovaLibraryMoreFilter.Clear,
                    label = getString(R.string.nova_library_filter_clear_more),
                    caption = getString(R.string.nova_library_filter_clear_more_hint),
                ),
            )
            // Each name once: Action was listed as a category and again as a genre (N16).
            NovaLibraryUiStateMapper.moreFilterEntries(model.allGames, ::categoryLabelFor, ::genreLabel).forEach { entry ->
                when (entry) {
                    is NovaLibraryMoreFilter.Category -> {
                        val category = entry.id
                        val count = model.allGames.count { it.category.equals(category, ignoreCase = true) }
                        add(
                            NovaOption<NovaLibraryMoreFilter>(
                                value = entry,
                                label = categoryLabelFor(category),
                                // "1 games" read as a typo; the count takes its plural.
                                caption = resources.getQuantityString(R.plurals.nova_library_panel_category_caption, count, count),
                            ),
                        )
                    }
                    is NovaLibraryMoreFilter.Genre -> {
                        val genre = entry.name
                        val count = model.allGames.count { game -> game.genres.any { it.equals(genre, ignoreCase = true) } }
                        add(
                            NovaOption<NovaLibraryMoreFilter>(
                                value = entry,
                                label = genreLabel(genre),
                                caption = resources.getQuantityString(R.plurals.nova_library_panel_genre_caption, count, count),
                            ),
                        )
                    }
                    NovaLibraryMoreFilter.Clear -> Unit
                }
            }
        }
        val current: NovaLibraryMoreFilter? = when {
            filterState.primary == NovaLibraryPrimaryFilter.MORE && filterState.category.isNotBlank() ->
                NovaLibraryMoreFilter.Category(filterState.category)
            filterState.primary == NovaLibraryPrimaryFilter.MORE && filterState.genre.isNotBlank() ->
                NovaLibraryMoreFilter.Genre(filterState.genre)
            filterState.primary == NovaLibraryPrimaryFilter.ALL && searchQuery.isBlank() -> NovaLibraryMoreFilter.Clear
            else -> null
        }
        return NovaCommonPage.Choice(
            key = LibraryPage.KEY_MORE,
            title = getString(R.string.nova_library_filter_sheet_more),
            options = options,
            current = current,
            onChoose = { choice ->
                when (choice) {
                    NovaLibraryMoreFilter.Clear -> clearFilters()
                    is NovaLibraryMoreFilter.Category -> applyCategoryFilter(choice.id)
                    is NovaLibraryMoreFilter.Genre -> applyGenreFilter(choice.name)
                }
            },
        )
    }

    /** Sort: six orders, each saying what it does, opening on the current one. */
    private fun librarySortPage(): NovaPage = NovaCommonPage.Choice(
        key = LibraryPage.KEY_SORT,
        title = getString(R.string.nova_library_options_sort_title),
        options = NovaLibrarySortMode.entries.map { NovaOption(it, sortModeLabel(it), caption = sortModeDetail(it)) },
        current = optionsState.sortMode,
        onChoose = { mode -> updateLibraryOptions { it.copy(sortMode = mode) } },
    )

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_HTTPS_PORT = "https_port"
        const val EXTRA_HTTP_PORT = "http_port"
        const val EXTRA_UNIQUE_ID = "unique_id"
        const val EXTRA_PC_UUID = "pc_uuid"
        const val EXTRA_SERVER_COMMANDS = "server_commands"
        const val EXTRA_SERVER_CERT = "server_cert"
        const val EXTRA_SPACES_AVAILABLE = "spaces_available"
        private const val CONTROLLER_HINT_IDLE_REVEAL_MS = 4_000L
        private const val ABOUT_NOTICE_KEY = "nova-library-about"
        private const val MANAGE_FAILED_NOTICE_KEY = "nova-library-manage-failed"
        private const val SPACES_POLL_OPEN_MS = 5_000L
        private const val SPACES_POLL_CLOSED_MS = 15_000L
        private const val SPACES_POLL_SLOW_MS = 30_000L
        private const val SPACES_POLL_MAX_MS = 60_000L
        private const val SPACE_OPEN_CHECK_TIMEOUT_MS = 12_000L
        private const val CONTROLLER_HINT_ANIMATION_MS = 180
        private const val CONTROLLER_AXIS_INTENT_THRESHOLD = 0.35f
        /** One corner radius for library surfaces, so panels, the continue-playing row and
         *  the bar keep the same edge whichever layout is showing. */
        private val NovaLibrarySurfaceCornerRadius = NovaRadius.hero

        /** The poster wall owns the screen in grid layouts, so the shared backdrop reads
         *  as atmosphere behind it rather than competing with twenty covers. */
        private const val NovaLibraryGridBackdropStrength = 0.45f

        /**
         * Height of the fade at the foot of the scrolling poster grid. It reads as
         * more content below. The grid fades its own pixels (novaLibraryGridBottomFade)
         * instead of painting a wash: the shell stops the grid above the controller
         * hints, so a painted wash ended in a line there and, with no panel framing the
         * grid, read as a second background over the backdrop.
         */
        private val NovaLibraryGridScrollFadeHeight =
            (NovaLibraryUiStateMapper.controllerHintBarMinHeightDp() + 38).dp

        private val LARGE_TEXT_HINT_INDICES = setOf(0, 1, 3)
        /** Keys a remote answers with, beside the D-pad, for telling a remote from a controller. */
        private val REMOTE_ANSWER_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
        )
        private val PRIMARY_HINT_INDICES = setOf(0, 1, 2)
        private val CONTROLLER_BROWSE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END
        )
        private val CONTROLLER_BROWSE_AXES = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX,
            MotionEvent.AXIS_RY
        )
        private val ACTIVE_SESSION_RESUME_REFRESH_DELAYS_MS = longArrayOf(1500L, 2000L, 3000L, 5000L, 8000L)
    }
}

/**
 * Keeps a poster that focus scrolls to the top of the grid clear of the grid's top edge by
 * its focus rise, so a lifted row stays whole the way the first row does under its top
 * inset. The mapper owns the arithmetic so it is tested off the device.
 */
@OptIn(ExperimentalFoundationApi::class)
private class NovaGridFocusRiseScrollSpec(private val leadingMarginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        NovaLibraryUiStateMapper.gridFocusScrollDistance(offset, size, containerSize, leadingMarginPx)
}
