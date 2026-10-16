package com.sleepingheads.sihpro.ui.navigation

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.DemoTripInfo
import com.sleepingheads.sihpro.data.replay.DemoTripRepository
import com.sleepingheads.sihpro.engine.GeoReckonEngine
import com.sleepingheads.sihpro.engine.SensorBridge
import com.sleepingheads.sihpro.network.*
import com.sleepingheads.sihpro.session.DemoPlaybackController
import com.sleepingheads.sihpro.session.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NavigationViewModel(private val app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "NavigationViewModel"
    }

    private val repository = DemoTripRepository(app)
    val controller = DemoPlaybackController(viewModelScope)
    val playbackState: StateFlow<PlaybackState> = controller.state

    val availableTrips: List<DemoTripInfo> = DemoTripRepository.AVAILABLE_TRIPS

    private val _selectedTrip = MutableStateFlow(availableTrips.first())
    val selectedTrip: StateFlow<DemoTripInfo> = _selectedTrip.asStateFlow()

    // ── 100% On-Device Offline Engine (KinoNet-R2 ONNX/TFLite) ──────────────────
    private val _isLiveOfflineMode = MutableStateFlow(false)
    val isLiveOfflineMode: StateFlow<Boolean> = _isLiveOfflineMode.asStateFlow()

    val offlineEngine = GeoReckonEngine(app)
    private val offlineSensorBridge = SensorBridge(app, offlineEngine, viewModelScope)
    private var offlineJob: Job? = null

    // ── Online AWS Cloud & WebSocket Pipeline ──────────────────────────────────
    private val prefs = app.getSharedPreferences("sihpro_backend_prefs", android.content.Context.MODE_PRIVATE)

    private val _isLiveCloudMode = MutableStateFlow(false)
    val isLiveCloudMode: StateFlow<Boolean> = _isLiveCloudMode.asStateFlow()

    private val initialEnv = try {
        val savedEnv = prefs.getString("backend_env", BackendEnvironment.AWS_EC2_CLOUD.name)
        BackendEnvironment.valueOf(savedEnv ?: BackendEnvironment.AWS_EC2_CLOUD.name)
    } catch (_: Exception) {
        BackendEnvironment.AWS_EC2_CLOUD
    }
    private val _selectedBackend = MutableStateFlow(initialEnv)
    val selectedBackend: StateFlow<BackendEnvironment> = _selectedBackend.asStateFlow()

    private val _customHost = MutableStateFlow(prefs.getString("custom_host", "3.230.8.186") ?: "3.230.8.186")
    val customHost: StateFlow<String> = _customHost.asStateFlow()

    private val _customPort = MutableStateFlow(prefs.getInt("custom_port", 80))
    val customPort: StateFlow<Int> = _customPort.asStateFlow()

    private val _isSecure = MutableStateFlow(prefs.getBoolean("is_secure", false))
    val isSecure: StateFlow<Boolean> = _isSecure.asStateFlow()

    private fun buildCurrentConfig(): GeoReckonConfig {
        return GeoReckonConfig(
            environment = _selectedBackend.value,
            customHost = _customHost.value.ifBlank { null },
            customPort = _customPort.value,
            isSecure = _isSecure.value
        )
    }

    private val socketClient = GeoReckonSocketIOClient(buildCurrentConfig())
    private val livePipeline = LiveSensorPipeline(app, socketClient, viewModelScope)

    private val _activeServerUrl = MutableStateFlow(socketClient.config.serverUrl)
    val activeServerUrl: StateFlow<String> = _activeServerUrl.asStateFlow()

    val connectionState: StateFlow<GeoReckonConnectionState> = socketClient.connectionState
    val lastRttMs: StateFlow<Double> = socketClient.lastRttMs
    val lastError: StateFlow<ErrorResponse?> = socketClient.lastError
    val lastPosition: StateFlow<PositionResponse?> = socketClient.lastPosition
    val lastCalibration: StateFlow<CalibrateDoneResponse?> = socketClient.lastCalibration

    private val liveSampleHistory = mutableListOf<DemoSample>()
    private var pingJob: Job? = null

    init {
        setupSocketCallbacks()
        loadTrip(_selectedTrip.value.assetPath)
    }

    private fun setupSocketCallbacks() {
        socketClient.onCalibrateDone = { resp ->
            Log.i(TAG, "AWS Calibrate Done: health=${resp.alignmentHealth}, degraded=${resp.degraded}")
            livePipeline.onCalibrateCompleted()
        }

        livePipeline.onLocationUpdate = { loc ->
            if (_isLiveCloudMode.value && !livePipeline.isDeadReckoningActive) {
                val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                val bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
                val sessionOrigin = controller.originCoordinates
                val dLatM = if (sessionOrigin != null) (loc.latitude - sessionOrigin.first) * 111320.0 else 0.0
                val dLonM = if (sessionOrigin != null) (loc.longitude - sessionOrigin.second) * 111320.0 * kotlin.math.cos(Math.toRadians(loc.latitude)) else 0.0
                val sample = DemoSample(
                    timestampMs = System.currentTimeMillis(),
                    trueLat = loc.latitude,
                    trueLon = loc.longitude,
                    trueX = dLonM,
                    trueY = dLatM,
                    gpsLat = loc.latitude,
                    gpsLon = loc.longitude,
                    gnssAvailable = true,
                    estX = dLonM,
                    estY = dLatM,
                    estLat = loc.latitude,
                    estLon = loc.longitude,
                    naiveX = dLonM,
                    naiveY = dLatM,
                    naiveErrorM = 0.0,
                    noAiX = dLonM,
                    noAiY = dLatM,
                    noAiErrorM = 0.0,
                    noNhcX = dLonM,
                    noNhcY = dLatM,
                    noNhcErrorM = 0.0,
                    noMapX = dLonM,
                    noMapY = dLatM,
                    noMapErrorM = 0.0,
                    speedMps = speedMps,
                    speedKmh = speedMps * 3.6,
                    headingDeg = bearingDeg,
                    aiSpeedMps = speedMps,
                    aiUncertainty = 0.05,
                    mode = "GNSS_FIX",
                    health = "NORMAL",
                    uncertaintyM = if (loc.hasAccuracy()) loc.accuracy.toDouble().coerceIn(1.5, 15.0) else 2.5,
                    alongUncertM = 1.8,
                    crossUncertM = 1.4,
                    headingUncertDeg = 1.2,
                    axisRatio = 1.2,
                    errorM = 0.0,
                    accelX = 0.0,
                    accelY = 0.0,
                    accelZ = 9.80665,
                    gyroX = 0.0,
                    gyroY = 0.0,
                    gyroZ = 0.0
                )
                appendLiveSample(sample)
            }
        }

        socketClient.onPosition = { pos ->
            val sample = DemoSample(
                timestampMs = pos.tMs,
                trueLat = pos.lat,
                trueLon = pos.lon,
                trueX = pos.enuXM,
                trueY = pos.enuYM,
                gpsLat = if (pos.mode.equals("DEAD_RECKONING", ignoreCase = true)) null else pos.lat,
                gpsLon = if (pos.mode.equals("DEAD_RECKONING", ignoreCase = true)) null else pos.lon,
                gnssAvailable = !pos.mode.equals("DEAD_RECKONING", ignoreCase = true),
                estX = pos.enuXM,
                estY = pos.enuYM,
                estLat = pos.lat,
                estLon = pos.lon,
                naiveX = pos.enuXM,
                naiveY = pos.enuYM,
                naiveErrorM = pos.drErrorM ?: 0.0,
                noAiX = pos.enuXM,
                noAiY = pos.enuYM,
                noAiErrorM = pos.drErrorM ?: 0.0,
                noNhcX = pos.enuXM,
                noNhcY = pos.enuYM,
                noNhcErrorM = pos.drErrorM ?: 0.0,
                noMapX = pos.enuXM,
                noMapY = pos.enuYM,
                noMapErrorM = pos.drErrorM ?: 0.0,
                speedMps = pos.speedMps,
                speedKmh = pos.speedMps * 3.6,
                headingDeg = pos.bearingDeg,
                aiSpeedMps = pos.kinonetSpeedMps ?: pos.speedMps,
                aiUncertainty = pos.kinonetVar ?: 0.05,
                mode = pos.mode,
                health = if (pos.degraded) "DEGRADED" else "NORMAL",
                uncertaintyM = (1.0 - pos.confidence).coerceAtLeast(0.0) * 12.0 + 1.0,
                alongUncertM = 1.8,
                crossUncertM = 1.4,
                headingUncertDeg = 1.2,
                axisRatio = 1.2,
                errorM = pos.drErrorM ?: 0.0,
                accelX = 0.0,
                accelY = 0.0,
                accelZ = 9.80665,
                gyroX = 0.0,
                gyroY = 0.0,
                gyroZ = 0.0
            )

            if (_isLiveCloudMode.value) {
                appendLiveSample(sample)
            }
        }

        socketClient.onError = { err ->
            Log.e(TAG, "Socket.IO Error [${err.code}]: ${err.message}")
            livePipeline.onCalibrateFailed(err.message)
        }
    }

    private fun appendLiveSample(sample: DemoSample) {
        synchronized(liveSampleHistory) {
            val lastSample = liveSampleHistory.lastOrNull()
            val shouldAddHistory = if (lastSample == null) {
                true
            } else if (lastSample.mode != sample.mode || lastSample.gnssAvailable != sample.gnssAvailable) {
                true
            } else {
                val dLatM = (sample.estLat - lastSample.estLat) * 111320.0
                val dLonM = (sample.estLon - lastSample.estLon) * 111320.0 * kotlin.math.cos(Math.toRadians(sample.estLat))
                val dM = kotlin.math.hypot(dLatM, dLonM)
                val dtMs = sample.timestampMs - lastSample.timestampMs
                if (sample.speedKmh < 1.0) {
                    dM >= 1.0 || dtMs >= 1000L
                } else {
                    dM >= 0.25 || dtMs >= 300L
                }
            }

            if (shouldAddHistory) {
                liveSampleHistory.add(sample)
                if (liveSampleHistory.size > 30000) {
                    liveSampleHistory.removeAt(0)
                }
            }
            controller.feedLiveSample(sample, liveSampleHistory.toList())
        }
    }

    /**
     * Activates 100% On-Device Live Sensor Mode.
     * Captures real phone accelerometer & gyroscope via SensorBridge,
     * executes KinoNet-R2 on-device via ONNX Runtime / TFLite,
     * and dead-reckons with InEKF directly on the phone with ZERO internet or server.
     */
    fun startLiveOfflineMode() {
        _isLiveCloudMode.value = false
        _isLiveOfflineMode.value = true
        liveSampleHistory.clear()
        pingJob?.cancel()
        livePipeline.stop()
        socketClient.disconnect()

        // Clean slate for new live driving session
        offlineEngine.reset()
        val engineName = offlineEngine.activeModelEngine
        controller.initLiveCloudSession(
            title = "100% On-Device Edge • $engineName"
        )
        offlineSensorBridge.start()

        offlineJob?.cancel()
        offlineJob = viewModelScope.launch {
            offlineEngine.output.collect { out ->
                if (!_isLiveOfflineMode.value) return@collect

                // Guard: Do not stream (0.0, 0.0) or NaN coordinates before the initial location fix is established
                if (out.estLat.isNaN() || out.estLon.isNaN() || (out.estLat == 0.0 && out.estLon == 0.0)) return@collect

                val sample = DemoSample(
                    timestampMs = System.currentTimeMillis(),
                    trueLat = out.estLat,
                    trueLon = out.estLon,
                    trueX = out.posX,
                    trueY = out.posY,
                    gpsLat = if (out.gnssAvailable) out.estLat else null,
                    gpsLon = if (out.gnssAvailable) out.estLon else null,
                    gnssAvailable = out.gnssAvailable,
                    estX = out.posX,
                    estY = out.posY,
                    estLat = out.estLat,
                    estLon = out.estLon,
                    naiveX = out.naiveX,
                    naiveY = out.naiveY,
                    naiveErrorM = out.naiveErrorM,
                    noAiX = out.posX,
                    noAiY = out.posY,
                    noAiErrorM = 0.0,
                    noNhcX = out.posX,
                    noNhcY = out.posY,
                    noNhcErrorM = 0.0,
                    noMapX = out.posX,
                    noMapY = out.posY,
                    noMapErrorM = 0.0,
                    speedMps = out.speedMps,
                    speedKmh = out.speedKmh,
                    headingDeg = out.headingDeg,
                    aiSpeedMps = out.aiSpeedMps,
                    aiUncertainty = out.aiUncertainty,
                    mode = out.mode,
                    health = out.health,
                    uncertaintyM = out.uncertaintyM,
                    alongUncertM = 1.8,
                    crossUncertM = 1.4,
                    headingUncertDeg = 1.2,
                    axisRatio = 1.2,
                    errorM = if (out.gnssAvailable) 0.0 else out.uncertaintyM,
                    accelX = 0.0,
                    accelY = 0.0,
                    accelZ = 9.80665,
                    gyroX = 0.0,
                    gyroY = 0.0,
                    gyroZ = 0.0
                )

                appendLiveSample(sample)
            }
        }
    }

    /**
     * Activates Real-Time Online AWS Cloud Mode.
     * Connects to the AWS EC2 hosted KinoNet model over WebSocket and streams live 10 Hz telemetry.
     */
    fun startLiveCloudMode() {
        _isLiveOfflineMode.value = false
        offlineJob?.cancel()
        offlineSensorBridge.stop()

        _isLiveCloudMode.value = true
        liveSampleHistory.clear()
        val initialFix = livePipeline.lastGpsFix
        controller.initLiveCloudSession(
            title = "AWS EC2 • ${_selectedBackend.value.title}",
            initialLat = initialFix?.lat,
            initialLon = initialFix?.lon
        )

        socketClient.connect()
        livePipeline.start()

        // Heartbeat ping loop (every 3 seconds)
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            while (isActive && _isLiveCloudMode.value) {
                if (socketClient.isConnected) {
                    socketClient.sendPing()
                }
                delay(3000L)
            }
        }
    }

    /**
     * Switches back to Offline Deterministic Replay Mode.
     */
    fun startReplayMode(trip: DemoTripInfo = _selectedTrip.value) {
        _isLiveCloudMode.value = false
        _isLiveOfflineMode.value = false
        offlineJob?.cancel()
        offlineSensorBridge.stop()
        pingJob?.cancel()
        livePipeline.stop()
        socketClient.disconnect()
        selectTrip(trip)
    }

    /**
     * Simulates a GNSS blackout / outage on the live AWS server or on-device engine.
     * If already in blackout, sends GPS restore to the server/engine.
     */
    fun toggleCloudBlackout() {
        if (_isLiveOfflineMode.value) {
            offlineEngine.isSimulatedBlackout = !offlineEngine.isSimulatedBlackout
            if (offlineEngine.isSimulatedBlackout) {
                offlineEngine.onGnssLost()
            }
            return
        }

        if (!_isLiveCloudMode.value) {
            controller.toggleManualBlackout()
            return
        }

        if (livePipeline.isDeadReckoningActive) {
            // Restore GNSS fix
            val lastFix = livePipeline.lastGpsFix
            val dummyLoc = Location("gps").apply {
                latitude = lastFix?.lat ?: lastPosition.value?.lat ?: 12.9715987
                longitude = lastFix?.lon ?: lastPosition.value?.lon ?: 77.5945627
                altitude = lastFix?.altM ?: 920.0
                speed = (lastFix?.speedMps ?: lastPosition.value?.speedMps ?: 10.0).toFloat()
                bearing = (lastFix?.bearingDeg ?: lastPosition.value?.bearingDeg ?: 45.0).toFloat()
                accuracy = (lastFix?.accuracyM ?: 2.5).toFloat()
            }
            livePipeline.onGpsRestored(dummyLoc)
        } else {
            // Force Outage -> Trigger calibration on AWS model
            livePipeline.onGpsLost()
        }
    }

    /**
     * Switches the backend environment preset (e.g. AWS EC2 Cloud vs Local Device Reverse)
     * and updates configuration and persistence immediately.
     */
    fun switchBackend(environment: BackendEnvironment) {
        _selectedBackend.value = environment
        prefs.edit().putString("backend_env", environment.name).apply()

        val newConfig = buildCurrentConfig()
        socketClient.updateConfig(newConfig)
        _activeServerUrl.value = newConfig.serverUrl
        Log.i(TAG, "Switched backend to $environment (${newConfig.serverUrl})")

        if (_isLiveCloudMode.value) {
            socketClient.disconnect()
            livePipeline.stop()
            startLiveCloudMode()
        }
    }

    /**
     * Updates custom server host/IP, port, and security (SSL/TLS).
     * Automatically strips extraneous paths (e.g. /socket.io/), query parameters (?EIO=4),
     * and extracts protocol/port if pasted as a full URL.
     * Persists in SharedPreferences and updates the client immediately.
     */
    fun updateCustomEndpoint(rawHost: String, rawPort: Int, rawSecure: Boolean = false) {
        var cleanHost = rawHost.trim()
        var secure = rawSecure

        if (cleanHost.startsWith("https://", ignoreCase = true)) {
            secure = true
            cleanHost = cleanHost.substring(8)
        } else if (cleanHost.startsWith("http://", ignoreCase = true)) {
            secure = false
            cleanHost = cleanHost.substring(7)
        } else if (cleanHost.startsWith("wss://", ignoreCase = true)) {
            secure = true
            cleanHost = cleanHost.substring(6)
        } else if (cleanHost.startsWith("ws://", ignoreCase = true)) {
            secure = false
            cleanHost = cleanHost.substring(5)
        }

        if (cleanHost.contains("?")) {
            cleanHost = cleanHost.substringBefore("?")
        }
        if (cleanHost.contains("/")) {
            cleanHost = cleanHost.substringBefore("/")
        }
        cleanHost = cleanHost.trim()

        var effectivePort = rawPort
        if (cleanHost.contains(":")) {
            val parts = cleanHost.split(":")
            cleanHost = parts[0]
            parts.getOrNull(1)?.toIntOrNull()?.let { effectivePort = it }
        }

        _customHost.value = cleanHost
        _customPort.value = effectivePort
        _isSecure.value = secure
        prefs.edit()
            .putString("custom_host", cleanHost)
            .putInt("custom_port", effectivePort)
            .putBoolean("is_secure", secure)
            .apply()

        val newConfig = buildCurrentConfig()
        socketClient.updateConfig(newConfig)
        _activeServerUrl.value = newConfig.serverUrl
        Log.i(TAG, "Updated custom endpoint to ${newConfig.serverUrl}")

        if (_isLiveCloudMode.value) {
            socketClient.disconnect()
            livePipeline.stop()
            startLiveCloudMode()
        }
    }

    /**
     * Performs a non-disruptive connection ping test to the configured backend.
     */
    fun testConnection(onResult: (Boolean, Double, String) -> Unit) {
        val config = buildCurrentConfig()
        socketClient.testConnection(config, timeoutMs = 4000L, onResult = onResult)
    }

    /**
     * Reconnects to the active AWS/server endpoint if connection was lost or failed.
     */
    fun retryCloudConnection() {
        if (_isLiveCloudMode.value) {
            socketClient.disconnect()
            socketClient.connect()
        }
    }

    // ── Replay Methods ──────────────────────────────────────────────────────────
    fun selectTrip(trip: DemoTripInfo) {
        _selectedTrip.value = trip
        loadTrip(trip.assetPath)
    }

    fun selectTripById(id: String) {
        val trip = availableTrips.find { it.id == id } ?: return
        selectTrip(trip)
    }

    fun loadTrip(assetPath: String) {
        viewModelScope.launch {
            controller.pause()
            repository.loadDemoTrip(assetPath).onSuccess { demoPkg ->
                controller.loadPackage(demoPkg)
                controller.start()
            }
        }
    }

    fun loadDemo() {
        loadTrip(_selectedTrip.value.assetPath)
    }

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            controller.pause()
        } else {
            controller.resume()
        }
    }

    fun restart() {
        if (_isLiveOfflineMode.value) {
            offlineEngine.reset()
            liveSampleHistory.clear()
            controller.initLiveCloudSession("100% On-Device Edge • ${offlineEngine.activeModelEngine}")
        } else if (_isLiveCloudMode.value) {
            livePipeline.reset()
            liveSampleHistory.clear()
            controller.initLiveCloudSession("AWS EC2 • ${_selectedBackend.value.title}")
        } else {
            controller.restart()
        }
    }

    fun seekTo(fraction: Float) = controller.seekTo(fraction)
    fun setSpeedMultiplier(mult: Float) = controller.setSpeedMultiplier(mult)
    fun toggleManualBlackout() = toggleCloudBlackout()

    override fun onCleared() {
        super.onCleared()
        pingJob?.cancel()
        offlineJob?.cancel()
        offlineSensorBridge.stop()
        offlineEngine.close()
        livePipeline.stop()
        socketClient.disconnect()
    }
}
