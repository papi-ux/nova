package com.papi.nova.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.papi.nova.LimeLog
import com.papi.nova.NovaSessionEndSignal
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
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
import com.papi.nova.utils.UiHelper
import com.papi.nova.ui.SpaceParticleView
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.compose.NovaFocusMotionSpec
import com.papi.nova.ui.compose.NovaMenuBackdropBlur
import com.papi.nova.ui.compose.novaFocusMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

private data class LibraryLoadResult(
    val games: List<PolarisGame>,
    val settings: PolarisClientSettings?,
    val activeSession: NovaLibraryActiveSessionUiState?
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
    private var detailSheet: NovaGameDetailSheet? = null

    private var allGames by mutableStateOf<List<PolarisGame>>(emptyList())
    private var filterState by mutableStateOf(NovaLibraryFilterState())
    private var searchQuery by mutableStateOf("")
    private var isInitialLoading by mutableStateOf(true)
    private var isRefreshing by mutableStateOf(false)
    private var loadErrorMessage by mutableStateOf<String?>(null)
    private var launchErrorMessage by mutableStateOf<String?>(null)
    private var clientSettings by mutableStateOf<PolarisClientSettings?>(null)
    private var activeSession by mutableStateOf<NovaLibraryActiveSessionUiState?>(null)
    private var activeFilterSheet by mutableStateOf<LibraryFilterSheet?>(null)
    private var optionsState by mutableStateOf(NovaLibraryOptionsState())
    private var activeOptionsSheet by mutableStateOf(false)
    private var activeSystemMenu by mutableStateOf(false)
    private var lastFocusedGameId by mutableStateOf<String?>(null)
    private var lastFocusedPrimaryFilter by mutableStateOf(NovaLibraryPrimaryFilter.ALL)
    private var controllerHintChromeState by mutableStateOf(NovaControllerHintChromeState())
    private var activeSessionRefreshJob: Job? = null
    private var controllerHintIdleJob: Job? = null
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

        if (streamHost.isBlank()) {
            finish()
            return
        }

        apiClient = PolarisApiClient(this, streamHost, streamHttpsPort, streamServerCert)
        val libraryPreferences = libraryPreferences()
        optionsState = NovaLibraryPreferences.loadOptions(libraryPreferences)
        filterState = NovaLibraryPreferences.loadFilterState(libraryPreferences)
        lastFocusedPrimaryFilter = filterState.primary

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
                        activeFilterSheet = activeFilterSheet,
                        activeOptionsSheet = activeOptionsSheet,
                        activeSystemMenu = activeSystemMenu,
                        controllerHintsVisible = controllerHintChromeState.visible,
                        restoreFocusGameId = lastFocusedGameId,
                        restoreFocusPrimaryFilter = lastFocusedPrimaryFilter,
                        onBack = ::finishWithTransition,
                        onSearchChange = { searchQuery = it },
                        onRefresh = { loadGames(forceRefresh = true) },
                        onResumeSession = ::resumeActiveSession,
                        onEndSession = ::endActiveSession,
                        onManageServer = ::openServerManagement,
                        onOpenDetail = ::showGameDetail,
                        onGameFocused = { lastFocusedGameId = it.id },
                        onPrimaryFilter = ::handlePrimaryFilter,
                        onPrimaryFilterFocused = { lastFocusedPrimaryFilter = it },
                        onOpenOptions = ::openLibraryOptionsSheet,
                        onDismissOptionsSheet = ::dismissLibraryOptionsSheet,
                        onOpenSystemMenu = ::openLibrarySystemMenu,
                        onDismissSystemMenu = ::dismissLibrarySystemMenu,
                        onOpenSettings = ::openSettings,
                        onOpenPolarisSync = ::openPolarisSync,
                        onOpenHelpDiagnostics = ::openHelpDiagnostics,
                        onOpenAbout = ::showAboutNova,
                        onSortMode = { sortMode ->
                            updateLibraryOptions { it.copy(sortMode = sortMode) }
                        },
                        onLayoutMode = ::selectLibraryLayoutMode,
                        onPosterTitlesVisible = { showPosterTitles ->
                            updateLibraryOptions { it.copy(showPosterTitles = showPosterTitles) }
                        },
                        onDismissFilterSheet = { activeFilterSheet = null },
                        onSourceFilter = ::applySourceFilter,
                        onCategoryFilter = ::applyCategoryFilter,
                        onGenreFilter = ::applyGenreFilter,
                        onClearFilters = ::clearFilters
                    )
                }
            }
        }
        setContentView(content)
        loadGames(forceRefresh = false)
    }

    private val hasActiveLibraryOverlay: Boolean
        get() = activeSystemMenu || activeOptionsSheet || activeFilterSheet != null

    private fun openLibraryOptionsSheet() {
        activeSystemMenu = false
        activeFilterSheet = null
        activeOptionsSheet = true
    }

    private fun dismissLibraryOptionsSheet() {
        activeOptionsSheet = false
    }

    private fun openLibrarySystemMenu() {
        activeOptionsSheet = false
        activeFilterSheet = null
        activeSystemMenu = true
    }

    private fun dismissLibrarySystemMenu() {
        activeSystemMenu = false
    }

    private fun dismissActiveLibraryOverlay(): Boolean {
        return when {
            activeSystemMenu -> {
                activeSystemMenu = false
                true
            }
            activeOptionsSheet -> {
                activeOptionsSheet = false
                true
            }
            activeFilterSheet != null -> {
                activeFilterSheet = null
                true
            }
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
        lastFocusedPrimaryFilter = normalized.primary
        NovaLibraryPreferences.persistFilterState(libraryPreferences(), normalized)
    }

    override fun onResume() {
        super.onResume()
        if (recreateForThemeChangeIfNeeded()) return
        revealControllerHints(NovaControllerHintChromeEvent.EXPLICIT_REVEAL)
        if (::apiClient.isInitialized && !isInitialLoading) {
            if (consumeLocalSessionEndSignal()) {
                activeSession = null
                scheduleActiveSessionFollowUpRefreshes(clearOnly = true)
            } else {
                refreshActiveSession(scheduleFollowUps = true)
            }
        }
    }

    private fun recreateForThemeChangeIfNeeded(): Boolean {
        val currentTheme = NovaThemeManager.getTheme(this)
        if (appliedTheme == currentTheme) return false
        appliedTheme = currentTheme
        recreate()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B && dismissActiveLibraryOverlay()) {
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!activeOptionsSheet) openLibraryOptionsSheet()
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!activeSystemMenu) openLibrarySystemMenu()
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (!activeOptionsSheet) openLibraryOptionsSheet()
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> cycleLibraryLayoutMode()
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
                if (!activeSystemMenu) openLibrarySystemMenu()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in CONTROLLER_BROWSE_KEYS) {
            registerControllerBrowseIntent()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && event.action == MotionEvent.ACTION_MOVE && event.hasControllerBrowseMotion()) {
            registerControllerBrowseIntent()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun MotionEvent.hasControllerBrowseMotion(): Boolean {
        return CONTROLLER_BROWSE_AXES.any { axis ->
            abs(getAxisValue(axis)) >= CONTROLLER_AXIS_INTENT_THRESHOLD
        }
    }

    private fun registerControllerBrowseIntent() {
        if (hasActiveLibraryOverlay) return
        controllerHintChromeState = controllerHintChromeState.reduce(
            NovaControllerHintChromeEvent.BROWSE_INTENT
        )
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
        if (activeOptionsSheet || activeSystemMenu || activeFilterSheet != null) {
            return false
        }
        val nextMode = optionsState.layoutMode.next()
        selectLibraryLayoutMode(nextMode)
        Toast.makeText(
            this,
            getString(R.string.nova_library_layout_toast_format, getString(layoutModeLabelRes(nextMode))),
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    override fun onStop() {
        activeSessionRefreshJob?.cancel()
        activeSessionRefreshJob = null
        controllerHintIdleJob?.cancel()
        controllerHintIdleJob = null
        detailSheet?.dismissAllowingStateLoss()
        detailSheet = null
        super.onStop()
    }

    private fun loadGames(forceRefresh: Boolean) {
        if (forceRefresh) {
            isRefreshing = true
        } else {
            isInitialLoading = true
        }
        loadErrorMessage = null
        launchErrorMessage = null

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val games = apiClient.getGames(limit = 100)
                    val settings = try {
                        apiClient.getClientSettings()
                    } catch (e: Exception) {
                        LimeLog.warning("Nova: Failed to load client settings: ${e.message}")
                        null
                    }
                    LibraryLoadResult(
                        games = games,
                        settings = settings,
                        activeSession = queryActiveSession()
                    )
                }
                apiClient.clearCoverCache()
                allGames = result.games
                clientSettings = result.settings
                val clearSessionAfterLocalEnd = consumeLocalSessionEndSignal()
                activeSession = if (clearSessionAfterLocalEnd) null else result.activeSession
                if (clearSessionAfterLocalEnd) {
                    scheduleActiveSessionFollowUpRefreshes(clearOnly = true)
                } else if (result.activeSession != null) {
                    scheduleActiveSessionFollowUpRefreshes()
                } else {
                    activeSessionRefreshJob?.cancel()
                    activeSessionRefreshJob = null
                }
                loadErrorMessage = null
                LimeLog.info("Nova: Loaded ${allGames.size} games")
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                loadErrorMessage = message
                LimeLog.severe("Nova: Failed to load games: ${e.message}")
                Toast.makeText(
                    this@NovaLibraryActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isInitialLoading = false
                isRefreshing = false
            }
        }
    }

    private fun refreshActiveSession(scheduleFollowUps: Boolean = false) {
        if (scheduleFollowUps) {
            activeSessionRefreshJob?.cancel()
            activeSessionRefreshJob = null
        }
        lifecycleScope.launch {
            try {
                val refreshed = withContext(Dispatchers.IO) {
                    queryActiveSession()
                }
                activeSession = refreshed
                if (scheduleFollowUps && refreshed != null) {
                    scheduleActiveSessionFollowUpRefreshes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LimeLog.warning("Nova: Failed to refresh active session: ${e.message}")
            }
        }
    }

    private fun scheduleActiveSessionFollowUpRefreshes(clearOnly: Boolean = false) {
        activeSessionRefreshJob?.cancel()
        activeSessionRefreshJob = lifecycleScope.launch {
            for (delayMillis in ACTIVE_SESSION_RESUME_REFRESH_DELAYS_MS) {
                delay(delayMillis)
                val refreshed = try {
                    withContext(Dispatchers.IO) {
                        queryActiveSession()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Failed to refresh active session after stream return: ${e.message}")
                    continue
                }
                if (clearOnly && refreshed != null) {
                    continue
                }
                activeSession = refreshed
                if (refreshed == null) {
                    break
                }
            }
        }
    }

    private fun consumeLocalSessionEndSignal(): Boolean {
        val consumed = NovaSessionEndSignal.consume(this, streamPcUuid, streamHost)
        if (consumed) {
            LimeLog.info("Nova: Clearing active session card after local End request")
        }
        return consumed
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
            NovaLibraryPrimaryFilter.SOURCES -> {
                activeOptionsSheet = false
                activeFilterSheet = LibraryFilterSheet.SOURCES
            }
            NovaLibraryPrimaryFilter.HDR -> updateLibraryFilterState(
                NovaLibraryFilterState(primary = filter)
            )
            NovaLibraryPrimaryFilter.MORE -> {
                activeOptionsSheet = false
                activeFilterSheet = LibraryFilterSheet.MORE
            }
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
        activeFilterSheet = null
    }

    private fun applyCategoryFilter(category: String) {
        updateLibraryFilterState(
            NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.MORE, category = category)
        )
        activeFilterSheet = null
    }

    private fun applyGenreFilter(genre: String) {
        updateLibraryFilterState(
            NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.MORE, genre = genre)
        )
        activeFilterSheet = null
    }

    private fun clearFilters() {
        updateLibraryFilterState(NovaLibraryFilterState())
        searchQuery = ""
        activeFilterSheet = null
    }

    private fun hasClearableFilters(
        searchQuery: String,
        filterState: NovaLibraryFilterState
    ): Boolean = searchQuery.isNotBlank() || filterState.hasActiveConstraint

    private fun showGameDetail(game: PolarisGame) {
        launchErrorMessage = null
        detailSheet?.dismissAllowingStateLoss()
        val preferences = PreferenceConfiguration.readPreferences(this)
        val defaultToVirtualDisplay = preferences.useVirtualDisplay
        detailSheet = NovaGameDetailSheet.newInstance(
            game = game,
            apiClient = apiClient,
            defaultToVirtualDisplay = defaultToVirtualDisplay,
            clientSettings = clientSettings
        ) { selectedGame, withVirtualDisplay, mirrorDesktop, forcePrivateAfterSteamClose, profilePreference, preflightOptimization ->
            launchGame(selectedGame, withVirtualDisplay, mirrorDesktop, forcePrivateAfterSteamClose, profilePreference, preflightOptimization)
        }
        detailSheet?.show(supportFragmentManager, "game_detail")
    }

    private fun launchGame(
        game: PolarisGame,
        withVirtualDisplay: Boolean,
        mirrorDesktop: Boolean = false,
        forcePrivateAfterSteamClose: Boolean = false,
        profilePreference: String = "auto",
        preflightOptimization: org.json.JSONObject? = null
    ) {
        if (game.appId <= 0) {
            val message = "This game entry is missing a launch ID"
            launchErrorMessage = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        val uniqueId = streamUniqueId
        val pcUuid = streamPcUuid
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || pcUuid.isNullOrBlank() || serverCert == null) {
            val message = "Missing Polaris session details for launch"
            launchErrorMessage = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            LimeLog.warning("Nova: Cannot launch from library; missing uniqueId, pcUuid, or server cert")
            return
        }
        launchErrorMessage = null

        Toast.makeText(
            this,
            getString(
                R.string.nova_library_launching_mode,
                game.name,
                when {
                    withVirtualDisplay -> getString(R.string.nova_library_launch_virtual_display)
                    mirrorDesktop -> getString(R.string.nova_desktop_steam_mirror_desktop)
                    forcePrivateAfterSteamClose -> getString(R.string.nova_desktop_steam_force_private)
                    else -> getString(R.string.nova_library_launch_headless)
                }
            ),
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            try {
                val mangoHudSynced = withContext(Dispatchers.IO) {
                    apiClient.setMangoHud(game.id, game.mangohud)
                }
                if (!mangoHudSynced) {
                    LimeLog.warning("Nova: MangoHUD launch state sync failed; continuing launch")
                }
                val preferences = com.papi.nova.preferences.PreferenceConfiguration.readPreferences(this@NovaLibraryActivity)
                val launchResolution = StreamSyncManager.resolveAutoSafeResolution(
                    preferences.width,
                    preferences.height,
                    preflightOptimization
                )
                val launchFps = StreamSyncManager.resolveAutoSafeTargetFps(
                    preferences.fps,
                    preflightOptimization
                )
                LimeLog.info(
                    "Nova: Launch resolved stream mode " +
                        launchResolution.width + "x" + launchResolution.height + "x" + launchFps + " " +
                        "source=" + (preflightOptimization?.optString("source", "") ?: "") + " " +
                        "effectiveFps=" + (preflightOptimization?.optDouble("effective_target_fps", 0.0) ?: 0.0) + " " +
                        "displayMode=" + (preflightOptimization?.optString("display_mode", "") ?: "")
                )
                val syncedSettings = withContext(Dispatchers.IO) {
                    apiClient.updateClientSettings(
                        streamDisplayMode = if (mirrorDesktop) {
                            PolarisClientSettings.MODE_DESKTOP_DISPLAY
                        } else {
                            PolarisStreamDisplayMode.preflightModeForLaunch(withVirtualDisplay, clientSettings)
                        },
                        displayMode = com.papi.nova.preferences.PreferenceConfiguration.formatStreamingDisplayMode(
                            launchResolution.width,
                            launchResolution.height,
                            launchFps
                        ),
                        targetBitrateKbps = preferences.bitrate.takeIf { it > 0 }
                    )
                }
                if (syncedSettings == null) {
                    LimeLog.warning("Nova: Preflight client settings sync failed; continuing launch")
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
                    withVirtualDisplay,
                    true,
                    false,
                    serverCert,
                    launchResolution.width,
                    launchResolution.height,
                    launchFps,
                    aiProfilePreference = profilePreference,
                    launchOptimizationJson = preflightOptimization?.toString(),
                    mirrorDesktop = mirrorDesktop,
                    forcePrivateAfterSteamClose = forcePrivateAfterSteamClose
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                launchErrorMessage = message
                LimeLog.severe("Nova: Failed to launch ${game.name}: ${e.message}")
                Toast.makeText(this@NovaLibraryActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resumeActiveSession(session: NovaLibraryActiveSessionUiState) {
        val uniqueId = streamUniqueId
        val pcUuid = streamPcUuid
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || pcUuid.isNullOrBlank() || serverCert == null) {
            val message = "Missing Polaris session details for resume"
            launchErrorMessage = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        ServerHelper.doStart(
            this,
            app,
            streamHost,
            streamHttpPort,
            streamHttpsPort,
            uniqueId,
            pcUuid,
            streamPcName,
            streamServerCommands,
            session.virtualDisplay,
            session.displayModeExplicit,
            session.watchOnly,
            serverCert,
            session.streamWidth,
            session.streamHeight,
            session.streamFps
        )
    }

    private fun endActiveSession(session: NovaLibraryActiveSessionUiState) {
        val uniqueId = streamUniqueId
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || serverCert == null) {
            Toast.makeText(this, "Missing Polaris session details for End", Toast.LENGTH_SHORT).show()
            LimeLog.warning("Nova: Cannot end session from library; missing uniqueId or server cert")
            return
        }

        val gameName = session.gameName.ifBlank { getString(R.string.applist_menu_watch_active_name) }
        val httpConn = NvHTTP(
            ComputerDetails.AddressTuple(streamHost, streamHttpPort),
            streamHttpsPort,
            uniqueId,
            PolarisApiClient.decodeCertificate(serverCert),
            PlatformBinding.getCryptoProvider(this)
        )
        UiHelper.displayQuitConfirmationDialog(
            this,
            {
                ServerHelper.doQuit(
                    this,
                    httpConn,
                    gameName,
                    {
                        runOnUiThread {
                            activeSession = null
                            scheduleActiveSessionFollowUpRefreshes(clearOnly = true)
                        }
                    },
                    {
                        runOnUiThread { refreshActiveSession(scheduleFollowUps = true) }
                    }
                )
            },
            null
        )
    }

    private fun openServerManagement() {
        val managementPort = if (streamHttpPort > 0) streamHttpPort + 1 else 47990
        val managementUrl = "https://$streamHost:$managementPort"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(managementUrl)))
        } catch (e: Exception) {
            LimeLog.warning("Nova: Failed to open server management: ${e.message}")
            Toast.makeText(this, R.string.nova_library_manage_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, StreamSettings::class.java))
        NovaThemeManager.applyFadeTransition(this)
    }

    private fun openPolarisSync() {
        NovaPolarisSyncSheet.newInstance(
            apiClient = apiClient,
            serverName = streamPcName.ifBlank { streamHost },
            serverUuid = streamPcUuid,
            initialSettings = clientSettings
        ) { settings ->
            clientSettings = settings
        }.show(supportFragmentManager, "polaris_sync")
    }

    private fun openHelpDiagnostics() {
        HelpLauncher.launchTroubleshooting(this)
    }

    private fun showAboutNova() {
        Toast.makeText(
            this,
            getString(R.string.nova_system_menu_about_toast, NovaAppVersion.current()),
            Toast.LENGTH_LONG
        ).show()
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
        return remember(games, searchQuery, filterState, activeSession, optionsState) {
            NovaLibraryUiStateMapper.build(
                games = games,
                search = searchQuery,
                filterState = filterState,
                optionsState = optionsState,
                activeSession = activeSession
            )
        }
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
        activeFilterSheet: LibraryFilterSheet?,
        activeOptionsSheet: Boolean,
        activeSystemMenu: Boolean,
        controllerHintsVisible: Boolean,
        restoreFocusGameId: String?,
        restoreFocusPrimaryFilter: NovaLibraryPrimaryFilter,
        onBack: () -> Unit,
        onSearchChange: (String) -> Unit,
        onRefresh: () -> Unit,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onEndSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onManageServer: () -> Unit,
        onOpenDetail: (PolarisGame) -> Unit,
        onGameFocused: (PolarisGame) -> Unit,
        onPrimaryFilter: (NovaLibraryPrimaryFilter) -> Unit,
        onPrimaryFilterFocused: (NovaLibraryPrimaryFilter) -> Unit,
        onOpenOptions: () -> Unit,
        onDismissOptionsSheet: () -> Unit,
        onOpenSystemMenu: () -> Unit,
        onDismissSystemMenu: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenPolarisSync: () -> Unit,
        onOpenHelpDiagnostics: () -> Unit,
        onOpenAbout: () -> Unit,
        onSortMode: (NovaLibrarySortMode) -> Unit,
        onLayoutMode: (NovaLibraryLayoutMode) -> Unit,
        onPosterTitlesVisible: (Boolean) -> Unit,
        onDismissFilterSheet: () -> Unit,
        onSourceFilter: (String?) -> Unit,
        onCategoryFilter: (String) -> Unit,
        onGenreFilter: (String) -> Unit,
        onClearFilters: () -> Unit,
    ) {
        if (activeOptionsSheet || activeSystemMenu) {
            NovaMenuBackdropBlur()
        }
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
        val spotlightMode = model.optionsState.layoutMode == NovaLibraryLayoutMode.SPOTLIGHT
        val showLandscapeControlRail = NovaLibraryUiStateMapper.showLandscapeControlRail()
        val columns = NovaLibraryUiStateMapper.gridColumnsForScreen(
            configuration.screenWidthDp,
            isLandscape,
            model.optionsState.layoutMode
        )
        val railWidth = NovaLibraryUiStateMapper.railWidthDp(configuration.screenWidthDp).dp
        val showLandscapeRecentRail = !spotlightMode &&
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = configuration.screenHeightDp,
                heroReason = model.hero.reason,
                recentCount = model.recentGames.size
            )
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val controllerHintBarBottomPadding = NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp
        val controllerHintBarLandscapeStartPadding = if (isLandscape && showLandscapeControlRail) railWidth + 10.dp else 0.dp
        val restoreFocusGameInRecent = !spotlightMode && restoreFocusGameId != null &&
            model.recentGames.any { it.id == restoreFocusGameId }
        val focusedBackdropGame = remember(
            model.filteredGames,
            model.recentGames,
            model.allGames,
            model.hero.game,
            restoreFocusGameId
        ) {
            restoreFocusGameId
                ?.let { focusedId -> model.allGames.firstOrNull { it.id == focusedId } }
                ?: model.hero.game
                ?: model.filteredGames.firstOrNull()
                ?: model.recentGames.firstOrNull()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.window)
        ) {
            NovaLibraryFocusedBackdrop(
                game = focusedBackdropGame,
                apiClient = apiClient
            )
            if (surfaces.particlesEnabled) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(surfaces.particleAlpha),
                    factory = { context ->
                        SpaceParticleView(context).apply { dense = true }
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(surfaces.backgroundScrim)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(NovaLibraryUiStateMapper.screenPaddingDp(isLandscape).dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isLandscape) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = controllerHintBarBottomPadding),
                            verticalArrangement = Arrangement.spacedBy(NovaLibraryUiStateMapper.landscapeContentSpacingDp().dp)
                        ) {
                            NovaLibraryLandscapeToolbar(
                                serverName = serverName,
                                serverHost = serverHost,
                                model = model,
                                clientSettings = clientSettings,
                                onOpenOptions = onOpenOptions,
                                onOpenSystemMenu = onOpenSystemMenu
                            )
                            if (!spotlightMode || activeSession != null) {
                                NovaLibraryHomeHero(
                                    hero = model.hero,
                                    compact = true,
                                    apiClient = apiClient,
                                    onPrimaryAction = {
                                        when (model.hero.primaryAction) {
                                            NovaLibraryHeroPrimaryAction.RESUME,
                                            NovaLibraryHeroPrimaryAction.WATCH -> activeSession?.let(onResumeSession)
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
                                    onGameFocused = onGameFocused
                                )
                            }
                            NovaLibraryContent(
                                modifier = Modifier.weight(1f),
                                model = model,
                                filterState = filterState,
                                columns = columns,
                                isLandscape = true,
                                isInitialLoading = isInitialLoading,
                                isRefreshing = isRefreshing,
                                loadErrorMessage = loadErrorMessage,
                                launchErrorMessage = launchErrorMessage,
                                apiClient = apiClient,
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
                            if (!spotlightMode || activeSession != null) {
                                NovaLibraryHomeHero(
                                    hero = model.hero,
                                    compact = false,
                                    apiClient = apiClient,
                                    onPrimaryAction = {
                                        when (model.hero.primaryAction) {
                                            NovaLibraryHeroPrimaryAction.RESUME,
                                            NovaLibraryHeroPrimaryAction.WATCH -> activeSession?.let(onResumeSession)
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
                                    onGameFocused = onGameFocused
                                )
                            }
                            if (!spotlightMode && model.recentGames.isNotEmpty()) {
                                NovaLibraryRecentRail(
                                    games = model.recentGames,
                                    apiClient = apiClient,
                                    restoreFocusGameId = restoreFocusGameId,
                                    showPosterTitles = model.optionsState.showPosterTitles,
                                    onGameFocused = onGameFocused,
                                    onOpenDetail = onOpenDetail
                                )
                            }
                            NovaLibraryContent(
                                modifier = Modifier.weight(1f),
                                model = model,
                                filterState = filterState,
                                columns = columns,
                                isLandscape = false,
                                isInitialLoading = isInitialLoading,
                                isRefreshing = isRefreshing,
                                loadErrorMessage = loadErrorMessage,
                                launchErrorMessage = launchErrorMessage,
                                apiClient = apiClient,
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
                    visible = controllerHintsVisible,
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
                    NovaControllerHintBar(
                        hints = novaLibraryControllerHints(isLandscape),
                        compact = isLandscape,
                        modifier = Modifier
                            .padding(start = controllerHintBarLandscapeStartPadding)
                            .fillMaxWidth()
                    )
                }
            }

            when {
                activeSystemMenu -> {
                    NovaSystemMenuSheet(
                        serverName = serverName,
                        serverHost = serverHost,
                        clientSettings = clientSettings,
                        loadErrorMessage = loadErrorMessage,
                        onDismiss = onDismissSystemMenu,
                        onOpenOptions = onOpenOptions,
                        onSwitchHost = onBack,
                        onOpenSettings = onOpenSettings,
                        onOpenPolarisSync = onOpenPolarisSync,
                        onManageServer = onManageServer,
                        onOpenHelpDiagnostics = onOpenHelpDiagnostics,
                        onOpenAbout = onOpenAbout
                    )
                }
                activeOptionsSheet -> {
                    NovaLibraryOptionsSheet(
                        optionsState = model.optionsState,
                        model = model,
                        filterState = filterState,
                        searchQuery = searchQuery,
                        restoreFocusPrimaryFilter = restoreFocusPrimaryFilter,
                        onSearchChange = onSearchChange,
                        onPrimaryFilter = onPrimaryFilter,
                        onPrimaryFilterFocused = onPrimaryFilterFocused,
                        onClearFilters = onClearFilters,
                        sourceLabel = { sourceLabelFor(it) },
                        onDismiss = onDismissOptionsSheet,
                        onOpenSystemMenu = onOpenSystemMenu,
                        onRefresh = onRefresh,
                        onSortMode = onSortMode,
                        onLayoutMode = onLayoutMode,
                        onPosterTitlesVisible = onPosterTitlesVisible
                    )
                }
                activeFilterSheet != null -> {
                    NovaLibraryFilterSheet(
                        sheet = activeFilterSheet,
                        model = model,
                        filterState = filterState,
                        onDismiss = onDismissFilterSheet,
                        onSourceFilter = onSourceFilter,
                        onCategoryFilter = onCategoryFilter,
                        onGenreFilter = onGenreFilter,
                        onClearFilters = onClearFilters,
                        sourceLabel = { sourceLabelFor(it) },
                        categoryLabel = { categoryLabelFor(it) }
                    )
                }
            }
        }
    }

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
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_x),
                label = stringResource(R.string.nova_controller_hint_library)
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

    @Composable
    private fun NovaLibraryFocusedBackdrop(
        game: PolarisGame?,
        apiClient: PolarisApiClient
    ) {
        val surfaces = LocalNovaLibrarySurfaces.current
        val artworkGame = game

        Crossfade(
            targetState = artworkGame,
            animationSpec = tween(durationMillis = 520),
            label = "NovaLibraryFocusedBackdrop"
        ) { targetGame ->
            if (targetGame != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    key(targetGame.id, targetGame.coverUrl) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = surfaces.focusedArtworkAlpha
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                },
                            factory = { context ->
                                ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    contentDescription = null
                                    apiClient.loadCoverInto(this, targetGame)
                                }
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(surfaces.focusedArtworkScrim)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to surfaces.focusedArtworkScrim.copy(alpha = 0.92f),
                                        0.45f to surfaces.focusedArtworkScrim.copy(alpha = 0.58f),
                                        1.0f to surfaces.focusedArtworkScrim.copy(alpha = 0.96f)
                                    )
                                )
                            )
                    )
                }
            }
        }
    }

    @Composable
    private fun NovaLibraryHomeHero(
        hero: NovaLibraryHeroState,
        compact: Boolean,
        apiClient: PolarisApiClient,
        onPrimaryAction: () -> Unit,
        onSecondaryAction: (() -> Unit)? = null,
        onGameFocused: (PolarisGame) -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val heroGame = hero.game
        val height = NovaLibraryUiStateMapper.heroHeightDp(compact = compact).dp
        val showCaption = !compact || hero.badges.isEmpty()
        var focused by remember { mutableStateOf(false) }
        LaunchedEffect(focused, heroGame) {
            if (focused && heroGame != null) {
                onGameFocused(heroGame)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .novaFocusMotion(
                    focused = focused,
                    focusedScale = NovaFocusMotionSpec.CardFocusedScale,
                    haloAlpha = NovaFocusMotionSpec.CardFocusedHaloAlpha,
                    cornerRadius = 20.dp
                )
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            surfaces.tile.copy(alpha = 0.98f * LocalNovaMenuOpacityScale.current),
                            surfaces.tile.copy(alpha = 0.82f * LocalNovaMenuOpacityScale.current),
                            colors.accent.copy(alpha = if (focused) 0.22f else 0.12f)
                        )
                    )
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) surfaces.focusRing else surfaces.tileBorder,
                    shape = RoundedCornerShape(20.dp)
                )
                .onFocusChanged {
                    focused = it.isFocused || it.hasFocus
                }
                .combinedClickable(onClick = onPrimaryAction)
                .focusable()
                .padding(if (compact) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovaLibraryHeroArtwork(
                game = heroGame,
                apiClient = apiClient,
                fallbackTitle = hero.artworkFallbackTitle,
                fallbackSubtitle = hero.artworkFallbackSubtitle,
                compact = compact
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 5.dp)
            ) {
                Text(
                    text = hero.eyebrow.uppercase(),
                    color = colors.accent,
                    fontSize = if (compact) 9.sp else 12.sp,
                    lineHeight = if (compact) 11.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hero.title,
                    color = colors.textPrimary,
                    fontSize = if (compact) 20.sp else 30.sp,
                    lineHeight = if (compact) 22.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (compact && hero.supportingLine.isNotBlank()) {
                    Text(
                        text = hero.supportingLine,
                        color = colors.textSecondary.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!compact) {
                    Text(
                        text = hero.subtitle,
                        color = colors.textSecondary,
                        fontSize = if (compact) 11.sp else 14.sp,
                        lineHeight = if (compact) 13.sp else 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showCaption) {
                        Text(
                            text = hero.caption,
                            color = colors.textSecondary.copy(alpha = 0.86f),
                            fontSize = if (compact) 11.sp else 13.sp,
                            lineHeight = if (compact) 13.sp else 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!compact && hero.badges.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        hero.badges.take(if (compact) 3 else 4).forEach { badge ->
                            NovaMiniBadge(text = badge)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.width(if (compact) 132.dp else 168.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)
            ) {
                NovaActionButton(
                    text = hero.actionLabel,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = if (compact) 28.dp else 48.dp,
                    fontSize = if (compact) 9.sp else 14.sp
                )
                if (hero.secondaryActionLabel != null && onSecondaryAction != null) {
                    NovaActionButton(
                        text = hero.secondaryActionLabel,
                        onClick = onSecondaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                        minHeight = if (compact) 26.dp else 40.dp,
                        fontSize = if (compact) 9.sp else 13.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun NovaLibraryHeroArtwork(
        game: PolarisGame?,
        apiClient: PolarisApiClient,
        fallbackTitle: String,
        fallbackSubtitle: String,
        compact: Boolean
    ) {
        val targetGame = game
        if (targetGame == null) {
            NovaLibraryHeroFallbackArtwork(
                title = fallbackTitle,
                subtitle = fallbackSubtitle,
                compact = compact
            )
            return
        }

        val surfaces = LocalNovaLibrarySurfaces.current
        val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
        Box(
            modifier = Modifier
                .width(if (compact) 58.dp else 108.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(surfaces.mediaPlaceholder)
                .border(1.dp, surfaces.tileBorder.copy(alpha = 0.74f * LocalNovaMenuOpacityScale.current), shape)
        ) {
            key(targetGame.id, targetGame.coverUrl) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(surfaces.mediaPlaceholder.toArgb())
                            contentDescription = context.getString(R.string.nova_a11y_game_cover)
                            apiClient.loadCoverInto(this, targetGame)
                        }
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to surfaces.mediaScrimTop.copy(alpha = 0.18f),
                                0.62f to surfaces.mediaScrimTop.copy(alpha = 0.08f),
                                1.0f to surfaces.mediaScrimBottom.copy(alpha = 0.68f)
                            )
                        )
                    )
            )
        }
    }

    @Composable
    private fun NovaLibraryHeroFallbackArtwork(
        title: String,
        subtitle: String,
        compact: Boolean
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
        Column(
            modifier = Modifier
                .width(if (compact) 58.dp else 108.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            colors.accent.copy(alpha = 0.34f),
                            surfaces.tile.copy(alpha = 0.92f * LocalNovaMenuOpacityScale.current)
                        )
                    )
                )
                .border(1.dp, surfaces.tileBorder.copy(alpha = 0.74f * LocalNovaMenuOpacityScale.current), shape)
                .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 5.dp else 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "NOVA",
                color = colors.textSecondary.copy(alpha = 0.76f),
                fontSize = if (compact) 8.sp else 10.sp,
                lineHeight = if (compact) 9.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = if (compact) 9.sp else 12.sp,
                    lineHeight = if (compact) 10.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compact && subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.textSecondary.copy(alpha = 0.82f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun NovaLibraryLandscapeToolbar(
        serverName: String?,
        serverHost: String,
        model: NovaLibraryUiModel,
        clientSettings: PolarisClientSettings?,
        onOpenOptions: () -> Unit,
        onOpenSystemMenu: () -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val hostLabel = serverName?.takeIf { it.isNotBlank() } ?: serverHost
        val shape = RoundedCornerShape(18.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(surfaces.panel.copy(alpha = 0.72f * LocalNovaMenuOpacityScale.current))
                .border(1.dp, surfaces.tileBorder, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NovaActionButton(
                text = stringResource(R.string.nova_library_options_title),
                onClick = onOpenOptions,
                primary = true,
                minHeight = 34.dp,
                fontSize = 11.sp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nova_library_title),
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "· $hostLabel",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nova_library_results_format, model.resultCount),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = layoutModeLabel(model.optionsState.layoutMode),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (clientSettings != null) {
                        Text(
                            text = stringResource(R.string.nova_system_menu_status_polaris_ready),
                            color = colors.accent,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            NovaActionButton(
                text = stringResource(R.string.nova_system_menu_title),
                onClick = onOpenSystemMenu,
                minHeight = 34.dp,
                fontSize = 10.sp
            )
        }
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

        NovaLibraryPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        NovaLibraryTitle(serverName, serverHost)
                    }
                    NovaActionButton(
                        text = stringResource(R.string.nova_library_options_title),
                        onClick = onOpenOptions,
                        primary = true,
                        minHeight = 36.dp,
                        fontSize = 11.sp
                    )
                    NovaActionButton(
                        text = stringResource(R.string.nova_system_menu_title),
                        onClick = onOpenSystemMenu,
                        minHeight = 36.dp,
                        fontSize = 11.sp
                    )
                }
                NovaLibraryCompactMetaRow(
                    model = model,
                    clientSettings = clientSettings,
                    activeSession = activeSession,
                    searchQuery = searchQuery,
                    hasFilters = hasFilters
                )
            }
        }
    }

    @Composable
    private fun NovaLibraryCompactMetaRow(
        model: NovaLibraryUiModel,
        clientSettings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?,
        searchQuery: String,
        hasFilters: Boolean
    ) {
        val colors = LocalNovaComposeColors.current
        val metaItems = buildList {
            add(stringResource(R.string.nova_library_results_format, model.resultCount))
            add(layoutModeLabel(model.optionsState.layoutMode))
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
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
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
        val mode = settings?.effective?.streamDisplayMode
            ?.ifBlank { settings.desired.streamDisplayMode }
            .orEmpty()
        return when (mode) {
            PolarisClientSettings.MODE_HEADLESS_STREAM, "headless" -> "Headless"
            PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY, "virtual_display" -> "Virtual"
            PolarisClientSettings.MODE_DESKTOP_DISPLAY -> "Desktop"
            PolarisClientSettings.MODE_GPU_NATIVE_TEST -> "GPU Native"
            else -> settings?.effectiveModeLabel
                ?.ifBlank { settings.desiredModeLabel }
                ?.ifBlank { null }
        }
    }

    @Composable
    private fun NovaLibraryActiveSessionCard(
        session: NovaLibraryActiveSessionUiState,
        modifier: Modifier = Modifier,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onEndSession: (NovaLibraryActiveSessionUiState) -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val fallbackName = stringResource(R.string.applist_menu_watch_active_name)
        val gameName = session.gameName.ifBlank { fallbackName }
        val actionLabel = stringResource(
            if (session.watchOnly) R.string.applist_menu_watch else R.string.applist_menu_resume
        )
        val ownerDetail = if (session.ownerDeviceName.isNotBlank()) {
            stringResource(R.string.nova_library_active_session_owner_format, session.ownerDeviceName)
        } else {
            null
        }
        val viewerDetail = when {
            session.viewerCount <= 0 -> null
            session.viewerCount == 1 -> stringResource(
                R.string.nova_library_active_session_viewer_count_one,
                session.viewerCount
            )
            else -> stringResource(
                R.string.nova_library_active_session_viewer_count_many,
                session.viewerCount
            )
        }
        val streamDetail = formatStreamProfile(session)
        val detail = listOfNotNull(ownerDetail, viewerDetail, streamDetail).joinToString(" / ")
        val shape = RoundedCornerShape(14.dp)

        Column(
            modifier = modifier
                .clip(shape)
                .background(surfaces.selectedControl)
                .border(1.dp, colors.accent.copy(alpha = 0.52f), shape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = stringResource(R.string.nova_library_active_session_title),
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = gameName,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NovaActionButton(
                    text = actionLabel,
                    onClick = { onResumeSession(session) },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    minHeight = 34.dp,
                    fontSize = 11.sp
                )
                if (!session.watchOnly) {
                    NovaActionButton(
                        text = stringResource(R.string.applist_menu_quit),
                        onClick = { onEndSession(session) },
                        modifier = Modifier.weight(1f),
                        primary = false,
                        minHeight = 34.dp,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    private fun formatStreamProfile(session: NovaLibraryActiveSessionUiState): String? {
        if (session.streamWidth <= 0 || session.streamHeight <= 0 || session.streamFps <= 0f) {
            return null
        }
        return "${session.streamWidth}x${session.streamHeight} @ ${session.streamFps.toInt()} FPS"
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
                .clip(RoundedCornerShape(999.dp))
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(999.dp))
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
                .clip(RoundedCornerShape(14.dp))
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(14.dp))
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
        val fill = if (enabled) surfaces.selectedControl else surfaces.control
        val stroke = if (enabled) colors.accent.copy(alpha = 0.68f) else surfaces.tileBorder
        Text(
            text = text,
            color = if (enabled) colors.accent else colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(fill)
                .border(1.dp, stroke, RoundedCornerShape(999.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun NovaSearchField(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        heightDp: Int = 44
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val searchFocusRequester = remember { FocusRequester() }
        var focused by remember { mutableStateOf(false) }
        var searchEditing by remember { mutableStateOf(false) }

        fun beginSearchEditing() {
            searchEditing = true
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }

        fun leaveSearchEditing(direction: FocusDirection? = null): Boolean {
            searchEditing = false
            keyboardController?.hide()
            direction?.let { focusManager.moveFocus(it) }
            return true
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !searchEditing,
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    searchEditing = false
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                }
            ),
            modifier = modifier
                .focusRequester(searchFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (!searchEditing) {
                                beginSearchEditing()
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionDown -> leaveSearchEditing(FocusDirection.Down)
                        Key.DirectionUp -> leaveSearchEditing(FocusDirection.Up)
                        Key.DirectionLeft -> leaveSearchEditing(FocusDirection.Left)
                        Key.DirectionRight -> leaveSearchEditing(FocusDirection.Right)
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { beginSearchEditing() })
                }
                .height(heightDp.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(surfaces.control)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) surfaces.focusRing else surfaces.tileBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused && searchEditing) {
                        searchEditing = false
                        keyboardController?.hide()
                    }
                }
                .semantics {
                    contentDescription = getString(R.string.nova_library_search_hint)
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = stringResource(R.string.nova_library_search_hint),
                            color = colors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
    }

    @Composable
    private fun NovaFilterChip(
        filter: NovaLibraryPrimaryFilter,
        selected: Boolean,
        count: Int,
        filterState: NovaLibraryFilterState,
        sourceLabel: (String?) -> String,
        modifier: Modifier = Modifier,
        restoreFocus: Boolean = false,
        onFocused: () -> Unit = {},
        onClick: () -> Unit
    ) {
        val label = filterLabel(filter, filterState, sourceLabel)
        NovaSelectableChip(
            label = label,
            detail = count.toString(),
            selected = selected,
            modifier = modifier,
            restoreFocus = restoreFocus,
            onFocused = onFocused,
            onClick = onClick
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NovaLibraryContent(
        modifier: Modifier,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        columns: Int,
        isLandscape: Boolean,
        isInitialLoading: Boolean,
        isRefreshing: Boolean,
        loadErrorMessage: String?,
        launchErrorMessage: String?,
        apiClient: PolarisApiClient,
        restoreFocusGameId: String?,
        onRefresh: () -> Unit,
        onManageServer: () -> Unit,
        onClearFilters: () -> Unit,
        onGameFocused: (PolarisGame) -> Unit,
        onOpenDetail: (PolarisGame) -> Unit
    ) {
        val layoutMode = model.optionsState.layoutMode
        val compactCards = layoutMode == NovaLibraryLayoutMode.COMPACT_GRID
        val listCards = layoutMode == NovaLibraryLayoutMode.LIST
        val gridColumns = columns
        val onRecoveryAction: (NovaLibraryRecoveryAction) -> Unit = { action ->
            when (action) {
                NovaLibraryRecoveryAction.RETRY -> onRefresh()
                NovaLibraryRecoveryAction.MANAGE_LIBRARY -> onManageServer()
                NovaLibraryRecoveryAction.CLEAR_FILTERS -> onClearFilters()
            }
        }
        NovaLibraryPanel(modifier = modifier, subtle = true) {
            if (loadErrorMessage != null && model.allGames.isEmpty()) {
                val recoveryState = NovaLibraryUiStateMapper.loadFailureRecoveryState(loadErrorMessage)
                NovaLibraryRecoveryState(
                    recoveryState = recoveryState,
                    onAction = onRecoveryAction
                )
            } else if (isInitialLoading && model.allGames.isEmpty()) {
                NovaLibraryLoadingGrid(columns = columns, isLandscape = isLandscape)
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val launchRecoveryState = launchErrorMessage
                        ?.let(NovaLibraryUiStateMapper::launchFailureRecoveryState)
                    if (launchRecoveryState != null) {
                        NovaLibraryRecoveryState(
                            recoveryState = launchRecoveryState,
                            onAction = onRecoveryAction
                        )
                    } else if (model.filteredGames.isEmpty()) {
                        val emptyRecoveryState = NovaLibraryUiStateMapper.emptyRecoveryState(
                            emptyState = model.emptyState,
                            totalCount = model.summary.totalCount,
                            sourceName = filterState.source
                        )
                        NovaLibraryRecoveryState(
                            recoveryState = emptyRecoveryState,
                            onAction = onRecoveryAction
                        )
                    } else if (layoutMode == NovaLibraryLayoutMode.SPOTLIGHT) {
                        NovaLibrarySpotlightRow(
                            games = model.filteredGames,
                            apiClient = apiClient,
                            isLandscape = isLandscape,
                            restoreFocusGameId = restoreFocusGameId,
                            showPosterTitles = model.optionsState.showPosterTitles,
                            onGameFocused = onGameFocused,
                            onOpenDetail = onOpenDetail
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = NovaLibraryUiStateMapper.gridContentPaddingDp().dp,
                                top = NovaLibraryUiStateMapper.gridContentPaddingDp().dp,
                                end = NovaLibraryUiStateMapper.gridContentPaddingDp().dp,
                                bottom = NovaLibraryUiStateMapper.gridBottomContentPaddingDp(isLandscape).dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                model.filteredGames,
                                key = { it.id },
                                contentType = { "library-game" }
                            ) { game ->
                                NovaLibraryGameCard(
                                    game = game,
                                    apiClient = apiClient,
                                    compact = compactCards,
                                    listStyle = listCards,
                                    showPosterTitle = model.optionsState.showPosterTitles,
                                    isLandscape = isLandscape,
                                    restoreFocus = game.id == restoreFocusGameId,
                                    onFocused = { onGameFocused(game) },
                                    onOpenDetail = { onOpenDetail(game) }
                                )
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
                            NovaLibraryGameCard(
                                game = game,
                                apiClient = apiClient,
                                compact = true,
                                showPosterTitle = showPosterTitles,
                                isLandscape = false,
                                modifier = Modifier.width(cardWidth),
                                restoreFocus = game.id == restoreFocusGameId,
                                onFocused = { onGameFocused(game) },
                                onOpenDetail = { onOpenDetail(game) }
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun NovaLibraryGameCard(
        game: PolarisGame,
        apiClient: PolarisApiClient,
        compact: Boolean,
        listStyle: Boolean = false,
        showPosterTitle: Boolean = true,
        isLandscape: Boolean,
        modifier: Modifier = Modifier,
        restoreFocus: Boolean = false,
        onFocused: () -> Unit = {},
        onOpenDetail: () -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        var focused by remember { mutableStateOf(false) }
        var restoreAttempted by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        val layoutMode = when {
            listStyle -> NovaLibraryLayoutMode.LIST
            compact -> NovaLibraryLayoutMode.COMPACT_GRID
            else -> NovaLibraryLayoutMode.GRID
        }
        val cardHeight = NovaLibraryUiStateMapper.gameCardHeightDp(layoutMode = layoutMode, isLandscape = isLandscape).dp
        val title = game.name.ifBlank { "Unknown game" }
        val meta = listOfNotNull(
            sourceLabelFor(game.source),
            game.category.takeIf { it.isNotBlank() }?.let { categoryLabelFor(it) }
        ).joinToString(" / ")
        LaunchedEffect(restoreFocus) {
            if (restoreFocus && !restoreAttempted) {
                restoreAttempted = true
                focusRequester.requestFocus()
            }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(cardHeight)
                .novaFocusMotion(
                    focused = focused,
                    focusedScale = NovaFocusMotionSpec.CardFocusedScale,
                    haloAlpha = NovaFocusMotionSpec.CardFocusedHaloAlpha,
                    cornerRadius = 14.dp
                )
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) surfaces.tile.copy(alpha = LocalNovaMenuOpacityScale.current) else surfaces.tile)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) surfaces.focusRing else surfaces.tileBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused || it.hasFocus
                    if (focused) {
                        onFocused()
                    }
                }
                .combinedClickable(
                    onClick = onOpenDetail,
                    onLongClick = onOpenDetail
                )
                .focusable()
                .semantics {
                    contentDescription = title
                }
        ) {
            if (listStyle) {
                if (focused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(surfaces.focusHalo.copy(alpha = 0.18f))
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (isLandscape) 126.dp else 104.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(surfaces.mediaPlaceholder)
                    ) {
                        key(game.id, game.coverUrl) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    ImageView(context).apply {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        setBackgroundColor(surfaces.mediaPlaceholder.toArgb())
                                        contentDescription = context.getString(R.string.nova_a11y_game_cover)
                                        apiClient.loadCoverInto(this, game)
                                    }
                                }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            surfaces.mediaScrimTop,
                                            surfaces.mediaScrimBottom.copy(alpha = 0.62f)
                                        )
                                    )
                                )
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        NovaLibraryCardBadgeRow(
                            game = game,
                            compact = compact
                        )
                    }
                    if (focused) {
                        NovaMiniBadge(text = stringResource(R.string.nova_library_card_action_details))
                    }
                }
            } else {
                key(game.id, game.coverUrl) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(surfaces.mediaPlaceholder.toArgb())
                                contentDescription = context.getString(R.string.nova_a11y_game_cover)
                                apiClient.loadCoverInto(this, game)
                            }
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to surfaces.mediaScrimTop,
                                    0.50f to surfaces.mediaScrimTop,
                                    1.0f to surfaces.mediaScrimBottom
                                )
                            )
                        )
                )
                if (focused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(surfaces.focusHalo.copy(alpha = 0.28f))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(4.dp, surfaces.focusRing, RoundedCornerShape(14.dp))
                            .padding(4.dp)
                            .border(2.dp, colors.onAccent.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
                    )
                    NovaMiniBadge(
                        text = stringResource(R.string.nova_library_card_action_details),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
                NovaLibraryCardBadgeRow(
                    game = game,
                    compact = compact,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                )
                if (showPosterTitle) {
                    NovaLibraryCardTitleScrim(
                        compact = compact,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(surfaces.mediaScrimBottom.copy(alpha = 0.34f))
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = title,
                            color = surfaces.onMedia,
                            fontSize = if (compact) 13.sp else 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                color = surfaces.onMediaSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NovaLibraryCardTitleScrim(compact: Boolean, modifier: Modifier = Modifier) {
        val surfaces = LocalNovaLibrarySurfaces.current
        Box(
            modifier = modifier
                .height(if (compact) 64.dp else 88.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to surfaces.mediaScrimBottom.copy(alpha = 0f),
                            0.36f to surfaces.mediaScrimBottom.copy(alpha = 0.64f),
                            1.0f to surfaces.mediaScrimBottom.copy(alpha = 0.96f)
                        )
                    )
                )
        )
    }

    @Composable
    private fun NovaLibraryCardBadgeRow(
        game: PolarisGame,
        compact: Boolean,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier.widthIn(max = if (compact) 92.dp else 128.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (game.hdrSupported) {
                NovaMiniBadge(text = stringResource(R.string.badge_hdr))
            }
            if (game.lastLaunched > 0) {
                NovaMiniBadge(text = stringResource(R.string.nova_library_filter_recent))
            }
        }
    }

    @Composable
    private fun NovaMiniBadge(text: String, modifier: Modifier = Modifier) {
        val surfaces = LocalNovaLibrarySurfaces.current
        Text(
            text = text,
            color = surfaces.onMedia,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 9.sp,
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(surfaces.mediaScrimBottom.copy(alpha = 0.60f))
                .border(1.dp, surfaces.onMedia.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }

    @Composable
    private fun NovaLibraryLoadingGrid(columns: Int, isLandscape: Boolean) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(12, contentType = { "loading-card" }) {
                NovaLoadingCard(isLandscape = isLandscape)
            }
        }
    }

    @Composable
    private fun NovaLoadingCard(isLandscape: Boolean) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
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
                colors.accent.copy(alpha = 0.18f),
                surfaces.mediaPlaceholder.copy(alpha = 0.42f)
            ),
            start = Offset(x = shimmerOffset * 620f, y = 0f),
            end = Offset(x = (shimmerOffset + 0.32f) * 620f, y = 260f)
        )
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NovaLibraryUiStateMapper.gameCardHeightDp(compact = false, isLandscape = isLandscape).dp)
                .clip(shape)
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, shape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(shimmerBrush)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .width(54.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.accent.copy(alpha = 0.16f))
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.74f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.divider.copy(alpha = 0.58f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.46f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.divider.copy(alpha = 0.36f))
                )
            }
        }
    }

    @Composable
    private fun NovaLibraryRecoveryState(
        recoveryState: NovaLibraryRecoveryUiState,
        onAction: (NovaLibraryRecoveryAction) -> Unit
    ) {
        NovaLibraryRecoveryState(
            eyebrow = recoveryState.eyebrow,
            title = recoveryState.title,
            message = recoveryState.message,
            primaryActionLabel = recoveryState.primaryActionLabel,
            onPrimaryAction = { onAction(recoveryState.primaryAction) },
            detail = recoveryState.detail,
            secondaryActionLabel = recoveryState.secondaryActionLabel,
            onSecondaryAction = recoveryState.secondaryAction?.let { action ->
                { onAction(action) }
            }
        )
    }

    @Composable
    private fun NovaLibraryRecoveryState(
        eyebrow: String,
        title: String,
        message: String,
        primaryActionLabel: String,
        onPrimaryAction: () -> Unit,
        detail: String? = null,
        secondaryActionLabel: String? = null,
        onSecondaryAction: (() -> Unit)? = null
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(surfaces.panel)
                    .border(1.dp, surfaces.tileBorder, RoundedCornerShape(22.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = eyebrow.uppercase(Locale.getDefault()),
                    color = colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                NovaActionButton(
                    text = primaryActionLabel,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    minHeight = 42.dp,
                    fontSize = 13.sp
                )
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    NovaActionButton(
                        text = secondaryActionLabel,
                        onClick = onSecondaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                        minHeight = 40.dp,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NovaSystemMenuSheet(
        serverName: String?,
        serverHost: String,
        clientSettings: PolarisClientSettings?,
        loadErrorMessage: String?,
        onDismiss: () -> Unit,
        onOpenOptions: () -> Unit,
        onSwitchHost: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenPolarisSync: () -> Unit,
        onManageServer: () -> Unit,
        onOpenHelpDiagnostics: () -> Unit,
        onOpenAbout: () -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val drawerShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
        val serverDisplayName = serverName?.takeIf { it.isNotBlank() && it != serverHost }
        val hostLabel = if (serverDisplayName == null) {
            stringResource(R.string.nova_system_menu_host_format, serverHost)
        } else {
            stringResource(R.string.nova_system_menu_host_named_format, serverHost, serverDisplayName)
        }
        val statusText = when {
            clientSettings != null -> stringResource(R.string.nova_system_menu_status_polaris_ready)
            !loadErrorMessage.isNullOrBlank() -> stringResource(R.string.nova_system_menu_status_offline)
            else -> stringResource(R.string.nova_system_menu_status_checking)
        }
        val modeText = compactStatusModeLabel(clientSettings)
        val drawerFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(75)
            drawerFocusRequester.requestFocus()
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            false
                        } else when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_BUTTON_L1,
                            KeyEvent.KEYCODE_BUTTON_X,
                            KeyEvent.KEYCODE_PAGE_UP -> {
                                onOpenOptions()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_B,
                            KeyEvent.KEYCODE_BACK -> {
                                onDismiss()
                                true
                            }
                            else -> if (event.key == Key.DirectionLeft) {
                                onOpenOptions()
                                true
                            } else {
                                false
                            }
                        }
                    }
                    .focusRequester(drawerFocusRequester)
                    .focusable()
            ) {
                val drawerWidthFraction = if (maxWidth < 520.dp) 0.94f else 0.42f
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            surfaces.backgroundScrim.copy(
                                alpha = NovaMenuPreferences.readabilityScrimAlpha(
                                    0.58f,
                                    LocalNovaMenuOpacityScale.current
                                )
                            )
                        )
                        .pointerInput(onDismiss) {
                            detectTapGestures { onDismiss() }
                        }
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(drawerWidthFraction)
                        .widthIn(max = 420.dp)
                        .clip(drawerShape)
                        .background(surfaces.panel)
                        .border(1.dp, surfaces.tileBorder, drawerShape)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .focusGroup()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_BUTTON_L1,
                                KeyEvent.KEYCODE_PAGE_UP -> {
                                    onOpenOptions()
                                    true
                                }
                                KeyEvent.KEYCODE_BUTTON_B,
                                KeyEvent.KEYCODE_BACK -> {
                                    onDismiss()
                                    true
                                }
                                else -> if (event.key == Key.DirectionLeft) {
                                    onOpenOptions()
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.nova_system_menu_title),
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = hostLabel,
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NovaStatusPill(text = statusText, enabled = clientSettings != null)
                        if (!modeText.isNullOrBlank()) {
                            NovaStatusPill(text = modeText, enabled = true)
                        }
                    }
                    NovaSystemMenuRow(
                        label = stringResource(R.string.nova_system_menu_switch_host),
                        detail = stringResource(R.string.nova_system_menu_switch_host_hint),
                        onClick = {
                            onDismiss()
                            onSwitchHost()
                        }
                    )
                    NovaSystemMenuRow(
                        label = stringResource(R.string.nova_system_menu_settings),
                        detail = stringResource(R.string.nova_system_menu_settings_hint),
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        }
                    )
                    NovaSystemMenuRow(
                        label = stringResource(R.string.nova_system_menu_polaris_sync),
                        detail = stringResource(R.string.nova_system_menu_polaris_sync_hint),
                        onClick = {
                            onDismiss()
                            onOpenPolarisSync()
                        }
                    )
                    NovaSystemMenuRow(
                        label = stringResource(R.string.nova_system_menu_manage_server),
                        detail = stringResource(R.string.nova_system_menu_manage_server_hint),
                        onClick = {
                            onDismiss()
                            onManageServer()
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NovaActionButton(
                            text = stringResource(R.string.nova_system_menu_help_diagnostics),
                            onClick = {
                                onDismiss()
                                onOpenHelpDiagnostics()
                            },
                            modifier = Modifier.weight(1f),
                            minHeight = 32.dp,
                            fontSize = 10.sp,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                        )
                        NovaActionButton(
                            text = stringResource(R.string.nova_system_menu_about),
                            onClick = {
                                onDismiss()
                                onOpenAbout()
                            },
                            modifier = Modifier.weight(1f),
                            minHeight = 32.dp,
                            fontSize = 10.sp,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun NovaSystemMenuRow(
        label: String,
        detail: String,
        onClick: () -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        var focused by remember { mutableStateOf(false) }
        val stroke = if (focused) surfaces.focusRing else surfaces.tileBorder

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .novaFocusMotion(
                    focused = focused,
                    focusedScale = NovaFocusMotionSpec.ButtonFocusedScale,
                    haloAlpha = NovaFocusMotionSpec.ButtonFocusedHaloAlpha,
                    cornerRadius = 16.dp
                )
                .clip(RoundedCornerShape(16.dp))
                .background(if (focused) surfaces.selectedControl else surfaces.control)
                .border(if (focused) 3.dp else 1.dp, stroke, RoundedCornerShape(16.dp))
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label. $detail"
                }
                .focusable()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    color = colors.textSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "›",
                color = if (focused) colors.accent else colors.textSecondary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun NovaLibraryOptionsSheet(
        optionsState: NovaLibraryOptionsState,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        searchQuery: String,
        restoreFocusPrimaryFilter: NovaLibraryPrimaryFilter,
        onSearchChange: (String) -> Unit,
        onPrimaryFilter: (NovaLibraryPrimaryFilter) -> Unit,
        onPrimaryFilterFocused: (NovaLibraryPrimaryFilter) -> Unit,
        onClearFilters: () -> Unit,
        sourceLabel: (String?) -> String,
        onDismiss: () -> Unit,
        onOpenSystemMenu: () -> Unit,
        onRefresh: () -> Unit,
        onSortMode: (NovaLibrarySortMode) -> Unit,
        onLayoutMode: (NovaLibraryLayoutMode) -> Unit,
        onPosterTitlesVisible: (Boolean) -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
        val drawerFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(75)
            drawerFocusRequester.requestFocus()
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val drawerWidthFraction = if (maxWidth < 520.dp) 0.94f else 0.50f
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            surfaces.backgroundScrim.copy(
                                alpha = NovaMenuPreferences.readabilityScrimAlpha(
                                    0.58f,
                                    LocalNovaMenuOpacityScale.current
                                )
                            )
                        )
                        .pointerInput(onDismiss) {
                            detectTapGestures { onDismiss() }
                        }
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(drawerWidthFraction)
                        .widthIn(max = 420.dp)
                        .clip(drawerShape)
                        .background(surfaces.panel)
                        .border(1.dp, surfaces.tileBorder, drawerShape)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .focusGroup()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_BUTTON_R1,
                                KeyEvent.KEYCODE_MENU,
                                KeyEvent.KEYCODE_BUTTON_START,
                                KeyEvent.KEYCODE_PAGE_DOWN -> {
                                    onOpenSystemMenu()
                                    true
                                }
                                KeyEvent.KEYCODE_BUTTON_B,
                                KeyEvent.KEYCODE_BACK -> {
                                    onDismiss()
                                    true
                                }
                                else -> if (event.key == Key.DirectionRight) {
                                    onOpenSystemMenu()
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        .focusRequester(drawerFocusRequester)
                        .focusable()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.nova_library_options_title),
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.nova_library_results_format, model.resultCount),
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    NovaSearchField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        modifier = Modifier.fillMaxWidth(),
                        heightDp = 40
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.nova_controller_hint_filters),
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp,
                            modifier = Modifier.weight(1f)
                        )
                        NovaActionButton(
                            text = stringResource(R.string.nova_refresh),
                            onClick = {
                                onDismiss()
                                onRefresh()
                            },
                            modifier = Modifier.widthIn(min = 104.dp),
                            minHeight = 32.dp,
                            fontSize = 10.sp,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NovaLibraryPrimaryFilter.entries.forEach { filter ->
                            NovaFilterChip(
                                filter = filter,
                                selected = filterState.primary == filter,
                                count = filterCount(filter, model),
                                filterState = filterState,
                                sourceLabel = sourceLabel,
                                modifier = Modifier.width(NovaLibraryUiStateMapper.filterChipWidthDp(filter).dp),
                                restoreFocus = restoreFocusPrimaryFilter == filter,
                                onFocused = { onPrimaryFilterFocused(filter) },
                                onClick = { onPrimaryFilter(filter) }
                            )
                        }
                        if (hasClearableFilters(searchQuery, filterState)) {
                            NovaActionButton(
                                text = stringResource(R.string.nova_library_filter_clear_all),
                                onClick = onClearFilters,
                                minHeight = 38.dp,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.nova_library_options_sort_title),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp
                    )
                    NovaLibrarySortMode.entries.forEach { sortMode ->
                        NovaSelectableChip(
                            label = sortModeLabel(sortMode),
                            detail = sortModeDetail(sortMode),
                            selected = optionsState.sortMode == sortMode,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSortMode(sortMode) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.nova_library_options_layout_title),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    NovaLibraryLayoutMode.entries.forEach { layoutMode ->
                        NovaSelectableChip(
                            label = layoutModeLabel(layoutMode),
                            detail = layoutModeDetail(layoutMode),
                            selected = optionsState.layoutMode == layoutMode,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onLayoutMode(layoutMode) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.nova_library_options_poster_titles_title),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    NovaSelectableChip(
                        label = stringResource(R.string.nova_library_options_poster_titles_show),
                        detail = stringResource(R.string.nova_library_options_poster_titles_show_hint),
                        selected = optionsState.showPosterTitles,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPosterTitlesVisible(true) }
                    )
                    NovaSelectableChip(
                        label = stringResource(R.string.nova_library_options_poster_titles_hide),
                        detail = stringResource(R.string.nova_library_options_poster_titles_hide_hint),
                        selected = !optionsState.showPosterTitles,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPosterTitlesVisible(false) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NovaLibraryFilterSheet(
        sheet: LibraryFilterSheet,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        onDismiss: () -> Unit,
        onSourceFilter: (String?) -> Unit,
        onCategoryFilter: (String) -> Unit,
        onGenreFilter: (String) -> Unit,
        onClearFilters: () -> Unit,
        sourceLabel: (String?) -> String,
        categoryLabel: (String) -> String
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = RoundedCornerShape(
                topStart = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp,
                topEnd = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp
            ),
            containerColor = surfaces.panel,
            contentColor = colors.textPrimary,
            scrimColor = surfaces.backgroundScrim.copy(
                alpha = NovaMenuPreferences.readabilityScrimAlpha(
                    NovaSheetChrome.SCRIM_ALPHA,
                    LocalNovaMenuOpacityScale.current
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (sheet == LibraryFilterSheet.SOURCES) {
                        stringResource(R.string.nova_library_filter_sheet_sources)
                    } else {
                        stringResource(R.string.nova_library_filter_sheet_more)
                    },
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (sheet == LibraryFilterSheet.SOURCES) {
                        stringResource(R.string.nova_library_filter_sheet_sources_hint)
                    } else {
                        stringResource(R.string.nova_library_filter_sheet_more_hint)
                    },
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
                if (sheet == LibraryFilterSheet.SOURCES) {
                    NovaSelectableChip(
                        label = stringResource(R.string.nova_library_filter_all_sources),
                        detail = model.summary.totalCount.toString(),
                        selected = filterState.primary == NovaLibraryPrimaryFilter.ALL,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSourceFilter(null) }
                    )
                    NovaLibraryUiStateMapper.sourceFilters(model.allGames).forEach { source ->
                        val sourceCount = model.allGames.count { it.source.equals(source, ignoreCase = true) }
                        NovaSelectableChip(
                            label = sourceLabel(source),
                            detail = sourceCount.toString(),
                            selected = filterState.source == source,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSourceFilter(source) }
                        )
                    }
                } else {
                    NovaSelectableChip(
                        label = stringResource(R.string.nova_library_filter_clear_more),
                        detail = model.summary.totalCount.toString(),
                        selected = filterState.primary == NovaLibraryPrimaryFilter.ALL && searchQuery.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onClearFilters,
                    )
                    NovaLibraryUiStateMapper.categoryFilters(model.allGames).forEach { category ->
                        val categoryCount = model.allGames.count { it.category.equals(category, ignoreCase = true) }
                        NovaSelectableChip(
                            label = categoryLabel(category),
                            detail = categoryCount.toString(),
                            selected = filterState.category == category,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onCategoryFilter(category) }
                        )
                    }
                    NovaLibraryUiStateMapper.genreFilters(model.allGames).forEach { genre ->
                        val genreCount = model.allGames.count { game ->
                            game.genres.any { it.equals(genre, ignoreCase = true) }
                        }
                        NovaSelectableChip(
                            label = genre.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                            },
                            detail = genreCount.toString(),
                            selected = filterState.genre == genre,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onGenreFilter(genre) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun NovaSelectableChip(
        label: String,
        detail: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        restoreFocus: Boolean = false,
        onFocused: () -> Unit = {},
        onClick: () -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        val chipDescription = "$label. $detail"
        var focused by remember { mutableStateOf(false) }
        var restoreAttempted by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        val stroke = when {
            focused -> surfaces.focusRing
            selected -> colors.accent.copy(alpha = 0.72f)
            else -> surfaces.tileBorder
        }
        LaunchedEffect(restoreFocus) {
            if (restoreFocus && !restoreAttempted) {
                restoreAttempted = true
                focusRequester.requestFocus()
            }
        }
        Row(
            modifier = modifier
                .height(NovaLibraryUiStateMapper.filterChipHeightDp().dp)
                .novaFocusMotion(
                    focused = focused,
                    focusedScale = NovaFocusMotionSpec.CardFocusedScale,
                    haloAlpha = NovaFocusMotionSpec.ButtonFocusedHaloAlpha,
                    cornerRadius = 14.dp
                )
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        focused -> surfaces.selectedControl
                        selected -> surfaces.selectedControl
                        else -> surfaces.control
                    }
                )
                .border(if (focused) 3.dp else 1.dp, stroke, RoundedCornerShape(14.dp))
                .semantics(mergeDescendants = true) {
                    contentDescription = chipDescription
                    role = Role.Button
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused || it.hasFocus
                    if (focused) {
                        onFocused()
                    }
                }
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick
                )
                .focusable()
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                color = if (selected) colors.accent else colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun NovaLibraryPanel(
        modifier: Modifier = Modifier,
        subtle: Boolean = false,
        content: @Composable () -> Unit
    ) {
        val surfaces = LocalNovaLibrarySurfaces.current
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            color = if (subtle) surfaces.panel.copy(alpha = 0.34f * LocalNovaMenuOpacityScale.current) else surfaces.panel,
            border = BorderStroke(
                1.dp,
                if (subtle) surfaces.panelBorder.copy(alpha = 0.30f * LocalNovaMenuOpacityScale.current) else surfaces.panelBorder
            ),
            content = content
        )
    }

    @Composable
    private fun sortModeLabel(sortMode: NovaLibrarySortMode): String = when (sortMode) {
        NovaLibrarySortMode.LIBRARY_ORDER -> stringResource(R.string.nova_library_options_sort_library_order)
        NovaLibrarySortMode.RECENT -> stringResource(R.string.nova_library_options_sort_recent)
        NovaLibrarySortMode.NAME_ASC -> stringResource(R.string.nova_library_options_sort_name_asc)
        NovaLibrarySortMode.NAME_DESC -> stringResource(R.string.nova_library_options_sort_name_desc)
        NovaLibrarySortMode.SOURCE -> stringResource(R.string.nova_library_options_sort_source)
        NovaLibrarySortMode.HDR_FIRST -> stringResource(R.string.nova_library_options_sort_hdr_first)
    }

    @Composable
    private fun sortModeDetail(sortMode: NovaLibrarySortMode): String = when (sortMode) {
        NovaLibrarySortMode.LIBRARY_ORDER -> stringResource(R.string.nova_library_options_sort_library_order_hint)
        NovaLibrarySortMode.RECENT -> stringResource(R.string.nova_library_options_sort_recent_hint)
        NovaLibrarySortMode.NAME_ASC -> stringResource(R.string.nova_library_options_sort_name_asc_hint)
        NovaLibrarySortMode.NAME_DESC -> stringResource(R.string.nova_library_options_sort_name_desc_hint)
        NovaLibrarySortMode.SOURCE -> stringResource(R.string.nova_library_options_sort_source_hint)
        NovaLibrarySortMode.HDR_FIRST -> stringResource(R.string.nova_library_options_sort_hdr_first_hint)
    }

    @Composable
    private fun layoutModeLabel(layoutMode: NovaLibraryLayoutMode): String =
        stringResource(layoutModeLabelRes(layoutMode))

    private fun layoutModeLabelRes(layoutMode: NovaLibraryLayoutMode): Int = when (layoutMode) {
        NovaLibraryLayoutMode.GRID -> R.string.nova_library_options_layout_grid
        NovaLibraryLayoutMode.COMPACT_GRID -> R.string.nova_library_options_layout_compact_grid
        NovaLibraryLayoutMode.LIST -> R.string.nova_library_options_layout_list
        NovaLibraryLayoutMode.SPOTLIGHT -> R.string.nova_library_options_layout_spotlight
    }

    @Composable
    private fun layoutModeDetail(layoutMode: NovaLibraryLayoutMode): String = when (layoutMode) {
        NovaLibraryLayoutMode.GRID -> stringResource(R.string.nova_library_options_layout_grid_hint)
        NovaLibraryLayoutMode.COMPACT_GRID -> stringResource(R.string.nova_library_options_layout_compact_grid_hint)
        NovaLibraryLayoutMode.LIST -> stringResource(R.string.nova_library_options_layout_list_hint)
        NovaLibraryLayoutMode.SPOTLIGHT -> stringResource(R.string.nova_library_options_layout_spotlight_hint)
    }

    private fun filterCount(filter: NovaLibraryPrimaryFilter, model: NovaLibraryUiModel): Int {
        return when (filter) {
            NovaLibraryPrimaryFilter.ALL -> model.summary.totalCount
            NovaLibraryPrimaryFilter.RECENT -> model.summary.recentCount
            NovaLibraryPrimaryFilter.SOURCES -> model.allGames.count { !it.source.isNullOrBlank() }
            NovaLibraryPrimaryFilter.HDR -> model.summary.hdrCount
            NovaLibraryPrimaryFilter.MORE -> model.allGames.count { it.category.isNotBlank() || it.genres.isNotEmpty() }
        }
    }

    private fun filterLabel(
        filter: NovaLibraryPrimaryFilter,
        filterState: NovaLibraryFilterState,
        sourceLabel: (String?) -> String
    ): String {
        return when (filter) {
            NovaLibraryPrimaryFilter.ALL -> getString(R.string.nova_library_filter_all)
            NovaLibraryPrimaryFilter.RECENT -> getString(R.string.nova_library_filter_recent)
            NovaLibraryPrimaryFilter.SOURCES ->
                filterState.source.takeIf { it.isNotBlank() }?.let(sourceLabel)
                    ?: getString(R.string.nova_library_filter_sources)
            NovaLibraryPrimaryFilter.HDR -> getString(R.string.nova_library_filter_hdr)
            NovaLibraryPrimaryFilter.MORE -> when {
                filterState.category.isNotBlank() -> categoryLabelFor(filterState.category)
                filterState.genre.isNotBlank() -> filterState.genre.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                }
                else -> getString(R.string.nova_library_filter_more)
            }
        }
    }

    private enum class LibraryFilterSheet {
        SOURCES,
        MORE
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_HTTPS_PORT = "https_port"
        const val EXTRA_HTTP_PORT = "http_port"
        const val EXTRA_UNIQUE_ID = "unique_id"
        const val EXTRA_PC_UUID = "pc_uuid"
        const val EXTRA_SERVER_COMMANDS = "server_commands"
        const val EXTRA_SERVER_CERT = "server_cert"
        private const val CONTROLLER_HINT_IDLE_REVEAL_MS = 4_000L
        private const val CONTROLLER_HINT_ANIMATION_MS = 180
        private const val CONTROLLER_AXIS_INTENT_THRESHOLD = 0.35f
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
