package com.example.data

import com.example.model.CurrentLocationDto
import com.example.model.GpsSample
import com.example.model.LiveTrainPayload
import com.example.model.RouteStation
import com.example.model.StationDbEntry
import com.example.model.TrainBetweenItem
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(message: String) : Exception(message)

class TimeoutApiException : Exception("TIMEOUT")

class TrainRepository(private val cacheDir: File? = null) {
  companion object {
    const val API_URL =
      "https://script.google.com/macros/s/AKfycbxpHggaA7sNDXrwcyNid4Yy-Mfm3nLe8M0VGgplD4XHHc7Ul9LTWDJtpjj4C_4MFGopBQ/exec"
    const val STATIONS_CDN_URL =
      "https://cdn.jsdelivr.net/gh/datameet/railways/stations.json"
    const val STATIONS_RAW_FALLBACK_URL =
      "https://raw.githubusercontent.com/datameet/railways/master/stations.json"
    const val TRAIN_CDN_BASE =
      "https://raw.githubusercontent.com/datameet/railways/master/trains/"
    private const val STATION_CACHE_FILE = "stations_cache.json"
    private const val STATION_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000L

    val CORE_STATIONS: List<StationDbEntry> = listOf(
      StationDbEntry("AHMADPUR JN", "AMP", 23.8359, 87.7033),
      StationDbEntry("BOLPUR S NIKTN", "BHP", 23.6683, 87.6908),
      StationDbEntry("SAINTHIA", "SNT", 23.9453, 87.6806),
      StationDbEntry("RAMPURHAT", "RPH", 24.1707, 87.7817),
      StationDbEntry("BARDDHAMAN JN", "BWN", 23.2504, 87.8705),
      StationDbEntry("HOWRAH JN", "HWH", 22.5839, 88.3426),
      StationDbEntry("SEALDAH", "SDAH", 22.5678, 88.3705),
      StationDbEntry("KOLKATA", "KOAA", 22.6012, 88.3834),
      StationDbEntry("SHALIMAR", "SHM", 22.5567, 88.3168),
      StationDbEntry("SANTRAGACHI JN", "SRC", 22.5819, 88.2844),
      StationDbEntry("KHARAGPUR JN", "KGP", 22.3396, 87.3250),
      StationDbEntry("BANDEL JN", "BDC", 22.9242, 88.3788),
      StationDbEntry("NAIHATI JN", "NH", 22.8935, 88.4209),
      StationDbEntry("DANKUNI", "DKAE", 22.6716, 88.2932),
      StationDbEntry("MALDA TOWN", "MLDT", 25.0096, 88.1333),
      StationDbEntry("NEW FARAKKA JN", "NFK", 24.8035, 87.8938),
      StationDbEntry("NEW JALPAIGURI", "NJP", 26.6828, 88.4430),
      StationDbEntry("SILIGURI JN", "SGUJ", 26.7310, 88.4282),
      StationDbEntry("GUWAHATI", "GHY", 26.1820, 91.7510),
      StationDbEntry("ASANSOL JN", "ASN", 23.6914, 86.9762),
      StationDbEntry("DURGAPUR", "DGR", 23.4956, 87.3119),
      StationDbEntry("MADHUPUR JN", "MDP", 24.2583, 86.6458),
      StationDbEntry("JASIDIH JN", "JSME", 24.5130, 86.6479),
      StationDbEntry("PATNA JN", "PNBE", 25.6033, 85.1372),
      StationDbEntry("GAYA JN", "GAYA", 24.8041, 84.9999),
      StationDbEntry("DHANBAD JN", "DHN", 23.7908, 86.4290),
      StationDbEntry("TATANAGAR JN", "TATA", 22.7689, 86.1972),
      StationDbEntry("RANCHI", "RNC", 23.3502, 85.3358),
      StationDbEntry("BHUBANESWAR", "BBS", 20.2669, 85.8434),
      StationDbEntry("PURI", "PURI", 19.8077, 85.8390),
      StationDbEntry("VISAKHAPATNAM", "VSKP", 17.7219, 83.2899),
      StationDbEntry("VIJAYAWADA JN", "BZA", 16.5186, 80.6200),
      StationDbEntry("MGR CHENNAI CTL", "MAS", 13.0827, 80.2755),
      StationDbEntry("KSR BENGALURU", "SBC", 12.9781, 77.5697),
      StationDbEntry("SECUNDERABAD JN", "SC", 17.4344, 78.5013),
      StationDbEntry("PUNE JN", "PUNE", 18.5289, 73.8744),
      StationDbEntry("MUMBAI CSMT", "CSMT", 18.9398, 72.8355),
      StationDbEntry("LOKMANYA TILAK T", "LTT", 19.0700, 72.8914),
      StationDbEntry("MUMBAI CENTRAL", "MMCT", 18.9712, 72.8195),
      StationDbEntry("AHMEDABAD JN", "ADI", 23.0268, 72.6008),
      StationDbEntry("SURAT", "ST", 21.2049, 72.8408),
      StationDbEntry("JAIPUR", "JP", 26.9196, 75.7878),
      StationDbEntry("BHOPAL JN", "BPL", 23.2683, 77.4136),
      StationDbEntry("NAGPUR", "NGP", 21.1502, 79.0888),
      StationDbEntry("PRAYAGRAJ JN", "PRYJ", 25.4460, 81.8339),
      StationDbEntry("VARANASI JN", "BSB", 25.3270, 82.9866),
      StationDbEntry("LUCKNOW NR", "LKO", 26.8318, 80.9209),
      StationDbEntry("KANPUR CENTRAL", "CNB", 26.4539, 80.3512),
      StationDbEntry("NEW DELHI", "NDLS", 28.6429, 77.2191),
      StationDbEntry("ANAND VIHAR TRM", "ANVT", 28.6502, 77.3150),
      StationDbEntry("H NIZAMUDDIN", "NZM", 28.5887, 77.2535),
      StationDbEntry("AMRITSAR JN", "ASR", 31.6331, 74.8672),
      StationDbEntry("THIRUVANANTHAPURAM CTL", "TVC", 8.4875, 76.9525),
      StationDbEntry("MURARAI", "MRR", 24.4557, 87.8569),
      StationDbEntry("NALHATI JN", "NHT", 24.2943, 87.8266),
      StationDbEntry("TARAPITH ROAD", "TPF", 24.1322, 87.7740),
      StationDbEntry("MALLARPUR", "MLV", 24.0837, 87.7294),
      StationDbEntry("PRANTIK", "PNE", 23.6939, 87.7191),
      StationDbEntry("GUSKARA", "GKH", 23.4956, 87.7438),
      StationDbEntry("KATWA", "KWAE", 23.6431, 88.1281),
      StationDbEntry("AZIMGANJ JN", "AZ", 24.2367, 88.2484),
      StationDbEntry("BERHAMPORE CRT", "BPC", 24.1038, 88.2572),
      StationDbEntry("KRISHNANAGAR CTY JN", "KNJ", 23.4013, 88.4998),
      StationDbEntry("RANAGHAT JN", "RHA", 23.1753, 88.5677),
      StationDbEntry("SURI", "SURI", 23.9102, 87.5269),
      StationDbEntry("SIURI", "SURI", 23.9102, 87.5269),
      StationDbEntry("ANDAL JN", "UDL", 23.5836, 87.1932),
      StationDbEntry("PANAGARH", "PAN", 23.4526, 87.4342),
    )
  }

  private val sheetRepository = SheetTrainRepository(cacheDir)

  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  private val sheetSyncManager = SheetSyncManager(client)
  private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val textPlainJson = "text/plain;charset=utf-8".toMediaType()

  suspend fun loadCachedStationsDb(): List<StationDbEntry> = withContext(Dispatchers.IO) {
    val file = cacheDir?.let { File(it, STATION_CACHE_FILE) }
    val cached = if (file != null && file.exists()) {
      try {
        parseStationsGeoJson(file.readText())
      } catch (_: Exception) {
        emptyList()
      }
    } else {
      emptyList()
    }
    val sheetStations = sheetRepository.loadCachedSheetStations()
    sheetRepository.mergeStationLists(sheetStations, mergeWithCoreStations(cached))
  }

  suspend fun fetchStationsDb(forceRefreshIfStale: Boolean = true): List<StationDbEntry> =
    withContext(Dispatchers.IO) {
      val sheetStations = sheetRepository.fetchSheetStations(forceRefresh = true)
      val file = cacheDir?.let { File(it, STATION_CACHE_FILE) }
      if (file != null && file.exists() && forceRefreshIfStale) {
        val age = System.currentTimeMillis() - file.lastModified()
        if (age < STATION_CACHE_MAX_AGE_MS) {
          val cached = try {
            parseStationsGeoJson(file.readText())
          } catch (_: Exception) {
            emptyList()
          }
          if (cached.isNotEmpty()) {
            return@withContext sheetRepository.mergeStationLists(
              sheetStations,
              mergeWithCoreStations(cached),
            )
          }
        }
      }

      for (url in listOf(STATIONS_CDN_URL, STATIONS_RAW_FALLBACK_URL)) {
        try {
          val req = Request.Builder().url(url).get().build()
          client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) continue
            val body = resp.body?.string() ?: continue
            val parsed = parseStationsGeoJson(body)
            if (parsed.isNotEmpty()) {
              if (file != null) {
                try {
                  file.parentFile?.mkdirs()
                  file.writeText(body)
                } catch (_: Exception) {
                }
              }
              return@withContext sheetRepository.mergeStationLists(
                sheetStations,
                mergeWithCoreStations(parsed),
              )
            }
          }
        } catch (_: Exception) {
        }
      }
      sheetRepository.mergeStationLists(sheetStations, loadCachedStationsDb())
    }

  private fun mergeWithCoreStations(remote: List<StationDbEntry>): List<StationDbEntry> {
    if (remote.isEmpty()) return CORE_STATIONS
    val existingCodes = HashSet<String>(remote.size + CORE_STATIONS.size)
    remote.forEach { existingCodes.add(it.code.uppercase(Locale.US)) }
    val merged = ArrayList<StationDbEntry>(remote.size + CORE_STATIONS.size)
    merged.addAll(remote)
    CORE_STATIONS.forEach { seed ->
      if (!existingCodes.contains(seed.code.uppercase(Locale.US))) {
        merged.add(seed)
      }
    }
    return merged
  }

  private fun parseStationsGeoJson(rawJson: String): List<StationDbEntry> {
    val root = JSONObject(rawJson)
    val features = root.optJSONArray("features") ?: return emptyList()
    val list = ArrayList<StationDbEntry>(features.length())
    for (i in 0 until features.length()) {
      val f = features.optJSONObject(i) ?: continue
      val props = f.optJSONObject("properties") ?: continue
      val code = props.optString("code", "").trim().uppercase(Locale.US)
      val name = props.optString("name", "").trim()
      val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates")
      val lon = coords?.optDoubleOrNull(0)
      val lat = coords?.optDoubleOrNull(1)
      if (code.isNotEmpty() || name.isNotEmpty()) {
        list.add(StationDbEntry(name = name, code = code, lat = lat, lon = lon))
      }
    }
    return list
  }

  suspend fun fetchTrainsBetween(
    fromCode: String,
    toCode: String,
    stationDb: List<StationDbEntry> = CORE_STATIONS,
  ): List<TrainBetweenItem> =
    withContext(Dispatchers.IO) {
      // 1) Search Train, Station & Schedule Google Sheets first (avoids API key usage when found)
      val sheetMatches = try {
        sheetRepository.searchTrainsBetweenFromSheet(fromCode, toCode, stationDb)
      } catch (_: Exception) {
        emptyList()
      }
      if (sheetMatches.isNotEmpty()) {
        return@withContext sheetMatches
      }

      // 2) Fallback to backend API key search when no matching trains are found in sheets
      val root = getApi(mapOf("action" to "between", "from" to fromCode, "to" to toCode))
      val trainsArr = root.optJSONObject("data")?.optJSONArray("trains") ?: JSONArray()
      val result = ArrayList<TrainBetweenItem>(trainsArr.length())
      for (i in 0 until trainsArr.length()) {
        val obj = trainsArr.optJSONObject(i) ?: continue
        val trainObj = obj.optJSONObject("train")
        val number = trainObj?.optString("number", "") ?: ""
        val name = trainObj?.optString("name", "") ?: ""
        val dep = obj.optJSONObject("from")?.optString("departure", "--:--")?.ifBlank { "--:--" }
          ?: "--:--"
        val arr = obj.optJSONObject("to")?.optString("arrival", "--:--")?.ifBlank { "--:--" }
          ?: "--:--"
        val dist = obj.optDoubleOrNull("distance") ?: 0.0
        result.add(
          TrainBetweenItem(
            trainNumber = number,
            trainName = name,
            departure = dep,
            arrival = arr,
            distanceKm = dist,
          ),
        )
      }
      if (result.isNotEmpty()) {
        syncScope.launch {
          sheetSyncManager.syncBetweenResults(fromCode, toCode, result, stationDb)
        }
      }
      result
    }

  suspend fun fetchLiveTrain(
    trainNo: String,
    cachedRoute: List<RouteStation>,
    stationDb: List<StationDbEntry>,
  ): LiveTrainPayload = withContext(Dispatchers.IO) {
    // 1) Check Train, Station & Schedule Google Sheets first
    val sheetLookup = try {
      sheetRepository.findTrainRouteFromSheet(trainNo, stationDb)
    } catch (_: Exception) {
      null
    }
    val sheetHasCompleteRoute = sheetLookup != null && sheetLookup.isComplete

    // 2) Live API for real-time currentLocation (and fallback route if sheets are missing/incomplete)
    var liveError: Throwable? = null
    val root: JSONObject? = try {
      getApi(mapOf("action" to "live", "trainNo" to trainNo))
    } catch (t: Throwable) {
      liveError = t
      null
    }

    val payload = root?.optJSONObject("data") ?: root
    val apiRoute = parseRouteArray(payload?.optJSONArray("route"))
      ?: parseRouteArray(root?.optJSONArray("route"))
      ?: parseRouteArray(payload?.optJSONArray("stations"))

    // 3) Only call CDN / schedule API if sheets lacked a complete route (saves API key usage!)
    var cdnRoute: List<RouteStation>? = null
    var apiSchedRoute: List<RouteStation>? = null
    if (!sheetHasCompleteRoute) {
      if (apiRoute.isNullOrEmpty() && cachedRoute.isEmpty()) {
        cdnRoute = fetchTrainRouteFromCdn(trainNo)
        if (cdnRoute.isNullOrEmpty() && trainNo.length == 5) {
          cdnRoute = fetchTrainRouteFromCdn(trainNo.drop(1))
        }
      }
      if (apiRoute.isNullOrEmpty() && cdnRoute.isNullOrEmpty() && cachedRoute.isEmpty()) {
        try {
          val sched = getApi(mapOf("action" to "schedule", "trainNo" to trainNo))
          apiSchedRoute = parseRouteArray(sched.optJSONArray("route"))
            ?: parseRouteArray(sched.optJSONObject("data")?.optJSONArray("route"))
            ?: parseRouteArray(sched.optJSONArray("stations"))
        } catch (_: Exception) {
        }
      }
    }

    val mergedRoute = selectAndMergeBestRoute(
      sheetRoute = sheetLookup?.route.orEmpty(),
      apiRoute = apiRoute.orEmpty(),
      cdnRoute = cdnRoute.orEmpty(),
      apiSchedRoute = apiSchedRoute.orEmpty(),
      cachedRoute = cachedRoute,
    )

    if (mergedRoute.isEmpty() && root == null && liveError != null) {
      throw liveError
    }

    val apiLoc = parseCurrentLocation(payload?.optJSONObject("currentLocation"))
    val currentLoc = apiLoc ?: sheetLookup?.let {
      sheetRepository.estimateLocationFromSheetSchedule(it.entry, mergedRoute)
    }

    val chosenRouteRaw = when {
      mergedRoute.isNotEmpty() -> mergedRoute
      currentLoc?.lat != null && currentLoc.lon != null -> listOf(
        RouteStation(
          stationCode = currentLoc.stationCode ?: "",
          stationName = currentLoc.stationName ?: "চলন্ত ট্রেন",
          sequence = 1,
          lat = currentLoc.lat,
          lon = currentLoc.lon,
          scheduledTimeRaw = null,
          arrival = null,
          departure = null,
        ),
        RouteStation(
          stationCode = "",
          stationName = currentLoc.nextStationName ?: "পরবর্তী স্টেশন",
          sequence = 2,
          lat = currentLoc.lat + 0.05,
          lon = currentLoc.lon + 0.05,
          scheduledTimeRaw = null,
          arrival = null,
          departure = null,
        ),
      )
      else -> emptyList()
    }
    val chosenRoute = enrichRouteFromStationDb(chosenRouteRaw, stationDb)

    val trainName = sheetLookup?.entry?.trainName?.takeIf { it.isNotBlank() }
      ?: payload?.optStringOrNull("trainName")
      ?: root?.optStringOrNull("trainName")

    val source = when {
      apiLoc != null -> payload?.optStringOrNull("source") ?: root?.optStringOrNull("source")
      sheetLookup != null -> "sheet"
      else -> payload?.optStringOrNull("source") ?: root?.optStringOrNull("source")
    }

    // 4) Auto-sync missing/incomplete/corrected Train, Train_Stops, Station, & Schedule data back to Google Sheets
    val routeUpgraded = chosenRoute.size >= 2 && (
      sheetLookup == null ||
        !sheetLookup.isComplete ||
        sheetLookup.needsTrainStopsFill ||
        chosenRoute.size > sheetLookup.route.size ||
        routeHasNewTimesOrStations(chosenRoute, sheetLookup.scheduleRows)
      )
    if (routeUpgraded) {
      syncScope.launch {
        sheetSyncManager.syncResolvedTrain(
          trainNo = trainNo,
          trainName = trainName,
          existingEntry = sheetLookup?.entry,
          existingScheduleRows = sheetLookup?.scheduleRows.orEmpty(),
          resolvedRoute = chosenRoute,
          stationDb = stationDb,
        )
      }
    }

    LiveTrainPayload(
      trainName = trainName,
      source = source,
      currentLocation = currentLoc,
      route = chosenRoute,
    )
  }

  fun reportLiveObservation(
    trainNo: String,
    trainName: String?,
    observedStationCode: String,
    observedStationName: String,
    observedTimeHHmm: String?,
    route: List<RouteStation>,
    stationDb: List<StationDbEntry>,
  ) {
    syncScope.launch {
      sheetSyncManager.syncLiveObservation(
        trainNo = trainNo,
        trainName = trainName,
        observedStationCode = observedStationCode,
        observedStationName = observedStationName,
        observedTimeHHmm = observedTimeHHmm,
        route = route,
        stationDb = stationDb,
      )
    }
  }

  private fun selectAndMergeBestRoute(
    sheetRoute: List<RouteStation>,
    apiRoute: List<RouteStation>,
    cdnRoute: List<RouteStation>,
    apiSchedRoute: List<RouteStation>,
    cachedRoute: List<RouteStation>,
  ): List<RouteStation> {
    val candidates = listOf(sheetRoute, apiRoute, apiSchedRoute, cdnRoute, cachedRoute)
      .filter { it.isNotEmpty() }
    if (candidates.isEmpty()) return emptyList()

    val primary = candidates.maxByOrNull { r ->
      val timed = r.count { hasValidTime(it.scheduledTimeRaw) || hasValidTime(it.arrival) }
      r.size * 1000 + timed * 10
    } ?: return emptyList()

    // Enrich missing times/coords on the primary route from any secondary route by stationCode
    val donorByCode = HashMap<String, RouteStation>()
    for (r in candidates) {
      if (r === primary) continue
      for (st in r) {
        val code = st.stationCode.trim().uppercase(Locale.US)
        if (code.isBlank()) continue
        val cur = donorByCode[code]
        if (cur == null || (hasValidTime(st.arrival ?: st.scheduledTimeRaw) && !hasValidTime(cur.arrival ?: cur.scheduledTimeRaw))) {
          donorByCode[code] = st
        }
      }
    }

    return primary.mapIndexed { idx, st ->
      val code = st.stationCode.trim().uppercase(Locale.US)
      val d = if (code.isNotBlank()) donorByCode[code] else null
      if (d == null) {
        st.copy(sequence = idx + 1)
      } else {
        st.copy(
          stationName = st.stationName.ifBlank { d.stationName },
          sequence = idx + 1,
          lat = st.lat ?: d.lat,
          lon = st.lon ?: d.lon,
          scheduledTimeRaw = if (hasValidTime(st.scheduledTimeRaw)) st.scheduledTimeRaw else d.scheduledTimeRaw,
          arrival = if (hasValidTime(st.arrival)) st.arrival else d.arrival,
          departure = if (hasValidTime(st.departure)) st.departure else d.departure,
        )
      }
    }
  }

  private fun hasValidTime(raw: String?): Boolean {
    val s = raw?.trim().orEmpty()
    return s.isNotEmpty() && s != "--:--" && !s.equals("None", ignoreCase = true)
  }

  private fun routeHasNewTimesOrStations(
    resolvedRoute: List<RouteStation>,
    sheetScheduleRows: List<SheetScheduleRow>,
  ): Boolean {
    if (sheetScheduleRows.isEmpty()) return resolvedRoute.isNotEmpty()
    val byCode = sheetScheduleRows.associateBy { it.stationCode.trim().uppercase(Locale.US) }
    return resolvedRoute.any { st ->
      val code = st.stationCode.trim().uppercase(Locale.US)
      val row = if (code.isNotBlank()) byCode[code] else null
      row == null ||
        ((!hasValidTime(row.arrivalRaw) && hasValidTime(st.arrival ?: st.scheduledTimeRaw)) ||
          (!hasValidTime(row.departureRaw) && hasValidTime(st.departure ?: st.scheduledTimeRaw)))
    }
  }

  suspend fun publishGps(trainNo: String, trainName: String?, sample: GpsSample) =
    withContext(Dispatchers.IO) {
      try {
        val json = JSONObject().apply {
          put("action", "gps")
          put("trainNo", trainNo)
          put("lat", sample.lat)
          put("lon", sample.lon)
          put("accuracy", sample.accuracy.toDouble())
          if (sample.speedMetersPerSec != null) {
            put("speed", sample.speedMetersPerSec.toDouble())
          } else {
            put("speed", JSONObject.NULL)
          }
          if (sample.headingDegrees != null) {
            put("heading", sample.headingDegrees.toDouble())
          } else {
            put("heading", JSONObject.NULL)
          }
          put("time", sample.timeMillis)
          put("trainName", trainName ?: "")
        }
        val req = Request.Builder()
          .url(API_URL)
          .post(json.toString().toRequestBody(textPlainJson))
          .build()
        client.newCall(req).execute().close()
      } catch (_: Exception) {
      }
    }

  private fun getApi(params: Map<String, String>): JSONObject {
    val urlBuilder = API_URL.toHttpUrl().newBuilder()
    params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
    val req = Request.Builder()
      .url(urlBuilder.build())
      .cacheControl(CacheControl.FORCE_NETWORK)
      .get()
      .build()

    try {
      client.newCall(req).execute().use { resp ->
        val bodyStr = resp.body?.string() ?: throw ApiException("Empty response")
        val json = JSONObject(bodyStr)
        val successVal = if (json.has("success")) json.optBoolean("success", true) else true
        if (!resp.isSuccessful || !successVal) {
          val errObj = json.optJSONObject("error")
          val errMsg = errObj?.optString("message")
            ?: json.optStringOrNull("error")
            ?: "API error"
          throw ApiException(errMsg)
        }
        return json
      }
    } catch (e: SocketTimeoutException) {
      throw TimeoutApiException()
    } catch (e: IOException) {
      throw ApiException(e.message ?: "Network error")
    }
  }

  private fun fetchTrainRouteFromCdn(trainNumber: String): List<RouteStation>? {
    return try {
      val req = Request.Builder().url("$TRAIN_CDN_BASE$trainNumber.json").get().build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return null
        if (features.length() == 0) return null
        val list = ArrayList<RouteStation>(features.length())
        for (i in 0 until features.length()) {
          val f = features.optJSONObject(i) ?: continue
          val props = f.optJSONObject("properties")
          val code = (props?.optString("code", "") ?: "").trim().uppercase(Locale.US)
          val name = props?.optStringOrNull("name") ?: code
          val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates")
          val lon = coords?.optDoubleOrNull(0)
          val lat = coords?.optDoubleOrNull(1)
          list.add(
            RouteStation(
              stationCode = code,
              stationName = name,
              sequence = i + 1,
              lat = lat,
              lon = lon,
              scheduledTimeRaw = "--:--",
              arrival = "--:--",
              departure = "--:--",
            ),
          )
        }
        list
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun enrichRouteFromStationDb(
    route: List<RouteStation>,
    stationDb: List<StationDbEntry>,
  ): List<RouteStation> {
    if (stationDb.isEmpty()) return route
    val dbMap = stationDb.associateBy { it.code }
    return route.map { st ->
      val code = st.stationCode.trim().uppercase(Locale.US)
      val match = dbMap[code]
      if (match != null) {
        st.copy(
          lat = st.lat ?: match.lat,
          lon = st.lon ?: match.lon,
          stationName = st.stationName.ifBlank { match.name },
        )
      } else {
        st
      }
    }
  }

  private fun parseCurrentLocation(obj: JSONObject?): CurrentLocationDto? {
    if (obj == null) return null
    val code = obj.optStringOrNull("stationCode") ?: obj.optStringOrNull("code")
    val name = obj.optStringOrNull("stationName") ?: obj.optStringOrNull("name")
    val seq = if (obj.has("sequence") && !obj.isNull("sequence")) {
      obj.optInt("sequence")
    } else {
      null
    }
    val lat = obj.optDoubleOrNull("lat") ?: obj.optDoubleOrNull("latitude")
    val lon = obj.optDoubleOrNull("lng")
      ?: obj.optDoubleOrNull("lon")
      ?: obj.optDoubleOrNull("longitude")
    val speed = obj.optDoubleOrNull("speedKmh") ?: obj.optDoubleOrNull("speed")
    val bearing = obj.optDoubleOrNull("bearingDegrees")
    val progress = obj.optDoubleOrNull("segmentProgress")
    val nextName = obj.optStringOrNull("nextStationName")

    return CurrentLocationDto(
      stationCode = code,
      stationName = name,
      sequence = seq,
      lat = lat,
      lon = lon,
      speedKmh = speed,
      bearingDegrees = bearing,
      segmentProgress = progress,
      nextStationName = nextName,
    )
  }

  private fun parseRouteArray(arr: JSONArray?): List<RouteStation>? {
    if (arr == null || arr.length() == 0) return null
    val list = ArrayList<RouteStation>(arr.length())
    for (i in 0 until arr.length()) {
      val s = arr.optJSONObject(i) ?: continue
      val code = (s.optStringOrNull("stationCode") ?: s.optStringOrNull("code") ?: "")
        .trim()
        .uppercase(Locale.US)
      val name = s.optStringOrNull("stationName")
        ?: s.optStringOrNull("name")
        ?: code
      val seq = if (s.has("sequence") && !s.isNull("sequence")) s.optInt("sequence") else null
      val lat = s.optDoubleOrNull("lat") ?: s.optDoubleOrNull("latitude")
      val lon = s.optDoubleOrNull("lng")
        ?: s.optDoubleOrNull("lon")
        ?: s.optDoubleOrNull("longitude")
      val arrival = s.optStringOrNull("arrivalTime")
        ?: s.optStringOrNull("arrival")
        ?: s.optStringOrNull("scheduledArrival")
        ?: s.optStringOrNull("eta")
      val departure = s.optStringOrNull("departure")
      val schedRaw = arrival ?: departure

      list.add(
        RouteStation(
          stationCode = code,
          stationName = name,
          sequence = seq,
          lat = lat,
          lon = lon,
          scheduledTimeRaw = schedRaw,
          arrival = arrival,
          departure = departure,
        ),
      )
    }
    return list
  }
}

private fun JSONObject.optStringOrNull(key: String): String? {
  if (!has(key) || isNull(key)) return null
  val v = optString(key, "").trim()
  return v.takeIf { it.isNotEmpty() && it != "null" }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
  if (!has(key) || isNull(key)) return null
  val raw = opt(key) ?: return null
  val num = when (raw) {
    is Number -> raw.toDouble()
    is String -> raw.toDoubleOrNull()
    else -> null
  }
  return num?.takeIf { it.isFinite() }
}

private fun JSONArray.optDoubleOrNull(index: Int): Double? {
  if (index < 0 || index >= length() || isNull(index)) return null
  val num = optDouble(index, Double.NaN)
  return num.takeIf { it.isFinite() }
}
