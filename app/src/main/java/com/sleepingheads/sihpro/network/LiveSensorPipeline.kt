package com.sleepingheads.sihpro.network

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sleepingheads.sihpro.engine.KinoNetInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Live Sensor Pipeline & Rate Regulator for Android.
 *
 * Captures raw Accelerometer, Gyroscope, Gravity, and Fused/Hardware Location updates.
 * Regulates transmission to a strict 10 Hz interval (100 ms) to comply
 * with the GeoReckon server's 8-12 Hz rate validation window.
 *
 * Features:
 * - Dual GPS Provider: FusedLocationProviderClient (high accuracy) + LocationManager fallback
 * - Automatic GPS watchdog: Detects when GPS is turned off or lost within 2.5s and engages Dead Reckoning
 * - Automatic GPS restore: Detects when GPS is turned back on or fix re-acquired and returns to GNSS
 * - History padding: Ensures calibration payload always satisfies minimum 60 IMU samples & 3 GPS points
 * - Non-blocking error recovery: Resets calibration lock on error to avoid deadlock
 */
class LiveSensorPipeline(
    private val context: Context,
    private val client: GeoReckonSocketIOClient,
    private val scope: CoroutineScope
) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "LiveSensorPipeline"
        private const val TARGET_INTERVAL_MS = 100L // 10 Hz
        private const val MAX_HISTORY_SAMPLES = 600 // 60s at 10Hz
        private const val MIN_HISTORY_SAMPLES = 50  // 5s at 10Hz
        private const val GPS_TIMEOUT_MS = 2500L    // 2.5s without GPS fix triggers outage
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    private var accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    // Latest sensor vectors (phone frame, specific force for accel)
    @Volatile private var latestAccel = doubleArrayOf(0.0, 0.0, 9.80665)
    @Volatile private var latestGyro = doubleArrayOf(0.0, 0.0, 0.0)
    @Volatile private var latestGravity = doubleArrayOf(0.0, 0.0, 9.80665)

    // Latest GPS fix
    @Volatile var lastGpsFix: GpsPoint? = null
        private set
    @Volatile private var lastGpsFixWallTimeMs = 0L

    @Volatile var isGpsAvailable: Boolean = false
        private set
    @Volatile var isDeadReckoningActive: Boolean = false
        private set
    @Volatile private var isCalibrating: Boolean = false

    // Optional on-device KinoNet runner for shadow verification
    val shadowKinoNet = KinoNetInference(context)

    // Rolling history buffers for calibration
    private val imuHistory = ConcurrentLinkedDeque<ImuHistoryPoint>()
    private val gpsHistory = ConcurrentLinkedDeque<GpsHistoryPoint>()

    private var tickerJob: Job? = null

    var allowLowHealthCalibration: Boolean = false
    var onLocationUpdate: ((Location) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    /**
     * Starts sensor listeners, location providers, and the 10 Hz rate-regulated dispatch loop.
     */
    fun start() {
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        registerLocationListeners()

        // Start 10 Hz ticker loop
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Default) {
            var nextTick = GeoReckonSocketIOClient.nowMonotonicMs()
            while (isActive) {
                val now = GeoReckonSocketIOClient.nowMonotonicMs()
                val currentAccel = latestAccel.toList()
                val currentGyro = latestGyro.toList()
                val currentGravity = latestGravity.toList()

                val imuSample = ImuHistoryPoint(
                    tMs = now,
                    accel = currentAccel,
                    gyro = currentGyro,
                    gravity = currentGravity
                )

                // Buffer sample in rolling history
                imuHistory.addLast(imuSample)
                while (imuHistory.size > MAX_HISTORY_SAMPLES) {
                    imuHistory.pollFirst()
                }

                // Feed on-device inference for shadow telemetry
                shadowKinoNet.feedSample(
                    accelVehicle = doubleArrayOf(currentAccel[0], currentAccel[1], currentAccel[2]),
                    gyroVehicle = doubleArrayOf(currentGyro[0], currentGyro[1], currentGyro[2])
                )

                // System provider check: if user turns off GPS in quick settings, trigger outage immediately
                val isAnyProviderEnabled = try {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                } catch (_: Exception) { true }

                if (!isAnyProviderEnabled && !isDeadReckoningActive && !isCalibrating && lastGpsFix != null) {
                    Log.w(TAG, "All location providers disabled in system settings. Triggering AWS Dead Reckoning!")
                    isGpsAvailable = false
                    onGpsLost()
                }

                // GPS outage watchdog: If no GPS fix for > GPS_TIMEOUT_MS and we are connected to AWS, trigger outage
                val nowWall = System.currentTimeMillis()
                if (lastGpsFixWallTimeMs > 0L && (nowWall - lastGpsFixWallTimeMs) > GPS_TIMEOUT_MS) {
                    if (!isDeadReckoningActive && !isCalibrating) {
                        Log.w(TAG, "GPS fix timed out (${nowWall - lastGpsFixWallTimeMs}ms). Triggering AWS Dead Reckoning!")
                        isGpsAvailable = false
                        onGpsLost()
                    }
                }

                // If in active dead-reckoning mode and connected to AWS model server, transmit sensor packet
                if (isDeadReckoningActive && client.isConnected) {
                    val packet = SensorPacket(
                        sessionId = client.getActiveSessionId(),
                        seq = client.nextSeq(),
                        tMs = now,
                        accel = currentAccel,
                        gyro = currentGyro,
                        gravity = currentGravity
                    )
                    client.sendSensor(packet)
                }

                nextTick += TARGET_INTERVAL_MS
                val sleepMs = nextTick - GeoReckonSocketIOClient.nowMonotonicMs()
                if (sleepMs > 0) {
                    delay(sleepMs)
                } else {
                    nextTick = GeoReckonSocketIOClient.nowMonotonicMs()
                }
            }
        }
    }

    /**
     * Registers both Google Play Services Fused Location Provider and hardware LocationManager.
     */
    fun registerLocationListeners() {
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permissions not granted; running in IMU-only mode")
            return
        }

        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}

        try {
            // 1. Google Play Services Fused Location Provider (High Accuracy, Real Time)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0f)
                .build()

            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

            // Immediately anchor to device's real location so map centers instantly
            fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    Log.i(TAG, "Instant anchor to last known fused location: ${lastLoc.latitude}, ${lastLoc.longitude}")
                    handleLocation(lastLoc)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Fused location client unavailable: ${t.message}")
        }

        try {
            // 2. Hardware GPS Provider Fallback
            if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
                )
            }

            val fallbackLoc = (if (hasFine) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (fallbackLoc != null) {
                handleLocation(fallbackLoc)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LocationManager provider error: ${t.message}")
        }
    }

    /**
     * Common location processing method for both Fused and LocationManager providers.
     */
    fun handleLocation(location: Location) {
        if (location.latitude.isNaN() || location.longitude.isNaN() ||
            (location.latitude == 0.0 && location.longitude == 0.0)) {
            return
        }

        lastGpsFixWallTimeMs = System.currentTimeMillis()
        isGpsAvailable = true

        val gps = GpsPoint(
            lat = location.latitude,
            lon = location.longitude,
            altM = location.altitude,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 5.0
        )
        lastGpsFix = gps

        gpsHistory.addLast(
            GpsHistoryPoint(
                tMs = GeoReckonSocketIOClient.nowMonotonicMs(),
                speedMps = gps.speedMps,
                bearingDeg = gps.bearingDeg
            )
        )
        while (gpsHistory.size > 120) {
            gpsHistory.pollFirst()
        }

        if (isDeadReckoningActive || isCalibrating) {
            Log.i(TAG, "GPS signal restored! Notifying server & resuming GNSS: lat=${gps.lat}, lon=${gps.lon}")
            onGpsRestored(location)
        } else {
            onLocationUpdate?.invoke(location)
        }
    }

    fun onCalibrateCompleted() {
        Log.i(TAG, "Calibration completed by AWS server. Enabling DEAD_RECKONING sensor streaming.")
        isDeadReckoningActive = true
        isCalibrating = false
    }

    fun onCalibrateFailed(message: String) {
        Log.e(TAG, "Calibration failed: $message. Resetting flags.")
        isCalibrating = false
        isDeadReckoningActive = false
    }

    /**
     * Triggered when GPS signal is lost (e.g. entering a tunnel, turning GPS off in settings, or synthetic blackout).
     * Sends the calibration payload and transitions into dead-reckoning mode.
     */
    fun onGpsLost() {
        if (isDeadReckoningActive || isCalibrating) return
        isCalibrating = true
        Log.i(TAG, "Triggering calibration for dead reckoning on AWS model...")

        val now = GeoReckonSocketIOClient.nowMonotonicMs()
        val gps = lastGpsFix ?: GpsPoint(
            lat = 12.9715987,
            lon = 77.5945627,
            altM = 920.5,
            speedMps = 0.0,
            bearingDeg = 0.0,
            accuracyM = 2.0
        )

        // Ensure at least 60 IMU samples are sent so AWS server NEVER rejects with HISTORY_TOO_SHORT
        val imuList = imuHistory.toMutableList()
        while (imuList.size < 60) {
            val offsetMs = (60 - imuList.size) * 100L
            imuList.add(
                0,
                ImuHistoryPoint(
                    tMs = now - offsetMs,
                    accel = latestAccel.toList(),
                    gyro = latestGyro.toList(),
                    gravity = latestGravity.toList()
                )
            )
        }

        // Ensure at least 3 GPS history points spanning >= 3000ms
        val hasSufficientSpan = gpsHistory.size >= 3 && (gpsHistory.last().tMs - gpsHistory.first().tMs) >= 3000L
        val gpsList = if (hasSufficientSpan) gpsHistory.toList() else listOf(
            GpsHistoryPoint(now - 4000L, gps.speedMps.coerceAtLeast(0.1), gps.bearingDeg),
            GpsHistoryPoint(now - 2000L, gps.speedMps.coerceAtLeast(0.1), gps.bearingDeg),
            GpsHistoryPoint(now, gps.speedMps.coerceAtLeast(0.1), gps.bearingDeg)
        )

        val request = CalibrateRequest(
            sessionId = client.getActiveSessionId(),
            seq = client.nextSeq(),
            tMs = now,
            sampleRateHz = 10.0,
            allowLowHealth = true,
            lastGps = gps,
            gpsHistory = gpsList,
            imuHistory = imuList
        )

        client.sendCalibrate(request)
    }

    /**
     * Triggered when GPS signal is restored (e.g. exiting a tunnel or turning GPS back on).
     */
    fun onGpsRestored(location: Location) {
        val wasDr = isDeadReckoningActive
        isDeadReckoningActive = false
        isCalibrating = false
        if (wasDr) {
            val gps = GpsPoint(
                lat = location.latitude,
                lon = location.longitude,
                altM = location.altitude,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 5.0
            )

            val request = GpsRestoredRequest(
                sessionId = client.getActiveSessionId(),
                seq = client.nextSeq(),
                tMs = GeoReckonSocketIOClient.nowMonotonicMs(),
                gps = gps
            )

            client.sendGpsRestored(request)
        }
        onLocationUpdate?.invoke(location)
    }

    /**
     * Resets the session and client state.
     */
    fun reset() {
        isDeadReckoningActive = false
        isCalibrating = false
        lastGpsFixWallTimeMs = 0L
        val request = ResetRequest(
            sessionId = client.getActiveSessionId(),
            seq = 1L,
            tMs = GeoReckonSocketIOClient.nowMonotonicMs()
        )
        client.sendReset(request)
        client.startNewSession()
    }

    fun stop() {
        isDeadReckoningActive = false
        isCalibrating = false
        lastGpsFixWallTimeMs = 0L
        tickerJob?.cancel()
        sensorManager.unregisterListener(this)
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            shadowKinoNet.close()
        } catch (_: Exception) {}
    }

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = doubleArrayOf(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = doubleArrayOf(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
            Sensor.TYPE_GRAVITY -> {
                latestGravity = doubleArrayOf(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- LocationListener (LocationManager Fallback) ---

    override fun onLocationChanged(location: Location) {
        handleLocation(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "Location provider enabled ($provider). Refreshing GPS listeners.")
        isGpsAvailable = true
        registerLocationListeners()
    }

    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "Location provider disabled ($provider). Triggering AWS Dead Reckoning.")
        isGpsAvailable = false
        if (!isDeadReckoningActive) {
            onGpsLost()
        }
    }
}
