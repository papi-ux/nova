package com.papi.nova.ui

import android.graphics.Color as AndroidColor
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaControllerHint

private data class NovaLibraryCinematicBackdropTarget(
    val game: PolarisGame,
    val artworkKind: String,
    val presentationKey: String,
)

@Composable
internal fun NovaLibraryCinematicBackdrop(
    game: PolarisGame?,
    apiClient: PolarisApiClient,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val backdropTarget = game?.let { game ->
        val hasCachedHero = game.artworkAsset(PolarisGame.ARTWORK_KIND_HERO)?.cached == true
        val artworkKind = if (hasCachedHero) PolarisGame.ARTWORK_KIND_HERO else PolarisGame.ARTWORK_KIND_POSTER
        NovaLibraryCinematicBackdropTarget(
            game = game,
            artworkKind = artworkKind,
            presentationKey = PolarisApiClient.artworkPresentationKey(game, artworkKind),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("nova-library-cinematic-backdrop"),
    ) {
        Crossfade(
            targetState = backdropTarget,
            animationSpec = tween(durationMillis = 320),
            label = "NovaLibraryCinematicBackdrop",
        ) { target ->
            if (target == null) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                key(target.presentationKey) {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(AndroidColor.TRANSPARENT)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                isFocusable = false
                                isFocusableInTouchMode = false
                                isClickable = false
                                isLongClickable = false
                                contentDescription = null
                            }
                        },
                        update = { view ->
                            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            view.isFocusable = false
                            view.isFocusableInTouchMode = false
                            view.isClickable = false
                            view.isLongClickable = false
                            view.contentDescription = null
                            if (view.getTag(R.id.nova_artwork_presentation_key) != target.presentationKey) {
                                view.setTag(R.id.nova_artwork_presentation_key, target.presentationKey)
                                view.setImageDrawable(null)
                                if (target.artworkKind == PolarisGame.ARTWORK_KIND_HERO) {
                                    apiClient.loadArtworkInto(view, target.game, PolarisGame.ARTWORK_KIND_HERO)
                                } else {
                                    apiClient.loadCoverInto(view, target.game)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = 0.9f + (surfaces.focusedArtworkAlpha * 0.1f)
                            }
                            .testTag("nova-library-cinematic-artwork"),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to colors.window.copy(alpha = 0.75f),
                            0.48f to colors.window.copy(alpha = 0.22f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to colors.window.copy(alpha = 0.62f),
                            0.18f to colors.window.copy(alpha = 0.18f),
                            0.66f to colors.window.copy(alpha = 0.14f),
                            1.0f to colors.window.copy(alpha = 0.78f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
internal fun NovaLibraryCinematicControllerHints(
    hints: List<NovaControllerHint>,
    semanticsDescription: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val opacityScale = LocalNovaMenuOpacityScale.current
    val itemSpacing = if (compact) 10.dp else 14.dp
    val rowMaxWidth = if (compact) 600.dp else 760.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .semantics {
                contentDescription = semanticsDescription
            }
            .testTag("nova-library-cinematic-controller-hints"),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = rowMaxWidth)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp)
                .padding(vertical = 6.dp)
                .testTag("nova-library-cinematic-controller-hints-row"),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hints.forEach { hint ->
                Row(
                    modifier = Modifier.clearAndSetSemantics { },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (hint.key.length <= 2) 20.dp else 28.dp)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.72f * opacityScale)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = hint.key,
                            color = colors.onAccent,
                            fontSize = if (hint.key.length <= 2) 8.sp else 6.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = hint.label,
                        color = colors.textPrimary.copy(alpha = 0.88f),
                        fontSize = if (compact) 10.sp else 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
