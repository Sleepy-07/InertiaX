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
 * - True GNSS Ground-Truth trajectory (Green)
 * - Dead-Reckoned trajectory (Amber/Orange)
 * - Tunnel / Outage Blackout Zone (Neon Magenta)
 * - Real-time vehicle cursor with heading orientation and uncertainty covariance circle
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

    val coroutineScope = rememberCoroutineScope()
    var isPerspective3D by remember { mutableStateOf(true) }

    val initialPos = LatLng(currentSample.estLat, currentSample.estLon)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.Builder()
            .target(initialPos)
            .zoom(17.5f)
            .tilt(if (isPerspective3D) 45f else 0f)
            .bearing(currentSample.headingDeg.toFloat())
            .build()
    }

    // Keep camera smoothly following vehicle
    LaunchedEffect(currentSample.timestampMs) {
        val target = LatLng(currentSample.estLat, currentSample.estLon)
        val bearing = currentSample.headingDeg.toFloat()
        val tilt = if (isPerspective3D) 45f else 0f

        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(17.5f)
                    .tilt(tilt)
                    .bearing(bearing)
                    .build()
            ),
            durationMs = 95
        )
    }

    // Ground truth points (all samples)
    val truePathPoints = remember(allSamples) {
        allSamples.map { LatLng(it.trueLat, it.trueLon) }
    }

    // Current traversed estimated points up to current sample
    val estTraversedPoints = remember(currentSampleIndex) {
        allSamples.take(currentSampleIndex + 1).map { LatLng(it.estLat, it.estLon) }
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

            // 3. Estimated Trajectory Path (Amber/Orange)
            if (estTraversedPoints.isNotEmpty()) {
                Polyline(
                    points = estTraversedPoints,
                    color = Color(0xFFFF9100),
                    width = 12f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }

            // 4. Uncertainty Covariance Circle around estimated vehicle position
            Circle(
                center = LatLng(currentSample.estLat, currentSample.estLon),
                radius = currentSample.uncertaintyM.coerceAtLeast(1.5),
                strokeColor = if (currentSample.mode == "DEAD_RECKONING") Color(0xFFFF9100) else Color(0xFF00E5FF),
                strokeWidth = 3f,
                fillColor = (if (currentSample.mode == "DEAD_RECKONING") Color(0x22FF9100) else Color(0x1800E5FF))
            )

            // 5. Vehicle Cursor Marker
            Marker(
                state = MarkerState(position = LatLng(currentSample.estLat, currentSample.estLon)),
                title = "Vehicle (${currentSample.speedKmh} km/h)",
                snippet = "Mode: ${currentSample.mode} • Uncertainty: ±${currentSample.uncertaintyM}m",
                rotation = currentSample.headingDeg.toFloat(),
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                flat = true,
                icon = BitmapDescriptorFactory.defaultMarker(
                    if (currentSample.mode == "DEAD_RECKONING") BitmapDescriptorFactory.HUE_ORANGE
                    else BitmapDescriptorFactory.HUE_CYAN
                )
            )

            // 6. Ground-Truth true location marker during outage for comparison
            if (currentSample.mode == "DEAD_RECKONING") {
                Marker(
                    state = MarkerState(position = LatLng(currentSample.trueLat, currentSample.trueLon)),
                    title = "True GNSS Position",
                    snippet = "Error: ${currentSample.errorM}m",
                    rotation = currentSample.headingDeg.toFloat(),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    flat = true,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                )
            }
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
                        cameraPositionState.animate(
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
                .padding(start = 16.dp, bottom = 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xDD12161F))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = Color(0xFF00E676), label = "True GPS")
            LegendItem(color = Color(0xFFFF9100), label = "Dead Reckoned")
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
