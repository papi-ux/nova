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
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.manager.StreamSyncManager
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
import kotlinx.coroutines.CancellationException
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
    private lateinit var artworkViewModel: NovaArtworkLibraryUpdateViewModel
    private var defaultToVirtualDisplay: Boolean = false
    private var clientSettings: PolarisClientSettings? = null
    private var serverName: String = ""
    private var serverUuid: String? = null

    /**
     * The sheet took these as constructor lambdas. Keeping the names and the nullable
     * shape lets the body below stay the code that was reviewed as a bottom sheet,
     * rather than a rewrite that happens to compile.
     */
    private val onLaunch: ((PolarisGame, Boolean, Boolean, Boolean, String, String, JSONObject?) -> Unit)? =
        { game, withVirtualDisplay, mirrorDesktop, forcePrivateAfterSteamClose, profilePreference, streamMode, preflight ->
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

    /**
     * The Polaris Sync sheet's engine, as Every Game's second surface. Started when
     * that scope first opens so the panel does not poll the host for people who never
     * flip it, and closed with the panel.
     */
    private var hostSyncEngine: NovaPolarisSyncEngine? = null

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
        defaultToVirtualDisplay = intent.getBooleanExtra(EXTRA_DEFAULT_VIRTUAL_DISPLAY, false)

        serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty().ifBlank { host }
        serverUuid = intent.getStringExtra(EXTRA_SERVER_UUID)

        apiClient = PolarisApiClient(this, host, httpsPort, serverCert)
        artworkViewModel = ViewModelProvider(
            this,
            NovaArtworkLibraryUpdateViewModel.Factory(
                context = applicationContext,
                serverAddress = host,
                httpsPort = httpsPort,
                serverCertDer = serverCert,
            ),
        )[NovaArtworkLibraryUpdateViewModel::class.java]

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
        destination != NovaGameDetailDestination.OVERVIEW -> {
            destination = NovaGameDetailDestination.OVERVIEW
            steamDecision = null
            modePickerOpen = false
            // The panel reopens on the game it was opened for; host scope is a place
            // someone flips to, not a place the panel should quietly resume in.
            playSetupScope = NovaPlaySetupScope.THIS_GAME
            explainedRow = NovaPlaySetupRow.WHERE_IT_RUNS
            hostSyncEngine?.close()
            true
        }
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Y is unclaimed everywhere else in this window, so the scope flip takes
        // nothing from anyone. Claimed only while Play Setup is open: a key that acts
        // on a panel that is not on screen is a key that does something invisible.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && destination == NovaGameDetailDestination.PLAY_SETUP) {
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
        if (playSetupScope == scope) {
            return
        }
        playSetupScope = scope
        modePickerOpen = false
        explainedRow = if (scope == NovaPlaySetupScope.EVERY_GAME) {
            NovaPlaySetupRow.HOST_DEFAULT_DISPLAY
        } else {
            NovaPlaySetupRow.WHERE_IT_RUNS
        }
        if (scope == NovaPlaySetupScope.EVERY_GAME) {
            hostSyncEngine?.let { engine ->
                engine.start(clientSettings)
                engine.refresh()
            }
        }
    }

    override fun onDestroy() {
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
            activeSession = session?.takeIf {
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

        var currentGame by mutableStateOf(game)
        var profilePreference by mutableStateOf(loadProfilePreference(currentGame))
        var uiState by mutableStateOf(buildUiState(currentGame, profilePreference))
        var mangoHudEnabled by mutableStateOf(game.mangohud)
        var resetWorking by mutableStateOf(false)
        var optimizationState by mutableStateOf(NovaGameDetailOptimizationState())
        var artworkState by mutableStateOf(loadArtworkState(game))
        // Which row the comparison strip is explaining. It follows focus, and a tap sets
        // it too -- touch has no cursor for the strip to follow, and a finger that lands
        // on a row should get the same explanation a d-pad would.
        explainedRow = NovaPlaySetupRow.WHERE_IT_RUNS
        // An explicit resolution, held until launch rather than launching on the spot.
        // Picking one used to start the game immediately, which is why the row that owned
        // it could not be a setting: there was nothing to set.
        var chosenResolution by mutableStateOf<NovaDisplayResolutionChoice?>(null)
        // Changing a row is cheap; telling the host about it is not. Presses settle first
        // so that cycling past three values costs one round-trip rather than three.
        var settleJob: Job? = null
        // A launch was asked for while the preflight that arms the desktop-Steam guard was
        // still on the wire. The press is held rather than dropped: being quick should not
        // cost you the launch, and it must not cost you the guard either.
        var pendingLaunch by mutableStateOf(false)

        /**
         * The blob this launch would go out with: the host's plan, composed with the
         * resolution chosen here and the High FPS pin, when either exists. Composed
         * over the host blob rather than replacing it, so a resolution pick no longer
         * silently discards the recovery clamp -- only the explicit fps pin releases
         * it. The fps that launches must always be re-derivable from this blob.
         */
        fun launchOptimization(): JSONObject? {
            val preferences = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
            return NovaLaunchStreamOverride.compose(
                raw = optimizationState.rawOptimization,
                resolution = chosenResolution,
                fpsOverride = NovaLaunchStreamOverride.highFpsPin(profilePreference, preferences.fps),
                fallbackWidth = preferences.width,
                fallbackHeight = preferences.height,
                fallbackFps = preferences.fps.toInt(),
            )
        }

        fun refreshUiState(preference: String = profilePreference) {
            uiState = buildUiState(currentGame, preference)
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

        fun launchConfirmed(mirrorDesktop: Boolean, forcePrivateAfterSteamClose: Boolean = false) {
            pendingLaunch = false
            onLaunch?.invoke(
                currentGame.copy(mangohud = mangoHudEnabled),
                uiState.playUsesVirtualDisplay,
                mirrorDesktop,
                forcePrivateAfterSteamClose,
                profilePreference,
                // Session-scoped streamMode travels ONLY for an explicit per-game
                // override. Passing the resolved playMode here froze a (possibly
                // stale) host default into the launch and overrode it per-session;
                // a host-default launch must send nothing and ride the host's
                // current mode instead.
                PolarisStreamDisplayMode.normalize(
                    NovaLaunchModeOverrides.load(this@NovaGameDetailActivity, currentGame).orEmpty()
                ),
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
        fun attemptLaunch() {
            if (!uiState.playEnabled) return
            val optimization = launchOptimization()
            // Guarded on the RAW blob: a pick or an fps pin makes the composed blob
            // non-null even while the preflight that arms the desktop-Steam guard is
            // still on the wire, and a launch in that window must wait either way.
            if (optimizationState.rawOptimization == null && optimizationState.preflightInFlight) {
                pendingLaunch = true
                // Whatever is waiting to settle is what this launch is waiting on, so run
                // it now instead of holding the press for a delay that exists to absorb
                // presses nobody is making any more.
                flushSettled()
                return
            }
            pendingLaunch = false
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
            optimizationState = NovaGameDetailOptimizationState(preflightInFlight = true)
            lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        syncLaunchPreflightSettings(this@NovaGameDetailActivity, apiClient, usesVirtualDisplay, clientSettings, uiState.playMode)?.let {
                            clientSettings = it
                        }
                        apiClient.getOptimization(deviceName, currentGame.name, preference, mode = uiState.playMode)
                    }
                    logPreflightOptimization("Preflight optimization", opt, preference)
                    buildOptimizationState(opt, preference)
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Preflight optimization failed: ${e.message}")
                    NovaGameDetailOptimizationState()
                }
                if (pendingLaunch) attemptLaunch()
            }
        }

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
        fun settleThen(work: () -> Unit) {
            optimizationState = NovaGameDetailOptimizationState(preflightInFlight = true)
            settleJob?.cancel()
            settleJob = lifecycleScope.launch {
                delay(NOVA_PLAY_SETUP_SETTLE_MS)
                work()
            }
        }

        flushSettled = {
            val job = settleJob
            if (job != null && job.isActive) {
                job.cancel()
                settleJob = null
                loadOptimization(profilePreference)
            }
        }

        fun retryHighFpsTrial() {
            profilePreference = "high_fps"
            saveProfilePreference(currentGame, profilePreference)
            refreshUiState(profilePreference)
            optimizationState = NovaGameDetailOptimizationState(preflightInFlight = true)
            lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        syncLaunchPreflightSettings(this@NovaGameDetailActivity, apiClient, uiState.playUsesVirtualDisplay, clientSettings, uiState.playMode)?.let {
                            clientSettings = it
                        }
                        apiClient.getOptimization(deviceName, currentGame.name, profilePreference, "high_fps", mode = uiState.playMode)
                    }
                    logPreflightOptimization("High FPS trial preflight", opt, profilePreference)
                    buildOptimizationState(opt, profilePreference)
                } catch (e: Exception) {
                    LimeLog.warning("Nova: High FPS trial preflight failed: ${e.message}")
                    NovaGameDetailOptimizationState()
                }
                if (pendingLaunch) attemptLaunch()
            }
        }

        fun selectLaunchMode(mode: String) {
            val allowed = when (PolarisGame.normalizeLaunchMode(mode)) {
                PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> uiState.virtualDisplayAllowed && !uiState.virtualDisplayUnavailable
                else -> uiState.headlessAllowed
            }
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
            // the display changes the question, so the answer does not carry over.
            chosenResolution = null
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
        ).choices.size

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
            settleThen { loadOptimization(profilePreference, usesVirtualDisplay = PolarisGame.normalizeLaunchMode(mode) == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY) }
        }

        /** The pinned entry: drop the override so this game follows the host again. */
        fun pickHostDefault() {
            modePickerOpen = false
            NovaLaunchModeOverrides.clear(this@NovaGameDetailActivity, currentGame)
            refreshUiState()
            chosenResolution = null
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
            chosenResolution = choice
        }

        fun selectProfilePreference(value: String) {
            if (value == profilePreference) return
            profilePreference = value
            saveProfilePreference(currentGame, value)
            refreshUiState(value)
            settleThen { loadOptimization(value) }
        }

        fun selectSteamLaunchMode(value: String) {
            val previousGame = currentGame
            val requestedMode = PolarisGame.SteamLaunchContract.normalizeMode(value)
            if (requestedMode == previousGame.steamLaunchMode) return

            // Shown immediately and reconciled when the host answers. The row is a value
            // someone is cycling through, so it cannot wait on a round-trip to redraw.
            currentGame = previousGame.copy(
                steamLaunch = previousGame.steamLaunch?.copy(mode = requestedMode)
            )
            refreshUiState()
            settleThen {
                lifecycleScope.launch {
                    val confirmedMode = withContext(Dispatchers.IO) {
                        apiClient.setSteamLaunchMode(previousGame.id, requestedMode)
                    }
                    if (confirmedMode != null) {
                        currentGame = currentGame.copy(
                            steamLaunch = currentGame.steamLaunch?.copy(mode = confirmedMode)
                        )
                    } else {
                        currentGame = previousGame
                        NovaSnackbar.showError(
                            this@NovaGameDetailActivity,
                            getString(R.string.nova_steam_launch_mode_failed),
                        )
                    }
                    refreshUiState()
                    loadOptimization(profilePreference)
                }
            }
        }

        /**
         * The act column, resolved. Four rows at most, and fewer when a row has nothing to
         * offer -- a host with one launch mode, or no display planner, drops the row rather
         * than drawing a control with a single value in it.
         */
        fun buildPlaySetupRows(): List<NovaPlaySetupRowState> {
            val rows = mutableListOf<NovaPlaySetupRowState>()
            val preferences = PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity)
            val fpsPin = NovaLaunchStreamOverride.highFpsPin(profilePreference, preferences.fps)
            val autoSafeFps = StreamSyncManager
                .resolveAutoSafeTargetFps(preferences.fps, optimizationState.rawOptimization)
                .roundToInt()

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
                enabled = modeOptions.count { it.enabled } > 1 ||
                    novaModePickerEligible(gameModeCatalogSize()),
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
                            consequence = listOf(choice.targetMode, choice.reason)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            current = choice.id == effective?.id,
                            onSelect = { chooseResolution(choice) },
                        )
                    },
                    overridden = overridden,
                )
            }

            rows += NovaPlaySetupRowState(
                row = NovaPlaySetupRow.TUNING,
                label = getString(R.string.nova_play_setup_tuning),
                // High FPS is binding, so the caption states the pin -- and, when the
                // host is holding a recovery target below it, exactly what is being
                // overridden. The other preferences keep the host in control.
                caption = when {
                    fpsPin != null && autoSafeFps in 1 until fpsPin ->
                        getString(R.string.nova_play_setup_tuning_pins_over_hold, fpsPin, autoSafeFps)
                    fpsPin != null -> getString(R.string.nova_play_setup_tuning_pins, fpsPin)
                    else -> getString(R.string.nova_game_detail_profile_caption)
                },
                value = getString(AutoQualityProfilePreferences.shortLabelRes(profilePreference)),
                stripTitle = getString(R.string.nova_play_setup_strip_tuning),
                options = AutoQualityProfilePreferences.values().map { value ->
                    NovaPlaySetupOption(
                        // shortLabelRes, not labelRes: the long form is "AI Preference: X",
                        // and four cards of it ellipsize to four identical words.
                        label = getString(AutoQualityProfilePreferences.shortLabelRes(value)),
                        consequence = getString(novaProfilePreferenceConsequenceRes(value)),
                        current = value == profilePreference,
                        onSelect = { selectProfilePreference(value) },
                    )
                },
                overridden = fpsPin != null,
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

            return rows
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
            onAutoQuality = { hostSyncEngine?.setAiAutoQuality(it) },
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
                NovaGameDetailContent(
                    uiState = uiState,
                    launchIntro = buildLaunchIntro(uiState),
                    recommendedBadge = getString(
                        R.string.nova_library_launch_recommended_mode_badge,
                        modeBadgeLabel(uiState.recommendedMode)
                    ),
                    lastPlayedText = lastPlayedText(currentGame),
                    profilePreferenceLabel = getString(AutoQualityProfilePreferences.labelRes(profilePreference)),
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
                    optimizationState = optimizationState,
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
                    playLabel = if (pendingLaunch) {
                        // The press landed and is being held, so say so. A button that
                        // looks untouched for the length of an HTTP round-trip reads as
                        // one that did not register.
                        getString(R.string.nova_game_detail_launch_checking_host)
                    } else if (optimizationState.reviewRequired) {
                        getString(R.string.nova_library_review_and_launch)
                    } else {
                        // The summary's label states the host's plan; a High FPS pin
                        // outranks that plan, so the button must state the pin instead
                        // of promising a recovery launch it will not perform.
                        val fpsPin = NovaLaunchStreamOverride.highFpsPin(
                            profilePreference,
                            PreferenceConfiguration.readPreferences(this@NovaGameDetailActivity).fps
                        )
                        if (fpsPin != null) {
                            getString(R.string.nova_play_setup_launch_pinned_fps, fpsPin)
                        } else {
                            optimizationState.profileSummary
                                ?.primaryLaunchLabel
                                ?.takeIf { it.isNotBlank() }
                                ?: primaryPlayLabel(uiState)
                        }
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
                            if (row == NovaPlaySetupRow.WHERE_IT_RUNS &&
                                novaModePickerEligible(gameModeCatalogSize())
                            ) {
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
                    onRetryHighFps = { retryHighFpsTrial() },
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

        loadOptimization(profilePreference)

    }

    private fun buildUiState(game: PolarisGame, profilePreference: String): NovaGameDetailUiState {
        return NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = defaultToVirtualDisplay,
            clientSettings = clientSettings,
            profilePreference = profilePreference,
            launchModeOverride = NovaLaunchModeOverrides.load(this@NovaGameDetailActivity, game),
        )
    }

    private fun loadProfilePreference(game: PolarisGame): String {
        return AutoQualityProfilePreferences.load(this@NovaGameDetailActivity, game.name)
    }

    private fun saveProfilePreference(game: PolarisGame, preference: String) {
        AutoQualityProfilePreferences.save(this@NovaGameDetailActivity, game.name, preference)
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
        val fallbackMode = clientSettings?.desired?.displayMode
            ?.takeIf { it.isNotBlank() }
            ?: clientSettings?.effective?.displayMode
            ?: ""
        return NovaDisplayResolutionPlanner.from(
            contract = game.displayPlanner,
            fallbackMode = fallbackMode,
            includeAdvanced = true
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

    private fun buildLaunchIntro(uiState: NovaGameDetailUiState): String {
        val parts = mutableListOf<String>()
        if (uiState.hostStreamDisplayMode in setOf(
                PolarisClientSettings.MODE_DESKTOP_DISPLAY,
                PolarisClientSettings.MODE_GPU_NATIVE_TEST
            ) && uiState.hostStreamDisplayModeLabel.isNotBlank()
        ) {
            parts += getString(R.string.nova_polaris_sync_host_mode_detail, uiState.hostStreamDisplayModeLabel)
        }
        parts += when {
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
        if (opt == null) return NovaGameDetailOptimizationState()

        val profileState = opt.optJSONObject("profile_state")
        val currentProfile = profileState?.optJSONObject("current_profile") ?: opt.optJSONObject("effective_profile")
        val lastResult = profileState?.optJSONObject("last_result")
        val source = opt.optString("source", "")
        val confidence = opt.optString("confidence", "")
        val cacheStatus = opt.optString("cache_status", "")
        val displayMode = currentProfile
            ?.optString("display_mode", "")
            ?.takeIf { it.isNotBlank() }
            ?: opt.optString("display_mode", "")
        val bitrate = currentProfile
            ?.optInt("target_bitrate_kbps", 0)
            ?.takeIf { it > 0 }
            ?: opt.optInt("target_bitrate_kbps", 0)
        val targetFps = currentProfile?.optDouble("target_fps", 0.0) ?: 0.0
        val codec = currentProfile
            ?.optString("preferred_codec", "")
            ?.takeIf { it.isNotBlank() }
            ?: opt.optString("preferred_codec", "")
        val reasoning = opt.optString("reasoning", "")
        val normalizationReason = opt.optString("normalization_reason", "")
        val generatedAt = opt.optLong("generated_at", 0L)

        val aiCard = if (displayMode.isNotEmpty() || codec.isNotEmpty() || profileState != null) {
            val parts = mutableListOf<String>()
            if (displayMode.isNotEmpty()) parts.add(displayMode)
            if (displayMode.isEmpty() && targetFps > 0.0) parts.add("${formatFps(targetFps)} FPS")
            if (codec.isNotEmpty()) parts.add(codec.uppercase())
            if (bitrate > 0) parts.add("up to ${bitrate / 1000} Mbps")
            val settingsText = parts.joinToString(" · ").ifBlank { "Profile is being learned" }

            val titleLabel = profileState
                ?.optString("label", "")
                ?.takeIf { it.isNotBlank() }
                ?: when {
                    source.contains("ai_live") && cacheStatus.equals("invalidated", ignoreCase = true) ->
                        "Auto Quality Recovery"
                    source.contains("ai_cached") -> "Auto Quality Ready"
                    source.contains("ai_live") -> "Auto Quality Optimized"
                    source.contains("device_db") -> "Auto Quality Baseline"
                    else -> "Auto Quality"
                }
            val sourceLabel = when {
                source.contains("ai_live") && cacheStatus.equals("invalidated", ignoreCase = true) ->
                    "Recovery"
                source.contains("ai_cached") -> "Cached profile"
                source.contains("ai_live") -> "Fresh profile"
                source.contains("device_db") -> getString(R.string.nova_library_ai_baseline_source_label)
                else -> source
            }
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
            val fullReasoning = listOf(profileReason, preferenceNote, requestedReason, reasoning, normalizationReason)
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
            val safeProfile = stability.optJSONObject("safe_profile")
            val safeProfileParts = mutableListOf<String>()
            val safeCodec = safeProfile?.optString("preferred_codec", "").orEmpty()
            if (safeCodec.isNotBlank()) {
                safeProfileParts += safeCodec.uppercase()
            }
            val safeBitrate = safeProfile?.optInt("target_bitrate_kbps", 0) ?: 0
            if (safeBitrate > 0) {
                safeProfileParts += "${safeBitrate / 1000} Mbps"
            }
            val safeDisplayMode = safeProfile?.optString("display_mode", "").orEmpty()
            if (safeDisplayMode.isNotBlank()) {
                safeProfileParts += modeBadgeLabel(safeDisplayMode)
            }
            if (safeProfile?.has("hdr") == true && !safeProfile.optBoolean("hdr", false)) {
                safeProfileParts += "HDR off"
            }

            val discouragedFeatures = stability.optJSONArray("discouraged_features")
            val firstDiscouragedReason = if (discouragedFeatures != null && discouragedFeatures.length() > 0) {
                discouragedFeatures.optJSONObject(0)?.optString("reason", "").orEmpty()
            } else {
                ""
            }
            val relaunchNotes = stability.optJSONArray("relaunch_notes")
            val relaunchNote = if (relaunchNotes != null && relaunchNotes.length() > 0) {
                relaunchNotes.optString(0)
            } else {
                ""
            }
            val stabilitySummary = stability.optString("summary", "")
            val stabilityMode = stability.optString("mode", "")
            val stabilityDetails = listOfNotNull(
                stabilitySummary.takeIf { it.isNotBlank() },
                firstDiscouragedReason.takeIf { it.isNotBlank() },
                relaunchNote.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            if (safeProfileParts.isNotEmpty() || stabilityDetails.isNotBlank()) {
                val isStabilityFirst = stabilityMode.equals("stability_first", ignoreCase = true) ||
                    opt.optInt("consecutive_poor_outcomes", 0) > 0
                val relaunchRequired = stability.optBoolean("relaunch_required", false)
                NovaGameDetailInsightCard(
                    label = when {
                        isStabilityFirst -> "Recovery Profile"
                        relaunchRequired -> "Recovery Queued"
                        else -> "Safer Fallback"
                    },
                    source = "",
                    settings = if (safeProfileParts.isNotEmpty()) {
                        safeProfileParts.joinToString(" · ")
                    } else {
                        "Safer next launch"
                    },
                    reasoning = stabilityDetails,
                    isWarning = isStabilityFirst
                )
            } else {
                null
            }
        }

        return NovaGameDetailOptimizationState(
            ai = aiCard,
            stability = stabilityCard,
            profileSummary = buildNovaLaunchProfileSummary(opt),
            rawOptimization = opt,
            reviewRequired = StreamSyncManager.requiresLaunchPreflightReview(opt),
            reviewReason = StreamSyncManager.launchPreflightReviewReason(opt),
            aiRecommendedMode = opt.optString("ai_recommended_mode", "")
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
        const val EXTRA_RESULT_LAUNCH = "nova.detail.result.launch"
        const val EXTRA_RESULT_LAUNCH_GAME = "nova.detail.result.launchGame"
        const val EXTRA_RESULT_SESSION = "nova.detail.result.session"
        const val RESULT_SESSION_RESUME = "resume"
        const val RESULT_SESSION_END = "end"
        const val EXTRA_RESULT_GAME = "nova.detail.result.game"

        const val RESULT_KEY_VIRTUAL_DISPLAY = "virtualDisplay"
        const val RESULT_KEY_MIRROR_DESKTOP = "mirrorDesktop"
        const val RESULT_KEY_FORCE_PRIVATE = "forcePrivateAfterSteamClose"
        const val RESULT_KEY_PROFILE_PREFERENCE = "profilePreference"
        const val RESULT_KEY_STREAM_MODE = "streamDisplayMode"
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
private const val NOVA_PLAY_SETUP_SETTLE_MS = 650L
