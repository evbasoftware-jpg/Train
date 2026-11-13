package com.example.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocationClient
import com.example.data.LocationEvent
import com.example.data.OsmTileLoader
import com.example.data.TrackerPreferences
import com.example.data.TrainRepository
import com.example.model.AppLanguage
import com.example.model.BannerType
import com.example.model.FocusedMapStation
import com.example.model.LocatedTrain
import com.example.model.MapSizePreset
import com.example.model.MovementStats
import com.example.model.RouteStation
import com.example.model.SavedRoutePair
import com.example.model.SavedTrainEntry
import com.example.model.StationAlarmState
import com.example.model.StationDbEntry
import com.example.model.StatusBanner
import com.example.model.TrackerTab
import com.example.model.TrackerUiState
import com.example.model.stringsFor
import com.example.service.TrainNotificationHelper
import com.example.service.TrainTrackingService
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
    private const val ALARM_RADIUS_KM = 3.0
  }

  private val appContext = application.applicationContext
  private val repository = TrainRepository(application.cacheDir)
  private val preferences = TrackerPreferences(application.applicationContext)
  private val locationClient = LocationClient(application)
  val tileLoader = OsmTileLoader(application.cacheDir)

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
    TrainTrackingService.onStopRequestedFromNotification = {
      stopTracking(showBanner = true)
    }

    viewModelScope.launch {
      preferences.prefsFlow.collect { saved ->
        _uiState.update { state ->
          state.copy(
            language = saved.language,
            favoriteTrains = saved.favoriteTrains,
            recentTrains = saved.recentTrains,
            recentRoutes = saved.recentRoutes,
            backgroundTrackingEnabled = saved.backgroundTrackingEnabled,
          )
        }
      }
    }

    viewModelScope.launch {
      // Load cached station DB immediately for instant offline search
      val cached = repository.loadCachedStationsDb()
      if (cached.isNotEmpty()) {
        stationDb = cached
        _uiState.update { state ->
          state.copy(
            stationDbLoaded = true,
            fromSuggestions = GeoMath.filterStationSuggestions(state.fromInput, cached),
            toSuggestions = GeoMath.filterStationSuggestions(state.toInput, cached),
          )
        }
      }
      // Refresh if stale or missing
      val fresh = repository.fetchStationsDb(forceRefreshIfStale = true)
      if (fresh.isNotEmpty()) {
        stationDb = fresh
        _uiState.update { state ->
          state.copy(
            stationDbLoaded = true,
            fromSuggestions = GeoMath.filterStationSuggestions(state.fromInput, fresh),
            toSuggestions = GeoMath.filterStationSuggestions(state.toInput, fresh),
          )
        }
      }
    }
  }

  private fun currentStrings() = stringsFor(_uiState.value.language)

  fun setLanguage(language: AppLanguage) {
    _uiState.update { it.copy(language = language) }
    viewModelScope.launch {
      preferences.saveLanguage(language)
    }
  }

  fun setActiveTab(tab: TrackerTab) {
    _uiState.update { it.copy(activeTab = tab) }
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

  fun onNotificationPermissionHandled() {
    _uiState.update { it.copy(needsNotificationPermissionPrompt = false) }
  }

  fun setBackgroundTrackingEnabled(enabled: Boolean) {
    _uiState.update { state ->
      state.copy(
        backgroundTrackingEnabled = enabled,
        needsNotificationPermissionPrompt =
          enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
      )
    }
    viewModelScope.launch {
      preferences.saveBackgroundTracking(enabled)
    }
    if (enabled && _uiState.value.running) {
      updateForegroundServiceNotification()
    } else if (!enabled) {
      TrainTrackingService.stop(appContext)
    }
  }

  fun toggleFavoriteCurrentTrain() {
    val num = _uiState.value.trainNoInput.trim()
    if (num.length != 5) return
    val name = _uiState.value.trainName ?: ""
    viewModelScope.launch {
      preferences.toggleFavoriteTrain(SavedTrainEntry(num, name))
    }
  }

  fun onQuickTrainSelected(entry: SavedTrainEntry) {
    onSelectTrainBetween(entry.trainNumber)
  }

  fun onRecentRouteSelected(pair: SavedRoutePair) {
    _uiState.update {
      it.copy(
        fromInput = pair.fromInput,
        toInput = pair.toInput,
        fromSuggestions = emptyList(),
        toSuggestions = emptyList(),
      )
    }
    maybeSearchBetween(force = true)
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
    val fromLabel = state.fromInput.trim().ifBlank { f }
    val toLabel = state.toInput.trim().ifBlank { t }

    betweenJob?.cancel()
    betweenJob = viewModelScope.launch {
      _uiState.update { it.copy(betweenLoading = true, betweenError = null) }
      try {
        delay(250)
        val trains = repository.fetchTrainsBetween(f, t)
        if (trains.isNotEmpty()) {
          preferences.addRecentRoute(SavedRoutePair(fromLabel, toLabel))
        }
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
        selectedStationIndex = null,
        stationAlarm = null,
        alarmAlertMessage = null,
        focusedMapStation = null,
        gpsSamples = emptyList(),
        movementStats = MovementStats(0.0, 0.0),
        verification = null,
        lowAccuracyMeters = null,
        resultBanner = null,
        statusBanner = StatusBanner(s.searchingLiveTrain(trainNo), BannerType.INFO),
      )
    }

    viewModelScope.launch {
      preferences.addRecentTrain(SavedTrainEntry(trainNo, ""))
    }

    if (_uiState.value.locationPermissionGranted) {
      startGpsCollection()
    } else {
      _uiState.update { it.copy(needsLocationPermissionPrompt = true) }
    }

    if (_uiState.value.backgroundTrackingEnabled) {
      updateForegroundServiceNotification()
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

    TrainTrackingService.stop(appContext)

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

  fun onStationNodeClicked(index: Int) {
    _uiState.update { state ->
      val nextIdx = if (state.selectedStationIndex == index) null else index
      state.copy(selectedStationIndex = nextIdx)
    }
  }

  fun focusStationOnMap(index: Int) {
    val st = _uiState.value.route.getOrNull(index) ?: return
    val lat = st.lat ?: return
    val lon = st.lon ?: return
    val name = st.stationName.ifBlank { st.stationCode }
    _uiState.update { state ->
      state.copy(
        selectedStationIndex = index,
        focusedMapStation = FocusedMapStation(
          stationCode = st.stationCode,
          stationName = name,
          lat = lat,
          lon = lon,
          signal = (state.focusedMapStation?.signal ?: 0) + 1,
        ),
        activeTab = TrackerTab.MAP,
      )
    }
  }

  fun toggleStationAlarm(index: Int) {
    val st = _uiState.value.route.getOrNull(index) ?: return
    val lat = st.lat ?: return
    val lon = st.lon ?: return
    val stName = st.stationName.ifBlank { st.stationCode }
    val currentAlarm = _uiState.value.stationAlarm

    val clearing = currentAlarm != null &&
      currentAlarm.stationCode.equals(st.stationCode, ignoreCase = true) &&
      currentAlarm.stationName == stName

    if (clearing) {
      _uiState.update { it.copy(stationAlarm = null, alarmAlertMessage = null) }
    } else {
      val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      _uiState.update {
        it.copy(
          stationAlarm = StationAlarmState(
            stationCode = st.stationCode,
            stationName = stName,
            lat = lat,
            lon = lon,
            triggered = false,
          ),
          alarmAlertMessage = null,
          needsNotificationPermissionPrompt = needsNotif,
        )
      }
      checkStationAlarmProximity()
    }
  }

  fun dismissStationAlarm() {
    _uiState.update { it.copy(stationAlarm = null, alarmAlertMessage = null) }
  }

  private fun checkStationAlarmProximity() {
    val state = _uiState.value
    val alarm = state.stationAlarm ?: return
    if (alarm.triggered) return
    val train = state.locatedTrain
    val user = state.gpsSamples.lastOrNull()

    val trainDistKm = train?.let { GeoMath.haversineKm(it.lat, it.lon, alarm.lat, alarm.lon) }
    val userDistKm = if (state.insideTrain && user != null) {
      GeoMath.haversineKm(user.lat, user.lon, alarm.lat, alarm.lon)
    } else {
      null
    }

    val effectiveDist = listOfNotNull(trainDistKm, userDistKm).minOrNull() ?: return
    if (effectiveDist <= ALARM_RADIUS_KM) {
      val strings = currentStrings()
      val trainNo = state.trainNoInput.trim()
      val alertMsg = strings.alarmTriggeredMsg(trainNo, alarm.stationName)
      TrainNotificationHelper.triggerStationAlarm(
        context = appContext,
        title = strings.appTitle,
        message = alertMsg,
      )
      _uiState.update {
        it.copy(
          stationAlarm = alarm.copy(triggered = true),
          alarmAlertMessage = alertMsg,
        )
      }
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

    if (!payload.trainName.isNullOrBlank()) {
      preferences.addRecentTrain(SavedTrainEntry(trainNo, payload.trainName))
    }

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
    checkStationAlarmProximity()
    updateForegroundServiceNotification()
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
            checkStationAlarmProximity()
            updateForegroundServiceNotification()
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

  private fun updateForegroundServiceNotification() {
    val state = _uiState.value
    if (!state.running || !state.backgroundTrackingEnabled) return
    val trainNo = state.trainNoInput.trim()
    val name = state.trainName?.let { "$trainNo • $it" } ?: "🚆 $trainNo"
    val loc = state.locatedTrain
    val content = if (loc != null) {
      val speed = if (state.insideTrain && state.movementStats.speedKmh > 0) {
        state.movementStats.speedKmh
      } else {
        loc.speedKmh
      }
      val nextText = if (loc.next.isNotBlank()) " → ${loc.next}" else ""
      "${loc.name}$nextText • ${String.format(Locale.US, "%.0f", speed)} km/h"
    } else {
      currentStrings().searchingTrainInfo
    }
    TrainTrackingService.startOrUpdate(appContext, name, content)
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
    TrainTrackingService.onStopRequestedFromNotification = null
    stopTracking(showBanner = false)
  }
}
