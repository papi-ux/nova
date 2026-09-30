package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.papi.nova.ui.panel.NovaPanelMetrics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaSplitConfirmState
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.rememberNovaSplitConfirmState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRadius
import kotlinx.coroutines.Job
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSpaces
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt


@Composable
internal fun NovaLibraryLandscapeStageShell(
    modifier: Modifier = Modifier,
    reserveControllerHintSpace: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hintLineHeight = with(LocalDensity.current) { LocalTextStyle.current.lineHeight.toDp() }
    val bottomPaddingDp = if (reserveControllerHintSpace) {
        maxOf(NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = true),
            (hintLineHeight.value + 12f).roundToInt())
    } else {
        0
    }
    Column(
        modifier = modifier
            .padding(bottom = bottomPaddingDp.dp)
            .testTag("nova-stage-production-shell"),
        verticalArrangement = Arrangement.spacedBy(
            NovaLibraryUiStateMapper.landscapeContentSpacingDp().dp,
        ),
    ) {
        content()
    }
}

@Composable
internal fun NovaLibraryLandscapeToolbarContent(
    hostLabel: String,
    resultCount: Int,
    layoutLabel: String,
    polarisReady: Boolean,
    cinematic: Boolean = false,
    onOpenOptions: () -> Unit,
    onOpenSystemMenu: () -> Unit,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val shape = RoundedCornerShape(if (cinematic) NovaRadius.row else NovaRadius.hero)
    val toolbarColor = if (cinematic) {
        surfaces.panel.copy(alpha = 0.34f * LocalNovaMenuOpacityScale.current)
    } else {
        surfaces.panel.copy(alpha = 0.72f * LocalNovaMenuOpacityScale.current)
    }
    val toolbarBorder = if (cinematic) {
        surfaces.tileBorder
    } else {
        surfaces.tileBorder
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NovaLibraryUiStateMapper.landscapeToolbarHeightDp(largeText).dp)
            .clip(shape)
            .background(toolbarColor)
            .border(1.dp, toolbarBorder, shape)
            .padding(horizontal = 10.dp, vertical = 5.5.dp)
            .testTag("nova-library-landscape-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NovaLibraryToolbarIdentity(
            hostLabel = hostLabel,
            cinematic = cinematic,
            modifier = Modifier.widthIn(min = 132.dp, max = 240.dp),
            statusContent = {
                if (polarisReady) {
                    Text(
                        text = stringResource(R.string.nova_system_menu_status_polaris_ready),
                        color = LocalNovaComposeColors.current.textSecondary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
        Spacer(modifier = Modifier.weight(1f))
        NovaLibraryResultAndLayoutMeta(
            resultCount = resultCount,
            layoutLabel = layoutLabel,
            cinematic = cinematic,
        )
        NovaLibraryToolbarOptionsAction(
            largeText = largeText,
            primary = !cinematic,
            onClick = onOpenOptions,
        )
        NovaLibraryToolbarSystemAction(
            onClick = onOpenSystemMenu,
        )
    }
}

@Composable
internal fun NovaLibraryPortraitToolbarContent(
    hostLabel: String,
    resultCount: Int,
    layoutLabel: String,
    polarisReady: Boolean,
    identityStatus: @Composable () -> Unit = {},
    onOpenOptions: () -> Unit,
    onOpenSystemMenu: () -> Unit,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (largeText) 74.dp else 60.dp)
            .testTag("nova-library-portrait-toolbar"),
    ) {
        val showMetadata = !largeText && maxWidth >= 400.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(NovaRadius.hero))
                .background(surfaces.panel.copy(alpha = 0.72f * LocalNovaMenuOpacityScale.current))
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.hero))
                .padding(horizontal = 10.dp, vertical = 5.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovaLibraryToolbarIdentity(
                hostLabel = hostLabel,
                cinematic = false,
                modifier = Modifier.weight(1f),
                statusContent = identityStatus,
            )
            if (showMetadata) {
                NovaLibraryResultAndLayoutMeta(
                    resultCount = resultCount,
                    layoutLabel = layoutLabel,
                    cinematic = false,
                )
            }
            NovaLibraryToolbarOptionsAction(
                largeText = largeText,
                primary = true,
                onClick = onOpenOptions,
            )
            NovaLibraryToolbarSystemAction(
                onClick = onOpenSystemMenu,
            )
        }
    }
}

/**
 * Landscape draws the host, a card when there is something to act on now, the Space control and the
 * two menu buttons in one row.
 *
 * A toolbar stacked above a continue card spent the width twice. The toolbar
 * carried an empty gap almost two thirds of the screen wide between the host
 * name and the count, and the card stopped at the halfway mark, so two
 * full-width strips were each about half empty and the poster grid was left with
 * one row. The continue slot sits in the gap the toolbar already had.
 *
 * When there is nothing to continue the slot is absent rather than blank, and
 * identity and metadata reflow across the space instead of holding it open.
 *
 * The row never scrolls. It used to be forced to 700 dp, or 980 dp with a continue card, times the
 * font scale inside a horizontal scroll, so on an 815 dp Retroid strip every Grid or Compact library
 * with Spaces, and Stage with enlarged text, slid Options and System past the right edge. Now the
 * parts are measured and [novaLibraryTopBarFit] leaves out what it must, in a fixed order, and the
 * Space control sits in the right-hand cluster directly left of Options instead of floating beside
 * the host.
 *
 * Three things changed after papi saw that on the Retroid (2026-09-16 21:27). The result count and
 * layout name left the strip for the Options sheet, where they already were. The Space control is a
 * control even when this device has nowhere else to go, so pressing it always reaches the chooser,
 * which says why. And the card only appears for something to act on now (a live game, an empty
 * library, filters that hide everything), never as a copy of the game selected in the grid.
 */
@Composable
internal fun NovaLibraryLandscapeShowcaseStripContent(
    hostLabel: String,
    polarisReady: Boolean,
    onOpenOptions: () -> Unit,
    onOpenSystemMenu: () -> Unit,
    continueSlot: (@Composable RowScope.(NovaTopBarFit) -> Unit)? = null,
    /** The continue card's words, so the strip can measure the card before drawing it. */
    continueCard: NovaTopBarContinue? = null,
    environments: PolarisSpaces? = null,
    environmentEnabled: Boolean = true,
    environmentStatusKnown: Boolean = true,
    environmentChanging: Boolean = false,
    onChooseEnvironment: () -> Unit = {},
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val shape = RoundedCornerShape(NovaRadius.row)
    val hostStatus = if (polarisReady) stringResource(R.string.nova_system_menu_status_polaris_ready) else null
    val environment = environments?.let {
        rememberNovaEnvironmentStrings(it, environmentStatusKnown, environmentChanging)
    }
    // A host name too long for its place drops its domain before anything else gives way:
    // "living-room-gaming-pc", not "living-room-gaming-pc.papi..." (R13).
    val hostMeasurer = rememberTextMeasurer()
    val hostDensity = LocalDensity.current
    val hostStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 11.sp, lineHeight = 13.sp))
    val shownHost = remember(hostLabel, hostStyle, hostDensity) {
        val full = with(hostDensity) {
            hostMeasurer.measure(hostLabel, hostStyle, softWrap = false, maxLines = 1).size.width.toDp().value
        }
        if (full > NOVA_TOP_BAR_IDENTITY_CAP) novaShortHostLabel(hostLabel) else hostLabel
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = rememberNovaLibraryTopBarFit(
            available = maxWidth - NOVA_TOP_BAR_HORIZONTAL_PADDING * 2,
            largeText = largeText,
            hostLabel = shownHost,
            hostStatus = hostStatus,
            environment = environment,
            continueCard = if (continueSlot != null) continueCard else null,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NovaLibraryUiStateMapper.landscapeShowcaseStripHeightDp(largeText).dp)
                .clip(shape)
                .background(surfaces.panel.copy(alpha = 0.34f * LocalNovaMenuOpacityScale.current))
                .border(1.dp, surfaces.tileBorder, shape)
                .padding(horizontal = NOVA_TOP_BAR_HORIZONTAL_PADDING, vertical = 5.5.dp)
                .testTag("nova-library-landscape-toolbar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NOVA_TOP_BAR_GAP),
        ) {
            // The host and what is running take whatever the right-hand cluster leaves: Row
            // measures unweighted children first, so Options and System always get their width.
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NOVA_TOP_BAR_GAP),
            ) {
                NovaLibraryToolbarIdentity(
                    hostLabel = shownHost,
                    cinematic = true,
                    modifier = Modifier.widthIn(max = minOf(NOVA_TOP_BAR_IDENTITY_CAP, fit.identityMax).dp),
                    statusContent = {
                        if (hostStatus != null && fit.showHostStatus) {
                            Text(
                                text = hostStatus,
                                color = LocalNovaComposeColors.current.textSecondary,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
                if (continueSlot != null) {
                    continueSlot(fit)
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (environments != null) {
                // Directly left of Options, with the same gap as Options and System: the Space is
                // something to change, like the menus beside it. Sized to its content beside the
                // host, it read as floating in the middle of the strip.
                NovaEnvironmentBar(
                    spaces = environments,
                    enabled = environmentEnabled,
                    statusKnown = environmentStatusKnown,
                    changing = environmentChanging,
                    onChoose = onChooseEnvironment,
                    modifier = Modifier.widthIn(max = minOf(280f * fontScale, fit.spaceWidth).dp),
                    compact = true,
                    showCaption = fit.showSpaceCaption,
                    compactStatus = fit.compactSpaceStatus,
                    showName = fit.showSpaceName,
                )
            }
            NovaLibraryToolbarOptionsAction(
                largeText = largeText,
                primary = false,
                onClick = onOpenOptions,
            )
            NovaLibraryToolbarSystemAction(
                onClick = onOpenSystemMenu,
            )
        }
    }
}

/** The mapper owns the inset, because the poster grid lines its artwork up with it. */
private val NOVA_TOP_BAR_HORIZONTAL_PADDING = NovaLibraryUiStateMapper.libraryBarContentInsetDp().dp
private val NOVA_TOP_BAR_GAP = 8.dp
private const val NOVA_TOP_BAR_IDENTITY_CAP = 168f
private const val NOVA_TOP_BAR_IDENTITY_FLOOR = 56f
private const val NOVA_TOP_BAR_VERTICAL_PADDING = 5.5f
/** NovaActionButton's horizontal content padding, both sides. */
private const val NOVA_TOP_BAR_BUTTON_PADDING = 24f
/** Rounding headroom between what is measured here and what the row lays out. */
private const val NOVA_TOP_BAR_SLACK = 6f

/**
 * What the strip's fit asks of its words, in pixels: how wide one line of [text] is, how tall, and
 * how many lines it takes at [maxWidthPx]. The strip measures with its own text measurer; a test
 * gives it a device's glyph widths, which Robolectric does not have.
 */
internal interface NovaTopBarTextMeasure {
    fun width(text: String, style: TextStyle): Int
    fun height(text: String, style: TextStyle): Int
    fun lines(text: String, style: TextStyle, maxWidthPx: Int): Int
}

private class NovaTopBarTextMeasurer(private val measurer: TextMeasurer) : NovaTopBarTextMeasure {
    override fun width(text: String, style: TextStyle): Int =
        measurer.measure(text, style, softWrap = false, maxLines = 1).size.width

    override fun height(text: String, style: TextStyle): Int =
        measurer.measure(text, style, softWrap = false, maxLines = 1).size.height

    override fun lines(text: String, style: TextStyle, maxWidthPx: Int): Int =
        measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidthPx)).lineCount
}

/**
 * Measure the strip's parts at the current font scale, with the same styles the composables draw
 * them in, and decide what the row leaves out ([novaLibraryTopBarMeasuredFit]).
 */
@Composable
private fun rememberNovaLibraryTopBarFit(
    available: Dp,
    largeText: Boolean,
    hostLabel: String,
    hostStatus: String?,
    environment: NovaEnvironmentStrings?,
    continueCard: NovaTopBarContinue?,
): NovaTopBarFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    val buttonStyle = MaterialTheme.typography.labelLarge
    val optionsLabel = stringResource(R.string.nova_controller_hint_options)
    val systemLabel = stringResource(R.string.nova_system_menu_title)
    // End Session is a split confirm in the strip's own button style, with its mark.
    val splitStyle = novaLibraryStripButtonStyle().text
    return remember(
        available, largeText, hostLabel, hostStatus, environment, continueCard,
        density, base, buttonStyle, optionsLabel, systemLabel, splitStyle,
    ) {
        novaLibraryTopBarMeasuredFit(
            measure = NovaTopBarTextMeasurer(measurer),
            density = density,
            available = available,
            largeText = largeText,
            hostLabel = hostLabel,
            hostStatus = hostStatus,
            environment = environment,
            continueCard = continueCard,
            base = base,
            buttonStyle = buttonStyle,
            splitStyle = splitStyle,
            optionsLabel = optionsLabel,
            systemLabel = systemLabel,
        )
    }
}

/**
 * The strip's fit from its words as [measure] measures them at [density]: what the row leaves
 * out, and the lines the continue card's words have. Everything the fit needs is text; the rest is
 * the padding, avatar and gaps the strip's composables use. A refused End's reason is the one set
 * of words the fit never leaves out, and it keeps the lines the strip has room for (XR3).
 */
internal fun novaLibraryTopBarMeasuredFit(
    measure: NovaTopBarTextMeasure,
    density: Density,
    available: Dp,
    largeText: Boolean,
    hostLabel: String,
    hostStatus: String?,
    environment: NovaEnvironmentStrings?,
    continueCard: NovaTopBarContinue?,
    base: TextStyle,
    buttonStyle: TextStyle,
    splitStyle: TextStyle,
    optionsLabel: String,
    systemLabel: String,
): NovaTopBarFit {
    if (available == Dp.Infinity || available <= 0.dp) return NovaTopBarFit()
    return with(density) {
        fun width(text: String, style: TextStyle): Float =
            if (text.isEmpty()) 0f else measure.width(text, style).toDp().value
        fun button(text: String, size: TextUnit): Float =
            width(text, buttonStyle.merge(TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold))) + NOVA_TOP_BAR_BUTTON_PADDING
        val small = base.merge(TextStyle(fontSize = 10.sp, lineHeight = 12.sp))
        val iconScale = fontScale.coerceIn(1f, 1.6f)
        // The card's words, whole: the eyebrow on one line and the title on the lines the
        // strip's height leaves it, with the eyebrow or without it.
        val eyebrowStyle = NovaChromeType.label(fontSize = 8.sp)
        val titleStyle = base.merge(TextStyle(fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))
        val stripInside = (NovaLibraryUiStateMapper.landscapeShowcaseStripHeightDp(largeText) - 2 * NOVA_TOP_BAR_VERTICAL_PADDING).dp.toPx()
        val titleLine = titleStyle.lineHeight.toPx()
        val eyebrowHeight = continueCard?.eyebrow?.takeIf { it.isNotBlank() }
            ?.let { measure.height(it.uppercase(), eyebrowStyle).toFloat() }
            ?: 0f
        val titleLinesUnderEyebrow = novaTopBarTitleLines(stripInside - eyebrowHeight - NOVA_TOP_BAR_WORDS_GAP.dp.toPx(), titleLine)
        val titleLinesAlone = novaTopBarTitleLines(stripInside, titleLine).coerceAtLeast(1)
        // A refused End's reason, in the eyebrow's type: the lines it has above one title line,
        // and the lines it has alone once the title gives way (XR3).
        val refusal = continueCard?.refusal == true
        val eyebrowLine = measure.height("Ag", eyebrowStyle).toFloat()
        val refusalLinesOverTitle = novaTopBarRefusalLines(stripInside - titleLine - NOVA_TOP_BAR_WORDS_GAP.dp.toPx(), eyebrowLine)
        val refusalLinesAlone = novaTopBarRefusalLines(stripInside, eyebrowLine).coerceAtLeast(1)
        // The narrowest width at which [text] takes no more than [lines] lines, never narrower
        // than its longest word, which would be broken in two.
        fun wordsWidth(text: String, style: TextStyle, lines: Int): Float {
            val single = width(text, style)
            if (lines <= 1 || single == 0f) return single
            var narrow = text.split(' ').maxOf { width(it, style) }
            var wide = single
            repeat(NOVA_TOP_BAR_TITLE_SEARCH_STEPS) {
                val middle = (narrow + wide) / 2f
                val laid = measure.lines(text, style, maxWidthPx = middle.dp.roundToPx().coerceAtLeast(1))
                if (laid <= lines) wide = middle else narrow = middle
            }
            return wide
        }
        fun titleWidth(title: String, lines: Int): Float = wordsWidth(title, titleStyle, lines)
        val fit = novaLibraryTopBarFit(
            NovaTopBarWidths(
                available = available.value,
                gap = NOVA_TOP_BAR_GAP.value,
                slack = NOVA_TOP_BAR_SLACK,
                hostName = width(hostLabel, base.merge(TextStyle(fontSize = 11.sp, lineHeight = 13.sp))),
                hostStatus = hostStatus?.let { width(it, small) } ?: 0f,
                identityCap = NOVA_TOP_BAR_IDENTITY_CAP,
                identityFloor = NOVA_TOP_BAR_IDENTITY_FLOOR,
                space = environment?.let { env ->
                    // NovaEnvironmentBar, compact: 8 + 10 dp surface padding, a 28 dp avatar
                    // scaled with text, 8 dp gaps and the 20 sp chevron it always draws.
                    val chevron = width("›", base.merge(TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))) + 8f
                    NovaTopBarSpaceWidths(
                        chrome = 18f + 28f * iconScale + chevron,
                        columnGap = 8f,
                        caption = width(env.caption, small),
                        name = width(env.name, base.merge(TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))),
                        status = env.status?.let { width(it, base.merge(TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium))) + 16f } ?: 0f,
                        statusDot = 8f,
                        statusGap = 6f,
                        cap = 280f * fontScale.coerceAtLeast(1f),
                    )
                },
                continueCard = continueCard?.let { card ->
                    // NovaLibraryStripContinue: 4 dp start padding, a square cover as tall
                    // as the strip's inside, 7 dp gaps, and actions at least 88 and 72 dp wide.
                    // Where no title line fits under the eyebrow, the words need more than any
                    // strip has, so the eyebrow is always the first to go.
                    val text = when {
                        // The reason over one title line, or, failing that, alone on every line.
                        card.refusal && refusalLinesOverTitle > 0 ->
                            maxOf(wordsWidth(card.eyebrow, eyebrowStyle, refusalLinesOverTitle), titleWidth(card.title, 1))
                        card.refusal -> available.value * 2
                        titleLinesUnderEyebrow > 0 ->
                            maxOf(width(card.eyebrow.uppercase(), eyebrowStyle), titleWidth(card.title, titleLinesUnderEyebrow))
                        else -> available.value * 2
                    }
                    NovaTopBarContinueWidths(
                        padding = 4f,
                        cover = if (card.hasCover) {
                            NovaLibraryUiStateMapper.landscapeShowcaseStripHeightDp(largeText) - 2 * NOVA_TOP_BAR_VERTICAL_PADDING
                        } else {
                            0f
                        },
                        textMin = text + NOVA_TOP_BAR_WORDS_ROOM,
                        titleMin = if (card.refusal) {
                            wordsWidth(card.eyebrow, eyebrowStyle, refusalLinesAlone)
                        } else {
                            titleWidth(card.title, titleLinesAlone)
                        } + NOVA_TOP_BAR_WORDS_ROOM,
                        keepsText = card.refusal,
                        gap = 7f,
                        primary = maxOf(88f, button(card.actionLabel, 10.sp)),
                        secondary = card.secondaryActionLabel?.let {
                            width(it, splitStyle) + NOVA_TOP_BAR_BUTTON_PADDING +
                                NOVA_LIBRARY_STRIP_BUTTON_ICON.value + NovaPanelMetrics.SpaceXs.value
                        } ?: 0f,
                    )
                },
                options = button(optionsLabel, 11.sp),
                system = button(systemLabel, 10.sp),
            ),
        )
        fit.copy(
            continueTitleLines = when {
                refusal -> 1
                fit.showContinueEyebrow -> titleLinesUnderEyebrow.coerceAtLeast(1)
                else -> titleLinesAlone
            },
            continueEyebrowLines = when {
                !refusal -> 1
                fit.showContinueEyebrow -> refusalLinesOverTitle.coerceAtLeast(1)
                else -> refusalLinesAlone
            },
        )
    }
}

/**
 * How many title lines of [linePx] a room [roomPx] tall holds, at most two: a third line in a strip
 * reads as a paragraph where a name should be.
 */
internal fun novaTopBarTitleLines(roomPx: Float, linePx: Float): Int {
    if (linePx <= 0f || roomPx <= 0f) return 0
    return (roomPx / linePx).toInt().coerceIn(0, NOVA_TOP_BAR_TITLE_LINES_MAX)
}

private const val NOVA_TOP_BAR_TITLE_LINES_MAX = 2

/**
 * How many lines of [linePx] a refused End's reason has in a room [roomPx] tall, at most three: it
 * is a sentence, where the title is a name.
 */
internal fun novaTopBarRefusalLines(roomPx: Float, linePx: Float): Int {
    if (linePx <= 0f || roomPx <= 0f) return 0
    return (roomPx / linePx).toInt().coerceIn(0, NOVA_TOP_BAR_REFUSAL_LINES_MAX)
}

private const val NOVA_TOP_BAR_REFUSAL_LINES_MAX = 3
/** The eyebrow and the title stand this far apart in the card, in dp. */
private const val NOVA_TOP_BAR_WORDS_GAP = 1f
/** Rounding room on the words' measured width, so what fits here also wraps the same way there. */
private const val NOVA_TOP_BAR_WORDS_ROOM = 2f
private const val NOVA_TOP_BAR_TITLE_SEARCH_STEPS = 10

@Composable
private fun NovaLibraryToolbarIdentity(
    hostLabel: String,
    cinematic: Boolean,
    modifier: Modifier,
    statusContent: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = modifier.testTag("nova-library-toolbar-identity"),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cinematic toolbar leads with the host: "Library" restates what the
            // whole screen already is, and the panel needs the weight back.
            if (!cinematic) {
                Text(
                    text = stringResource(R.string.nova_library_title),
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (cinematic) hostLabel else "· $hostLabel",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        statusContent()
    }
}

@Composable
private fun NovaLibraryResultAndLayoutMeta(
    resultCount: Int,
    layoutLabel: String,
    cinematic: Boolean,
) {
    val colors = LocalNovaComposeColors.current
    Row(
        modifier = Modifier
            .widthIn(max = 132.dp)
            .testTag("nova-library-toolbar-meta"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.nova_library_results_format, resultCount),
            color = colors.textSecondary,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = layoutLabel,
            color = colors.accent,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NovaLibraryToolbarOptionsAction(
    largeText: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val optionsLabel = stringResource(R.string.nova_library_options_title)
    NovaActionButton(
        text = stringResource(R.string.nova_controller_hint_options),
        modifier = Modifier.testTag("nova-library-toolbar-options"),
        contentDescription = optionsLabel,
        onClick = onClick,
        primary = primary,
        minHeight = 48.dp,
        fontSize = 11.sp,
    )
}

@Composable
private fun NovaLibraryToolbarSystemAction(onClick: () -> Unit) {
    NovaActionButton(
        text = stringResource(R.string.nova_system_menu_title),
        modifier = Modifier.testTag("nova-library-toolbar-system-menu"),
        onClick = onClick,
        minHeight = 48.dp,
        fontSize = 10.sp,
    )
}

@Composable
internal fun NovaLibraryStage(
    games: List<PolarisGame>,
    focusedGame: PolarisGame?,
    restoreFocusGameId: String?,
    primaryActionLabel: String,
    sessionTitle: String? = null,
    sessionSupportingLine: String? = null,
    sessionActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    apiClient: PolarisApiClient,
    showPosterTitles: Boolean,
    onPrimaryAction: () -> Unit,
    onSessionAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    /** Why the host refused an End, said under the hero's title until the End status clears (XR3). */
    endRefusal: String? = null,
    endPending: Boolean = false,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit,
    artworkLoader: (ImageView, PolarisGame, String) -> Unit = { view, game, artworkKind ->
        apiClient.loadArtworkInto(view, game, artworkKind)
    },
    posterLoader: (ImageView, PolarisGame) -> Unit = { view, game ->
        apiClient.loadCoverInto(view, game)
    }
) {
    val selected = if (sessionTitle != null && focusedGame == null) {
        null
    } else {
        focusedGame ?: games.firstOrNull()
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("nova-library-stage")) {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val verticalGrid = maxHeight > maxWidth
        val footerHeightDp = if (verticalGrid) {
            0
        } else {
            NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp()
        }
        val spec = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = maxWidth.value.toInt(),
            heightDp = (maxHeight.value.toInt() - footerHeightDp).coerceAtLeast(0),
            largeText = largeText,
        )

        if (selected != null) {
            NovaLibraryStageHero(
                game = selected,
                heightDp = spec.stageHeroHeightDp,
                largeText = largeText,
                compact = spec.stageUsesCompactHero,
                primaryActionLabel = primaryActionLabel,
                sessionActionLabel = sessionActionLabel,
                secondaryActionLabel = secondaryActionLabel,
                artworkLoader = artworkLoader,
                onPrimaryAction = onPrimaryAction,
                onSessionAction = onSessionAction,
                onSecondaryAction = onSecondaryAction,
                endRefusal = endRefusal,
                endPending = endPending,
            )
        } else if (sessionTitle != null && sessionActionLabel != null && onSessionAction != null) {
            NovaLibraryStageSessionHero(
                title = sessionTitle,
                supportingLine = sessionSupportingLine,
                heightDp = spec.stageHeroHeightDp,
                largeText = largeText,
                compact = spec.stageUsesCompactHero,
                actionLabel = sessionActionLabel,
                secondaryActionLabel = secondaryActionLabel,
                onAction = onSessionAction,
                onSecondaryAction = onSecondaryAction,
                endRefusal = endRefusal,
                endPending = endPending,
            )
        }

        if (spec.stageUsesVerticalGrid) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(spec.stagePosterRailHeightDp.dp),
            ) {
                NovaLibraryStagePosterGrid(
                    games = games,
                    apiClient = apiClient,
                    columns = spec.stagePosterColumns,
                    heightDp = spec.stagePosterRailHeightDp,
                    restoreFocusGameId = restoreFocusGameId,
                    showPosterTitles = showPosterTitles,
                    posterLoader = posterLoader,
                    onGameFocused = onGameFocused,
                    onOpenDetail = onOpenDetail,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height((spec.stagePosterRailHeightDp + footerHeightDp).dp)
                    .padding(bottom = footerHeightDp.dp),
            ) {
                NovaLibraryStageRow(
                    games = games,
                    apiClient = apiClient,
                    isLandscape = true,
                    posterColumns = spec.stagePosterColumns,
                    restoreFocusGameId = restoreFocusGameId,
                    showPosterTitles = showPosterTitles,
                    onGameFocused = onGameFocused,
                    onOpenDetail = onOpenDetail,
                    coverLoader = posterLoader,
                )
            }
        }
    }
}

@Composable
private fun NovaLibraryStageSessionHero(
    title: String,
    supportingLine: String?,
    heightDp: Int,
    largeText: Boolean,
    compact: Boolean,
    actionLabel: String,
    secondaryActionLabel: String?,
    onAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
    endRefusal: String? = null,
    endPending: Boolean = false,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(surfaces.mediaPlaceholder)
            .testTag("nova-stage-session-only-hero"),
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(if (compact) 8.dp else 14.dp),
        ) {
            Text(
                text = title,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = when {
                    compact && largeText -> 18.sp
                    compact -> 20.sp
                    else -> 24.sp
                },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("nova-stage-session-title"),
            )
            if (endRefusal != null) {
                NovaStageEndRefusal(endRefusal, compact)
            } else if (!compact && !supportingLine.isNullOrBlank()) {
                Text(
                    text = supportingLine,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // End splits in its own slot; armed, Resume steps aside so the pair has its room.
            val endSplit = rememberNovaSplitConfirmState()
            val resumeFocus = remember { FocusRequester() }
            val handoff = rememberNovaEndFocusHandoff(
                endShown = secondaryActionLabel != null && onSecondaryAction != null,
                resume = resumeFocus,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = handoff.group) {
                if (endPending) NovaLibraryEndingNotice() else {
                    if (!endSplit.armed) NovaStageHeroAction(
                        label = actionLabel,
                        emphasized = true,
                        testTag = "nova-stage-session-action",
                        onClick = onAction,
                        modifier = Modifier.focusRequester(resumeFocus),
                    )
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        NovaStageEndAction(label = secondaryActionLabel, state = endSplit, onConfirm = onSecondaryAction, modifier = handoff.end)
                    }
                }
            }
        }
    }
}

/** Cards sit flat and evenly spaced; the focused card is distinguished by scale,
 *  opacity and lift rather than by crowding its neighbours. Poster width itself
 *  comes from [NovaLibraryUiStateMapper.stageRailPosterWidthDp]. */
private val NovaStageCarouselGapDp = 12.dp

/** How close (in card widths) the focused poster may come to a rail edge before the rail
 *  scrolls. Inside that band the rail holds still and the selection travels across
 *  stationary posters, which is what keeps a sense of place in a long library. */
private const val NovaStageEdgeScrollMarginCards = 1.15f

/**
 * Edge-scrolling policy for the poster rail.
 *
 * Compose already asks the scrollable to bring a newly focused child into view; the default
 * spec scrolls the minimum needed, which re-seats the selection against the viewport edge on
 * every step. Supplying the spec — rather than running a second scroller next to it — keeps a
 * single scroll authority and lets the rail stay put until the selection nears an edge.
 */
@OptIn(ExperimentalFoundationApi::class)
private object NovaStageEdgeScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val margin = (size * NovaStageEdgeScrollMarginCards)
            .coerceAtMost((containerSize - size) / 2f)
            .coerceAtLeast(0f)
        val trailingEdge = offset + size
        return when {
            offset < margin -> offset - margin
            trailingEdge > containerSize - margin -> trailingEdge - (containerSize - margin)
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovaLibraryStageHero(
    game: PolarisGame,
    heightDp: Int,
    largeText: Boolean,
    compact: Boolean,
    primaryActionLabel: String,
    sessionActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    artworkLoader: (ImageView, PolarisGame, String) -> Unit,
    onPrimaryAction: () -> Unit,
    onSessionAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    endRefusal: String? = null,
    endPending: Boolean = false,
) {
    val heroColors = LocalNovaComposeColors.current
    val hasIcon = game.iconArtwork != null
    val iconKey = PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_ICON)
    // No scrim here: NovaLibraryCinematicBackdrop is the single owner of the stage
    // gradients, and stacking a second one over it crushed the hero artwork.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .testTag("nova-stage-hero"),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 4.dp else 16.dp)
                .testTag("nova-stage-identity"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasIcon) {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                        },
                        update = { view ->
                            if (view.getTag(R.id.nova_artwork_presentation_key) != iconKey) {
                                view.setTag(R.id.nova_artwork_presentation_key, iconKey)
                                view.setImageDrawable(null)
                                artworkLoader(view, game, PolarisGame.ARTWORK_KIND_ICON)
                            }
                        },
                        modifier = Modifier
                            .size(if (compact) 32.dp else 40.dp)
                            .clip(RoundedCornerShape(NovaRadius.row))
                            .testTag("nova-stage-icon"),
                    )
                }
                Text(
                    text = game.name,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = when {
                        compact -> 20.sp
                        largeText -> 26.sp
                        else -> 24.sp
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("nova-stage-title"),
                )
            }
            // End splits in its own slot; armed, the other actions and the metadata line step aside
            // so the pair and its consequence line fit the hero's height.
            val endSplit = rememberNovaSplitConfirmState()
            val endArmed = endSplit.armed && secondaryActionLabel != null && onSecondaryAction != null
            val heroMetadata = stageHeroMetadata(game)
            if (endRefusal != null && !endArmed) {
                // A refused End, in the metadata line's place under the title, and whole.
                NovaStageEndRefusal(endRefusal, compact)
            } else if (heroMetadata.isNotBlank() && !largeText && !endArmed) {
                Text(
                    text = heroMetadata,
                    color = heroColors.textSecondary,
                    style = NovaChromeType.label(fontSize = if (compact) 9.sp else 10.sp, letterSpacing = 0.16.em),
                    lineHeight = if (compact) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = if (compact) 2.dp else 6.dp)
                        .testTag("nova-stage-metadata"),
                )
            }
            // Where a refused End leaves no Try Again, focus goes to Resume, or to the primary when
            // there is no session action (XR3).
            val resumeFocus = remember { FocusRequester() }
            val hasSessionAction = sessionActionLabel != null && onSessionAction != null
            val handoff = rememberNovaEndFocusHandoff(
                endShown = secondaryActionLabel != null && onSecondaryAction != null,
                resume = resumeFocus,
            )
            Row(
                modifier = Modifier.padding(top = if (compact) 4.dp else 10.dp).then(handoff.group),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (endPending) NovaLibraryEndingNotice() else {
                    if (!endArmed) NovaStageHeroAction(
                        label = primaryActionLabel,
                        emphasized = true,
                        testTag = "nova-stage-primary-action",
                        onClick = onPrimaryAction,
                        modifier = if (hasSessionAction) Modifier else Modifier.focusRequester(resumeFocus),
                    )
                    if (sessionActionLabel != null && onSessionAction != null && !endArmed) {
                        NovaStageHeroAction(
                            label = sessionActionLabel,
                            emphasized = false,
                            testTag = "nova-stage-session-action",
                            onClick = onSessionAction,
                            modifier = Modifier.focusRequester(resumeFocus),
                        )
                    }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        NovaStageEndAction(label = secondaryActionLabel, state = endSplit, onConfirm = onSecondaryAction, modifier = handoff.end)
                    }
                }
            }
        }
    }
}

/**
 * Why the host refused an End, under the Stage hero's title (XR3): whole, on as many lines as it
 * takes, and announced. The Stage had said nothing, and End only went away.
 */
@Composable
private fun NovaStageEndRefusal(line: String, compact: Boolean) {
    Text(
        text = line,
        color = LocalNovaComposeColors.current.warning,
        fontSize = if (compact) 11.sp else 12.sp,
        lineHeight = if (compact) 13.sp else 15.sp,
        modifier = Modifier
            .padding(top = if (compact) 2.dp else 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(NOVA_STAGE_END_REFUSED_TAG),
    )
}

/** The Stage hero's line saying why an End was refused, for a test to find it. */
internal const val NOVA_STAGE_END_REFUSED_TAG = "nova-stage-end-refused"

/**
 * End Session on the stage, as a split in its own slot: Stay and End Session, with what ending
 * costs said once under the pair. The library ends the session without asking again.
 */
@Composable
private fun NovaStageEndAction(
    label: String,
    state: NovaSplitConfirmState,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NovaSplitConfirm(
        label = label,
        confirmLabel = stringResource(R.string.game_dialog_action_end_session),
        onConfirm = onConfirm,
        consequence = stringResource(R.string.nova_panel_end_session_message),
        state = state,
        modifier = modifier.testTag("nova-stage-secondary-action"),
    )
}

/**
 * A stage call to action, in the one focus look: the selection fill and a 3dp ring inside the
 * visible surface, animated over 150ms, with no scale. The press target stays the larger box
 * around the surface. The emphasized action rests as a tile like the one beside it, marked by its
 * label in the accent, and takes the accent fill, with its label and ring in the theme's on-accent
 * colour, only while it holds focus (M6): a solid accent at rest read as a second focus beside the
 * poster that held it, as every other primary did before it rested as a tile.
 */
@Composable
private fun NovaStageHeroAction(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val largeText = density.fontScale >= 1.5f
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val opacityScale = LocalNovaMenuOpacityScale.current
    val shape = RoundedCornerShape(NovaRadius.hero)
    // The hero's primary action is not menu chrome. Folding the menu-opacity preference (64% by
    // default) into its tile would thin the scrim under its accent label until the artwork showed
    // through the words, so the primary keeps the scrim's own weight at rest.
    val restFill = if (emphasized) surfaces.focusedArtworkScrim else surfaces.focusedArtworkScrim.copy(alpha = 0.72f * opacityScale)
    val focusedFill = if (emphasized) colors.accent else surfaces.selectedControl
    val ring = if (emphasized) colors.onAccent else surfaces.focusRing
    val focus by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(NovaPanelMetrics.FocusMillis),
        label = "NovaStageActionFocus",
    )
    val visualFontSize = when {
        density.fontScale >= 1.9f -> 9.sp
        largeText -> 11.sp
        else -> 13.sp
    }
    val visualLineHeight = when {
        density.fontScale >= 1.9f -> 12.sp
        largeText -> 14.sp
        else -> 16.sp
    }

    Box(
        modifier = modifier
            .width(if (largeText) 140.dp else 116.dp)
            .height(if (largeText) 42.dp else 40.dp)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused || focusState.hasFocus
            }
            .semantics { role = Role.Button; contentDescription = label }
            // A on release, and only where it was pressed.
            .novaClickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(if (largeText) 132.dp else 108.dp)
                .height(if (largeText) 34.dp else 28.dp)
                .clip(shape)
                .background(lerp(restFill, focusedFill, focus))
                // The ring sits inside the surface's corners, as it does on every control, and only
                // with focus: a border takes no room and draws inside its shape.
                .then(
                    if (focus > 0f) {
                        Modifier.border(NovaPanelMetrics.FocusRingWidth, ring.copy(alpha = ring.alpha * focus), shape)
                    } else {
                        Modifier
                    },
                )
                .testTag("${testTag}-surface"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    color = when {
                        emphasized && focused -> colors.onAccent
                        emphasized -> colors.accentText
                        else -> colors.textPrimary
                    },
                    fontSize = visualFontSize,
                    lineHeight = visualLineHeight,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${testTag}-label"),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovaLibraryStagePosterGrid(
    games: List<PolarisGame>,
    apiClient: PolarisApiClient,
    columns: Int,
    heightDp: Int,
    restoreFocusGameId: String?,
    showPosterTitles: Boolean,
    posterLoader: (ImageView, PolarisGame) -> Unit,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit
) {
    val gameIds = remember(games) { games.map { it.id } }
    val initialIndex = remember(gameIds, restoreFocusGameId) {
        NovaLibraryUiStateMapper.stageRestoreIndex(gameIds, restoreFocusGameId)
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)
    val focusRequesters = remember(gameIds) { List(games.size) { FocusRequester() } }
    LaunchedEffect(gameIds, initialIndex) {
        if (games.isEmpty()) return@LaunchedEffect
        gridState.scrollToItem(initialIndex)
        repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            val composed = gridState.layoutInfo.visibleItemsInfo.any { it.index == initialIndex }
            if (composed &&
                runCatching { focusRequesters[initialIndex].requestFocus() }.getOrDefault(false)
            ) {
                return@LaunchedEffect
            }
            delay(STAGE_FOCUS_RETRY_DELAY_MS)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxWidth().height(heightDp.dp).testTag("nova-stage-portrait-grid"),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        gridItemsIndexed(items = games, key = { _, game -> game.id }) { index, game ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nova-stage-poster-${game.id}"),
            ) {
                NovaLibraryPosterCard(
                    game = game,
                    layoutMode = NovaLibraryLayoutMode.STAGE,
                    apiClient = apiClient,
                    showPosterTitle = showPosterTitles,
                    onOpenDetail = { onOpenDetail(game) },
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = focusRequesters[index],
                    onFocused = { onGameFocused(game) },
                    posterLoader = posterLoader,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NovaLibraryStageRow(
    games: List<PolarisGame>,
    apiClient: PolarisApiClient,
    isLandscape: Boolean,
    posterColumns: Int,
    restoreFocusGameId: String?,
    showPosterTitles: Boolean,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit,
    coverLoader: (ImageView, PolarisGame) -> Unit = { view, game ->
        apiClient.loadCoverInto(view, game)
    }
) {
    // The rail is a lazy list and the cinematic posters are ~10% of the viewport, so the
    // whole library scrolls here. It used to be capped to a handful of items back when a
    // focused card took a quarter of the screen and only a few could ever be reached.
    val effectiveGames = games
    val gameIds = remember(effectiveGames) { effectiveGames.map { it.id } }
    val initialIndex = remember(gameIds, restoreFocusGameId) {
        NovaLibraryUiStateMapper.stageRestoreIndex(gameIds, restoreFocusGameId)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val focusRequesters = remember(gameIds) { List(effectiveGames.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var focusedCardId by remember(gameIds) { mutableStateOf<String?>(null) }
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val inputModeManager = LocalInputModeManager.current

    // A FocusRequester bound to a lazy item that has not been composed yet silently does
    // nothing, so wait for the target to actually appear in the layout before asking. Without
    // this the rail never takes focus on a cold start and the first D-pad press walks the
    // toolbar instead of the library.
    LaunchedEffect(gameIds, initialIndex) {
        if (effectiveGames.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(initialIndex)
        repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            val composed = listState.layoutInfo.visibleItemsInfo.any { it.index == initialIndex }
            if (composed) {
                // Compose refuses focus while the window is in touch mode, so a cold start
                // would otherwise leave the stage unfocused and hand the first D-pad press
                // to the toolbar. This surface is controller-first, so declare that intent.
                inputModeManager.requestInputMode(InputMode.Keyboard)
                val accepted = runCatching {
                    focusRequesters[initialIndex].requestFocus()
                }.getOrDefault(false)
                if (accepted) return@LaunchedEffect
            }
            delay(STAGE_FOCUS_RETRY_DELAY_MS)
        }
    }

    // Removed aggressive auto-snap: allows smooth free-form scrolling
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidthDp = maxWidth.value.toInt()
        val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(
            NovaLibraryLayoutMode.STAGE,
        )
        val railHeightDp = maxHeight.value.toInt()
        val captionBudgetDp = stagePosterCaptionBudgetDp(
            showPosterTitles = showPosterTitles,
            largeText = largeText,
        )
        val minimumRailHeightDp =
            NovaLibraryUiStateMapper.minimumPortraitPosterRailHeightDp(presentationSpec) + captionBudgetDp
        if (railHeightDp < minimumRailHeightDp) return@BoxWithConstraints
        val artworkRailHeightDp = (railHeightDp - captionBudgetDp).coerceAtLeast(0)
        val fitSize = NovaLibraryUiStateMapper.portraitPosterSizeForRail(
            artworkRailHeightDp,
            presentationSpec,
        )
        // Pin card width to a fraction of the viewport (GameNative-style) rather
        // than deriving it from leftover rail height, then clamp to what the rail
        // can actually show. Without this the cards collapse whenever the hero
        // takes vertical budget.
        val carouselTargetWidthDp =
            NovaLibraryUiStateMapper.stageRailPosterWidthDp(availableWidthDp)
        // The cinematic proportion decides the poster size. The rail-fit size is a
        // ceiling, not a floor: it only shrinks the card when the rail genuinely cannot
        // host the proportional size. Using it as a floor let cards inflate to fill
        // whatever rail height happened to be reserved, which silently overrode the
        // proportion this layout is supposed to hold.
        val widthFirstDp = carouselTargetWidthDp.coerceAtMost(fitSize.widthDp)
        val posterSize = NovaLibraryUiStateMapper.portraitPosterSizeForWidth(
            widthFirstDp.coerceAtLeast(2),
        )
        val artworkWidthDp = posterSize.widthDp
        val artworkHeightDp = posterSize.heightDp
        val cellWidthDp = artworkWidthDp + 2 * presentationSpec.focusGutterDp
        val cellHeightDp = artworkHeightDp + captionBudgetDp
        val verticalContentPaddingPerEdgeDp = NovaLibraryUiStateMapper.stageRailVerticalContentPaddingDp()
        val horizontalPaddingDp = NovaLibraryUiStateMapper.stageHorizontalContentPaddingDp(
            availableWidthDp = availableWidthDp,
            cardWidthDp = cellWidthDp,
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides NovaStageEdgeScrollSpec) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("nova-stage-landscape-rail"),
            contentPadding = PaddingValues(
                start = horizontalPaddingDp.dp,
                top = verticalContentPaddingPerEdgeDp.dp,
                end = horizontalPaddingDp.dp,
                bottom = verticalContentPaddingPerEdgeDp.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(NovaStageCarouselGapDp),
            verticalAlignment = Alignment.Bottom
        ) {
            itemsIndexed(
                items = effectiveGames,
                key = { _, game -> game.id },
                contentType = { _, _ -> "stage-game" }
            ) { index, game ->
                val isFocusedCard = focusedCardId == game.id
                Box(
                    modifier = Modifier
                        .width(cellWidthDp.dp)
                        .height(cellHeightDp.dp)
                        .zIndex(if (isFocusedCard) 1f else 0f)
                        .testTag("nova-stage-poster-${game.id}"),
                ) {
                    NovaLibraryPosterCard(
                        game = game,
                        layoutMode = NovaLibraryLayoutMode.STAGE,
                        apiClient = apiClient,
                        showPosterTitle = showPosterTitles,
                        onOpenDetail = {
                            onGameFocused(game)
                            onOpenDetail(game)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = focusRequesters[index],
                        onFocusChanged = { isFocused ->
                            focusedCardId = NovaLibraryUiStateMapper.stageFocusOwnerAfterChange(
                                currentOwnerId = focusedCardId,
                                gameId = game.id,
                                isFocused = isFocused,
                            )
                        },
                        onFocused = { onGameFocused(game) },
                        onNavigate = { delta ->
                            val nextIndex = NovaLibraryUiStateMapper.stageAdjacentIndex(
                                currentIndex = index,
                                delta = delta,
                                itemCount = effectiveGames.size,
                            )
                            if (nextIndex != index) {
                                scope.launch {
                                    repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
                                        withFrameNanos { }
                                        if (runCatching {
                                            focusRequesters[nextIndex].requestFocus()
                                        }.getOrDefault(false)
                                        ) {
                                            return@launch
                                        }
                                        delay(STAGE_FOCUS_RETRY_DELAY_MS)
                                    }
                                }
                            }
                            true
                        },
                        posterLoader = coverLoader,
                    )
                }
            }
        }
        }
    }
}

private fun stagePosterCaptionBudgetDp(showPosterTitles: Boolean, largeText: Boolean): Int = when {
    !showPosterTitles -> 0
    largeText -> STAGE_LARGE_TEXT_POSTER_CAPTION_BUDGET_DP
    else -> STAGE_POSTER_CAPTION_BUDGET_DP
}

private const val STAGE_POSTER_CAPTION_BUDGET_DP = 36
private const val STAGE_LARGE_TEXT_POSTER_CAPTION_BUDGET_DP = 64
private const val STAGE_FOCUS_REQUEST_ATTEMPTS = 24
private const val STAGE_FOCUS_RETRY_DELAY_MS = 32L

/**
 * Supporting line under the hero title: where the game came from, what it is, and the
 * capabilities worth knowing before launching. Uppercased and letterspaced so it reads as
 * a caption against the title rather than competing with it.
 */
@Composable
private fun stageHeroMetadata(game: PolarisGame): String {
    val hdrLabel = stringResource(R.string.badge_hdr)
    val recentLabel = stringResource(R.string.nova_library_filter_recent)
    return remember(
        game.id,
        game.sourceLabel,
        game.categoryLabel,
        game.hdrSupported,
        game.lastLaunched,
        hdrLabel,
        recentLabel,
    ) {
        buildList {
            add(game.sourceLabel)
            add(game.categoryLabel)
            if (game.hdrSupported) add(hdrLabel)
            if (game.lastLaunched > 0L) add(recentLabel)
        }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ") { it.uppercase(Locale.US) }
    }
}

internal fun stageDisplayTitle(title: String, largeText: Boolean): String {
    if (!largeText || title.length <= 20 || '\n' in title) return title
    val midpoint = title.length / 2
    val breakIndex = title.indices
        .filter { index -> title[index] == ' ' }
        .minByOrNull { index -> kotlin.math.abs(index - midpoint) }
        ?: return title
    return title.substring(0, breakIndex) + "\n" + title.substring(breakIndex + 1)
}
