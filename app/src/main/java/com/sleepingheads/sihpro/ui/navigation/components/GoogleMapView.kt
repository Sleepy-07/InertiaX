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
    val activeSample = currentSample ?: allSamples.lastOrNull() ?: remember {
        DemoSample(
            timestampMs = System.currentTimeMillis(),
            trueLat = metadata?.originLat ?: 12.9715987,
            trueLon = metadata?.originLon ?: 77.5945627,
            trueX = 0.0, trueY = 0.0,
            gpsLat = metadata?.originLat ?: 12.9715987,
            gpsLon = metadata?.originLon ?: 77.5945627,
            gnssAvailable = true,
            estX = 0.0, estY = 0.0,
            estLat = metadata?.originLat ?: 12.9715987,
            estLon = metadata?.originLon ?: 77.5945627,
            naiveX = 0.0, naiveY = 0.0, naiveErrorM = 0.0,
            noAiX = 0.0, noAiY = 0.0, noAiErrorM = 0.0,
            noNhcX = 0.0, noNhcY = 0.0, noNhcErrorM = 0.0,
            noMapX = 0.0, noMapY = 0.0, noMapErrorM = 0.0,
            speedMps = 0.0, speedKmh = 0.0, headingDeg = 0.0,
            aiSpeedMps = 0.0, aiUncertainty = 0.05,
            mode = "GNSS+INS", health = "NORMAL",
            uncertaintyM = 1.8, alongUncertM = 1.8, crossUncertM = 1.4,
            headingUncertDeg = 1.2, axisRatio = 1.2, errorM = 0.0,
            accelX = 0.0, accelY = 0.0, accelZ = 9.80665,
            gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0
        )
    }

    val firstValidSample = remember(allSamples) {
        allSamples.firstOrNull { !it.estLat.isNaN() && (it.estLat != 0.0 || it.estLon != 0.0) }
    }
    val originLat = firstValidSample?.estLat ?: metadata?.originLat ?: 28.6315
    val originLon = firstValidSample?.estLon ?: metadata?.originLon ?: 77.2167

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isPerspective3D by remember { mutableStateOf(true) }
    val isBlackout = activeSample.mode == "DEAD_RECKONING"

    // Safe coordinates fallback
    val safeEstLat = if (activeSample.estLat.isNaN() || (activeSample.estLat == 0.0 && activeSample.estLon == 0.0)) originLat else activeSample.estLat
    val safeEstLon = if (activeSample.estLon.isNaN() || (activeSample.estLat == 0.0 && activeSample.estLon == 0.0)) originLon else activeSample.estLon
    val safeTrueLat = if (activeSample.trueLat.isNaN() || (activeSample.trueLat == 0.0 && activeSample.trueLon == 0.0)) originLat else activeSample.trueLat
    val safeTrueLon = if (activeSample.trueLon.isNaN() || (activeSample.trueLat == 0.0 && activeSample.trueLon == 0.0)) originLon else activeSample.trueLon

    // Initial camera position centered on vehicle
    val initialPos = remember(metadata?.tripId) {
        LatLng(safeEstLat, safeEstLon)
    }

    val cameraPositionState = rememberCameraPositionState(key = metadata?.tripId) {
        position = CameraPosition.Builder()
            .target(initialPos)
            .zoom(17.5f)
            .tilt(if (isPerspective3D) 45f else 0f)
            .bearing(if (activeSample.headingDeg.isNaN()) 0f else activeSample.headingDeg.toFloat())
            .build()
    }

    val metersPerDegLat = 111320.0
    val metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(originLat))

    var lastCamTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastCamBearing by remember { mutableFloatStateOf(0f) }

    // Force perfect zoom to vehicle location on startup or when real GPS fix arrives
    var hasSnappedInitial by remember(metadata?.tripId) { mutableStateOf(false) }

    val isDefaultBangalore = Math.abs(safeEstLat - 12.9715987) < 0.001 && Math.abs(safeEstLon - 77.5945627) < 0.001
    val isRealFix = !safeEstLat.isNaN() && !safeEstLon.isNaN() && (safeEstLat != 0.0 || safeEstLon != 0.0)

    LaunchedEffect(metadata?.tripId, currentSampleIndex == 0, safeEstLat, safeEstLon) {
        if (!hasSnappedInitial || currentSampleIndex == 0 || (!isDefaultBangalore && isRealFix)) {
            val target = LatLng(safeEstLat, safeEstLon)
            val bearing = if (activeSample.headingDeg.isNaN()) 0f else activeSample.headingDeg.toFloat()
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
            if (!isDefaultBangalore && isRealFix) {
                hasSnappedInitial = true
                lastCamTarget = target
            }
        }
    }

    // High performance real-time camera tracking with deadband stabilization:
    // Using move() instead of animate() removes all frame queue lag and stutters at 10 Hz
    LaunchedEffect(activeSample.timestampMs) {
        val lat = activeSample.estLat
        val lon = activeSample.estLon
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return@LaunchedEffect

        val target = LatLng(lat, lon)
        val bearing = if (activeSample.headingDeg.isNaN()) 0f else activeSample.headingDeg.toFloat()
        val tilt = if (isPerspective3D) 45f else 0f
        val currentZoom = cameraPositionState.position.zoom.takeIf { it >= 10f } ?: 17.5f

        val isStopped = activeSample.speedKmh < 1.5
        val prevTarget = lastCamTarget

        if (prevTarget != null) {
            val dLatM = (target.latitude - prevTarget.latitude) * metersPerDegLat
            val dLonM = (target.longitude - prevTarget.longitude) * metersPerDegLon
            val distM = Math.hypot(dLatM, dLonM)
            var bDiff = Math.abs(bearing - lastCamBearing) % 360f
            if (bDiff > 180f) bDiff = 360f - bDiff

            // If stationary and displacement is under 1.5m, do not jitter the camera
            if (isStopped && distM < 1.5) {
                return@LaunchedEffect
            }

            // Deadband: skip moving camera if movement is sub-decimeter and angle change is tiny
            if (distM < 0.35 && bDiff < 1.5f) {
                return@LaunchedEffect
            }
        }

        lastCamTarget = target
        lastCamBearing = bearing

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

    // Precalculate all LatLng polylines once to prevent garbage collection lag during 10 Hz ticks
    val truePathPoints = remember(allSamples) {
        allSamples.filter { !it.trueLat.isNaN() && !it.trueLon.isNaN() && (it.trueLat != 0.0 || it.trueLon != 0.0) }
            .map { LatLng(it.trueLat, it.trueLon) }
    }

    val allEstPoints = remember(allSamples) {
        allSamples.filter { !it.estLat.isNaN() && !it.estLon.isNaN() && (it.estLat != 0.0 || it.estLon != 0.0) }
            .map { LatLng(it.estLat, it.estLon) }
    }

    val naiveLat = if (metersPerDegLat > 0) originLat + (activeSample.naiveY / metersPerDegLat) else originLat
    val naiveLon = if (metersPerDegLon > 0) originLon + (activeSample.naiveX / metersPerDegLon) else originLon
    val safeNaivePos = if (naiveLat.isNaN() || naiveLon.isNaN()) LatLng(originLat, originLon) else LatLng(naiveLat, naiveLon)

    val allNaivePoints = remember(allSamples, originLat, originLon) {
        allSamples.filter { !it.naiveY.isNaN() && !it.naiveX.isNaN() }.map {
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
        position = LatLng(safeEstLat, safeEstLon)
    )
    val naiveMarkerState = rememberMarkerState(
        position = safeNaivePos
    )
    val trueGpsMarkerState = rememberMarkerState(
        position = LatLng(safeTrueLat, safeTrueLon)
    )

    LaunchedEffect(safeEstLat, safeEstLon) {
        carMarkerState.position = LatLng(safeEstLat, safeEstLon)
    }
    LaunchedEffect(safeNaivePos) {
        naiveMarkerState.position = safeNaivePos
    }
    LaunchedEffect(safeTrueLat, safeTrueLon) {
        trueGpsMarkerState.position = LatLng(safeTrueLat, safeTrueLon)
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

    val hasLocationPermission = remember {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val mapProperties = remember(hasLocationPermission) {
        MapProperties(
            isBuildingEnabled = true,
            isTrafficEnabled = false,
            isMyLocationEnabled = hasLocationPermission,
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
            if (naiveTraversedPoints.isNotEmpty() && (isBlackout || activeSample.naiveErrorM > 4.0)) {
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
            val circleRadius = if (activeSample.uncertaintyM.isNaN()) 2.0 else activeSample.uncertaintyM.coerceIn(1.5, 200.0)
            Circle(
                center = LatLng(safeEstLat, safeEstLon),
                radius = circleRadius,
                strokeColor = if (isBlackout) Color(0xFFFF9100) else Color(0xFF00E5FF),
                strokeWidth = 3f,
                fillColor = (if (isBlackout) Color(0x22FF9100) else Color(0x1800E5FF))
            )

            // 6. Drift vector line from GeoReckon vehicle to Naive drift position
            if (isBlackout || activeSample.naiveErrorM > 4.0) {
                Polyline(
                    points = listOf(LatLng(safeEstLat, safeEstLon), safeNaivePos),
                    color = Color(0xAAEF4444),
                    width = 4f,
                    pattern = listOf(Dash(14f), Gap(8f))
                )

                // Naive INS Drift Marker (Custom Red Beacon)
                Marker(
                    state = naiveMarkerState,
                    title = "⚠️ Naive INS Drift",
                    snippet = "Uncorrected Error: ${String.format(java.util.Locale.US, "%.1f", activeSample.naiveErrorM)}m",
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
                    snippet = "GeoReckon Error: ${String.format(java.util.Locale.US, "%.1f", activeSample.errorM)}m",
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    flat = false,
                    icon = trueGpsIcon
                )
            }

            // 8. Custom GeoReckon Car Marker (Oriented top-down sports car)
            Marker(
                state = carMarkerState,
                title = "GeoReckon AI (${activeSample.speedKmh.toInt()} km/h)",
                snippet = "Mode: ${activeSample.mode} • Uncertainty: ±${String.format(java.util.Locale.US, "%.1f", circleRadius)}m",
                rotation = if (activeSample.headingDeg.isNaN()) 0f else activeSample.headingDeg.toFloat(),
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
                                    .target(LatLng(activeSample.estLat, activeSample.estLon))
                                    .zoom(17.5f)
                                    .tilt(if (isPerspective3D) 45f else 0f)
                                    .bearing(activeSample.headingDeg.toFloat())
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
