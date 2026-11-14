package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.data.AdImageLoader
import com.example.data.OsmTileLoader
import com.example.model.MapSizePreset
import com.example.model.SavedRoutePair
import com.example.model.SavedTrainEntry
import com.example.model.StationDbEntry
import com.example.model.TrackerUiState
import com.example.service.TrainTrackingCoordinator
import kotlinx.coroutines.flow.StateFlow

class TrainTrackerViewModel(application: Application) : AndroidViewModel(application) {
  private val coordinator = TrainTrackingCoordinator.getInstance(application)

  val uiState: StateFlow<TrackerUiState> = coordinator.uiState
  val tileLoader: OsmTileLoader = coordinator.tileLoader
  val adImageLoader: AdImageLoader = coordinator.adImageLoader

  fun setLanguage(languageCode: String) = coordinator.setLanguage(languageCode)

  fun setLocationPermissionState(granted: Boolean) =
    coordinator.setLocationPermissionState(granted)

  fun onLocationPermissionResult(granted: Boolean) =
    coordinator.onLocationPermissionResult(granted)

  fun requestLocationPermissionPrompt() = coordinator.requestLocationPermissionPrompt()

  fun onNotificationPermissionHandled() = coordinator.onNotificationPermissionHandled()

  fun toggleFavoriteCurrentTrain() = coordinator.toggleFavoriteCurrentTrain()

  fun onQuickTrainSelected(entry: SavedTrainEntry) = coordinator.onQuickTrainSelected(entry)

  fun onRecentRouteSelected(pair: SavedRoutePair) = coordinator.onRecentRouteSelected(pair)

  fun onFromInputChanged(value: String) = coordinator.onFromInputChanged(value)

  fun onToInputChanged(value: String) = coordinator.onToInputChanged(value)

  fun onStationSelected(isFrom: Boolean, entry: StationDbEntry) =
    coordinator.onStationSelected(isFrom, entry)

  fun swapStations() = coordinator.swapStations()

  fun onSelectTrainBetween(trainNumber: String, trainName: String? = null) =
    coordinator.onSelectTrainBetween(trainNumber, trainName)

  fun onTrainNoChanged(raw: String) = coordinator.onTrainNoChanged(raw)

  fun startTracking() = coordinator.startTracking()

  fun stopTracking() = coordinator.stopTracking()

  fun onStationNodeClicked(index: Int) = coordinator.onStationNodeClicked(index)

  fun focusStationOnMap(index: Int) = coordinator.focusStationOnMap(index)

  fun toggleStationAlarm(index: Int) = coordinator.toggleStationAlarm(index)

  fun dismissStationAlarm() = coordinator.dismissStationAlarm()

  fun setMapSizePreset(preset: MapSizePreset) = coordinator.setMapSizePreset(preset)

  fun setMapFullscreen(fullscreen: Boolean) = coordinator.setMapFullscreen(fullscreen)

  fun triggerRecenterMap() = coordinator.triggerRecenterMap()

  fun refreshAdsNow() = coordinator.refreshAdsNow()
}
