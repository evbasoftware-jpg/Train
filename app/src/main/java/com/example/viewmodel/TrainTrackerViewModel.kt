package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocationClient
import com.example.data.LocationEvent
import com.example.data.OsmTileLoader
import com.example.data.TrainRepository
import com.example.model.AppLanguage
import com.example.model.BannerType
import com.example.model.GpsSample
import com.example.model.LocatedTrain
import com.example.model.MapSizePreset
import com.example.model.RouteStation
import com.example.model.StationDbEntry
import com.example.model.StatusBanner
import com.example.model.TrackerUiState
import com.example.model.stringsFor
import com.example.util.GeoMath
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TrainTrackerViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val API_MS = 420_000L // 7 minutes
    private const val GPS_MS = 15_000L // 15 seconds
    private const val MAX_ACC = 200f // meters
    private const val MAX_API = 1_200_000L // 20 minutes
  }

  private val repository = TrainRepository()
  private val locationClient = LocationClient(application)
  val tileLoader = OsmTileLoader()

  private val _uiState = MutableStateFlow(TrackerUiState())
  val uiState: StateFlow<TrackerUiState> = _uiState.asStateFlow()

  private var stationDb: List<StationDbEntry> = emptyList()
  private var savedRoute: List<RouteStation> = emptyList()

  private var betweenJob: Job? = null
  private var autoStartDebounceJob: Job? = null
  private var pollingJob: Job? = null
  private var gpsJob: Job? = null
  private var stopTimerJob: Job? = null

  private var lastAutoStartedTrainNo = ""
  private var lastBetweenPair: Pair<String, String>? = null
  private var lastPublishTimeMillis = 0L
  private var startToken = 0

  init {
    viewModelScope.launch {
      val loaded = repository.fetchStationsDb()
      stationDb = loaded
      _uiState.update { state ->
        state.copy(
          stationDbLoaded = loaded.isNotEmpty(),
          fromSuggestions = GeoMath.filterStationSuggestions(state.fromInput, loaded),
          toSuggestions = GeoMath.filterStationSuggestions(state.toInput, loaded),
        )
      }
    }
  }

  private fun currentStrings() = stringsFor(_uiState.value.language)

  fun setLanguage(language: AppLanguage) {
    _uiState.update { it.copy(language = language) }
  }

  fun setLocationPermissionState(granted: Boolean) {
    val wasGranted = _uiState.value.locationPermissionGranted
    _uiState.update {
      it.copy(
        locationPermissionGranted = granted,
        needsLocationPermissionPrompt = false,
      )
    }
    if (granted && _uiState.value.running && gpsJob == null) {
      startGpsCollection()
    } else if (!granted && wasGranted != granted && _uiState.value.running) {
      _uiState.update {
        it.copy(statusBanner = StatusBanner(currentStrings().gpsPermissionDenied, BannerType.BAD))
      }
    }
  }

  fun onLocationPermissionResult(granted: Boolean) {
    _uiState.update {
      it.copy(
        locationPermissionGranted = granted,
        needsLocationPermissionPrompt = false,
      )
    }
    if (granted) {
      if (_uiState.value.running) {
        startGpsCollection()
      }
    } else {
      _uiState.update {
        it.copy(statusBanner = StatusBanner(currentStrings().gpsPermissionDenied, BannerType.BAD))
      }
    }
  }

  fun requestLocationPermissionPrompt() {
    _uiState.update { it.copy(needsLocationPermissionPrompt = true) }
  }

  fun onFromInputChanged(value: String) {
    val suggestions = GeoMath.filterStationSuggestions(value, stationDb)
    _uiState.update { it.copy(fromInput = value, fromSuggestions = suggestions) }
    maybeSearchBetween()
  }

  fun onToInputChanged(value: String) {
    val suggestions = GeoMath.filterStationSuggestions(value, stationDb)
    _uiState.update { it.copy(toInput = value, toSuggestions = suggestions) }
    maybeSearchBetween()
  }

  fun onStationSelected(isFrom: Boolean, entry: StationDbEntry) {
    val formatted = "${entry.name} (${entry.code})"
    _uiState.update { state ->
      if (isFrom) {
        state.copy(fromInput = formatted, fromSuggestions = emptyList())
      } else {
        state.copy(toInput = formatted, toSuggestions = emptyList())
      }
    }
    maybeSearchBetween(force = true)
  }

  fun dismissSuggestions(isFrom: Boolean) {
    _uiState.update { state ->
      if (isFrom) state.copy(fromSuggestions = emptyList()) else state.copy(toSuggestions = emptyList())
    }
  }

  fun swapStations() {
    _uiState.update { state ->
      state.copy(
        fromInput = state.toInput,
        toInput = state.fromInput,
        fromSuggestions = emptyList(),
        toSuggestions = emptyList(),
      )
    }
    lastBetweenPair = null
    maybeSearchBetween(force = true)
  }

  private fun maybeSearchBetween(force: Boolean = false) {
    val state = _uiState.value
    val f = GeoMath.extractStationCode(state.fromInput, stationDb)
    val t = GeoMath.extractStationCode(state.toInput, stationDb)
    if (f.length >= 2 && t.length >= 2 && f != t) {
      if (force || lastBetweenPair != (f to t)) {
        searchTrainsBetween(f, t)
      }
    }
  }

  fun searchTrainsBetween(fromCode: String? = null, toCode: String? = null) {
    val state = _uiState.value
    val f = fromCode ?: GeoMath.extractStationCode(state.fromInput, stationDb)
    val t = toCode ?: GeoMath.extractStationCode(state.toInput, stationDb)
    if (f.isEmpty() || t.isEmpty() || f == t) return

    lastBetweenPair = f to t
    betweenJob?.cancel()
    betweenJob = viewModelScope.launch {
      _uiState.update { it.copy(betweenLoading = true, betweenError = null) }
      try {
        delay(250)
        val trains = repository.fetchTrainsBetween(f, t)
        val err = if (trains.isEmpty()) currentStrings().noTrainsFound(f, t) else null
        _uiState.update {
          it.copy(
            betweenLoading = false,
            betweenTrains = trains,
            betweenError = err,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            betweenLoading = false,
            betweenTrains = emptyList(),
            betweenError = e.message ?: "Error loading trains",
          )
        }
      }
    }
  }

  fun onSelectTrainBetween(trainNumber: String) {
    val clean = trainNumber.filter { it.isDigit() }.take(5)
    lastAutoStartedTrainNo = clean
    _uiState.update { it.copy(trainNoInput = clean) }
    if (clean.length == 5) {
      startTracking()
    }
  }

  fun onTrainNoChanged(raw: String) {
    val digits = raw.filter { it.isDigit() }.take(5)
    _uiState.update { it.copy(trainNoInput = digits) }
    autoStartDebounceJob?.cancel()
    if (digits.length == 5 && digits != lastAutoStartedTrainNo) {
      lastAutoStartedTrainNo = digits
      autoStartDebounceJob = viewModelScope.launch {
        delay(250)
        startTracking()
      }
    } else if (digits.length < 5) {
      lastAutoStartedTrainNo = ""
    }
  }

  fun startTracking() {
    val trainNo = _uiState.value.trainNoInput.trim()
    if (!Regex("""^\d{5}$""").matches(trainNo)) return

    val token = ++startToken
    stopTracking(showBanner = false)
    savedRoute = emptyList()
    lastPublishTimeMillis = 0L

    val s = currentStrings()
    _uiState.update {
      it.copy(
        running = true,
        insideTrain = false,
        trainName = null,
        rawSource = null,
        route = emptyList(),
        locatedTrain = null,
        gpsSamples = emptyList(),
        movementStats = com.example.model.MovementStats(0.0, 0.0),
        verification = null,
        lowAccuracyMeters = null,
        resultBanner = null,
        statusBanner = StatusBanner(s.searchingLiveTrain(trainNo), BannerType.INFO),
      )
    }

    if (_uiState.value.locationPermissionGranted) {
      startGpsCollection()
    } else {
      _uiState.update { it.copy(needsLocationPermissionPrompt = true) }
    }

    viewModelScope.launch {
      try {
        updateTrainLive(trainNo)
        if (token != startToken || !_uiState.value.running) return@launch
        schedulePolling(trainNo, token)
        scheduleApiAutoStop(token)
      } catch (_: Exception) {
        if (token != startToken || !_uiState.value.running) return@launch
        _uiState.update {
          it.copy(statusBanner = StatusBanner(currentStrings().retryingSignal, BannerType.INFO))
        }
        schedulePolling(trainNo, token)
      }
    }
  }

  fun stopTracking(showBanner: Boolean = true) {
    pollingJob?.cancel()
    pollingJob = null
    gpsJob?.cancel()
    gpsJob = null
    stopTimerJob?.cancel()
    stopTimerJob = null

    _uiState.update { state ->
      state.copy(
        running = false,
        insideTrain = false,
        gpsSamples = emptyList(),
        verification = null,
        lowAccuracyMeters = null,
        statusBanner = if (showBanner) {
          StatusBanner(currentStrings().trackingStopped, BannerType.INFO)
        } else {
          state.statusBanner
        },
      )
    }
  }

  private fun scheduleApiAutoStop(token: Int) {
    stopTimerJob?.cancel()
    stopTimerJob = viewModelScope.launch {
      delay(MAX_API)
      if (token == startToken && _uiState.value.running && !_uiState.value.insideTrain) {
        stopTracking(showBanner = false)
        _uiState.update {
          it.copy(statusBanner = StatusBanner(currentStrings().apiTimeoutStopped, BannerType.INFO))
        }
      }
    }
  }

  private fun schedulePolling(trainNo: String, token: Int) {
    pollingJob?.cancel()
    pollingJob = viewModelScope.launch {
      while (_uiState.value.running && token == startToken) {
        val interval = if (_uiState.value.insideTrain) GPS_MS else API_MS
        delay(interval)
        if (!_uiState.value.running || token != startToken) break
        try {
          updateTrainLive(trainNo)
        } catch (_: Exception) {
          // Silent retry on transient network drop
        }
      }
    }
  }

  private suspend fun updateTrainLive(trainNo: String) {
    val previousTrain = _uiState.value.locatedTrain
    val payload = repository.fetchLiveTrain(
      trainNo = trainNo,
      cachedRoute = savedRoute,
      stationDb = stationDb,
    )
    if (payload.route.isNotEmpty()) {
      savedRoute = payload.route
    }
    val located = GeoMath.locateTrain(
      currentLocation = payload.currentLocation,
      route = payload.route,
      fallbackStationLabel = currentStrings().unknownStation,
    )

    _uiState.update { state ->
      val shouldAutoCenter = previousTrain == null
      val nextStatus = if (previousTrain != null && payload.source == "gps" && !state.insideTrain) {
        StatusBanner(currentStrings().otherUserGpsMsg, BannerType.OK)
      } else {
        state.statusBanner
      }
      state.copy(
        trainName = payload.trainName ?: state.trainName,
        rawSource = payload.source,
        route = payload.route,
        locatedTrain = located,
        statusBanner = nextStatus,
        recenterSignal = if (shouldAutoCenter) state.recenterSignal + 1 else state.recenterSignal,
      )
    }

    verifyAndSync()
  }

  private fun startGpsCollection() {
    gpsJob?.cancel()
    gpsJob = viewModelScope.launch {
      locationClient.observeLocationUpdates().collect { event ->
        if (!_uiState.value.running) return@collect
        when (event) {
          is LocationEvent.Fix -> {
            val s = event.sample
            if (s.accuracy > MAX_ACC) {
              _uiState.update { it.copy(lowAccuracyMeters = s.accuracy.toInt()) }
              return@collect
            }
            _uiState.update { state ->
              val updated = (state.gpsSamples + s).takeLast(20)
              val movement = GeoMath.computeMovement(updated)
              state.copy(
                gpsSamples = updated,
                movementStats = movement,
                lowAccuracyMeters = null,
              )
            }
            verifyAndSync()
          }
          LocationEvent.SlowSignalFallback -> {
            _uiState.update {
              it.copy(
                statusBanner = StatusBanner(currentStrings().gpsSlowNetworkFallback, BannerType.INFO),
              )
            }
          }
          LocationEvent.UnavailableOrDisabled -> {
            _uiState.update {
              it.copy(
                statusBanner = StatusBanner(currentStrings().gpsDisabledOrUnavailable, BannerType.BAD),
              )
            }
          }
        }
      }
    }
  }

  private fun verifyAndSync() {
    val state = _uiState.value
    val train = state.locatedTrain ?: return
    val latestSample = state.gpsSamples.lastOrNull() ?: return
    if (!state.running) return

    val movement = state.movementStats
    val metrics = GeoMath.computeVerification(latestSample, movement, train)
    val strings = currentStrings()

    if (metrics.recognizedInside) {
      val wasInside = state.insideTrain
      if (!wasInside) {
        stopTimerJob?.cancel()
        stopTimerJob = null
        // Restart polling at GPS_MS cadence
        schedulePolling(state.trainNoInput.trim(), startToken)
      }

      val route = state.route
      val nearestIdx = GeoMath.findNearestRouteStationIndex(train.lat, train.lon, route, train.idx)
      val snappedTrain: LocatedTrain = if (route.isNotEmpty() && nearestIdx in route.indices) {
        val curName = route[nearestIdx].stationName.ifBlank { train.name }
        val nxtName = route.getOrNull(nearestIdx + 1)?.stationName ?: ""
        train.copy(idx = nearestIdx, name = curName, next = nxtName)
      } else {
        train
      }

      // Throttled GPS publish (every 15 seconds)
      val now = System.currentTimeMillis()
      if (now - lastPublishTimeMillis >= GPS_MS) {
        lastPublishTimeMillis = now
        val trainNo = state.trainNoInput.trim()
        val trainName = state.trainName
        viewModelScope.launch {
          repository.publishGps(trainNo, trainName, latestSample)
        }
      }

      _uiState.update {
        it.copy(
          insideTrain = true,
          locatedTrain = snappedTrain,
          verification = metrics,
          statusBanner = if (!wasInside) {
            StatusBanner(strings.gpsVerifiedInsideMsg, BannerType.OK)
          } else {
            it.statusBanner
          },
          resultBanner = StatusBanner(strings.trainLocSetFromUserGps, BannerType.OK),
        )
      }
    } else {
      val crossM = String.format(Locale.US, "%.1f", metrics.crossTrackKm * 1000.0)
      val alongM = String.format(Locale.US, "%.1f", metrics.alongTrackKm * 1000.0)
      _uiState.update {
        it.copy(
          verification = metrics,
          resultBanner = StatusBanner(strings.offsetResult(crossM, alongM), BannerType.INFO),
        )
      }
    }
  }

  fun setMapSizePreset(preset: MapSizePreset) {
    _uiState.update { it.copy(mapSizePreset = preset) }
  }

  fun setMapFullscreen(fullscreen: Boolean) {
    _uiState.update { it.copy(mapFullscreen = fullscreen) }
  }

  fun triggerRecenterMap() {
    _uiState.update { it.copy(recenterSignal = it.recenterSignal + 1) }
  }

  override fun onCleared() {
    super.onCleared()
    stopTracking(showBanner = false)
  }
}
