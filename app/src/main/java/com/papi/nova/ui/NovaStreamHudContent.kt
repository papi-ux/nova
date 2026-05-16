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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

@Composable
fun NovaStreamHudContent(
    state: NovaHudUiState,
    modifier: Modifier = Modifier
) {
    when (state.mode) {
        NovaHudMode.FULL -> NovaStreamHudFull(state, modifier)
        NovaHudMode.BANNER -> NovaStreamHudBanner(state, modifier)
        NovaHudMode.FPS_ONLY -> NovaStreamHudFpsOnly(state, modifier)
    }
}

@Composable
private fun NovaStreamHudFull(state: NovaHudUiState, modifier: Modifier) {
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = state.autopilotLabel,
                    color = state.statusTone.hudColor(),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
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
private fun NovaStreamHudBanner(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier.widthIn(min = 0.dp),
        cornerRadius = 16.dp,
        padding = 8.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudStatusDot(state.statusTone, height = 20.dp)
            Text(
                text = state.autopilotCompactLabel,
                color = state.statusTone.hudColor(),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .widthIn(min = 36.dp),
                maxLines = 1
            )
            HudDivider()
            HudValueText(state.fpsLabel, state.fpsTone, size = 15)
            if (state.targetFpsLabel.isNotBlank()) {
                Text(
                    text = state.targetFpsLabel,
                    color = LocalNovaComposeColors.current.textMuted,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 1.dp)
                )
            }
            HudCompactText(state.latencyLabel, state.latencyTone, minWidth = 36.dp)
            HudCompactText(state.bitrateLabel, NovaHudTone.INFO, minWidth = 34.dp)
            HudCompactText(state.resolutionLabel, NovaHudTone.MUTED)
            HudCompactText(state.codecLabel, NovaHudTone.MUTED)
            NovaHudSparkline(
                samples = state.sparklineSamples,
                tone = state.fpsTone,
                modifier = Modifier
                    .padding(start = 9.dp)
                    .width(54.dp)
                    .height(15.dp)
            )
        }
    }
}

@Composable
private fun NovaStreamHudFpsOnly(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier.width(190.dp),
        cornerRadius = 18.dp,
        padding = 7.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudStatusDot(state.statusTone, height = 32.dp)
            Column(
                modifier = Modifier
                    .padding(start = 7.dp)
                    .width(48.dp)
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
            NovaHudSparkline(
                samples = state.sparklineSamples,
                tone = state.fpsTone,
                modifier = Modifier
                    .padding(start = 5.dp, end = 7.dp)
                    .width(42.dp)
                    .height(22.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Row {
                    HudCompactText(state.latencyLabel, state.latencyTone, minWidth = 32.dp)
                    HudCompactText(state.bitrateLabel, NovaHudTone.INFO, minWidth = 30.dp, startPadding = 5.dp)
                }
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(surfaces.panel.copy(alpha = 0.92f))
            .border(1.dp, surfaces.tileBorder.copy(alpha = 0.8f), RoundedCornerShape(cornerRadius))
            .padding(padding),
        content = content
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
    Column(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surfaces.control.copy(alpha = 0.82f))
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
        modifier = Modifier
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
            .clip(RoundedCornerShape(8.dp))
            .background(tone.hudColor())
    )
}

@Composable
private fun HudDivider() {
    Spacer(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .width(1.dp)
            .height(16.dp)
            .background(LocalNovaComposeColors.current.accent.copy(alpha = 0.35f))
    )
}

@Composable
private fun NovaHudSparkline(
    samples: List<Float>,
    tone: NovaHudTone,
    modifier: Modifier = Modifier
) {
    val lineColor = tone.hudColor()
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
        drawPath(fillPath, lineColor.copy(alpha = 0.16f))
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
