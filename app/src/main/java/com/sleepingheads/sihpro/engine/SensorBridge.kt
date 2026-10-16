package com.sleepingheads.sihpro.engine

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
import com.google.android.gms.location.*
import com.sleepingheads.sihpro.data.model.GnssSample
import com.sleepingheads.sihpro.data.model.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android Sensor Bridge
 *
 * Subscribes to the Android [SensorManager] (accelerometer + gyroscope)
 * and [FusedLocationProviderClient] / [LocationManager] (real GPS/GNSS),
 * converts their callbacks into [ImuSample] / [GnssSample] data classes,
 * and forwards them to [GeoReckonEngine].
 */
class SensorBridge(
    private val context: Context,
    private val engine: GeoReckonEngine,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SensorBridge"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    private var accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroSensor:  Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var rotVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Latest gyro (may arrive at different time than accel on some devices)
    private var latestGyro = FloatArray(3)
    private var latestGyroTs = 0L
    private var sampleCounter = 0

    // Down-sample factor: 50 Hz hardware → 10 Hz engine
    private val DOWN_SAMPLE = 5

    // GPS Outage Watchdog
    private var lastGpsFixWallTimeMs: Long = 0L
    private val GPS_TIMEOUT_MS = 2500L // 2.5s without GPS fix = signal lost in tunnel/outage
    private var isGpsCurrentlyLost = false

    // Single-threaded sequential dispatcher for strict chronological sensor ingestion into engine
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)

    private fun checkGpsTimeout() {
        if (lastGpsFixWallTimeMs > 0L) {
            val elapsed = System.currentTimeMillis() - lastGpsFixWallTimeMs
            if (elapsed > GPS_TIMEOUT_MS && !isGpsCurrentlyLost) {
                isGpsCurrentlyLost = true
                Log.w(TAG, "GPS signal dropped (> 2.5s without fix)! Auto-engaging On-Device Dead Reckoning.")
                scope.launch(engineDispatcher) {
                    engine.onGnssLost()
                }
            } else if (elapsed <= GPS_TIMEOUT_MS && isGpsCurrentlyLost) {
                isGpsCurrentlyLost = false
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyro = event.values.copyOf()
                    latestGyroTs = event.timestamp
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotMat = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotMat, orientation)
                    var azimuthDeg = Math.toDegrees(orientation[0].toDouble())
                    if (azimuthDeg < 0.0) azimuthDeg += 360.0
                    scope.launch(engineDispatcher) {
                        engine.onCompassHeading(azimuthDeg)
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // Check if GPS signal stopped arriving (tunnel / underpass)
                    checkGpsTimeout()

                    sampleCounter++
                    // Down-sample to ~10 Hz
                    if (sampleCounter % DOWN_SAMPLE != 0) return

                    val imu = ImuSample(
                        timestampNs = event.timestamp,
                        accelX = event.values[0].toDouble(),
                        accelY = event.values[1].toDouble(),
                        accelZ = event.values[2].toDouble(),
                        gyroX = latestGyro[0].toDouble(),
                        gyroY = latestGyro[1].toDouble(),
                        gyroZ = latestGyro[2].toDouble()
                    )
                    scope.launch(engineDispatcher) {
                        engine.onImuSample(imu)
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun handleLocation(location: Location) {
        if (location.latitude.isNaN() || location.longitude.isNaN() ||
            (location.latitude == 0.0 && location.longitude == 0.0)) {
            return
        }

        lastGpsFixWallTimeMs = System.currentTimeMillis()
        isGpsCurrentlyLost = false

        val speedMps = if (location.hasSpeed()) location.speed.toDouble() else null
        val bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null
        Log.d(TAG, "Real GPS fix: lat=${location.latitude}, lon=${location.longitude}, speed=${speedMps?.let { it * 3.6 }} km/h, bearing=$bearingDeg")

        val gnss = GnssSample(
            timestampNs = location.elapsedRealtimeNanos,
            latitude = location.latitude,
            longitude = location.longitude,
            speedMps = speedMps,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            valid = location.accuracy <= 100f,
            bearingDeg = bearingDeg
        )
        scope.launch(engineDispatcher) {
            engine.onGnssSample(gnss)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    private val legacyLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            scope.launch(engineDispatcher) {
                engine.onGnssLost()
            }
        }
    }

    /**
     * Start listening to IMU and real device GPS sensors.
     */
    fun start() {
        try {
            accelSensor?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroSensor?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            rotVectorSensor?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error registering IMU sensors: ${t.message}")
        }

        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permissions not granted, running IMU-only")
            return
        }

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
                    LocationManager.GPS_PROVIDER, 1000L, 0f, legacyLocationListener, Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0f, legacyLocationListener, Looper.getMainLooper()
                )
            }

            // Fallback anchor from LocationManager
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
     * Stop all sensor listeners.
     */
    fun stop() {
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (_: Throwable) {}

        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Throwable) {}

        try {
            locationManager.removeUpdates(legacyLocationListener)
        } catch (_: Throwable) {}

        lastGpsFixWallTimeMs = 0L
        isGpsCurrentlyLost = false
    }
}
