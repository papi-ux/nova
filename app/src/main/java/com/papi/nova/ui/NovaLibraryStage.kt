package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.NovaKeys
import com.papi.nova.ui.panel.NovaPressLatch
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSpaces
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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

/** The beta.1 Stage: one fixed focus owner, a height-sized cover, and a wrapping row beside it.
 * Session actions belong to the shared strip; A on the selected cover opens its game page. */
@Composable
internal fun NovaLibraryStage(
    games: List<PolarisGame>,
    focusedGame: PolarisGame?,
    restoreFocusGameId: String?,
    apiClient: PolarisApiClient,
    showPosterTitles: Boolean,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit,
    artworkLoader: (ImageView, PolarisGame, String) -> Unit = { view, game, kind -> apiClient.loadArtworkInto(view, game, kind) },
    posterLoader: (ImageView, PolarisGame) -> Unit = { view, game -> apiClient.loadCoverInto(view, game) },
    runningGameId: String? = null,
    sortLabel: String? = null,
) {
    if (games.isEmpty()) return
    val ids = remember(games) { games.map { it.id } }
    var selectedId by remember(ids) { mutableStateOf(restoreFocusGameId?.takeIf { it in ids } ?: focusedGame?.id?.takeIf { it in ids } ?: ids.first()) }
    val selectedIndex = ids.indexOf(selectedId).coerceAtLeast(0)
    val selected = games[selectedIndex]
    val neighbours = remember(games, selectedIndex) {
        games.drop(selectedIndex + 1) + games.take(selectedIndex)
    }
    val stageFocus = remember { FocusRequester() }
    var stageFocused by remember { mutableStateOf(false) }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val inputModeManager = LocalInputModeManager.current
    val haptics = LocalHapticFeedback.current
    val activation = remember { NovaStagePress() }
    fun select(game: PolarisGame) {
        if (game.id == selectedId) return
        activation.clear()
        selectedId = game.id
        onGameFocused(game)
        scope.launch { rowState.scrollToItem(0) }
    }
    LaunchedEffect(restoreFocusGameId, ids) {
        restoreFocusGameId?.takeIf { it in ids && it != selectedId }?.let {
            activation.clear()
            selectedId = it
        }
    }
    LaunchedEffect(ids) {
        inputModeManager.requestInputMode(InputMode.Keyboard)
        repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            if (runCatching { stageFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(STAGE_FOCUS_RETRY_DELAY_MS)
        }
    }
    val density = LocalDensity.current
    val largeText = density.fontScale >= 1.5f
    val counter = stringResource(R.string.nova_library_stage_position, selectedIndex + 1, games.size,
        sortLabel ?: stringResource(R.string.nova_library_options_sort_library_order))
    BoxWithConstraints(Modifier.fillMaxSize().testTag("nova-library-stage").padding(horizontal = 10.dp)) {
        val captionHeightDp = if (showPosterTitles) with(density) {
            val lineHeightPx = novaLibraryStageCaptionLineHeightPx(this.fontScale,
                NOVA_STAGE_CAPTION_FONT_SIZE_SP.sp.toPx(), NOVA_STAGE_CAPTION_LINE_HEIGHT_SP.sp.toPx())
            novaLibraryStageCaptionHeightDp(lineHeightPx, NOVA_STAGE_CAPTION_TOP_PADDING_DP.dp.roundToPx(), this.density)
        } else 0
        // Pixel rounding can report an intended 354dp budget as 353.90476dp at density
        // 2.625. Recover its nearest integer dp rather than dropping a whole 2:3 rung.
        val geometry = novaLibraryStageGeometry((maxWidth + 20.dp).value.roundToInt(), maxHeight.value.roundToInt(), density.fontScale, captionHeightDp)
        Row(
            modifier = Modifier.fillMaxWidth().height(geometry.selected.heightDp.dp)
                .testTag("nova-stage-poster-area"),
            horizontalArrangement = Arrangement.spacedBy(geometry.selectedGapDp.dp),
        ) {
            // This owner never leaves the layout as Left/Right changes the selected game's art.
            Box(
                modifier = Modifier.width(geometry.selected.widthDp.dp).height(geometry.selected.heightDp.dp)
                    .focusRequester(stageFocus)
                    .onFocusChanged {
                        stageFocused = it.isFocused
                        if (!it.hasFocus) activation.clear()
                        if (it.isFocused) onGameFocused(selected)
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (NovaKeys.isActivation(native.keyCode)) {
                            // The fixed focus owner survives selection changes, so it fences
                            // the release by the game that received the press, as per-game
                            // posters do by losing focus. Touch and semantics keep novaClickable.
                            when (event.type) {
                                KeyEventType.KeyDown -> if (native.repeatCount == 0) {
                                    activation.latch.press(native.keyCode)
                                    activation.gameId = selected.id
                                }
                                KeyEventType.KeyUp -> if (activation.latch.release(native.keyCode)) {
                                    val target = activation.gameId
                                    activation.gameId = null
                                    if (!native.isCanceled && target == selected.id) {
                                        haptics.novaConfirm()
                                        onOpenDetail(selected)
                                    }
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
                        select(games[NovaLibraryUiStateMapper.stageAdjacentIndex(selectedIndex, delta, games.size)])
                        true
                    }
                    .novaClickable(role = Role.Button) {
                        haptics.novaConfirm()
                        onOpenDetail(selected)
                    }
                    .testTag("nova-stage-selected-focus"),
            ) {
                NovaLibraryPosterCard(
                    game = selected,
                    layoutMode = NovaLibraryLayoutMode.STAGE,
                    apiClient = apiClient,
                    showPosterTitle = false,
                    onOpenDetail = { onOpenDetail(selected) },
                    modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
                    focusedOverride = stageFocused,
                    running = selected.id == runningGameId,
                    posterLoader = posterLoader,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NovaLibraryStageIdentity(
                    game = selected,
                    largeText = largeText,
                    running = selected.id == runningGameId,
                    artworkLoader = artworkLoader,
                    heightDp = geometry.infoHeightDp,
                    modifier = Modifier.fillMaxWidth().height(geometry.infoHeightDp.dp),
                )
                if (geometry.neighbour.heightDp >= 3) {
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.fillMaxWidth().height((geometry.neighbour.heightDp + captionHeightDp).dp)
                            .novaStageRowEdgeFade { rowState.canScrollForward }
                            .testTag("nova-stage-landscape-rail"),
                        horizontalArrangement = Arrangement.spacedBy(geometry.posterGapDp.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        itemsIndexed(neighbours, key = { _, game -> game.id }, contentType = { _, _ -> "stage-game" }) { _, game ->
                            NovaLibraryPosterCard(
                                game = game,
                                layoutMode = NovaLibraryLayoutMode.STAGE,
                                apiClient = apiClient,
                                showPosterTitle = showPosterTitles,
                                onOpenDetail = {
                                    select(game)
                                    stageFocus.requestFocus()
                                },
                                modifier = Modifier.width(geometry.neighbour.widthDp.dp).height((geometry.neighbour.heightDp + captionHeightDp).dp)
                                    .focusProperties { canFocus = false },
                                running = game.id == runningGameId,
                                posterLoader = posterLoader,
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = counter,
            color = LocalNovaComposeColors.current.textSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).testTag("nova-stage-position"),
        )
    }
}

@Composable
private fun NovaLibraryStageIdentity(
    game: PolarisGame,
    largeText: Boolean,
    running: Boolean,
    artworkLoader: (ImageView, PolarisGame, String) -> Unit,
    heightDp: Int,
    modifier: Modifier,
) {
    val colors = LocalNovaComposeColors.current
    // Match the detail page: a cached logo is ready to use, otherwise the game name is stable.
    val logo = game.logoArtwork?.cached == true
    val logoKey = PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_LOGO)
    val fontScale = LocalDensity.current.fontScale
    val metadataHeight = if (largeText) 0f else 14f * fontScale + 8f
    val titleLines = if (heightDp >= 60f * fontScale + metadataHeight + 17f * fontScale + 8f) 2 else 1
    val titleHeight = if (logo) 46f else titleLines * 30f * fontScale
    val statsLines = if (heightDp >= titleHeight + metadataHeight + 34f * fontScale + 8f) 2 else 1
    Column(modifier.testTag("nova-stage-identity"), verticalArrangement = Arrangement.Bottom) {
        if (logo) {
            AndroidView(
                factory = { context -> ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_START
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                } },
                update = { view ->
                    if (view.getTag(R.id.nova_artwork_presentation_key) != logoKey) {
                        view.setTag(R.id.nova_artwork_presentation_key, logoKey)
                        view.setImageDrawable(null)
                        artworkLoader(view, game, PolarisGame.ARTWORK_KIND_LOGO)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(46.dp)
                    .semantics { contentDescription = game.name }
                    .testTag("nova-stage-logo"),
            )
        } else {
            Text(game.name, color = colors.textPrimary, fontSize = 26.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Bold, maxLines = titleLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("nova-stage-title"))
        }
        val metadata = stageHeroMetadata(game)
        if (!largeText && metadata.isNotBlank()) {
            Text(metadata, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).testTag("nova-stage-metadata"))
        }
        val stats = stagePlayStats(game, running)
        if (stats.isNotBlank()) {
            Text(stats, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 17.sp,
                maxLines = statsLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).testTag("nova-stage-play-stats"))
        }

    }
}

/** Right-edge fade for a partial next cover. The selected cover and its ring are outside it. */
private fun Modifier.novaStageRowEdgeFade(more: () -> Boolean): Modifier = graphicsLayer {
    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
    clip = true
}.drawWithContent {
    drawContent()
    if (more()) {
        val band = 24.dp.toPx().coerceAtMost(size.width / 2f)
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent), startX = size.width - band, endX = size.width),
            topLeft = androidx.compose.ui.geometry.Offset(size.width - band, 0f),
            size = androidx.compose.ui.geometry.Size(band, size.height),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
}

private const val STAGE_FOCUS_REQUEST_ATTEMPTS = 24
private const val STAGE_FOCUS_RETRY_DELAY_MS = 32L

private class NovaStagePress {
    val latch = NovaPressLatch()
    var gameId: String? = null
    fun clear() {
        latch.clear()
        gameId = null
    }
}

@Composable
private fun stageHeroMetadata(game: PolarisGame): String {
    val hdr = stringResource(R.string.badge_hdr)
    return listOf(game.sourceLabel, game.categoryLabel, hdr.takeIf { game.hdrSupported })
        .filterNotNull().filter(String::isNotBlank).distinct().joinToString(" · ")
}

@Composable
private fun stagePlayStats(game: PolarisGame, running: Boolean): String = buildList {
    if (running) add(stringResource(R.string.nova_library_stage_running_now))
    game.playTime?.let {
        val minutes = it.seconds.coerceAtLeast(0) / 60
        add(if (minutes >= 60) stringResource(R.string.nova_game_detail_played_hours, minutes / 60)
            else stringResource(R.string.nova_game_detail_played_minutes, minutes))
    }
    if (game.lastLaunched > 0) {
        val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
            game.lastLaunched.coerceAtMost(Long.MAX_VALUE / 1000) * 1000,
            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE)
        add(stringResource(R.string.nova_library_stage_last_played, relative))
    }
}.joinToString(" · ")

internal fun stageDisplayTitle(title: String, largeText: Boolean): String {
    if (!largeText || title.length <= 20 || '\n' in title) return title
    val midpoint = title.length / 2
    val breakIndex = title.indices.filter { title[it] == ' ' }.minByOrNull { abs(it - midpoint) } ?: return title
    return title.substring(0, breakIndex) + "\n" + title.substring(breakIndex + 1)
}
