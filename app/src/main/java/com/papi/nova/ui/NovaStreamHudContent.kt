@file:OptIn(ExperimentalLayoutApi::class)

package com.papi.nova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.TextUnit
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
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
import androidx.compose.material3.Text as MaterialText
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
    opacityScale: Float = 1f,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList()
) {
    val accessibleModifier = modifier.semantics {
        paneTitle = "Stream statistics"
        stateDescription = state.healthReasonLabel
        customActions = accessibilityActions
        // No live region: a screen reader must not announce every once-a-second tick.
    }
    val hudOpacityScale = rememberHudOpacityScale(opacityScale)
    CompositionLocalProvider(LocalNovaHudOpacityScale provides hudOpacityScale) {
        when (state.mode) {
            NovaHudMode.DEBUG -> NovaStreamHudDebug(state, accessibleModifier)
            NovaHudMode.PERFORMANCE -> NovaStreamHudPerformance(state, accessibleModifier)
            NovaHudMode.MINIMAL -> NovaStreamHudMinimal(state, accessibleModifier)
            NovaHudMode.SLIM -> NovaStreamHudSlim(state, accessibleModifier)
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
// belong to, HOST, NET and CLIENT. Groups wrap at larger font sizes; text keeps its
// readability backing even when the player's panel glass is clear.
@Composable
private fun NovaStreamHudDebug(state: NovaHudUiState, modifier: Modifier) {
    // Wrap the layer groups when font scale or the usable surface cannot hold all three.
    HudPanel(
        modifier = modifier.widthIn(max = 520.dp),
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
                        HudText(
                            text = state.targetFpsLabel,
                            color = LocalNovaComposeColors.current.textMuted,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                            maxLines = 2
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
                modifier = Modifier.widthIn(max = 150.dp),
                horizontalAlignment = Alignment.End
            ) {
                HudText(
                    text = state.autopilotHudLabel,
                    color = state.tuningTone.hudColor(),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.widthIn(max = 150.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.streamModeLabel.isNotBlank()) {
                    HudText(
                        text = state.streamModeShortLabel,
                        color = LocalNovaComposeColors.current.textMuted,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        HudDiagnosticStrip(state.healthReasonLabel, state.healthReasonTone, state.streamTruthLabel)

        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val minimum = (120 * LocalDensity.current.fontScale).dp
            val columns = ((maxWidth.value + 8) / (minimum.value + 8)).toInt().coerceIn(1, 3)
            val columnWidth = (maxWidth - 8.dp * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val host = state.layerHealth.getOrNull(0)
                val net = state.layerHealth.getOrNull(1)
                val client = state.layerHealth.getOrNull(2)
                HudLayerColumn(host?.label ?: "HOST", host?.tone ?: NovaHudTone.MUTED, Modifier.width(columnWidth)) {
                    HudFact("ENCODE", state.hostLatencyLabel)
                    HudFact("RES", state.resolutionLabel)
                    HudFact("CODEC", state.codecLabel.ifBlank { "--" })
                    HudFact("BIT", state.bitrateLabel)
                }
                HudLayerColumn(net?.label ?: "NET", net?.tone ?: NovaHudTone.MUTED, Modifier.width(columnWidth)) {
                    HudFact("RTT", state.latencyLabel, state.latencyTone)
                    HudFact("JITTER", state.jitterLabel)
                    HudFact("FRAME LOSS", state.packetLossLabel, state.packetLossTone)
                    HudFact("MISSING", state.framesLostLabel)
                    HudFact("IN", state.incomingFpsLabel)
                }
                HudLayerColumn(client?.label ?: "CLIENT", client?.tone ?: NovaHudTone.MUTED, Modifier.width(columnWidth)) {
                    HudFact("DECODE", state.decodeTimeLabel, state.decodeTone)
                    HudFact("OUT", state.renderedFpsLabel)
                    HudFact("WINDOW MIN", state.lowOnePercentLabel)
                    // A rate gap can include intentional pacing. It is not a cumulative drop counter.
                    HudFact("RENDER GAP", state.renderGapLabel)
                }
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
            HudText(
                text = state.targetFpsLabel,
                color = LocalNovaComposeColors.current.textMuted,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 3.dp),
                maxLines = 2,
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
    FlowRow(
        modifier = Modifier
            .padding(start = 12.dp, top = 4.dp, end = 4.dp)
            .testTag(NOVA_HUD_PERFORMANCE_DETAILS_TAG),
        verticalArrangement = Arrangement.spacedBy(3.dp)
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
        FlowRow(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            HudStatusDot(state.statusTone, height = 16.dp)
            HudValueText(
                text = state.fpsLabel,
                tone = state.fpsTone,
                size = 15,
                modifier = Modifier.padding(start = 6.dp)
            )
            if (state.targetFpsLabel.isNotBlank()) {
                HudText(
                    text = state.targetFpsLabel,
                    color = LocalNovaComposeColors.current.textMuted,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp),
                    maxLines = 2
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
        FlowRow(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
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
        modifier = Modifier.padding(start = startPadding).semantics(mergeDescendants = true) {
            contentDescription = hudMetricDescription(label, value)
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudText(
            text = label,
            color = LocalNovaComposeColors.current.textMuted,
            fontSize = 7.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2
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
        HudText(
            text = healthReasonLabel,
            color = healthReasonTone.hudColor(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.7f)
        )
        HudText(
            text = streamTruthLabel,
            color = LocalNovaComposeColors.current.textSecondary,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 2,
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
    HudText(
        text = listOf(healthReasonLabel, streamTruthLabel).filter { it.isNotBlank() }.joinToString(" · "),
        color = healthReasonTone.hudColor(),
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
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
        Row(modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: ${tone.accessibleLabel}"
        }, verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(NovaRadius.pill))
                    .background(tone.hudColor())
            )
            HudText(
                text = label,
                color = tone.hudColor(),
                fontSize = 8.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
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
            .padding(top = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = hudMetricDescription(label, value) },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        HudText(
            text = label,
            color = LocalNovaComposeColors.current.textMuted,
            fontSize = 7.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            modifier = Modifier.alignByBaseline()
        )
        HudText(
            text = value,
            color = tone.hudColor(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp)
                .alignByBaseline()
        )
    }
}

@Composable
private fun HudEventBreadcrumb(label: String) {
    if (label.isBlank()) return
    HudText(
        text = label,
        color = LocalNovaComposeColors.current.accent,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 5.dp)
    )
}

@Composable
private fun HudValueText(text: String, tone: NovaHudTone, size: Int, modifier: Modifier = Modifier) {
    HudText(
        text = text,
        color = tone.hudColor(),
        fontSize = size.sp,
        lineHeight = (size + 2).sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Rendered frame rate: $text frames per second"
        }
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
    HudText(
        text = text,
        color = tone.hudColor(),
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
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
            .semantics { contentDescription = "Stream status: ${tone.accessibleLabel}" }
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
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
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

/** Each text run has its own small backing, independent of the adjustable panel glass. */
@Composable
private fun HudText(
    text: String, color: Color, fontSize: TextUnit, lineHeight: TextUnit,
    modifier: Modifier = Modifier, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, maxLines: Int = 2,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val minimum = if (LocalNovaFormFactor.current == NovaFormFactor.Television) 14f else 11f
    val size = fontSize.value.coerceAtLeast(minimum)
    MaterialText(text = text, color = remember(color) { NovaHudReadability.foreground(color) },
        fontSize = size.sp, lineHeight = maxOf(lineHeight.value, size * 1.25f).sp,
        fontWeight = fontWeight, fontFamily = fontFamily, maxLines = maxLines, overflow = overflow,
        modifier = modifier.background(NovaHudReadability.backing, RoundedCornerShape(NovaRadius.chip))
            .padding(horizontal = NovaRadius.chip, vertical = 1.dp)
            .testTag("nova_hud_readability_backing"))
}

internal fun hudMetricDescription(label: String, value: String): String {
    val name = when (label) {
        "RTT" -> "Round trip latency"
        "JITTER" -> "Round trip variation"
        "DEC", "DECODE" -> "Decode time"
        "ENCODE" -> "Host processing time"
        "IN" -> "Received frame rate"
        "OUT" -> "Rendered frame rate"
        "WINDOW MIN" -> "Minimum of the last 60 FPS samples"
        "FRAME LOSS" -> "Video frames missing in the current window"
        "MISSING" -> "Video frames missing this session"
        "RENDER GAP" -> "Receive minus render rate, including intentional pacing"
        "BIT" -> "Bitrate"
        "RES" -> "Resolution"
        "CODEC" -> "Codec"
        else -> label
    }
    val reading = if (value.startsWith("--")) "unavailable" else when (label) {
        "IN", "OUT", "WINDOW MIN" -> "$value frames per second"
        "BIT" -> value.replace("Mbps", "megabits per second").replace("M", " megabits per second")
        else -> value.replace("ms", " milliseconds").replace("%", " percent").replace("FPS", "frames per second")
    }
    return "$name: $reading"
}

private val NovaHudTone.accessibleLabel: String get() = when (this) {
    NovaHudTone.STABLE -> "no issue in available readings"
    NovaHudTone.WARNING -> "attention"
    NovaHudTone.DANGER -> "high"
    NovaHudTone.INFO -> "observation"
    NovaHudTone.MUTED -> "unavailable"
}
