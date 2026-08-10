package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.compose.NovaRadius
import kotlinx.coroutines.delay

/** Where the detail window currently is. Back unwinds one level before leaving. */
/**
 * Where the detail window can go.
 *
 * LAUNCH_MODE and TUNE were one decision behind two doors, each holding half of the
 * other's subject, so they are now PLAY_SETUP. Artwork stays its own destination:
 * curating images is a different job from deciding how to play.
 */
internal enum class NovaGameDetailDestination { OVERVIEW, PLAY_SETUP, ARTWORK }

/** Content insets shared by the Overview and the destinations that sit beside it. */
internal val NovaGameDetailInset = 28.dp
internal val NovaGameDetailFloor = 58.dp

/** Every focusable control clears the accessible target floor. */
internal val NovaGameDetailActionHeight = 48.dp

/** Matches the library's surface radius; the sharp edge was a deliberate choice there. */
/**
 * One radius scale, chosen by control size rather than by which surface it sits on.
 *
 * The window had drifted to a single 8dp for everything from a close chip to a full
 * notice card, which is why some selectable things read as cards and others as squares.
 * Three steps, taken from the concept at 2.30625px per dp: 10px, 14px, 18px.
 */
/**
 * The landing screen of the detail window.
 *
 * The artwork is the subject and nothing sits on it in a card: the content block rests on
 * the scrim floor in one reading order — who the game is, a hairline, what pressing the
 * primary action will do, then what you can do. Layout is complete before the artwork
 * arrives, so a slow or failed load changes the backdrop and moves nothing.
 */
@Composable
internal fun NovaGameDetailOverview(
    uiState: NovaGameDetailUiState,
    apiClient: PolarisApiClient,
    playLabel: String,
    lastPlayedText: String?,
    sourceLabel: String,
    optimizationState: NovaGameDetailOptimizationState,
    reviewExpanded: Boolean,
    showLaunchModeAction: Boolean,
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String,
    playFocusRequester: FocusRequester,
    onPrimaryLaunch: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    onDestination: (NovaGameDetailDestination) -> Unit,
    activeSession: NovaLibraryActiveSessionUiState?,
    onResumeSession: () -> Unit,
    onEndSession: () -> Unit,
    /**
     * How strongly the chrome reads while something is open over it.
     *
     * A destination that spans the width is translucent so the game stays present, but
     * "the game" means the artwork -- the Overview's own title, gauge, status line and
     * rail are high-contrast text, and ghosting them behind more text is noise rather
     * than context. Only the chrome fades; the backdrop is left alone, because it is the
     * thing worth seeing through to.
     */
    chromeAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val game = uiState.game

    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("nova-game-detail-overview")) {
        val portrait = maxHeight > maxWidth

        if (portrait) {
            // A 3:1 hero cropped to a phone's aspect shows a slice, not a subject, so it
            // gets a 16:9 band and dissolves into the ground rather than filling behind.
            NovaLibraryCinematicBackdrop(
                game = game,
                apiClient = apiClient,
                strength = 1f,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .novaFadeToGround(colors.window),
            )
        } else {
            NovaLibraryCinematicBackdrop(game = game, apiClient = apiClient, strength = 1f)
        }

        Column(
            modifier = Modifier
                .align(if (portrait) Alignment.TopStart else Alignment.BottomStart)
                .graphicsLayer { alpha = chromeAlpha }
                .fillMaxWidth()
                .then(if (portrait) Modifier.padding(top = 176.dp) else Modifier)
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(start = NovaGameDetailInset, end = NovaGameDetailInset, bottom = 10.dp),
        ) {
            NovaGameDetailTitle(
                game = game,
                logoAvailable = logoAvailable,
                logoPresentationKey = logoPresentationKey,
                logoLoader = logoLoader,
                logoContentDescription = logoContentDescription,
            )

            Text(
                text = novaGameDetailIdentityLine(sourceLabel, lastPlayedText, game).uppercase(),
                color = colors.textSecondary,
                style = NovaChromeType.label(fontSize = 11.sp, letterSpacing = 0.17.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 11.dp),
            )

            // The hairline divides names from numbers: identity above, machine state below.
            Box(
                modifier = Modifier
                    .padding(top = 11.dp)
                    .width(330.dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to colors.accent,
                                0.30f to colors.accent.copy(alpha = 0.44f),
                                0.62f to colors.textMuted.copy(alpha = 0.16f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                    ),
            )

            // A duration is a number, so it sits below the hairline with the rest of them.
            // The concept's gauge needs How Long To Beat to have something to measure
            // against; until that exists it draws the played figure alone, and a game no
            // launcher owns draws nothing rather than claiming nobody has played it.
            NovaGameDetailBeatGauge(
                gameName = uiState.game.name,
                playTime = uiState.game.playTime,
                beatTime = uiState.game.beatTime,
                onCorrectMatch = { onDestination(NovaGameDetailDestination.ARTWORK) },
            )

            NovaGameDetailStatusLine(
                uiState = uiState,
                optimizationState = optimizationState,
                modifier = Modifier.padding(top = 11.dp),
            )

            if (reviewExpanded) {
                LaunchProfileReviewNotice(
                    optimizationState = optimizationState,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            NovaGameDetailActions(
                stacked = portrait,
                uiState = uiState,
                optimizationState = optimizationState,
                playLabel = playLabel,
                reviewExpanded = reviewExpanded,
                showLaunchModeAction = showLaunchModeAction,
                playFocusRequester = playFocusRequester,
                onPrimaryLaunch = onPrimaryLaunch,
                onRetryHighFps = onRetryHighFps,
                onResetProfile = onResetProfile,
                onDestination = onDestination,
                activeSession = activeSession,
                onResumeSession = onResumeSession,
                onEndSession = onEndSession,
                modifier = Modifier.padding(top = 16.dp),
            )

            if (!portrait) {
                NovaGameDetailFooter(modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            }
        }

        // On a tall screen the floor belongs at the bottom, not trailing the actions.
        if (portrait) {
            NovaGameDetailFooter(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeContent)
                    .padding(horizontal = NovaGameDetailInset, vertical = 10.dp),
            )
        }

    }
}

/**
 * The floor: what the buttons do on the left, the Polaris mark on the right. Borderless,
 * because a bordered container here would be one more box on a screen whose point is that
 * nothing sits on the artwork in a box.
 */
@Composable
private fun NovaGameDetailFooter(modifier: Modifier = Modifier) {
    val colors = LocalNovaComposeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.testTag("nova-game-detail-footer"),
    ) {
        novaGameDetailOverviewHints().forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 18.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.accent.copy(alpha = 0.22f)),
                ) {
                    Text(hint.key, color = colors.textPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(hint.label, color = colors.textSecondary, fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.nova_polaris_wordmark),
            color = colors.textMuted,
            style = NovaChromeType.label(fontSize = 9.sp),
        )
    }
}

/**
 * Curated logo artwork replaces the title outright when it is ready at first composition.
 * A logo that arrives later is ignored: swapping a settled title for one is a visible jump,
 * and the title is never wrong.
 */
@Composable
private fun NovaGameDetailTitle(
    game: PolarisGame,
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String,
) {
    if (logoAvailable) {
        key(logoPresentationKey) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_START
                        contentDescription = logoContentDescription
                        logoLoader(this)
                    }
                },
                modifier = Modifier
                    .sizeIn(maxWidth = 200.dp, maxHeight = 64.dp)
                    .semantics { contentDescription = logoContentDescription }
                    .testTag("nova-game-detail-logo"),
            )
        }
    } else {
        Text(
            text = game.name,
            color = LocalNovaComposeColors.current.textPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.03).em,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("nova-game-detail-title"),
        )
    }
}

/**
 * What pressing the primary action will do, read as an instrument line rather than a chip:
 * an indicator lamp, then mode and profile set with tabular figures.
 */
@Composable
private fun NovaGameDetailStatusLine(
    uiState: NovaGameDetailUiState,
    optimizationState: NovaGameDetailOptimizationState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val summary = optimizationState.profileSummary
    val limited = optimizationState.reviewRequired ||
        summary?.noticeTone == NovaLaunchProfileNoticeTone.WARNING
    val lamp = when {
        limited -> colors.warning
        summary == null -> colors.textMuted
        else -> colors.accent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier.testTag("nova-game-detail-status"),
    ) {
        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(percent = 50)).background(lamp))
        Text(
            text = novaGameDetailStatusText(uiState, summary).uppercase(),
            color = colors.textPrimary,
            // Measurements, so the digits line up rather than dance. Space Grotesk's
            // digits are proportional by default, so this is load-bearing here.
            style = NovaChromeType.label(fontSize = 11.sp).copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The action lane. Play holds first focus; the rail is gated so it never grows a node that
 * leads nowhere. While a review is expanded the lane becomes the review's own choices —
 * the three buttons of the alert this replaces.
 */
@Composable
private fun NovaGameDetailActions(
    stacked: Boolean,
    uiState: NovaGameDetailUiState,
    optimizationState: NovaGameDetailOptimizationState,
    playLabel: String,
    reviewExpanded: Boolean,
    showLaunchModeAction: Boolean,
    playFocusRequester: FocusRequester,
    onPrimaryLaunch: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    onDestination: (NovaGameDetailDestination) -> Unit,
    activeSession: NovaLibraryActiveSessionUiState?,
    onResumeSession: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lane: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (stacked) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = modifier.fillMaxWidth(),
                content = { content() },
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier,
                content = { content() },
            )
        }
    }

    // Stacked buttons sized to their own labels look ragged; in a column they share a width.
    val itemWidth: Modifier = if (stacked) Modifier.fillMaxWidth() else Modifier

    lane {
        // Precedence: someone else's session, then yours, then the ordinary launch.
        // Launching over a session another device owns would take their display.
        NovaGameDetailAction(
            text = when {
                activeSession?.watchOnly == true -> stringResource(R.string.nova_game_detail_watch)
                activeSession != null -> stringResource(R.string.nova_game_detail_resume)
                else -> playLabel
            },
            onClick = if (activeSession != null) onResumeSession else onPrimaryLaunch,
            enabled = uiState.playEnabled || activeSession != null,
            primary = activeSession?.watchOnly != true,
            glyph = stringResource(R.string.nova_controller_hint_a),
            modifier = itemWidth
                .focusRequester(playFocusRequester)
                .testTag("nova-game-detail-primary"),
        )

        // Play holds first focus, and it has to be asked for here.
        //
        // The request used to live in NovaGameDetailLaunchFooter, which lost its last
        // caller when this window replaced the bottom sheet, so nothing had requested
        // focus since. Focus fell to whichever node happened to be focusable first, and
        // the primary is not focusable until the launch profile arrives -- so on a slow
        // profile the d-pad started on the destination beside Play instead of on Play.
        //
        // Keyed on enabled, because that is the moment the primary becomes reachable.
        // The request is allowed to fail: while a destination is open the Overview is a
        // focusProperties { canFocus = false } group, and asking then must not throw.
        //
        // Settle first, for the same reason novaHoldsFirstFocus does: when the profile
        // is already cached, playEnabled is true on the first composition, so this
        // effect runs before Play is placed -- the request lands on nothing, runCatching
        // swallows it, and focus falls to the first focusable above Play (the launch-
        // profile row). Waiting one settle lets layout happen so the request lands on
        // Play. On the slow-profile path playEnabled flips true after layout, so the
        // wait costs nothing there.
        val playFocusable = uiState.playEnabled || activeSession != null
        LaunchedEffect(playFocusable) {
            if (playFocusable) {
                delay(NOVA_FIRST_FOCUS_SETTLE_MS)
                runCatching { playFocusRequester.requestFocus() }
            }
        }

        if (activeSession != null && !activeSession.watchOnly) {
            NovaGameDetailAction(
                text = stringResource(R.string.nova_game_detail_end_session),
                onClick = onEndSession,
                mark = "◼",
                modifier = itemWidth,
            )
        }

        // Reset is on the rail whatever the review is doing. It used to be drawn only
        // while a review was expanded, which made Play Setup's copy the only one anybody
        // could reach the rest of the time -- and the copy is what a four-row Play Setup
        // has no room for.
        if (reviewExpanded && optimizationState.profileSummary?.showRetryHighFps == true) {
            NovaGameDetailAction(
                text = stringResource(R.string.nova_library_retry_high_fps),
                onClick = onRetryHighFps,
                mark = "\u25B2",
                modifier = itemWidth,
            )
        }
        NovaGameDetailAction(
            text = stringResource(R.string.nova_library_reset_game_profile),
            onClick = onResetProfile,
            mark = "\u21BA",
            modifier = itemWidth,
        )
        if (!reviewExpanded) {
            // Three nodes, always. `showLaunchModeAction` used to decide whether a
            // fourth existed, so the lane changed length depending on the game; what it
            // gated is now a row inside Play Setup rather than an action beside it.
            NovaGameDetailAction(
                text = stringResource(R.string.nova_play_setup_title),
                onClick = { onDestination(NovaGameDetailDestination.PLAY_SETUP) },
                mark = "\u2699",
                modifier = itemWidth,
            )
            NovaGameDetailAction(
                text = stringResource(R.string.nova_artwork_studio_title),
                onClick = { onDestination(NovaGameDetailDestination.ARTWORK) },
                mark = "\u25C8",
                modifier = itemWidth,
            )
        }
    }
}

/**
 * How long this has been played, against how long it takes.
 *
 * Drawn as the concept draws it: the played figure bright at the left, the three
 * estimates dimmer at the right and labelled so a number means something, and a bar
 * whose full width is the completionist figure with the other two cut through it as
 * notches — ground-coloured with a light ring, overhanging top and bottom, so they read
 * as gaps in the bar rather than marks on top of it.
 *
 * The partial cases are the concept's own answers, because a gauge that guesses is worse
 * than one that says less:
 *
 *  - both          bar, notches, figure and estimates
 *  - played only   the figure alone; there is nothing to measure it against
 *  - estimate only "Not started" over an empty track
 *  - neither       nothing, and the hairline above still separates identity from state
 *  - past the end  the bar caps and the figure keeps counting, which is the true number
 */
@Composable
private fun NovaGameDetailBeatGauge(
    gameName: String,
    playTime: PolarisGame.PlayTime?,
    beatTime: PolarisGame.BeatTime?,
    onCorrectMatch: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val uriHandler = LocalUriHandler.current

    val playedSeconds = playTime?.seconds?.takeIf { it > 0 } ?: 0L
    val fullWidthSeconds = beatTime?.longestSeconds?.takeIf { it > 0 } ?: 0L
    if (playedSeconds <= 0L && fullWidthSeconds <= 0L) {
        return
    }

    // Both readouts below are chrome labels that happen to contain numbers, so they take
    // the shared label style and add tabular figures on top: Space Grotesk's digits are
    // proportional, and these change while you watch them.
    val figures = NovaChromeType.label(fontSize = 10.sp).copy(fontFeatureSettings = "tnum")
    var estimateFocused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 10.dp).testTag("nova-game-detail-played")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(NOVA_GAUGE_WIDTH),
        ) {
            Text(
                text = if (playedSeconds > 0L) {
                    val minutes = playedSeconds / 60
                    if (minutes >= 60) {
                        stringResource(R.string.nova_game_detail_played_hours, minutes / 60)
                    } else {
                        stringResource(R.string.nova_game_detail_played_minutes, minutes)
                    }
                } else {
                    stringResource(R.string.nova_game_detail_not_started)
                }.uppercase(),
                color = colors.textPrimary,
                maxLines = 1,
                style = figures.copy(letterSpacing = 0.12.em),
            )

            Spacer(modifier = Modifier.weight(1f))

            val parts = listOfNotNull(
                beatTime?.mainSeconds?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.nova_game_detail_beat_main, it / 3600) },
                beatTime?.extrasSeconds?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.nova_game_detail_beat_extras, it / 3600) },
                beatTime?.completionistSeconds?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.nova_game_detail_beat_complete, it / 3600) },
            )

            if (parts.isNotEmpty()) {
                val page = beatTime?.url.orEmpty()
                val linked = page.isNotBlank()
                val ring = if (estimateFocused && linked) colors.accent else Color.Transparent

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(NOVA_GAUGE_CHIP_RADIUS))
                        .background(
                            if (estimateFocused && linked) {
                                colors.accent.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .border(1.dp, ring, RoundedCornerShape(NOVA_GAUGE_CHIP_RADIUS))
                        .then(
                            // A page makes this a control; without one it is a readout and
                            // has no business in the focus lane.
                            if (linked) {
                                Modifier
                                    .onFocusChanged { estimateFocused = it.isFocused || it.hasFocus }
                                    .clickable(role = Role.Button) { uriHandler.openUri(page) }
                                    .focusable()
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = parts.joinToString("  ·  ").uppercase(),
                        color = colors.textSecondary,
                        maxLines = 1,
                        style = figures.copy(letterSpacing = 0.10.em),
                    )
                    if (linked) {
                        Text(
                            text = "\u2197",
                            color = if (estimateFocused) colors.accent else colors.textMuted,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        if (fullWidthSeconds > 0L) {
            val track = colors.textPrimary.copy(alpha = 0.10f)
            val fillEnd = colors.accent
            val fillStart = lerp(colors.accent, Color.White, 0.28f)
            val notchInk = colors.window
            val notchRing = colors.textPrimary.copy(alpha = 0.34f)
            val played = (playedSeconds.toFloat() / fullWidthSeconds.toFloat()).coerceIn(0f, 1f)
            val notches = listOfNotNull(
                beatTime?.mainSeconds?.takeIf { it > 0 && it < fullWidthSeconds },
                beatTime?.extrasSeconds?.takeIf { it > 0 && it < fullWidthSeconds },
            ).map { it.toFloat() / fullWidthSeconds.toFloat() }

            Canvas(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(NOVA_GAUGE_WIDTH)
                    .height(NOVA_GAUGE_BAR + NOVA_GAUGE_NOTCH_OVERHANG * 2),
            ) {
                val barTop = NOVA_GAUGE_NOTCH_OVERHANG.toPx()
                val barHeight = NOVA_GAUGE_BAR.toPx()
                val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, barTop),
                    size = Size(size.width, barHeight),
                    cornerRadius = radius,
                )
                if (played > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(fillStart, fillEnd)),
                        topLeft = Offset(0f, barTop),
                        size = Size(size.width * played, barHeight),
                        cornerRadius = radius,
                    )
                }

                val notchWidth = NOVA_GAUGE_NOTCH.toPx()
                val ringWidth = notchWidth + 2f
                notches.forEach { fraction ->
                    val centre = size.width * fraction
                    // The ring first, so the notch reads as a gap cut through the bar.
                    drawRoundRect(
                        color = notchRing,
                        topLeft = Offset(centre - ringWidth / 2f, 0f),
                        size = Size(ringWidth, size.height),
                        cornerRadius = CornerRadius(ringWidth / 2f, ringWidth / 2f),
                    )
                    drawRoundRect(
                        color = notchInk,
                        topLeft = Offset(centre - notchWidth / 2f, 1f),
                        size = Size(notchWidth, size.height - 2f),
                        cornerRadius = CornerRadius(notchWidth / 2f, notchWidth / 2f),
                    )
                }
            }
        }

        // A fuzzy match that went wrong looks exactly like one that went right, so the
        // name it actually found is shown whenever it is not plainly the same game.
        val matched = beatTime?.matchedName.orEmpty()
        if (matched.isNotBlank() && novaSameTitle(matched, gameName).not()) {
            var correctionFocused by remember { mutableStateOf(false) }
            Text(
                text = stringResource(R.string.nova_game_detail_matched_as, matched),
                // The estimate follows whatever identity the studio settled on, so the
                // admission of a mismatch is also the way to the place that fixes it.
                color = if (correctionFocused) colors.accent else colors.textMuted,
                fontSize = 10.sp,
                letterSpacing = 0.06.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 5.dp)
                    .onFocusChanged { correctionFocused = it.isFocused || it.hasFocus }
                    .clickable(role = Role.Button, onClick = onCorrectMatch)
                    .focusable()
                    .width(NOVA_GAUGE_WIDTH),
            )
        }
    }
}

/**
 * Whether two titles are the same game as far as a reader cares.
 *
 * Punctuation and case disagree constantly between a launcher and a catalogue, and
 * saying so every time would bury the cases that matter under noise.
 */
private fun novaSameTitle(left: String, right: String): Boolean {
    fun fold(value: String) = buildString {
        value.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }
    }
    return fold(left) == fold(right)
}

/** Matches the hairline above it, so the two read as one column. */
private val NOVA_GAUGE_WIDTH = 330.dp
private val NOVA_GAUGE_BAR = 4.dp
private val NOVA_GAUGE_NOTCH = 1.5.dp
/** The notches overhang the bar, which is what makes them read as cuts through it. */
private val NOVA_GAUGE_NOTCH_OVERHANG = 2.dp
private val NOVA_GAUGE_CHIP_RADIUS = 5.dp

/** Dissolves the hero band into the window colour instead of cutting against it. */
private fun Modifier.novaFadeToGround(ground: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.62f to Color.Transparent,
                1.0f to ground,
            ),
        ),
    )
}

/**
 * One action in the lane. The primary carries the button it is bound to and an accent
 * gradient; the rest are quiet, hairline-bordered and marked. Focus is a ring and a
 * tint — never a scale or an offset, which is the contract the poster cards settled on.
 */
@Composable
private fun NovaGameDetailAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    glyph: String? = null,
    mark: String? = null,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NovaRadius.hero)

    val background = if (primary && enabled) {
        Brush.linearGradient(
            listOf(
                colors.accent,
                lerp(colors.accent, Color.White, 0.28f),
                lerp(colors.accent, Color.White, 0.62f),
            ),
        )
    } else {
        SolidColor(surfaces.control.copy(alpha = 1f))
    }
    val label = when {
        primary && enabled -> colors.onAccent
        enabled -> colors.textPrimary
        else -> colors.textMuted
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier
            .heightIn(min = NovaGameDetailActionHeight)
            .clip(shape)
            .background(background, shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.accent else surfaces.tileBorder,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = text }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (glyph != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.window.copy(alpha = 0.88f)),
            ) {
                Text(
                    text = glyph,
                    color = colors.textPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (mark != null) {
            Text(text = mark, color = label.copy(alpha = 0.62f), fontSize = 13.sp)
        }
        Text(
            text = text,
            color = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LaunchProfileReviewNotice(
    optimizationState: NovaGameDetailOptimizationState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val summary = optimizationState.profileSummary
    val detail = listOf(
        summary?.noticeDetail,
        summary?.noticeRecommendation,
        summary?.reasonLine,
    ).firstOrNull { !it.isNullOrBlank() }
        ?: stringResource(
            R.string.nova_library_preflight_review_message,
            optimizationState.reviewReason.ifBlank { "fps_override" },
        )

    Column(
        modifier = modifier
            .sizeIn(maxWidth = 520.dp)
            .clip(RoundedCornerShape(NovaRadius.hero))
            .background(colors.warning.copy(alpha = 0.13f))
            .border(1.dp, colors.warning.copy(alpha = 0.46f), RoundedCornerShape(NovaRadius.hero))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("nova-game-detail-review"),
    ) {
        Text(
            text = stringResource(R.string.nova_library_preflight_review_title),
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** Source, when it was last played, and its primary genre — who the game is. */
private fun novaGameDetailIdentityLine(
    sourceLabel: String,
    lastPlayedText: String?,
    game: PolarisGame,
): String = listOf(sourceLabel, lastPlayedText, game.genres.firstOrNull())
    .filter { !it.isNullOrBlank() }
    .joinToString("  ·  ")

/** Mode, profile and freshness — what the primary action will do. */
private fun novaGameDetailStatusText(
    uiState: NovaGameDetailUiState,
    summary: NovaLaunchProfileSummary?,
): String {
    return listOf(
        uiState.hostStreamDisplayModeLabel.takeIf { uiState.playUsesVirtualDisplay },
        summary?.selectedLine,
        summary?.limitingLine?.takeIf { it.isNotBlank() } ?: summary?.freshnessLine,
    ).filter { !it.isNullOrBlank() }.joinToString("  ·  ")
}

/**
 * B unwinds a level, X reaches Tune. A is not repeated here: the primary action already
 * carries it, and this window exists to remove duplication.
 */
@Composable
private fun novaGameDetailOverviewHints(): List<NovaControllerHint> = listOf(
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_b),
        label = stringResource(R.string.nova_controller_hint_close),
    ),
)
