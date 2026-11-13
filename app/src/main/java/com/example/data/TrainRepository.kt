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
import kotlinx.coroutines.Dispatchers
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
    const val TRAIN_CDN_BASE =
      "https://raw.githubusercontent.com/datameet/railways/master/trains/"
    private const val STATION_CACHE_FILE = "stations_cache.json"
    private const val STATION_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000L
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  private val textPlainJson = "text/plain;charset=utf-8".toMediaType()

  suspend fun loadCachedStationsDb(): List<StationDbEntry> = withContext(Dispatchers.IO) {
    val file = cacheDir?.let { File(it, STATION_CACHE_FILE) } ?: return@withContext emptyList()
    if (!file.exists()) return@withContext emptyList()
    return@withContext try {
      parseStationsGeoJson(file.readText())
    } catch (_: Exception) {
      emptyList()
    }
  }

  suspend fun fetchStationsDb(forceRefreshIfStale: Boolean = true): List<StationDbEntry> =
    withContext(Dispatchers.IO) {
      val file = cacheDir?.let { File(it, STATION_CACHE_FILE) }
      if (file != null && file.exists() && forceRefreshIfStale) {
        val age = System.currentTimeMillis() - file.lastModified()
        if (age < STATION_CACHE_MAX_AGE_MS) {
          val cached = try {
            parseStationsGeoJson(file.readText())
          } catch (_: Exception) {
            emptyList()
          }
          if (cached.isNotEmpty()) return@withContext cached
        }
      }

      try {
        val req = Request.Builder().url(STATIONS_CDN_URL).get().build()
        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@withContext loadCachedStationsDb()
          val body = resp.body?.string() ?: return@withContext loadCachedStationsDb()
          val parsed = parseStationsGeoJson(body)
          if (parsed.isNotEmpty() && file != null) {
            try {
              file.parentFile?.mkdirs()
              file.writeText(body)
            } catch (_: Exception) {
            }
          }
          parsed.ifEmpty { loadCachedStationsDb() }
        }
      } catch (_: Exception) {
        loadCachedStationsDb()
      }
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

  suspend fun fetchTrainsBetween(fromCode: String, toCode: String): List<TrainBetweenItem> =
    withContext(Dispatchers.IO) {
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
      result
    }

  suspend fun fetchLiveTrain(
    trainNo: String,
    cachedRoute: List<RouteStation>,
    stationDb: List<StationDbEntry>,
  ): LiveTrainPayload = withContext(Dispatchers.IO) {
    val root = getApi(mapOf("action" to "live", "trainNo" to trainNo))
    val payload = root.optJSONObject("data") ?: root

    var incomingRoute = parseRouteArray(payload.optJSONArray("route"))
      ?: parseRouteArray(root.optJSONArray("route"))
      ?: parseRouteArray(payload.optJSONArray("stations"))

    // Fallback 1: DataMeet GitHub GeoJSON (5-digit, then 4-digit)
    if (incomingRoute.isNullOrEmpty() && cachedRoute.isEmpty()) {
      incomingRoute = fetchTrainRouteFromCdn(trainNo)
      if (incomingRoute.isNullOrEmpty() && trainNo.length == 5) {
        incomingRoute = fetchTrainRouteFromCdn(trainNo.drop(1))
      }
    }

    // Fallback 2: Backend schedule action
    if (incomingRoute.isNullOrEmpty() && cachedRoute.isEmpty()) {
      try {
        val sched = getApi(mapOf("action" to "schedule", "trainNo" to trainNo))
        incomingRoute = parseRouteArray(sched.optJSONArray("route"))
          ?: parseRouteArray(sched.optJSONObject("data")?.optJSONArray("route"))
          ?: parseRouteArray(sched.optJSONArray("stations"))
      } catch (_: Exception) {
      }
    }

    val currentLoc = parseCurrentLocation(payload.optJSONObject("currentLocation"))

    val chosenRoute = when {
      !incomingRoute.isNullOrEmpty() -> enrichRouteFromStationDb(incomingRoute, stationDb)
      cachedRoute.isNotEmpty() -> enrichRouteFromStationDb(cachedRoute, stationDb)
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

    val trainName = payload.optStringOrNull("trainName") ?: root.optStringOrNull("trainName")
    val source = payload.optStringOrNull("source") ?: root.optStringOrNull("source")

    LiveTrainPayload(
      trainName = trainName,
      source = source,
      currentLocation = currentLoc,
      route = chosenRoute,
    )
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
