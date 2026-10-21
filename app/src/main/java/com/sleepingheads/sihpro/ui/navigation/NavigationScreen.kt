package com.sleepingheads.sihpro.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleepingheads.sihpro.data.repository.DestinationInfo
import com.sleepingheads.sihpro.data.repository.NavRoute
import com.sleepingheads.sihpro.network.GeoReckonConnectionState
import com.sleepingheads.sihpro.ui.navigation.components.MapboxMapView
import com.sleepingheads.sihpro.ui.navigation.components.TrajectoryView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun NavigationScreen(
    onNavigateToResults: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: NavigationViewModel = viewModel()
) {
    val state by viewModel.playbackState.collectAsState()
    val isLiveCloudMode by viewModel.isLiveCloudMode.collectAsState()
    val isLiveOfflineMode by viewModel.isLiveOfflineMode.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val lastRttMs by viewModel.lastRttMs.collectAsState()
    val lastPosition by viewModel.lastPosition.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val activeServerUrl by viewModel.activeServerUrl.collectAsState()
    val selectedTrip by viewModel.selectedTrip.collectAsState()
    val activeDestination by viewModel.activeDestination.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsState()

    val sample = state.currentSample
    val mode = sample?.mode ?: "INITIALIZING"
    val health = sample?.health ?: "NORMAL"

    val isBlackout = mode == "DEAD_RECKONING" || state.isManualBlackoutForced
    var useMapboxMap by rememberSaveable { mutableStateOf(true) }
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var showRouteMenu by remember { mutableStateOf(false) }

    val customOrigin by viewModel.customOrigin.collectAsState()
    var isRoutePlannerExpanded by rememberSaveable { mutableStateOf(false) }
    var originInput by rememberSaveable { mutableStateOf("Your location") }
    var destinationInput by rememberSaveable { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<DestinationInfo>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var pinnedToastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeDestination) {
        if (activeDestination != null) {
            destinationInput = activeDestination!!.name
            showSuggestions = false
        } else {
            destinationInput = ""
        }
    }

    LaunchedEffect(customOrigin) {
        if (customOrigin != null) {
            originInput = customOrigin!!.name
        } else {
            originInput = "Your location"
        }
    }

    LaunchedEffect(destinationInput) {
        val q = destinationInput.trim()
        if (q.length >= 2 && q != (activeDestination?.name ?: "")) {
            isSearching = true
            showSuggestions = true
            viewModel.searchPlaces(q) { res ->
                searchResults = res
                isSearching = false
            }
        } else {
            searchResults = emptyList()
            isSearching = false
        }
    }

    LaunchedEffect(pinnedToastMessage) {
        if (pinnedToastMessage != null) {
            delay(3500)
            pinnedToastMessage = null
        }
    }

    // Colors matching state
    val modeColor = when (mode) {
        "DEAD_RECKONING" -> Color(0xFFFF9100)
        "REACQUIRING" -> Color(0xFF00E5FF)
        "GNSS_AIDED" -> Color(0xFF10B981)
        else -> Color(0xFF94A3B8)
    }

    // Calculate covered distance in km
    val distanceKm = remember(state.currentSampleIndex, state.allSamples) {
        if (state.allSamples.isEmpty() || state.currentSampleIndex <= 0) 0.0
        else {
            var distM = 0.0
            val targetIdx = minOf(state.currentSampleIndex, state.allSamples.size - 1)
            for (i in 1..targetIdx) {
                val p1 = state.allSamples[i - 1]
                val p2 = state.allSamples[i]
                val dx = p2.estX - p1.estX
                val dy = p2.estY - p1.estY
                distM += Math.hypot(dx, dy)
            }
            distM / 1000.0
        }
    }

    // ── Interactive Drag & Smooth Snapping Setup (Fully Responsive to Device Insets) ──
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val collapsedHeightDp = 248.dp + navBarBottom
    val expandedHeightDp = (configuration.screenHeightDp * 0.70f).dp.coerceIn(500.dp, 680.dp)

    val collapsedHeightPx = with(density) { collapsedHeightDp.toPx() }
    val expandedHeightPx = with(density) { expandedHeightDp.toPx() }

    val currentHeightAnim = remember { Animatable(collapsedHeightPx) }

    // Synchronize Animatable with isExpanded and device orientation/insets
    LaunchedEffect(isExpanded, collapsedHeightPx, expandedHeightPx) {
        val target = if (isExpanded) expandedHeightPx else collapsedHeightPx
        if (Math.abs(currentHeightAnim.value - target) > 1f) {
            currentHeightAnim.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    var lastDragDelta by remember { mutableStateOf(0f) }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = {
                lastDragDelta = 0f
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                lastDragDelta = dragAmount
                coroutineScope.launch {
                    val targetHeight = (currentHeightAnim.value - dragAmount).coerceIn(collapsedHeightPx, expandedHeightPx)
                    currentHeightAnim.snapTo(targetHeight)
                    val midpoint = (collapsedHeightPx + expandedHeightPx) / 2
                    if (currentHeightAnim.value > midpoint && !isExpanded) {
                        isExpanded = true
                    } else if (currentHeightAnim.value <= midpoint && isExpanded) {
                        isExpanded = false
                    }
                }
            },
            onDragEnd = {
                coroutineScope.launch {
                    val midpoint = (collapsedHeightPx + expandedHeightPx) / 2
                    if (lastDragDelta < -6f) { // Fast upward fling/swipe
                        isExpanded = true
                        currentHeightAnim.animateTo(
                            expandedHeightPx,
                            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                        )
                    } else if (lastDragDelta > 6f) { // Fast downward fling/swipe
                        isExpanded = false
                        currentHeightAnim.animateTo(
                            collapsedHeightPx,
                            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                        )
                    } else if (currentHeightAnim.value > midpoint) {
                        isExpanded = true
                        currentHeightAnim.animateTo(
                            expandedHeightPx,
                            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                        )
                    } else {
                        isExpanded = false
                        currentHeightAnim.animateTo(
                            collapsedHeightPx,
                            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                        )
                    }
                }
            },
            onDragCancel = {
                coroutineScope.launch {
                    val target = if (isExpanded) expandedHeightPx else collapsedHeightPx
                    currentHeightAnim.animateTo(target)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        // ── 1. Fullscreen Map (Clean & Uncluttered) ──────────────────────────
        if (useMapboxMap) {
            MapboxMapView(
                allSamples = state.allSamples,
                currentSample = sample,
                currentSampleIndex = state.currentSampleIndex,
                metadata = state.metadata,
                activeRoute = activeRoute,
                activeDestination = activeDestination,
                onDestinationPinned = { lat, lon ->
                    viewModel.setDestinationFromCoordinates(lat, lon)
                    pinnedToastMessage = "📍 Destination pinned on map • Calculating route..."
                },
                bottomPadding = collapsedHeightDp + 14.dp,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            TrajectoryView(
                allSamples = state.allSamples,
                currentSample = sample,
                metadata = state.metadata,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 2. Floating Top Bar: Google Maps Route Planner ──────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .zIndex(10f)
        ) {
            if (isLiveCloudMode || isLiveOfflineMode) {
                if (isRoutePlannerExpanded) {
                    // Google Maps Two-Field Route Planner Card (Source & Destination)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Collapse / Back Button
                                IconButton(
                                    onClick = { isRoutePlannerExpanded = false },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Collapse",
                                        tint = Color(0xFF1E293B),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Google Maps Dot-Line-Pin Indicator
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.width(18.dp)
                                ) {
                                    // Blue Circle (Source / Origin)
                                    Box(
                                        modifier = Modifier
                                            .size(11.dp)
                                            .background(Color(0xFF4285F4), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(Color.White, CircleShape)
                                        )
                                    }

                                    // 3 Vertical Connector Dots
                                    Column(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Box(modifier = Modifier.size(2.dp).background(Color(0xFF94A3B8), CircleShape))
                                        Box(modifier = Modifier.size(2.dp).background(Color(0xFF94A3B8), CircleShape))
                                        Box(modifier = Modifier.size(2.dp).background(Color(0xFF94A3B8), CircleShape))
                                    }

                                    // Red Pin (Destination)
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = Color(0xFFEA4335),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Source & Destination Inputs
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // 1. Source / Origin Input Field
                                    BasicTextField(
                                        value = originInput,
                                        onValueChange = { originInput = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF0F172A)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.5.dp),
                                        decorationBox = { innerTextField ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    if (originInput.isEmpty()) {
                                                        Text("Your location", color = Color(0xFF64748B), fontSize = 13.sp)
                                                    }
                                                    innerTextField()
                                                }
                                                if (originInput != "Your location" && originInput.isNotEmpty()) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Reset",
                                                        tint = Color(0xFF94A3B8),
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .clickable {
                                                                originInput = "Your location"
                                                                viewModel.setCustomOrigin(null)
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    // 2. Destination Input Field (Direct Typing)
                                    BasicTextField(
                                        value = destinationInput,
                                        onValueChange = {
                                            destinationInput = it
                                            showSuggestions = it.trim().length >= 2
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0F172A)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (activeDestination != null) Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (activeDestination != null) Color(0xFF3B82F6) else Color(0xFFCBD5E1),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.5.dp),
                                        decorationBox = { innerTextField ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    if (destinationInput.isEmpty()) {
                                                        Text(
                                                            text = "Choose destination or double-click map",
                                                            color = Color(0xFF94A3B8),
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                                if (destinationInput.isNotEmpty() || activeDestination != null) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Clear",
                                                        tint = Color(0xFFEF4444),
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clickable {
                                                                destinationInput = ""
                                                                viewModel.clearDestination()
                                                                showSuggestions = false
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Swap Button
                                IconButton(
                                    onClick = { viewModel.swapOriginAndDestination() },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapVert,
                                        contentDescription = "Swap Origin and Destination",
                                        tint = Color(0xFF475569),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Search Progress Indicator
                            if (isSearching) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.5.dp),
                                    color = Color(0xFF3B82F6),
                                    trackColor = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }

                    // Autocomplete Place Suggestions Dropdown
                    AnimatedVisibility(
                        visible = showSuggestions && searchResults.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .heightIn(max = 240.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            ) {
                                searchResults.forEach { place ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                destinationInput = place.name
                                                viewModel.setDestination(place)
                                                showSuggestions = false
                                                isRoutePlannerExpanded = false
                                            }
                                            .padding(horizontal = 14.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = Color(0xFFEA4335),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(place.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                            if (place.address.isNotEmpty()) {
                                                Text(place.address, fontSize = 11.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 0.8.dp)
                                }
                            }
                        }
                    }

                    // Popular Destination Chips (When expanded)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val quickDestinations = listOf(
                            DestinationInfo("Airport", 13.1986, 77.7066, "Kempegowda International Airport"),
                            DestinationInfo("City Centre", 12.9716, 77.5946, "MG Road, Bengaluru"),
                            DestinationInfo("Tech Park", 12.9856, 77.7374, "ITPL, Whitefield"),
                            DestinationInfo("Indiranagar", 12.9719, 77.6412, "100ft Road")
                        )
                        items(quickDestinations) { qDest ->
                            Surface(
                                onClick = {
                                    destinationInput = qDest.name
                                    viewModel.setDestination(qDest)
                                    isRoutePlannerExpanded = false
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White.copy(alpha = 0.95f),
                                shadowElevation = 3.dp,
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(Icons.Default.NearMe, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(13.dp))
                                    Text(qDest.name, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                }
                            }
                        }
                    }
                } else {
                    // Normal Clean State: Floating Back Button + Single Search Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Floating circular white Back Button
                        Surface(
                            onClick = {
                                viewModel.startReplayMode()
                                onNavigateBack()
                            },
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 4.dp,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color(0xFF1E293B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Floating Search Bar Capsule
                        Surface(
                            onClick = { isRoutePlannerExpanded = true },
                            shape = RoundedCornerShape(26.dp),
                            color = Color.White,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = if (activeDestination != null) activeDestination!!.name
                                        else "Search destination or double-click map...",
                                        color = if (activeDestination != null) Color(0xFF0F172A) else Color(0xFF64748B),
                                        fontSize = 13.5.sp,
                                        fontWeight = if (activeDestination != null) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (activeDestination != null) {
                                    IconButton(
                                        onClick = {
                                            viewModel.clearDestination()
                                            destinationInput = ""
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear Destination",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    // Green ready status dot
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF10B981), CircleShape)
                                    )
                                }
                            }
                        }
                    }

                    // Shortest & Fastest Route Info Strip (Google Maps style)
                    if (activeRoute != null && activeDestination != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            onClick = { isRoutePlannerExpanded = true },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF0F172A),
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "${activeRoute!!.etaString} (${String.format(Locale.US, "%.1f km", activeRoute!!.distanceM / 1000.0)})",
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "• Fastest route",
                                        color = Color(0xFF34D399),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "Tap to edit",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // Simulation Mode: Simple header with back button + trip title pill (No destination button)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = onNavigateBack,
                        shape = CircleShape,
                        color = Color.White,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFF1E293B),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = Color.White,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(20.dp))
                            Text(
                                text = "Simulation: ${selectedTrip.title}",
                                color = Color(0xFF0F172A),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Pinned Notification Toast / Pill
            AnimatedVisibility(
                visible = pinnedToastMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E293B),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = pinnedToastMessage ?: "",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CLEAR",
                            color = Color(0xFFF87171),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                viewModel.clearDestination()
                                pinnedToastMessage = null
                            }
                        )
                    }
                }
            }

            // Outage Alert Banner (Floating inside column so it never overlaps)
            AnimatedVisibility(
                visible = isBlackout,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFEA580C),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "GNSS OUTAGE DETECTED • KINONET-R2 AI DEAD RECKONING ACTIVE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── Bottom Sheet Heights & Expansion State ──
        val currentHeightDp = with(density) { currentHeightAnim.value.toDp() }
        val isPartiallyOrFullyExpanded = currentHeightAnim.value > collapsedHeightPx + with(density) { 30.dp.toPx() }

        // ── 4. Modern Expandable / Collapsible Draggable Bottom Sheet ────────
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(currentHeightDp)
                .zIndex(20f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Color.White,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(bottom = 6.dp)
            ) {
                // Draggable Handle Bar Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .clickable { isExpanded = !isExpanded }
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFCBD5E1))
                    )
                }

                // Header Row (Clickable and Draggable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Red Pin Icon
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(24.dp)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (activeDestination != null) activeDestination!!.name
                                else if (destinationInput.isNotEmpty()) destinationInput
                                else if (isLiveOfflineMode) "Real-Time On-Device Navigation"
                                else if (isLiveCloudMode) "Real-Time AWS Cloud Navigation"
                                else selectedTrip.title,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (activeDestination != null) {
                                    if (activeRoute != null) {
                                        String.format(Locale.US, "%.1f km • %s • Shortest & Fastest Route", activeRoute!!.distanceM / 1000.0, activeRoute!!.etaString)
                                    } else if (isCalculatingRoute) {
                                        "Calculating fastest route..."
                                    } else {
                                        "Destination set • Double-tap map to change"
                                    }
                                } else if (isLiveOfflineMode) "100% Real-Time Hardware IMU • Zero Network"
                                else if (isLiveCloudMode) "Live 10 Hz Telemetry Streaming to AWS EC2"
                                else "${selectedTrip.badge} • ${selectedTrip.scenarioType}",
                                fontSize = 12.sp,
                                color = if (activeDestination != null && activeRoute != null) Color(0xFF0284C7) else Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (activeDestination != null) {
                            IconButton(
                                onClick = {
                                    viewModel.clearDestination()
                                    destinationInput = ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Route",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Upward / Downward triangle indicator
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isPartiallyOrFullyExpanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowDropUp,
                            contentDescription = if (isPartiallyOrFullyExpanded) "Collapse" else "Expand",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // ── 3-Column Telemetry Stats Row (Draggable) ────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. Current Speed
                    Column(modifier = Modifier.weight(1.2f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format("%.0f", sample?.speedKmh ?: 0.0),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF0F172A),
                                fontFamily = FontFamily.SansSerif
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "km/h",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF475569),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Text(
                            text = "CURRENT SPEED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Vertical Divider Line
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(Color(0xFFE2E8F0))
                    )

                    // 2. Distance (KM) or Remaining Distance
                    Column(
                        modifier = Modifier.weight(1.2f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val distStr = if (activeDestination != null && activeRoute != null) {
                            String.format(Locale.US, "%.1f", activeRoute!!.distanceM / 1000.0)
                        } else {
                            String.format(Locale.US, "%.2f", distanceKm)
                        }
                        val distLabel = if (activeDestination != null && activeRoute != null) "REMAINING (KM)" else "DISTANCE (KM)"
                        Text(
                            text = distStr,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeDestination != null) Color(0xFF0284C7) else Color(0xFF0F172A),
                            fontFamily = FontFamily.SansSerif
                        )
                        Text(
                            text = distLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Vertical Divider Line
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(Color(0xFFE2E8F0))
                    )

                    // 3. Duration (mm:ss) or ETA
                    Column(
                        modifier = Modifier.weight(1.2f),
                        horizontalAlignment = Alignment.End
                    ) {
                        val durationStr = if (activeDestination != null && activeRoute != null) {
                            activeRoute!!.etaString
                        } else {
                            String.format("%02d:%02d", (state.elapsedSec / 60).toInt(), (state.elapsedSec % 60).toInt())
                        }
                        val durationLabel = if (activeDestination != null && activeRoute != null) "EST. TRAVEL" else "DURATION"
                        Text(
                            text = durationStr,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeDestination != null) Color(0xFF10B981) else Color(0xFF0F172A),
                            fontFamily = if (activeDestination != null) FontFamily.SansSerif else FontFamily.Monospace
                        )
                        Text(
                            text = durationLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // ── Primary Action Buttons ──────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLiveOfflineMode || isLiveCloudMode) {
                        // Set Destination / Clear Route Button
                        Surface(
                            onClick = {
                                if (activeDestination != null) {
                                    viewModel.clearDestination()
                                    destinationInput = ""
                                    isRoutePlannerExpanded = false
                                } else {
                                    isRoutePlannerExpanded = true
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (activeDestination != null) Color(0xFFDC2626) else Color(0xFF181C2E),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (activeDestination != null) Icons.Default.Close else Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = if (activeDestination != null) Color.White else Color(0xFFEF4444),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (activeDestination != null) "Clear Route" else "Set Destination",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Real-Time Sensor Status & Calibration Button
                        Surface(
                            onClick = { viewModel.restart() },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isLiveOfflineMode) Color(0xFF059669) else Color(0xFF0284C7),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.White, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isLiveOfflineMode) "Realtime DR" else "AWS Realtime",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // In Simulation mode: Set Destination button removed!
                        // Prominent full-width Simulation Replay Play/Pause Button
                        Surface(
                            onClick = { viewModel.togglePlayPause() },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF4F46E5),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF00F2FE),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (state.isPlaying) "Pause Simulation Replay" else "Start Simulation Replay",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // ── Expanded Content (Preserving All Telemetry & Controls) ──
                if (isPartiallyOrFullyExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        HorizontalDivider(
                            color = Color(0xFFF1F5F9),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )

                        Text(
                            text = "NAVIGATION & ENGINE STATUS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )

                        // 1. Shortest & Best Road Route Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = if (activeRoute != null) Color(0xFFEFF6FF) else Color(0xFFF8FAFC)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(if (activeRoute != null) Color(0xFFDBEAFE) else Color(0xFFFEF3C7), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (activeRoute != null) Icons.Default.DirectionsCar else Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (activeRoute != null) Color(0xFF2563EB) else Color(0xFFF59E0B),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (activeDestination != null) "Fastest Route to ${activeDestination!!.name}" else "Shortest & Best Road Route",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeRoute != null) {
                                            "${String.format(Locale.US, "%.1f km", activeRoute!!.distanceM / 1000.0)} • ${activeRoute!!.etaString} • ${activeRoute!!.summary}"
                                        } else if (isCalculatingRoute) {
                                            "Calculating dynamic traffic route..."
                                        } else {
                                            "Mapbox Directions v5 • Double-click map or search to navigate"
                                        },
                                        fontSize = 11.sp,
                                        color = if (activeRoute != null) Color(0xFF1E40AF) else Color(0xFF64748B)
                                    )
                                }
                                if (activeRoute != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2563EB))
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = activeRoute!!.etaString,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // 2. KinoNet-R2 AI Dead Reckoning Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFFCE7F3), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = Color(0xFFEC4899),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "KinoNet-R2 AI Dead Reckoning",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "100 Hz IMU sensor fusion & Extended Kalman Filter",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(modeColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = mode.replace("_", " "),
                                        color = modeColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // 3. AI Drift Comparison & Precision Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("GEORECKON DRIFT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                        Text(
                                            text = String.format("%.1f m", sample?.errorM ?: 0.0),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0284C7)
                                        )
                                    }
                                    Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0xFFCBD5E1)))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("NAIVE INS DRIFT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                        Text(
                                            text = String.format("%.1f m", sample?.naiveErrorM ?: 0.0),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626)
                                        )
                                    }
                                    Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0xFFCBD5E1)))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("UNCERTAINTY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                        Text(
                                            text = String.format("±%.1f m", sample?.uncertaintyM ?: 1.8),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF16A34A)
                                        )
                                    }
                                }

                                // Ablation Quick Badges
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("ABLATION:", fontSize = 9.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("AI SPEED: ON", fontSize = 9.sp, color = Color(0xFF0284C7), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        Text("NHC: ON", fontSize = 9.sp, color = Color(0xFF0284C7), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        Text("MAP: ON", fontSize = 9.sp, color = Color(0xFF0284C7), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // 4. Trip Playback & Simulation Controls Card
                        if (!isLiveCloudMode && !isLiveOfflineMode) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Replay Timeline Slider
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("REPLAY TIMELINE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                        Text(
                                            text = "${(state.progress * 100).toInt()}%",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4F46E5)
                                        )
                                    }
                                    Slider(
                                        value = state.progress,
                                        onValueChange = { viewModel.seekTo(it) },
                                        modifier = Modifier.fillMaxWidth().height(20.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF4F46E5),
                                            activeTrackColor = Color(0xFF4F46E5),
                                            inactiveTrackColor = Color(0xFFE2E8F0)
                                        )
                                    )

                                    // Controls Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Restart button
                                        IconButton(
                                            onClick = { viewModel.restart() },
                                            modifier = Modifier.size(36.dp).background(Color(0xFFE2E8F0), CircleShape)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = Color(0xFF1E293B), modifier = Modifier.size(18.dp))
                                        }

                                        // Speed Multiplier Pills (1x, 2x, 5x)
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(Color(0xFFE2E8F0))
                                                .padding(2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            listOf(1.0f, 2.0f, 5.0f).forEach { speed ->
                                                val isSelected = state.speedMultiplier == speed
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(if (isSelected) Color(0xFF4F46E5) else Color.Transparent)
                                                        .clickable { viewModel.setSpeedMultiplier(speed) }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = "${speed.toInt()}x",
                                                        color = if (isSelected) Color.White else Color(0xFF475569),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        // Outage Simulation Toggle
                                        val isOutageActive = isBlackout || state.isManualBlackoutForced
                                        Button(
                                            onClick = { viewModel.toggleCloudBlackout() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isOutageActive) Color(0xFFEF4444) else Color(0xFF1E293B)
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(
                                                if (isOutageActive) Icons.Default.GpsOff else Icons.Default.GpsFixed,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isOutageActive) "Restore GNSS" else "Sim Outage", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        // Results Button
                                        Button(
                                            onClick = onNavigateToResults,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("Results", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Icon(Icons.Default.Assessment, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // 5. Trip Route Switcher & Diagnostics Quick Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isLiveCloudMode && !isLiveOfflineMode) {
                                // Route Picker Dropdown (Only for simulation mode)
                                Box(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        onClick = { showRouteMenu = true },
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFFF1F5F9),
                                        modifier = Modifier.fillMaxWidth().height(36.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Default.AltRoute, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(16.dp))
                                                Text(
                                                    text = selectedTrip.title,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0F172A),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showRouteMenu,
                                        onDismissRequest = { showRouteMenu = false },
                                        modifier = Modifier.background(Color.White)
                                    ) {
                                        viewModel.availableTrips.forEach { trip ->
                                            val isCurrent = trip.id == selectedTrip.id
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Text(
                                                                text = trip.title,
                                                                color = if (isCurrent) Color(0xFF4F46E5) else Color(0xFF0F172A),
                                                                fontSize = 13.sp,
                                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                            if (isCurrent) {
                                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(14.dp))
                                                            }
                                                        }
                                                        Text(
                                                            text = "${trip.badge} • ${trip.scenarioType}",
                                                            color = Color(0xFF64748B),
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.selectTrip(trip)
                                                    destinationInput = trip.title
                                                    showRouteMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Live Real-Time Sensor DR Capsule
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isLiveOfflineMode) Color(0xFFECFDF5) else Color(0xFFE0F2FE),
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(
                                                    if (isLiveOfflineMode) Color(0xFF10B981) else Color(0xFF0284C7),
                                                    CircleShape
                                                )
                                        )
                                        Text(
                                            text = if (isLiveOfflineMode) "Real-Time Sensor DR" else "AWS Cloud Live",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isLiveOfflineMode) Color(0xFF047857) else Color(0xFF0369A1),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // View Toggle (Mapbox Map vs Trajectory Canvas)
                            Surface(
                                onClick = { useMapboxMap = !useMapboxMap },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFF1F5F9),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (useMapboxMap) Icons.Default.Layers else Icons.Default.Map,
                                        contentDescription = null,
                                        tint = Color(0xFF4F46E5),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (useMapboxMap) "Mapbox" else "3D Canvas",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                            }

                            // Diagnostics Button
                            Surface(
                                onClick = onNavigateToDiagnostics,
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFF1F5F9),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.QueryStats, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(16.dp))
                                    Text("Diagnostics", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                }
                            }
                        }

                        // 6. Live Sensor / Cloud Mode Status (if active)
                        if (isLiveOfflineMode) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(modifier = Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
                                            Text("LIVE ON-DEVICE SENSORS • 10 Hz", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text("Samples: #${state.allSamples.size}", color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilledTonalButton(
                                            onClick = { viewModel.restart() },
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE2E8F0)),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f).height(36.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Recalibrate", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.startReplayMode()
                                                onNavigateBack()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f).height(36.dp)
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Exit Live", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else if (isLiveCloudMode) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    when (connState) {
                                                        GeoReckonConnectionState.CONNECTED_IDLE,
                                                        GeoReckonConnectionState.DEAD_RECKONING -> Color(0xFF10B981)
                                                        GeoReckonConnectionState.CONNECTING,
                                                        GeoReckonConnectionState.CALIBRATING -> Color(0xFFF59E0B)
                                                        else -> Color(0xFFEF4444)
                                                    },
                                                    CircleShape
                                                )
                                        )
                                        Text("AWS: ${connState.name} (${lastRttMs.toInt()}ms)", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (connState == GeoReckonConnectionState.ERROR) {
                                        Button(
                                            onClick = { viewModel.retryCloudConnection() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Retry", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // 7. Interactive Tip Card
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Double-click anywhere on the map to set destination pin instantly.",
                                fontSize = 11.5.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
