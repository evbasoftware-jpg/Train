package com.example.service

import android.content.Context
import android.os.Build
import com.example.data.LocationClient
import com.example.data.LocationEvent
import com.example.data.OsmTileLoader
import com.example.data.TrackerPreferences
import com.example.data.TrainRepository
import com.example.data.TranslationRepository
import com.example.model.BannerType
import com.example.model.FocusedMapStation
import com.example.model.GpsSample
import com.example.model.LocatedTrain
import com.example.model.MapSizePreset
import com.example.model.MovementStats
import com.example.model.RouteStation
import com.example.model.SavedRoutePair
import com.example.model.SavedTrainEntry
import com.example.model.StationAlarmState
import com.example.model.StationDbEntry
import com.example.model.StatusBanner
import com.example.model.TrackerUiState
import com.example.model.stringsForCode
import com.example.util.GeoMath
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TrainTrackingCoordinator private constructor(private val appContext: Context) {
  companion object {
    private const val API_MS = 420_000L // 7 minutes
    private const val GPS_MS = 15_000L // 15 seconds
    private const val MAX_ACC = 200f // meters
    private const val MAX_API = 1_200_000L // 20 minutes
    private const val ALARM_RADIUS_KM = 3.0

    @Volatile
    private var instance: TrainTrackingCoordinator? = null

    fun getInstance(context: Context): TrainTrackingCoordinator =
      instance ?: synchronized(this) {
        instance ?: TrainTrackingCoordinator(context.applicationContext).also { instance = it }
      }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val repository = TrainRepository(appContext.cacheDir)
  private val preferences = TrackerPreferences(appContext)
  private val translationRepository = TranslationRepository(appContext.cacheDir)
  private val locationClient = LocationClient(appContext)
  val tileLoader = OsmTileLoader(appContext.cacheDir)

  private val _uiState = MutableStateFlow(TrackerUiState())
  val uiState: StateFlow<TrackerUiState> = _uiState.asStateFlow()

  private var stationDb: List<StationDbEntry> = emptyList()
  private var savedRoute: List<RouteStation> = emptyList()
  private val gpsHistory = ArrayList<GpsSample>(24)

  private var betweenJob: Job? = null
  private var autoStartDebounceJob: Job? = null
  private var pollingJob: Job? = null
  private var gpsJob: Job? = null
  private var stopTimerJob: Job? = null
  private var languageJob: Job? = null

  private var lastAutoStartedTrainNo = ""
  private var lastBetweenPair: Pair<String, String>? = null
  private var lastPublishTimeMillis = 0L
  private var startToken = 0
  private var loadedLanguageCode = "bn"

  init {
    TrainTrackingService.onStopRequestedFromNotification = {
      stopTracking(showBanner = true)
    }

    scope.launch {
      preferences.prefsFlow.collect { saved ->
        _uiState.update { state ->
          state.copy(
            favoriteTrains = saved.favoriteTrains,
            recentTrains = saved.recentTrains,
            recentRoutes = saved.recentRoutes,
            backgroundTrackingEnabled = saved.backgroundTrackingEnabled,
          )
        }
        if (saved.languageCode != loadedLanguageCode) {
          applyLanguage(saved.languageCode, persist = false)
        }
      }
    }

    scope.launch {
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

  private fun currentStrings() = _uiState.value.strings

  fun setLanguage(languageCode: String) {
    applyLanguage(languageCode, persist = true)
  }

  private fun applyLanguage(languageCode: String, persist: Boolean) {
    val cleanCode = languageCode.ifBlank { "bn" }
    loadedLanguageCode = cleanCode
    languageJob?.cancel()

    val isBuiltIn = cleanCode == "bn" || cleanCode == "en" || cleanCode == "hi"
    _uiState.update { state ->
      state.copy(
        languageCode = cleanCode,
        isTranslatingLanguage = !isBuiltIn,
        strings = if (isBuiltIn) stringsForCode(cleanCode) else state.strings,
      )
    }

    languageJob = scope.launch {
      if (persist) {
        preferences.saveLanguageCode(cleanCode)
      }
      val resolved = translationRepository.getStringsForLanguage(cleanCode)
      _uiState.update { state ->
        if (state.languageCode == cleanCode) {
          state.copy(strings = resolved, isTranslatingLanguage = false)
        } else {
          state
        }
      }
      if (_uiState.value.running && _uiState.value.backgroundTrackingEnabled) {
        updateForegroundServiceNotification()
      }
    }
  }

  fun setLocationPermissionState(granted: Boolean) {
    val wasGranted = _uiState.value.locationPermissionGranted
    _uiState.update {
      it.copy(
        locationPermissionGranted = granted,
        needsLocationPermissionPrompt = false,
      )
    }
    if (granted && _uiState.value.running) {
      if (gpsJob == null) startGpsCollection()
      if (_uiState.value.backgroundTrackingEnabled) updateForegroundServiceNotification()
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
        if (_uiState.value.backgroundTrackingEnabled) {
          updateForegroundServiceNotification()
        }
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
    if (_uiState.value.running && _uiState.value.backgroundTrackingEnabled) {
      updateForegroundServiceNotification()
    }
  }

  fun setBackgroundTrackingEnabled(enabled: Boolean) {
    val hasLoc = TrainTrackingService.hasLocationPermission(appContext)
    _uiState.update { state ->
      state.copy(
        backgroundTrackingEnabled = enabled,
        needsNotificationPermissionPrompt =
          enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        needsLocationPermissionPrompt =
          if (enabled && !hasLoc) true else state.needsLocationPermissionPrompt,
      )
    }
    scope.launch {
      preferences.saveBackgroundTracking(enabled)
    }
    if (enabled && _uiState.value.running && hasLoc) {
      updateForegroundServiceNotification()
    } else if (!enabled) {
      TrainTrackingService.stop(appContext)
    }
  }

  fun toggleFavoriteCurrentTrain() {
    val num = _uiState.value.trainNoInput.trim()
    if (!Regex("""^\d{4,5}$""").matches(num)) return
    val name = _uiState.value.trainName ?: ""
    scope.launch {
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
    val suggestions = GeoMath.filterStationSuggestions(value, stationDb, limit = 8)
    _uiState.update { it.copy(fromInput = value, fromSuggestions = suggestions) }
    maybeSearchBetween()
  }

  fun onToInputChanged(value: String) {
    val suggestions = GeoMath.filterStationSuggestions(value, stationDb, limit = 8)
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
    betweenJob = scope.launch {
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
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
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
    if (clean.isEmpty()) return
    autoStartDebounceJob?.cancel()
    lastAutoStartedTrainNo = clean
    _uiState.update {
      it.copy(
        trainNoInput = clean,
        fromSuggestions = emptyList(),
        toSuggestions = emptyList(),
      )
    }
    if (clean.length >= 4) {
      startTracking()
    }
  }

  fun onTrainNoChanged(raw: String) {
    val digits = raw.filter { it.isDigit() }.take(5)
    _uiState.update { it.copy(trainNoInput = digits) }
    autoStartDebounceJob?.cancel()
    if (digits.length == 5 && digits != lastAutoStartedTrainNo) {
      lastAutoStartedTrainNo = digits
      autoStartDebounceJob = scope.launch {
        delay(250)
        startTracking()
      }
    } else if (digits.length < 5) {
      lastAutoStartedTrainNo = ""
    }
  }

  fun startTracking() {
    val trainNo = _uiState.value.trainNoInput.trim()
    if (!Regex("""^\d{4,5}$""").matches(trainNo)) return

    val token = ++startToken
    cancelTrackingJobs()
    savedRoute = emptyList()
    gpsHistory.clear()
    lastPublishTimeMillis = 0L

    val s = currentStrings()
    val hasLoc = TrainTrackingService.hasLocationPermission(appContext)
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
        latestUserGps = null,
        movementStats = MovementStats(0.0, 0.0),
        verification = null,
        lowAccuracyMeters = null,
        resultBanner = null,
        statusBanner = StatusBanner(s.searchingLiveTrain(trainNo), BannerType.INFO),
        needsLocationPermissionPrompt = !hasLoc,
      )
    }

    scope.launch {
      preferences.addRecentTrain(SavedTrainEntry(trainNo, ""))
    }

    if (hasLoc) {
      startGpsCollection()
      if (_uiState.value.backgroundTrackingEnabled) {
        updateForegroundServiceNotification()
      }
    }

    scope.launch {
      try {
        updateTrainLive(trainNo)
        if (token != startToken || !_uiState.value.running) return@launch
        schedulePolling(trainNo, token)
        scheduleApiAutoStop(token)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
        if (token != startToken || !_uiState.value.running) return@launch
        _uiState.update {
          it.copy(statusBanner = StatusBanner(currentStrings().retryingSignal, BannerType.INFO))
        }
        schedulePolling(trainNo, token)
      }
    }
  }

  private fun cancelTrackingJobs() {
    pollingJob?.cancel()
    pollingJob = null
    gpsJob?.cancel()
    gpsJob = null
    stopTimerJob?.cancel()
    stopTimerJob = null
  }

  fun stopTracking(showBanner: Boolean = true) {
    cancelTrackingJobs()
    gpsHistory.clear()
    TrainTrackingService.stop(appContext)

    _uiState.update { state ->
      state.copy(
        running = false,
        insideTrain = false,
        latestUserGps = null,
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
    val user = state.latestUserGps

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
    stopTimerJob = scope.launch {
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
    pollingJob = scope.launch {
      while (_uiState.value.running && token == startToken) {
        val interval = if (_uiState.value.insideTrain) GPS_MS else API_MS
        delay(interval)
        if (!_uiState.value.running || token != startToken) break
        try {
          updateTrainLive(trainNo)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Throwable) {
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
    gpsJob = scope.launch {
      locationClient.observeLocationUpdates().collect { event ->
        if (!_uiState.value.running) return@collect
        when (event) {
          is LocationEvent.Fix -> {
            val s = event.sample
            if (s.accuracy > MAX_ACC) {
              _uiState.update { it.copy(lowAccuracyMeters = s.accuracy.toInt()) }
              return@collect
            }
            gpsHistory.add(s)
            if (gpsHistory.size > 20) {
              gpsHistory.removeAt(0)
            }
            val movement = GeoMath.computeMovement(gpsHistory)
            _uiState.update { state ->
              state.copy(
                latestUserGps = s,
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
    val latestSample = state.latestUserGps ?: return
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
        scope.launch {
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
      val crossM = String.format(
        Locale.US,
        "%.1f",
        (metrics.crossTrackKm * 1000.0).takeIf { it.isFinite() } ?: 0.0,
      )
      val alongM = String.format(
        Locale.US,
        "%.1f",
        (metrics.alongTrackKm * 1000.0).takeIf { it.isFinite() } ?: 0.0,
      )
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
    if (!TrainTrackingService.hasLocationPermission(appContext)) return
    val trainNo = state.trainNoInput.trim()
    val name = state.trainName?.let { "$trainNo • $it" } ?: "🚆 $trainNo"
    val loc = state.locatedTrain
    val content = if (loc != null) {
      val rawSpeed = if (state.insideTrain && state.movementStats.speedKmh > 0) {
        state.movementStats.speedKmh
      } else {
        loc.speedKmh
      }
      val safeSpeed = rawSpeed.takeIf { it.isFinite() } ?: 0.0
      val nextText = if (loc.next.isNotBlank()) " → ${loc.next}" else ""
      "${loc.name}$nextText • ${String.format(Locale.US, "%.0f", safeSpeed)} km/h"
    } else {
      currentStrings().searchingLiveTrain(trainNo)
    }
    TrainTrackingService.startOrUpdate(
      context = appContext,
      title = name,
      content = content,
      stopLabel = currentStrings().stopTrackingBtn,
    )
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

  fun onHostViewModelCleared() {
    if (!_uiState.value.backgroundTrackingEnabled) {
      stopTracking(showBanner = false)
    }
  }
}
