package com.olympquant.terminal.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olympquant.terminal.data.TradeJournalEntity
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.ui.theme.AmberGold
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.CyberCardBrush
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.NeonViolet
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateBorderBright
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextMuted
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val HmmStateLabels = listOf(
    "S0: Expansion", "S1: Reversal", "S2: Reversal", "S3: Expansion",
    "S4: Calm", "S5: Trending", "S6: Calm", "S7: Calm"
)

/**
 * Crash-immune, zero-allocation progress bar that sanitizes NaN / Infinity
 * and avoids Material3 ProgressBarRangeInfo semantics exceptions.
 */
@Composable
fun SafeQuantProgressBar(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val safeProgress = progress.finiteOr(0f).coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val radius = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(
            color = trackColor,
            size = Size(w, h),
            cornerRadius = radius
        )
        val fillW = (w * safeProgress).coerceIn(0f, w)
        if (fillW > 0.5f) {
            drawRoundRect(
                color = color,
                size = Size(fillW, h),
                cornerRadius = radius
            )
        }
    }
}

@Composable
fun HmmAndAuditScreen(telemetry: MarketTelemetryState) {
    val hmm = telemetry.hmmOutput
    val micro = telemetry.microstructure
    val fib = telemetry.fibLevels
    val radarPolygonPath = remember { Path() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hmm_audit_screen_list"),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "audit_title", contentType = "header") {
            Column {
                Text(
                    text = "8-STATE GAUSSIAN HMM & MICROSTRUCTURE AUDIT",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "Real-time forward posteriors (α_t), WebSocket order-flow imbalance & 50-tick Fibonacci diagnostics",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        // 1. WebSocket Stream Decision-Making Telemetry Card (Order-Flow Imbalance, Tick Intensity, Asset Lock, Clock Offset)
        item(key = "ws_stream_decision_telemetry", contentType = "ws_decision") {
            val ofi = telemetry.orderFlowImbalance.finiteOr(0.0)
            val intensity = telemetry.tickIntensityRatio.finiteOr(1.0)
            val ofiColor = when {
                ofi >= 15.0 -> BullishGreen
                ofi <= -15.0 -> BearishCrimson
                else -> ElectricCyan
            }
            val ofiNorm = ((ofi + 100.0) / 200.0).toFloat().finiteOr(0.5f).coerceIn(0.02f, 0.98f)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorderBright, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberCardBrush)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WEBSOCKET STREAM DECISION TELEMETRY",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                        Text(
                            text = if (telemetry.assetLocked) "⚠️ ASSET LOCKED" else "✅ TRADABLE (+${telemetry.assetPayoutPercent}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.assetLocked) BearishCrimson else BullishGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuditPill("ORDER FLOW", "${"%+.1f".format(ofi)}%", ofiColor, Modifier.weight(1f))
                        AuditPill("TICK INTENSITY", "${"%.2f".format(intensity)}x", AmberGold, Modifier.weight(1f))
                        AuditPill("SENTIMENT", "${telemetry.bullishSentimentPercent}% BULL", BullishGreen, Modifier.weight(1f))
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SELL PRESSURE",
                                style = MaterialTheme.typography.labelSmall,
                                color = BearishCrimson
                            )
                            Text(
                                text = "STREAM ORDER-FLOW IMBALANCE (${"%+.1f".format(ofi)}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ofiColor
                            )
                            Text(
                                text = "BUY PRESSURE",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen
                            )
                        }
                        SafeQuantProgressBar(
                            progress = ofiNorm,
                            color = BullishGreen,
                            trackColor = BearishCrimson.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    Text(
                        text = "Sub-second e:1 Clock Δ: ${telemetry.serverClockOffsetMs}ms | Effective Next Stake: $${"%.2f".format(telemetry.nextEffectiveStake.finiteOr(10.0))} | Daily P&L: $${"%+.2f".format(telemetry.dailyNetPnl.finiteOr(0.0))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // 2. 8-State HMM Polar Radar Wheel + Posterior Distribution Matrix
        item(key = "hmm_posterior_matrix", contentType = "hmm_matrix") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorderBright, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberCardBrush)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FORWARD POSTERIOR PROBABILITIES (α_t)",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                        Text(
                            text = if (hmm != null) "Active: S${hmm.state} (${hmm.regimeName}) | ${hmm.duration}t" else "Warmup (<25 ticks)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberGold
                        )
                    }

                    val probs = hmm?.stateProbabilities ?: List(8) { 0.125 }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 8-Axis Polar Radar Canvas
                        Canvas(
                            modifier = Modifier
                                .size(132.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ObsidianBackground)
                                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                                .padding(10.dp)
                        ) {
                            if (size.width <= 4f || size.height <= 4f) return@Canvas
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = (min(cx, cy) * 0.88f).coerceAtLeast(1f)

                            drawCircle(color = SlateBorder.copy(alpha = 0.45f), radius = radius * 0.35f, center = Offset(cx, cy), style = Stroke(1f))
                            drawCircle(color = SlateBorder.copy(alpha = 0.45f), radius = radius * 0.70f, center = Offset(cx, cy), style = Stroke(1f))
                            drawCircle(color = SlateBorder.copy(alpha = 0.7f), radius = radius, center = Offset(cx, cy), style = Stroke(1f))

                            radarPolygonPath.reset()
                            for (i in 0 until 8) {
                                val angle = (i * 45.0 - 90.0) * (PI / 180.0)
                                val ax = cx + (cos(angle) * radius).toFloat()
                                val ay = cy + (sin(angle) * radius).toFloat()
                                drawLine(
                                    color = SlateBorder.copy(alpha = 0.4f),
                                    start = Offset(cx, cy),
                                    end = Offset(ax, ay),
                                    strokeWidth = 1f
                                )

                                val rawP = probs.getOrElse(i) { 0.125 }.finiteOr(0.125)
                                val normP = rawP.toFloat().finiteOr(0.125f).coerceIn(0.08f, 1f)
                                val px = cx + (cos(angle) * radius * normP).toFloat()
                                val py = cy + (sin(angle) * radius * normP).toFloat()
                                if (i == 0) radarPolygonPath.moveTo(px, py) else radarPolygonPath.lineTo(px, py)
                            }
                            radarPolygonPath.close()

                            drawPath(
                                path = radarPolygonPath,
                                color = ElectricCyan.copy(alpha = 0.24f)
                            )
                            drawPath(
                                path = radarPolygonPath,
                                color = ElectricCyan,
                                style = Stroke(width = 2f)
                            )
                        }

                        // Right Column: 8-State Safe Progress Bars
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            HmmStateLabels.forEachIndexed { idx, name ->
                                val rawP = probs.getOrElse(idx) { 0.0 }.finiteOr(0.0)
                                val p = rawP.toFloat().finiteOr(0f).coerceIn(0f, 1f)
                                val isDominant = hmm?.state == idx
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isDominant) BullishGreen else TextSecondary
                                        )
                                        Text(
                                            text = "${"%.1f".format(p * 100f)}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isDominant) BullishGreen else TextPrimary
                                        )
                                    }
                                    SafeQuantProgressBar(
                                        progress = p,
                                        color = if (isDominant) BullishGreen else ElectricCyan,
                                        trackColor = SlateSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Microstructure, Landmark & 50-Tick Fibonacci Telemetry Audit Card
        item(key = "micro_fib_audit", contentType = "micro_fib") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorderBright, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberCardBrush)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "MICROSTRUCTURE, LANDMARKS & 50-TICK FIBONACCI AUDIT",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuditPill("IMPULSE", "${"%.1f".format(micro.microImpulseScore.finiteOr(0.0))}%", AmberGold, Modifier.weight(1f))
                        AuditPill("EFFICIENCY", "${"%.1f".format(micro.directionalEfficiency.finiteOr(0.0))}%", ElectricCyan, Modifier.weight(1f))
                        AuditPill("MCI INDEX", "${"%.1f".format(micro.mci.finiteOr(50.0))}", NeonViolet, Modifier.weight(1f))
                    }

                    // Breakout vs Mean-Reversion Probability Split Bar
                    val brkPct = (micro.breakoutProb.finiteOr(50.0) / 100.0).toFloat().finiteOr(0.5f).coerceIn(0.01f, 0.99f)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "BREAKOUT PROB ${"%.0f".format(micro.breakoutProb.finiteOr(50.0))}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen
                            )
                            Text(
                                text = "MEAN-REVERSION ${"%.0f".format(micro.meanReversionProb.finiteOr(50.0))}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberGold
                            )
                        }
                        SafeQuantProgressBar(
                            progress = brkPct,
                            color = BullishGreen,
                            trackColor = AmberGold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    Text(
                        text = "Persistence: ${"%.1f".format(micro.microPersistence.finiteOr(0.0))} | Hesitation: ${"%.1f".format(micro.microHesitation.finiteOr(0.0))}% | Exhaustion: ${"%.1f".format(micro.microExhaustion.finiteOr(0.0))}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "Landmark: ${micro.activeLandmark.name} | Phase: ${micro.phase.name} | Energy: ${micro.energyState.name} (${micro.urgency.name})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )

                    if (fib.high50.isFinite() && fib.low50.isFinite() && fib.high50 > fib.low50) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ObsidianBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "50-TICK FIBONACCI RETRACEMENT LADDER (High ${"%.4f".format(fib.high50)} / Low ${"%.4f".format(fib.low50)})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberGold
                                )
                                Text(
                                    text = "23.6%: ${"%.4f".format(fib.fib236.finiteOr(0.0))}  |  38.2%: ${"%.4f".format(fib.fib382.finiteOr(0.0))}  |  50.0%: ${"%.4f".format(fib.fib500.finiteOr(0.0))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "61.8%: ${"%.4f".format(fib.fib618.finiteOr(0.0))}  |  78.6%: ${"%.4f".format(fib.fib786.finiteOr(0.0))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Context Enhancer & S/R Memory Corridor Diagnostics
        item(key = "context_sr_audit", contentType = "sr_audit") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "STRUCTURAL MEMORY & CONTEXT ENHANCER",
                        style = MaterialTheme.typography.labelLarge,
                        color = AmberGold
                    )
                    Text(
                        text = "Adaptive S/R Corridor Width: ${"%.4f".format(telemetry.adaptiveCorridorWidth.finiteOr(0.2))} | Nearest S/R Distance: ${"%.4f".format(telemetry.srDistance.finiteOr(999.0))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Support Zone: ${telemetry.nearestSupport?.let { "${"%.4f".format(it.centerPrice.finiteOr(0.0))} (${it.touchCount} touches)" } ?: "None in active 300-candle window"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BullishGreen
                    )
                    Text(
                        text = "Resistance Zone: ${telemetry.nearestResistance?.let { "${"%.4f".format(it.centerPrice.finiteOr(0.0))} (${it.touchCount} touches)" } ?: "None in active 300-candle window"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BearishCrimson
                    )
                    if (telemetry.contextBlockReasons.isEmpty()) {
                        Text(
                            text = "✅ Zero Structural or Stream Danger Blocks Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = BullishGreen
                        )
                    } else {
                        telemetry.contextBlockReasons.forEach { reason ->
                            Text(
                                text = "⛔ BLOCK: $reason",
                                style = MaterialTheme.typography.labelMedium,
                                color = BearishCrimson
                            )
                        }
                    }
                }
            }
        }

        // 5. 12-Feature Scaled HMM Vector Inspector
        item(key = "hmm_features_audit", contentType = "features") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "12-FEATURE STANDARDIZED EMISSION INPUTS (Z-SCORES)",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    val features = hmm?.scaledFeatures ?: emptyMap()
                    if (features.isEmpty()) {
                        Text(
                            text = "Waiting for 25+ live ticks to standardize rolling return & velocity vectors.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    } else {
                        features.entries.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { (k, rawV) ->
                                    val v = rawV.finiteOr(0.0)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = SlateSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = k, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                            Text(
                                                text = "%+.2fσ".format(v),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (abs(v) > 2.0) AmberGold else TextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, SlateBorder, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = SlateSurfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(text = value, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

private data class JournalStats(
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val netPnl: Double,
    val profitFactor: Double,
    val expectedValue: Double,
    val equityCurve: List<Double>
)

@Composable
fun TradeJournalScreen(
    trades: List<TradeJournalEntity>,
    onClearJournal: () -> Unit,
    onExportJson: () -> String
) {
    var exportedJsonDialog by remember { mutableStateOf<String?>(null) }

    val stats by remember(trades) {
        derivedStateOf {
            val resolved = trades.filter { it.outcome == "WIN" || it.outcome == "LOSS" }
            val wins = resolved.count { it.outcome == "WIN" }
            val losses = resolved.count { it.outcome == "LOSS" }
            val winRate = if (resolved.isNotEmpty()) (wins.toDouble() / resolved.size * 100.0) else 0.0
            val netPnl = trades.sumOf { it.profitLoss.finiteOr(0.0) }
            val grossWin = trades.filter { it.profitLoss > 0 }.sumOf { it.profitLoss.finiteOr(0.0) }
            val grossLoss = abs(trades.filter { it.profitLoss < 0 }.sumOf { it.profitLoss.finiteOr(0.0) })
            val profitFactor = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) 9.99 else 0.0
            val expectedValue = if (resolved.isNotEmpty()) netPnl / resolved.size else 0.0

            val chronological = resolved.asReversed()
            val curve = ArrayList<Double>(chronological.size + 1)
            var running = 0.0
            curve.add(0.0)
            for (t in chronological) {
                running += t.profitLoss.finiteOr(0.0)
                curve.add(running)
            }
            JournalStats(
                wins = wins,
                losses = losses,
                winRate = winRate,
                netPnl = netPnl,
                profitFactor = profitFactor,
                expectedValue = expectedValue,
                equityCurve = curve
            )
        }
    }

    val equityPath = remember { Path() }
    val equityAreaPath = remember { Path() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "QUANTITATIVE TRADING JOURNAL",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "Full telemetry snapshot at entry + live cumulative equity curve",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportedJsonDialog = onExportJson() },
                    modifier = Modifier.testTag("export_journal_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Export JSON")
                    Spacer(Modifier.width(4.dp))
                    Text("JSON")
                }
                OutlinedButton(
                    onClick = onClearJournal,
                    modifier = Modifier.testTag("clear_journal_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Journal", tint = BearishCrimson)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JournalKpiCard("WIN RATE", "${"%.1f".format(stats.winRate)}% (${stats.wins}/${stats.losses})", BullishGreen, Modifier.weight(1f))
            JournalKpiCard("NET P&L", "$${"%+.2f".format(stats.netPnl)}", if (stats.netPnl >= 0) BullishGreen else BearishCrimson, Modifier.weight(1f))
            JournalKpiCard("EV / PF", "${"%+.2f".format(stats.expectedValue)} | ${"%.2f".format(stats.profitFactor)}", ElectricCyan, Modifier.weight(1f))
        }

        if (stats.equityCurve.size >= 2) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorderBright, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CUMULATIVE SESSION EQUITY CURVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                        Text(
                            text = "${stats.equityCurve.size - 1} Resolved Trades",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBackground)
                            .padding(8.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        if (w <= 4f || h <= 4f) return@Canvas
                        val pts = stats.equityCurve
                        val minV = min(0.0, pts.minOrNull() ?: 0.0)
                        val maxV = max(1.0, pts.maxOrNull() ?: 1.0)
                        val span = (maxV - minV).coerceAtLeast(1e-3)
                        val stepX = w / max(1, pts.size - 1)

                        fun valToY(v: Double): Float {
                            return (h - ((v - minV) / span * h)).toFloat().finiteOr(h / 2f).coerceIn(0f, h)
                        }

                        val zeroY = valToY(0.0)
                        drawLine(
                            color = SlateBorder,
                            start = Offset(0f, zeroY),
                            end = Offset(w, zeroY),
                            strokeWidth = 1f
                        )

                        equityPath.reset()
                        equityAreaPath.reset()
                        pts.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = valToY(v)
                            if (idx == 0) {
                                equityPath.moveTo(x, y)
                                equityAreaPath.moveTo(x, h)
                                equityAreaPath.lineTo(x, y)
                            } else {
                                equityPath.lineTo(x, y)
                                equityAreaPath.lineTo(x, y)
                            }
                            if (idx == pts.size - 1) {
                                equityAreaPath.lineTo(x, h)
                                equityAreaPath.close()
                            }
                        }

                        val lineColor = if (stats.netPnl >= 0) BullishGreen else BearishCrimson
                        drawPath(
                            path = equityAreaPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(lineColor.copy(alpha = 0.24f), Color.Transparent)
                            )
                        )
                        drawPath(
                            path = equityPath,
                            color = lineColor,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }

        if (trades.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .background(SlateSurface, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No trades logged yet. Start the bot or use CALL UP / PUT DOWN on the Terminal tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(trades, key = { it.id }, contentType = { "trade_row" }) { trade ->
                    TradeJournalRowCard(trade = trade)
                }
            }
        }
    }

    exportedJsonDialog?.let { jsonStr ->
        AlertDialog(
            onDismissRequest = { exportedJsonDialog = null },
            title = { Text("Exported trades_history.json") },
            text = {
                Column(modifier = Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                    Text(text = jsonStr, style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }
            },
            confirmButton = {
                Button(
                    onClick = { exportedJsonDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Text("Done", color = ObsidianBackground)
                }
            }
        )
    }
}

@Composable
private fun JournalKpiCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = SlateSurface
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TradeJournalRowCard(trade: TradeJournalEntity) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val badgeColor = when (trade.outcome) {
        "WIN" -> BullishGreen
        "LOSS" -> BearishCrimson
        "PENDING" -> AmberGold
        else -> TextSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${trade.pair} • ${trade.direction}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (trade.direction == "UP") BullishGreen else BearishCrimson
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$${"%.0f".format(trade.stake.finiteOr(10.0))} (${trade.durationSeconds}s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "${trade.outcome} (${"%+.2f".format(trade.profitLoss.finiteOr(0.0))})",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = "Strategy: ${trade.strategyName} | Account: ${trade.accountGroup.uppercase()} | ${timeFmt.format(Date(trade.timestampMs))}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryBadge("WS ${trade.wsLatencyMs}ms")
                TelemetryBadge("HMM S${trade.hmmState} ${trade.hmmRegime} (${(trade.hmmConfidence.finiteOr(0.0) * 100).toInt()}%)")
                TelemetryBadge("HVOI ${"%.0f".format(trade.hvoiScore.finiteOr(50.0))}%")
                TelemetryBadge("Cons ${"%.2f".format(trade.consistency.finiteOr(0.0))}")
                TelemetryBadge("SlopeR ${"%.2f".format(trade.slopeRatio.finiteOr(0.0))}x")
            }
        }
    }
}

@Composable
private fun TelemetryBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SlateSurfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = ElectricCyan,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
