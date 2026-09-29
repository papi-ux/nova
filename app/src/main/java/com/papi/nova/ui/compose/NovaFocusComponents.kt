package com.papi.nova.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaCurrentMark
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaFocusRing

internal object NovaFocusMotionSpec {
    const val DurationMillis = 150
    const val CardFocusedScale = 1.025f
    const val CardFocusedHaloAlpha = 0.34f
    const val ButtonPressedAlpha = 0.86f
}

private fun novaFocusFloatTween() = tween<Float>(durationMillis = NovaFocusMotionSpec.DurationMillis)

/**
 * The card lift and halo the library's hero strip and the search field still draw. Nothing presses
 * through it any more: buttons take the one focus look ([novaFocusRing]), with no scale.
 */
internal fun Modifier.novaFocusMotion(
    focused: Boolean,
    focusedScale: Float = NovaFocusMotionSpec.CardFocusedScale,
    haloAlpha: Float = NovaFocusMotionSpec.CardFocusedHaloAlpha,
    cornerRadius: Dp = NovaRadius.row
): Modifier = composed {
    val surfaces = LocalNovaLibrarySurfaces.current
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = novaFocusFloatTween(),
        label = "NovaFocusMotionScale"
    )
    val animatedHaloAlpha by animateFloatAsState(
        targetValue = if (focused) haloAlpha else 0f,
        animationSpec = novaFocusFloatTween(),
        label = "NovaFocusMotionHalo"
    )

    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.drawWithContent {
        if (animatedHaloAlpha > 0f) {
            drawRoundRect(
                color = surfaces.focusHalo.copy(alpha = surfaces.focusHalo.alpha * animatedHaloAlpha),
                cornerRadius = CornerRadius((cornerRadius + 4.dp).toPx(), (cornerRadius + 4.dp).toPx())
            )
        }
        drawContent()
    }
}

@Composable
fun NovaBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalNovaComposeColors.current.textSecondary,
    backgroundColor: Color = LocalNovaLibrarySurfaces.current.control,
    borderColor: Color = Color.Transparent,
    fontWeight: FontWeight = FontWeight.Medium,
    fontSize: TextUnit = 10.sp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
) {
    val shape = RoundedCornerShape(NovaRadius.pill)
    Text(
        text = text,
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * The side of a round key chip: [base] at an ordinary font scale, growing with its letter.
 *
 * The chips were a fixed 20dp around text set in sp, so at a large font scale the letter grew
 * and the circle did not: A, B and Y sat with their lower halves cut off. Small text scales in
 * a straight line, so the chip does too. It never shrinks, because the circle is also what
 * the hint row lines up on.
 */
@Composable
fun novaKeyChipSize(base: Dp): Dp = novaKeyChipSize(base, LocalDensity.current.fontScale)

internal fun novaKeyChipSize(base: Dp, fontScale: Float): Dp = base * fontScale.coerceAtLeast(1f)

/**
 * One controller hint: a key and what it does here. Panels and screens alike draw a list of them
 * with the one hint bar, NovaPanelHintBar; the library paints its own on the artwork.
 */
data class NovaControllerHint(
    val key: String,
    val label: String
)

// Shared haptic vocabulary for the gamepad-first surfaces: a light tick when focus moves,
// a firmer confirm when a primary action fires. The platform suppresses these when the
// user has system haptics disabled, so no Nova-level setting gates them.
fun HapticFeedback.novaFocusTick() {
    performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
}

fun HapticFeedback.novaConfirm() {
    performHapticFeedback(HapticFeedbackType.Confirm)
}

@Composable
fun NovaActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    contentDescription: String = text,
    selected: Boolean = false,
    stateDescription: String? = null,
    minHeight: Dp = 38.dp,
    cornerRadius: Dp = NovaRadius.hero,
    fontSize: TextUnit = 13.sp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
) {
    NovaActionSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        primary = primary,
        destructive = destructive,
        contentDescription = contentDescription,
        selected = selected,
        stateDescription = stateDescription,
        minHeight = minHeight,
        cornerRadius = cornerRadius,
        contentPadding = contentPadding,
    ) { contentColor, _ ->
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The surface of [NovaActionButton] around content of its own: the same container, focus look,
 * press state, haptics and disabled treatment, for an action that is more than one line of text,
 * such as the library's Space control. [content] gets the colour text should use and whether the
 * surface holds focus, so an affordance like a chevron can follow the ring.
 *
 * Focus has one look everywhere: a fill plus a 3dp ring inside the shape, with no scale and no
 * halo ([novaFocusRing]). A [primary] takes the accent fill under focus. At rest it is a tile like
 * any other, with its label and icon in the accent, so only focus is loud (R9): Resume, Close and
 * Save rested as solid accent beside the focused button and read as a second focus. A fill at rest
 * is kept for a primary destructive, such as the confirm half of an armed destructive split, in
 * red (a confirm page's destructive answer looks the same), and for a full-screen state page's
 * recovery action, which passes [fillAtRest] because it is the one thing on its page. The
 * destructive fill is a red on every theme, never the text colour the destructive text falls
 * back to.
 *
 * Both halves of an armed neutral split pass [accentUnderFocus]: they rest as tiles, the
 * confirm's label in the accent as a primary's is, and the half with focus fills in the accent,
 * so nothing fills without focus (review finding 4).
 *
 * The ring is the accent ring every control has, on every surface but one: a primary whose accent
 * fill runs flush to its edge under focus rings in its label colour, `onAccent`, where an accent
 * ring would vanish on it. A red fill takes the accent ring flush (in-game #14). An accent fill
 * that is there at rest, or one under [accentUnderFocus], stands off the ring by
 * [NovaPanelMetrics.FocusRingGap] under focus, so the one accent ring reads on it.
 *
 * A destructive action at rest has destructive text and a hairline in the destructive fill: the
 * text colour falls back to the ordinary text colour on a theme whose red does not read as words,
 * and a hairline in that colour read as a second focus ring. A line needs only 3:1, which the
 * fill always has. Activation goes through [novaClickable], so A acts on release and only on the
 * surface that took the press.
 *
 * [selected] marks the current value the one way R9 allows: the check ([NovaCurrentMark]) after the
 * content, selected semantics and the state description Current. It never fills the surface,
 * because fills only ever mean focus.
 *
 * At rest an unfilled surface draws the control fill, or [restFill] where it stands among rows,
 * such as a destructive row that splits in place, so it rests as the tile the rows around it do.
 * What it shows is published by the focus look that draws it ([NovaSurfaceLook]).
 */
@Composable
fun NovaActionSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    contentDescription: String? = null,
    selected: Boolean = false,
    stateDescription: String? = null,
    minHeight: Dp = 38.dp,
    cornerRadius: Dp = NovaRadius.hero,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    contentAlignment: Alignment = Alignment.Center,
    restFill: Color = Color.Unspecified,
    fillAtRest: Boolean = false,
    accentUnderFocus: Boolean = false,
    content: @Composable BoxScope.(contentColor: Color, focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(cornerRadius)
    val fill = if (destructive) colors.destructiveFill else colors.accent
    val onFill = if (destructive) colors.onDestructiveFill else colors.onAccent
    // A primary fills under focus, and so does a half of an armed neutral split; only a primary
    // destructive or [fillAtRest] is filled at rest too.
    val fills = (primary || accentUnderFocus) && enabled
    val filledAtRest = primary && enabled && (destructive || fillAtRest)
    val filled = filledAtRest || (fills && focused)
    val pressedFill = fill.copy(alpha = fill.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
    val restContainer = when {
        pressed && filledAtRest -> pressedFill
        pressed && enabled -> surfaces.selectedControl.copy(alpha = surfaces.selectedControl.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        filledAtRest -> fill
        else -> restFill.takeOrElse { surfaces.control }
    }
    // A surface that fills takes its fill under focus; everything else takes the focused control fill.
    val focusedContainer = if (fills) (if (pressed) pressedFill else fill) else surfaces.selectedControl
    // An accent fill that is not flush to the edge under focus stands off the accent ring; a red
    // fill never has.
    val ringStandsOff = fills && !destructive && (filledAtRest || accentUnderFocus)
    val ring = novaActionRing(fills = fills, destructive = destructive, standsOff = ringStandsOff, onFill = onFill, focusRing = surfaces.focusRing)
    val contentColor = when {
        filled -> onFill
        primary && enabled -> colors.accentText
        destructive && enabled -> colors.destructive
        enabled -> colors.textPrimary
        else -> colors.textMuted
    }
    val restBorder = when {
        filledAtRest -> Color.Transparent
        // The fill's red, never the text fallback: a hairline in the text colour is a second ring.
        destructive && enabled -> colors.destructiveFill
        else -> surfaces.tileBorder
    }
    val alpha = if (enabled) 1f else NovaPanelMetrics.DisabledAlpha
    val state = stateDescription ?: if (selected) stringResource(R.string.nova_panel_current) else null

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .novaFocusRing(
                shape = shape,
                ring = ring,
                focusedFill = focusedContainer.copy(alpha = focusedContainer.alpha * alpha),
                restFill = restContainer.copy(alpha = restContainer.alpha * alpha),
                restBorder = restBorder,
                restBorderWidth = if (filledAtRest) 0.dp else NovaPanelMetrics.Hairline,
                ringStandsOff = ringStandsOff,
            )
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                if (selected) {
                    this.selected = true
                }
                state?.let { this.stateDescription = it }
                role = Role.Button
            }
            .onFocusChanged {
                val nowFocused = it.isFocused || it.hasFocus
                if (nowFocused && !focused) haptics.novaFocusTick()
                focused = nowFocused
            }
            .novaClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                onClick = {
                    haptics.novaConfirm()
                    onClick()
                },
            )
            .padding(contentPadding),
        contentAlignment = contentAlignment
    ) {
        if (selected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
            ) {
                Box(Modifier.weight(1f, fill = false), contentAlignment = contentAlignment) {
                    content(contentColor, focused)
                }
                NovaCurrentMark()
            }
        } else {
            content(contentColor, focused)
        }
    }
}

/**
 * A [NovaActionSurface]'s focus ring. A primary whose accent fill runs flush to its edge under
 * focus rings in its label colour, since an accent ring would vanish on it. Every other surface
 * takes the accent ring every control has: a red fill, on which the armed End Session drew a black
 * ring where Stay beside it drew the accent (in-game #14), and an accent fill that [standsOff] the
 * ring, as a half of an armed neutral split does under focus (review finding 4).
 */
internal fun novaActionRing(fills: Boolean, destructive: Boolean, standsOff: Boolean, onFill: Color, focusRing: Color): Color =
    if (fills && !destructive && !standsOff) onFill else focusRing

/**
 * What a surface with the focus look shows once focus has settled: the [fill] behind its content,
 * the [ring] focus draws or [Color.Unspecified] without focus, and whether its fill stands off that
 * ring. The node that draws the look publishes it in its semantics ([novaFocusRing]), so a check
 * reads what that node draws rather than a copy of the rule.
 */
@Immutable
data class NovaSurfaceLook(val fill: Color, val ring: Color, val ringStandsOff: Boolean)

/** The [NovaSurfaceLook] of a [NovaActionSurface]. */
val NovaSurfaceLookKey = SemanticsPropertyKey<NovaSurfaceLook>("NovaSurfaceLook")
var SemanticsPropertyReceiver.novaSurfaceLook by NovaSurfaceLookKey
