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
import com.example.model.StoppageDistances
import com.example.model.TrackerUiState
import com.example.model.stringsForCode
import com.example.util.GeoMath
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
    private const val STATIONARY_EXIT_SAMPLES = 15 // ~75s completely stationary exits onboard mode

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
  private val voiceAnnouncer = VoiceAnnouncer(appContext)
  val tileLoader = OsmTileLoader(appContext.cacheDir)

  private val _uiState = MutableStateFlow(TrackerUiState())
  val uiState: StateFlow<TrackerUiState> = _uiState.asStateFlow()

  private var stationDb: List<StationDbEntry> = TrainRepository.CORE_STATIONS
  private var savedRoute: List<RouteStation> = emptyList()
  private val gpsHistory = ArrayList<GpsSample>(24)

  private var betweenJob: Job? = null
  private var autoStartDebounceJob: Job? = null
  private var pollingJob: Job? = null
  private var gpsJob: Job? = null
  private var stopTimerJob: Job? = null
  private var languageJob: Job? = null
  private var dynamicTranslationJob: Job? = null

  private var lastAutoStartedTrainNo = ""
  private var lastBetweenPair: Pair<String, String>? = null
  private var lastPublishTimeMillis = 0L
  private var lastAnnouncedStopKey = ""
  private var stationaryConfirmCount = 0
  private var startToken = 0
  private var loadedLanguageCode = "bn"

  init {
    TrainTrackingService.onStopRequestedFromNotification = {
      stopTracking()
    }

    scope.launch {
      preferences.prefsFlow.collect { saved ->
        _uiState.update { state ->
          state.copy(
            favoriteTrains = saved.favoriteTrains,
            recentTrains = saved.recentTrains,
            recentRoutes = saved.recentRoutes,
          )
        }
        if (saved.languageCode != loadedLanguageCode) {
          applyLanguage(saved.languageCode, persist = false)
        } else {
          scheduleDynamicTranslations()
        }
      }
    }

    scope.launch {
      val cached = repository.loadCachedStationsDb()
      if (cached.isNotEmpty()) {
        stationDb = cached
        refreshStationMatches()
      }
      val fresh = repository.fetchStationsDb(forceRefreshIfStale = true)
      if (fresh.isNotEmpty()) {
        stationDb = fresh
        refreshStationMatches()
      }
    }
  }

  private fun refreshStationMatches() {
    _uiState.update { state ->
      state.copy(
        stationDbLoaded = true,
        fromResolvedStation = GeoMath.resolveStation(state.fromInput, stationDb),
        toResolvedStation = GeoMath.resolveStation(state.toInput, stationDb),
        fromSuggestions = GeoMath.filterStationSuggestions(state.fromInput, stationDb, limit = 8),
        toSuggestions = GeoMath.filterStationSuggestions(state.toInput, stationDb, limit = 8),
      )
    }
    scheduleDynamicTranslations()
  }

  private fun currentStrings() = _uiState.value.strings

  fun setLanguage(languageCode: String) {
    applyLanguage(languageCode, persist = true)
  }

  private fun collectTranslatableNames(state: TrackerUiState): Set<String> {
    val names = LinkedHashSet<String>()
    state.fromResolvedStation?.name?.let(names::add)
    state.toResolvedStation?.name?.let(names::add)
    state.fromSuggestions.forEach { names.add(it.name) }
    state.toSuggestions.forEach { names.add(it.name) }
    state.betweenTrains.forEach { names.add(it.trainName) }
    state.favoriteTrains.forEach { if (it.trainName.isNotBlank()) names.add(it.trainName) }
    state.recentTrains.forEach { if (it.trainName.isNotBlank()) names.add(it.trainName) }
    state.trainName?.let(names::add)
    state.locatedTrain?.let { loc ->
      if (loc.name.isNotBlank()) names.add(loc.name)
      if (loc.next.isNotBlank()) names.add(loc.next)
    }
    state.route.forEach { st ->
      if (st.stationName.isNotBlank()) names.add(st.stationName)
    }
    state.stationAlarm?.stationName?.let(names::add)
    state.resolveDestinationRaw()?.let(names::add)
    return names
  }

  private fun scheduleDynamicTranslations() {
    val state = _uiState.value
    val langCode = state.languageCode
    if (langCode == "en") {
      if (state.translatedNames.isNotEmpty()) {
        _uiState.update { it.copy(translatedNames = emptyMap()) }
      }
      return
    }
    val names = collectTranslatableNames(state)
    if (names.isEmpty()) return

    dynamicTranslationJob?.cancel()
    dynamicTranslationJob = scope.launch {
      val translated = translationRepository.translateDynamicNames(langCode, names)
      if (_uiState.value.languageCode == langCode && translated.isNotEmpty()) {
        _uiState.update { cur ->
          cur.copy(translatedNames = cur.translatedNames + translated)
        }
        if (_uiState.value.running) {
          updateTrackingNotification()
        }
      }
    }
  }

  private fun applyLanguage(languageCode: String, persist: Boolean) {
    val cleanCode = languageCode.ifBlank { "bn" }
    loadedLanguageCode = cleanCode
    languageJob?.cancel()
    dynamicTranslationJob?.cancel()
    voiceAnnouncer.setLanguage(cleanCode)

    val isBuiltIn = cleanCode == "bn" || cleanCode == "en" || cleanCode == "hi"
    _uiState.update { state ->
      state.copy(
        languageCode = cleanCode,
        isTranslatingLanguage = !isBuiltIn,
        strings = if (isBuiltIn) stringsForCode(cleanCode) else state.strings,
        translatedNames = emptyMap(),
      )
    }

    languageJob = scope.launch {
      if (persist) {
        preferences.saveLanguageCode(cleanCode)
      }
      val resolved = translationRepository.getStringsForLanguage(cleanCode)
      val visibleNames = collectTranslatableNames(_uiState.value)
      val translatedNames = if (cleanCode != "en" && visibleNames.isNotEmpty()) {
        translationRepository.translateDynamicNames(cleanCode, visibleNames)
      } else {
        emptyMap()
      }

      _uiState.update { state ->
        if (state.languageCode == cleanCode) {
          state.copy(
            strings = resolved,
            translatedNames = translatedNames,
            isTranslatingLanguage = false,
          )
        } else {
          state
        }
      }

      if (_uiState.value.running) {
        updateTrackingNotification()
        announceStoppageIfChanged(force = true)
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
      updateTrackingNotification()
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
        updateTrackingNotification()
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
    if (_uiState.value.running) {
      updateTrackingNotification()
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
    onSelectTrainBetween(entry.trainNumber, entry.trainName)
  }

  fun onRecentRouteSelected(pair: SavedRoutePair) {
    _uiState.update {
      it.copy(
        fromInput = pair.fromInput,
        toInput = pair.toInput,
        fromResolvedStation = GeoMath.resolveStation(pair.fromInput, stationDb),
        toResolvedStation = GeoMath.resolveStation(pair.toInput, stationDb),
        fromSuggestions = emptyList(),
        toSuggestions = emptyList(),
      )
    }
    scheduleDynamicTranslations()
    maybeSearchBetween(force = true)
  }

  fun onFromInputChanged(value: String) {
    val suggestions = GeoMath.filterStationSuggestions(value, stationDb, limit = 8)
    val resolved = GeoMath.resolveStation(value, stationDb)
    _uiState.update {
      it.copy(
        fromInput = value,
        fromResolvedStation = resolved,
        fromSuggestions = suggestions,
      )
    }
    scheduleDynamicTranslations()
    if (_uiState.value.running) updateTrackingNotification()
    maybeSearchBetween()
  }

  fun onToInputChanged(value: String) {
    val suggestions = GeoMath.filterStationSuggestions(value, stationDb, limit = 8)
    val resolved = GeoMath.resolveStation(value, stationDb)
    _uiState.update {
      it.copy(
        toInput = value,
        toResolvedStation = resolved,
        toSuggestions = suggestions,
      )
    }
    scheduleDynamicTranslations()
    if (_uiState.value.running) updateTrackingNotification()
    maybeSearchBetween()
  }

  fun onStationSelected(isFrom: Boolean, entry: StationDbEntry) {
    val formatted = "${entry.name} (${entry.code})"
    _uiState.update { state ->
      if (isFrom) {
        state.copy(
          fromInput = formatted,
          fromResolvedStation = entry,
          fromSuggestions = emptyList(),
        )
      } else {
        state.copy(
          toInput = formatted,
          toResolvedStation = entry,
          toSuggestions = emptyList(),
        )
      }
    }
    scheduleDynamicTranslations()
    if (_uiState.value.running) updateTrackingNotification()
    maybeSearchBetween(force = true)
  }

  fun swapStations() {
    _uiState.update { state ->
      state.copy(
        fromInput = state.toInput,
        toInput = state.fromInput,
        fromResolvedStation = state.toResolvedStation,
        toResolvedStation = state.fromResolvedStation,
        fromSuggestions = emptyList(),
        toSuggestions = emptyList(),
      )
    }
    lastBetweenPair = null
    scheduleDynamicTranslations()
    if (_uiState.value.running) updateTrackingNotification()
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
        scheduleDynamicTranslations()
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

  fun onSelectTrainBetween(trainNumber: String, initialTrainName: String? = null) {
    val clean = trainNumber.filter { it.isDigit() }.take(5)
    if (clean.isEmpty()) return
    autoStartDebounceJob?.cancel()
    lastAutoStartedTrainNo = clean
    _uiState.update {
      it.copy(
        trainNoInput = clean,
        trainName = initialTrainName?.takeIf { n -> n.isNotBlank() } ?: it.trainName,
        fromSuggestions = emptyList(),
        toSuggestions = emptyList(),
      )
    }
    if (clean.length >= 4) {
      startTracking(prefilledTrainName = initialTrainName)
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

  fun startTracking(prefilledTrainName: String? = null) {
    val trainNo = _uiState.value.trainNoInput.trim()
    if (!Regex("""^\d{4,5}$""").matches(trainNo)) return

    val token = ++startToken
    cancelTrackingJobs()
    savedRoute = emptyList()
    gpsHistory.clear()
    lastPublishTimeMillis = 0L
    lastAnnouncedStopKey = ""
    stationaryConfirmCount = 0

    val s = currentStrings()
    val hasLoc = TrainTrackingService.hasLocationPermission(appContext)
    val needsNotifPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    _uiState.update {
      it.copy(
        running = true,
        insideTrain = false,
        trainName = prefilledTrainName?.takeIf { n -> n.isNotBlank() },
        rawSource = null,
        route = emptyList(),
        locatedTrain = null,
        stoppageDistances = StoppageDistances(null, null),
        selectedStationIndex = null,
        stationAlarm = null,
        alarmAlertMessage = null,
        focusedMapStation = null,
        latestUserGps = null,
        movementStats = MovementStats(0.0, 0.0),
        verification = null,
        lowAccuracyMeters = null,
        statusBanner = StatusBanner(s.searchingLiveTrain(trainNo), BannerType.INFO),
        needsLocationPermissionPrompt = !hasLoc,
        needsNotificationPermissionPrompt = needsNotifPrompt,
      )
    }

    scheduleDynamicTranslations()

    scope.launch {
      preferences.addRecentTrain(SavedTrainEntry(trainNo, prefilledTrainName ?: ""))
    }

    // Automatically activate live notification as soon as a train is selected
    updateTrackingNotification()

    if (hasLoc) {
      startGpsCollection()
    }

    scope.launch {
      try {
        updateTrainLive(trainNo)
        if (token != startToken || !_uiState.value.running) return@launch
        if (!_uiState.value.insideTrain) {
          schedulePolling(trainNo, token)
          scheduleApiAutoStop(token)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
        if (token != startToken || !_uiState.value.running) return@launch
        _uiState.update {
          it.copy(statusBanner = StatusBanner(currentStrings().retryingSignal, BannerType.INFO))
        }
        if (!_uiState.value.insideTrain) {
          schedulePolling(trainNo, token)
        }
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

  fun stopTracking() {
    cancelTrackingJobs()
    gpsHistory.clear()
    lastAutoStartedTrainNo = ""
    lastAnnouncedStopKey = ""
    stationaryConfirmCount = 0
    voiceAnnouncer.stop()
    TrainTrackingService.stop(appContext)

    _uiState.update { state ->
      state.copy(
        running = false,
        insideTrain = false,
        stoppageDistances = StoppageDistances(null, null),
        latestUserGps = null,
        verification = null,
        lowAccuracyMeters = null,
        statusBanner = null,
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
      val localizedStation = state.localizeName(alarm.stationName)
      val alertMsg = strings.alarmTriggeredMsg(trainNo, localizedStation)
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
        stopTracking()
        _uiState.update {
          it.copy(statusBanner = StatusBanner(currentStrings().apiTimeoutStopped, BannerType.INFO))
        }
      }
    }
  }

  private fun schedulePolling(trainNo: String, token: Int) {
    pollingJob?.cancel()
    pollingJob = scope.launch {
      while (_uiState.value.running && token == startToken && !_uiState.value.insideTrain) {
        delay(API_MS)
        if (!_uiState.value.running || token != startToken || _uiState.value.insideTrain) break
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
    // When moving onboard user GPS is active, never overwrite live location with API key data
    if (_uiState.value.insideTrain) return

    val previousTrain = _uiState.value.locatedTrain
    val payload = repository.fetchLiveTrain(
      trainNo = trainNo,
      cachedRoute = savedRoute,
      stationDb = stationDb,
    )
    if (_uiState.value.insideTrain) return

    if (payload.route.isNotEmpty()) {
      savedRoute = payload.route
    }
    val located = GeoMath.locateTrain(
      currentLocation = payload.currentLocation,
      route = payload.route,
      fallbackStationLabel = currentStrings().unknownStation,
    )
    val distances = GeoMath.computeStoppageDistances(located, payload.route)

    if (!payload.trainName.isNullOrBlank()) {
      preferences.addRecentTrain(SavedTrainEntry(trainNo, payload.trainName))
    }

    _uiState.update { state ->
      if (state.insideTrain) return@update state
      val shouldAutoCenter = previousTrain == null
      val nextStatus = when {
        previousTrain != null && payload.source == "gps" ->
          StatusBanner(currentStrings().otherUserGpsMsg, BannerType.OK)
        state.statusBanner?.type == BannerType.INFO -> null
        else -> state.statusBanner
      }
      state.copy(
        trainName = payload.trainName ?: state.trainName,
        rawSource = payload.source,
        route = payload.route,
        locatedTrain = located,
        stoppageDistances = distances,
        statusBanner = nextStatus,
        recenterSignal = if (shouldAutoCenter) state.recenterSignal + 1 else state.recenterSignal,
      )
    }

    scheduleDynamicTranslations()
    verifyAndSync()
    checkStationAlarmProximity()
    updateTrackingNotification()
    announceStoppageIfChanged()
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
            updateTrackingNotification()
            announceStoppageIfChanged()
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
    val route = state.route

    if (state.insideTrain) {
      // Already onboard: verify we haven't become stationary for an extended time or moved off-track
      val isStillMoving = movement.speedKmh >= 5.0 || movement.netDisplacementKm >= 0.015
      val offTrackWhileMoving = movement.speedKmh >= 8.0 && metrics.crossTrackKm > 0.8 && metrics.distanceKm > 1.0
      if (!isStillMoving) {
        stationaryConfirmCount++
      } else {
        stationaryConfirmCount = 0
      }

      if (offTrackWhileMoving || stationaryConfirmCount >= STATIONARY_EXIT_SAMPLES) {
        // User left train or stopped moving for extended duration — resume API/crowdsourced polling
        stationaryConfirmCount = 0
        val trainNo = state.trainNoInput.trim()
        _uiState.update {
          it.copy(
            insideTrain = false,
            rawSource = "api",
            verification = metrics,
            statusBanner = StatusBanner(strings.retryingSignal, BannerType.INFO),
          )
        }
        schedulePolling(trainNo, startToken)
        scheduleApiAutoStop(startToken)
        return
      }

      // Stop API polling & update train location directly from phone's moving GPS
      pollingJob?.cancel()
      pollingJob = null
      stopTimerJob?.cancel()
      stopTimerJob = null

      val updatedIdx = GeoMath.resolveProgressStationIndex(
        latestSample.lat,
        latestSample.lon,
        route,
        train.idx,
      )
      val curStation = route.getOrNull(updatedIdx)
      val nxtStation = route.getOrNull(updatedIdx + 1)
      val curName = curStation?.stationName?.takeIf { it.isNotBlank() }
        ?: curStation?.stationCode?.takeIf { it.isNotBlank() }
        ?: train.name
      val nxtName = nxtStation?.stationName?.takeIf { it.isNotBlank() }
        ?: nxtStation?.stationCode
        ?: ""

      val gpsTrain = train.copy(
        lat = latestSample.lat,
        lon = latestSample.lon,
        speedKmh = movement.speedKmh.takeIf { it > 0.5 } ?: train.speedKmh,
        bearingDegrees = movement.bearingDegrees.takeIf { it.isFinite() && movement.speedKmh >= 4.0 }
          ?: train.bearingDegrees,
        idx = updatedIdx,
        name = curName,
        next = nxtName,
      )
      val distances = GeoMath.computeStoppageDistances(gpsTrain, route)

      publishGpsIfDue(state.trainNoInput.trim(), state.trainName, latestSample)

      _uiState.update {
        it.copy(
          insideTrain = true,
          rawSource = "gps",
          locatedTrain = gpsTrain,
          stoppageDistances = distances,
          verification = metrics,
        )
      }
      return
    }

    if (metrics.recognizedInside) {
      // Moving GPS matched train timing and path! Stop API key polling and take over via phone GPS.
      stationaryConfirmCount = 0
      pollingJob?.cancel()
      pollingJob = null
      stopTimerJob?.cancel()
      stopTimerJob = null

      val updatedIdx = GeoMath.resolveProgressStationIndex(
        latestSample.lat,
        latestSample.lon,
        route,
        train.idx,
      )
      val curStation = route.getOrNull(updatedIdx)
      val nxtStation = route.getOrNull(updatedIdx + 1)
      val curName = curStation?.stationName?.takeIf { it.isNotBlank() }
        ?: curStation?.stationCode?.takeIf { it.isNotBlank() }
        ?: train.name
      val nxtName = nxtStation?.stationName?.takeIf { it.isNotBlank() }
        ?: nxtStation?.stationCode
        ?: ""

      val gpsTrain = train.copy(
        lat = latestSample.lat,
        lon = latestSample.lon,
        speedKmh = movement.speedKmh,
        bearingDegrees = movement.bearingDegrees.takeIf { it.isFinite() } ?: train.bearingDegrees,
        idx = updatedIdx,
        name = curName,
        next = nxtName,
      )
      val distances = GeoMath.computeStoppageDistances(gpsTrain, route)

      publishGpsIfDue(state.trainNoInput.trim(), state.trainName, latestSample)

      _uiState.update {
        it.copy(
          insideTrain = true,
          rawSource = "gps",
          locatedTrain = gpsTrain,
          stoppageDistances = distances,
          verification = metrics,
          statusBanner = StatusBanner(strings.gpsVerifiedInsideMsg, BannerType.OK),
        )
      }
    } else {
      // Stationary or off-path GPS: NEVER track train using this phone's GPS
      _uiState.update { it.copy(verification = metrics) }
    }
  }

  private fun publishGpsIfDue(trainNo: String, trainName: String?, sample: GpsSample) {
    val now = System.currentTimeMillis()
    if (now - lastPublishTimeMillis >= GPS_MS) {
      lastPublishTimeMillis = now
      scope.launch {
        repository.publishGps(trainNo, trainName, sample)
      }
    }
  }

  private fun announceStoppageIfChanged(force: Boolean = false) {
    val state = _uiState.value
    if (!state.running) return
    val loc = state.locatedTrain ?: return
    val rawCurStation = loc.name.trim()
    if (rawCurStation.isBlank() || rawCurStation == currentStrings().unknownStation) return
    val rawNxtStation = loc.next.trim()
    val stopKey = "${state.trainNoInput}|${state.languageCode}|$rawCurStation|$rawNxtStation"
    if (!force && stopKey == lastAnnouncedStopKey) return
    lastAnnouncedStopKey = stopKey

    val spokenCurrent = state.localizeName(rawCurStation)
    val spokenNext = if (rawNxtStation.isNotBlank()) state.localizeName(rawNxtStation) else ""
    val spokenText = currentStrings().announceStop(spokenCurrent, spokenNext)
    voiceAnnouncer.announce(spokenText, state.languageCode)
  }

  private fun updateTrackingNotification() {
    val state = _uiState.value
    if (!state.running) return
    val strings = currentStrings()
    val trainNo = state.trainNoInput.trim()
    val localizedTrainName = state.trainName?.let { state.localizeName(it) }
    val title = localizedTrainName?.let { "🚆 $trainNo • $it" } ?: "🚆 $trainNo"
    val loc = state.locatedTrain
    val distances = state.stoppageDistances

    val rawCurrent = loc?.name?.takeIf { it.isNotBlank() }
    val localizedCurrent = rawCurrent?.let { state.localizeName(it) } ?: strings.waitingLabel
    val currentText = if (rawCurrent != null && distances.passedFromCurrentKm != null) {
      "$localizedCurrent (${strings.formatPassedDistance(distances.passedFromCurrentKm)})"
    } else {
      localizedCurrent
    }

    val rawNext = loc?.next?.takeIf { it.isNotBlank() }
    val localizedNext = rawNext?.let { state.localizeName(it) } ?: "—"
    val nextText = if (rawNext != null && distances.remainingToNextKm != null) {
      "$localizedNext (${strings.formatDistance(distances.remainingToNextKm)})"
    } else {
      localizedNext
    }

    val rawDest = state.resolveDestinationRaw()
    val destinationStation = rawDest?.let { state.localizeName(it) } ?: "—"

    val info = TrackingNotificationContent(
      title = title,
      currentLabel = strings.notifCurrentLabel,
      currentStation = currentText,
      nextLabel = strings.notifNextLabel,
      nextStation = nextText,
      destLabel = strings.notifDestinationLabel,
      destStation = destinationStation,
      stopLabel = strings.stopTrackingBtn,
    )
    TrainTrackingService.startOrUpdate(appContext, info)
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
}
