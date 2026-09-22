package com.papi.nova.ui

import android.app.Dialog
import androidx.core.content.edit
import com.papi.nova.NovaActivity
import com.papi.nova.api.PolarisGameJson
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisApiRejectedException
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.api.isLaunchModeAvailable
import com.papi.nova.api.isLaunchModeSessionOverridable
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.preferences.NovaDisplayFpsCapability
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.compose.NovaFocusableCard
import com.papi.nova.utils.DeviceUtils
import com.papi.nova.utils.GameShortcutPinState
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt


/**
 * Full-screen window showing game details, tuning, and explicit launch modes.
 * Opened from the Polaris library; returns the chosen launch, so the library
 * performs it after this window closes.
 */
class NovaGameDetailActivity : NovaActivity() {

    private lateinit var apiClient: PolarisApiClient
    private lateinit var shortcutHelper: ShortcutHelper
    private lateinit var artworkViewModel: NovaArtworkLibraryUpdateViewModel
    private lateinit var launchViewModel: NovaGameDetailLaunchViewModel
    private var directSpaceOpen = false
    private var spaceGame: PolarisGame? = null
    private var spaceLaunchDelivered = false
    private var spacesEnabled = true
    // Assigned once loadPlayDestinations exists, so onResume and an early launch can ask for it.
    private var refreshPlayDestinations: () -> Unit = {}
    private var resumedOnce = false
    private var defaultToVirtualDisplay: Boolean = false
    private var clientSettings: PolarisClientSettings? = null
    private var serverName: String = ""
    private var serverUuid: String? = null
    private var shortcutGameAppId: Int? = null
    private var shortcutPinState by mutableStateOf(GameShortcutPinState.UNSUPPORTED)
    private var shortcutPinRequestPending by mutableStateOf(false)

    /**
     * The sheet took these as constructor lambdas. Keeping the names and the nullable
     * shape lets the body below stay the code that was reviewed as a bottom sheet,
     * rather than a rewrite that happens to compile.
     */
    private val onLaunch: ((PolarisGame, Boolean, Boolean, Boolean, String, String, String, String, JSONObject?) -> Unit)? =
        { game, withVirtualDisplay, mirrorDesktop, forcePrivateAfterSteamClose, profilePreference, streamMode, encoderBackend, presentationMode, preflight ->
            setResult(
                RESULT_OK,
                Intent().putExtra(
                    EXTRA_RESULT_LAUNCH,
                    JSONObject()
                        .put(RESULT_KEY_VIRTUAL_DISPLAY, withVirtualDisplay)
                        .put(RESULT_KEY_MIRROR_DESKTOP, mirrorDesktop)
                        .put(RESULT_KEY_FORCE_PRIVATE, forcePrivateAfterSteamClose)
                        .put(RESULT_KEY_PROFILE_PREFERENCE, profilePreference)
                        .put(RESULT_KEY_STREAM_MODE, streamMode)
                        .put(RESULT_KEY_ENCODER_BACKEND, encoderBackend)
                        // Presentation only: the library can name an unpinned host
                        // default without turning it into streamMode authority.
                        .put(RESULT_KEY_PRESENTATION_MODE, presentationMode)
                        .put(RESULT_KEY_PREFLIGHT, preflight ?: JSONObject.NULL)
                        .toString(),
                )
                    .putExtra(EXTRA_RESULT_LAUNCH_GAME, PolarisGameJson.encode(game))
                    .putExtra(EXTRA_RESULT_GAME, updatedGame?.let { PolarisGameJson.encode(it) }),
            )
        }

    private val onGameUpdated: ((PolarisGame) -> Unit)? = { game -> updatedGame = game }

    /** Resume and End need stream credentials this window does not carry, so it asks. */
    private fun finishWithSessionRequest(request: String) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_SESSION, request)
                .putExtra(EXTRA_RESULT_SPACE, spaceGame?.let(PolarisGameJson::encode))
                .putExtra(EXTRA_RESULT_GAME, updatedGame?.let { PolarisGameJson.encode(it) }),
        )
        finish()
    }

    /** Return to the library so its existing, correctly port-scoped host action opens Polaris. */
    private fun finishWithManageServerRequest() {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_MANAGE_SERVER, true)
                .putExtra(EXTRA_RESULT_GAME, updatedGame?.let { PolarisGameJson.encode(it) }),
        )
        finish()
    }

    private val onRefreshArtwork: ((PolarisGame, (NovaArtworkMutationResult) -> Unit) -> Unit)? =
        { game, onResult -> artworkViewModel.refreshArtwork(game = game, onResult = onResult) }

    private val onApplyArtwork: ((
        PolarisGame,
        PolarisArtworkMatchCandidate,
        Map<String, PolarisArtworkChoice>,
        (NovaArtworkMutationResult) -> Unit,
    ) -> Unit)? = { game, candidate, selections, onResult ->
        artworkViewModel.applyArtworkSelections(
            game = game,
            candidate = candidate,
            selections = selections,
            onResult = onResult,
        )
    }

    private val onClearArtwork: ((PolarisGame, (NovaArtworkMutationResult) -> Unit) -> Unit)? =
        { game, onResult -> artworkViewModel.clearArtworkOverride(game = game, onResult = onResult) }

    /** Artwork and MangoHUD edits made here; handed back so the library can merge them. */
    private var updatedGame: PolarisGame? = null

    private var destination by mutableStateOf(NovaGameDetailDestination.OVERVIEW)

    /**
     * Which subject Play Setup shows: this game, or the host defaults every game
     * inherits. Y flips it while the panel is open — the first key this window claims —
     * and the header pill does the same by touch.
     */
    private var playSetupScope by mutableStateOf(NovaPlaySetupScope.THIS_GAME)
    private var modePickerOpen by mutableStateOf(false)

    /** The strip explains this row; rows point it at themselves as focus moves. */
    private var explainedRow by mutableStateOf(NovaPlaySetupRow.WHERE_IT_RUNS)

    // Where a Space game opens is its own control above the rows, not a row the strip explains,
    // so a Space game's strip starts on Resolution. A row it does not have would leave it empty.
    private fun openingExplainedRow(): NovaPlaySetupRow =
        if (spaceGame != null) NovaPlaySetupRow.RESOLUTION else NovaPlaySetupRow.WHERE_IT_RUNS

    /**
     * The Polaris Sync sheet's engine, as Every Game's second surface. Started when
     * that scope first opens so the panel does not poll the host for people who never
     * flip it, and closed with the panel.
     */
    private var hostSyncEngine: NovaPolarisSyncEngine? = null

    /** One icon-resolution/pin request at a time; lifecycle cancellation reaches OkHttp. */
    private var pinShortcutJob: Job? = null

    /**
     * Set when Polaris reports desktop Steam active. It turns Launch mode into the
     * three-way choice that used to be a bottom sheet raised over the content.
     */
    private var steamDecision by mutableStateOf<NovaDesktopSteamLaunchDecision?>(null)

    /** The preflight review, expanded on the Overview rather than raised as an alert. */
    private var reviewExpanded by mutableStateOf(false)

    /** The host's session, when it is this game's. Null means nothing is running. */
    private var activeSession by mutableStateOf<NovaLibraryActiveSessionUiState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, DEFAULT_HTTPS_PORT)
        val serverCert = intent.getByteArrayExtra(EXTRA_SERVER_CERT)
        val game = intent.getStringExtra(EXTRA_GAME)?.let { PolarisGameJson.decode(it) }
        if (host.isBlank() || game == null) {
            LimeLog.warning("Nova: Game detail opened without a host or game; closing")
            finish()
            return
        }
        spaceGame = game.takeIf(NovaSpaceUiState::isSpace)
        spacesEnabled = intent.getBooleanExtra(EXTRA_SPACES_ENABLED, true)
        directSpaceOpen = spaceGame != null && savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_SPACE, false)
        if (intent.getBooleanExtra(EXTRA_PLAY_SETUP, false) ||
            spaceGame != null && intent.getBooleanExtra(EXTRA_SPACE_SETTINGS, false)) {
            destination = NovaGameDetailDestination.PLAY_SETUP
        }
        defaultToVirtualDisplay = intent.getBooleanExtra(EXTRA_DEFAULT_VIRTUAL_DISPLAY, false)

        serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty().ifBlank { host }
        serverUuid = intent.getStringExtra(EXTRA_SERVER_UUID)

        apiClient = PolarisApiClient(this, host, httpsPort, serverCert)
        shortcutHelper = ShortcutHelper(this)
        shortcutGameAppId = game.appId
        refreshShortcutPinState()
        artworkViewModel = ViewModelProvider(
            this,
            NovaArtworkLibraryUpdateViewModel.Factory(
                context = applicationContext,
                serverAddress = host,
                httpsPort = httpsPort,
                serverCertDer = serverCert,
            ),
        )[NovaArtworkLibraryUpdateViewModel::class.java]
        launchViewModel = ViewModelProvider(
            this,
            NovaGameDetailLaunchViewModel.Factory(
                context = applicationContext,
                serverAddress = host,
                httpsPort = httpsPort,
                serverCertDer = serverCert,
                gameId = game.id,
                initialSteamLaunchMode = game.steamLaunchMode,
            ),
        )[NovaGameDetailLaunchViewModel::class.java]

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (dismissActiveDetailDestination()) return
                    publishGameUpdate()
                    finish()
                }
            },
        )

        setUpDetail(game, apiClient)
        refreshActiveSession(game)
    }

    override fun onResume() {
        super.onResume()
        refreshShortcutPinState()
        // Where this game can play changes while the window is away (a Space started or
        // stopped, a switch made in the library), so the answer is refreshed on return.
        if (resumedOnce) refreshPlayDestinations()
        resumedOnce = true
    }

    private fun refreshShortcutPinState() {
        if (!::shortcutHelper.isInitialized) return
        val hostUuid = serverUuid?.takeIf { it.isNotBlank() }
        val appId = shortcutGameAppId
        shortcutPinState = if (spaceGame != null || hostUuid == null || appId == null) {
            GameShortcutPinState.UNSUPPORTED
        } else {
            shortcutHelper.getGameShortcutPinState(hostUuid, appId)
        }
        if (shortcutPinState != GameShortcutPinState.AVAILABLE) {
            shortcutPinRequestPending = false
        }
    }

    /**
     * requestPinShortcut() only means the launcher accepted the request. Poll briefly
     * while its confirmation UI is up, and recheck again from onResume, so Nova never
     * calls a request "pinned" before Android does.
     */
    private fun awaitShortcutPinConfirmation() {
        shortcutPinRequestPending = true
        lifecycleScope.launch {
            repeat(12) {
                delay(250)
                refreshShortcutPinState()
                if (shortcutPinState == GameShortcutPinState.PINNED) {
                    return@launch
                }
            }
            shortcutPinRequestPending = false
        }
    }

    /**
     * Unwinds one level: an expanded review collapses, a destination returns to the
     * Overview, and only then does back leave for the library.
     *
     * There used to be a level above these for closing whichever row's options were
     * showing in the strip. The strip is a legend now rather than something that opens,
     * so there is nothing to close and back has one fewer step to take.
     */
    private fun dismissActiveDetailDestination(): Boolean = when {
        reviewExpanded -> {
            reviewExpanded = false
            true
        }
        modePickerOpen -> {
            modePickerOpen = false
            true
        }
        spaceGame != null && destination == NovaGameDetailDestination.PLAY_SETUP &&
            intent.getBooleanExtra(EXTRA_SPACE_SETTINGS, false) -> {
            // Settings opened from Library return to its selected Space and live status.
            publishGameUpdate()
            finish()
            true
        }
        destination != NovaGameDetailDestination.OVERVIEW -> {
            destination = NovaGameDetailDestination.OVERVIEW
            steamDecision = null
            modePickerOpen = false
            // The panel reopens on the game it was opened for; host scope is a place
            // someone flips to, not a place the panel should quietly resume in.
            playSetupScope = NovaPlaySetupScope.THIS_GAME
            explainedRow = openingExplainedRow()
            hostSyncEngine?.close()
            true
        }
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Y is unclaimed everywhere else in this window, so the scope flip takes
        // nothing from anyone. Claimed only while Play Setup is open: a key that acts
        // on a panel that is not on screen is a key that does something invisible.
        if (spaceGame == null && keyCode == KeyEvent.KEYCODE_BUTTON_Y && destination == NovaGameDetailDestination.PLAY_SETUP) {
            selectPlaySetupScope(
                if (playSetupScope == NovaPlaySetupScope.THIS_GAME) {
                    NovaPlaySetupScope.EVERY_GAME
                } else {
                    NovaPlaySetupScope.THIS_GAME
                }
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun selectPlaySetupScope(scope: NovaPlaySetupScope) {
        if (spaceGame != null || playSetupScope == scope) {
            return
        }
        playSetupScope = scope
        modePickerOpen = false
        explainedRow = if (scope == NovaPlaySetupScope.EVERY_GAME) {
            NovaPlaySetupRow.HOST_DEFAULT_DISPLAY
        } else {
            openingExplainedRow()
        }
        if (scope == NovaPlaySetupScope.EVERY_GAME) {
            hostSyncEngine?.let { engine ->
                engine.start(clientSettings)
                engine.refresh()
            }
        }
    }

    override fun onDestroy() {
        pinShortcutJob?.cancel()
        pinShortcutJob = null
        hostSyncEngine?.close()
        super.onDestroy()
    }

    /**
     * Polaris reports one session at a time, so it only matters here when it is this
     * game's. Matched on the UUID Polaris uses, falling back to the numeric app id.
     */
    private fun refreshActiveSession(game: PolarisGame) {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                runCatching { NovaLibraryActiveSessionUiState.from(apiClient.getSessionStatus()) }
                    .getOrNull()
            }
            activeSession = if (NovaSpaceUiState.isSpace(game)) NovaSpaceUiState.matchingSession(game, session) else session?.takeIf {
                it.gameUuid.equals(game.id, ignoreCase = true) || it.gameId == game.appId
            }
        }
    }

    /** Carries artwork or MangoHUD edits back even when the window closes without launching. */
    private fun publishGameUpdate() {
        val game = updatedGame ?: return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_GAME, PolarisGameJson.encode(game)))
    }

    override fun finish() {
        super.finish()
        NovaThemeManager.applyBackTransition(this)
    }

    private fun canPublishArtworkMutationUi(): Boolean =
        canPublishArtworkMutationUiForState(lifecycle.currentState)

    private fun setUpDetail(game: PolarisGame, apiClient: PolarisApiClient) {
        val deviceName = DeviceUtils.getModel()

        val retainedSteamLaunchMode = launchViewModel.steamLaunchModeSnapshot().displayMode
        var environmentChanging by mutableStateOf(false)
        var environmentError by mutableStateOf<String?>(null)
        var environmentSnapshot by mutableStateOf<com.papi.nova.api.PolarisSpaces?>(null)
        var playDestinations by mutableStateOf<List<NovaPlayDestination>>(emptyList())
        var currentGame by mutableStateOf(
            game.copy(
                steamLaunch = game.steamLaunch?.copy(mode = retainedSteamLaunchMode),
            ),
        )
        var profilePreference by mutableStateOf(loadProfilePreference(currentGame))
        var uiState by mutableStateOf(buildUiState(currentGame, profilePreference))
        var mangoHudEnabled by mutableStateOf(game.mangohud)
        var resetWorking by mutableStateOf(false)
        var optimizationState by mutableStateOf(NovaGameDetailOptimizationState())
        var artworkState by mutableStateOf(loadArtworkState(game))
        // Which row the comparison strip is explaining. It follows focus, and a tap sets
        // it too -- touch has no cursor for the strip to follow, and a finger that lands
        // on a row should get the same explanation a d-pad would.
        explainedRow = openingExplainedRow()
        // An explicit resolution, held until launch rather than launching on the spot.
        // Picking one used to start the game immediately, which is why the row that owned
        // it could not be a setting: there was nothing to set. The choice itself is
        // rebuilt fresh from the planner every open, so only its id is persisted
        // (NovaResolutionOverrides) and resolved back against this open's planner here.
        var chosenResolution by mutableStateOf<NovaDisplayResolutionChoice?>(loadResolutionOverride(currentGame))
        // An explicit frame-rate pin, independent of resolution. It composes over
        // the host-resolved cadence -- see NovaLaunchStreamOverride.compose, where the
        // resolution row changes dimensions while fpsOverride alone changes cadence. Persisted
        // the same way as chosenResolution, so it survives past this Activity's lifetime.
        var chosenFps by mutableStateOf<Int?>(loadFrameRateOverride(currentGame))
        // Null keeps Polaris' configured host policy. "auto" is deliberately not
        // null: it asks Polaris to choose and permits fallback for this launch.
        var chosenEncoderBackend by mutableStateOf<String?>(
            NovaEncoderBackendOverrides.load(this@NovaGameDetailActivity, currentGame),
        )
        // Changing a row is cheap; telling the host about it is not. Presses settle first
        // so that cycling past three values costs one round-trip rather than three.
        var settleJob: Job? = null
        var pendingSettledWork: (suspend () -> Unit)? = null
        // A blocking host request may not observe coroutine cancellation until it returns.
        // The generation fence is therefore the authority boundary: only the newest
        // request may publish settings/optimization state or replay a held Play press.
        val preflightRequestFence = NovaLaunchPreflightRequestFence()
        var preflightJob: Job? = null
        // The retained ViewModel owns the actual Steam mutation. This Activity job only
        // waits to publish its result; recreation may cancel the waiter without losing
        // host ordering or allowing the replacement window to launch stale state.
        var steamLaunchModeJob: Job? = null
        // A launch was asked for while the preflight that arms the desktop-Steam guard was
        // still on the wire. The press is held rather than dropped: being quick should not
        // cost you the launch, and it must not cost you the guard either.
        var pendingLaunch by mutableStateOf(false)

        /**
         * The blob this launch would go out with: the host's plan, composed with the
         * resolution chosen here and the fps pin, when either exists. Composed over the
         * host blob rather than replacing it, so a resolution pick no longer silently
         * discards the recovery clamp -- only the explicit fps pin releases it. An
         * explicit Frame Rate row choice wins over the implicit Tuning = High FPS pin,
         * since a pin made by name here is a more specific answer than one inferred
         * from a quality profile. The fps that launches must always be re-derivable
         * from this blob.
         */
        fun launchOptimization(): JSONObject? {
            // The worker response is an exact host-validated contract. Space choices
            // go into the request; rewriting the response invalidates its provenance.
            if (spaceGame != null) return optimizationState.rawOptimization
            val preferences = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
            return NovaLaunchStreamOverride.compose(
                raw = optimizationState.rawOptimization,
                resolution = chosenResolution,
                fpsOverride = effectiveFpsPin(chosenFps, profilePreference, preferences.fps),
                fallbackWidth = preferences.width,
                fallbackHeight = preferences.height,
                fallbackFps = preferences.fps.toInt(),
            )
        }

        fun spaceConstraint() = if (spaceGame != null) NovaSpaceUiState.constrainedRequest(
            chosenResolution, chosenFps,
            com.papi.nova.manager.WorkerLaunchContract.parse(optimizationState.rawOptimization),
        ) else null

        fun refreshUiState(preference: String = profilePreference) {
            uiState = buildUiState(currentGame, preference)
        }

        fun reconcileEncoderBackend(settings: PolarisClientSettings): Boolean {
            val available = NovaEncoderBackendOverrides.loadAvailable(
                this@NovaGameDetailActivity,
                currentGame,
                settings,
            )
            val changed = chosenEncoderBackend != available
            chosenEncoderBackend = available
            return changed
        }

        fun selectedEncoderBackend(): String {
            if (com.papi.nova.manager.WorkerLaunchContract.isProfileApp(currentGame.id)) return ""
            val selected = chosenEncoderBackend ?: return ""
            val settings = clientSettings ?: return ""
            return selected.takeIf { candidate ->
                settings.capabilities.sessionEncoderOverride &&
                    settings.capabilities.encoders.any { option ->
                        option.available && option.value == candidate
                    }
            }.orEmpty()
        }

        /**
         * Publish the settings learned by launch preflight before resolving its mode.
         *
         * The eager settings GET is only a convenience and may fail or lose this race.
         * The preflight response is authoritative too, so its capability verdict must
         * rebuild the exact mode that both /optimize and the final launch will carry.
         */
        suspend fun syncAndPublishLaunchPreflightSettings(
            usesVirtualDisplay: Boolean,
            resolvedMode: String,
            requestGeneration: Long,
        ): Boolean {
            // Assigned profiles own their display. Their authenticated resolver
            // supplies the launch contract without writing host display settings.
            if (com.papi.nova.manager.WorkerLaunchContract.isProfileApp(currentGame.id)) return true
            val updated = withContext(Dispatchers.IO) {
                syncLaunchPreflightSettings(
                    this@NovaGameDetailActivity,
                    apiClient,
                    usesVirtualDisplay,
                    clientSettings,
                    resolvedMode,
                )
            } ?: return false
            if (!preflightRequestFence.owns(requestGeneration)) {
                throw CancellationException("launch preflight superseded")
            }
            reconcileEncoderBackend(updated)
            clientSettings = updated
            refreshUiState()
            return true
        }

        /**
         * The host scope, reached from the surface where the per-game choice is made.
         *
         * Polaris Sync owns settings loading and six handlers that write to the host, so
         * it stays where those changes happen. What it did not have was a way in from the
         * decision it is the default for -- it sat four items down the System drawer.
         * Settings it returns are kept, so the host facts drawn beside the game's own
         * answer stay current after a change.
         */
        // The host's own answer, so it can be stated beside the game's. Until now these
        // settings only arrived during launch preflight, which is after the moment they
        // would have been worth reading -- so the host default had no value to show at
        // the point someone is deciding whether to override it.
        lifecycleScope.launch {
            val settings = withContext(Dispatchers.IO) {
                runCatching { apiClient.getClientSettings() }
                    .onFailure { LimeLog.warning("Nova: Failed to load client settings: ${it.message}") }
                    .getOrNull()
            }
            if (settings != null) {
                reconcileEncoderBackend(settings)
                clientSettings = settings
                refreshUiState()
            }
        }

        hostSyncEngine = NovaPolarisSyncEngine(
            context = this,
            apiClient = apiClient,
            serverUuid = serverUuid,
            scope = lifecycleScope,
            onSettingsChanged = { settings ->
                reconcileEncoderBackend(settings)
                clientSettings = settings
                refreshUiState()
            },
            onMessage = { messageRes, isError ->
                if (isError) {
                    NovaSnackbar.showError(this, getString(messageRes))
                } else {
                    NovaSnackbar.showSuccess(this, getString(messageRes))
                }
            },
            onTextMessage = { message, isError ->
                if (isError) {
                    NovaSnackbar.showError(this, message)
                } else {
                    NovaSnackbar.showSuccess(this, message)
                }
            },
        )

        fun acceptArtwork(manifest: PolarisGame.ArtworkManifest) {
            val nextChoiceGeneration = artworkState.choiceGeneration + 1
            currentGame = currentGame.copy(artwork = manifest)
            refreshUiState()
            artworkState = loadArtworkState(currentGame).copy(choiceGeneration = nextChoiceGeneration)
            onGameUpdated?.invoke(currentGame)
        }

        fun loadArtworkChoices(candidate: PolarisArtworkMatchCandidate, kind: String) {
            val normalizedKind = kind.trim().lowercase().takeIf { it in NovaArtworkKinds.ALL } ?: return
            if (normalizedKind in artworkState.loadedKinds || normalizedKind in artworkState.loadingKinds) return
            val generation = artworkState.choiceGeneration
            artworkState = artworkState.reduce(
                NovaArtworkStudioAction.ChoicesLoading(candidate, normalizedKind, generation),
            )
            lifecycleScope.launch {
                try {
                    val choices = withContext(Dispatchers.IO) {
                        apiClient.listArtworkChoices(currentGame.id, candidate, normalizedKind)
                    }
                    artworkState = artworkState.reduce(
                        NovaArtworkStudioAction.ChoicesLoaded(
                            candidate = candidate,
                            kind = normalizedKind,
                            choices = choices,
                            emptyMessage = if (choices.isEmpty()) getString(R.string.nova_artwork_no_choices) else "",
                            generation = generation,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    artworkState = artworkState.reduce(
                        NovaArtworkStudioAction.ChoicesFailed(
                            message = getString(R.string.nova_artwork_choices_failed),
                            candidate = candidate,
                            kind = normalizedKind,
                            generation = generation,
                        ),
                    )
                }
            }
        }


        // Assigned once loadOptimization exists. Held work runs early when a launch is
        // waiting on it, so pressing Play never means waiting out a delay meant for
        // someone still cycling.
        var flushSettled: () -> Unit = {}
        // Assigned after loadOptimization is declared. It lets the single launch gate
        // retry a failed host-capability check without creating a second launch path.
        var retryPreflight: () -> Unit = {}

        fun launchConfirmed(mirrorDesktop: Boolean, forcePrivateAfterSteamClose: Boolean = false) {
            if (spaceGame != null && spaceLaunchDelivered) return
            if (spaceGame != null) spaceLaunchDelivered = true
            pendingLaunch = false
            onLaunch?.invoke(
                currentGame.copy(mangohud = mangoHudEnabled),
                uiState.playUsesVirtualDisplay,
                mirrorDesktop,
                forcePrivateAfterSteamClose,
                profilePreference,
                // Normal host-default launches send nothing. A validated per-game
                // choice, or the safe replacement for a host default that is no
                // longer launch-ready, travels for this session only.
                uiState.launchStreamMode,
                selectedEncoderBackend(),
                // A normal host default intentionally stays out of streamMode. Carry
                // its resolved name separately so the library can describe it truthfully.
                uiState.playMode,
                launchOptimization()
            )
            finish()
        }

        /**
         * The single way a launch starts, so the guards in front of it cannot be walked past.
         *
         * The desktop-Steam decision is read out of the preflight blob. While a preflight is
         * on the wire that blob is null, which used to fall through to an unguarded launch --
         * and since changing a mode reloads the preflight and used to return you to the Play
         * button, the fastest route through Play Setup was also the one that skipped the guard.
         * A press in that window is now held and replayed when the answer lands.
         *
         * The option cards had the same hole and a second one of their own: they launch with
         * the blob attached to the card but decided from the activity's, so a card carrying
         * its own answer was still judged by whatever the last preflight said. Both paths
         * come through here now, and the blob that decides is the blob that launches.
         */
        fun playEnvironmentReady(): Boolean {
            val space = currentGame.space ?: return true
            val snapshot = environmentSnapshot ?: return false
            return snapshot.selectedId == space.id && snapshot.selected?.openable == true
        }

        fun attemptLaunch() {
            if (environmentChanging) { pendingLaunch = false; return }
            if (environmentError != null) {
                // The failed check is the thing to retry; Play reads "Retry Space Check" until it lands.
                pendingLaunch = false; environmentError = null; refreshPlayDestinations(); return
            }
            if (!playEnvironmentReady()) { pendingLaunch = false; return }
            if (spaceGame != null) {
                if (spaceLaunchDelivered) return
                when (NovaSpaceUiState.availability(currentGame, activeSession)) {
                    NovaSpaceUiState.Availability.IN_USE -> { pendingLaunch = false; return }
                    NovaSpaceUiState.Availability.RESUMABLE -> {
                        pendingLaunch = false
                        spaceLaunchDelivered = true
                        finishWithSessionRequest(RESULT_SESSION_RESUME)
                        return
                    }
                    NovaSpaceUiState.Availability.AVAILABLE -> Unit
                }
            }
            if (spaceConstraint() != null) {
                pendingLaunch = false; destination = NovaGameDetailDestination.PLAY_SETUP; return
            }
            if (!uiState.playEnabled) { pendingLaunch = false; return }
            val optimization = launchOptimization()
            // Guarded on the RAW blob: a pick or an fps pin makes the composed blob
            // non-null even while the preflight that arms the desktop-Steam guard is
            // still on the wire, and a launch in that window must wait either way.
            when (optimizationState.launchPreflightGate()) {
                NovaLaunchPreflightGate.WAIT -> {
                    pendingLaunch = true
                    // Whatever is waiting to settle is what this launch is waiting on, so run
                    // it now instead of holding the press for a delay that exists to absorb
                    // presses nobody is making any more.
                    flushSettled()
                    return
                }
                NovaLaunchPreflightGate.RETRY -> {
                    pendingLaunch = true
                    retryPreflight()
                    return
                }
                NovaLaunchPreflightGate.READY -> Unit
            }
            pendingLaunch = false
            if (spaceConstraint() != null) {
                destination = NovaGameDetailDestination.PLAY_SETUP
                return
            }
            val decision = NovaDesktopSteamLaunchDecision.from(uiState, optimization)
            when {
                // A choice of where to run belongs in the destination that
                // owns where it runs, not in a sheet raised over the artwork.
                decision.required -> {
                    steamDecision = decision
                    destination = NovaGameDetailDestination.PLAY_SETUP
                }
                // The review is a statement about the profile, and the status
                // line is where the profile lives, so it expands in place.
                optimizationState.reviewRequired && !reviewExpanded -> {
                    reviewExpanded = true
                }
                else -> launchConfirmed(false)
            }
        }

        /** A launch plan must be resolved only after the retained Steam mutation settles. */
        suspend fun awaitLatestSteamLaunchModeWrite(): NovaSteamLaunchModeCoordinator.Snapshot {
            val resolution = launchViewModel.awaitLatestSteamLaunchMode()
            if (currentGame.steamLaunchMode != resolution.displayMode) {
                currentGame = currentGame.copy(
                    steamLaunch = currentGame.steamLaunch?.copy(mode = resolution.displayMode),
                )
                refreshUiState()
            }
            return resolution
        }

        fun loadOptimization(preference: String, usesVirtualDisplay: Boolean = uiState.playUsesVirtualDisplay) {
            LimeLog.info(
                "Nova: Preflight optimization requested game=${currentGame.name} " +
                    "preference=$preference virtualDisplay=$usesVirtualDisplay"
            )
            android.util.Log.i(
                "NovaPreflight",
                "requested game=${currentGame.name} preference=$preference virtualDisplay=$usesVirtualDisplay"
            )
            // Marked here rather than inside the coroutine. Callers used to clear the state
            // themselves and then call this, so between those two statements it read as a
            // settled answer of "nothing to guard" -- for as long as the round-trip took.
            preflightJob?.cancel()
            val requestGeneration = preflightRequestFence.begin()
            optimizationState = NovaGameDetailOptimizationState(preflightInFlight = true)
            preflightJob = lifecycleScope.launch {
                var launchCanReplay = false
                var failureMessageShown = false
                val nextOptimizationState = try {
                    awaitLatestSteamLaunchModeWrite()
                    if (!preflightRequestFence.owns(requestGeneration)) {
                        throw CancellationException("launch preflight superseded")
                    }
                    check(
                        syncAndPublishLaunchPreflightSettings(
                            usesVirtualDisplay,
                            uiState.playMode,
                            requestGeneration,
                        ),
                    ) {
                        "launch preflight settings unavailable"
                    }
                    val optimizationMode = uiState.playMode
                    val optimizationEncoder = selectedEncoderBackend()
                    val opt = withContext(Dispatchers.IO) {
                        val launchPrefs = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
                        val metered = StreamSyncManager.isMeteredNetwork(this@NovaGameDetailActivity)
                        val spaceRequest = if (spaceGame != null) NovaSpaceUiState.request(
                            chosenResolution, chosenFps, launchPrefs.width, launchPrefs.height, launchPrefs.fps,
                        ) else null
                        apiClient.getOptimization(
                            deviceName, currentGame.id.ifBlank { currentGame.name }, preference,
                            mode = optimizationMode,
                            topologyLocked = true,
                            width = spaceRequest?.width ?: launchPrefs.width,
                            height = spaceRequest?.height ?: launchPrefs.height,
                            fps = spaceRequest?.fps ?: launchPrefs.fps,
                            bitrateKbps = if (metered) launchPrefs.meteredBitrate else launchPrefs.bitrate,
                            bitrateLocked = metered,
                            hdr = launchPrefs.enableHdr,
                            clientMaxFps = StreamSyncManager.maxSupportedRefreshRate(
                                ServerHelper.getActiveDisplay(this@NovaGameDetailActivity, launchPrefs)
                            ),
                            encoderBackend = optimizationEncoder,
                        )
                    }
                    check(StreamSyncManager.hasTrustedResolvedProfile(opt)) {
                        "trusted launch profile unavailable"
                    }
                    if (!preflightRequestFence.owns(requestGeneration)) {
                        throw CancellationException("launch preflight superseded")
                    }
                    logPreflightOptimization("Preflight optimization", opt, preference)
                    buildOptimizationState(opt, preference).also { launchCanReplay = true }
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: PolarisApiRejectedException) {
                    if (!preflightRequestFence.owns(requestGeneration)) return@launch
                    LimeLog.warning("Nova: Preflight optimization rejected: ${e.rejection.code}")
                    NovaSnackbar.showError(this@NovaGameDetailActivity, e.rejection.error)
                    failureMessageShown = true
                    NovaGameDetailOptimizationState(preflightFailed = true)
                } catch (e: Exception) {
                    if (!preflightRequestFence.owns(requestGeneration)) return@launch
                    LimeLog.warning("Nova: Preflight optimization failed: ${e.message}")
                    NovaGameDetailOptimizationState(preflightFailed = true)
                }
                if (!preflightRequestFence.owns(requestGeneration)) return@launch
                optimizationState = nextOptimizationState
                if (pendingLaunch) {
                    if (launchCanReplay) {
                        attemptLaunch()
                    } else {
                        pendingLaunch = false
                        if (!failureMessageShown) {
                            NovaSnackbar.showError(
                                this@NovaGameDetailActivity,
                                getString(R.string.nova_game_detail_launch_preflight_unavailable),
                            )
                        }
                    }
                }
            }
        }

        retryPreflight = { loadOptimization(profilePreference) }

        /**
         * Tell the host once the presses stop.
         *
         * Every row here used to write to the host on each selection, which was tolerable
         * when a selection meant opening a picker and choosing from it, and is not when a
         * row advances on every press. What matters is that the state is marked in flight
         * *immediately*: during the settle the last answer belongs to the previous value,
         * so a launch in that window has to wait rather than be armed from it. That is the
         * same rule as the preflight guard, applied to a gap this introduces.
         */
        fun settleThen(work: suspend () -> Unit) {
            optimizationState = NovaGameDetailOptimizationState(preflightInFlight = true)
            preflightRequestFence.invalidate()
            preflightJob?.cancel()
            preflightJob = null
            settleJob?.cancel()
            pendingSettledWork = work
            settleJob = lifecycleScope.launch {
                delay(NOVA_PLAY_SETUP_SETTLE_MS)
                if (pendingSettledWork !== work) return@launch
                pendingSettledWork = null
                work()
            }
        }

        flushSettled = {
            val work = pendingSettledWork
            if (work != null) {
                settleJob?.cancel()
                pendingSettledWork = null
                settleJob = lifecycleScope.launch { work() }
            }
        }

        fun selectHighFpsPreset() {
            profilePreference = "high_fps"
            saveProfilePreference(currentGame, profilePreference)
            refreshUiState(profilePreference)
            settleJob?.cancel()
            settleJob = null
            pendingSettledWork = null
            loadOptimization(profilePreference)
        }

        fun selectLaunchMode(mode: String) {
            val normalizedMode = PolarisGame.normalizeLaunchMode(mode)
            val allowed = currentGame.isLaunchModeAvailable(normalizedMode, clientSettings) &&
                clientSettings.isLaunchModeSessionOverridable(normalizedMode)
            if (!allowed || mode == uiState.playMode) return

            val previousLaunchMode = currentGame.launchMode
            val allowedModes = previousLaunchMode?.allowedModes
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(PolarisGame.MODE_HEADLESS_STREAM, PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)
            val updatedLaunchMode = (previousLaunchMode ?: PolarisGame.LaunchModeContract()).copy(
                preferredMode = mode,
                allowedModes = allowedModes
            )
            currentGame = currentGame.copy(launchMode = updatedLaunchMode)
            NovaLaunchModeOverrides.save(this@NovaGameDetailActivity, currentGame, mode)
            refreshUiState()
            // A resolution was an answer to "how should this run on that display". Changing
            // the display changes the question, so the answer does not carry over — and
            // must not resurface on the next open either, or the question looks answered
            // when it was never asked for this display.
            chosenResolution = null
            clearResolutionOverride(currentGame)
            settleThen { loadOptimization(profilePreference, usesVirtualDisplay = PolarisGame.normalizeLaunchMode(mode) == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY) }
        }

        /** The sheet's mapper, fed from the engine, so panel and sheet read alike. */
        fun hostScopeUiState(): NovaPolarisSyncUiState {
            val engine = hostSyncEngine
            val prefs = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
            return NovaPolarisSyncUiStateMapper.build(
                settings = engine?.currentSettings ?: clientSettings,
                busy = engine?.busy == true,
                settingsUnavailable = engine?.settingsUnavailable == true,
                autoSyncEnabled = engine?.autoSyncEnabled == true,
                hasServerUuid = !serverUuid.isNullOrBlank(),
                novaDisplayMode = PreferenceConfiguration.formatStreamingDisplayMode(
                    prefs.width,
                    prefs.height,
                    prefs.fps
                ),
                novaBitrateKbps = prefs.bitrate,
                loadingLabel = getString(R.string.nova_polaris_sync_loading),
                unavailableLabel = getString(R.string.nova_polaris_sync_unavailable),
                unsetLabel = getString(R.string.nova_polaris_sync_unset),
                savedAfterRelaunchLabel = getString(R.string.nova_polaris_sync_status_saved_relaunch),
                selectedLabel = getString(R.string.nova_polaris_sync_status_selected),
                activeNowLabel = getString(R.string.nova_polaris_sync_status_active_now),
                availableLabel = getString(R.string.nova_polaris_sync_status_available),
            )
        }

        /**
         * How many modes the full-panel picker would offer this game: the host
         * catalog cut to the contract's allowed list. Decides row enablement and
         * whether a press opens the picker or cycles the classic pair in place.
         */
        fun gameModeCatalogSize(): Int = buildGameModePickerState(
            modes = hostScopeUiState().modes,
            allowedModes = currentGame.launchMode?.allowedModes.orEmpty(),
            playMode = uiState.playMode,
            hasExplicitOverride = uiState.hasExplicitOverride,
            title = "",
            hostDefaultLabel = "",
        ).choices.count { it.enabled }

        /** Classic headless/virtual can cycle inline; every other multi-choice set opens the picker. */
        fun gameModePickerEligible(): Boolean {
            val inlineChoiceCount = listOf(
                PolarisGame.MODE_HEADLESS_STREAM,
                PolarisGame.MODE_HOST_VIRTUAL_DISPLAY,
            ).count { mode ->
                currentGame.isLaunchModeAvailable(mode, clientSettings) &&
                    clientSettings.isLaunchModeSessionOverridable(mode)
            }
            return novaModePickerEligible(gameModeCatalogSize(), inlineChoiceCount)
        }

        /**
         * A pick from the full-panel picker. The picker only offers what the host
         * catalog allows for this game, so unlike [selectLaunchMode] there is no
         * pair gate to re-check: the override becomes the chosen canonical id.
         */
        fun pickPlayMode(mode: String) {
            modePickerOpen = false
            NovaLaunchModeOverrides.save(this@NovaGameDetailActivity, currentGame, mode)
            refreshUiState()
            chosenResolution = null
            clearResolutionOverride(currentGame)
            settleThen { loadOptimization(profilePreference, usesVirtualDisplay = PolarisGame.normalizeLaunchMode(mode) == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY) }
        }

        /** The pinned entry: drop the override so this game follows the host again. */
        fun pickHostDefault() {
            modePickerOpen = false
            NovaLaunchModeOverrides.clear(this@NovaGameDetailActivity, currentGame)
            refreshUiState()
            chosenResolution = null
            clearResolutionOverride(currentGame)
            settleThen { loadOptimization(profilePreference, usesVirtualDisplay = uiState.playUsesVirtualDisplay) }
        }

        /**
         * Hold the resolution rather than launching with it.
         *
         * Choosing one used to start the stream on the spot, which is why the row that
         * owned it could not read as a setting: there was no state, only a launch wearing
         * a picker's clothes. It rides along as an optimization override now, and the row
         * says so.
         */
        fun chooseResolution(choice: NovaDisplayResolutionChoice) {
            if (choice.id == NovaDisplayResolutionPlanner.DEVICE_SETTINGS_ID) {
                // The device's own setting is what a launch uses with nothing chosen, so going
                // back to it is clearing the choice. Stored as a choice it would freeze today's
                // size into the launch and stop following the setting.
                chosenResolution = null
                clearResolutionOverride(currentGame)
            } else {
                chosenResolution = choice
                saveResolutionOverride(currentGame, choice.id)
            }
            if (spaceGame != null) loadOptimization(profilePreference)
        }

        /**
         * Pin a frame rate rather than launching with it, same as [chooseResolution].
         * Null clears the pin -- the row's own "Auto" option -- so the game goes back to
         * whatever fps the resolution choice or the host's plan would have used.
         */
        fun chooseFrameRate(fps: Int?) {
            chosenFps = fps
            if (fps != null) {
                saveFrameRateOverride(currentGame, fps)
            } else {
                clearFrameRateOverride(currentGame)
            }
            if (spaceGame != null) loadOptimization(profilePreference)
        }

        /** Select a backend for this game only; null returns to Polaris' host setting. */
        fun chooseEncoderBackend(backend: String?) {
            val normalized = PolarisClientSettings.normalizeEncoderBackend(backend)
            if (backend != null && normalized == null) return
            val allowed = backend == null || clientSettings?.capabilities?.let { capabilities ->
                capabilities.sessionEncoderOverride && capabilities.encoders.any { option ->
                    option.available && option.value == normalized
                }
            } == true
            if (!allowed || normalized == chosenEncoderBackend) return
            chosenEncoderBackend = normalized
            if (normalized == null) {
                NovaEncoderBackendOverrides.clear(this@NovaGameDetailActivity, currentGame)
            } else {
                NovaEncoderBackendOverrides.save(
                    this@NovaGameDetailActivity,
                    currentGame,
                    normalized,
                )
            }
            settleThen { loadOptimization(profilePreference) }
        }

        fun chooseFaceButtonLayout(layout: String?) {
            val normalized = NovaFaceButtonLayoutOverrides.normalize(layout)
            if (layout != null && normalized == null) return
            if (normalized == null) {
                NovaFaceButtonLayoutOverrides.clear(this@NovaGameDetailActivity, currentGame)
            } else {
                NovaFaceButtonLayoutOverrides.save(this@NovaGameDetailActivity, currentGame, normalized)
            }
            settleThen { loadOptimization(profilePreference) }
        }

        fun selectProfilePreference(value: String) {
            if (value == profilePreference) return
            profilePreference = value
            saveProfilePreference(currentGame, value)
            refreshUiState(value)
            settleThen { loadOptimization(value) }
        }

        fun selectSteamLaunchMode(value: String) {
            val requestedMode = PolarisGame.SteamLaunchContract.normalizeMode(value)
            if (requestedMode == currentGame.steamLaunchMode) return

            // Shown immediately and reconciled when the host answers. The row is a value
            // someone is cycling through, so it cannot wait on a round-trip to redraw.
            currentGame = currentGame.copy(
                steamLaunch = currentGame.steamLaunch?.copy(mode = requestedMode)
            )
            refreshUiState()

            // A Steam change supersedes any pending read-only settle, whose eventual
            // optimizer request is recreated after this host mutation confirms.
            optimizationState = NovaGameDetailOptimizationState(preflightInFlight = true)
            preflightRequestFence.invalidate()
            preflightJob?.cancel()
            preflightJob = null
            settleJob?.cancel()
            settleJob = null
            pendingSettledWork = null

            val intentGeneration = launchViewModel.selectSteamLaunchMode(requestedMode)
            steamLaunchModeJob?.cancel()
            steamLaunchModeJob = lifecycleScope.launch {
                val resolution = awaitLatestSteamLaunchModeWrite()
                if (resolution.generation != intentGeneration) return@launch
                if (resolution.failed) {
                    NovaSnackbar.showError(
                        this@NovaGameDetailActivity,
                        getString(R.string.nova_steam_launch_mode_failed),
                    )
                }
                loadOptimization(profilePreference)
            }
        }

        /**
         * The act column, resolved. Rows appear only when the current host has a real choice
         * to offer; richer catalogs use the panel's measured scrolling fallback.
         *
         * A host without Spaces is not asked. The per-Space library lookups run together
         * rather than one after another, a Space without a library is not asked for one, and
         * the answer is refreshed whenever the window comes back.
         */
        var destinationsJob: Job? = null
        var destinationsFailed = false
        fun loadPlayDestinations() {
            if (!spacesEnabled && currentGame.space == null) return
            val queryGame = currentGame
            destinationsJob?.cancel()
            destinationsJob = lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val spaces = apiClient.getSpaces()
                        val destinations = if (spaces == null || !spaces.enabled || spaces.spaces.isEmpty()) emptyList() else coroutineScope {
                            // Steam games pair by app id; Steam Big Picture pairs with Big Picture on the other side.
                            val match = NovaPlayDestinationMatch
                            val desktop = if (spaces.desktopAllowed || queryGame.space == null) async {
                                val desktopGame = when {
                                    queryGame.space == null -> queryGame
                                    !match.needsDesktopLibrary(queryGame) -> null
                                    else -> runCatching { match.desktopTitle(queryGame, apiClient.getDesktopGames()) }.getOrNull()
                                }
                                NovaPlayDestination(match.DESKTOP_ID, getString(R.string.nova_space_desktop), desktopGame, "ready")
                            } else null
                            val perSpace = spaces.spaces.map { space -> async {
                                val current = space.id == queryGame.space?.id
                                val library = when {
                                    current || !space.libraryEnabled -> null
                                    match.needsSpaceLibrary(queryGame) -> runCatching { apiClient.getSpaceLibrary(space.id) }.getOrNull()
                                    else -> null
                                }
                                val title = if (current) queryGame else match.spaceTitle(queryGame, library)
                                // Not installed there, but that Space's Steam can be opened to install it.
                                val steam = match.spaceSteam(queryGame, library, title)
                                NovaPlayDestination(
                                    space.id, space.name, title ?: steam, space.state,
                                    libraryEnabled = current || space.libraryEnabled,
                                    canOpen = space.openable, blockedReason = space.blockedReason,
                                    viaSteam = title == null && steam != null,
                                )
                            } }
                            listOfNotNull(desktop?.await()) + perSpace.awaitAll()
                        }
                        spaces to destinations
                    }
                    environmentSnapshot = result.first
                    playDestinations = result.second
                    if (destinationsFailed) { destinationsFailed = false; environmentError = null }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (queryGame.space != null) { destinationsFailed = true; environmentError = getString(R.string.nova_space_destinations_failed) }
                }
            }
        }
        refreshPlayDestinations = { loadPlayDestinations() }

        fun choosePlayDestination(choice: NovaPlayDestination) {
            val snapshot = environmentSnapshot ?: return
            val title = choice.game ?: return
            if (choice.id == (currentGame.space?.id ?: "desktop") && environmentError == null) return
            if (environmentChanging || !snapshot.canSwitch || !choice.canOpen) return
            pendingLaunch = false; environmentChanging = true; environmentError = null
            lifecycleScope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) { apiClient.selectSpace(choice.id, snapshot.selectedId) }
                    if (selected.selectedId != choice.id) throw IllegalStateException("Environment choice changed")
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
                    // A fresh Activity discards the previous game's settings and in-flight
                    // preflight responses. Forward the launch result to the same Library.
                    val next = Intent(intent).putExtra(EXTRA_GAME, PolarisGameJson.encode(title))
                        .putExtra(EXTRA_PLAY_SETUP, true).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                    next.removeExtra(EXTRA_OPEN_SPACE); next.removeExtra(EXTRA_SPACE_SETTINGS)
                    startActivity(next)
                    finish()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // The host's sentence when it sent one; otherwise what happens next.
                    environmentError = (e as? PolarisApiRejectedException)?.rejection?.error?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.nova_space_change_failed)
                    loadPlayDestinations()
                } finally { environmentChanging = false }
            }
        }
        loadPlayDestinations()

        fun buildPlaySetupRows(): List<NovaPlaySetupRowState> {
            val rows = mutableListOf<NovaPlaySetupRowState>()
            if (playDestinations.isNotEmpty()) rows += NovaPlaySetupRowState(
                row = NovaPlaySetupRow.PLAY_IN, label = getString(R.string.nova_space_change),
                // Said above the destination cards only when there is something to say: the cards
                // and their heading already explain the choice.
                caption = environmentError
                    ?: if (environmentChanging) getString(R.string.nova_space_changing) else "",
                value = currentGame.space?.name ?: getString(R.string.nova_space_desktop),
                stripTitle = getString(R.string.nova_space_where_it_opens),
                options = playDestinations.map { choice -> NovaPlaySetupOption(
                    label = choice.name,
                    consequence = getString(NovaPlayDestinationMatch.caption(choice), choice.name),
                    current = choice.id == (currentGame.space?.id ?: "desktop"),
                    enabled = !environmentChanging && environmentSnapshot?.canSwitch == true &&
                        choice.game != null && choice.canOpen,
                    onSelect = { choosePlayDestination(choice) },
                ) },
                enabled = !environmentChanging && environmentSnapshot?.canSwitch == true,
            )
            val preferences = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
            val fpsPin = if (spaceGame == null) NovaLaunchStreamOverride.highFpsPin(profilePreference, preferences.fps) else null

            val modeOptions = buildList {
                if (uiState.headlessAllowed) {
                    add(
                        NovaPlaySetupOption(
                            label = modeBadgeLabel(PolarisGame.MODE_HEADLESS_STREAM),
                            consequence = getString(R.string.nova_play_setup_compare_private),
                            current = uiState.playMode == PolarisGame.MODE_HEADLESS_STREAM,
                            onSelect = { selectLaunchMode(PolarisGame.MODE_HEADLESS_STREAM) },
                        )
                    )
                }
                if (uiState.virtualDisplayAllowed) {
                    add(
                        NovaPlaySetupOption(
                            label = modeBadgeLabel(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY),
                            consequence = getString(R.string.nova_play_setup_compare_virtual),
                            current = uiState.playMode == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY,
                            enabled = !uiState.virtualDisplayUnavailable,
                            onSelect = { selectLaunchMode(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY) },
                        )
                    )
                }
            }
            rows += NovaPlaySetupRowState(
                row = NovaPlaySetupRow.WHERE_IT_RUNS,
                label = getString(R.string.nova_game_detail_where_it_runs),
                caption = getString(R.string.nova_play_setup_where_caption),
                value = modeBadgeLabel(uiState.playMode),
                stripTitle = getString(R.string.nova_play_setup_strip_where),
                options = modeOptions,
                enabled = modeOptions.count { it.enabled } > 1 || gameModePickerEligible(),
                overridden = uiState.overridesHostMode,
            )

            val planner = resolutionPlanner(currentGame)
            if (planner.available && planner.visibleChoices.isNotEmpty()) {
                val chosen = chosenResolution
                val recommended = planner.visibleChoices.firstOrNull { it.recommended }
                val effective = chosen ?: recommended
                val overridden = chosen != null && chosen.id != recommended?.id
                rows += NovaPlaySetupRowState(
                    row = NovaPlaySetupRow.RESOLUTION,
                    label = getString(R.string.nova_play_setup_resolution),
                    caption = if (overridden) {
                        getString(R.string.nova_play_setup_resolution_chosen)
                    } else {
                        getString(R.string.nova_play_setup_resolution_caption)
                    },
                    value = NovaDisplayResolutionPlanner.resolutionLabel(effective?.targetMode.orEmpty()),
                    stripTitle = getString(R.string.nova_play_setup_strip_resolution),
                    options = planner.visibleChoices.map { choice ->
                        NovaPlaySetupOption(
                            label = choice.title,
                            consequence = listOf(
                                NovaDisplayResolutionPlanner.resolutionLabel(choice.targetMode),
                                choice.reason,
                            )
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            current = choice.id == effective?.id,
                            onSelect = { chooseResolution(choice) },
                        )
                    },
                    overridden = overridden,
                )

                val effectiveFps = chosenFps ?: fpsPin ?: NovaLaunchStreamOverride.automaticFps(
                    optimizationState.rawOptimization,
                    preferences.fps.toInt(),
                )
                rows += NovaPlaySetupRowState(
                    row = NovaPlaySetupRow.FRAME_RATE,
                    label = getString(R.string.nova_play_setup_frame_rate),
                    caption = if (chosenFps != null) {
                        getString(R.string.nova_play_setup_frame_rate_chosen)
                    } else {
                        getString(R.string.nova_play_setup_frame_rate_caption)
                    },
                    value = getString(R.string.nova_play_setup_frame_rate_fps_format, effectiveFps),
                    stripTitle = getString(R.string.nova_play_setup_strip_frame_rate),
                    options = buildList {
                        add(
                            NovaPlaySetupOption(
                                label = getString(R.string.nova_play_setup_frame_rate_auto),
                                consequence = getString(R.string.nova_play_setup_frame_rate_auto_consequence),
                                current = chosenFps == null,
                                onSelect = { chooseFrameRate(null) },
                            )
                        )
                        // Culled to what this panel can actually present -- offering 120
                        // on a 60Hz panel would let a player lock in a launch the display
                        // cannot honor, turning a simple choice into a fail-closed error.
                        val panelAllowedFps = NovaDisplayFpsCapability.allowedFpsValues(
                            NovaDisplayFpsCapability.maxSupportedFps(windowManager.defaultDisplay)
                        )
                        NOVA_FRAME_RATE_CHOICES.filter { it in panelAllowedFps }.forEach { fps ->
                            add(
                                NovaPlaySetupOption(
                                    label = getString(R.string.nova_play_setup_frame_rate_fps_format, fps),
                                    consequence = "",
                                    current = chosenFps == fps,
                                    onSelect = { chooseFrameRate(fps) },
                                )
                            )
                        }
                    },
                    overridden = chosenFps != null,
                )
            } else if (chosenFps != null) {
                // The Frame Rate row only exists alongside the display planner above. A
                // pin saved during an earlier session with a planner could otherwise keep
                // steering launchOptimization()'s fpsOverride from here on while having no
                // visible, clearable control on this screen -- a durable lock nobody can
                // see or undo. Retire it instead of leaving it stuck.
                chooseFrameRate(null)
            }

            val encoderCatalog = clientSettings?.capabilities?.takeIf {
                it.sessionEncoderOverride
            }?.encoders.orEmpty().filter { it.available }.distinctBy { it.value }
            if (encoderCatalog.isNotEmpty() && !com.papi.nova.manager.WorkerLaunchContract.isProfileApp(currentGame.id)) {
                val selectedEncoder = selectedEncoderBackend()
                val selectedOption = encoderCatalog.firstOrNull { it.value == selectedEncoder }
                rows += NovaPlaySetupRowState(
                    row = NovaPlaySetupRow.ENCODER,
                    label = getString(R.string.nova_play_setup_encoder),
                    caption = when {
                        selectedEncoder.isBlank() -> getString(
                            R.string.nova_play_setup_encoder_host_default_caption,
                        )
                        selectedOption?.fallbackAllowed == true -> getString(
                            R.string.nova_play_setup_encoder_auto_caption,
                        )
                        else -> getString(R.string.nova_play_setup_encoder_exact_caption)
                    },
                    value = selectedOption?.displayLabel
                        ?: getString(R.string.nova_play_setup_encoder_host_default),
                    stripTitle = getString(R.string.nova_play_setup_strip_encoder),
                    options = buildList {
                        add(
                            NovaPlaySetupOption(
                                label = getString(R.string.nova_play_setup_encoder_host_default),
                                consequence = getString(
                                    R.string.nova_play_setup_encoder_host_default_consequence,
                                ),
                                current = selectedEncoder.isBlank(),
                                onSelect = { chooseEncoderBackend(null) },
                            ),
                        )
                        encoderCatalog.forEach { option ->
                            add(
                                NovaPlaySetupOption(
                                    label = option.displayLabel,
                                    consequence = option.reason.ifBlank {
                                        if (option.fallbackAllowed) {
                                            getString(R.string.nova_play_setup_encoder_auto_consequence)
                                        } else {
                                            getString(R.string.nova_play_setup_encoder_exact_consequence)
                                        }
                                    },
                                    current = option.value == selectedEncoder,
                                    onSelect = { chooseEncoderBackend(option.value) },
                                ),
                            )
                        }
                    },
                    overridden = selectedEncoder.isNotBlank(),
                )
            }

            run {
                val selectedLayout = NovaFaceButtonLayoutOverrides.load(this@NovaGameDetailActivity, currentGame)
                val switchGame = currentGame.platform.equals("switch", ignoreCase = true)
                rows += NovaPlaySetupRowState(
                    row = NovaPlaySetupRow.FACE_BUTTONS,
                    label = getString(R.string.nova_play_setup_face_buttons),
                    caption = when (selectedLayout) {
                        NovaFaceButtonLayoutOverrides.POSITIONS -> getString(R.string.nova_play_setup_face_buttons_positions_caption)
                        NovaFaceButtonLayoutOverrides.LABELS -> getString(R.string.nova_play_setup_face_buttons_labels_caption)
                        else -> if (switchGame) {
                            getString(R.string.nova_play_setup_face_buttons_switch_hint)
                        } else {
                            getString(R.string.nova_play_setup_face_buttons_app_setting_caption)
                        }
                    },
                    value = when (selectedLayout) {
                        NovaFaceButtonLayoutOverrides.POSITIONS -> getString(R.string.nova_play_setup_face_buttons_positions)
                        NovaFaceButtonLayoutOverrides.LABELS -> getString(R.string.nova_play_setup_face_buttons_labels)
                        else -> getString(R.string.nova_play_setup_face_buttons_app_setting)
                    },
                    stripTitle = getString(R.string.nova_play_setup_strip_face_buttons),
                    options = listOf(
                        NovaPlaySetupOption(
                            label = getString(R.string.nova_play_setup_face_buttons_app_setting),
                            consequence = getString(R.string.nova_play_setup_face_buttons_app_setting_consequence),
                            current = selectedLayout == null,
                            onSelect = { chooseFaceButtonLayout(null) },
                        ),
                        NovaPlaySetupOption(
                            label = getString(R.string.nova_play_setup_face_buttons_labels),
                            consequence = getString(R.string.nova_play_setup_face_buttons_labels_consequence),
                            current = selectedLayout == NovaFaceButtonLayoutOverrides.LABELS,
                            onSelect = { chooseFaceButtonLayout(NovaFaceButtonLayoutOverrides.LABELS) },
                        ),
                        NovaPlaySetupOption(
                            label = getString(R.string.nova_play_setup_face_buttons_positions),
                            consequence = getString(R.string.nova_play_setup_face_buttons_positions_consequence),
                            current = selectedLayout == NovaFaceButtonLayoutOverrides.POSITIONS,
                            onSelect = { chooseFaceButtonLayout(NovaFaceButtonLayoutOverrides.POSITIONS) },
                        ),
                    ),
                    overridden = selectedLayout != null,
                )
            }

            rows += NovaPlaySetupRowState(
                row = NovaPlaySetupRow.TUNING,
                label = getString(R.string.nova_play_setup_tuning),
                // High FPS is binding, so the caption states the explicit pin -- but only
                // when Tuning's own High FPS preference is what is actually pinning it. An
                // explicit Frame Rate row choice wins over that pin in launchOptimization()
                // (see the fpsOverride comment there), and the row above already says so;
                // Tuning claiming credit for a number the Frame Rate row is really the one
                // launching with would put a wrong number in front of the player -- not
                // just an inconsistent one. Historical Doctor guidance never participates
                // in this launch either way.
                caption = when {
                    chosenFps == null && fpsPin != null -> getString(R.string.nova_play_setup_tuning_pins, fpsPin)
                    // The row used to show only the saved ask; for the asks the host
                    // owns, the outcome is the half that was never said anywhere.
                    else -> when (
                        val outcome = novaTuningOutcome(optimizationState.rawOptimization, profilePreference)
                    ) {
                        is NovaTuningOutcome.Applied -> getString(R.string.nova_play_setup_tuning_applied)
                        is NovaTuningOutcome.Declined -> if (outcome.reason.isNotBlank()) {
                            getString(R.string.nova_play_setup_tuning_declined, outcome.reason)
                        } else {
                            getString(R.string.nova_play_setup_tuning_declined_no_reason)
                        }
                        else -> getString(R.string.nova_game_detail_profile_caption)
                    }
                },
                value = getString(AutoQualityProfilePreferences.shortLabelRes(profilePreference)),
                stripTitle = getString(R.string.nova_play_setup_strip_tuning),
                options = AutoQualityProfilePreferences.values().map { value ->
                    NovaPlaySetupOption(
                        // shortLabelRes, not labelRes: the long form is "Launch preset: X",
                        // and four cards of it ellipsize to four identical words.
                        label = getString(AutoQualityProfilePreferences.shortLabelRes(value)),
                        consequence = getString(novaProfilePreferenceConsequenceRes(value)),
                        current = value == profilePreference,
                        onSelect = { selectProfilePreference(value) },
                    )
                },
                overridden = chosenFps == null && fpsPin != null,
            )

            if (uiState.showSteamLaunchMode) {
                rows += NovaPlaySetupRowState(
                    row = NovaPlaySetupRow.STEAM_LAUNCH,
                    label = getString(R.string.nova_steam_launch_detail_label),
                    caption = steamLaunchCaption(uiState),
                    value = steamLaunchModeLabel(uiState.steamLaunchMode),
                    stripTitle = getString(R.string.nova_play_setup_strip_steam),
                    options = listOf("direct", "big-picture").map { mode ->
                        val normalized = PolarisGame.SteamLaunchContract.normalizeMode(mode)
                        NovaPlaySetupOption(
                            label = steamLaunchModeLabel(normalized),
                            consequence = getString(novaSteamLaunchConsequenceRes(normalized)),
                            current = normalized == uiState.steamLaunchMode,
                            onSelect = { selectSteamLaunchMode(normalized) },
                        )
                    },
                )
            }

            return if (spaceGame == null) rows else rows.filter {
                it.row in setOf(NovaPlaySetupRow.PLAY_IN, NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.FRAME_RATE)
            }
        }

        /**
         * A press moves the row to its next value.
         *
         * Read off the same option list the strip draws, so the order someone sees is the
         * order they get. Disabled options are stepped over rather than landed on, which is
         * what made the blocked virtual-display case reachable-but-inert before.
         */
        fun advancePlaySetupRow(row: NovaPlaySetupRow) {
            explainedRow = row
            val options = buildPlaySetupRows().firstOrNull { it.row == row }?.options.orEmpty()
            val selectable = options.filter { it.enabled && it.onSelect != null }
            if (selectable.size < 2) return
            val currentIndex = selectable.indexOfFirst { it.current }
            val next = selectable[(currentIndex + 1).mod(selectable.size)]
            next.onSelect?.invoke()
        }

        fun hostPolarisProfileValue(sync: NovaPolarisSyncUiState): String =
            novaPlaySetupHostProfileValue(
                sync = sync,
                settings = hostSyncEngine?.currentSettings ?: clientSettings,
                getString = { resId -> getString(resId) },
            )

        fun hostActions() = NovaPlaySetupHostActions(
            onSelectMode = { hostSyncEngine?.setStreamDisplayMode(it) },
            onMatchNova = { hostSyncEngine?.matchNova() },
            onSendNova = { hostSyncEngine?.sendNova() },
            onUsePolaris = { hostSyncEngine?.usePolarisProfile() },
            onClearProfile = { hostSyncEngine?.clearProfile() },
            onKeepInStep = { hostSyncEngine?.setAutoSync(it) },
        )

        fun buildHostPlaySetupRows(): List<NovaPlaySetupRowState> {
            val sync = hostScopeUiState()
            return buildNovaPlaySetupHostRows(
                sync = sync,
                polarisProfileValue = hostPolarisProfileValue(sync),
                getString = { resId -> getString(resId) },
                actions = hostActions(),
            )
        }

        fun advanceHostPlaySetupRow(row: NovaPlaySetupRow) {
            explainedRow = row
            val sync = hostScopeUiState()
            advanceNovaPlaySetupHostRow(
                row = row,
                rows = buildNovaPlaySetupHostRows(
                    sync = sync,
                    polarisProfileValue = hostPolarisProfileValue(sync),
                    getString = { resId -> getString(resId) },
                    actions = hostActions(),
                ),
                sync = sync,
                actions = hostActions(),
            )
        }

        fun hostPlaySetupPlan(sync: NovaPolarisSyncUiState): NovaPlaySetupPlan =
            novaPlaySetupHostPlan(
                sync = sync,
                polarisProfileValue = hostPolarisProfileValue(sync),
                getString = { resId -> getString(resId) },
            )

        fun resetProfile() {
            resetWorking = true
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    apiClient.clearOptimizerProfile(deviceName, currentGame.name)
                }
                loadOptimization(profilePreference)
                resetWorking = false
            }
        }

        setContentView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
            NovaComposeTheme {
                val launchPreferences = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
                val launchPreview = optimizationState.withLaunchProfileSummary(
                    launchOptimization(),
                    clientAskedFps = (effectiveFpsPin(chosenFps, profilePreference, launchPreferences.fps)
                        ?: launchPreferences.fps.toInt()).toDouble(),
                    clientAskedHdr = launchPreferences.enableHdr,
                    spaceName = launchSpaceName(),
                )
                if (spaceGame != null && com.papi.nova.manager.WorkerLaunchContract.isLegacyProfileApp(currentGame.id)) {
                    // The contract is parsed once per change, not on every recomposition.
                    val constraint = remember(optimizationState.rawOptimization, chosenResolution, chosenFps) { spaceConstraint() }
                    // Why the Space cannot be opened, in the host's words; null while it can.
                    val spaceBlock = environmentSnapshot?.let { snapshot ->
                        val target = currentGame.space?.id
                        when {
                            target == null -> null
                            snapshot.selectedId != target -> R.string.nova_space_status_changed
                            else -> snapshot.selected?.takeUnless { it.state == "in_use" }?.let { NovaSpacesCopy.openBlockedReason(it) }
                        }
                    }
                    val blockedSpace = environmentSnapshot?.takeIf { it.selectedId == currentGame.space?.id }?.selected
                    NovaSpaceContent(
                        game = currentGame,
                        hostName = serverName,
                        activeSession = activeSession,
                        onOpen = { attemptLaunch() },
                        onSettings = { pendingLaunch = false; destination = NovaGameDetailDestination.PLAY_SETUP },
                        onBack = { if (!dismissActiveDetailDestination()) finish() },
                        showSettings = destination == NovaGameDetailDestination.PLAY_SETUP,
                        settingsRows = buildPlaySetupRows(),
                        primaryEnabled = if (environmentError != null) !environmentChanging
                            else uiState.playEnabled && !pendingLaunch && !spaceLaunchDelivered && constraint == null &&
                                !environmentChanging && playEnvironmentReady(),
                        primaryLabel = getString(when {
                            environmentChanging -> R.string.nova_space_changing
                            environmentError != null -> R.string.nova_space_retry_check
                            !playEnvironmentReady() -> blockedSpace?.let { NovaSpacesCopy.playBlockedLabel(it.state, it.blockedReason) }
                                ?: R.string.nova_space_checking
                            constraint != null -> R.string.nova_space_host_settings_required
                            pendingLaunch -> R.string.nova_space_checking
                            optimizationState.preflightFailed -> R.string.nova_space_retry
                            optimizationState.reviewRequired && !reviewExpanded -> R.string.nova_space_review
                            else -> R.string.nova_space_open
                        }),
                        message = when {
                            environmentError != null -> environmentError
                            spaceBlock != null -> getString(spaceBlock)
                            constraint != null -> getString(R.string.nova_space_host_constraint,
                                constraint.width, constraint.height, constraint.fps)
                            reviewExpanded -> launchPreview.profileSummary?.noticeDetail
                                ?.takeIf { it.isNotBlank() } ?: getString(R.string.nova_library_preflight_review_message, optimizationState.reviewReason)
                            optimizationState.preflightFailed -> getString(R.string.nova_game_detail_launch_preflight_unavailable)
                            !uiState.playEnabled -> uiState.hostStreamDisplayModeUnavailableReason.takeIf { it.isNotBlank() }
                            else -> null
                        },
                    )
                } else NovaGameDetailContent(
                    // With a failed Space check, Play is the retry, so it stays enabled.
                    uiState = uiState.copy(playEnabled = !environmentChanging &&
                        (environmentError != null || (uiState.playEnabled && playEnvironmentReady()))),
                    launchIntro = environmentError ?: buildLaunchIntro(uiState),
                    recommendedBadge = getString(
                        R.string.nova_library_launch_recommended_mode_badge,
                        modeBadgeLabel(uiState.recommendedMode)
                    ),
                    lastPlayedText = lastPlayedText(currentGame),
                    profilePreferenceLabel = currentGame.space?.let { getString(R.string.nova_space_profile_label_format, it.name) }
                        ?: getString(AutoQualityProfilePreferences.labelRes(profilePreference)),
                    resetProfileLabel = getString(
                        if (resetWorking) {
                            R.string.nova_library_reset_game_profile_working
                        } else {
                            R.string.nova_library_reset_game_profile
                        }
                    ),
                    resetProfileWorking = resetWorking,
                    mangoHudEnabled = mangoHudEnabled,
                    mangoHudStatusLabel = getString(R.string.nova_mangohud_enabled_status),
                    mangoHudStatusCaption = getString(R.string.nova_mangohud_novahud_caption),
                    mangoHudWarning = uiState.mangoHudRisk != NovaGameDetailUiState.MangoHudRisk.NONE,
                    steamLaunchLabel = getString(R.string.nova_steam_launch_detail_label),
                    steamLaunchModeLabel = steamLaunchModeLabel(uiState.steamLaunchMode),
                    steamLaunchCaption = steamLaunchCaption(uiState),
                    optimizationState = launchPreview,
                    playSetupRows = buildPlaySetupRows(),
                    explainedPlaySetupRow = explainedRow,
                    playSetupScope = playSetupScope,
                    onPlaySetupScopeSelected = { selectPlaySetupScope(it) },
                    hostPlaySetupRows = if (playSetupScope == NovaPlaySetupScope.EVERY_GAME) {
                        buildHostPlaySetupRows()
                    } else {
                        emptyList()
                    },
                    hostPlaySetupPlan = if (playSetupScope == NovaPlaySetupScope.EVERY_GAME) {
                        hostPlaySetupPlan(hostScopeUiState())
                    } else {
                        null
                    },
                    modePicker = if (modePickerOpen && steamDecision == null) {
                        if (playSetupScope == NovaPlaySetupScope.EVERY_GAME) {
                            buildHostModePickerState(
                                modes = hostScopeUiState().modes,
                                title = getString(R.string.nova_play_setup_host_default_display),
                            )
                        } else {
                            buildGameModePickerState(
                                modes = hostScopeUiState().modes,
                                allowedModes = currentGame.launchMode?.allowedModes.orEmpty(),
                                playMode = uiState.playMode,
                                hasExplicitOverride = uiState.hasExplicitOverride,
                                aiRecommendedMode = optimizationState.aiRecommendedMode,
                                title = getString(R.string.nova_game_detail_where_it_runs),
                                hostDefaultLabel = getString(
                                    R.string.nova_play_setup_host_default_entry_detail,
                                    uiState.hostStreamDisplayModeLabel.ifBlank {
                                        getString(R.string.nova_polaris_sync_unset)
                                    },
                                ),
                                hostDefaultOnlyDetail = getString(R.string.nova_play_setup_mode_host_default_only),
                                plainModeDetails = playSetupModeDetails(),
                            )
                        }
                    } else {
                        null
                    },
                    onPickMode = { mode ->
                        if (playSetupScope == NovaPlaySetupScope.EVERY_GAME) {
                            modePickerOpen = false
                            hostSyncEngine?.setStreamDisplayMode(mode)
                        } else {
                            pickPlayMode(mode)
                        }
                    },
                    onPickHostDefault = { pickHostDefault() },
                    onConfigureHostMode = { finishWithManageServerRequest() },
                    playLabel = if (environmentChanging) {
                        getString(R.string.nova_space_changing)
                    } else if (environmentError != null) {
                        getString(R.string.nova_space_retry_check)
                    } else if (!playEnvironmentReady()) {
                        val blocked = environmentSnapshot?.takeIf { it.selectedId == currentGame.space?.id }?.selected
                        getString(blocked?.let { NovaSpacesCopy.playBlockedLabel(it.state, it.blockedReason) } ?: R.string.nova_space_checking)
                    } else if (spaceConstraint() != null) {
                        getString(R.string.nova_space_host_settings_required)
                    } else if (pendingLaunch) {
                        // The press landed and is being held, so say so. A button that
                        // looks untouched for the length of an HTTP round-trip reads as
                        // one that did not register.
                        getString(R.string.nova_game_detail_launch_checking_host)
                    } else if (optimizationState.preflightFailed) {
                        getString(R.string.nova_game_detail_launch_retry_host_check)
                    } else if (optimizationState.reviewRequired) {
                        getString(R.string.nova_library_review_and_launch)
                    } else if (currentGame.space != null) {
                        if (com.papi.nova.manager.WorkerLaunchContract.isLauncherEntry(currentGame.space?.target)) {
                            getString(R.string.nova_space_open_launcher, currentGame.name)
                        } else {
                            getString(R.string.nova_space_play)
                        }
                    } else {
                        // Describe the same composed choices attemptLaunch() will send.
                        launchPreview.profileSummary
                            ?.primaryLaunchLabel
                            ?.takeIf { it.isNotBlank() }
                            ?: primaryPlayLabel(uiState)
                    },
                    launchModeTitle = getString(R.string.nova_library_launch_mode_title),
                    headlessModeLabel = modeBadgeLabel(PolarisGame.MODE_HEADLESS_STREAM),
                    virtualDisplayModeLabel = modeBadgeLabel(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY),
                    coverContentDescription = getString(R.string.nova_a11y_game_cover),
                    onPrimaryLaunch = { attemptLaunch() },
                    onExplainPlaySetupRow = { row -> explainedRow = row },
                    onAdvancePlaySetupRow = { row ->
                        if (playSetupScope == NovaPlaySetupScope.EVERY_GAME) {
                            if (row == NovaPlaySetupRow.HOST_DEFAULT_DISPLAY &&
                                novaModePickerEligible(hostScopeUiState().modes.size)
                            ) {
                                explainedRow = row
                                modePickerOpen = true
                            } else {
                                advanceHostPlaySetupRow(row)
                            }
                        } else {
                            if (row == NovaPlaySetupRow.WHERE_IT_RUNS && gameModePickerEligible()) {
                                explainedRow = row
                                modePickerOpen = true
                            } else {
                                advancePlaySetupRow(row)
                            }
                        }
                    },
                    destination = destination,
                    steamDecision = steamDecision,
                    reviewExpanded = reviewExpanded,
                    apiClient = apiClient,
                    sourceLabel = currentGame.sourceLabel,
                    onDestination = { next -> destination = next },
                    onDismissDestination = { dismissActiveDetailDestination() },
                    activeSession = activeSession,
                    onResumeSession = { finishWithSessionRequest(RESULT_SESSION_RESUME) },
                    onEndSession = { finishWithSessionRequest(RESULT_SESSION_END) },
                    onSteamChoice = { choice ->
                        when (choice) {
                            NovaSteamLaunchChoice.PRIVATE_STREAM ->
                                launchConfirmed(mirrorDesktop = false, forcePrivateAfterSteamClose = false)
                            NovaSteamLaunchChoice.MIRROR_DESKTOP ->
                                launchConfirmed(mirrorDesktop = true, forcePrivateAfterSteamClose = false)
                            NovaSteamLaunchChoice.CLOSE_STEAM_THEN_PRIVATE ->
                                launchConfirmed(mirrorDesktop = false, forcePrivateAfterSteamClose = true)
                        }
                    },
                    onRetryHighFps = { selectHighFpsPreset() },
                    onResetProfile = {
                        resetWorking = true
                        lifecycleScope.launch {
                            val cleared = withContext(Dispatchers.IO) {
                                apiClient.clearOptimizerProfile(deviceName, currentGame.name)
                            }
                            val sheetContext = this@NovaGameDetailActivity
                            if (cleared == true) {
                                optimizationState = NovaGameDetailOptimizationState()
                            }
                            val message = when (cleared) {
                                true -> R.string.nova_library_reset_game_profile_cleared
                                false -> R.string.nova_library_reset_game_profile_empty
                                null -> R.string.nova_library_reset_game_profile_failed
                            }
                            Toast.makeText(sheetContext, message, Toast.LENGTH_SHORT).show()
                            resetWorking = false
                        }
                    },
                    shortcutPinState = shortcutPinState,
                    shortcutPinRequestPending = shortcutPinRequestPending,
                    onPinShortcut = pinShortcut@ {
                        val hostUuid = serverUuid
                        if (hostUuid.isNullOrEmpty()) {
                            Toast.makeText(
                                this@NovaGameDetailActivity,
                                R.string.nova_library_pin_shortcut_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            if (pinShortcutJob?.isActive == true) return@pinShortcut
                            val pinnedGame = currentGame
                            pinShortcutJob = lifecycleScope.launch {
                                try {
                                    val iconBits = withContext(Dispatchers.IO) {
                                        apiClient.loadShortcutIcon(pinnedGame)
                                    }
                                    val pinned = shortcutHelper.createPinnedGameShortcut(
                                        hostUuid = hostUuid,
                                        hostName = serverName,
                                        appUuid = pinnedGame.id,
                                        appId = pinnedGame.appId,
                                        appName = pinnedGame.name,
                                        hdrSupported = pinnedGame.hdrSupported,
                                        iconBits = iconBits,
                                    )
                                    val messageRes = if (pinned) {
                                        awaitShortcutPinConfirmation()
                                        R.string.nova_library_pin_shortcut_success
                                    } else {
                                        R.string.nova_library_pin_shortcut_unsupported
                                    }
                                    Toast.makeText(
                                        this@NovaGameDetailActivity,
                                        messageRes,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } finally {
                                    pinShortcutJob = null
                                }
                            }
                        }
                    },
                    artworkState = artworkState,
                    onRefreshArtwork = {
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.MutationLoading)
                        this@NovaGameDetailActivity.onRefreshArtwork?.invoke(currentGame) mutationResult@{ result ->
                            if (!canPublishArtworkMutationUi()) return@mutationResult
                            when (result) {
                                is NovaArtworkMutationResult.Committed -> {
                                    val manifest = result.game.artwork ?: return@mutationResult
                                    acceptArtwork(manifest)
                                }
                                NovaArtworkMutationResult.Rejected,
                                NovaArtworkMutationResult.Failed -> {
                                    artworkState = artworkState.reduce(
                                        NovaArtworkStudioAction.Failed(
                                            getString(R.string.nova_artwork_refresh_failed),
                                        ),
                                    )
                                }
                            }
                        } ?: run {
                            artworkState = artworkState.reduce(
                                NovaArtworkStudioAction.Failed(
                                    getString(R.string.nova_artwork_refresh_failed),
                                ),
                            )
                        }
                    },
                    onSearchArtwork = { query ->
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.SearchLoading)
                        lifecycleScope.launch {
                            try {
                                val candidates = withContext(Dispatchers.IO) {
                                    apiClient.searchArtworkCandidates(currentGame.id, query)
                                }
                                artworkState = artworkState.reduce(
                                    NovaArtworkStudioAction.SearchLoaded(
                                        candidates,
                                        if (candidates.isEmpty()) getString(R.string.nova_artwork_no_matches) else "",
                                    ),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: com.papi.nova.api.PolarisArtworkSearchUnavailableException) {
                                artworkState = artworkState.reduce(
                                    NovaArtworkStudioAction.Failed(getString(NovaArtworkSearchFailure.messageRes(e.code))),
                                )
                            } catch (_: Exception) {
                                artworkState = artworkState.reduce(
                                    NovaArtworkStudioAction.Failed(getString(R.string.nova_artwork_search_failed)),
                                )
                            }
                        }
                    },
                    onIdentitySelected = { candidate ->
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.IdentitySelected(candidate))
                        loadArtworkChoices(candidate, NovaArtworkKinds.POSTER)
                    },
                    onIdentityChange = {
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.IdentityChangeRequested)
                    },
                    onKindSelected = { kind ->
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.KindSelected(kind))
                        artworkState.selectedCandidate?.let { loadArtworkChoices(it, kind) }
                    },
                    onChoiceSelected = { choice ->
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.ChoiceSelected(choice))
                    },
                    onStudioAction = { action ->
                        artworkState = artworkState.reduce(action)
                    },
                    onApplyArtwork = { candidate, selections ->
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.MutationLoading)
                        this@NovaGameDetailActivity.onApplyArtwork?.invoke(
                            currentGame,
                            candidate,
                            selections,
                        ) mutationResult@{ result ->
                            if (!canPublishArtworkMutationUi()) return@mutationResult
                            when (result) {
                                is NovaArtworkMutationResult.Committed -> {
                                    val manifest = result.game.artwork ?: return@mutationResult
                                    acceptArtwork(manifest)
                                }
                                NovaArtworkMutationResult.Rejected,
                                NovaArtworkMutationResult.Failed -> {
                                    artworkState = artworkState.reduce(
                                        NovaArtworkStudioAction.ApplyFailed(
                                            getString(R.string.nova_artwork_apply_failed),
                                        ),
                                    )
                                }
                            }
                        } ?: run {
                            artworkState = artworkState.reduce(
                                NovaArtworkStudioAction.ApplyFailed(
                                    getString(R.string.nova_artwork_apply_failed),
                                ),
                            )
                        }
                    },
                    onClearArtwork = {
                        artworkState = artworkState.reduce(NovaArtworkStudioAction.MutationLoading)
                        this@NovaGameDetailActivity.onClearArtwork?.invoke(currentGame) mutationResult@{ result ->
                            if (!canPublishArtworkMutationUi()) return@mutationResult
                            when (result) {
                                is NovaArtworkMutationResult.Committed -> {
                                    val manifest = result.game.artwork ?: return@mutationResult
                                    acceptArtwork(manifest)
                                }
                                NovaArtworkMutationResult.Rejected,
                                NovaArtworkMutationResult.Failed -> {
                                    artworkState = artworkState.reduce(
                                        NovaArtworkStudioAction.Failed(
                                            getString(R.string.nova_artwork_clear_failed),
                                        ),
                                    )
                                }
                            }
                        } ?: run {
                            artworkState = artworkState.reduce(
                                NovaArtworkStudioAction.Failed(
                                    getString(R.string.nova_artwork_clear_failed),
                                ),
                            )
                        }
                    },
                    onLogoTransform = { scale, x, y ->
                        artworkState = artworkState.copy(logoScale = scale, logoX = x, logoY = y)
                        saveArtworkTransform(currentGame.id, scale, x, y)
                    },
                    candidatePreviewLoader = apiClient::loadArtworkCandidatePreviewInto,
                    choicePreviewLoader = apiClient::loadArtworkChoicePreviewInto,
                    currentArtworkPresentationKey = { kind ->
                        PolarisApiClient.artworkPresentationKey(currentGame, kind)
                    },
                    currentArtworkLoader = { imageView, kind ->
                        apiClient.loadArtworkInto(imageView, currentGame, kind)
                    },


                    heroAvailable = currentGame.heroArtwork?.cached == true,
                    heroPresentationKey = PolarisApiClient.artworkPresentationKey(currentGame, PolarisGame.ARTWORK_KIND_HERO),
                    heroLoader = { imageView -> apiClient.loadArtworkInto(imageView, currentGame, PolarisGame.ARTWORK_KIND_HERO) },
                    heroContentDescription = getString(R.string.nova_artwork_hero_content_description, currentGame.name),
                    logoAvailable = currentGame.logoArtwork?.cached == true,
                    logoPresentationKey = PolarisApiClient.artworkPresentationKey(currentGame, PolarisGame.ARTWORK_KIND_LOGO),
                    logoLoader = { imageView -> apiClient.loadArtworkInto(imageView, currentGame, PolarisGame.ARTWORK_KIND_LOGO) },
                    logoContentDescription = getString(R.string.nova_artwork_logo_content_description, currentGame.name),
                    iconAvailable = currentGame.iconArtwork?.cached == true,
                    iconPresentationKey = PolarisApiClient.artworkPresentationKey(currentGame, PolarisGame.ARTWORK_KIND_ICON),
                    iconLoader = { imageView -> apiClient.loadArtworkInto(imageView, currentGame, PolarisGame.ARTWORK_KIND_ICON) },
                    iconContentDescription = getString(R.string.nova_artwork_icon_content_description, currentGame.name),
                    coverLoader = { imageView ->
                        apiClient.loadCoverInto(imageView, currentGame)
                    }
                        )
                    }
                }
            }
        )

        pendingLaunch = directSpaceOpen
        directSpaceOpen = false
        loadOptimization(profilePreference)

    }

    private fun buildUiState(game: PolarisGame, profilePreference: String): NovaGameDetailUiState {
        return NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = defaultToVirtualDisplay,
            clientSettings = clientSettings,
            profilePreference = profilePreference,
            launchModeOverride = NovaLaunchModeOverrides.loadAvailable(
                this@NovaGameDetailActivity,
                game,
                clientSettings,
            ),
        )
    }

    private fun loadProfilePreference(game: PolarisGame): String {
        return AutoQualityProfilePreferences.load(this@NovaGameDetailActivity, game.id, game.name)
    }

    private fun saveProfilePreference(game: PolarisGame, preference: String) {
        AutoQualityProfilePreferences.save(this@NovaGameDetailActivity, game.id, game.name, preference)
    }

    /** Resolves a saved resolution-choice id back against this open's planner, if any. */
    private fun loadResolutionOverride(game: PolarisGame): NovaDisplayResolutionChoice? =
        resolveSavedResolutionChoice(
            savedId = NovaResolutionOverrides.load(this@NovaGameDetailActivity, game),
            visibleChoices = resolutionPlanner(game).visibleChoices,
        )

    private fun saveResolutionOverride(game: PolarisGame, choiceId: String) {
        NovaResolutionOverrides.save(this@NovaGameDetailActivity, game, choiceId)
    }

    /** Drop the durable choice, not just the in-memory state, so it does not return on reopen. */
    private fun clearResolutionOverride(game: PolarisGame) {
        NovaResolutionOverrides.clear(this@NovaGameDetailActivity, game)
    }

    /**
     * A saved pin from a previous open, coerced down to what this panel can present.
     *
     * The pin can outlive the panel it was chosen on -- a different physical display, or
     * the same one after a refresh-rate change. An impossible value here would not just
     * look wrong: launchOptimization() feeds it straight into fpsOverride, so it would
     * fail the launch outright rather than merely being an odd choice on screen.
     */
    private fun loadFrameRateOverride(game: PolarisGame): Int? {
        val saved = NovaFrameRateOverrides.load(this@NovaGameDetailActivity, game) ?: return null
        val maxSupportedFps = NovaDisplayFpsCapability.maxSupportedFps(windowManager.defaultDisplay)
        val coerced = NovaDisplayFpsCapability.coerce(saved, maxSupportedFps)
        if (coerced != saved) {
            NovaFrameRateOverrides.save(this@NovaGameDetailActivity, game, coerced)
        }
        return coerced
    }

    private fun saveFrameRateOverride(game: PolarisGame, fps: Int) {
        NovaFrameRateOverrides.save(this@NovaGameDetailActivity, game, fps)
    }

    private fun clearFrameRateOverride(game: PolarisGame) {
        NovaFrameRateOverrides.clear(this@NovaGameDetailActivity, game)
    }

    private fun loadArtworkState(game: PolarisGame): NovaArtworkStudioState {
        val defaults = NovaArtworkStudioState.from(game)
        val prefs = this@NovaGameDetailActivity.getSharedPreferences("nova_artwork", 0)
        val key = "logo_${game.id}_"
        return defaults.copy(
            logoScale = prefs.getFloat("${key}scale", defaults.logoScale).coerceIn(0.25f, 4f),
            logoX = prefs.getFloat("${key}x", defaults.logoX).coerceIn(0f, 1f),
            logoY = prefs.getFloat("${key}y", defaults.logoY).coerceIn(0f, 1f),
        )
    }

    private fun saveArtworkTransform(gameId: String, scale: Float, x: Float, y: Float) {
        getSharedPreferences("nova_artwork", 0).edit {
            putFloat("logo_${gameId}_scale", scale.coerceIn(0.25f, 4f))
            putFloat("logo_${gameId}_x", x.coerceIn(0f, 1f))
            putFloat("logo_${gameId}_y", y.coerceIn(0f, 1f))
        }
    }

    private fun logPreflightOptimization(
        label: String,
        opt: JSONObject?,
        preference: String
    ) {
        if (opt == null) {
            LimeLog.warning("Nova: $label returned no profile for preference=$preference")
            return
        }

        val profileState = opt.optJSONObject("profile_state")
        val effective = opt.optJSONObject("effective_profile")
        val selectedFps = opt.optDouble(
            "effective_target_fps",
            profileState
                ?.optJSONObject("current_profile")
                ?.optDouble("target_fps", 0.0)
                ?: 0.0
        )
        LimeLog.info(
            "Nova: $label loaded source=${opt.optString("source", "unknown")} " +
                "cache=${opt.optString("cache_status", "unknown")} " +
                "state=${profileState?.optString("state", "none") ?: "none"} " +
                "effective=${effective?.optString("display_mode", "") ?: ""} " +
                "fps=$selectedFps preference=$preference " +
                "applied=${opt.optBoolean("preference_applied", false)} " +
                "trial=${opt.optBoolean("trial_profile", false)}"
        )
    }

    /**
     * The host's resolution plan for this game, or an unavailable one.
     *
     * This used to be showLaunchOptions, which turned the same choices into items that
     * launched the stream the moment one was picked -- and, on a host with no planner,
     * into a second copy of the launch-mode choice with no explanatory text on it at all.
     * That copy is what "More Launch Settings" showed, and why pressing it looked like
     * nothing happened. The row is a setting now, and a host without a planner has no
     * resolution to offer, so it draws no row rather than an echo of the one above.
     */
    private fun resolutionPlanner(game: PolarisGame): NovaDisplayResolutionPlanner {
        if (NovaSpaceUiState.isSpace(game)) {
            val preferences = PreferenceConfiguration.readPreferences(this)
            return NovaSpaceUiState.resolutionPlanner(preferences.width, preferences.height, preferences.fps)
        }
        val fallbackMode = clientSettings?.desired?.displayMode
            ?.takeIf { it.isNotBlank() }
            ?: clientSettings?.effective?.displayMode
            ?: ""
        // Planned from what this device is set to stream at, which is what a launch uses with
        // nothing chosen, so the row and the launch cannot disagree.
        val preferences = PreferenceConfiguration.readPreferences(this)
        return NovaDisplayResolutionPlanner.from(
            contract = game.displayPlanner,
            fallbackMode = fallbackMode,
            includeAdvanced = true,
            device = NovaDisplayResolutionPlanner.DeviceMode(
                preferences.width, preferences.height, preferences.fps.toInt(),
            )
        )
    }

    private fun optionLabel(mode: String, recommendedMode: String): String {
        val label = modeLabel(mode)
        return if (mode == recommendedMode) {
            getString(R.string.nova_library_launch_recommended_format, label)
        } else {
            label
        }
    }

    private fun syncLaunchPreflightSettings(
        context: Context,
        apiClient: PolarisApiClient,
        usesVirtualDisplay: Boolean,
        clientSettings: PolarisClientSettings?,
        resolvedMode: String = ""
    ): PolarisClientSettings? {
        val preferences = PreferenceConfiguration.readPreferences(context)
        return NovaLaunchPreflight.push(
            apiClient = apiClient,
            clientSettings = clientSettings,
            usesVirtualDisplay = usesVirtualDisplay,
            resolvedMode = resolvedMode,
            width = preferences.width,
            height = preferences.height,
            fps = preferences.fps,
            bitrateKbps = preferences.bitrate
        )
    }



    private fun expandBottomSheet(bottomSheetDialog: BottomSheetDialog?, contentView: View) {
        val sheet = bottomSheetDialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog, contentView)
        contentView.post {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val maxHeightRatio = if (isLandscape) 0.96f else 0.90f
            val maxHeight = (resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
            val contentHeight = contentView.measuredHeight.takeIf { it > 0 } ?: return@post
            val desiredHeight = contentHeight.coerceAtMost(maxHeight)
            val displayWidth = resources.displayMetrics.widthPixels
            val density = resources.displayMetrics.density
            val desiredWidth = if (isLandscape) {
                val minWidth = (720 * density).toInt()
                val maxWidth = (1260 * density).toInt()
                (displayWidth * 0.7f).toInt().coerceIn(minWidth, maxWidth)
            } else {
                displayWidth
            }
            val horizontalMargin = if (isLandscape) {
                ((displayWidth - desiredWidth) / 2).coerceAtLeast((18 * density).toInt())
            } else {
                0
            }

            contentView.layoutParams = contentView.layoutParams.apply {
                height = if (contentHeight > maxHeight) desiredHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            sheet.layoutParams = sheet.layoutParams.apply {
                width = if (isLandscape) displayWidth - (horizontalMargin * 2) else ViewGroup.LayoutParams.MATCH_PARENT
                height = desiredHeight
            }
            (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = horizontalMargin
                lp.marginEnd = horizontalMargin
                sheet.layoutParams = lp
            }
            sheet.minimumHeight = 0
            sheet.requestLayout()

            val behavior = BottomSheetBehavior.from(sheet)
            behavior.isFitToContents = true
            behavior.isDraggable = false
            behavior.skipCollapsed = true
            behavior.peekHeight = desiredHeight
            behavior.state = BottomSheetBehavior.STATE_EXPANDED

            when (contentView) {
                is NestedScrollView -> contentView.post { contentView.scrollTo(0, 0) }
                is ScrollView -> contentView.post { contentView.scrollTo(0, 0) }
            }
        }
    }

    private fun modeLabel(mode: String): String {
        return when (PolarisGame.normalizeLaunchMode(mode)) {
            PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> getString(R.string.nova_library_launch_virtual_display)
            PolarisGame.MODE_DESKTOP_DISPLAY -> getString(R.string.nova_library_launch_desktop_display)
            PolarisGame.MODE_DESKTOP_TAKEOVER -> getString(R.string.nova_library_launch_desktop_takeover)
            PolarisGame.MODE_WINDOWED_STREAM -> getString(R.string.nova_library_launch_gpu_native_test)
            PolarisGame.MODE_GAMESCOPE_STREAM -> getString(R.string.nova_library_launch_gamescope)
            PolarisGame.MODE_HEADLESS_DONGLE -> getString(R.string.nova_library_launch_dongle)
            else -> getString(R.string.nova_library_launch_headless)
        }
    }

    private fun modeBadgeLabel(mode: String): String {
        return when (PolarisGame.normalizeLaunchMode(mode)) {
            PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> getString(R.string.nova_library_launch_virtual_short)
            PolarisGame.MODE_DESKTOP_DISPLAY -> getString(R.string.nova_library_launch_desktop_display)
            PolarisGame.MODE_DESKTOP_TAKEOVER -> getString(R.string.nova_library_launch_desktop_takeover)
            PolarisGame.MODE_WINDOWED_STREAM -> getString(R.string.nova_library_launch_gpu_native_test)
            PolarisGame.MODE_GAMESCOPE_STREAM -> getString(R.string.nova_library_launch_gamescope)
            PolarisGame.MODE_HEADLESS_DONGLE -> getString(R.string.nova_library_launch_dongle)
            else -> getString(R.string.nova_library_launch_headless)
        }
    }

    private fun primaryPlayLabel(uiState: NovaGameDetailUiState): String {
        return if (uiState.playEnabled) {
            getString(R.string.nova_library_play_mode, modeBadgeLabel(uiState.playMode))
        } else {
            getString(R.string.nova_library_play_unavailable)
        }
    }

    private fun steamLaunchModeLabel(mode: String): String {
        return when (PolarisGame.SteamLaunchContract.normalizeMode(mode)) {
            "big-picture" -> getString(R.string.nova_steam_launch_big_picture)
            else -> getString(R.string.nova_steam_launch_direct)
        }
    }

    private fun steamLaunchCaption(uiState: NovaGameDetailUiState): String {
        return if (uiState.steamLaunchWarning) {
            getString(R.string.nova_steam_launch_caption_big_picture)
        } else {
            getString(R.string.nova_steam_launch_caption_direct)
        }
    }

    /** The Space this game opens in, by name; blank for a Desktop game. */
    private fun launchSpaceName(): String =
        spaceGame?.let { game -> game.space?.name?.takeIf { it.isNotBlank() } ?: game.name }.orEmpty()

    /** One plain sentence per place a game can run, shared by the picker and the plan. */
    private fun playSetupModeDetails(): Map<String, String> = mapOf(
        PolarisClientSettings.MODE_HEADLESS_STREAM to
            getString(R.string.nova_play_setup_mode_private_detail),
        PolarisClientSettings.MODE_GPU_NATIVE_TEST to
            getString(R.string.nova_play_setup_mode_gpu_detail),
        PolarisClientSettings.MODE_GAMESCOPE_STREAM to
            getString(R.string.nova_play_setup_mode_gamescope_detail),
        PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY to
            getString(R.string.nova_play_setup_mode_virtual_detail),
        PolarisClientSettings.MODE_HEADLESS_DONGLE to
            getString(R.string.nova_play_setup_mode_dongle_detail),
        PolarisClientSettings.MODE_DESKTOP_DISPLAY to
            getString(R.string.nova_play_setup_mode_mirror_detail),
        PolarisClientSettings.MODE_DESKTOP_TAKEOVER to
            getString(R.string.nova_play_setup_mode_takeover_detail),
    )

    private fun buildLaunchIntro(uiState: NovaGameDetailUiState): String {
        // A Space game runs in its Space, with that Space's Steam sign-in and saves, whatever the
        // host's default mode is. Said once and plainly: the host default is not what this launch
        // uses, so calling it not ready made every Space launch read as a failure.
        if (uiState.runsInSpace) {
            return getString(R.string.nova_space_launch_intro, launchSpaceName())
        }
        val parts = mutableListOf<String>()
        if (uiState.hostStreamDisplayMode in setOf(
                PolarisClientSettings.MODE_DESKTOP_DISPLAY,
                PolarisClientSettings.MODE_GPU_NATIVE_TEST
            ) && uiState.hostStreamDisplayModeLabel.isNotBlank()
        ) {
            parts += getString(R.string.nova_polaris_sync_host_mode_detail, uiState.hostStreamDisplayModeLabel)
        }
        parts += when {
            uiState.usesSafeHostFallback -> {
                buildList {
                    add(
                        getString(
                            R.string.nova_library_launch_intro_safe_fallback,
                            uiState.hostStreamDisplayModeLabel,
                            uiState.playModeLabel,
                        ),
                    )
                    uiState.hostStreamDisplayModeUnavailableReason
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }.joinToString(" ")
            }
            uiState.virtualDisplayUnavailable -> {
                val unavailableParts = mutableListOf(
                    getString(R.string.nova_library_virtual_display_unavailable_body)
                )
                uiState.virtualDisplayUnavailableReason
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        unavailableParts += getString(
                            R.string.nova_library_virtual_display_unavailable_reason_format,
                            it
                        )
                    }
                unavailableParts.joinToString(" ")
            }
            // A place picked for this game speaks for itself. The host's reason explains its
            // own default: picked Host Virtual, the plan still said the game ran in a private
            // labwc compositor.
            uiState.hasExplicitOverride ->
                playSetupModeDetails()[PolarisStreamDisplayMode.normalize(uiState.playMode)].orEmpty()
            uiState.launchChoice.hostModeReason.isNotBlank() -> uiState.launchChoice.hostModeReason
            uiState.game.launchMode?.modeReason?.isNotBlank() == true -> uiState.game.launchMode?.modeReason.orEmpty()
            uiState.recommendedMode == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> getString(R.string.nova_library_launch_intro_virtual_default)
            else -> getString(R.string.nova_library_launch_intro_headless_default)
        }
        // The app's own preference trails the description rather than leading it: this
        // paragraph sits under "What will happen", and opening it with a mode that will
        // NOT happen ("App default: Host Virtual Display." over a Private Stream plan)
        // made the headline and its first sentence contradict each other.
        if (uiState.preferredMode != uiState.recommendedMode) {
            parts += getString(R.string.nova_library_launch_preferred_mode_format, modeLabel(uiState.preferredMode))
        }
        return parts.joinToString(" ")
    }

    private fun lastPlayedText(game: PolarisGame): String? {
        if (game.lastLaunched <= 0) return null
        val relative = DateUtils.getRelativeTimeSpanString(
            game.lastLaunched * 1000,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
        return getString(R.string.nova_library_meta_last_played, relative)
    }

    private fun buildOptimizationState(
        opt: JSONObject?,
        profilePreference: String
    ): NovaGameDetailOptimizationState {
        if (opt == null || !StreamSyncManager.hasTrustedResolvedProfile(opt)) {
            return NovaGameDetailOptimizationState()
        }

        // Only the deterministic resolved-profile contract is renderable.
        // Legacy AI/cache/recovery objects are neither launch inputs nor UI
        // fallbacks in this release.
        val profileState: JSONObject? = null
        val resolvedProfile = opt.optJSONObject("resolved_profile")
        val currentProfile = resolvedProfile
        val resolvedFields = resolvedProfile?.optJSONObject("fields")
        fun resolvedField(name: String): JSONObject? = resolvedFields?.optJSONObject(name)
        val lastResult: JSONObject? = null
        val source = opt.optString("source", "")
        val confidence = opt.optString("confidence", "")
        val cacheStatus = opt.optString("cache_status", "")
        val displayMode = resolvedField("display_mode")
            ?.optString("value", "")
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        val bitrate = resolvedField("target_bitrate_kbps")
            ?.optInt("value", 0)
            ?.takeIf { it > 0 }
            ?: 0
        val targetFps = resolvedField("target_fps")?.optDouble("value", 0.0)
            ?.takeIf { it > 0.0 }
            ?: displayMode.substringAfterLast('x', "").toDoubleOrNull()
            ?: 0.0
        val codec = resolvedField("preferred_codec")
            ?.optString("value", "")
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        val hdr = resolvedField("hdr")?.takeIf { it.has("value") }?.optBoolean("value")
        val reasoning = opt.optString("reasoning", "")
        val normalizationReason = opt.optString("normalization_reason", "")
        val generatedAt = opt.optLong("generated_at", 0L)

        val aiCard = if (displayMode.isNotEmpty() || codec.isNotEmpty() || profileState != null) {
            val parts = mutableListOf<String>()
            if (displayMode.isNotEmpty()) parts.add(displayMode)
            if (displayMode.isEmpty() && targetFps > 0.0) parts.add("${formatFps(targetFps)} FPS")
            if (codec.isNotEmpty()) parts.add(codec.uppercase())
            if (bitrate > 0) parts.add("up to ${bitrate / 1000} Mbps")
            if (hdr != null) parts.add(if (hdr) "HDR" else "SDR")
            val settingsText = parts.joinToString(" · ").ifBlank { "Preset fields are unavailable" }

            val provenanceParts = mutableListOf<String>()
            val fieldNames = resolvedFields?.keys()
            while (fieldNames?.hasNext() == true) {
                val fieldName = fieldNames.next()
                if (fieldName == "display_mode" &&
                    resolvedFields.has("display_width") && resolvedFields.has("display_height") &&
                    resolvedFields.has("target_fps")) {
                    continue
                }
                val field = resolvedFields.optJSONObject(fieldName) ?: continue
                val fieldSource = (field.opt("source") as? String)
                    ?.takeIf { it.isNotBlank() } ?: "unknown"
                val flags = buildList {
                    if (field.optBoolean("locked", false)) add("locked")
                    if (field.optBoolean("normalized", false)) add("normalized")
                }
                provenanceParts += buildString {
                    append(fieldName.replace('_', ' '))
                    append(" from ")
                    append(fieldSource.replace('_', ' '))
                    if (flags.isNotEmpty()) append(" (${flags.joinToString()})")
                }
            }
            val provenanceText = provenanceParts.sorted().joinToString("; ")

            val presetLabel = currentProfile
                ?.optString("preset_label", "")
                ?.takeIf { it.isNotBlank() }
                ?: resolvedProfile?.optString("preset_label", "")?.takeIf { it.isNotBlank() }
            val titleLabel = "Launch preset: ${presetLabel ?: "Auto"}"
            val sourceLabel = "Deterministic policy v1"
            val profileStateLabel = profileState
                ?.optString("state", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { profileStateLabel(it) }
                .orEmpty()
            val stateLabel = when {
                profileStateLabel.isNotBlank() -> profileStateLabel
                normalizationReason.isNotBlank() -> getString(R.string.nova_optimization_host_adjusted)
                cacheStatus.equals("hit", ignoreCase = true) -> getString(R.string.nova_optimization_cached)
                cacheStatus.equals("invalidated", ignoreCase = true) -> getString(R.string.nova_optimization_recovery)
                cacheStatus.equals("miss", ignoreCase = true) -> getString(R.string.nova_optimization_fresh)
                source.contains("device_db") -> getString(R.string.nova_optimization_device_tune)
                else -> ""
            }
            val lastResultText = buildLastResultText(lastResult)
            val generatedLabel = if (generatedAt > 0) {
                DateUtils.getRelativeTimeSpanString(
                    generatedAt * 1000,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else {
                ""
            }
            val sourceText = listOf(
                stateLabel.takeIf { it.isNotBlank() && stateLabel != titleLabel },
                profileState?.optString("preference_label", "")?.takeIf { it.isNotBlank() },
                lastResultText.takeIf { it.isNotBlank() },
                sourceLabel.takeIf { it.isNotBlank() && sourceLabel != titleLabel },
                confidence.takeIf { it.isNotBlank() }?.lowercase()?.plus(" confidence"),
                generatedLabel.takeIf { it.isNotBlank() }
            ).filter { !it.isNullOrBlank() }.joinToString(" · ")
            val profileReason = profileState?.optString("reason", "").orEmpty()
            val preferenceNote = profileState
                ?.optString("preference_note", "")
                ?.takeIf { profilePreference != "auto" }
                .orEmpty()
            val requestedFps = opt.optDouble("requested_target_fps", 0.0)
            val effectiveFps = opt.optDouble("effective_target_fps", 0.0)
            val requestedReason = if (requestedFps > 0.0 && effectiveFps > 0.0 && abs(requestedFps - effectiveFps) > 0.5) {
                "Requested ${formatFps(requestedFps)} FPS, selected ${formatFps(effectiveFps)} FPS."
            } else {
                ""
            }
            val fullReasoning = listOf(
                profileReason,
                preferenceNote,
                provenanceText,
                requestedReason,
                reasoning,
                normalizationReason
            )
                .filter { it.isNotBlank() }
                .joinToString(" ")

            NovaGameDetailInsightCard(
                label = titleLabel,
                source = sourceText,
                settings = settingsText,
                reasoning = fullReasoning,
                isWarning = cacheStatus.equals("invalidated", ignoreCase = true)
            )
        } else {
            null
        }

        val stabilityCard = opt.optJSONObject("stability")?.let { stability ->
            val summary = (stability.opt("summary") as? String).orEmpty()
            if (summary.isBlank()) {
                null
            } else {
                NovaGameDetailInsightCard(
                    label = "Doctor Observation",
                    source = "",
                    settings = "Launch settings unchanged",
                    reasoning = summary,
                    isWarning = false
                )
            }
        }

        val clientPreferences = PreferenceConfiguration.readPreferences(this)
        return NovaGameDetailOptimizationState(
            ai = aiCard,
            stability = stabilityCard,
            profileSummary = buildNovaLaunchProfileSummary(
                opt,
                clientAskedFps = clientPreferences.fps.toDouble(),
                clientFpsPinned = NovaLaunchStreamOverride.highFpsPin(profilePreference, clientPreferences.fps) != null,
                clientAskedHdr = clientPreferences.enableHdr,
                spaceName = launchSpaceName(),
            ),
            rawOptimization = opt,
            reviewRequired = StreamSyncManager.requiresLaunchPreflightReview(opt),
            reviewReason = StreamSyncManager.launchPreflightReviewReason(opt),
            aiRecommendedMode = ""
        )
    }

    private fun profileStateLabel(state: String): String {
        return when (state.lowercase()) {
            "manual_override" -> "Manual"
            "upgrade_available" -> "Ready"
            "recovering" -> "Recovery"
            "blocked" -> "Holding"
            "learning" -> "Learning"
            "stable" -> "Stable"
            else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatFps(fps: Double): String {
        val rounded = round(fps)
        return if (abs(fps - rounded) < 0.01) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", fps)
        }
    }

    private fun buildLastResultText(lastResult: JSONObject?): String {
        if (lastResult == null) return ""
        val grade = lastResult.optString("grade", "")
        val delivered = lastResult.optDouble("delivered_fps", 0.0)
        val target = lastResult.optDouble("target_fps", 0.0)
        val fpsText = if (delivered > 0.0 && target > 0.0) {
            "${formatFps(delivered)}/${formatFps(target)} FPS"
        } else {
            ""
        }
        return listOf(
            grade.takeIf { it.isNotBlank() }?.let { "Last $it" },
            fpsText.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" · ")
    }


    companion object {
        const val EXTRA_HOST = "nova.detail.host"
        const val EXTRA_HTTPS_PORT = "nova.detail.httpsPort"
        const val EXTRA_SERVER_CERT = "nova.detail.serverCert"

        /**
         * The server's display name and uuid.
         *
         * The uuid is what auto-match is stored against, so opening the host settings
         * without it would silently disable that toggle rather than fail visibly.
         */
        const val EXTRA_SERVER_NAME = "nova.detail.serverName"
        const val EXTRA_SERVER_UUID = "nova.detail.serverUuid"
        const val EXTRA_GAME = "nova.detail.game"
        const val EXTRA_DEFAULT_VIRTUAL_DISPLAY = "nova.detail.defaultVirtualDisplay"
        const val EXTRA_PLAY_SETUP = "nova.detail.playSetup"
        const val EXTRA_OPEN_SPACE = "nova.detail.openSpace"
        const val EXTRA_SPACE_SETTINGS = "nova.detail.spaceSettings"
        /** False when the library already knows this host has no Spaces; Play Setup then asks it nothing. */
        const val EXTRA_SPACES_ENABLED = "nova.detail.spacesEnabled"
        const val EXTRA_RESULT_SPACE = "nova.detail.result.space"
        const val EXTRA_RESULT_LAUNCH = "nova.detail.result.launch"
        const val EXTRA_RESULT_LAUNCH_GAME = "nova.detail.result.launchGame"
        const val EXTRA_RESULT_SESSION = "nova.detail.result.session"
        const val EXTRA_RESULT_MANAGE_SERVER = "nova.detail.result.manageServer"
        const val RESULT_SESSION_RESUME = "resume"
        const val RESULT_SESSION_END = "end"
        const val EXTRA_RESULT_GAME = "nova.detail.result.game"

        const val RESULT_KEY_VIRTUAL_DISPLAY = "virtualDisplay"
        const val RESULT_KEY_MIRROR_DESKTOP = "mirrorDesktop"
        const val RESULT_KEY_FORCE_PRIVATE = "forcePrivateAfterSteamClose"
        const val RESULT_KEY_PROFILE_PREFERENCE = "profilePreference"
        const val RESULT_KEY_STREAM_MODE = "streamDisplayMode"
        const val RESULT_KEY_ENCODER_BACKEND = "encoderBackend"
        const val RESULT_KEY_PRESENTATION_MODE = "presentationDisplayMode"
        const val RESULT_KEY_PREFLIGHT = "preflightOptimization"

        private const val DEFAULT_HTTPS_PORT = 47984

        fun newIntent(
            context: Context,
            game: PolarisGame,
            host: String,
            httpsPort: Int,
            serverCert: ByteArray?,
            defaultToVirtualDisplay: Boolean,
            serverName: String = "",
            serverUuid: String? = null,
        ): Intent = Intent(context, NovaGameDetailActivity::class.java)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_HTTPS_PORT, httpsPort)
            .putExtra(EXTRA_SERVER_CERT, serverCert)
            .putExtra(EXTRA_SERVER_NAME, serverName)
            .putExtra(EXTRA_SERVER_UUID, serverUuid)
            .putExtra(EXTRA_GAME, PolarisGameJson.encode(game))
            .putExtra(EXTRA_DEFAULT_VIRTUAL_DISPLAY, defaultToVirtualDisplay)
    }
}

/**
 * How long a row waits before the host is told.
 *
 * Long enough to absorb someone cycling a row to the value they want, short enough that
 * letting go and pressing Play does not feel like a stall -- and a launch flushes it
 * early anyway, so this is only ever the cost of walking away mid-change.
 */
internal const val NOVA_PLAY_SETUP_SETTLE_MS = 650L

/**
 * Resolves a persisted resolution-choice id against this open's planner choices.
 *
 * [NovaDisplayResolutionChoice] objects are rebuilt fresh from the planner on every
 * screen open, so a saved id can point at a choice that no longer exists — a host
 * catalog change, a different display topology, or a stale id from before this game's
 * choices were last rebuilt. That must read as "no override", not a crash or a stale
 * object, so the lookup is a plain [List.firstOrNull] and a miss returns null.
 */
internal fun resolveSavedResolutionChoice(
    savedId: String?,
    visibleChoices: List<NovaDisplayResolutionChoice>,
): NovaDisplayResolutionChoice? {
    if (savedId.isNullOrBlank()) return null
    return visibleChoices.firstOrNull { it.id == savedId }
}

/**
 * The fixed rates the Frame Rate row could offer, independent of what any resolution
 * choice happens to pair with. Not host-derived like the resolution list -- Polaris does
 * not publish a capability list to filter this against -- but it must still be culled to
 * what this panel can actually present: see [NovaDisplayFpsCapability.allowedFpsValues],
 * the same threshold every other FPS-offering surface in the app uses.
 */
private val NOVA_FRAME_RATE_CHOICES: List<Int> = NovaDisplayFpsCapability.STANDARD_FPS_VALUES

/**
 * The fps that actually launches: an explicit Frame Rate row pin, or -- only when there
 * is none -- the fps Tuning = High FPS would pin instead.
 *
 * The single place this precedence is decided. [NovaGameDetailActivity]'s
 * launchOptimization() feeds this straight into the launch envelope's fpsOverride; the
 * Play Setup Tuning row caption must never name a different winner than this, which is
 * why the row suppresses its own "Pins N FPS" claim whenever chosenFps is non-null
 * instead of recomputing this precedence a second time.
 */
internal fun effectiveFpsPin(chosenFps: Int?, profilePreference: String, settingsFps: Float): Int? =
    chosenFps ?: NovaLaunchStreamOverride.highFpsPin(profilePreference, settingsFps)
