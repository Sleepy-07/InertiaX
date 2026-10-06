package com.sleepingheads.sihpro.ui.navigation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.TripMetadata
import kotlinx.coroutines.launch

/**
 * Modern Dark-Themed Google Maps Navigation View.
 * Displays real-world coordinates from the IO-VNBD dataset:
 * - Real Google Maps road tiles with dark theme styling
 * - Custom oriented GeoReckon Car Marker (Cyan/Amber based on outage mode)
 * - Custom Naive INS Drift Marker (Red warning ghost/beacon) with drift offset vector
 * - Custom Ground-Truth GPS Satellite Beacon during outage
 * - True GNSS Ground-Truth trajectory (Green)
 * - Dead-Reckoned trajectory (Amber/Orange)
 * - Naive Drifting trajectory (Crimson/Red dashed)
 * - Tunnel / Outage Blackout Zone (Neon Magenta)
 */
@Composable
fun GoogleMapView(
    allSamples: List<DemoSample>,
    currentSample: DemoSample?,
    currentSampleIndex: Int = 0,
    metadata: TripMetadata?,
    modifier: Modifier = Modifier
) {
    if (allSamples.isEmpty() || currentSample == null) {
        Box(
            modifier = modifier.background(Color(0xFF0B0E14)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF00E676))
        }
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isPerspective3D by remember { mutableStateOf(true) }
    val isBlackout = currentSample.mode == "DEAD_RECKONING"

    // Initial camera position centered on vehicle
    val initialPos = remember(metadata?.tripId) {
        LatLng(currentSample.estLat, currentSample.estLon)
    }

    val cameraPositionState = rememberCameraPositionState(key = metadata?.tripId) {
        position = CameraPosition.Builder()
            .target(initialPos)
            .zoom(17.5f)
            .tilt(if (isPerspective3D) 45f else 0f)
            .bearing(currentSample.headingDeg.toFloat())
            .build()
    }

    // Force perfect zoom to vehicle location on startup or trip reset
    var hasSnappedInitial by remember(metadata?.tripId) { mutableStateOf(false) }

    LaunchedEffect(metadata?.tripId, currentSampleIndex == 0) {
        if (!hasSnappedInitial || currentSampleIndex == 0) {
            val target = LatLng(currentSample.estLat, currentSample.estLon)
            val bearing = currentSample.headingDeg.toFloat()
            val tilt = if (isPerspective3D) 45f else 0f
            cameraPositionState.move(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(17.5f)
                        .tilt(tilt)
                        .bearing(bearing)
                        .build()
                )
            )
            hasSnappedInitial = true
        }
    }

    // High performance real-time camera tracking:
    // Using move() instead of animate() removes all frame queue lag and stutters at 10 Hz
    LaunchedEffect(currentSample.timestampMs) {
        if (hasSnappedInitial && currentSampleIndex > 0) {
            val target = LatLng(currentSample.estLat, currentSample.estLon)
            val bearing = currentSample.headingDeg.toFloat()
            val tilt = if (isPerspective3D) 45f else 0f
            val currentZoom = cameraPositionState.position.zoom.takeIf { it >= 10f } ?: 17.5f

            cameraPositionState.move(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(currentZoom)
                        .tilt(tilt)
                        .bearing(bearing)
                        .build()
                )
            )
        }
    }

    // Precalculate all LatLng polylines once to prevent garbage collection lag during 10 Hz ticks
    val truePathPoints = remember(allSamples) {
        allSamples.map { LatLng(it.trueLat, it.trueLon) }
    }

    val allEstPoints = remember(allSamples) {
        allSamples.map { LatLng(it.estLat, it.estLon) }
    }

    val originLat = metadata?.originLat ?: 28.6315
    val originLon = metadata?.originLon ?: 77.2167
    val metersPerDegLat = 111320.0
    val metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(originLat))

    val naiveLat = originLat + (currentSample.naiveY / metersPerDegLat)
    val naiveLon = originLon + (currentSample.naiveX / metersPerDegLon)
    val naivePos = LatLng(naiveLat, naiveLon)

    val allNaivePoints = remember(allSamples, metadata) {
        allSamples.map {
            LatLng(
                originLat + (it.naiveY / metersPerDegLat),
                originLon + (it.naiveX / metersPerDegLon)
            )
        }
    }

    // Slice cached points up to current sample index (zero new LatLng allocations)
    val estTraversedPoints = remember(currentSampleIndex, allEstPoints) {
        allEstPoints.take((currentSampleIndex + 1).coerceAtMost(allEstPoints.size))
    }

    val naiveTraversedPoints = remember(currentSampleIndex, allNaivePoints) {
        allNaivePoints.take((currentSampleIndex + 1).coerceAtMost(allNaivePoints.size))
    }

    // Tunnel / Outage segment points
    val outagePoints = remember(allSamples, metadata) {
        if (metadata == null) emptyList()
        else {
            val startMs = (metadata.outageStartSec * 1000).toLong()
            val endMs = (metadata.outageEndSec * 1000).toLong()
            allSamples.filter { it.timestampMs in startMs..endMs }
                .map { LatLng(it.trueLat, it.trueLon) }
        }
    }

    // Persistent MarkerStates updated in-place to prevent destroying/recreating native markers each frame
    val carMarkerState = rememberMarkerState(
        position = LatLng(currentSample.estLat, currentSample.estLon)
    )
    val naiveMarkerState = rememberMarkerState(
        position = naivePos
    )
    val trueGpsMarkerState = rememberMarkerState(
        position = LatLng(currentSample.trueLat, currentSample.trueLon)
    )

    LaunchedEffect(currentSample.estLat, currentSample.estLon) {
        carMarkerState.position = LatLng(currentSample.estLat, currentSample.estLon)
    }
    LaunchedEffect(naiveLat, naiveLon) {
        naiveMarkerState.position = naivePos
    }
    LaunchedEffect(currentSample.trueLat, currentSample.trueLon) {
        trueGpsMarkerState.position = LatLng(currentSample.trueLat, currentSample.trueLon)
    }

    // Custom marker icons
    val carIcon = remember(isBlackout) {
        MapMarkerHelper.getCarMarker(context, isBlackout)
    }
    val naiveIcon = remember {
        MapMarkerHelper.getNaiveMarker(context)
    }
    val trueGpsIcon = remember {
        MapMarkerHelper.getTrueGpsMarker(context)
    }

    val mapUiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = true,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = true,
            tiltGesturesEnabled = true,
            scrollGesturesEnabled = true
        )
    }

    val mapProperties = remember {
        MapProperties(
            isBuildingEnabled = true,
            isTrafficEnabled = false,
            mapStyleOptions = MapStyleOptions(DARK_NAVIGATION_MAP_STYLE)
        )
    }

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = mapUiSettings,
            properties = mapProperties
        ) {
            // 1. Outage / Tunnel Segment (Highlight Zone)
            if (outagePoints.isNotEmpty()) {
                Polyline(
                    points = outagePoints,
                    color = Color(0x66E040FB),
                    width = 24f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }

            // 2. Ground Truth Reference Path (Green)
            if (truePathPoints.isNotEmpty()) {
                Polyline(
                    points = truePathPoints,
                    color = Color(0xFF00E676),
                    width = 8f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }

            // 3. Naive INS Drifting Trajectory Path (Dashed Crimson Red)
            if (naiveTraversedPoints.isNotEmpty() && (isBlackout || currentSample.naiveErrorM > 4.0)) {
                Polyline(
                    points = naiveTraversedPoints,
                    color = Color(0x99EF4444),
                    width = 6f,
                    pattern = listOf(Dash(20f), Gap(12f))
                )
            }

            // 4. Estimated GeoReckon Trajectory Path (Amber/Cyan)
            if (estTraversedPoints.isNotEmpty()) {
                Polyline(
                    points = estTraversedPoints,
                    color = if (isBlackout) Color(0xFFFF9100) else Color(0xFF00E5FF),
                    width = 10f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }

            // 5. Uncertainty Covariance Circle around estimated vehicle position
            Circle(
                center = LatLng(currentSample.estLat, currentSample.estLon),
                radius = currentSample.uncertaintyM.coerceAtLeast(1.5),
                strokeColor = if (isBlackout) Color(0xFFFF9100) else Color(0xFF00E5FF),
                strokeWidth = 3f,
                fillColor = (if (isBlackout) Color(0x22FF9100) else Color(0x1800E5FF))
            )

            // 6. Drift vector line from GeoReckon vehicle to Naive drift position
            if (isBlackout || currentSample.naiveErrorM > 4.0) {
                Polyline(
                    points = listOf(LatLng(currentSample.estLat, currentSample.estLon), naivePos),
                    color = Color(0xAAEF4444),
                    width = 4f,
                    pattern = listOf(Dash(14f), Gap(8f))
                )

                // Naive INS Drift Marker (Custom Red Beacon)
                Marker(
                    state = naiveMarkerState,
                    title = "⚠️ Naive INS Drift",
                    snippet = "Uncorrected Error: ${String.format("%.1f", currentSample.naiveErrorM)}m",
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    flat = false,
                    icon = naiveIcon
                )
            }

            // 7. Ground-Truth true location marker during outage (Custom Green Satellite Crosshair)
            if (isBlackout) {
                Marker(
                    state = trueGpsMarkerState,
                    title = "True GNSS Position",
                    snippet = "GeoReckon Error: ${String.format("%.1f", currentSample.errorM)}m",
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    flat = false,
                    icon = trueGpsIcon
                )
            }

            // 8. Custom GeoReckon Car Marker (Oriented top-down sports car)
            Marker(
                state = carMarkerState,
                title = "GeoReckon AI (${currentSample.speedKmh.toInt()} km/h)",
                snippet = "Mode: ${currentSample.mode} • Uncertainty: ±${String.format("%.1f", currentSample.uncertaintyM)}m",
                rotation = currentSample.headingDeg.toFloat(),
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                flat = true,
                icon = carIcon
            )
        }

        // Floating Map Controls (3D/2D Toggle & Recenter)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 3D Perspective Toggle Button
            FloatingActionButton(
                onClick = { isPerspective3D = !isPerspective3D },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                containerColor = Color(0xEE161F2E),
                contentColor = if (isPerspective3D) Color(0xFF00E5FF) else Color.White
            ) {
                Text(
                    text = if (isPerspective3D) "3D" else "2D",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Recenter Camera Button
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        cameraPositionState.move(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(currentSample.estLat, currentSample.estLon))
                                    .zoom(17.5f)
                                    .tilt(if (isPerspective3D) 45f else 0f)
                                    .bearing(currentSample.headingDeg.toFloat())
                                    .build()
                            )
                        )
                    }
                },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                containerColor = Color(0xEE161F2E),
                contentColor = Color(0xFF00E676)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Recenter", modifier = Modifier.size(20.dp))
            }
        }

        // Bottom Map Legend Card
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xDD12161F))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = if (isBlackout) Color(0xFFFF9100) else Color(0xFF00E5FF), label = "Vehicle Car")
            LegendItem(color = Color(0xFFEF4444), label = "Naive INS")
            LegendItem(color = Color(0xFF00E676), label = "True GPS")
            if (outagePoints.isNotEmpty()) {
                LegendItem(color = Color(0xFFE040FB), label = "Outage Zone")
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text = label, color = Color(0xFFCFD8DC), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// Google Maps Night / Cyberpunk Dark Navigation Style JSON
private const val DARK_NAVIGATION_MAP_STYLE = """
[
  { "elementType": "geometry", "stylers": [{ "color": "#121620" }] },
  { "elementType": "labels.text.stroke", "stylers": [{ "color": "#121620" }] },
  { "elementType": "labels.text.fill", "stylers": [{ "color": "#748398" }] },
  { "featureType": "administrative.locality", "elementType": "labels.text.fill", "stylers": [{ "color": "#b0bec5" }] },
  { "featureType": "poi", "elementType": "labels.text.fill", "stylers": [{ "color": "#627284" }] },
  { "featureType": "poi.park", "elementType": "geometry", "stylers": [{ "color": "#18222d" }] },
  { "featureType": "road", "elementType": "geometry", "stylers": [{ "color": "#232d3d" }] },
  { "featureType": "road", "elementType": "geometry.stroke", "stylers": [{ "color": "#18202c" }] },
  { "featureType": "road", "elementType": "labels.text.fill", "stylers": [{ "color": "#9ca9ba" }] },
  { "featureType": "road.highway", "elementType": "geometry", "stylers": [{ "color": "#2c3b52" }] },
  { "featureType": "road.highway", "elementType": "geometry.stroke", "stylers": [{ "color": "#1f2a3a" }] },
  { "featureType": "road.highway", "elementType": "labels.text.fill", "stylers": [{ "color": "#cfd8dc" }] },
  { "featureType": "transit", "elementType": "geometry", "stylers": [{ "color": "#1e2736" }] },
  { "featureType": "water", "elementType": "geometry", "stylers": [{ "color": "#0d131a" }] },
  { "featureType": "water", "elementType": "labels.text.fill", "stylers": [{ "color": "#3f4d5e" }] }
]
"""
