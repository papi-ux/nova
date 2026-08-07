package com.papi.nova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaInGameOverlayAlpha
import com.papi.nova.ui.compose.NovaRadius

@Composable
fun NovaStreamHudContent(
    state: NovaHudUiState,
    modifier: Modifier = Modifier,
    opacityScale: Float = 1f
) {
    val hudOpacityScale = rememberHudOpacityScale(opacityScale)
    CompositionLocalProvider(LocalNovaHudOpacityScale provides hudOpacityScale) {
        when (state.mode) {
            NovaHudMode.DEBUG -> NovaStreamHudDebug(state, modifier)
            NovaHudMode.PERFORMANCE -> NovaStreamHudPerformance(state, modifier)
            NovaHudMode.MINIMAL -> NovaStreamHudMinimal(state, modifier)
        }
    }
}

private val LocalNovaHudOpacityScale = compositionLocalOf { 1f }

internal const val NOVA_HUD_PERFORMANCE_PRIMARY_TAG = "nova_hud_performance_primary"
internal const val NOVA_HUD_PERFORMANCE_DETAILS_TAG = "nova_hud_performance_details"

@Composable
private fun rememberHudOpacityScale(opacityScale: Float): Float {
    return remember(opacityScale) { opacityScale.coerceIn(NovaHudPreferences.MIN_OPACITY_PERCENT / 100f, 1f) }
}

@Composable
private fun NovaStreamHudDebug(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier.width(236.dp),
        cornerRadius = 18.dp,
        padding = 10.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudStatusDot(state.statusTone, height = 40.dp)
            Column(
                modifier = Modifier
                    .padding(start = 9.dp)
                    .weight(1f)
            ) {
                HudTinyLabel("STREAM")
                Row(verticalAlignment = Alignment.Bottom) {
                    HudValueText(
                        text = state.fpsLabel,
                        tone = state.fpsTone,
                        size = 24
                    )
                    if (state.targetFpsLabel.isNotBlank()) {
                        Text(
                            text = state.targetFpsLabel,
                            color = LocalNovaComposeColors.current.textSecondary,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                            maxLines = 1
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.widthIn(max = 96.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = state.autopilotHudLabel,
                    color = state.statusTone.hudColor(),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.widthIn(max = 96.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.streamModeLabel.isNotBlank()) {
                    Text(
                        text = state.streamModeLabel,
                        color = LocalNovaComposeColors.current.textMuted,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        NovaHudSparkline(
            samples = state.sparklineSamples,
            tone = state.fpsTone,
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(top = 7.dp)
        )
        HudDiagnosticStrip(state)
        HudLayerHealthRow(state.layerHealth)
        HudEventBreadcrumb(state.eventBreadcrumbLabel)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HudMetric("1% LOW", state.lowOnePercentLabel, Modifier.weight(1f))
            HudMetric("RTT", state.latencyLabel, Modifier.weight(1f), valueTone = state.latencyTone)
            HudMetric("BIT", state.bitrateLabel, Modifier.weight(1f), valueTone = NovaHudTone.INFO)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HudMetric("CODEC", state.codecLabel.ifBlank { "--" }, Modifier.weight(1f), valueTone = NovaHudTone.INFO)
            HudMetric("RES", state.resolutionLabel, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NovaStreamHudPerformance(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier.widthIn(max = 320.dp),
        cornerRadius = 16.dp,
        padding = 8.dp
    ) {
        HudPerformancePrimaryRow(state)
        HudPerformanceDetailRow(state)
        HudCompactDiagnosticStrip(state)
        HudEventBreadcrumb(state.eventBreadcrumbLabel)
    }
}

@Composable
private fun HudPerformancePrimaryRow(state: NovaHudUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NOVA_HUD_PERFORMANCE_PRIMARY_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudStatusDot(state.statusTone, height = 20.dp)
        Text(
            text = state.autopilotCompactLabel,
            color = state.statusTone.hudColor(),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 5.dp)
                .widthIn(min = 28.dp, max = 42.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        HudDivider(horizontalPadding = 5.dp)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom
        ) {
            HudValueText(state.fpsLabel, state.fpsTone, size = 15)
            if (state.targetFpsLabel.isNotBlank()) {
                Text(
                    text = state.targetFpsLabel,
                    color = LocalNovaComposeColors.current.textMuted,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 1.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        NovaHudSparkline(
            samples = state.sparklineSamples,
            tone = state.fpsTone,
            modifier = Modifier
                .padding(start = 8.dp)
                .width(48.dp)
                .height(15.dp)
        )
    }
}

@Composable
private fun HudPerformanceDetailRow(state: NovaHudUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .testTag(NOVA_HUD_PERFORMANCE_DETAILS_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudCompactText(
            state.latencyLabel,
            state.latencyTone,
            modifier = Modifier.weight(1f),
            startPadding = 0.dp
        )
        HudCompactText(
            state.bitrateLabel,
            NovaHudTone.INFO,
            modifier = Modifier.weight(1f),
            startPadding = 0.dp
        )
        HudCompactText(
            state.resolutionLabel,
            NovaHudTone.MUTED,
            modifier = Modifier.weight(1f),
            startPadding = 0.dp
        )
        HudCompactText(
            state.codecLabel,
            NovaHudTone.MUTED,
            modifier = Modifier.weight(1f),
            startPadding = 0.dp
        )
    }
}

@Composable
private fun NovaStreamHudMinimal(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier.width(148.dp),
        cornerRadius = 18.dp,
        padding = 7.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudStatusDot(state.statusTone, height = 32.dp)
            Column(
                modifier = Modifier
                    .padding(start = 7.dp)
                    .width(54.dp)
            ) {
                HudTinyLabel("FPS")
                Row(verticalAlignment = Alignment.Bottom) {
                    HudValueText(state.fpsLabel, state.fpsTone, size = 19)
                    if (state.targetFpsLabel.isNotBlank()) {
                        Text(
                            text = state.targetFpsLabel,
                            color = LocalNovaComposeColors.current.textMuted,
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 1.dp, bottom = 2.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                HudCompactText(state.latencyLabel, state.latencyTone, minWidth = 34.dp)
                Text(
                    text = state.autopilotCompactLabel,
                    color = state.statusTone.hudColor(),
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (state.eventBreadcrumbLabel.isNotBlank()) {
            HudEventBreadcrumb(state.eventBreadcrumbLabel)
        }
    }
}

@Composable
private fun HudPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    padding: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    val panelShape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .shadow(16.dp * hudOpacityScale, panelShape, clip = false)
            .clip(panelShape)
            .background(surfaces.panel.copy(alpha = hudOpacityScale))
            .border(
                1.dp,
                surfaces.tileBorder.copy(alpha = NovaInGameOverlayAlpha.Border * hudOpacityScale),
                panelShape
            )
            .padding(padding),
        content = content
    )
}

@Composable
private fun HudDiagnosticStrip(state: NovaHudUiState) {
    if (state.healthReasonLabel.isBlank() && state.streamTruthLabel.isBlank()) return
    val surfaces = LocalNovaLibrarySurfaces.current
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp)
            .clip(RoundedCornerShape(NovaRadius.row))
            .background(surfaces.control.copy(alpha = NovaInGameOverlayAlpha.NestedControl * hudOpacityScale))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.healthReasonLabel,
            color = state.healthReasonTone.hudColor(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.7f)
        )
        Text(
            text = state.streamTruthLabel,
            color = LocalNovaComposeColors.current.textSecondary,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.3f)
        )
    }
}

@Composable
private fun HudCompactDiagnosticStrip(state: NovaHudUiState) {
    if (state.healthReasonLabel == "Stable" && state.streamTruthLabel.isBlank()) return
    Text(
        text = listOf(state.healthReasonLabel, state.streamTruthLabel).filter { it.isNotBlank() }.joinToString(" · "),
        color = state.healthReasonTone.hudColor(),
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 5.dp)
    )
}

@Composable
private fun HudLayerHealthRow(layers: List<NovaHudLayerHealth>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        layers.forEach { layer ->
            HudLayerChip(layer, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HudLayerChip(layer: NovaHudLayerHealth, modifier: Modifier = Modifier) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NovaRadius.chip))
            .background(surfaces.control.copy(alpha = NovaInGameOverlayAlpha.NestedControl * hudOpacityScale))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = layer.label,
            color = layer.tone.hudColor(),
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HudEventBreadcrumb(label: String) {
    if (label.isBlank()) return
    Text(
        text = label,
        color = LocalNovaComposeColors.current.accent,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 5.dp)
    )
}

@Composable
private fun HudMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTone: NovaHudTone = NovaHudTone.MUTED
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    Column(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(NovaRadius.row))
            .background(surfaces.control.copy(alpha = NovaInGameOverlayAlpha.NestedControl * hudOpacityScale))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center
    ) {
        HudTinyLabel(label)
        Text(
            text = value,
            color = valueTone.hudColor(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HudTinyLabel(text: String) {
    Text(
        text = text,
        color = LocalNovaComposeColors.current.textMuted,
        fontSize = 7.sp,
        lineHeight = 8.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HudValueText(text: String, tone: NovaHudTone, size: Int) {
    Text(
        text = text,
        color = tone.hudColor(),
        fontSize = size.sp,
        lineHeight = (size + 2).sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HudCompactText(
    text: String,
    tone: NovaHudTone,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
    startPadding: Dp = 9.dp
) {
    Text(
        text = text,
        color = tone.hudColor(),
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(start = startPadding)
            .widthIn(min = minWidth)
    )
}

@Composable
private fun HudStatusDot(tone: NovaHudTone, height: Dp) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(height)
            .clip(RoundedCornerShape(NovaRadius.pill))
            .background(tone.hudColor())
    )
}

@Composable
private fun HudDivider(horizontalPadding: Dp = 8.dp) {
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    Spacer(
        modifier = Modifier
            .padding(horizontal = horizontalPadding)
            .width(1.dp)
            .height(16.dp)
            .background(
                LocalNovaComposeColors.current.accent.copy(
                    alpha = NovaInGameOverlayAlpha.AccentDivider * hudOpacityScale
                )
            )
    )
}

@Composable
private fun NovaHudSparkline(
    samples: List<Float>,
    tone: NovaHudTone,
    modifier: Modifier = Modifier
) {
    val lineColor = tone.hudColor()
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    Canvas(modifier = modifier) {
        if (samples.size < 2 || size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }
        val min = samples.minOrNull() ?: return@Canvas
        val max = samples.maxOrNull() ?: return@Canvas
        val range = (max - min).coerceAtLeast(5f)
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
        val linePath = Path()
        val fillPath = Path()
        samples.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - min) / range) * (size.height - 2f) - 1f
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((samples.size - 1) * stepX, size.height)
        fillPath.close()
        drawLine(
            color = lineColor.copy(alpha = NovaInGameOverlayAlpha.SparklineGuide * hudOpacityScale),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f
        )
        drawLine(
            color = lineColor.copy(alpha = 0.10f * hudOpacityScale),
            start = Offset(0f, 1f),
            end = Offset(size.width, 1f),
            strokeWidth = 1f
        )
        drawPath(fillPath, lineColor.copy(alpha = NovaInGameOverlayAlpha.SparklineFill * hudOpacityScale))
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun NovaHudTone.hudColor(): Color {
    val colors = LocalNovaComposeColors.current
    return when (this) {
        NovaHudTone.STABLE -> Color(0xFF4ADE80)
        NovaHudTone.WARNING -> Color(0xFFFBBF24)
        NovaHudTone.DANGER -> Color(0xFFF87171)
        NovaHudTone.INFO -> colors.accent
        NovaHudTone.MUTED -> colors.textSecondary
    }
}
