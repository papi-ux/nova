package com.papi.nova.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisGame
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.ServerHelper
import com.papi.nova.ui.SpaceParticleView
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class LibraryLoadResult(
    val games: List<PolarisGame>,
    val settings: PolarisClientSettings?,
    val activeSession: NovaLibraryActiveSessionUiState?
)

class NovaLibraryActivity : AppCompatActivity() {

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
    private var clientSettings by mutableStateOf<PolarisClientSettings?>(null)
    private var activeSession by mutableStateOf<NovaLibraryActiveSessionUiState?>(null)
    private var activeFilterSheet by mutableStateOf<LibraryFilterSheet?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
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

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWithTransition()
                }
            }
        )

        val content = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NovaComposeTheme {
                    val model = NovaLibraryUiStateMapper.build(allGames, searchQuery, filterState)
                    NovaLibraryScreen(
                        serverName = streamPcName,
                        serverHost = streamHost,
                        model = model,
                        filterState = filterState,
                        searchQuery = searchQuery,
                        isInitialLoading = isInitialLoading,
                        isRefreshing = isRefreshing,
                        clientSettings = clientSettings,
                        activeSession = activeSession,
                        apiClient = apiClient,
                        activeFilterSheet = activeFilterSheet,
                        onBack = ::finishWithTransition,
                        onSearchChange = { searchQuery = it },
                        onRefresh = { loadGames(forceRefresh = true) },
                        onResumeSession = ::resumeActiveSession,
                        onManageServer = ::openServerManagement,
                        onOpenDetail = ::showGameDetail,
                        onPrimaryFilter = ::handlePrimaryFilter,
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

    override fun onResume() {
        super.onResume()
        if (::apiClient.isInitialized && !isInitialLoading) {
            refreshActiveSession()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> {
                movePrimaryFilter(-1)
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> {
                movePrimaryFilter(1)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
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
                activeSession = result.activeSession
                LimeLog.info("Nova: Loaded ${allGames.size} games")
            } catch (e: Exception) {
                LimeLog.severe("Nova: Failed to load games: ${e.message}")
                Toast.makeText(
                    this@NovaLibraryActivity,
                    e.localizedMessage ?: e.javaClass.simpleName,
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isInitialLoading = false
                isRefreshing = false
            }
        }
    }

    private fun refreshActiveSession() {
        lifecycleScope.launch {
            try {
                activeSession = withContext(Dispatchers.IO) {
                    queryActiveSession()
                }
            } catch (e: Exception) {
                LimeLog.warning("Nova: Failed to refresh active session: ${e.message}")
            }
        }
    }

    private fun queryActiveSession(): NovaLibraryActiveSessionUiState? {
        return NovaLibraryActiveSessionUiState.from(apiClient.getSessionStatus())
    }

    private fun handlePrimaryFilter(filter: NovaLibraryPrimaryFilter) {
        when (filter) {
            NovaLibraryPrimaryFilter.ALL -> filterState = NovaLibraryFilterState()
            NovaLibraryPrimaryFilter.RECENT -> filterState = NovaLibraryFilterState(primary = filter)
            NovaLibraryPrimaryFilter.SOURCES -> activeFilterSheet = LibraryFilterSheet.SOURCES
            NovaLibraryPrimaryFilter.HDR -> filterState = NovaLibraryFilterState(primary = filter)
            NovaLibraryPrimaryFilter.MORE -> activeFilterSheet = LibraryFilterSheet.MORE
        }
    }

    private fun applySourceFilter(source: String?) {
        filterState = if (source == null) {
            NovaLibraryFilterState()
        } else {
            NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.SOURCES, source = source)
        }
        activeFilterSheet = null
    }

    private fun applyCategoryFilter(category: String) {
        filterState = NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.MORE, category = category)
        activeFilterSheet = null
    }

    private fun applyGenreFilter(genre: String) {
        filterState = NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.MORE, genre = genre)
        activeFilterSheet = null
    }

    private fun clearFilters() {
        filterState = NovaLibraryFilterState()
        searchQuery = ""
        activeFilterSheet = null
    }

    private fun movePrimaryFilter(direction: Int) {
        val filters = NovaLibraryPrimaryFilter.entries
        val currentIndex = filters.indexOf(filterState.primary).coerceAtLeast(0)
        val nextIndex = (currentIndex + direction + filters.size) % filters.size
        handlePrimaryFilter(filters[nextIndex])
    }

    private fun showGameDetail(game: PolarisGame) {
        detailSheet?.dismissAllowingStateLoss()
        val preferences = PreferenceConfiguration.readPreferences(this)
        val defaultToVirtualDisplay = preferences.useVirtualDisplay
        detailSheet = NovaGameDetailSheet.newInstance(
            game = game,
            apiClient = apiClient,
            defaultToVirtualDisplay = defaultToVirtualDisplay,
            clientSettings = clientSettings
        ) { selectedGame, withVirtualDisplay ->
            launchGame(selectedGame, withVirtualDisplay)
        }
        detailSheet?.show(supportFragmentManager, "game_detail")
    }

    private fun launchGame(game: PolarisGame, withVirtualDisplay: Boolean) {
        if (game.appId <= 0) {
            Toast.makeText(this, "This game entry is missing a launch ID", Toast.LENGTH_SHORT).show()
            return
        }
        val uniqueId = streamUniqueId
        val pcUuid = streamPcUuid
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || pcUuid.isNullOrBlank() || serverCert == null) {
            Toast.makeText(this, "Missing Polaris session details for launch", Toast.LENGTH_SHORT).show()
            LimeLog.warning("Nova: Cannot launch from library; missing uniqueId, pcUuid, or server cert")
            return
        }

        Toast.makeText(
            this,
            getString(
                R.string.nova_library_launching_mode,
                game.name,
                if (withVirtualDisplay) getString(R.string.nova_library_launch_virtual_display)
                else getString(R.string.nova_library_launch_headless)
            ),
            Toast.LENGTH_SHORT
        ).show()

        val app = NvApp(game.name, game.id, game.appId, game.hdrSupported)
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
            withVirtualDisplay,
            true,
            false,
            serverCert
        )
    }

    private fun resumeActiveSession(session: NovaLibraryActiveSessionUiState) {
        val uniqueId = streamUniqueId
        val pcUuid = streamPcUuid
        val serverCert = streamServerCert
        if (uniqueId.isNullOrBlank() || pcUuid.isNullOrBlank() || serverCert == null) {
            Toast.makeText(this, "Missing Polaris session details for resume", Toast.LENGTH_SHORT).show()
            LimeLog.warning("Nova: Cannot resume from library; missing uniqueId, pcUuid, or server cert")
            return
        }

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
            false,
            false,
            session.watchOnly,
            serverCert
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
    private fun NovaLibraryScreen(
        serverName: String?,
        serverHost: String,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        searchQuery: String,
        isInitialLoading: Boolean,
        isRefreshing: Boolean,
        clientSettings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?,
        apiClient: PolarisApiClient,
        activeFilterSheet: LibraryFilterSheet?,
        onBack: () -> Unit,
        onSearchChange: (String) -> Unit,
        onRefresh: () -> Unit,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onManageServer: () -> Unit,
        onOpenDetail: (PolarisGame) -> Unit,
        onPrimaryFilter: (NovaLibraryPrimaryFilter) -> Unit,
        onDismissFilterSheet: () -> Unit,
        onSourceFilter: (String?) -> Unit,
        onCategoryFilter: (String) -> Unit,
        onGenreFilter: (String) -> Unit,
        onClearFilters: () -> Unit
    ) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
        val columns = NovaLibraryUiStateMapper.gridColumnsForScreen(configuration.screenWidthDp, isLandscape)
        val railWidth = NovaLibraryUiStateMapper.railWidthDp(configuration.screenWidthDp).dp
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.window)
        ) {
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
                    .padding(if (isLandscape) 10.dp else 8.dp)
            ) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NovaLibraryRail(
                            modifier = Modifier
                                .width(railWidth)
                                .fillMaxHeight(),
                            serverName = serverName,
                            serverHost = serverHost,
                            model = model,
                            filterState = filterState,
                            searchQuery = searchQuery,
                            clientSettings = clientSettings,
                            activeSession = activeSession,
                            onSearchChange = onSearchChange,
                            onRefresh = onRefresh,
                            onResumeSession = onResumeSession,
                            onManageServer = onManageServer,
                            onBack = onBack,
                            onPrimaryFilter = onPrimaryFilter,
                            sourceLabel = { sourceLabelFor(it) }
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (model.recentGames.isNotEmpty()) {
                                NovaLibraryRecentRail(
                                    games = model.recentGames,
                                    apiClient = apiClient,
                                    onOpenDetail = onOpenDetail
                                )
                            }
                            NovaLibraryContent(
                                modifier = Modifier.weight(1f),
                                model = model,
                                columns = columns,
                                isLandscape = true,
                                isInitialLoading = isInitialLoading,
                                isRefreshing = isRefreshing,
                                apiClient = apiClient,
                                onRefresh = onRefresh,
                                onOpenDetail = onOpenDetail
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
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
                            onSearchChange = onSearchChange,
                            onRefresh = onRefresh,
                            onResumeSession = onResumeSession,
                            onManageServer = onManageServer,
                            onBack = onBack,
                            onPrimaryFilter = onPrimaryFilter,
                            sourceLabel = { sourceLabelFor(it) }
                        )
                        if (model.recentGames.isNotEmpty()) {
                            NovaLibraryRecentRail(
                                games = model.recentGames,
                                apiClient = apiClient,
                                onOpenDetail = onOpenDetail
                            )
                        }
                        NovaLibraryContent(
                            modifier = Modifier.weight(1f),
                            model = model,
                            columns = columns,
                            isLandscape = false,
                            isInitialLoading = isInitialLoading,
                            isRefreshing = isRefreshing,
                            apiClient = apiClient,
                            onRefresh = onRefresh,
                            onOpenDetail = onOpenDetail
                        )
                    }
                }
            }

            if (activeFilterSheet != null) {
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

    @Composable
    private fun NovaLibraryRail(
        modifier: Modifier,
        serverName: String?,
        serverHost: String,
        model: NovaLibraryUiModel,
        filterState: NovaLibraryFilterState,
        searchQuery: String,
        clientSettings: PolarisClientSettings?,
        activeSession: NovaLibraryActiveSessionUiState?,
        onSearchChange: (String) -> Unit,
        onRefresh: () -> Unit,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onManageServer: () -> Unit,
        onBack: () -> Unit,
        onPrimaryFilter: (NovaLibraryPrimaryFilter) -> Unit,
        sourceLabel: (String?) -> String
    ) {
        NovaLibraryPanel(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NovaLibraryTitle(serverName, serverHost)
                NovaLibraryStatus(settings = clientSettings)
                if (activeSession != null) {
                    NovaLibraryActiveSessionCard(
                        session = activeSession,
                        onResumeSession = onResumeSession,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NovaLibrarySummary(model = model)
                NovaSearchField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaActionButton(
                        text = stringResource(R.string.nova_refresh),
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                        minHeight = 38.dp,
                        fontSize = 12.sp
                    )
                    NovaActionButton(
                        text = stringResource(R.string.nova_library_manage),
                        onClick = onManageServer,
                        modifier = Modifier.weight(1f),
                        minHeight = 38.dp,
                        fontSize = 12.sp
                    )
                }
                NovaActionButton(
                    text = stringResource(R.string.nova_library_switch_server),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 38.dp,
                    fontSize = 12.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaLibraryPrimaryFilter.entries.forEach { filter ->
                        NovaFilterChip(
                            filter = filter,
                            selected = filterState.primary == filter,
                            count = filterCount(filter, model),
                            filterState = filterState,
                            sourceLabel = sourceLabel,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onPrimaryFilter(filter) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.nova_library_results_format, model.resultCount),
                    color = LocalNovaComposeColors.current.textSecondary,
                    fontSize = 12.sp
                )
            }
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
        onSearchChange: (String) -> Unit,
        onRefresh: () -> Unit,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit,
        onManageServer: () -> Unit,
        onBack: () -> Unit,
        onPrimaryFilter: (NovaLibraryPrimaryFilter) -> Unit,
        sourceLabel: (String?) -> String
    ) {
        NovaLibraryPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        NovaLibraryTitle(serverName, serverHost)
                    }
                    NovaActionButton(
                        text = stringResource(R.string.nova_refresh),
                        onClick = onRefresh,
                        minHeight = 38.dp,
                        fontSize = 12.sp
                    )
                    NovaActionButton(
                        text = stringResource(R.string.nova_library_manage),
                        onClick = onManageServer,
                        minHeight = 38.dp,
                        fontSize = 12.sp
                    )
                }
                NovaLibraryStatus(settings = clientSettings)
                if (activeSession != null) {
                    NovaLibraryActiveSessionCard(
                        session = activeSession,
                        onResumeSession = onResumeSession,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NovaLibrarySummary(model = model)
                NovaSearchField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth()
                )
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
                            onClick = { onPrimaryFilter(filter) }
                        )
                    }
                    NovaActionButton(
                        text = stringResource(R.string.nova_library_switch_server),
                        onClick = onBack,
                        minHeight = 40.dp,
                        fontSize = 12.sp
                    )
                }
            }
        }
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
    private fun NovaLibraryStatus(settings: PolarisClientSettings?) {
        val autoQualityEnabled = settings?.let {
            it.desired.aiAutoQualityEnabled == true ||
                it.effective.aiAutoQualityEnabled == true ||
                it.desired.adaptiveBitrateEnabled == true ||
                it.effective.adaptiveBitrateEnabled == true ||
                it.desired.aiOptimizerEnabled == true ||
                it.effective.aiOptimizerEnabled == true
        }
        val autoQualityText = when (autoQualityEnabled) {
            true -> stringResource(R.string.nova_library_auto_quality_on)
            false -> stringResource(R.string.nova_library_auto_quality_off)
            null -> stringResource(R.string.nova_library_status_checking)
        }
        val modeText = settings?.effectiveModeLabel
            ?: settings?.desiredModeLabel
            ?: stringResource(R.string.nova_library_mode_checking)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovaStatusPill(text = autoQualityText, enabled = autoQualityEnabled == true)
            NovaStatusPill(
                text = modeText,
                enabled = settings != null
            )
        }
    }

    @Composable
    private fun NovaLibraryActiveSessionCard(
        session: NovaLibraryActiveSessionUiState,
        modifier: Modifier = Modifier,
        onResumeSession: (NovaLibraryActiveSessionUiState) -> Unit
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
        val detail = listOfNotNull(ownerDetail, viewerDetail).joinToString(" / ")
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
            NovaActionButton(
                text = actionLabel,
                onClick = { onResumeSession(session) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                minHeight = 38.dp,
                fontSize = 12.sp
            )
        }
    }

    @Composable
    private fun NovaLibrarySummary(model: NovaLibraryUiModel) {
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
        modifier: Modifier = Modifier
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
                        Key.Enter, Key.NumPadEnter -> {
                            if (!searchEditing) {
                                beginSearchEditing()
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionCenter -> true
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
                .height(44.dp)
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
        onClick: () -> Unit
    ) {
        val label = filterLabel(filter, filterState, sourceLabel)
        NovaSelectableChip(
            label = label,
            detail = count.toString(),
            selected = selected,
            modifier = modifier,
            onClick = onClick
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NovaLibraryContent(
        modifier: Modifier,
        model: NovaLibraryUiModel,
        columns: Int,
        isLandscape: Boolean,
        isInitialLoading: Boolean,
        isRefreshing: Boolean,
        apiClient: PolarisApiClient,
        onRefresh: () -> Unit,
        onOpenDetail: (PolarisGame) -> Unit
    ) {
        NovaLibraryPanel(modifier = modifier, subtle = true) {
            if (isInitialLoading && model.allGames.isEmpty()) {
                NovaLibraryLoadingGrid(columns = columns, isLandscape = isLandscape)
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (model.filteredGames.isEmpty()) {
                        NovaLibraryEmptyState(model.emptyState)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(model.filteredGames, key = { it.id }) { game ->
                                NovaLibraryGameCard(
                                    game = game,
                                    apiClient = apiClient,
                                    compact = false,
                                    isLandscape = isLandscape,
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
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(games, key = { it.id }) { game ->
                        NovaLibraryGameCard(
                            game = game,
                            apiClient = apiClient,
                            compact = true,
                            isLandscape = false,
                            modifier = Modifier.width(176.dp),
                            onOpenDetail = { onOpenDetail(game) }
                        )
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
        isLandscape: Boolean,
        modifier: Modifier = Modifier,
        onOpenDetail: () -> Unit
    ) {
        val surfaces = LocalNovaLibrarySurfaces.current
        var focused by remember { mutableStateOf(false) }
        val cardHeight = when {
            compact -> 126.dp
            isLandscape -> 164.dp
            else -> 184.dp
        }
        val title = game.name.ifBlank { "Unknown game" }
        val meta = listOfNotNull(
            sourceLabelFor(game.source),
            game.category.takeIf { it.isNotBlank() }?.let { categoryLabelFor(it) }
        ).joinToString(" / ")

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(cardHeight)
                .graphicsLayer {
                    scaleX = if (focused) 1.035f else 1f
                    scaleY = if (focused) 1.035f else 1f
                }
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) surfaces.tile.copy(alpha = 1f) else surfaces.tile)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) surfaces.focusRing else surfaces.tileBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .combinedClickable(
                    onClick = onOpenDetail,
                    onLongClick = onOpenDetail
                )
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .semantics {
                    contentDescription = title
                }
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
                        .border(4.dp, surfaces.focusRing, RoundedCornerShape(14.dp))
                        .padding(4.dp)
                        .border(2.dp, surfaces.focusRing.copy(alpha = 0.48f), RoundedCornerShape(10.dp))
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (game.hdrSupported) {
                    NovaMiniBadge(text = stringResource(R.string.badge_hdr))
                }
                if (game.lastLaunched > 0) {
                    NovaMiniBadge(text = stringResource(R.string.nova_library_filter_recent))
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
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

    @Composable
    private fun NovaMiniBadge(text: String) {
        val surfaces = LocalNovaLibrarySurfaces.current
        Text(
            text = text,
            color = surfaces.onMedia,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(surfaces.mediaScrimBottom.copy(alpha = 0.68f))
                .border(1.dp, surfaces.onMedia.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
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
            items(12) {
                NovaLoadingCard(isLandscape = isLandscape)
            }
        }
    }

    @Composable
    private fun NovaLoadingCard(isLandscape: Boolean) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isLandscape) 164.dp else 184.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(14.dp))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .fillMaxWidth(0.72f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.divider.copy(alpha = 0.48f))
            )
        }
    }

    @Composable
    private fun NovaLibraryEmptyState(emptyState: NovaLibraryEmptyState) {
        val title = when (emptyState) {
            NovaLibraryEmptyState.DEFAULT -> stringResource(R.string.nova_library_empty_title_default)
            NovaLibraryEmptyState.RECENT -> stringResource(R.string.nova_library_empty_title_recent)
            NovaLibraryEmptyState.FILTERED -> stringResource(R.string.nova_library_empty_title_filtered)
        }
        val message = when (emptyState) {
            NovaLibraryEmptyState.DEFAULT -> stringResource(R.string.nova_library_empty_hint_default)
            NovaLibraryEmptyState.RECENT -> stringResource(R.string.nova_library_empty_hint_recent)
            NovaLibraryEmptyState.FILTERED -> stringResource(R.string.nova_library_empty_hint_filtered)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = LocalNovaComposeColors.current.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = message,
                    color = LocalNovaComposeColors.current.textSecondary,
                    fontSize = 13.sp
                )
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
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = surfaces.panel,
            contentColor = colors.textPrimary,
            scrimColor = surfaces.backgroundScrim.copy(alpha = 0.30f)
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
                        onClick = onClearFilters
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
        onClick: () -> Unit
    ) {
        val colors = LocalNovaComposeColors.current
        val surfaces = LocalNovaLibrarySurfaces.current
        var focused by remember { mutableStateOf(false) }
        val stroke = when {
            focused -> surfaces.focusRing
            selected -> colors.accent.copy(alpha = 0.72f)
            else -> surfaces.tileBorder
        }
        Row(
            modifier = modifier
                .height(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) surfaces.selectedControl else surfaces.control
                )
                .border(if (focused) 2.dp else 1.dp, stroke, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onClick)
                .onFocusChanged { focused = it.isFocused }
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
            color = if (subtle) surfaces.panel.copy(alpha = 0.34f) else surfaces.panel,
            border = BorderStroke(
                1.dp,
                if (subtle) surfaces.panelBorder.copy(alpha = 0.30f) else surfaces.panelBorder
            ),
            content = content
        )
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
    }
}
