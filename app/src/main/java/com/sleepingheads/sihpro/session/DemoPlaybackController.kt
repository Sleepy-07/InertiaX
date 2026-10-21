package com.sleepingheads.sihpro.session

import com.sleepingheads.sihpro.data.model.DemoPackage
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.TripMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSampleIndex: Int = 0,
    val currentSample: DemoSample? = null,
    val progress: Float = 0f,
    val elapsedSec: Float = 0f,
    val speedMultiplier: Float = 1.0f,
    val isManualBlackoutForced: Boolean = false,
    val isCompleted: Boolean = false,
    val metadata: TripMetadata? = null,
    val allSamples: List<DemoSample> = emptyList()
)

/**
 * Deterministic real-time playback controller for GeoReckon demonstration.
 * Replays samples at 10 Hz with speed scaling, pause, resume, seek, and manual blackout toggling.
 */
class DemoPlaybackController(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var playbackJob: Job? = null

    fun loadPackage(demoPackage: DemoPackage) {
        playbackJob?.cancel()
        val samples = demoPackage.samples
        _state.update {
            it.copy(
                allSamples = samples,
                metadata = demoPackage.metadata,
                currentSampleIndex = 0,
                currentSample = samples.firstOrNull(),
                progress = 0f,
                elapsedSec = 0f,
                isPlaying = false,
                isCompleted = false
            )
        }
    }

    val originCoordinates: Pair<Double, Double>?
        get() = _state.value.metadata?.let { Pair(it.originLat, it.originLon) }

    fun initLiveCloudSession(
        title: String = "AWS EC2 Live Stream",
        initialLat: Double? = null,
        initialLon: Double? = null
    ) {
        playbackJob?.cancel()
        _state.update {
            it.copy(
                isPlaying = true,
                allSamples = emptyList(),
                currentSampleIndex = 0,
                currentSample = null,
                metadata = TripMetadata(
                    tripId = "aws_live_cloud",
                    title = title,
                    scenarioType = "AWS Cloud Live Dead Reckoning",
                    badge = "AWS-LIVE",
                    description = "Real-time 10 Hz telemetry connected to AWS EC2 pure-Python KinoNet-R2 server",
                    sampleRateHz = 10,
                    durationSec = 0.0,
                    sampleCount = 0,
                    outageStartSec = 0.0,
                    outageEndSec = 0.0,
                    outageDurationSec = 0.0,
                    outageDistanceM = 0.0,
                    endpointErrorM = 0.0,
                    rmsErrorM = 0.0,
                    maxErrorM = 0.0,
                    driftPercent = 0.0,
                    isroTargetPercent = 10.0,
                    isroPassed = true,
                    originLat = initialLat ?: 12.9715987,
                    originLon = initialLon ?: 77.5945627,
                    modelVersion = "KinoNet-R2 (AWS Cloud)",
                    dataType = "Live Sensor WebSocket Stream"
                ),
                progress = 0f,
                elapsedSec = 0f,
                isCompleted = false
            )
        }
    }

    fun initLiveOfflineSession(
        title: String = "100% On-Device Neural DR",
        initialLat: Double? = null,
        initialLon: Double? = null
    ) {
        playbackJob?.cancel()
        _state.update {
            it.copy(
                isPlaying = true,
                allSamples = emptyList(),
                currentSampleIndex = 0,
                currentSample = null,
                metadata = TripMetadata(
                    tripId = "live_offline_dr",
                    title = title,
                    scenarioType = "Real-Time On-Device Dead Reckoning",
                    badge = "REALTIME AI",
                    description = "100% Real-time hardware IMU & Invariant EKF running directly on device",
                    sampleRateHz = 10,
                    durationSec = 0.0,
                    sampleCount = 0,
                    outageStartSec = 0.0,
                    outageEndSec = 0.0,
                    outageDurationSec = 0.0,
                    outageDistanceM = 0.0,
                    endpointErrorM = 0.0,
                    rmsErrorM = 0.0,
                    maxErrorM = 0.0,
                    driftPercent = 0.0,
                    isroTargetPercent = 10.0,
                    isroPassed = true,
                    originLat = initialLat ?: 12.9715987,
                    originLon = initialLon ?: 77.5945627,
                    modelVersion = "KinoNet-R2 (On-Device ONNX)",
                    dataType = "Real-Time Sensor Telemetry"
                ),
                progress = 0f,
                elapsedSec = 0f,
                isCompleted = false
            )
        }
    }

    val isLiveSession: Boolean
        get() = _state.value.metadata?.tripId == "aws_live_cloud" || _state.value.metadata?.tripId == "live_offline_dr"

    fun feedLiveSample(sample: DemoSample, history: List<DemoSample>) {
        _state.update {
            val isDefaultBangalore = (it.metadata?.originLat ?: 0.0) == 12.9715987 && (it.metadata?.originLon ?: 0.0) == 77.5945627
            val updatedMetadata = if (history.size <= 1 || isDefaultBangalore) {
                it.metadata?.copy(
                    originLat = sample.trueLat,
                    originLon = sample.trueLon,
                    sampleCount = history.size
                )
            } else {
                it.metadata?.copy(sampleCount = history.size)
            }

            it.copy(
                isPlaying = true,
                currentSample = sample,
                allSamples = history,
                currentSampleIndex = (history.size - 1).coerceAtLeast(0),
                elapsedSec = history.size * 0.1f,
                metadata = updatedMetadata
            )
        }
    }

    fun start() {
        if (isLiveSession) return // Live sensor navigation runs in real-time without simulation loops
        val samples = _state.value.allSamples
        if (samples.isEmpty()) return
        if (playbackJob?.isActive == true && _state.value.isPlaying) return
        if (_state.value.isCompleted) {
            seekTo(0f)
        }
        _state.update { it.copy(isPlaying = true) }
        startLoop()
    }

    fun pause() {
        _state.update { it.copy(isPlaying = false) }
        playbackJob?.cancel()
    }

    fun resume() {
        start()
    }

    fun restart() {
        pause()
        seekTo(0f)
        start()
    }

    fun seekTo(fraction: Float) {
        val samples = _state.value.allSamples
        if (samples.isEmpty()) return
        val targetIdx = (fraction * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
        val sample = samples[targetIdx]
        _state.update {
            it.copy(
                currentSampleIndex = targetIdx,
                currentSample = sample,
                progress = targetIdx.toFloat() / (samples.size - 1),
                elapsedSec = sample.timestampMs / 1000f,
                isCompleted = targetIdx >= samples.size - 1
            )
        }
    }

    fun setSpeedMultiplier(multiplier: Float) {
        _state.update { it.copy(speedMultiplier = multiplier) }
        if (_state.value.isPlaying) {
            playbackJob?.cancel()
            startLoop()
        }
    }

    fun toggleManualBlackout() {
        _state.update { it.copy(isManualBlackoutForced = !it.isManualBlackoutForced) }
    }

    private fun startLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.Default) {
            val baseIntervalMs = 100L // 10 Hz sampling rate
            while (isActive && _state.value.isPlaying) {
                val currentIdx = _state.value.currentSampleIndex
                val samples = _state.value.allSamples
                if (samples.isEmpty()) {
                    _state.update { it.copy(isPlaying = false) }
                    break
                }

                if (currentIdx >= samples.size - 1) {
                    _state.update { it.copy(isPlaying = false, isCompleted = true) }
                    break
                }

                val nextIdx = currentIdx + 1
                val sample = samples[nextIdx]

                // Modify sample if manual blackout is forced
                val effectiveSample = if (_state.value.isManualBlackoutForced && sample.gnssAvailable) {
                    sample.copy(
                        gnssAvailable = false,
                        gpsLat = null,
                        gpsLon = null,
                        mode = "DEAD_RECKONING",
                        health = "ADAPTIVE",
                        uncertaintyM = minOf(15.0, sample.uncertaintyM + 4.0)
                    )
                } else {
                    sample
                }

                _state.update {
                    it.copy(
                        currentSampleIndex = nextIdx,
                        currentSample = effectiveSample,
                        progress = nextIdx.toFloat() / (samples.size - 1),
                        elapsedSec = effectiveSample.timestampMs / 1000f
                    )
                }

                val delayMs = (baseIntervalMs / _state.value.speedMultiplier).toLong().coerceAtLeast(10L)
                delay(delayMs)
            }
        }
    }
}
