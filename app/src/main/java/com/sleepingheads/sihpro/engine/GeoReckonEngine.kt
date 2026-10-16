package com.sleepingheads.sihpro.engine

import android.content.Context
import com.sleepingheads.sihpro.data.model.GnssSample
import com.sleepingheads.sihpro.data.model.ImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * GeoReckon Engine — Main Orchestrator
 *
 * Wires together all 5 phases of [georeckon_pipeline.py] into a single
 * class that accepts live Android sensor samples and emits [EngineOutput]
 * via StateFlow for the UI layer.
 *
 * Pipeline execution per IMU sample (10 Hz):
 *  1. Phase 1: SignalFilter — Butterworth + MAD on raw IMU axes
 *  2. Phase 2: PhoneAligner — estimate/apply phone-to-vehicle transform
 *  3. Phase 3: KinoNetInference — predict forward speed + uncertainty
 *  4. Phase 4: InvariantEKF.predict → apply AI speed → NHC → ZUPT → GNSS update
 *              → tunnel wall (when in blackout)
 *
 * All computation is synchronous and runs on whichever coroutine context the
 * caller uses.  Wrap in Dispatchers.Default for non-blocking UI.
 *
 * @param context Android context (needed for TFLite asset loading)
 */
class GeoReckonEngine(context: Context) {

    // ---- Config ----
    private val DT = 0.1               // 10 Hz timestep
    private val METERS_PER_DEG_LAT = 111320.0
    private val WINDOW_HISTORY = 12    // samples for terrain / stop detection

    // ---- Pipeline components (Phase 1-4) ----
    private val aligner = PhoneAligner()
    private val kinoNet = KinoNetInference(context)
    private val ekf = InvariantEKF()

    // ---- Signal filter buffers (one per axis) ----
    // We maintain a rolling raw-signal buffer then filter the last N samples.
    // For real-time use we apply a causal IIR approximation: process each
    // sample through the IIR state rather than batching (practical trade-off).
    private val accelRawBuf = Array(3) { ArrayDeque<Double>(50) }
    private val gyroRawBuf  = Array(3) { ArrayDeque<Double>(50) }
    private val accelFiltBuf= Array(3) { ArrayDeque<Double>(WINDOW_HISTORY + 1) }
    private val gyroFiltBuf = Array(3) { ArrayDeque<Double>(WINDOW_HISTORY + 1) }

    // Per-channel IIR state (Direct-Form II Transposed) — one per axis
    private val accelIirState = Array(3) { IirState() }
    private val gyroIirState  = Array(3) { IirState() }

    // ---- GPS origin ----
    private var lat0: Double = Double.NaN
    private var lon0: Double = Double.NaN
    private var hasRealGnssFix: Boolean = false
    private var metersPerDegLon: Double = 111320.0
    private var latestGpsSpeedMps: Double? = null
    private var latestGpsBearingDeg: Double? = null

    // ---- Calibration state ----
    private var isCalibrated = false
    private var calibStaticAccels = mutableListOf<DoubleArray>()
    private var calibStaticGyros = mutableListOf<DoubleArray>()
    private var calibFirstMoveAccels = mutableListOf<DoubleArray>()
    private var staticSampleCount = 0
    private var prevWasStatic = false

    // ---- Tracking history ----
    private val recentAccelVehicle = ArrayDeque<DoubleArray>(WINDOW_HISTORY + 1)
    private val recentGyroVehicle  = ArrayDeque<DoubleArray>(WINDOW_HISTORY + 1)

    // ---- GNSS state ----
    private var lastGnssPos: DoubleArray? = null
    private var prevGnssAvailable = true
    private var sampleCount = 0L

    // ---- Naive INS Baseline & Distance Tracking ----
    private var naiveX: Double = 0.0
    private var naiveY: Double = 0.0
    private var outageElapsedSec: Double = 0.0
    private var totalTripDistanceM: Double = 0.0
    private var lastPosX: Double = 0.0
    private var lastPosY: Double = 0.0
    private var hasMovedBefore: Boolean = false
    private var stationaryAnchorPos = DoubleArray(2)
    private var lastStableHeadingDeg: Double = 0.0
    private var hasInitialHeading: Boolean = false
    private var isCurrentlyStationary: Boolean = true
    private var consecutiveMotionFrames: Int = 0

    // ---- Output StateFlow ----
    private val _output = MutableStateFlow(EngineOutput())
    val output: StateFlow<EngineOutput> = _output.asStateFlow()

    // -----------------------------------------------------------------------
    // PRIMARY ENTRY POINTS
    // -----------------------------------------------------------------------

    /**
     * Initializes device orientation from phone hardware magnetometer/rotation vector.
     */
    @Synchronized
    fun onCompassHeading(azimuthDeg: Double) {
        if (!hasInitialHeading) {
            lastStableHeadingDeg = azimuthDeg
            ekf.alignHeading(azimuthDeg)
            hasInitialHeading = true
        }
    }

    /**
     * Reliable stationary detection based on multi-sensor agreement:
     * 1. Low IMU accelerometer dynamic variance (no vehicle acceleration or road vibration)
     * 2. Low gyroscope angular rate & variance (no vehicle turning)
     * 3. Hysteresis to prevent sensor noise spikes from breaking stationary lock
     *
     * Note: We intentionally avoid relying on GPS speed to prove motion, because indoor GPS
     * multipath speed jitter is commonly 0.5 - 2.5 m/s (2 - 9 km/h) even while resting on a table.
     */
    private fun checkIsStationary(
        recentAccels: List<DoubleArray>,
        recentGyros: List<DoubleArray>,
        gyroVehicle: DoubleArray,
        latestGpsSpeed: Double?
    ): Boolean {
        // Startup default: strictly stationary until deliberate motion is established
        if (recentAccels.size < 5) return true

        // 1. Accelerometer dynamic variance:
        val magsA = recentAccels.mapNotNull { a -> 
            if (a != null && a.size >= 3) sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) else null 
        }
        if (magsA.size < 5) return true
        val meanA = magsA.average()
        val accelVar = magsA.sumOf { (it - meanA) * (it - meanA) } / magsA.size

        // 2. Gyroscope rate and variance:
        val gyroMag = if (gyroVehicle.size >= 3) sqrt(gyroVehicle[0] * gyroVehicle[0] + gyroVehicle[1] * gyroVehicle[1] + gyroVehicle[2] * gyroVehicle[2]) else 0.0
        val gyroVar = if (recentGyros.size >= 5) {
            val magsG = recentGyros.mapNotNull { g -> 
                if (g != null && g.size >= 3) sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) else null 
            }
            if (magsG.isNotEmpty()) {
                val meanG = magsG.average()
                magsG.sumOf { (it - meanG) * (it - meanG) } / magsG.size
            } else 0.0
        } else 0.0

        // IMU stationary condition:
        // On a table or in a stopped car with engine idling, accelVar is < 0.22 and gyro is calm.
        // When driving, road bumps and turns produce accelVar > 0.35 and gyro turns > 0.15 rad/s.
        val imuIsQuiet = accelVar < 0.22 && (gyroMag < 0.12 || gyroVar < 0.008)

        // GPS agreement:
        // Only consider GPS moving if speed is consistently high (>= 3.5 m/s = 12.6 km/h) AND IMU shows vibration.
        val gpsShowsDefiniteDriving = (latestGpsSpeed ?: 0.0) >= 3.5 && accelVar > 0.15

        val instantStationary = imuIsQuiet && !gpsShowsDefiniteDriving

        // Hysteresis: require 4 consecutive frames of motion before unlocking from stationary
        if (instantStationary) {
            consecutiveMotionFrames = 0
            isCurrentlyStationary = true
        } else {
            consecutiveMotionFrames++
            if (consecutiveMotionFrames >= 4) {
                isCurrentlyStationary = false
            }
        }

        return isCurrentlyStationary
    }

    /**
     * Process one IMU sample. Call from a sensor listener at ~10-50 Hz.
     * The engine down-samples internally to 10 Hz for the filter + EKF.
     *
     * @param sample Raw IMU sample in phone frame (m/s², rad/s)
     */
    @Synchronized
    fun onImuSample(sample: ImuSample) {
        sampleCount++

        val rawAccel = doubleArrayOf(sample.accelX, sample.accelY, sample.accelZ)
        val rawGyro  = doubleArrayOf(sample.gyroX, sample.gyroY, sample.gyroZ)

        // ---- Phase 1: Apply causal Butterworth IIR filter per axis ----
        val filtAccel = DoubleArray(3) { i -> accelIirState[i].process(rawAccel[i]) }
        val filtGyro  = DoubleArray(3) { i -> gyroIirState[i].process(rawGyro[i]) }

        // ---- Phase 2: Collect calibration data or apply transform ----
        if (!isCalibrated) {
            handleCalibration(filtAccel, filtGyro)
            return
        }

        // Apply phone-to-vehicle rotation + bias subtraction
        val accelRotated = aligner.matMul3(aligner.rPhoneToVehicle, filtAccel)
        val gyroRotated  = aligner.matMul3(aligner.rPhoneToVehicle, filtGyro)
        val accelVehicle = DoubleArray(3) { accelRotated[it] - aligner.accelBias[it] }
        val gyroVehicle  = DoubleArray(3) { gyroRotated[it]  - aligner.gyroBias[it] }

        // Remove gravity from vertical (Z) axis
        val GRAVITY = 9.80665
        accelVehicle[2] -= GRAVITY

        // Track recent for terrain/stop detection
        recentAccelVehicle.addLast(accelVehicle.copyOf())
        if (recentAccelVehicle.size > WINDOW_HISTORY) recentAccelVehicle.removeFirst()

        recentGyroVehicle.addLast(gyroVehicle.copyOf())
        if (recentGyroVehicle.size > WINDOW_HISTORY) recentGyroVehicle.removeFirst()

        // 1. Detect stationary state BEFORE running prediction
        val accelsCopy = recentAccelVehicle.filterNotNull().map { it.clone() }
        val gyrosCopy  = recentGyroVehicle.filterNotNull().map { it.clone() }
        val stopped = checkIsStationary(accelsCopy, gyrosCopy, gyroVehicle, latestGpsSpeedMps)

        if (stopped) {
            // Continually zero out gyro bias drift while phone is resting on a table or stopped
            aligner.updateGyroBiasStationary(filtGyro)
            ekf.bG.fill(0.0)
        }

        // ---- Phase 3: Feed KinoNet window + predict speed ----
        kinoNet.feedSample(accelVehicle, gyroVehicle)

        // ---- Phase 4: EKF prediction step (frozen when stationary) ----
        ekf.predict(accelVehicle, gyroVehicle, DT, isStationary = stopped)

        val rough = ekf.isRoughTerrain(recentAccelVehicle.toList())
        val (rawSpeedPred, rawSpeedVar) = if (stopped) Pair(0.0, 0.001) else (kinoNet.predict() ?: Pair(0.0, 10.0))

        // Fuse real vehicle GPS speed when available; fall back to AI prediction during GNSS blackout
        val isGpsActive = ekf.mode == "GNSS+INS" && latestGpsSpeedMps != null
        val speedPred = when {
            stopped -> 0.0
            isGpsActive -> latestGpsSpeedMps!!
            else -> maxOf(0.0, rawSpeedPred)
        }
        val speedVar  = when {
            stopped -> 0.0001
            isGpsActive -> 0.05
            else -> rawSpeedVar
        }

        if (stopped) {
            // ZUPT: Zero Velocity Update when stationary
            ekf.applyZupt()
            ekf.v[0] = 0.0
            ekf.v[1] = 0.0
            ekf.v[2] = 0.0
            // Pin position to stationary anchor point to reject cross-covariance drift
            ekf.pos[0] = stationaryAnchorPos[0]
            ekf.pos[1] = stationaryAnchorPos[1]
            ekf.pos[2] = 0.0
        } else {
            // AI / Fused speed measurement update
            ekf.applyAiSpeedUpdate(speedPred, speedVar)
            // NHC constraints (terrain-adaptive)
            ekf.applyNhc(rough)
        }

        // Distance accumulator (only when moving)
        val currentPosX = ekf.pos[0]
        val currentPosY = ekf.pos[1]
        if (!stopped && hasMovedBefore) {
            val dStep = hypot(currentPosX - lastPosX, currentPosY - lastPosY)
            if (dStep in 0.01..25.0) {
                totalTripDistanceM += dStep
            }
        }
        lastPosX = currentPosX
        lastPosY = currentPosY
        hasMovedBefore = true

        // Heading calculation: locked when stationary, smoothed when moving
        val heading = calculateHeading(speedPred, stopped)

        // Naive INS baseline simulation during outage
        val isBlackout = ekf.mode == "DEAD_RECKONING"

        if (!isBlackout || stopped) {
            // Under clear GPS or while resting, Naive INS tracks position exactly
            naiveX = currentPosX
            naiveY = currentPosY
            outageElapsedSec = 0.0
        } else {
            // During outage while driving: simulate unconstrained double-integration drift
            outageElapsedSec += DT
            val rad = Math.toRadians(heading)
            val driftHeading = rad + (0.02 * outageElapsedSec)
            val driftSpeed = maxOf(0.0, speedPred)
            val nVx = driftSpeed * sin(driftHeading) + (0.05 * outageElapsedSec * cos(driftHeading))
            val nVy = driftSpeed * cos(driftHeading) - (0.07 * outageElapsedSec * sin(driftHeading))
            naiveX += nVx * DT
            naiveY += nVy * DT
        }
        val naiveErrorM = if (isBlackout && !stopped) hypot(naiveX - currentPosX, naiveY - currentPosY) else 0.0

        // ---- Emit output ----
        emitOutput(speedPred, speedVar, stopped, rough, heading, naiveErrorM)
    }

    var isSimulatedBlackout: Boolean = false
    val activeModelEngine: String get() = kinoNet.activeEngine

    /**
     * Triggered automatically when GPS fixes stop arriving (tunnel, underpass, urban canyon),
     * or manually when the user toggles a simulated outage while driving.
     */
    @Synchronized
    fun onGnssLost() {
        if (prevGnssAvailable) {
            ekf.mode = "DEAD_RECKONING"
            lastGnssPos = null
            prevGnssAvailable = false
            latestGpsSpeedMps = null
            latestGpsBearingDeg = null
            outageElapsedSec = 0.0
        }
    }

    /**
     * Process one GNSS fix. Call from LocationManager / FusedLocation callback.
     *
     * @param sample GPS fix (lat/lon degrees, speed m/s, accuracy m, bearing deg)
     */
    @Synchronized
    fun onGnssSample(sample: GnssSample) {
        if (isSimulatedBlackout) {
            onGnssLost()
            return
        }

        val gnssNowAvailable = sample.valid &&
                !sample.latitude.isNaN() && !sample.longitude.isNaN() &&
                (sample.latitude != 0.0 || sample.longitude != 0.0)

        if (gnssNowAvailable) {
            // Anchor origin to real hardware location on first fix
            if (!hasRealGnssFix || lat0.isNaN()) {
                lat0 = sample.latitude
                lon0 = sample.longitude
                metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(lat0))
                ekf.pos = doubleArrayOf(0.0, 0.0, 0.0)
                stationaryAnchorPos[0] = 0.0
                stationaryAnchorPos[1] = 0.0
                hasRealGnssFix = true
                if (sample.bearingDeg != null && !hasInitialHeading) {
                    lastStableHeadingDeg = sample.bearingDeg
                    ekf.alignHeading(sample.bearingDeg)
                    hasInitialHeading = true
                }
            }

            latestGpsSpeedMps = sample.speedMps

            // Only accept GPS bearing when driving forward reliably (>= 2.5 m/s or 9.0 km/h) and not stationary
            if (sample.bearingDeg != null && (sample.speedMps ?: 0.0) >= 2.5 && !isCurrentlyStationary) {
                latestGpsBearingDeg = sample.bearingDeg
                ekf.alignHeading(sample.bearingDeg)
                lastStableHeadingDeg = sample.bearingDeg
                hasInitialHeading = true
            }

            val gpsX = (sample.longitude - lon0) * metersPerDegLon
            val gpsY = (sample.latitude  - lat0) * METERS_PER_DEG_LAT
            val gpsPos = doubleArrayOf(gpsX, gpsY, 0.0)

            val isReentry = !prevGnssAvailable && gnssNowAvailable  // tunnel exit

            // Zero-Speed Position Pinning:
            // When stopped / resting on a table, ignore indoor/stationary GPS jitter completely!
            val distFromAnchor = hypot(gpsPos[0] - stationaryAnchorPos[0], gpsPos[1] - stationaryAnchorPos[1])
            val isCarStationary = isCurrentlyStationary || ((sample.speedMps ?: 0.0) < 1.0 && distFromAnchor < 6.0)

            if (isCarStationary && !isReentry) {
                // Strictly pin position to the stationary anchor point — reject GPS jitter!
                ekf.pos[0] = stationaryAnchorPos[0]
                ekf.pos[1] = stationaryAnchorPos[1]
                ekf.pos[2] = 0.0
                ekf.v[0] = 0.0; ekf.v[1] = 0.0; ekf.v[2] = 0.0
                ekf.mode = "GNSS+INS"
            } else {
                stationaryAnchorPos[0] = gpsPos[0]
                stationaryAnchorPos[1] = gpsPos[1]

                if (isReentry) {
                    // GNSS re-entry: snap EKF position to GPS, widen covariance
                    ekf.pos = gpsPos.copyOf()
                    val posVar = 6.25  // GNSS_POSITION_STD² = 2.5²
                    for (i in 0..2) ekf.cov[(i + 6) * 15 + (i + 6)] = posVar
                    ekf.mode = "GNSS+INS"
                    naiveX = gpsPos[0]
                    naiveY = gpsPos[1]
                    outageElapsedSec = 0.0
                }

                val gpsVel = if (sample.speedMps != null && sample.bearingDeg != null && (sample.speedMps ?: 0.0) >= 2.5) {
                    val rad = Math.toRadians(sample.bearingDeg)
                    doubleArrayOf(sample.speedMps * sin(rad), sample.speedMps * cos(rad), 0.0)
                } else null

                ekf.updateGnss(gpsPos, gpsVel, forceUpdate = isReentry)
                lastGnssPos = gpsPos
                ekf.mode = "GNSS+INS"
            }
        } else {
            onGnssLost()
        }

        prevGnssAvailable = gnssNowAvailable
    }

    /**
     * Force calibration with a static snapshot (used in demo/replay mode
     * to skip the real-time calibration phase).
     */
    fun forceCalibrate(
        staticAccels: List<DoubleArray>,
        staticGyros: List<DoubleArray>,
        firstMoveAccels: List<DoubleArray> = emptyList()
    ) {
        aligner.calibrateFromSnapshot(staticAccels, staticGyros, firstMoveAccels)
        isCalibrated = true
    }

    /** Reset engine state (call on new trip or restart). */
    @Synchronized
    fun reset() {
        ekf.R = aligner.identity3()
        ekf.v = DoubleArray(3)
        ekf.pos = DoubleArray(3)
        ekf.bA = DoubleArray(3)
        ekf.bG = DoubleArray(3)
        for (i in ekf.cov.indices) ekf.cov[i] = if (i % 15 == i / 15) 0.01 else 0.0
        ekf.mode = "GNSS+INS"
        lat0 = Double.NaN; lon0 = Double.NaN
        metersPerDegLon = 111320.0
        hasRealGnssFix = false
        latestGpsSpeedMps = null
        latestGpsBearingDeg = null
        lastGnssPos = null; prevGnssAvailable = true; sampleCount = 0L
        recentAccelVehicle.clear()
        recentGyroVehicle.clear()
        for (s in accelIirState) s.reset()
        for (s in gyroIirState) s.reset()
        isCalibrated = false
        calibStaticAccels.clear(); calibStaticGyros.clear(); calibFirstMoveAccels.clear()
        staticSampleCount = 0; prevWasStatic = false
        naiveX = 0.0; naiveY = 0.0
        outageElapsedSec = 0.0
        totalTripDistanceM = 0.0
        lastPosX = 0.0; lastPosY = 0.0
        hasMovedBefore = false
        isSimulatedBlackout = false
        lastStableHeadingDeg = 0.0
        hasInitialHeading = false
        isCurrentlyStationary = true
        consecutiveMotionFrames = 0
        stationaryAnchorPos[0] = 0.0
        stationaryAnchorPos[1] = 0.0
        _output.value = EngineOutput()
    }

    fun close() { kinoNet.close() }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun handleCalibration(filtAccel: DoubleArray, filtGyro: DoubleArray) {
        aligner.processSample(filtAccel, filtGyro)
        if (aligner.isCalibrated && !isCalibrated) {
            isCalibrated = true
        }
    }

    private fun calculateHeading(speedPred: Double, isStopped: Boolean): Double {
        // When stationary or creeping (< 2.5 km/h), lock heading completely!
        if (isStopped || (speedPred * 3.6) < 2.5) {
            return lastStableHeadingDeg
        }

        val targetHeading = if (latestGpsBearingDeg != null && (speedPred * 3.6) >= 8.0) {
            latestGpsBearingDeg!!
        } else {
            // Geographic bearing from EKF rotation matrix: body X forward mapped to ENU (East, North)
            var deg = Math.toDegrees(atan2(ekf.R[0 * 3 + 0], ekf.R[1 * 3 + 0]))
            if (deg.isNaN()) lastStableHeadingDeg else {
                if (deg < 0.0) deg += 360.0
                deg
            }
        }

        // Smooth angle interpolation avoiding wrap-around jumps at 0°/360°
        var diff = (targetHeading - lastStableHeadingDeg) % 360.0
        if (diff < -180.0) diff += 360.0
        if (diff > 180.0) diff -= 360.0

        // Only update if difference exceeds deadband (0.5 degree)
        if (abs(diff) > 0.5) {
            lastStableHeadingDeg = (lastStableHeadingDeg + 0.12 * diff) % 360.0
            if (lastStableHeadingDeg < 0.0) lastStableHeadingDeg += 360.0
        }
        return lastStableHeadingDeg
    }

    private fun emitOutput(
        speedPred: Double,
        speedVar: Double,
        stopped: Boolean,
        rough: Boolean,
        heading: Double,
        naiveErrorM: Double
    ) {
        val estLat = if (hasRealGnssFix && !lat0.isNaN()) lat0 + ekf.pos[1] / METERS_PER_DEG_LAT else 0.0
        val estLon = if (hasRealGnssFix && !lon0.isNaN()) lon0 + ekf.pos[0] / metersPerDegLon else 0.0

        val mode = ekf.mode
        val uncertainty = run {
            // Uncertainty from position covariance diagonal
            val covX = sqrt(maxOf(0.0, ekf.cov[6 * 15 + 6]))
            val covY = sqrt(maxOf(0.0, ekf.cov[7 * 15 + 7]))
            sqrt(covX * covX + covY * covY)
        }

        _output.value = EngineOutput(
            posX = ekf.pos[0],
            posY = ekf.pos[1],
            estLat = estLat,
            estLon = estLon,
            naiveX = naiveX,
            naiveY = naiveY,
            naiveErrorM = naiveErrorM,
            speedMps = maxOf(0.0, speedPred),
            speedKmh = maxOf(0.0, speedPred) * 3.6,
            headingDeg = heading,
            aiSpeedMps = maxOf(0.0, speedPred),
            aiUncertainty = speedVar,
            uncertaintyM = uncertainty.coerceIn(0.5, 200.0),
            mode = mode,
            health = when {
                uncertainty > 50.0 -> "DEGRADED"
                uncertainty > 20.0 -> "ADAPTIVE"
                mode == "DEAD_RECKONING" -> "ADAPTIVE"
                else -> "NORMAL"
            },
            gnssAvailable = ekf.mode == "GNSS+INS",
            isRoughTerrain = rough,
            isStopped = stopped,
            sampleIndex = sampleCount,
            outageDurationSec = outageElapsedSec,
            totalDistanceM = totalTripDistanceM
        )
    }

    // -----------------------------------------------------------------------
    // Causal Butterworth IIR state (Direct-Form II Transposed)
    // Matches the forward-pass of the Python filtfilt chain.
    // Coefficients match [SignalFilter] (4th order, Wn=0.6).
    // -----------------------------------------------------------------------
    private inner class IirState {
        private val B = doubleArrayOf(0.20657208, 0.82628831, 1.23943247, 0.82628831, 0.20657208)
        private val A = doubleArrayOf(1.0, 0.00000000, 0.48602992, 0.00000000, 0.01766468)
        private val w = DoubleArray(5)

        fun process(x: Double): Double {
            val y = B[0] * x + w[0]
            for (j in 1 until 5) {
                val bj = B[j]; val aj = if (j < A.size) A[j] else 0.0
                w[j - 1] = bj * x - aj * y + (if (j < 4) w[j] else 0.0)
            }
            return y
        }

        fun reset() { w.fill(0.0) }
    }
}

// -----------------------------------------------------------------------
// Data class for engine output (replaces the demo DemoSample for live mode)
// -----------------------------------------------------------------------

/**
 * One timestep of GeoReckon engine output.
 *
 * Maps to the same fields consumed by [NavigationScreen] / [TrajectoryView],
 * mirroring [DemoSample] so the UI needs zero changes.
 */
data class EngineOutput(
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val estLat: Double = 0.0,
    val estLon: Double = 0.0,
    val naiveX: Double = 0.0,
    val naiveY: Double = 0.0,
    val naiveErrorM: Double = 0.0,
    val speedMps: Double = 0.0,
    val speedKmh: Double = 0.0,
    val headingDeg: Double = 0.0,
    val aiSpeedMps: Double = 0.0,
    val aiUncertainty: Double = 0.0,
    val uncertaintyM: Double = 1.8,
    val mode: String = "INITIALIZING",       // "GNSS+INS" | "DEAD_RECKONING" | "REACQUIRING"
    val health: String = "NORMAL",            // "NORMAL" | "ADAPTIVE" | "DEGRADED"
    val gnssAvailable: Boolean = true,
    val isRoughTerrain: Boolean = false,
    val isStopped: Boolean = false,
    val sampleIndex: Long = 0L,
    val outageDurationSec: Double = 0.0,
    val totalDistanceM: Double = 0.0
)
