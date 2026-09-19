package com.papi.nova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            NovaHudMode.SLIM -> NovaStreamHudSlim(state, modifier)
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

// Debug is Slim's line with the whole stream under it. The facts sit under the layer they
// belong to, HOST, NET and CLIENT, each headed by that layer's health, so the layer that went
// amber and the numbers that explain it line up. Label and value share a line and nothing
// has a box of its own: at a low opacity a box per fact outlived the panel it sat on.
@Composable
private fun NovaStreamHudDebug(state: NovaHudUiState, modifier: Modifier) {
    // 256dp: three columns hold "RES 1920×1080" at 10sp with room left.
    HudPanel(
        modifier = modifier.width(256.dp),
        cornerRadius = NovaRadius.hero,
        padding = 10.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudStatusDot(state.statusTone, height = 30.dp)
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    HudValueText(
                        text = state.fpsLabel,
                        tone = state.fpsTone,
                        size = 20
                    )
                    if (state.targetFpsLabel.isNotBlank()) {
                        Text(
                            text = state.targetFpsLabel,
                            color = LocalNovaComposeColors.current.textMuted,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                            maxLines = 1
                        )
                    }
                }
                NovaHudSparkline(
                    samples = state.sparklineSamples,
                    tone = state.fpsTone,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .width(96.dp)
                        .height(12.dp)
                )
            }
            Column(
                modifier = Modifier.widthIn(max = 96.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = state.autopilotHudLabel,
                    color = state.tuningTone.hudColor(),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.widthIn(max = 96.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.streamModeLabel.isNotBlank()) {
                    Text(
                        text = state.streamModeShortLabel,
                        color = LocalNovaComposeColors.current.textMuted,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        HudDiagnosticStrip(state.healthReasonLabel, state.healthReasonTone, state.streamTruthLabel)

        val host = state.layerHealth.getOrNull(0)
        val net = state.layerHealth.getOrNull(1)
        val client = state.layerHealth.getOrNull(2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            // The host's own latency, what it sends and how: encode time, the mode, the codec
            // and the bitrate it holds.
            HudLayerColumn(host?.label ?: "HOST", host?.tone ?: NovaHudTone.MUTED, Modifier.weight(1f)) {
                HudFact("HOST", state.hostLatencyLabel)
                HudFact("RES", state.resolutionLabel)
                HudFact("CODEC", state.codecLabel.ifBlank { "--" })
                HudFact("BIT", state.bitrateLabel)
            }
            HudColumnRule()
            // What the link does to it: the round trip and how much it wobbles, loss in the
            // current window graded so zero is the only green, and the frames that arrived.
            HudLayerColumn(net?.label ?: "NET", net?.tone ?: NovaHudTone.MUTED, Modifier.weight(1f)) {
                HudFact("RTT", state.latencyLabel, state.latencyTone)
                HudFact("JIT", state.jitterLabel)
                HudFact("LOSS", state.packetLossLabel, state.packetLossTone)
                HudFact("IN", state.incomingFpsLabel)
            }
            HudColumnRule()
            // What this device does with it: decode time graded against the frame budget, the
            // frames drawn, the worst one percent, and the frames the session lost.
            HudLayerColumn(client?.label ?: "CLIENT", client?.tone ?: NovaHudTone.MUTED, Modifier.weight(1f)) {
                HudFact("DEC", state.decodeTimeLabel, state.decodeTone)
                HudFact("OUT", state.renderedFpsLabel)
                HudFact("1% LOW", state.lowOnePercentLabel)
                HudFact("DROPS", state.framesLostLabel)
            }
        }
        HudEventBreadcrumb(state.eventBreadcrumbLabel)
    }
}

// Performance is Slim's line in two: the frame rate with its target and its last minute drawn
// beside it, then the four facts it is pinned to, each glued to its label the way Slim's are.
// The panel wraps what it holds rather than spreading four columns across 320 dp. Live Tuning
// stays in Debug: its label ("Tuning: On") was cut to 42 dp here and said nothing at a glance.
@Composable
private fun NovaStreamHudPerformance(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier.widthIn(max = 320.dp),
        cornerRadius = NovaRadius.hero,
        padding = 8.dp
    ) {
        HudPerformancePrimaryRow(state)
        HudPerformanceDetailRow(state)
        HudCompactDiagnosticStrip(state.healthReasonLabel, state.healthReasonTone, state.streamTruthLabel)
        HudEventBreadcrumb(state.eventBreadcrumbLabel)
    }
}

@Composable
private fun HudPerformancePrimaryRow(state: NovaHudUiState) {
    Row(
        modifier = Modifier
            .padding(start = 2.dp, end = 4.dp)
            .testTag(NOVA_HUD_PERFORMANCE_PRIMARY_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudStatusDot(state.statusTone, height = 16.dp)
        HudValueText(
            text = state.fpsLabel,
            tone = state.fpsTone,
            size = 15,
            modifier = Modifier.padding(start = 6.dp)
        )
        if (state.targetFpsLabel.isNotBlank()) {
            Text(
                text = state.targetFpsLabel,
                color = LocalNovaComposeColors.current.textMuted,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        NovaHudSparkline(
            samples = state.sparklineSamples,
            tone = state.fpsTone,
            modifier = Modifier
                .padding(start = 8.dp)
                .width(64.dp)
                .height(14.dp)
        )
    }
}

@Composable
private fun HudPerformanceDetailRow(state: NovaHudUiState) {
    // Starts under the frame rate, past the health bar, so the two lines read as one block.
    Row(
        modifier = Modifier
            .padding(start = 12.dp, top = 4.dp, end = 4.dp)
            .testTag(NOVA_HUD_PERFORMANCE_DETAILS_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudSlimStat("RTT", state.latencyLabel, state.latencyTone, startPadding = 0.dp)
        HudSlimStat("BIT", state.bitrateLabel, NovaHudTone.MUTED)
        HudSlimStat("RES", state.resolutionLabel, NovaHudTone.MUTED)
        HudSlimStat("CODEC", state.codecLabel.ifBlank { "--" }, NovaHudTone.MUTED)
    }
}

// Minimal is the smallest glance: Slim's pill with the frame rate, its target and the round
// trip, and nothing that needs reading. The health bar's color says whether all is well.
@Composable
private fun NovaStreamHudMinimal(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier,
        cornerRadius = NovaRadius.pill,
        padding = 5.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudStatusDot(state.statusTone, height = 16.dp)
            HudValueText(
                text = state.fpsLabel,
                tone = state.fpsTone,
                size = 15,
                modifier = Modifier.padding(start = 6.dp)
            )
            if (state.targetFpsLabel.isNotBlank()) {
                Text(
                    text = state.targetFpsLabel,
                    color = LocalNovaComposeColors.current.textMuted,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp),
                    maxLines = 1
                )
            }
            HudSlimStat("RTT", state.latencyLabel, state.latencyTone)
        }
    }
}

// One line, one glance, the way MangoHud's bar reads: the health bar, the frame rate with
// its last minute drawn beside it, then the three facts that explain a bad number. Each
// fact carries an inline label because two millisecond values cannot be told apart by
// position. No target, no autopilot text, no breadcrumb, no stacked tiles.
@Composable
private fun NovaStreamHudSlim(state: NovaHudUiState, modifier: Modifier) {
    HudPanel(
        modifier = modifier,
        cornerRadius = NovaRadius.pill,
        padding = 5.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudStatusDot(state.statusTone, height = 16.dp)
            HudValueText(
                text = state.fpsLabel,
                tone = state.fpsTone,
                size = 15,
                modifier = Modifier.padding(start = 6.dp)
            )
            NovaHudSparkline(
                samples = state.sparklineSamples,
                tone = state.fpsTone,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .width(44.dp)
                    .height(14.dp)
            )
            HudSlimStat("DEC", state.decodeTimeLabel, state.decodeTone)
            HudSlimStat("RTT", state.latencyLabel, state.latencyTone)
            HudSlimStat("BIT", state.bitrateLabel, NovaHudTone.MUTED)
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
            // Android draws an elevation shadow under the whole outline, and through a panel
            // that is not opaque it showed as a dark box. Only a solid panel casts one.
            .then(if (hudOpacityScale >= 1f) Modifier.shadow(16.dp, panelShape, clip = false) else Modifier)
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

// Slim's inline fact: a 7 sp label glued to its value, so two millisecond numbers in one
// line read apart without a stacked tile.
@Composable
private fun HudSlimStat(label: String, value: String, tone: NovaHudTone, startPadding: Dp = 8.dp) {
    Row(
        modifier = Modifier.padding(start = startPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = LocalNovaComposeColors.current.textMuted,
            fontSize = 7.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        HudCompactText(value, tone, startPadding = 3.dp)
    }
}

// The strips take only the strings and tones they draw. Handed the whole state, they
// recomposed on every sample because the fps changed, even when their own text had not;
// with plain parameters Compose skips them until their words actually change.
@Composable
private fun HudDiagnosticStrip(
    healthReasonLabel: String,
    healthReasonTone: NovaHudTone,
    streamTruthLabel: String
) {
    if (healthReasonLabel.isBlank() && streamTruthLabel.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = healthReasonLabel,
            color = healthReasonTone.hudColor(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.7f)
        )
        Text(
            text = streamTruthLabel,
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
private fun HudCompactDiagnosticStrip(
    healthReasonLabel: String,
    healthReasonTone: NovaHudTone,
    streamTruthLabel: String
) {
    if (healthReasonLabel == "Stable" && streamTruthLabel.isBlank()) return
    Text(
        text = listOf(healthReasonLabel, streamTruthLabel).filter { it.isNotBlank() }.joinToString(" · "),
        color = healthReasonTone.hudColor(),
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp)
    )
}

// A Debug column: the layer's health as a dot and its name, then its facts. The column takes
// an immutable label and tone, so it skips while the layer holds, as the chips it replaced did.
@Composable
private fun HudLayerColumn(
    label: String,
    tone: NovaHudTone,
    modifier: Modifier = Modifier,
    facts: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(NovaRadius.pill))
                    .background(tone.hudColor())
            )
            Text(
                text = label,
                color = tone.hudColor(),
                fontSize = 8.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        facts()
    }
}

// One Debug fact: the label at the left of its column and the value at the right, on one
// baseline, like a row of MangoHud's table.
@Composable
private fun HudFact(label: String, value: String, tone: NovaHudTone = NovaHudTone.MUTED) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = LocalNovaComposeColors.current.textMuted,
            fontSize = 7.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            text = value,
            color = tone.hudColor(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp)
                .alignByBaseline()
        )
    }
}

// A fixed height, the header and four facts: a rule that stretched to its row asked the row to
// measure every column twice on each tick, and Debug missed frames Slim did not.
private val HUD_COLUMN_RULE_HEIGHT = 66.dp

@Composable
private fun HudColumnRule() {
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .width(1.dp)
            .height(HUD_COLUMN_RULE_HEIGHT)
            .background(
                LocalNovaComposeColors.current.accent.copy(
                    alpha = NovaInGameOverlayAlpha.AccentDivider * hudOpacityScale
                )
            )
    )
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
private fun HudValueText(text: String, tone: NovaHudTone, size: Int, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = tone.hudColor(),
        fontSize = size.sp,
        lineHeight = (size + 2).sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
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
private fun NovaHudSparkline(
    samples: List<Float>,
    tone: NovaHudTone,
    modifier: Modifier = Modifier
) {
    val lineColor = tone.hudColor()
    val hudOpacityScale = LocalNovaHudOpacityScale.current
    // The sparkline redraws once a second for the life of a stream. Two paths that live
    // with the composable and get reset cost nothing; two fresh ones per draw were garbage.
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    Canvas(modifier = modifier) {
        if (samples.size < 2 || size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }
        val min = samples.minOrNull() ?: return@Canvas
        val max = samples.maxOrNull() ?: return@Canvas
        val range = (max - min).coerceAtLeast(5f)
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
        linePath.reset()
        fillPath.reset()
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
