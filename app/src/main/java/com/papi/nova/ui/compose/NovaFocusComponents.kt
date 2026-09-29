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
 * halo ([novaFocusRing]). The ring contrasts with the fill it sits on: `onAccent` on a primary,
 * `onDestructiveFill` on a primary destructive (the armed half of a split confirm), whose fill is
 * the destructive fill, a red on every theme, never the text colour the destructive text falls
 * back to. A destructive action at rest has destructive text and a destructive hairline. Activation goes through
 * [novaClickable], so A acts on release and only on the surface that took the press.
 *
 * [selected] marks the current value the one way R9 allows: the check ([NovaCurrentMark]) after the
 * content, selected semantics and the state description Current. It never fills the surface,
 * because fills only ever mean focus.
 *
 * At rest an unfilled surface draws the control fill, or [restFill] where it stands among rows,
 * such as a destructive row that splits in place, so it rests as the tile the rows around it do.
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
    val filled = primary && enabled
    val restContainer = when {
        pressed && filled -> fill.copy(alpha = fill.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        pressed && enabled -> surfaces.selectedControl.copy(alpha = surfaces.selectedControl.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        filled -> fill
        else -> restFill.takeOrElse { surfaces.control }
    }
    // A filled surface keeps its fill under focus; everything else takes the focused control fill.
    val focusedContainer = if (filled) restContainer else surfaces.selectedControl
    val contentColor = when {
        filled -> onFill
        destructive && enabled -> colors.destructive
        enabled -> colors.textPrimary
        else -> colors.textMuted
    }
    val restBorder = when {
        filled -> Color.Transparent
        destructive && enabled -> colors.destructive
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
                ring = if (filled) onFill else surfaces.focusRing,
                focusedFill = focusedContainer.copy(alpha = focusedContainer.alpha * alpha),
                restFill = restContainer.copy(alpha = restContainer.alpha * alpha),
                restBorder = restBorder,
                restBorderWidth = if (filled) 0.dp else NovaPanelMetrics.Hairline,
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
