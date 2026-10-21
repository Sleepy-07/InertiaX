package com.sleepingheads.sihpro.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class DestinationInfo(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String = ""
)

data class NavRoute(
    val points: List<Pair<Double, Double>>, // (lat, lon) pairs
    val geoJsonString: String,              // LineString GeoJSON for Mapbox
    val distanceM: Double,
    val durationSec: Double,
    val summary: String,
    val etaString: String
)

object RoutingService {
    private const val TAG = "RoutingService"

    /**
     * Search for places/addresses matching [query] using Mapbox Geocoding API with Nominatim fallback.
     */
    suspend fun searchPlaces(
        query: String,
        proximityLat: Double? = null,
        proximityLon: Double? = null,
        mapboxToken: String = ""
    ): List<DestinationInfo> = withContext(Dispatchers.IO) {
        if (query.trim().isEmpty()) return@withContext emptyList()

        val results = mutableListOf<DestinationInfo>()
        val cleanToken = mapboxToken.trim()

        // 1. Try Mapbox Geocoding API if token is available
        if (cleanToken.isNotEmpty() && cleanToken.startsWith("pk.")) {
            try {
                val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
                var urlStr = "https://api.mapbox.com/geocoding/v5/mapbox.places/$encodedQuery.json?access_token=$cleanToken&autocomplete=true&limit=6"
                if (proximityLat != null && proximityLon != null && !proximityLat.isNaN() && !proximityLon.isNaN()) {
                    urlStr += "&proximity=$proximityLon,$proximityLat"
                }

                val jsonStr = httpGet(urlStr, 4000)
                if (jsonStr != null) {
                    val root = JSONObject(jsonStr)
                    val features = root.optJSONArray("features")
                    if (features != null) {
                        for (i in 0 until features.length()) {
                            val feat = features.getJSONObject(i)
                            val text = feat.optString("text", "")
                            val placeName = feat.optString("place_name", text)
                            val center = feat.optJSONArray("center")
                            if (center != null && center.length() >= 2) {
                                val lon = center.getDouble(0)
                                val lat = center.getDouble(1)
                                results.add(
                                    DestinationInfo(
                                        name = text.ifEmpty { placeName },
                                        lat = lat,
                                        lon = lon,
                                        address = placeName
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Mapbox geocoding error: ${t.message}")
            }
        }

        // 2. Fallback to OpenStreetMap Nominatim if Mapbox returned empty or failed
        if (results.isEmpty()) {
            try {
                val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
                val urlStr = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=6&addressdetails=1"
                val jsonStr = httpGet(urlStr, 4000, userAgent = "InertiaX-App/1.0")
                if (jsonStr != null) {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val name = item.optString("name", item.optString("display_name", ""))
                        val displayName = item.optString("display_name", "")
                        val lat = item.optDouble("lat", 0.0)
                        val lon = item.optDouble("lon", 0.0)
                        if (lat != 0.0 && lon != 0.0) {
                            results.add(
                                DestinationInfo(
                                    name = name.ifEmpty { "Location" },
                                    lat = lat,
                                    lon = lon,
                                    address = displayName
                                )
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Nominatim geocoding error: ${t.message}")
            }
        }

        results
    }

    /**
     * Reverse geocodes a coordinate to a human-readable place name.
     */
    suspend fun reverseGeocode(
        lat: Double,
        lon: Double,
        mapboxToken: String = ""
    ): String = withContext(Dispatchers.IO) {
        val cleanToken = mapboxToken.trim()
        if (cleanToken.isNotEmpty() && cleanToken.startsWith("pk.")) {
            try {
                val urlStr = "https://api.mapbox.com/geocoding/v5/mapbox.places/$lon,$lat.json?access_token=$cleanToken&limit=1"
                val jsonStr = httpGet(urlStr, 3500)
                if (jsonStr != null) {
                    val root = JSONObject(jsonStr)
                    val features = root.optJSONArray("features")
                    if (features != null && features.length() > 0) {
                        val feat = features.getJSONObject(0)
                        val text = feat.optString("text", "")
                        val placeName = feat.optString("place_name", text)
                        return@withContext if (text.isNotEmpty()) text else placeName
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Reverse geocoding error: ${t.message}")
            }
        }

        // Default coordinate string
        String.format(Locale.US, "Location (%.4f, %.4f)", lat, lon)
    }

    /**
     * Fetches the shortest & fastest driving route from (startLat, startLon) to (destLat, destLon)
     * using Mapbox Directions v5 with dynamic traffic avoidance, with fallback to OSRM.
     */
    suspend fun fetchRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        mapboxToken: String = ""
    ): NavRoute? = withContext(Dispatchers.IO) {
        val cleanToken = mapboxToken.trim()

        // 1. Try Mapbox Directions v5 (Fastest route with traffic avoidance)
        if (cleanToken.isNotEmpty() && cleanToken.startsWith("pk.")) {
            try {
                val urlStr = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$startLon,$startLat;$destLon,$destLat?geometries=geojson&overview=full&steps=true&access_token=$cleanToken"
                val jsonStr = httpGet(urlStr, 5000)
                if (jsonStr != null) {
                    val root = JSONObject(jsonStr)
                    val routes = root.optJSONArray("routes")
                    if (routes != null && routes.length() > 0) {
                        val routeObj = routes.getJSONObject(0)
                        val distanceM = routeObj.optDouble("distance", 0.0)
                        val durationSec = routeObj.optDouble("duration", 0.0)
                        val geometry = routeObj.optJSONObject("geometry")
                        val coordsArray = geometry?.optJSONArray("coordinates")

                        if (coordsArray != null && coordsArray.length() > 0) {
                            val points = mutableListOf<Pair<Double, Double>>()
                            for (i in 0 until coordsArray.length()) {
                                val coord = coordsArray.getJSONArray(i)
                                val cLon = coord.getDouble(0)
                                val cLat = coord.getDouble(1)
                                points.add(Pair(cLat, cLon))
                            }

                            val etaMins = (durationSec / 60.0).toInt().coerceAtLeast(1)
                            val etaHours = etaMins / 60
                            val remMins = etaMins % 60
                            val etaString = if (etaHours > 0) "${etaHours}h ${remMins}m" else "${etaMins} min"

                            return@withContext NavRoute(
                                points = points,
                                geoJsonString = geometry.toString(),
                                distanceM = distanceM,
                                durationSec = durationSec,
                                summary = "Fastest Route via Mapbox Directions",
                                etaString = etaString
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Mapbox directions error: ${t.message}")
            }
        }

        // 2. Fallback to Open Source Routing Machine (OSRM)
        try {
            val urlStr = "https://router.project-osrm.org/route/v1/driving/$startLon,$startLat;$destLon,$destLat?overview=full&geometries=geojson&steps=true"
            val jsonStr = httpGet(urlStr, 5000, userAgent = "InertiaX-App/1.0")
            if (jsonStr != null) {
                val root = JSONObject(jsonStr)
                val routes = root.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val routeObj = routes.getJSONObject(0)
                    val distanceM = routeObj.optDouble("distance", 0.0)
                    val durationSec = routeObj.optDouble("duration", 0.0)
                    val geometry = routeObj.optJSONObject("geometry")
                    val coordsArray = geometry?.optJSONArray("coordinates")

                    if (coordsArray != null && coordsArray.length() > 0) {
                        val points = mutableListOf<Pair<Double, Double>>()
                        for (i in 0 until coordsArray.length()) {
                            val coord = coordsArray.getJSONArray(i)
                            val cLon = coord.getDouble(0)
                            val cLat = coord.getDouble(1)
                            points.add(Pair(cLat, cLon))
                        }

                        val etaMins = (durationSec / 60.0).toInt().coerceAtLeast(1)
                        val etaHours = etaMins / 60
                        val remMins = etaMins % 60
                        val etaString = if (etaHours > 0) "${etaHours}h ${remMins}m" else "${etaMins} min"

                        return@withContext NavRoute(
                            points = points,
                            geoJsonString = geometry.toString(),
                            distanceM = distanceM,
                            durationSec = durationSec,
                            summary = "Shortest Route via OSRM Engine",
                            etaString = etaString
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OSRM routing error: ${t.message}")
        }

        // 3. Fallback: Straight line path if offline or zero network
        val dLatM = (destLat - startLat) * 111320.0
        val dLonM = (destLon - startLon) * 111320.0 * Math.cos(Math.toRadians(startLat))
        val directDistanceM = Math.hypot(dLatM, dLonM)
        val directDurationSec = (directDistanceM / 11.11).coerceAtLeast(60.0) // ~40 km/h average
        val etaMins = (directDurationSec / 60.0).toInt().coerceAtLeast(1)

        val fallbackCoords = JSONArray().apply {
            put(JSONArray().put(startLon).put(startLat))
            put(JSONArray().put(destLon).put(destLat))
        }
        val fallbackGeoJson = JSONObject().apply {
            put("type", "LineString")
            put("coordinates", fallbackCoords)
        }

        NavRoute(
            points = listOf(Pair(startLat, startLon), Pair(destLat, destLon)),
            geoJsonString = fallbackGeoJson.toString(),
            distanceM = directDistanceM,
            durationSec = directDurationSec,
            summary = "Direct Route (Offline Estimate)",
            etaString = "${etaMins} min"
        )
    }

    private fun httpGet(urlStr: String, timeoutMs: Int, userAgent: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent)
            }
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                sb.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
