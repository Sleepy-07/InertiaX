package com.sleepingheads.sihpro.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepingheads.sihpro.ui.navigation.NavigationViewModel
import com.sleepingheads.sihpro.ui.theme.ThemeManager

@Composable
fun ResultsScreen(
    viewModel: NavigationViewModel,
    onReplay: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    val isDark = ThemeManager.isDarkTheme.value
    val state by viewModel.playbackState.collectAsState()
    val meta = state.metadata
    val scrollState = rememberScrollState()

    val drift = meta?.driftPercent ?: 0.10
    val targetDrift = meta?.isroTargetPercent ?: 10.0
    val isPassed = drift < targetDrift

    // Theme Color Tokens
    val screenBg = if (isDark) Color(0xFF0B0E14) else Color(0xFFF8FAFC)
    val cardBg = if (isDark) Color(0xFF141A24) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color(0xFF1E2838) else Color(0xFFE2E8F0)
    val textPrimary = if (isDark) Color.White else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF90A4AE) else Color(0xFF475569)
    val textMuted = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val navButtonBg = if (isDark) Color(0x22FFFFFF) else Color(0xFFF1F5F9)
    val navButtonTint = if (isDark) Color.White else Color(0xFF0F172A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // ── Modern Top App Bar with Proper Back Button ──────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateHome,
                modifier = Modifier
                    .size(42.dp)
                    .background(navButtonBg, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = navButtonTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TRIP BENCHMARK REPORT",
                    color = textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "ISRO Dead-Reckoning Evaluation",
                    color = textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = onNavigateToDiagnostics,
                modifier = Modifier
                    .size(42.dp)
                    .background(navButtonBg, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.QueryStats,
                    contentDescription = "Diagnostics",
                    tint = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── Title & Mission Flow ──────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = meta?.title ?: "Delhi CP Expressway Run",
                color = textPrimary,
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "GNSS-AIDED",
                    color = if (isDark) Color(0xFF00E676) else Color(0xFF059669),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = textMuted,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    "DEAD RECKONING",
                    color = if (isDark) Color(0xFFFF9100) else Color(0xFFD97706),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = textMuted,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    "REACQUISITION",
                    color = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Primary Hero Card: ISRO Benchmark Drift Score ─────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(
                    if (isPassed) {
                        if (isDark) listOf(Color(0xFF00E676), Color(0xFF00E5FF))
                        else listOf(Color(0xFF059669), Color(0xFF0284C7))
                    } else {
                        listOf(Color(0xFFFF5252), Color(0xFFFF9100))
                    }
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "ENDPOINT DRIFT PERCENTAGE [Simulation]",
                    color = textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.2f", drift),
                        color = if (isPassed) {
                            if (isDark) Color(0xFF00E676) else Color(0xFF059669)
                        } else Color(0xFFEF4444),
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "%",
                        color = textSecondary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
                    )
                }

                // ISRO Benchmark Chip
                val chipAccent = if (isPassed) {
                    if (isDark) Color(0xFF00E676) else Color(0xFF059669)
                } else Color(0xFFEF4444)

                val chipBg = if (isPassed) {
                    if (isDark) Color(0x2200E676) else Color(0x15059669)
                } else {
                    if (isDark) Color(0x22FF5252) else Color(0x15EF4444)
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(chipBg)
                        .border(1.dp, chipAccent.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isPassed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = chipAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isPassed) "ISRO TARGET (< 10.0%) PASSED" else "TARGET MISSED",
                        color = chipAccent,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Calculation: 100 × (endpoint position error / outage distance)",
                    color = textMuted,
                    fontSize = 10.5.sp
                )
            }
        }

        // ── Detailed Metrics Grid ─────────────────────────────────────────
        Text(
            text = "EVALUATION EVIDENCE",
            color = textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "Outage Distance",
                value = String.format("%.1f m", meta?.outageDistanceM ?: 609.6),
                subtitle = "Tunnel Length",
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Outage Duration",
                value = String.format("%.1f s", meta?.outageDurationSec ?: 40.0),
                subtitle = "GNSS Denied",
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "Endpoint Error",
                value = String.format("%.2f m", meta?.endpointErrorM ?: 0.60),
                subtitle = "Exit Accuracy",
                accentColor = if (isDark) Color(0xFF00E676) else Color(0xFF059669),
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Maximum Error",
                value = String.format("%.2f m", meta?.maxErrorM ?: 1.85),
                subtitle = "Peak Outage Deviation",
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "RMS Position Error",
                value = String.format("%.2f m", meta?.rmsErrorM ?: 0.95),
                subtitle = "Root Mean Square",
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "AI Speed Model",
                value = "KinoNet-R2",
                subtitle = "1D-CNN Heteroscedastic",
                accentColor = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Action Buttons ────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReplay,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(48.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0xFF455A64) else Color(0xFFCBD5E1)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isDark) Color.White else Color(0xFF0F172A)
                )
            ) {
                Icon(
                    Icons.Default.Replay,
                    contentDescription = null,
                    tint = if (isDark) Color.White else Color(0xFF0F172A),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Replay Trip", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Button(
                onClick = onNavigateHome,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
                ),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text(
                    "Done",
                    color = if (isDark) Color(0xFF002233) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    isDark: Boolean,
    accentColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDark) Color(0xFF141A24) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color(0xFF1E2838) else Color(0xFFE2E8F0)
    val titleColor = if (isDark) Color(0xFF78909C) else Color(0xFF64748B)
    val valueColor = accentColor ?: if (isDark) Color.White else Color(0xFF0F172A)
    val subColor = if (isDark) Color(0xFF546E7A) else Color(0xFF94A3B8)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 1.5.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(cardBorder, cardBorder.copy(alpha = 0.5f)))
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = titleColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = valueColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = subColor, fontSize = 10.sp)
        }
    }
}
