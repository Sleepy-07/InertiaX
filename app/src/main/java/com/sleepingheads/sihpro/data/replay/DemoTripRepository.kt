package com.sleepingheads.sihpro.data.replay

import android.content.Context
import com.google.gson.Gson
import com.sleepingheads.sihpro.data.model.DemoPackage
import com.sleepingheads.sihpro.data.model.DemoTripInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * Loads deterministic offline demo trip data from bundled assets.
 * Supports multiple curated routes from the IO-VNBD dataset and synthetic benchmarks.
 */
class DemoTripRepository(private val context: Context) {
    private val gson = Gson()
    private val cache = mutableMapOf<String, DemoPackage>()

    companion object {
        val AVAILABLE_TRIPS = listOf(
            DemoTripInfo(
                id = "demo_delhi_tunnel",
                title = "Delhi Pragati Expressway Tunnel",
                scenarioType = "Expressway Tunnel Outage",
                badge = "FLAGSHIP",
                description = "40s complete GNSS blackout along Pragati Maidan Expressway with soft handover and procedural 3D corridor buildings.",
                assetPath = "demo/demo_delhi_tunnel.json",
                outageDurationSec = 40.0,
                durationSec = 120.0,
                speedKmh = "55.0 km/h",
                driftPercent = "0.26%"
            ),
            DemoTripInfo(
                id = "demo_roundabout",
                title = "Coventry Roundabout & S-Curves",
                scenarioType = "Urban Maneuvers & Turns",
                badge = "MANEUVER",
                description = "Real IO-VNBD S-S3a trial testing continuous yaw gyro integration through successive roundabout turns under satellite loss.",
                assetPath = "demo/demo_roundabout.json",
                outageDurationSec = 40.0,
                durationSec = 120.0,
                speedKmh = "21.7 km/h",
                driftPercent = "5.46%"
            ),
            DemoTripInfo(
                id = "demo_urban_commute",
                title = "Urban Stop-and-Go Commute",
                scenarioType = "City Traffic & Signals",
                badge = "ZUPT",
                description = "Real IO-VNBD S-S1 trial demonstrating Zero Velocity Updates (ZUPT) stopping accelerometer drift at red signals.",
                assetPath = "demo/demo_urban_commute.json",
                outageDurationSec = 40.0,
                durationSec = 120.0,
                speedKmh = "15.0 km/h",
                driftPercent = "7.91%"
            ),
            DemoTripInfo(
                id = "demo_expressway",
                title = "Arterial Expressway High-Speed",
                scenarioType = "High-Speed Highway",
                badge = "HIGHWAY",
                description = "Real IO-VNBD S-S3c trial testing longitudinal velocity estimation and drag dynamics at 30+ km/h sustained cruise.",
                assetPath = "demo/demo_expressway.json",
                outageDurationSec = 40.0,
                durationSec = 120.0,
                speedKmh = "31.0 km/h",
                driftPercent = "1.91%"
            )
        )
    }

    suspend fun loadDemoTrip(assetPath: String = AVAILABLE_TRIPS.first().assetPath): Result<DemoPackage> = withContext(Dispatchers.IO) {
        cache[assetPath]?.let { return@withContext Result.success(it) }
        try {
            context.assets.open(assetPath).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val pkg = gson.fromJson(reader, DemoPackage::class.java)
                    cache[assetPath] = pkg
                    Result.success(pkg)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
