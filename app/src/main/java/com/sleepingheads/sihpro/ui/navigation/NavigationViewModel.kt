package com.sleepingheads.sihpro.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepingheads.sihpro.data.model.DemoTripInfo
import com.sleepingheads.sihpro.data.replay.DemoTripRepository
import com.sleepingheads.sihpro.session.DemoPlaybackController
import com.sleepingheads.sihpro.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DemoTripRepository(application)
    val controller = DemoPlaybackController(viewModelScope)
    val playbackState: StateFlow<PlaybackState> = controller.state

    val availableTrips: List<DemoTripInfo> = DemoTripRepository.AVAILABLE_TRIPS

    private val _selectedTrip = MutableStateFlow(availableTrips.first())
    val selectedTrip: StateFlow<DemoTripInfo> = _selectedTrip.asStateFlow()

    init {
        loadTrip(_selectedTrip.value.assetPath)
    }

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

    fun restart() = controller.restart()
    fun seekTo(fraction: Float) = controller.seekTo(fraction)
    fun setSpeedMultiplier(mult: Float) = controller.setSpeedMultiplier(mult)
    fun toggleManualBlackout() = controller.toggleManualBlackout()
}
