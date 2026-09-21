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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.novaKeyChipSize

/**
 * What the backdrop crossfades between. Two targets are the same backdrop when they draw the
 * same artwork, whatever else changed about the game: a library refresh that only moves
 * last-launched or play time must not fade the one full-screen layer out and back in. The
 * presentation key already carries the game, the artwork revision and the asset URL, so it and
 * the artwork kind are the identity; the game rides along for the first load.
 */
internal class NovaLibraryCinematicBackdropTarget(
    val game: PolarisGame,
    val artworkKind: String,
    val presentationKey: String,
) {
    override fun equals(other: Any?): Boolean = other is NovaLibraryCinematicBackdropTarget &&
        other.artworkKind == artworkKind && other.presentationKey == presentationKey

    override fun hashCode(): Int = 31 * artworkKind.hashCode() + presentationKey.hashCode()
}

internal fun novaLibraryCinematicBackdropTarget(game: PolarisGame?): NovaLibraryCinematicBackdropTarget? =
    game?.let { game ->
        // One rule for every entry, owned by the mapper: a real hero, or the ambient field.
        // A poster stretched full-bleed was a slice of its wordmark behind the whole screen,
        // and Big Picture's bundled Steam mark was a grey smear; neither is drawn now.
        val artworkKind = NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game) ?: return@let null
        NovaLibraryCinematicBackdropTarget(
            game = game,
            artworkKind = artworkKind,
            presentationKey = PolarisApiClient.artworkPresentationKey(game, artworkKind),
        )
    }

/**
 * Fades the poster grid's own pixels out toward its bottom edge and paints nothing. The shared
 * cinematic backdrop is the one ground under the library. A wash painted in window colour over
 * the grid's bounds stopped at the grid's edge, above the controller hints, and once no panel
 * framed the grid that edge read as a second, darker background laid over the first (papi,
 * 2026-09-16: "it doubles in Grid and Compact"). Offscreen compositing keeps DstIn to the
 * grid's own layer, so the backdrop behind it is never touched.
 */
internal fun Modifier.novaLibraryGridBottomFade(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceIn(0f, size.height)
        if (fade <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Black,
                    0.45f to Color.Black.copy(alpha = 0.38f),
                    1.0f to Color.Transparent,
                ),
                startY = size.height - fade,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
internal fun NovaLibraryCinematicBackdrop(
    game: PolarisGame?,
    apiClient: PolarisApiClient,
    modifier: Modifier = Modifier,
    /**
     * How strongly the artwork reads. The cinematic stage pairs one hero with small
     * posters, so the art is the subject there; the grid puts twenty covers on screen and
     * the same artwork competes with them, so it is pulled back behind that wall.
     */
    strength: Float = 1f,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val backdropTarget = novaLibraryCinematicBackdropTarget(game)

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
                                apiClient.loadArtworkInto(view, target.game, PolarisGame.ARTWORK_KIND_HERO)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = (0.9f + (surfaces.focusedArtworkAlpha * 0.1f)) * strength
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
        // Sits in the empty space the right-aligned hints leave behind, so it costs no
        // vertical budget. Dropped on compact shells where that space is not free.
        if (!compact) {
            Text(
                text = stringResource(R.string.nova_stage_footer_brand),
                color = colors.textSecondary.copy(alpha = 0.72f),
                style = NovaChromeType.label(fontSize = 9.sp, letterSpacing = 0.18.em),
                lineHeight = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .testTag("nova-stage-footer-brand"),
            )
        }
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
                            .size(novaKeyChipSize(if (hint.key.length <= 2) 20.dp else 28.dp))
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
