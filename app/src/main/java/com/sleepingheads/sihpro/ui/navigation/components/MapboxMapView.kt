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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sleepingheads.sihpro.BuildConfig
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.TripMetadata
import com.sleepingheads.sihpro.data.repository.DestinationInfo
import com.sleepingheads.sihpro.data.repository.NavRoute
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
    activeRoute: NavRoute? = null,
    activeDestination: DestinationInfo? = null,
    onDestinationPinned: ((Double, Double) -> Unit)? = null,
    bottomPadding: Dp = 270.dp,
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

    var selectedStyle by remember { mutableStateOf(MapboxStyle.SATELLITE) }
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
    LaunchedEffect(isWebMapReady, metadata?.tripId, allSamples.size / 5) {
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

    // Push or clear navigation driving route
    LaunchedEffect(isWebMapReady, activeRoute, activeDestination) {
        if (!isWebMapReady || webViewRef == null) return@LaunchedEffect
        if (activeDestination != null && activeRoute != null) {
            val escapedGeoJson = activeRoute.geoJsonString.replace("'", "\\'")
            val js = "setNavigationRoute('$escapedGeoJson', ${activeDestination.lat}, ${activeDestination.lon});"
            webViewRef?.evaluateJavascript(js, null)
        } else if (activeDestination != null) {
            val js = "setNavigationRoute(null, ${activeDestination.lat}, ${activeDestination.lon});"
            webViewRef?.evaluateJavascript(js, null)
        } else {
            val js = "clearNavigationRoute();"
            webViewRef?.evaluateJavascript(js, null)
        }
    }

    val currentOnDestinationPinned by rememberUpdatedState(onDestinationPinned)

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

                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onMapDoubleClicked(lat: Double, lon: Double) {
                            post {
                                currentOnDestinationPinned?.invoke(lat, lon)
                            }
                        }
                    }, "AndroidBridge")

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

        // ── Floating Action Buttons (Right side of map down above bottom sheet: View Type, 3D, Recenter) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = bottomPadding, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Layer switcher FAB (Satellite vs Normal)
            Surface(
                onClick = {
                    selectedStyle = if (selectedStyle == MapboxStyle.SATELLITE) MapboxStyle.NORMAL else MapboxStyle.SATELLITE
                    webViewRef?.evaluateJavascript("setMapStyle('${selectedStyle.id}');", null)
                },
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 5.dp,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Map Style",
                        tint = Color(0xFF0284C7),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 2. 3D perspective FAB
            Surface(
                onClick = {
                    is3DMode = !is3DMode
                    webViewRef?.evaluateJavascript("set3DMode($is3DMode);", null)
                },
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 5.dp,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (is3DMode) "3D" else "2D",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
            }

            // 3. Recenter camera FAB (Blue crosshair)
            Surface(
                onClick = {
                    webViewRef?.evaluateJavascript("recenterCamera();", null)
                },
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 5.dp,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recenter Camera",
                        tint = Color(0xFF0284C7),
                        modifier = Modifier.size(22.dp)
                    )
                }
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
