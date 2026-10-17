package com.sleepingheads.sihpro.ui.navigation.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sleepingheads.sihpro.BuildConfig
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.TripMetadata
import org.json.JSONArray

enum class MapboxStyle(val id: String, val label: String) {
    NORMAL("normal", "Normal"),
    SATELLITE("satellite", "Satellite"),
    DARK("dark", "Dark")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapboxMapView(
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
    val originLat = firstValidSample?.estLat ?: metadata?.originLat ?: 12.9715987
    val originLon = firstValidSample?.estLon ?: metadata?.originLon ?: 77.5945627

    val safeEstLat = if (activeSample.estLat.isNaN() || (activeSample.estLat == 0.0 && activeSample.estLon == 0.0)) originLat else activeSample.estLat
    val safeEstLon = if (activeSample.estLon.isNaN() || (activeSample.estLat == 0.0 && activeSample.estLon == 0.0)) originLon else activeSample.estLon
    val safeTrueLat = if (activeSample.trueLat.isNaN() || (activeSample.trueLat == 0.0 && activeSample.trueLon == 0.0)) originLat else activeSample.trueLat
    val safeTrueLon = if (activeSample.trueLon.isNaN() || (activeSample.trueLat == 0.0 && activeSample.trueLon == 0.0)) originLon else activeSample.trueLon

    val metersPerDegLat = 111320.0
    val metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(originLat))
    val naiveLat = if (metersPerDegLat > 0) originLat + (activeSample.naiveY / metersPerDegLat) else originLat
    val naiveLon = if (metersPerDegLon > 0) originLon + (activeSample.naiveX / metersPerDegLon) else originLon

    val isBlackout = activeSample.mode == "DEAD_RECKONING"

    var selectedStyle by remember { mutableStateOf(MapboxStyle.NORMAL) }
    var is3DMode by remember { mutableStateOf(true) }
    var isWebMapReady by remember { mutableStateOf(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Mapbox token from local.properties injected via BuildConfig
    val mapboxToken = remember {
        BuildConfig.MAPBOX_ACCESS_TOKEN.ifEmpty {
            BuildConfig.MAPS_API_KEY
        }
    }

    // Build GeoJSON coordinate strings once for the full trip
    val (truePathJson, estPathJson, naivePathJson, outagePathJson) = remember(allSamples, metadata) {
        val trueArr = JSONArray()
        val estArr = JSONArray()
        val naiveArr = JSONArray()
        val outageArr = JSONArray()

        val outageStartMs = ((metadata?.outageStartSec ?: 0.0) * 1000).toLong()
        val outageEndMs = ((metadata?.outageEndSec ?: 0.0) * 1000).toLong()

        for (sample in allSamples) {
            if (!sample.trueLat.isNaN() && !sample.trueLon.isNaN() && (sample.trueLat != 0.0 || sample.trueLon != 0.0)) {
                trueArr.put(JSONArray().put(sample.trueLon).put(sample.trueLat))
                if (metadata != null && sample.timestampMs in outageStartMs..outageEndMs) {
                    outageArr.put(JSONArray().put(sample.trueLon).put(sample.trueLat))
                }
            }
            if (!sample.estLat.isNaN() && !sample.estLon.isNaN() && (sample.estLat != 0.0 || sample.estLon != 0.0)) {
                estArr.put(JSONArray().put(sample.estLon).put(sample.estLat))
            }
            if (!sample.naiveY.isNaN() && !sample.naiveX.isNaN()) {
                val nLat = originLat + (sample.naiveY / metersPerDegLat)
                val nLon = originLon + (sample.naiveX / metersPerDegLon)
                naiveArr.put(JSONArray().put(nLon).put(nLat))
            }
        }

        listOf(
            trueArr.toString(),
            estArr.toString(),
            naiveArr.toString(),
            outageArr.toString()
        )
    }

    // Push vehicle location updates to Mapbox GL JS
    LaunchedEffect(
        isWebMapReady,
        safeEstLat,
        safeEstLon,
        activeSample.headingDeg,
        activeSample.speedKmh,
        isBlackout,
        naiveLat,
        naiveLon
    ) {
        if (!isWebMapReady || webViewRef == null) return@LaunchedEffect

        val js = """
            updateVehicle(
                $safeEstLat,
                $safeEstLon,
                ${if (activeSample.headingDeg.isNaN()) 0.0 else activeSample.headingDeg},
                ${activeSample.speedKmh},
                '${activeSample.mode}',
                ${if (activeSample.uncertaintyM.isNaN()) 1.8 else activeSample.uncertaintyM},
                $naiveLat,
                $naiveLon,
                ${activeSample.naiveErrorM},
                $isBlackout,
                $safeTrueLat,
                $safeTrueLon
            );
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    // Push trip path geometries when map is ready or sample data changes
    LaunchedEffect(isWebMapReady, metadata?.tripId) {
        if (!isWebMapReady || webViewRef == null) return@LaunchedEffect

        val js = """
            setPathData(
                JSON.stringify($truePathJson),
                JSON.stringify($estPathJson),
                JSON.stringify($naivePathJson),
                JSON.stringify($outagePathJson)
            );
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── Hardware-Accelerated Mapbox GL JS WebView ────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = false
                        displayZoomControls = false
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            android.util.Log.d(
                                "MapboxGL",
                                "${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()} [${consoleMessage?.sourceId()}]"
                            )
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isWebMapReady = true
                            val initJs = """
                                initMap(
                                    '$mapboxToken',
                                    $safeEstLat,
                                    $safeEstLon,
                                    17.5,
                                    '${selectedStyle.id}',
                                    $is3DMode
                                );
                            """.trimIndent()
                            evaluateJavascript(initJs, null)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            android.util.Log.e("MapboxGL", "WebView error: ${error?.description}")
                        }
                    }

                    // Load pre-populated HTML with HTTPS base URL to bypass file:// CORS
                    val rawHtml = try {
                        ctx.assets.open("mapbox_map.html").bufferedReader().use { it.readText() }
                            .replace("__MAPBOX_TOKEN__", mapboxToken)
                            .replace("__DEFAULT_LAT__", safeEstLat.toString())
                            .replace("__DEFAULT_LON__", safeEstLon.toString())
                            .replace("__DEFAULT_STYLE__", selectedStyle.id)
                            .replace("__DEFAULT_3D__", is3DMode.toString())
                    } catch (e: Exception) {
                        android.util.Log.e("MapboxMapView", "Error reading mapbox_map.html", e)
                        ""
                    }

                    loadDataWithBaseURL("https://api.mapbox.com", rawHtml, "text/html", "UTF-8", null)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top-Right Map Controls: Normal/Satellite & 3D/2D (Snapchat style) ──
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 110.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Style Selector: Normal vs Satellite vs Dark
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xDD121722),
                border = BorderStroke(1.dp, Color(0x3300E5FF)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MapboxStyle.values().forEach { style ->
                        val isSelected = selectedStyle == style
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0xFF00E5FF) else Color.Transparent)
                                .clickable {
                                    selectedStyle = style
                                    webViewRef?.evaluateJavascript("setMapStyle('${style.id}');", null)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = style.label,
                                color = if (isSelected) Color(0xFF0B0E14) else Color(0xFFCFD8DC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 3D Perspective Toggle Button (Snap Map 3D Buildings & Terrain)
            FloatingActionButton(
                onClick = {
                    is3DMode = !is3DMode
                    webViewRef?.evaluateJavascript("set3DMode($is3DMode);", null)
                },
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                containerColor = if (is3DMode) Color(0xFF00E5FF) else Color(0xEE161F2E),
                contentColor = if (is3DMode) Color(0xFF0B0E14) else Color.White
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (is3DMode) "3D" else "2D",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Recenter Camera Button
            FloatingActionButton(
                onClick = {
                    webViewRef?.evaluateJavascript("recenterCamera();", null)
                },
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                containerColor = Color(0xEE161F2E),
                contentColor = Color(0xFF00E676)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter Camera",
                    modifier = Modifier.size(20.dp)
                )
            }

            // Zoom In / Out
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xEE161F2E),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                shadowElevation = 6.dp
            ) {
                Column {
                    IconButton(
                        onClick = { webViewRef?.evaluateJavascript("zoomIn();", null) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    HorizontalDivider(modifier = Modifier.width(40.dp), thickness = 1.dp, color = Color(0x22FFFFFF))
                    IconButton(
                        onClick = { webViewRef?.evaluateJavascript("zoomOut();", null) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // ── Bottom Map Legend Card ───────────────────────────────────────────
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
            if (outagePathJson.length > 5) {
                LegendItem(color = Color(0xFFE040FB), label = "Outage Zone")
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = Color(0xFFCFD8DC),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
