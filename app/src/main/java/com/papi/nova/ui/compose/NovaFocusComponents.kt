package com.papi.nova.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object NovaFocusMotionSpec {
    const val DurationMillis = 150
    const val CardFocusedScale = 1.025f
    const val ButtonFocusedScale = 1.03f
    const val ButtonPressedScale = 0.98f
    const val CardFocusedHaloAlpha = 0.34f
    const val ButtonFocusedHaloAlpha = 0.28f
    const val ButtonPressedAlpha = 0.86f
}

private fun novaFocusFloatTween() = tween<Float>(durationMillis = NovaFocusMotionSpec.DurationMillis)

private fun novaFocusDpTween() = tween<Dp>(durationMillis = NovaFocusMotionSpec.DurationMillis)

private fun novaFocusColorTween() = tween<Color>(durationMillis = NovaFocusMotionSpec.DurationMillis)

internal fun Modifier.novaFocusMotion(
    focused: Boolean,
    enabled: Boolean = true,
    pressed: Boolean = false,
    focusedScale: Float = NovaFocusMotionSpec.CardFocusedScale,
    pressedScale: Float = NovaFocusMotionSpec.ButtonPressedScale,
    haloAlpha: Float = NovaFocusMotionSpec.CardFocusedHaloAlpha,
    cornerRadius: Dp = NovaRadius.row
): Modifier = composed {
    val surfaces = LocalNovaLibrarySurfaces.current
    val targetScale = when {
        pressed && enabled -> pressedScale
        focused && enabled -> focusedScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = novaFocusFloatTween(),
        label = "NovaFocusMotionScale"
    )
    val animatedHaloAlpha by animateFloatAsState(
        targetValue = if (focused && enabled) haloAlpha else 0f,
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

data class NovaControllerHint(
    val key: String,
    val label: String
)

@Composable
fun NovaControllerHintBar(
    hints: List<NovaControllerHint>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    semanticsDescription: String? = null
) {
    if (hints.isEmpty()) return

    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.hero)
    val hintContentDescription = semanticsDescription
        ?: hints.joinToString(separator = " · ") { hint -> "${hint.key} ${hint.label}" }
    val horizontalPadding = if (compact) 8.dp else 10.dp
    val itemSpacing = if (compact) 8.dp else 12.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clip(shape)
            .background(surfaces.panel.copy(alpha = 0.86f * LocalNovaMenuOpacityScale.current))
            .border(1.dp, surfaces.panelBorder, shape)
            .semantics { contentDescription = hintContentDescription }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        hints.forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = hint.key,
                    color = colors.onAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(NovaRadius.row))
                        .background(colors.accent.copy(alpha = 0.92f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
                Text(
                    text = hint.label,
                    color = colors.textSecondary,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}

@Composable
fun NovaFocusableCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.row)
    val borderWidth by animateDpAsState(
        targetValue = if (focused && enabled) 2.dp else 1.dp,
        animationSpec = novaFocusDpTween(),
        label = "NovaFocusableCardBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused && enabled) surfaces.focusRing else surfaces.tileBorder,
        animationSpec = novaFocusColorTween(),
        label = "NovaFocusableCardBorderColor"
    )
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        Modifier
    }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            if (onClick != null) {
                role = Role.Button
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .novaFocusMotion(
                focused = focused,
                enabled = enabled,
                haloAlpha = NovaFocusMotionSpec.CardFocusedHaloAlpha,
                cornerRadius = NovaRadius.row
            )
            .clip(shape)
            .background(surfaces.tile)
            .border(borderWidth, borderColor, shape)
            .then(semanticsModifier)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .then(clickableModifier)
            .focusable(enabled = enabled)
            .padding(contentPadding),
        content = content
    )
}

// Shared haptic vocabulary for the gamepad-first surfaces: a light tick when focus moves,
// a firmer confirm when a primary action fires. The platform suppresses these when the
// user has system haptics disabled, so no Nova-level setting gates them.
fun HapticFeedback.novaFocusTick() {
    performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
}

fun HapticFeedback.novaConfirm() {
    performHapticFeedback(HapticFeedbackType.Confirm)
}

// Destructive actions keep the quiet ghost shape and only tint their text, matching the
// End Session confirm sheet.
private val NovaDestructiveContent = Color(0xFFF87171)

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
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(cornerRadius)
    val targetContainer = when {
        pressed && enabled && primary -> colors.accent.copy(alpha = colors.accent.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        pressed && enabled -> surfaces.selectedControl.copy(alpha = surfaces.selectedControl.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        primary && enabled -> colors.accent
        focused -> surfaces.selectedControl
        // `selected` used to reach the semantics tree and no colour branch, so a selected
        // button looked exactly like an unselected one. Call sites worked around that by
        // passing `primary = true` to mean "selected", which is why that flag ended up
        // carrying two meanings.
        selected && enabled -> colors.accentSurface
        else -> surfaces.control
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = novaFocusColorTween(),
        label = "NovaActionButtonContainer"
    )
    val contentColor = when {
        primary && enabled -> colors.onAccent
        destructive && enabled -> NovaDestructiveContent
        enabled -> colors.textPrimary
        else -> colors.textMuted
    }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && primary -> colors.onAccent
            focused -> surfaces.focusRing
            destructive && !primary -> NovaDestructiveContent.copy(alpha = 0.45f)
            !primary -> surfaces.tileBorder
            else -> surfaces.tileBorder
        },
        animationSpec = novaFocusColorTween(),
        label = "NovaActionButtonBorderColor"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            focused -> 3.dp
            !primary -> 1.dp
            else -> 0.dp
        },
        animationSpec = novaFocusDpTween(),
        label = "NovaActionButtonBorderWidth"
    )
    val alpha = if (enabled) 1f else 0.45f

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .novaFocusMotion(
                focused = focused,
                enabled = enabled,
                pressed = pressed,
                focusedScale = NovaFocusMotionSpec.ButtonFocusedScale,
                haloAlpha = NovaFocusMotionSpec.ButtonFocusedHaloAlpha,
                cornerRadius = cornerRadius
            )
            .clip(shape)
            .background(container.copy(alpha = container.alpha * alpha))
            .border(borderWidth, borderColor, shape)
            .semantics {
                this.contentDescription = contentDescription
                if (selected) {
                    this.selected = true
                }
                stateDescription?.let { this.stateDescription = it }
                role = Role.Button
            }
            .onFocusChanged {
                val nowFocused = it.isFocused || it.hasFocus
                if (nowFocused && !focused) haptics.novaFocusTick()
                focused = nowFocused
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.novaConfirm()
                    onClick()
                }
            )
            .focusable(enabled = enabled)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
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
