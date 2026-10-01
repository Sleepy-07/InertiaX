package com.sleepingheads.sihpro.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepingheads.sihpro.data.model.DemoTripInfo
import com.sleepingheads.sihpro.ui.navigation.NavigationViewModel

@Composable
fun HomeScreen(
    viewModel: NavigationViewModel,
    onStartDemo: () -> Unit,
    onViewResults: () -> Unit,
    onViewDiagnostics: () -> Unit
) {
    val scrollState = rememberScrollState()
    val availableTrips = viewModel.availableTrips
    val selectedTrip by viewModel.selectedTrip.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // App Identity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "GeoReckon",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x3300E676))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("MVP", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "Smart India Hackathon • Problem SIH26168",
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Offline Status Chip
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x2200E5FF))
                    .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(modifier = Modifier.size(7.dp).background(Color(0xFF00E5FF), CircleShape))
                Text("100% Offline", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Section Title: Route Selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SELECT BENCHMARK SCENARIO ROUTE",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "${availableTrips.size} Curated Trips",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Route Selection Window (List of curated trip cards)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            availableTrips.forEach { trip ->
                val isSelected = trip.id == selectedTrip.id
                TripSelectionCard(
                    trip = trip,
                    isSelected = isSelected,
                    onSelect = { viewModel.selectTrip(trip) }
                )
            }
        }

        // Active Demonstration Preview Card (Hero Launcher)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
            shape = RoundedCornerShape(22.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.linearGradient(
                    listOf(
                        getBadgeColor(selectedTrip.badge),
                        Color(0xFF00E5FF)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(getBadgeColor(selectedTrip.badge).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = selectedTrip.badge,
                                color = getBadgeColor(selectedTrip.badge),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = selectedTrip.scenarioType,
                            color = Color(0xFFB0BEC5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "${selectedTrip.outageDurationSec.toInt()}s Blackout",
                        color = Color(0xFFFF9100),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = selectedTrip.title,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedTrip.description,
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }

                // Run Highlights Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MiniStat("Outage Duration", "${selectedTrip.outageDurationSec} s")
                    MiniStat("Speed Profile", selectedTrip.speedKmh)
                    MiniStat("Drift Verification", "${selectedTrip.driftPercent} (PASS)")
                }

                Button(
                    onClick = onStartDemo,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Start Simulation: ${selectedTrip.badge}",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Secondary Action Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                title = "System Diagnostics",
                subtitle = "Real-time Telemetry",
                icon = Icons.Default.QueryStats,
                accentColor = Color(0xFF00E5FF),
                onClick = onViewDiagnostics,
                modifier = Modifier.weight(1f)
            )

            ActionTile(
                title = "Benchmark Scorecard",
                subtitle = "ISRO Target Verification",
                icon = Icons.Default.Assessment,
                accentColor = Color(0xFFFF9100),
                onClick = onViewResults,
                modifier = Modifier.weight(1f)
            )
        }

        // Core Engineering Architecture Pillar Cards
        Text(
            text = "SYSTEM ARCHITECTURE PILLARS",
            color = Color(0xFF90A4AE),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ArchitectureCard(
                phase = "Phase 1 & 2",
                title = "Signal Cleaning & Alignment",
                description = "4th order Butterworth (3 Hz cutoff) + MAD outlier removal. Static gravity leveling + forward acceleration yaw estimation.",
                icon = Icons.Default.FilterAlt,
                accentColor = Color(0xFF90CAF9)
            )

            ArchitectureCard(
                phase = "Phase 3",
                title = "KinoNet-R2 Speed Model",
                description = "1D-CNN running causal 20-sample IMU windows to predict forward velocity μ and heteroscedastic uncertainty σ².",
                icon = Icons.Default.Psychology,
                accentColor = Color(0xFFCE93D8)
            )

            ArchitectureCard(
                phase = "Phase 4",
                title = "Invariant EKF & Vehicle Constraints",
                description = "State on SE₂(3) manifold with Non-Holonomic Constraints (lateral/vertical velocity ≈ 0) and Zero Velocity Updates (ZUPT).",
                icon = Icons.Default.DirectionsCar,
                accentColor = Color(0xFFFFCC80)
            )

            ArchitectureCard(
                phase = "Phase 5",
                title = "Anti-Snapping GNSS Recovery",
                description = "Mahalanobis innovation gating with 9.21 critical gate. Smooth uncertainty pull rather than instantaneous position teleportation.",
                icon = Icons.Default.GpsFixed,
                accentColor = Color(0xFFA5D6A7)
            )
        }
    }
}

@Composable
private fun TripSelectionCard(
    trip: DemoTripInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val badgeColor = getBadgeColor(trip.badge)
    val borderColor = if (isSelected) badgeColor else Color(0x22FFFFFF)
    val bgColor = if (isSelected) Color(0xFF161F2E) else Color(0xFF12161F)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                if (isSelected) listOf(badgeColor, Color(0xFF00E5FF)) else listOf(borderColor, borderColor)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scenario Badge Icon / Pill
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = if (isSelected) 0.25f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (trip.badge) {
                            "FLAGSHIP" -> Icons.Default.AltRoute
                            "MANEUVER" -> Icons.Default.TurnRight
                            "ZUPT" -> Icons.Default.Traffic
                            else -> Icons.Default.Speed
                        },
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = trip.title,
                            color = if (isSelected) Color.White else Color(0xFFECEFF1),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "${trip.scenarioType} • ${trip.speedKmh} • Drift: ${trip.driftPercent}",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }
            }

            // Radio / Checkbox Indicator
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) badgeColor else Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private fun getBadgeColor(badge: String): Color {
    return when (badge.uppercase()) {
        "FLAGSHIP" -> Color(0xFF00E676)
        "MANEUVER" -> Color(0xFFE040FB)
        "ZUPT" -> Color(0xFFFFAB00)
        "HIGHWAY" -> Color(0xFF00E5FF)
        else -> Color(0xFF00E676)
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFF78909C), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x11FFFFFF))))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accentColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF78909C), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ArchitectureCard(
    phase: String,
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161F)),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0x1AFFFFFF), Color(0x0AFFFFFF))))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(phase, color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("•", color = Color(0xFF546E7A), fontSize = 10.sp)
                    Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(description, color = Color(0xFF90A4AE), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}
