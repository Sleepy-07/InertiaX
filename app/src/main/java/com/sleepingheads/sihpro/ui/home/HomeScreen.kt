package com.sleepingheads.sihpro.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sleepingheads.sihpro.data.model.DemoTripInfo
import com.sleepingheads.sihpro.network.GeoReckonConnectionState
import com.sleepingheads.sihpro.session.AuthProviderType
import com.sleepingheads.sihpro.session.UserSessionManager
import com.sleepingheads.sihpro.ui.navigation.NavigationViewModel
import com.sleepingheads.sihpro.ui.profile.AsyncAvatar
import com.sleepingheads.sihpro.ui.profile.ProfileScreen
import com.sleepingheads.sihpro.ui.theme.ThemeManager

enum class DashboardTab { HOME, SIMULATION, PROFILE }

@Composable
fun HomeScreen(
    viewModel: NavigationViewModel,
    onStartDemo: () -> Unit,
    onViewResults: () -> Unit,
    onViewDiagnostics: () -> Unit,
    onLogout: (() -> Unit)? = null
) {
    var currentTab by remember { mutableStateOf(DashboardTab.HOME) }
    BackHandler(enabled = currentTab != DashboardTab.HOME) { currentTab = DashboardTab.HOME }
    val isDark = ThemeManager.isDarkTheme.value

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (isDark) Color(0xFF090D16) else Color(0xFFF1F5F9),
        topBar = {
            if (currentTab != DashboardTab.PROFILE) {
                HomeTopAppBar(onProfileClick = { currentTab = DashboardTab.PROFILE })
            }
        },
        bottomBar = {
            DashboardBottomNavigationBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        }
    ) { innerPadding ->
        Crossfade(
            targetState = currentTab,
            animationSpec = tween(durationMillis = 220),
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                ),
            label = "DashboardTabCrossfade"
        ) { tab ->
            when (tab) {
                DashboardTab.HOME -> HomeDashboardContent(
                    viewModel = viewModel,
                    onStartDemo = onStartDemo,
                    onViewResults = onViewResults,
                    onViewDiagnostics = onViewDiagnostics,
                    onNavigateToProfile = { currentTab = DashboardTab.PROFILE },
                    onNavigateToSimulation = { currentTab = DashboardTab.SIMULATION }
                )
                DashboardTab.SIMULATION -> SimulationDashboardContent(
                    viewModel = viewModel,
                    onStartDemo = onStartDemo,
                    onViewResults = onViewResults,
                    onViewDiagnostics = onViewDiagnostics
                )
                DashboardTab.PROFILE -> ProfileScreen(
                    onNavigateToLogin = onLogout ?: {},
                    onLogout = onLogout ?: {},
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun HomeTopAppBar(onProfileClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val userProfile = remember { UserSessionManager.getCurrentUser(context) }
    val haptic = LocalHapticFeedback.current
    val isDark = ThemeManager.isDarkTheme.value

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_bar")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isDark) Color(0xFF0B111D) else Color(0xFFFFFFFF),
        shadowElevation = if (isDark) 0.dp else 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = Alignment.Start
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Inertia",
                        color = if (isDark) Color.White else Color(0xFF0F172A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        "X",
                        color = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isDark) Color(0x1A00E5FF) else Color(0xFFE0F2FE))
                            .border(1.dp, if (isDark) Color(0x3300E5FF) else Color(0xFFBAE6FD), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    (if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)).copy(alpha = pulseAlpha),
                                    CircleShape
                                )
                        )
                        Text(
                            "GeoReckon",
                            color = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.2.sp
                        )
                    }
                }
                Text(
                    "ISRO Dead-Reckoning Navigation",
                    color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF131D2E) else Color(0xFFEFF6FF))
                    .border(1.5.dp, if (isDark) Color(0x5500E5FF) else Color(0xFFBAE6FD), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onProfileClick()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (userProfile.provider == AuthProviderType.GOOGLE && userProfile.photoUrl != null) {
                    AsyncAvatar(sourceUrl = userProfile.photoUrl, localPath = null, name = userProfile.name, size = 38, modifier = Modifier.size(38.dp))
                } else if (userProfile.customAvatarPath != null) {
                    AsyncAvatar(sourceUrl = null, localPath = userProfile.customAvatarPath, name = userProfile.name, size = 38, modifier = Modifier.size(38.dp))
                } else {
                    Icon(
                        Icons.Default.Person,
                        "Profile",
                        tint = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeDashboardContent(
    viewModel: NavigationViewModel,
    onStartDemo: () -> Unit,
    onViewResults: () -> Unit,
    onViewDiagnostics: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToSimulation: () -> Unit
) {
    val scrollState = rememberScrollState()
    val connState by viewModel.connectionState.collectAsState()
    val activeServerUrl by viewModel.activeServerUrl.collectAsState()
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Triple<Boolean, Double, String>?>(null) }
    val context = LocalContext.current
    var pendingLiveMode by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            try {
                if (pendingLiveMode == "CLOUD") viewModel.startLiveCloudMode() else viewModel.startLiveOfflineMode()
                onStartDemo()
            } catch (t: Throwable) {
                android.util.Log.e("HomeScreen", "Error: " + t.message, t)
            }
        }
    }

    val launchLiveWithPermission: (String) -> Unit = { mode ->
        pendingLiveMode = mode
        val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            try {
                if (mode == "CLOUD") viewModel.startLiveCloudMode() else viewModel.startLiveOfflineMode()
                onStartDemo()
            } catch (t: Throwable) {
                android.util.Log.e("HomeScreen", "Error: " + t.message, t)
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val isDark = ThemeManager.isDarkTheme.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF090D16) else Color(0xFFF1F5F9))
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live Navigation Modes
        SectionLabel("LIVE NAVIGATION ENGINES", isDark)

        // On-Device Mode Card
        OnDeviceCard(
            isDark = isDark,
            engineName = viewModel.offlineEngine.activeModelEngine,
            onLaunch = { launchLiveWithPermission("OFFLINE") }
        )

        // AWS Cloud Live Mode Card
        CloudCard(
            isDark = isDark,
            connState = connState,
            activeServerUrl = activeServerUrl,
            isTestingConnection = isTestingConnection,
            testResult = testResult,
            onTestPing = {
                isTestingConnection = true
                testResult = null
                viewModel.testConnection { s, r, m ->
                    isTestingConnection = false
                    testResult = Triple(s, r, m)
                }
            },
            onLaunch = { launchLiveWithPermission("CLOUD") }
        )

        // 3. Section: Tools & Insights
        SectionLabel("TOOLS & BENCHMARKS", isDark)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionTile(
                title = "Live Diagnostics",
                subtitle = "Real-time IMU Telemetry",
                icon = Icons.Default.QueryStats,
                accentColor = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                onClick = onViewDiagnostics,
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )

            ActionTile(
                title = "ISRO Scorecard",
                subtitle = "Benchmark Verifications",
                icon = Icons.Default.Assessment,
                accentColor = Color(0xFFFF9100),
                onClick = onViewResults,
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(36.dp))
    }
}



@Composable
private fun SectionLabel(text: String, isDark: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7))
        )
        Text(
            text,
            color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun OnDeviceCard(
    isDark: Boolean,
    engineName: String,
    onLaunch: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sensor_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sensor_dot_alpha"
    )
    val accentGreen = if (isDark) Color(0xFF00E676) else Color(0xFF059669)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF0D1B16) else Color(0xFFFFFFFF)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    if (isDark) Brush.linearGradient(listOf(Color(0x6600E676), Color(0x1A00E676), Color(0x6600E676)))
                    else Brush.linearGradient(listOf(Color(0xFFA7F3D0), Color(0xFFD1FAE5), Color(0xFFA7F3D0))),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(accentGreen.copy(alpha = if (isDark) 0.2f else 0.12f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Sensors, null, tint = accentGreen, modifier = Modifier.size(20.dp))
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                "On-Device Dead Reckoning",
                                color = if (isDark) Color.White else Color(0xFF0F172A),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Hardware IMU · Zero Network Required",
                                color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Clean Horizontal Badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = accentGreen.copy(alpha = if (isDark) 0.15f else 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentGreen.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(accentGreen.copy(alpha = pulseAlpha), CircleShape)
                            )
                            Text(
                                "OFFLINE AI",
                                color = accentGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.4.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Text(
                    "Runs KinoNet-R2 neural dead reckoning in real-time on-device using native ONNX and Invariant EKF for 100% offline positioning.",
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                // Feature Chips
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("100 Hz IMU", "Realtime InEKF", "ZUPT & NHC").forEach { chip ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentGreen.copy(alpha = if (isDark) 0.12f else 0.08f))
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                chip,
                                color = if (isDark) Color(0xFF69F0AE) else Color(0xFF047857),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // CTA Button
                Button(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFF00E676) else Color(0xFF059669)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF003319) else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Start On-Device Ride",
                        color = if (isDark) Color(0xFF003319) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudCard(
    isDark: Boolean,
    connState: GeoReckonConnectionState,
    activeServerUrl: String,
    isTestingConnection: Boolean,
    testResult: Triple<Boolean, Double, String>?,
    onTestPing: () -> Unit,
    onLaunch: () -> Unit
) {
    val accentCyan = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
    val connStatusText = when (connState) {
        GeoReckonConnectionState.CONNECTED_IDLE -> "CONNECTED"
        GeoReckonConnectionState.DEAD_RECKONING -> "STREAMING"
        GeoReckonConnectionState.CONNECTING -> "CONNECTING"
        GeoReckonConnectionState.ERROR -> "OFFLINE"
        else -> "READY"
    }
    val connStatusColor = when (connState) {
        GeoReckonConnectionState.CONNECTED_IDLE, GeoReckonConnectionState.DEAD_RECKONING -> if (isDark) Color(0xFF00E676) else Color(0xFF059669)
        GeoReckonConnectionState.CONNECTING, GeoReckonConnectionState.CALIBRATING -> Color(0xFFFFB300)
        GeoReckonConnectionState.ERROR -> Color(0xFFEF4444)
        else -> if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF0E1726) else Color(0xFFFFFFFF)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    if (isDark) Brush.linearGradient(listOf(Color(0x6600E5FF), Color(0x1A00E5FF), Color(0x6600E5FF)))
                    else Brush.linearGradient(listOf(Color(0xFFBAE6FD), Color(0xFFE0F2FE), Color(0xFFBAE6FD))),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(accentCyan.copy(alpha = if (isDark) 0.2f else 0.12f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudQueue, null, tint = accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                "AWS EC2 Cloud Navigation",
                                color = if (isDark) Color.White else Color(0xFF0F172A),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "KinoNet-R2 · High-Throughput Daemon",
                                color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Status Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = connStatusColor.copy(alpha = if (isDark) 0.15f else 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, connStatusColor.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(connStatusColor, CircleShape)
                            )
                            Text(
                                connStatusText,
                                color = connStatusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.4.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Text(
                    "Streams real-time 10 Hz phone IMU vectors over WebSocket to the AWS EC2 hosted KinoNet-R2 neural engine with sub-50ms latency.",
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                // Server Endpoint & Quick Ping
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDark) Color(0xFF09101C) else Color(0xFFF8FAFC))
                        .border(1.dp, if (isDark) Color(0x2200E5FF) else Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Link, null, tint = accentCyan, modifier = Modifier.size(15.dp))
                        Text(
                            activeServerUrl,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF334155),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    TextButton(
                        onClick = onTestPing,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 2.dp, color = accentCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Testing...", color = accentCyan, fontSize = 10.sp)
                        } else {
                            Icon(Icons.Default.Bolt, null, tint = accentCyan, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Test Ping", color = accentCyan, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                testResult?.let { (success, rtt, msg) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (success) Color(0x1200E676) else Color(0x12FF1744))
                            .border(
                                1.dp,
                                if (success) (if (isDark) Color(0xFF00E676) else Color(0xFF059669)) else Color(0xFFEF4444),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            null,
                            tint = if (success) (if (isDark) Color(0xFF00E676) else Color(0xFF059669)) else Color(0xFFEF4444),
                            modifier = Modifier.size(15.dp)
                        )
                        Column {
                            Text(
                                if (success) "Online · Ping ${rtt.toInt()} ms" else "Connection Failed",
                                color = if (success) (if (isDark) Color(0xFF00E676) else Color(0xFF059669)) else Color(0xFFEF4444),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                msg,
                                color = if (success) (if (isDark) Color(0xFFB9F6CA) else Color(0xFF065F46)) else Color(0xFFDC2626),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // CTA Button
                Button(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF002233) else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Launch AWS Cloud Mode",
                        color = if (isDark) Color(0xFF002233) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SimulationDashboardContent(
    viewModel: NavigationViewModel,
    onStartDemo: () -> Unit,
    onViewResults: () -> Unit,
    onViewDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val selectedTrip by viewModel.selectedTrip.collectAsState()
    val availableTrips = viewModel.availableTrips
    val isDark = ThemeManager.isDarkTheme.value

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF090D16) else Color(0xFFF1F5F9))
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Simulation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Simulation Studio",
                    color = if (isDark) Color.White else Color(0xFF0F172A),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Curated ISRO GNSS Blackout Scenarios",
                    color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0x2200E5FF) else Color(0xFFE0F2FE),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0x6600E5FF) else Color(0xFFBAE6FD))
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "REPLAY ENGINE",
                        color = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Active Scenario Spotlight Card
        val activeBadgeColor = getBadgeColor(selectedTrip.badge)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) Color(0xFF0E1626) else Color(0xFFFFFFFF)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        1.5.dp,
                        Brush.linearGradient(
                            listOf(
                                activeBadgeColor,
                                if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
                            )
                        ),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(activeBadgeColor.copy(alpha = if (isDark) 0.2f else 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = selectedTrip.badge,
                                    color = activeBadgeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Text(
                                text = selectedTrip.scenarioType,
                                color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            text = "ACTIVE",
                            color = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Text(
                        text = selectedTrip.title,
                        color = if (isDark) Color.White else Color(0xFF0F172A),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = selectedTrip.description,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    // Stats in a clean row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF080F1B) else Color(0xFFF8FAFC))
                            .border(1.dp, if (isDark) Color(0x18FFFFFF) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        MiniStat("SPEED", selectedTrip.speedKmh, isDark)
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(if (isDark) Color(0x22FFFFFF) else Color(0xFFE2E8F0)))
                        MiniStat("DRIFT", selectedTrip.driftPercent, isDark)
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(if (isDark) Color(0x22FFFFFF) else Color(0xFFE2E8F0)))
                        MiniStat("OUTAGE", "${selectedTrip.outageDurationSec.toInt()}s", isDark)
                    }

                    Button(
                        onClick = {
                            viewModel.startReplayMode(selectedTrip)
                            onStartDemo()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF00E676) else Color(0xFF059669)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFF003319) else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Launch Simulation Replay",
                            color = if (isDark) Color(0xFF003319) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Scenario Selector
        SectionLabel("ALL VERIFICATION SCENARIOS", isDark)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            availableTrips.forEach { trip ->
                TripSelectionCard(
                    trip = trip,
                    isSelected = trip.id == selectedTrip.id,
                    onSelect = { viewModel.selectTrip(trip) }
                )
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun TripSelectionCard(trip: DemoTripInfo, isSelected: Boolean, onSelect: () -> Unit) {
    val isDark = ThemeManager.isDarkTheme.value
    val haptic = LocalHapticFeedback.current
    val badgeColor = getBadgeColor(trip.badge)
    val animScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.985f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "trip_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) {
                if (isSelected) Color(0xFF0F1E2E) else Color(0xFF0B111A)
            } else {
                if (isSelected) Color(0xFFF0FDFB) else Color(0xFFFFFFFF)
            }
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 1.5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) Brush.linearGradient(listOf(badgeColor, badgeColor.copy(0.4f), badgeColor))
                    else Brush.linearGradient(listOf(if (isDark) Color(0x18FFFFFF) else Color(0xFFE2E8F0), if (isDark) Color(0x0CFFFFFF) else Color(0xFFE2E8F0))),
                    RoundedCornerShape(16.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(badgeColor.copy(alpha = if (isSelected) 0.2f else 0.1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (trip.badge) {
                                "FLAGSHIP" -> Icons.AutoMirrored.Filled.AltRoute
                                "MANEUVER" -> Icons.Default.TurnRight
                                "ZUPT" -> Icons.Default.Traffic
                                else -> Icons.Default.Speed
                            },
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = trip.title,
                                color = if (isDark) Color.White else Color(0xFF0F172A),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeColor.copy(alpha = if (isDark) 0.22f else 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = trip.badge,
                                    color = badgeColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Text(
                            text = "${trip.scenarioType} · ${trip.speedKmh} · Drift ${trip.driftPercent}",
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) badgeColor else (if (isDark) Color(0xFF1A2535) else Color(0xFFE2E8F0))),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getBadgeColor(badge: String): Color = when (badge.uppercase()) {
    "FLAGSHIP" -> Color(0xFF00E676)
    "MANEUVER" -> Color(0xFFD946EF)
    "ZUPT" -> Color(0xFFF59E0B)
    "HIGHWAY" -> Color(0xFF00E5FF)
    else -> Color(0xFF00E676)
}

@Composable
private fun MiniStat(label: String, value: String, isDark: Boolean = ThemeManager.isDarkTheme.value) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Text(
            value,
            color = if (isDark) Color.White else Color(0xFF0F172A),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF0D1422) else Color(0xFFFFFFFF)),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(
                    1.dp,
                    if (isDark) accentColor.copy(0.25f) else Color(0xFFE2E8F0),
                    RoundedCornerShape(18.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(accentColor.copy(0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
                }
                Text(
                    title,
                    color = if (isDark) Color.White else Color(0xFF0F172A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = if (isDark) Color(0xFF64748B) else Color(0xFF64748B),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun DashboardBottomNavigationBar(
    currentTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = ThemeManager.isDarkTheme.value
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isDark) Color(0xFF090D16) else Color(0xFFFFFFFF),
        shadowElevation = if (isDark) 0.dp else 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = Icons.Default.Home,
                label = "Home",
                isSelected = currentTab == DashboardTab.HOME,
                isDark = isDark,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTabSelected(DashboardTab.HOME)
                },
                modifier = Modifier.weight(1f)
            )

            BottomNavItem(
                icon = Icons.AutoMirrored.Filled.AltRoute,
                label = "Simulation",
                isSelected = currentTab == DashboardTab.SIMULATION,
                isDark = isDark,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTabSelected(DashboardTab.SIMULATION)
                },
                modifier = Modifier.weight(1f)
            )

            BottomNavItem(
                icon = Icons.Default.Person,
                label = "Profile",
                isSelected = currentTab == DashboardTab.PROFILE,
                isDark = isDark,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTabSelected(DashboardTab.PROFILE)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
    val inactiveColor = if (isDark) Color(0xFF475569) else Color(0xFF94A3B8)

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.95f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "nav_item_scale"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected) {
                        activeColor.copy(alpha = if (isDark) 0.18f else 0.12f)
                    } else Color.Transparent
                )
                .padding(horizontal = 14.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = label,
            color = if (isSelected) activeColor else inactiveColor,
            fontSize = 11.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
