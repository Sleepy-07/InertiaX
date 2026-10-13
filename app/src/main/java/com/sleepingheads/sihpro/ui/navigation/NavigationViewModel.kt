package com.sleepingheads.sihpro.ui.navigation

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.DemoTripInfo
import com.sleepingheads.sihpro.data.replay.DemoTripRepository
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

    // ── Online AWS Cloud & WebSocket Pipeline ──────────────────────────────────
    private val _isLiveCloudMode = MutableStateFlow(false)
    val isLiveCloudMode: StateFlow<Boolean> = _isLiveCloudMode.asStateFlow()

    private val _selectedBackend = MutableStateFlow(BackendEnvironment.AWS_EC2_CLOUD)
    val selectedBackend: StateFlow<BackendEnvironment> = _selectedBackend.asStateFlow()

    private var socketClient = GeoReckonSocketIOClient(GeoReckonConfig(environment = _selectedBackend.value))
    private var livePipeline = LiveSensorPipeline(app, socketClient, viewModelScope)

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

            synchronized(liveSampleHistory) {
                liveSampleHistory.add(sample)
                if (liveSampleHistory.size > 800) {
                    liveSampleHistory.removeAt(0)
                }
                if (_isLiveCloudMode.value) {
                    controller.feedLiveSample(sample, liveSampleHistory.toList())
                }
            }
        }

        socketClient.onError = { err ->
            Log.e(TAG, "Socket.IO Error [${err.code}]: ${err.message}")
        }
    }

    /**
     * Activates Real-Time Online AWS Cloud Mode.
     * Connects to the AWS EC2 hosted KinoNet model over WebSocket and streams live 10 Hz telemetry.
     */
    fun startLiveCloudMode() {
        _isLiveCloudMode.value = true
        liveSampleHistory.clear()
        controller.initLiveCloudSession(
            title = "AWS EC2 • ${_selectedBackend.value.title}"
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
        pingJob?.cancel()
        livePipeline.stop()
        socketClient.disconnect()
        selectTrip(trip)
    }

    /**
     * Simulates a GNSS blackout / outage on the live AWS server.
     * If already in blackout, sends GPS restore to the server.
     */
    fun toggleCloudBlackout() {
        if (!_isLiveCloudMode.value) {
            controller.toggleManualBlackout()
            return
        }

        if (livePipeline.isDeadReckoningActive) {
            // Restore GNSS fix
            val dummyLoc = Location("gps").apply {
                latitude = lastPosition.value?.lat ?: 12.9715987
                longitude = lastPosition.value?.lon ?: 77.5945627
                altitude = 920.0
                speed = (lastPosition.value?.speedMps ?: 10.0).toFloat()
                bearing = (lastPosition.value?.bearingDeg ?: 45.0).toFloat()
                accuracy = 2.5f
            }
            livePipeline.onGpsRestored(dummyLoc)
        } else {
            // Force Outage -> Trigger calibration on AWS model
            livePipeline.onGpsLost()
        }
    }

    /**
     * Switches the backend environment preset (e.g. AWS EC2 Cloud vs Local Emulator)
     * without requiring the user to type in server URLs manually.
     */
    fun switchBackend(environment: BackendEnvironment) {
        _selectedBackend.value = environment
        if (_isLiveCloudMode.value) {
            socketClient.disconnect()
            livePipeline.stop()
            socketClient = GeoReckonSocketIOClient(GeoReckonConfig(environment = environment))
            livePipeline = LiveSensorPipeline(app, socketClient, viewModelScope)
            setupSocketCallbacks()
            startLiveCloudMode()
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
        if (_isLiveCloudMode.value) {
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
        livePipeline.stop()
        socketClient.disconnect()
    }
}
