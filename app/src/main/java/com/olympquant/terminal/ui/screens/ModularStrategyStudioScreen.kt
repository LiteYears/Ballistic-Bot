package com.olympquant.terminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary

@Composable
fun ModularStrategyStudioScreen(
    telemetry: MarketTelemetryState,
    activeBalance: Double
) {
    val lockedStrategy = HardLockedMandate.lockedStrategyConfig()
    val lockedStake = HardLockedMandate.computeLockedStake(activeBalance)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(14.dp)
            .testTag("locked_strategy_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LOCKED HFT IMPULSE ENGINE",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "All legacy strategies and Boost Mode removed. Hard-locked to BallisticApex5s.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ElectricCyan.copy(alpha = 0.14f),
                    modifier = Modifier.border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Immutable",
                            tint = ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "IMMUTABLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 1. Immutable Execution & Risk Mandate Card
        item(key = "immutable_mandate_card") {
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
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "HARD-LOCKED RISK & SIZING PARAMETERS",
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan
                        )
                    }

                    MandateRuleRow(
                        label = "Position Sizing Formula",
                        value = "5% of Balance (Min $1.00 • Max $4,000.00)",
                        valueColor = BullishGreen
                    )
                    MandateRuleRow(
                        label = "Current Active Locked Stake",
                        value = "$${"%,.2f".format(lockedStake)} (from $${"%,.2f".format(activeBalance)} balance)",
                        valueColor = TextPrimary
                    )
                    MandateRuleRow(
                        label = "Daily Execution Quota",
                        value = "${telemetry.dailyTradesCount} / ${HardLockedMandate.MAX_TRADES_PER_DAY} Trades Today (Hard Stop @ 10)",
                        valueColor = if (telemetry.dailyTradesCount >= HardLockedMandate.MAX_TRADES_PER_DAY) BearishCrimson else BullishGreen
                    )
                    MandateRuleRow(
                        label = "Consecutive Loss Breaker",
                        value = "${telemetry.consecutiveLosses} / ${HardLockedMandate.MAX_CONSECUTIVE_LOSSES} Consecutive Losses (Hard Stop @ 3)",
                        valueColor = if (telemetry.consecutiveLosses >= HardLockedMandate.MAX_CONSECUTIVE_LOSSES) BearishCrimson else BullishGreen
                    )
                    MandateRuleRow(
                        label = "Contract Expiry & Cooldown",
                        value = "${HardLockedMandate.LOCKED_DURATION_SEC}s Fixed • ${HardLockedMandate.LOCKED_COOLDOWN_MS / 1000}s Cooldown • Boost Removed",
                        valueColor = TextSecondary
                    )
                }
            }
        }

        // 2. Proprietary Strategy Thesis Card
        item(key = "strategy_thesis_card") {
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
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lockedStrategy.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = BullishGreen
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = BullishGreen.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "ACTIVE • LOCKED",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = lockedStrategy.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        // 3. The 5 Locked Quantitative Gatekeeper Modules
        item(key = "gates_header") {
            Text(
                text = "5 LOCKED BALLISTIC CONTINUATION GATES",
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan
            )
        }

        items(lockedStrategy.filters, key = { it.filterType.name }) { rule ->
            val ft = rule.filterType
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = BullishGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = ft.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Locked Threshold: ${"%.2f".format(rule.threshold)} ${ft.unit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SlateSurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "LOCKED",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MandateRuleRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SlateSurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
    }
}
