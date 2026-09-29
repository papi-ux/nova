package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRevealingText
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaFocusMotionSpec
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaFocusMotion
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaSplitButtonStyle
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaSplitConfirmState
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.rememberNovaSplitConfirmState

/**
 * The library's continue action, in its two shapes: the home hero, a card of its own above the
 * grid in portrait, and the card inside the landscape strip.
 *
 * Neither cuts its words. The hero's eyebrow, title and lines wrap, and the card grows to hold
 * them; where the actions beside the words would leave the words too narrow to read, as on a
 * phone, the actions go under them. The strip cannot grow, so its card shows words only when the
 * strip has room for them whole (see [NovaTopBarFit]), and otherwise lets them go, never an
 * ellipsis (R13).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun NovaLibraryHeroCard(
    hero: NovaLibraryHeroState,
    compact: Boolean,
    apiClient: PolarisApiClient,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null,
    /**
     * The card opens the game; the buttons do the thing. Without this the running
     * game was the one entry whose detail could not be reached at all, because the
     * whole card resumed and the grid omits it while a session is live.
     */
    onOpenDetail: (() -> Unit)? = null,
    onGameFocused: (PolarisGame) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val heroGame = hero.game
    // The least the card is: it grows past it to hold words that wrap.
    val height = NovaLibraryUiStateMapper.heroHeightDp(compact = compact).dp
    val showCaption = !compact || hero.badges.isEmpty()
    var focused by remember { mutableStateOf(false) }
    // End splits in its own slot; armed, the words step aside so the pair has two halves' width.
    val endSplit = rememberNovaSplitConfirmState()
    val endArmed = endSplit.armed && onSecondaryAction != null
    LaunchedEffect(focused, heroGame) {
        if (focused && heroGame != null) {
            onGameFocused(heroGame)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val padding = if (compact) 8.dp else 16.dp
        val gap = if (compact) 6.dp else 16.dp
        // Beside the words the actions take up to an armed pair's width. Where that would leave the
        // words narrower than a readable column, they go under the words instead, full width.
        val actionsUnder = novaLibraryHeroActionsUnder(
            width = maxWidth,
            padding = padding,
            gap = gap,
            artwork = novaLibraryHeroArtworkWidth(compact),
        )
        val artworkWidth = novaLibraryHeroArtworkWidth(compact)
        // Under its words the cover keeps a poster's shape at the top, and the card is at least
        // as tall as that cover; beside them it is as tall as the card.
        val least = if (actionsUnder) maxOf(height, artworkWidth / NOVA_LIBRARY_HERO_COVER_ASPECT + padding * 2) else height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = least)
                .novaFocusMotion(
                    focused = focused,
                    focusedScale = NovaFocusMotionSpec.CardFocusedScale,
                    haloAlpha = NovaFocusMotionSpec.CardFocusedHaloAlpha,
                    cornerRadius = NovaRadius.hero
                )
                .clip(RoundedCornerShape(NovaRadius.hero))
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
                    shape = RoundedCornerShape(NovaRadius.hero)
                )
                .onFocusChanged {
                    focused = it.isFocused || it.hasFocus
                }
                .combinedClickable(onClick = onOpenDetail ?: onPrimaryAction)
                .focusable()
                .testTag(NOVA_LIBRARY_HERO_TAG),
        ) {
            // The cover stands in the card's own height, which the words and actions set. Asking the
            // row for an intrinsic height instead guessed the words' width, so badges that wrapped
            // at 130% left End Session a sliver at the foot of the card.
            Box(modifier = Modifier.matchParentSize().padding(if (compact) 8.dp else 16.dp)) {
                NovaLibraryHeroArtwork(
                    game = heroGame,
                    apiClient = apiClient,
                    fallbackTitle = hero.artworkFallbackTitle,
                    fallbackSubtitle = hero.artworkFallbackSubtitle,
                    compact = compact,
                    modifier = if (actionsUnder) {
                        Modifier.aspectRatio(NOVA_LIBRARY_HERO_COVER_ASPECT)
                    } else {
                        Modifier.fillMaxHeight()
                    },
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = padding + artworkWidth + gap, top = padding, end = padding, bottom = padding),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!endArmed || actionsUnder) Column(
                    // fill = false so the action sits with the content it belongs to instead of
                    // being pushed to the far edge across a gulf of empty row.
                    modifier = Modifier.weight(1f, fill = actionsUnder),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 5.dp)
                ) {
                    // Every line wraps rather than ending in an ellipsis, and the card grows to hold
                    // it (R13): a title cut short names nothing.
                    if (!endArmed) {
                        val endFailed = hero.endStatus is NovaLibraryEndStatus.Failed
                        // A refused End is said where the eyebrow was, as a sentence, and announced.
                        Text(
                            text = if (endFailed) hero.eyebrow else hero.eyebrow.uppercase(),
                            color = if (endFailed) colors.warning else colors.accent,
                            fontSize = if (compact) 9.sp else 12.sp,
                            lineHeight = if (compact) 11.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = if (endFailed) {
                                Modifier
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                                    .testTag(NOVA_LIBRARY_END_FAILED_TAG)
                            } else {
                                Modifier
                            },
                        )
                        Text(
                            text = hero.title,
                            color = colors.textPrimary,
                            // A phone's column is narrow, so its title is a size smaller there.
                            fontSize = if (compact) 20.sp else if (actionsUnder) 24.sp else 30.sp,
                            lineHeight = if (compact) 22.sp else 34.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (compact && hero.supportingLine.isNotBlank()) {
                            Text(
                                text = hero.supportingLine,
                                color = colors.textSecondary.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                            )
                        }
                        if (!compact) {
                            Text(
                                text = hero.subtitle,
                                color = colors.textSecondary,
                                fontSize = if (compact) 11.sp else 14.sp,
                                lineHeight = if (compact) 13.sp else 16.sp,
                            )
                            if (showCaption) {
                                Text(
                                    text = hero.caption,
                                    color = colors.textSecondary.copy(alpha = 0.86f),
                                    fontSize = if (compact) 11.sp else 13.sp,
                                    lineHeight = if (compact) 13.sp else 15.sp,
                                )
                            }
                        }
                        if (!compact && hero.badges.isNotEmpty()) {
                            // Badges that do not fit a line start another, rather than running off the card.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                hero.badges.take(if (compact) 3 else 4).forEach { badge ->
                                    NovaLibraryHeroBadge(text = badge)
                                }
                            }
                        }
                    }
                    if (actionsUnder) {
                        NovaLibraryHeroActions(
                            hero = hero,
                            compact = compact,
                            endArmed = endArmed,
                            endSplit = endSplit,
                            onPrimaryAction = onPrimaryAction,
                            onSecondaryAction = onSecondaryAction,
                            modifier = Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 6.dp),
                        )
                    }
                }
                if (!actionsUnder) NovaLibraryHeroActions(
                    hero = hero,
                    compact = compact,
                    endArmed = endArmed,
                    endSplit = endSplit,
                    onPrimaryAction = onPrimaryAction,
                    onSecondaryAction = onSecondaryAction,
                    // As wide as its buttons' labels, from one split half up to an armed pair's two, so
                    // the words beside it keep the rest of the row. Armed, End takes the words' room
                    // too, so its pair and the consequence under it have the width two halves need.
                    modifier = if (endArmed) {
                        Modifier.weight(1f)
                    } else {
                        Modifier
                            .widthIn(
                                min = NovaPanelMetrics.SplitHalfMinWidth,
                                max = NovaPanelMetrics.SplitHalfMinWidth * 2 + NovaPanelMetrics.SplitGap,
                            )
                            .width(IntrinsicSize.Max)
                    },
                )
            }
        }
    }
}

/**
 * The hero's two actions, one over the other: Resume, the panel's button so the pair shares one
 * height and one type, over End Session, which splits in its own slot. The split stays at the
 * same place in composition whichever side of the words the actions are on, because moving it
 * would dispose it and so disarm it.
 */
@Composable
private fun NovaLibraryHeroActions(
    hero: NovaLibraryHeroState,
    compact: Boolean,
    endArmed: Boolean,
    endSplit: NovaSplitConfirmState,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
    modifier: Modifier,
) {
    val resumeFocus = remember { FocusRequester() }
    val handoff = rememberNovaEndFocusHandoff(
        endShown = hero.secondaryActionLabel != null && onSecondaryAction != null,
        resume = resumeFocus,
    )
    Column(
        modifier = modifier.then(handoff.group),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)
    ) {
        // The panel's button, as the End split's halves are, so the two stacked buttons
        // share one height and one type.
        if (!endArmed) NovaPanelButton(
            text = hero.actionLabel,
            onClick = onPrimaryAction,
            modifier = Modifier.fillMaxWidth().focusRequester(resumeFocus),
        )
        if (hero.secondaryActionLabel != null && onSecondaryAction != null) {
            NovaSplitConfirm(
                label = hero.secondaryActionLabel,
                confirmLabel = stringResource(R.string.game_dialog_action_end_session),
                onConfirm = onSecondaryAction,
                consequence = stringResource(R.string.nova_panel_end_session_message),
                state = endSplit,
                // As wide as Resume above it, so the pair reads as one column of buttons.
                fillSlot = true,
                modifier = Modifier.fillMaxWidth().then(handoff.end).testTag("nova-library-hero-end"),
            )
        }
    }
}

/** What [rememberNovaEndFocusHandoff] marks: the actions' [group], and [end], End's own slot. */
internal class NovaEndFocusHandoff(val group: Modifier, val end: Modifier)

/**
 * Hands focus to [resume] when End's slot goes while End holds focus. A refused End that asking
 * again cannot turn around, a session another device started or one whose details Nova lost,
 * takes End away (XR3), and the focus it held would have gone with it, leaving no ring. Losing
 * focus to anything else first, while End is still there, cancels the handoff.
 */
@Composable
internal fun rememberNovaEndFocusHandoff(endShown: Boolean, resume: FocusRequester): NovaEndFocusHandoff {
    val shownNow by rememberUpdatedState(endShown)
    val endHeld = remember { mutableStateOf(false) }
    LaunchedEffect(endShown) {
        if (!endShown && endHeld.value) {
            endHeld.value = false
            withFrameNanos { }
            runCatching { resume.requestFocus() }
        }
    }
    return remember(resume) {
        NovaEndFocusHandoff(
            // Read when focus leaves: End going away is not the player moving on.
            group = Modifier.onFocusChanged { if (!it.hasFocus && shownNow) endHeld.value = false },
            end = Modifier.onFocusChanged { if (it.hasFocus) endHeld.value = true },
        )
    }
}

/** The width of the hero's cover. */
internal fun novaLibraryHeroArtworkWidth(compact: Boolean): Dp = if (compact) 58.dp else 108.dp

/**
 * Whether a hero [width] wide puts its actions under its words: beside them, at an armed pair's
 * width, they would leave the words less than [NOVA_LIBRARY_HERO_WORDS_MIN].
 */
internal fun novaLibraryHeroActionsUnder(width: Dp, padding: Dp, gap: Dp, artwork: Dp): Boolean {
    if (width == Dp.Infinity) return false
    val actions = NovaPanelMetrics.SplitHalfMinWidth * 2 + NovaPanelMetrics.SplitGap
    val words = width - padding * 2 - artwork - gap * 2 - actions
    return words < NOVA_LIBRARY_HERO_WORDS_MIN
}

@Composable
private fun NovaLibraryHeroArtwork(
    game: PolarisGame?,
    apiClient: PolarisApiClient,
    fallbackTitle: String,
    fallbackSubtitle: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetGame = game
    if (targetGame == null) {
        NovaLibraryHeroFallbackArtwork(
            title = fallbackTitle,
            subtitle = fallbackSubtitle,
            compact = compact,
            modifier = modifier,
        )
        return
    }

    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(if (compact) NovaRadius.row else NovaRadius.hero)
    Box(
        // The width first, so a cover's shape is taken from it rather than from the row.
        modifier = Modifier
            .width(novaLibraryHeroArtworkWidth(compact))
            .then(modifier)
            .clip(shape)
            .background(surfaces.mediaPlaceholder)
            .border(1.dp, surfaces.tileBorder.copy(alpha = 0.74f * LocalNovaMenuOpacityScale.current), shape)
    ) {
        key(PolarisApiClient.artworkPresentationKey(targetGame, PolarisGame.ARTWORK_KIND_POSTER)) {
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
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(if (compact) NovaRadius.row else NovaRadius.hero)
    Column(
        modifier = Modifier
            .width(novaLibraryHeroArtworkWidth(compact))
            .then(modifier)
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
        )
        // The tile's words wrap inside it, whole (R13).
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = if (compact) 9.sp else 12.sp,
                lineHeight = if (compact) 10.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!compact && subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.textSecondary.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun NovaLibraryHeroBadge(text: String, modifier: Modifier = Modifier) {
    val surfaces = LocalNovaLibrarySurfaces.current
    NovaBadge(
        text = text,
        modifier = modifier,
        color = surfaces.onMedia,
        backgroundColor = surfaces.mediaScrimBottom.copy(alpha = 0.60f),
        borderColor = surfaces.onMedia.copy(alpha = 0.20f),
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 1.dp)
    )
}

/**
 * The continue action as it appears inside the landscape strip: cover, what
 * it is, and the verb. The old standalone card carried an eyebrow, a title,
 * a subtitle, a caption and badges across a full-width panel whose right half
 * was empty; at strip height only the first three earn their place.
 *
 * The strip cannot grow a line, so the words are whole or absent: [fit] says whether the strip
 * has room for the eyebrow on one line and the title on [NovaTopBarFit.continueTitleLines], and
 * gives the eyebrow up first, then the words. Without the title on screen the action still says
 * what it continues.
 */
@Composable
internal fun RowScope.NovaLibraryStripContinue(
    hero: NovaLibraryHeroState,
    apiClient: PolarisApiClient,
    /** What the strip had room for: the cover goes first, then the words, End Session last. */
    fit: NovaTopBarFit,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    // End splits in its own slot (R3). The strip cannot grow a line under it, so while it is
    // armed only Resume steps aside: the pair takes Resume's and End's room, the cover and the
    // title stay so the player sees which game is ending, and the consequence takes the eyebrow's
    // line. Hiding the title and putting the line ahead of the pair had made the pair jump right.
    val endSplit = rememberNovaSplitConfirmState()
    val endArmed = endSplit.armed && onSecondaryAction != null
    // Confirmed: the strip says Ending at once instead of offering Resume and End again for the
    // ten seconds the host takes to answer. The host's answer ends that: a refusal puts Resume back
    // with Try Again in End's slot and the refusal in the eyebrow's line, and the split hands focus
    // back to Try Again as it does after any End. Kept only until then, so a failed End never
    // leaves the strip on Ending with neither action (XR3).
    val endFailed = hero.endStatus is NovaLibraryEndStatus.Failed
    var confirmed by remember(hero.game?.id) { mutableStateOf(false) }
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(hero.endStatus, confirmed) {
        if (confirmed && endFailed) {
            confirmed = false
            // A refusal asking again cannot fix brings no Try Again back to take focus where End
            // was (XR3), so Resume takes it, and the strip keeps a ring.
            if (hero.secondaryActionLabel == null) {
                withFrameNanos { }
                runCatching { resumeFocus.requestFocus() }
            }
        }
    }
    val ending = !endFailed && (confirmed || hero.endStatus is NovaLibraryEndStatus.Ending)
    // A refusal the strip has room for only alone takes the title's place: the reason is the one
    // thing in the strip that says why End went (XR3).
    val titleShown = fit.showContinueText && (!endFailed || endArmed || fit.showContinueEyebrow)
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(start = 4.dp)
            .testTag("nova-library-showcase-continue"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val game = hero.game
        if (game != null && fit.showContinueCover) {
            val shape = RoundedCornerShape(NovaRadius.chip)
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(surfaces.mediaPlaceholder)
                    .border(
                        1.dp,
                        surfaces.tileBorder.copy(alpha = 0.74f * LocalNovaMenuOpacityScale.current),
                        shape,
                    ),
            ) {
                key(PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_POSTER)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(surfaces.mediaPlaceholder.toArgb())
                                contentDescription = context.getString(R.string.nova_a11y_game_cover)
                                apiClient.loadCoverInto(this, game)
                            }
                        },
                        update = { apiClient.loadCoverInto(it, game) },
                    )
                }
            }
        }
        if (fit.showContinueText) Column(
            modifier = Modifier.weight(1f, fill = false).testTag("nova-library-showcase-words"),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // Whole, on the lines the fit measured room for: never an ellipsis (R13).
            if (endArmed) {
                Text(
                    text = stringResource(R.string.nova_library_end_strip_consequence),
                    style = NovaChromeType.label(fontSize = 8.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (endFailed) {
                // The refusal, in the eyebrow's place on the lines the fit measured it for, and
                // announced. Nothing else in the strip says why End went, so the fit never leaves
                // it out: the title gives way first (XR3).
                Text(
                    text = hero.eyebrow,
                    style = NovaChromeType.label(fontSize = 8.sp),
                    color = colors.warning,
                    maxLines = fit.continueEyebrowLines,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(NOVA_LIBRARY_END_FAILED_TAG),
                )
            } else if (fit.showContinueEyebrow) {
                Text(
                    text = hero.eyebrow.uppercase(),
                    style = NovaChromeType.label(fontSize = 8.sp),
                    color = colors.accent,
                )
            }
            if (titleShown) {
                Text(
                    text = hero.title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = fit.continueTitleLines,
                )
            }
        }
        if (ending) {
            Text(
                text = stringResource(R.string.nova_library_ending_session),
                style = novaPanelType.caption,
                color = colors.textSecondary,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("nova-library-showcase-ending"),
            )
        } else if (!endArmed) NovaActionSurface(
            onClick = onPrimaryAction,
            modifier = Modifier.widthIn(min = 88.dp).focusRequester(resumeFocus),
            // The card's own action is the next step, so it carries the accent; End Session stays quiet.
            primary = true,
            // Without the title on screen, left out or given way to a refusal, the action still
            // says what it continues.
            contentDescription = if (titleShown) hero.actionLabel else "${hero.actionLabel}, ${hero.title}",
            minHeight = NOVA_LIBRARY_STRIP_BUTTON_HEIGHT,
        ) { contentColor, focused ->
            // One line, whole at the width the strip's fit measured for it. The strip is one fixed
            // height and the button only as tall, so a label that wrapped had its second line cut
            // through (C25). Where a large font still overruns the line, the rest shows while the
            // button has focus.
            val label = MaterialTheme.typography.labelLarge
            NovaRevealingText(
                text = hero.actionLabel,
                highlighted = focused,
                maxLines = 1,
                color = contentColor,
                fontSize = 10.sp,
                lineHeight = label.lineHeight,
                fontWeight = FontWeight.SemiBold,
                style = label.copy(textAlign = TextAlign.Center),
            )
        }
        val secondaryLabel = hero.secondaryActionLabel
        if (secondaryLabel != null && onSecondaryAction != null && fit.showContinueSecondary && !ending) {
            NovaSplitConfirm(
                label = secondaryLabel,
                confirmLabel = stringResource(R.string.game_dialog_action_end_session),
                onConfirm = {
                    confirmed = true
                    onSecondaryAction()
                },
                state = endSplit,
                // Resume's type and height, so the strip holds one size of button, not two.
                buttonStyle = novaLibraryStripButtonStyle(),
                modifier = Modifier.testTag("nova-library-showcase-end"),
            )
        }
    }
}

/**
 * The strip's buttons: Resume's 10sp label in the button face, 30dp tall, with its padding. End
 * Session takes the same, with a 14dp mark, so the strip holds one size of button.
 */
@Composable
internal fun novaLibraryStripButtonStyle(): NovaSplitButtonStyle = NovaSplitButtonStyle(
    text = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
    minHeight = NOVA_LIBRARY_STRIP_BUTTON_HEIGHT,
    padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    iconSize = NOVA_LIBRARY_STRIP_BUTTON_ICON,
)

internal val NOVA_LIBRARY_STRIP_BUTTON_HEIGHT = 30.dp
internal val NOVA_LIBRARY_STRIP_BUTTON_ICON = 14.dp

/** The home hero, for a test to find it. */
internal const val NOVA_LIBRARY_HERO_TAG = "nova-library-hero"

/** The line that says an End from the library was refused, for a test to find it. */
internal const val NOVA_LIBRARY_END_FAILED_TAG = "nova-library-end-failed"

/** The narrowest the hero's words may be beside its actions before the actions go under them. */
internal val NOVA_LIBRARY_HERO_WORDS_MIN = 200.dp

/** The cover's shape when it sits at the top of a tall hero: the poster's, as in the grid. */
private const val NOVA_LIBRARY_HERO_COVER_ASPECT = 108f / 152f
