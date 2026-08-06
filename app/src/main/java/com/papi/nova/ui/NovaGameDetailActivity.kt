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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round


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

    /**
     * The sheet took these as constructor lambdas. Keeping the names and the nullable
     * shape lets the body below stay the code that was reviewed as a bottom sheet,
     * rather than a rewrite that happens to compile.
     */
    private val onLaunch: ((PolarisGame, Boolean, Boolean, Boolean, String, JSONObject?) -> Unit)? =
        { game, withVirtualDisplay, mirrorDesktop, forcePrivateAfterSteamClose, profilePreference, preflight ->
            setResult(
                RESULT_OK,
                Intent().putExtra(
                    EXTRA_RESULT_LAUNCH,
                    JSONObject()
                        .put(RESULT_KEY_VIRTUAL_DISPLAY, withVirtualDisplay)
                        .put(RESULT_KEY_MIRROR_DESKTOP, mirrorDesktop)
                        .put(RESULT_KEY_FORCE_PRIVATE, forcePrivateAfterSteamClose)
                        .put(RESULT_KEY_PROFILE_PREFERENCE, profilePreference)
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
     */
    private fun dismissActiveDetailDestination(): Boolean = when {
        reviewExpanded -> {
            reviewExpanded = false
            true
        }
        destination != NovaGameDetailDestination.OVERVIEW -> {
            destination = NovaGameDetailDestination.OVERVIEW
            steamDecision = null
            true
        }
        else -> false
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
        var launchOptionsState by mutableStateOf<NovaLaunchOptionsState?>(null)
        var profileOptionsState by mutableStateOf<NovaProfilePreferenceOptionsState?>(null)
        var steamLaunchOptionsState by mutableStateOf<NovaSteamLaunchModeOptionsState?>(null)
        var artworkState by mutableStateOf(loadArtworkState(game))

        fun refreshUiState(preference: String = profilePreference) {
            uiState = buildUiState(currentGame, preference)
        }

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


        fun loadOptimization(preference: String, usesVirtualDisplay: Boolean = uiState.playUsesVirtualDisplay) {
            LimeLog.info(
                "Nova: Preflight optimization requested game=${currentGame.name} " +
                    "preference=$preference virtualDisplay=$usesVirtualDisplay"
            )
            android.util.Log.i(
                "NovaPreflight",
                "requested game=${currentGame.name} preference=$preference virtualDisplay=$usesVirtualDisplay"
            )
            lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        syncLaunchPreflightSettings(this@NovaGameDetailActivity, apiClient, usesVirtualDisplay, clientSettings)?.let {
                            clientSettings = it
                        }
                        apiClient.getOptimization(deviceName, currentGame.name, preference)
                    }
                    logPreflightOptimization("Preflight optimization", opt, preference)
                    buildOptimizationState(opt, preference)
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Preflight optimization failed: ${e.message}")
                    NovaGameDetailOptimizationState()
                }
            }
        }

        fun retryHighFpsTrial() {
            profilePreference = "high_fps"
            saveProfilePreference(currentGame, profilePreference)
            refreshUiState(profilePreference)
            lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        syncLaunchPreflightSettings(this@NovaGameDetailActivity, apiClient, uiState.playUsesVirtualDisplay, clientSettings)?.let {
                            clientSettings = it
                        }
                        apiClient.getOptimization(deviceName, currentGame.name, profilePreference, "high_fps")
                    }
                    logPreflightOptimization("High FPS trial preflight", opt, profilePreference)
                    buildOptimizationState(opt, profilePreference)
                } catch (e: Exception) {
                    LimeLog.warning("Nova: High FPS trial preflight failed: ${e.message}")
                    NovaGameDetailOptimizationState()
                }
            }
        }

        fun selectLaunchMode(mode: String) {
            val allowed = when (mode) {
                "virtual_display" -> uiState.virtualDisplayAllowed && !uiState.virtualDisplayUnavailable
                else -> uiState.headlessAllowed
            }
            if (!allowed || mode == uiState.playMode) return

            val previousLaunchMode = currentGame.launchMode
            val allowedModes = previousLaunchMode?.allowedModes
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("headless", "virtual_display")
            val updatedLaunchMode = (previousLaunchMode ?: PolarisGame.LaunchModeContract()).copy(
                preferredMode = mode,
                allowedModes = allowedModes
            )
            currentGame = currentGame.copy(launchMode = updatedLaunchMode)
            refreshUiState()
            optimizationState = NovaGameDetailOptimizationState()
            loadOptimization(profilePreference, usesVirtualDisplay = mode == "virtual_display")
        }

        fun launchConfirmed(mirrorDesktop: Boolean, forcePrivateAfterSteamClose: Boolean = false) {
            onLaunch?.invoke(
                currentGame.copy(mangohud = mangoHudEnabled),
                uiState.playUsesVirtualDisplay,
                mirrorDesktop,
                forcePrivateAfterSteamClose,
                profilePreference,
                optimizationState.rawOptimization
            )
            finish()
        }

        fun resetProfile() {
            resetWorking = true
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    apiClient.clearOptimizerProfile(deviceName, currentGame.name)
                }
                optimizationState = NovaGameDetailOptimizationState()
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
                    launchOptionsState = launchOptionsState,
                    profileOptionsState = profileOptionsState,
                    playLabel = if (optimizationState.reviewRequired) {
                        getString(R.string.nova_library_review_and_launch)
                    } else {
                        optimizationState.profileSummary
                            ?.primaryLaunchLabel
                            ?.takeIf { it.isNotBlank() }
                            ?: primaryPlayLabel(uiState)
                    },
                    launchOptionsLabel = getString(R.string.nova_library_launch_options_secondary),
                    launchModeTitle = getString(R.string.nova_library_launch_mode_title),
                    headlessModeLabel = modeBadgeLabel("headless"),
                    virtualDisplayModeLabel = modeBadgeLabel("virtual_display"),
                    coverContentDescription = getString(R.string.nova_a11y_game_cover),
                    onPrimaryLaunch = {
                        if (!uiState.playEnabled) return@NovaGameDetailContent
                        val decision = NovaDesktopSteamLaunchDecision.from(
                            uiState,
                            optimizationState.rawOptimization
                        )
                        when {
                            // A choice of where to run belongs in the destination named that,
                            // not in a sheet raised over the artwork.
                            decision.required -> {
                                steamDecision = decision
                                destination = NovaGameDetailDestination.LAUNCH_MODE
                            }
                            // The review is a statement about the profile, and the status
                            // line is where the profile lives, so it expands in place.
                            optimizationState.reviewRequired && !reviewExpanded -> {
                                reviewExpanded = true
                            }
                            else -> launchConfirmed(false)
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
                    onLaunchOptions = {
                        val nextState = showLaunchOptions(currentGame, uiState)
                        if (nextState == null) {
                            Toast.makeText(this@NovaGameDetailActivity, R.string.nova_library_no_launch_modes, Toast.LENGTH_SHORT).show()
                        } else {
                            launchOptionsState = nextState
                            profileOptionsState = null
                        }
                    },
                    onLaunchModeSelected = ::selectLaunchMode,
                    onLaunchOptionSelected = { option ->
                        fun launchSelected(mirrorDesktop: Boolean, forcePrivateAfterSteamClose: Boolean = false) {
                            val selectedLaunchOptimization = option.launchOptimization ?: optimizationState.rawOptimization
                            onLaunch?.invoke(
                                currentGame.copy(mangohud = mangoHudEnabled),
                                option.usesVirtualDisplay,
                                mirrorDesktop,
                                forcePrivateAfterSteamClose,
                                profilePreference,
                                selectedLaunchOptimization
                            )
                            launchOptionsState = null
                            finish()
                        }
                        val desktopSteamDecision = NovaDesktopSteamLaunchDecision.from(
                            uiState,
                            optimizationState.rawOptimization,
                            usesVirtualDisplay = option.usesVirtualDisplay
                        )
                        if (desktopSteamDecision.required) {
                            // Same destination the primary action routes to; picking an
                            // explicit option does not change where the choice belongs.
                            steamDecision = desktopSteamDecision
                            destination = NovaGameDetailDestination.LAUNCH_MODE
                        } else {
                            launchSelected(mirrorDesktop = false)
                        }
                    },
                    onDismissLaunchOptions = {
                        launchOptionsState = null
                    },
                    onProfilePreference = {
                        profileOptionsState = showProfilePreferenceOptions(currentGame)
                        launchOptionsState = null
                    },
                    onProfilePreferenceSelected = { selected ->
                        saveProfilePreference(currentGame, selected.value)
                        profilePreference = selected.value
                        refreshUiState(selected.value)
                        optimizationState = NovaGameDetailOptimizationState()
                        profileOptionsState = null
                        loadOptimization(selected.value)
                    },
                    onDismissProfileOptions = {
                        profileOptionsState = null
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
                    steamLaunchOptionsState = steamLaunchOptionsState,
                    onSteamLaunchMode = {
                        steamLaunchOptionsState = steamLaunchModeOptionsState(currentGame)
                    },
                    onSteamLaunchModeSelected = { selected ->
                        val previousGame = currentGame
                        val requestedMode = PolarisGame.SteamLaunchContract.normalizeMode(selected.value)
                        if (requestedMode == previousGame.steamLaunchMode) {
                            steamLaunchOptionsState = null
                            return@NovaGameDetailContent
                        }

                        currentGame = previousGame.copy(
                            steamLaunch = previousGame.steamLaunch?.copy(mode = requestedMode)
                        )
                        refreshUiState()
                        lifecycleScope.launch {
                            val confirmedMode = withContext(Dispatchers.IO) {
                                apiClient.setSteamLaunchMode(previousGame.id, requestedMode)
                            }
                            val message = if (confirmedMode != null) {
                                currentGame = currentGame.copy(
                                    steamLaunch = currentGame.steamLaunch?.copy(mode = confirmedMode)
                                )
                                refreshUiState()
                                steamLaunchOptionsState = null
                                R.string.nova_steam_launch_mode_updated
                            } else {
                                currentGame = previousGame
                                refreshUiState()
                                steamLaunchOptionsState = steamLaunchModeOptionsState(previousGame)
                                R.string.nova_steam_launch_mode_failed
                            }
                            Toast.makeText(this@NovaGameDetailActivity, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDismissSteamLaunchModeOptions = {
                        steamLaunchOptionsState = null
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
            profilePreference = profilePreference
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

    private fun showProfilePreferenceOptions(
        game: PolarisGame
    ): NovaProfilePreferenceOptionsState {
        val values = AutoQualityProfilePreferences.values()
        val current = loadProfilePreference(game)
        val labels = values.map {
            when (it) {
                "quality" -> "Prefer Quality"
                "high_fps" -> "Prefer High FPS"
                "stability" -> "Prefer Stability"
                else -> "Auto"
            }
        }
        return NovaProfilePreferenceOptionsState(
            title = getString(R.string.nova_library_profile_preference_title),
            closeLabel = getString(R.string.nova_controller_hint_close),
            options = values.mapIndexed { index, value ->
                NovaProfilePreferenceItem(
                    label = labels[index],
                    value = value,
                    selected = value == current
                )
            }
        )
    }

    private fun steamLaunchModeOptionsState(game: PolarisGame): NovaSteamLaunchModeOptionsState {
        val modes = listOf("direct", "big-picture")
        return NovaSteamLaunchModeOptionsState(
            title = getString(R.string.nova_steam_launch_options_title),
            subtitle = getString(R.string.nova_steam_launch_detail_label),
            closeLabel = getString(R.string.nova_controller_hint_close),
            options = modes.map { mode ->
                val normalizedMode = PolarisGame.SteamLaunchContract.normalizeMode(mode)
                NovaSteamLaunchModeItem(
                    label = steamLaunchModeLabel(normalizedMode),
                    value = normalizedMode,
                    selected = normalizedMode == game.steamLaunchMode
                )
            }
        )
    }

    private fun showLaunchOptions(
        game: PolarisGame,
        uiState: NovaGameDetailUiState
    ): NovaLaunchOptionsState? {
        val options = mutableListOf<NovaLaunchOptionItem>()
        val fallbackMode = clientSettings?.desired?.displayMode
            ?.takeIf { it.isNotBlank() }
            ?: clientSettings?.effective?.displayMode
            ?: ""
        val planner = NovaDisplayResolutionPlanner.from(
            contract = game.displayPlanner,
            fallbackMode = fallbackMode,
            includeAdvanced = true
        )
        if (planner.available) {
            planner.visibleChoices.forEach { choice ->
                options += NovaLaunchOptionItem(
                    label = choice.title,
                    usesVirtualDisplay = uiState.playUsesVirtualDisplay,
                    recommended = choice.recommended,
                    caption = listOf(choice.targetMode, choice.reason).filter { it.isNotBlank() }.joinToString(" · "),
                    badge = choice.badge,
                    launchOptimization = NovaDisplayResolutionPlanner.buildLaunchOptimizationOverride(
                        choice,
                        source = "nova_display_planner"
                    )
                )
            }
        } else {
            if (uiState.headlessAllowed) {
                options += NovaLaunchOptionItem(
                    label = optionLabel("headless", uiState.recommendedMode),
                    usesVirtualDisplay = false,
                    recommended = uiState.recommendedMode == "headless"
                )
            }
            if (uiState.virtualDisplayAllowed) {
                options += NovaLaunchOptionItem(
                    label = optionLabel("virtual_display", uiState.recommendedMode),
                    usesVirtualDisplay = true,
                    recommended = uiState.recommendedMode == "virtual_display"
                )
            }
        }

        if (options.isEmpty()) return null

        return NovaLaunchOptionsState(
            title = getString(R.string.nova_library_launch_options_title),
            closeLabel = getString(R.string.nova_controller_hint_close),
            gameName = game.name,
            options = options
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
        clientSettings: PolarisClientSettings?
    ): PolarisClientSettings? {
        val preferences = PreferenceConfiguration.readPreferences(context)
        return apiClient.updateClientSettings(
            streamDisplayMode = PolarisStreamDisplayMode.preflightModeForLaunch(usesVirtualDisplay, clientSettings),
            displayMode = PreferenceConfiguration.formatStreamingDisplayMode(
                preferences.width,
                preferences.height,
                preferences.fps
            ),
            targetBitrateKbps = preferences.bitrate.takeIf { it > 0 }
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
        return when (mode) {
            "virtual_display" -> getString(R.string.nova_library_launch_virtual_display)
            else -> getString(R.string.nova_library_launch_headless)
        }
    }

    private fun modeBadgeLabel(mode: String): String {
        return when (mode) {
            "virtual_display" -> getString(R.string.nova_library_launch_virtual_short)
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
        if (uiState.preferredMode != uiState.recommendedMode) {
            parts += getString(R.string.nova_library_launch_preferred_mode_format, modeLabel(uiState.preferredMode))
        }
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
            uiState.recommendedMode == "virtual_display" -> getString(R.string.nova_library_launch_intro_virtual_default)
            else -> getString(R.string.nova_library_launch_intro_headless_default)
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
            reviewReason = StreamSyncManager.launchPreflightReviewReason(opt)
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
        const val RESULT_KEY_PREFLIGHT = "preflightOptimization"

        private const val DEFAULT_HTTPS_PORT = 47984

        fun newIntent(
            context: Context,
            game: PolarisGame,
            host: String,
            httpsPort: Int,
            serverCert: ByteArray?,
            defaultToVirtualDisplay: Boolean,
        ): Intent = Intent(context, NovaGameDetailActivity::class.java)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_HTTPS_PORT, httpsPort)
            .putExtra(EXTRA_SERVER_CERT, serverCert)
            .putExtra(EXTRA_GAME, PolarisGameJson.encode(game))
            .putExtra(EXTRA_DEFAULT_VIRTUAL_DISPLAY, defaultToVirtualDisplay)
    }
}
