package com.papi.nova.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    cornerRadius: Dp = 14.dp
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
    color: Color = LocalNovaComposeColors.current.textSecondary
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaces.control)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
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
    val shape = RoundedCornerShape(14.dp)
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
                cornerRadius = 14.dp
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

@Composable
fun NovaActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    contentDescription: String = text,
    minHeight: Dp = 38.dp,
    cornerRadius: Dp = 14.dp,
    fontSize: TextUnit = 13.sp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(cornerRadius)
    val targetContainer = when {
        pressed && enabled && primary -> colors.accent.copy(alpha = colors.accent.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        pressed && enabled -> surfaces.selectedControl.copy(alpha = surfaces.selectedControl.alpha * NovaFocusMotionSpec.ButtonPressedAlpha)
        primary && enabled -> colors.accent
        focused -> surfaces.selectedControl
        else -> surfaces.control
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = novaFocusColorTween(),
        label = "NovaActionButtonContainer"
    )
    val contentColor = when {
        primary && enabled -> colors.onAccent
        enabled -> colors.textPrimary
        else -> colors.textMuted
    }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && primary -> colors.onAccent
            focused -> surfaces.focusRing
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
                role = Role.Button
            }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
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
