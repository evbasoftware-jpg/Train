package com.example.data

import com.example.model.RouteStation
import com.example.model.StationDbEntry
import com.example.model.TrainBetweenItem
import com.example.util.GeoMath
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SheetSyncManager(private val client: OkHttpClient) {
  companion object {
    private val JSON_MEDIA = "text/plain;charset=utf-8".toMediaType()

    /**
     * Formats consecutive unique stoppages separated by ", " for Train.Train_Stops column.
     * Example: "HWH, BWN, BHP, AMP, SNT, RPH"
     */
    fun formatTrainStopsCsv(route: List<RouteStation>): String {
      if (route.isEmpty()) return ""
      val tokens = ArrayList<String>(route.size)
      var prevKey = ""
      for (st in route) {
        val tok = st.stationCode.trim().uppercase(Locale.US)
          .ifBlank { st.stationName.trim() }
        if (tok.isBlank()) continue
        val key = tok.uppercase(Locale.US)
        if (key != prevKey) {
          tokens.add(tok)
          prevKey = key
        }
      }
      return tokens.joinToString(", ")
    }
  }

  private val sentSignatures = ConcurrentHashMap<String, String>()

  suspend fun syncResolvedTrain(
    trainNo: String,
    trainName: String?,
    existingEntry: SheetTrainEntry?,
    existingScheduleRows: List<SheetScheduleRow>,
    resolvedRoute: List<RouteStation>,
    stationDb: List<StationDbEntry>,
    forceSync: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    val cleanTrainNo = trainNo.filter { it.isDigit() }
    if (cleanTrainNo.isBlank() || resolvedRoute.isEmpty()) return@withContext

    val trainStopsCsv = formatTrainStopsCsv(resolvedRoute)
    val dbByCode = stationDb.associateBy { it.code.trim().uppercase(Locale.US) }

    val enrichedRoute = resolvedRoute.mapIndexed { idx, st ->
      val code = st.stationCode.trim().uppercase(Locale.US)
      val db = if (code.isNotEmpty()) dbByCode[code] else null
      st.copy(
        stationCode = code,
        stationName = st.stationName.ifBlank { db?.name ?: code },
        sequence = idx + 1,
        lat = st.lat ?: db?.lat,
        lon = st.lon ?: db?.lon,
      )
    }

    val existingStopsNorm = existingEntry?.trainStopsRaw?.trim().orEmpty()
    val hasNewOrUpdatedTrain = existingEntry == null ||
      (trainStopsCsv.isNotBlank() && existingStopsNorm != trainStopsCsv) ||
      (existingEntry.trainName.isBlank() && !trainName.isNullOrBlank()) ||
      (existingEntry.routePointsCount < enrichedRoute.size) ||
      (existingEntry.fromCode.isBlank() && enrichedRoute.first().stationCode.isNotBlank()) ||
      (existingEntry.toCode.isBlank() && enrichedRoute.last().stationCode.isNotBlank())

    val existingSchedByCode = existingScheduleRows.associateBy { it.stationCode.trim().uppercase(Locale.US) }
    val hasNewOrUpdatedSched = enrichedRoute.size > existingScheduleRows.size ||
      enrichedRoute.any { st ->
        val ex = existingSchedByCode[st.stationCode]
        if (ex == null) {
          true
        } else {
          val newArr = normalizeTimeOrNull(st.arrival ?: st.scheduledTimeRaw)
          val newDep = normalizeTimeOrNull(st.departure ?: st.scheduledTimeRaw)
          val exArrEmpty = ex.arrivalRaw.isBlank() || ex.arrivalRaw.equals("None", ignoreCase = true)
          val exDepEmpty = ex.departureRaw.isBlank() || ex.departureRaw.equals("None", ignoreCase = true)
          (newArr != null && exArrEmpty) || (newDep != null && exDepEmpty)
        }
      }

    // Collect stations whose code is known and either missing in Station sheet or newly enriched with coords
    val stationPayloads = ArrayList<JSONObject>()
    val seenCodes = HashSet<String>()
    for (st in enrichedRoute) {
      val code = st.stationCode
      if (code.isBlank() || !seenCodes.add(code)) continue
      val existingDb = dbByCode[code]
      val lat = st.lat ?: existingDb?.lat
      val lon = st.lon ?: existingDb?.lon
      val name = st.stationName.ifBlank { existingDb?.name ?: code }
      if (existingDb == null || existingDb.lat == null || existingDb.lon == null || (lat != null && lon != null)) {
        stationPayloads.add(
          JSONObject().apply {
            put("stationCode", code)
            put("stationName", name)
            if (lat != null && lon != null) {
              put("latitude", lat)
              put("longitude", lon)
            }
          },
        )
      }
    }

    if (!forceSync && !hasNewOrUpdatedTrain && !hasNewOrUpdatedSched && stationPayloads.isEmpty()) {
      return@withContext
    }

    val sig = "${cleanTrainNo}|${enrichedRoute.size}|$trainStopsCsv|${trainName.orEmpty()}"
    val syncKey = "train:$cleanTrainNo"
    if (!forceSync && sentSignatures[syncKey] == sig) return@withContext
    sentSignatures[syncKey] = sig

    val first = enrichedRoute.first()
    val last = enrichedRoute.last()
    val resolvedName = trainName?.takeIf { it.isNotBlank() }
      ?: existingEntry?.trainName?.takeIf { it.isNotBlank() }
      ?: ""
    val depStart = normalizeTimeOrNull(first.departure ?: first.scheduledTimeRaw)
      ?: existingEntry?.departureRaw?.takeIf { it.isNotBlank() && !it.equals("None", true) }
      ?: ""
    val arrEnd = normalizeTimeOrNull(last.arrival ?: last.scheduledTimeRaw)
      ?: existingEntry?.arrivalRaw?.takeIf { it.isNotBlank() && !it.equals("None", true) }
      ?: ""
    val totalDistKm = existingEntry?.distanceKm?.takeIf { it > 0.0 } ?: computeTotalDistanceKm(enrichedRoute)

    val trainObj = JSONObject().apply {
      put("trainNumber", cleanTrainNo)
      put("trainName", resolvedName)
      put("fromCode", first.stationCode.ifBlank { existingEntry?.fromCode.orEmpty() })
      put("fromName", first.stationName.ifBlank { existingEntry?.fromName.orEmpty() })
      put("departure", depStart)
      put("toCode", last.stationCode.ifBlank { existingEntry?.toCode.orEmpty() })
      put("toName", last.stationName.ifBlank { existingEntry?.toName.orEmpty() })
      put("arrival", arrEnd)
      put("distanceKm", if (totalDistKm > 0.0) Math.round(totalDistKm * 10.0) / 10.0 else 0)
      put("routePointsCount", maxOf(enrichedRoute.size, existingEntry?.routePointsCount ?: 0))
      put("trainStops", trainStopsCsv)
    }

    val schedArr = JSONArray()
    var day = 1
    var prevMin: Int? = null
    enrichedRoute.forEachIndexed { idx, st ->
      val ex = existingSchedByCode[st.stationCode]
      val arr = normalizeTimeOrNull(st.arrival ?: st.scheduledTimeRaw)
        ?: ex?.arrivalRaw?.takeIf { it.isNotBlank() && !it.equals("None", true) }
        ?: "None"
      val dep = normalizeTimeOrNull(st.departure ?: st.scheduledTimeRaw)
        ?: ex?.departureRaw?.takeIf { it.isNotBlank() && !it.equals("None", true) }
        ?: "None"
      val minNow = parseMinutes(arr) ?: parseMinutes(dep)
      if (prevMin != null && minNow != null && minNow + 120 < prevMin!!) {
        day++
      }
      if (minNow != null) prevMin = minNow
      val resolvedDay = ex?.day ?: day
      schedArr.put(
        JSONObject().apply {
          put("sequence", idx + 1)
          put("trainNumber", cleanTrainNo)
          put("trainName", resolvedName)
          put("stationCode", st.stationCode)
          put("stationName", st.stationName)
          put("arrival", arr)
          put("departure", dep)
          put("day", resolvedDay)
        },
      )
    }

    val stationsArr = JSONArray().apply {
      stationPayloads.forEach { put(it) }
    }

    val payload = JSONObject().apply {
      put("action", "syncSheetData")
      put("sheetId", SheetTrainRepository.SHEET_ID)
      put("train", trainObj)
      put("stations", stationsArr)
      put("schedule", schedArr)
    }

    postSyncPayload(payload)
  }

  suspend fun syncBetweenResults(
    fromCode: String,
    toCode: String,
    items: List<TrainBetweenItem>,
    stationDb: List<StationDbEntry>,
  ) = withContext(Dispatchers.IO) {
    if (items.isEmpty()) return@withContext
    val fCode = fromCode.trim().uppercase(Locale.US)
    val tCode = toCode.trim().uppercase(Locale.US)
    val sig = "$fCode->$tCode:${items.joinToString(",") { it.trainNumber }}"
    val syncKey = "between:$fCode:$tCode"
    if (sentSignatures[syncKey] == sig) return@withContext
    sentSignatures[syncKey] = sig

    val dbByCode = stationDb.associateBy { it.code.trim().uppercase(Locale.US) }
    val fromDb = dbByCode[fCode]
    val toDb = dbByCode[tCode]

    val stationsArr = JSONArray().apply {
      listOfNotNull(
        fromDb?.let { st ->
          JSONObject().apply {
            put("stationCode", fCode)
            put("stationName", st.name)
            st.lat?.let { put("latitude", it) }
            st.lon?.let { put("longitude", it) }
          }
        },
        toDb?.let { st ->
          JSONObject().apply {
            put("stationCode", tCode)
            put("stationName", st.name)
            st.lat?.let { put("latitude", it) }
            st.lon?.let { put("longitude", it) }
          }
        },
      ).forEach { put(it) }
    }

    val trainsArr = JSONArray().apply {
      items.forEach { item ->
        val num = item.trainNumber.filter { it.isDigit() }
        if (num.isNotBlank()) {
          put(
            JSONObject().apply {
              put("trainNumber", num)
              put("trainName", item.trainName)
              put("fromCode", fCode)
              put("fromName", fromDb?.name ?: fCode)
              put("departure", item.departure.takeIf { it != "--:--" } ?: "")
              put("toCode", tCode)
              put("toName", toDb?.name ?: tCode)
              put("arrival", item.arrival.takeIf { it != "--:--" } ?: "")
              put("distanceKm", item.distanceKm)
              put("trainStops", "$fCode, $tCode")
            },
          )
        }
      }
    }

    if (trainsArr.length() == 0) return@withContext

    val payload = JSONObject().apply {
      put("action", "syncSheetData")
      put("sheetId", SheetTrainRepository.SHEET_ID)
      put("betweenTrains", trainsArr)
      put("stations", stationsArr)
    }

    postSyncPayload(payload)
  }

  suspend fun syncLiveObservation(
    trainNo: String,
    trainName: String?,
    observedStationCode: String,
    observedStationName: String,
    observedTimeHHmm: String?,
    route: List<RouteStation>,
    stationDb: List<StationDbEntry>,
  ) = withContext(Dispatchers.IO) {
    val cleanTrainNo = trainNo.filter { it.isDigit() }
    val stCode = observedStationCode.trim().uppercase(Locale.US)
    if (cleanTrainNo.isBlank() || (stCode.isBlank() && observedStationName.isBlank())) return@withContext

    val syncKey = "obs:$cleanTrainNo:$stCode"
    val sig = "${observedTimeHHmm.orEmpty()}|${route.size}"
    if (sentSignatures[syncKey] == sig) return@withContext
    sentSignatures[syncKey] = sig

    val stopsCsv = formatTrainStopsCsv(route)
    val dbHit = if (stCode.isNotEmpty()) stationDb.firstOrNull { it.code.equals(stCode, true) } else null

    val payload = JSONObject().apply {
      put("action", "syncSheetData")
      put("sheetId", SheetTrainRepository.SHEET_ID)
      if (stopsCsv.isNotBlank() && route.size >= 2) {
        put(
          "train",
          JSONObject().apply {
            put("trainNumber", cleanTrainNo)
            put("trainName", trainName.orEmpty())
            put("fromCode", route.first().stationCode)
            put("fromName", route.first().stationName)
            put("toCode", route.last().stationCode)
            put("toName", route.last().stationName)
            put("routePointsCount", route.size)
            put("trainStops", stopsCsv)
          },
        )
      }
      if (stCode.isNotBlank()) {
        put(
          "stations",
          JSONArray().put(
            JSONObject().apply {
              put("stationCode", stCode)
              put("stationName", observedStationName.ifBlank { dbHit?.name ?: stCode })
              dbHit?.lat?.let { put("latitude", it) }
              dbHit?.lon?.let { put("longitude", it) }
            },
          ),
        )
        if (!observedTimeHHmm.isNullOrBlank()) {
          val idx = route.indexOfFirst { it.stationCode.equals(stCode, true) }
          put(
            "schedule",
            JSONArray().put(
              JSONObject().apply {
                put("sequence", if (idx >= 0) idx + 1 else 1)
                put("trainNumber", cleanTrainNo)
                put("trainName", trainName.orEmpty())
                put("stationCode", stCode)
                put("stationName", observedStationName.ifBlank { dbHit?.name ?: stCode })
                put("arrival", observedTimeHHmm)
                put("departure", observedTimeHHmm)
                put("day", 1)
              },
            ),
          )
        }
      }
    }

    postSyncPayload(payload)
  }

  private fun postSyncPayload(payload: JSONObject) {
    try {
      val req = Request.Builder()
        .url(TrainRepository.API_URL)
        .post(payload.toString().toRequestBody(JSON_MEDIA))
        .build()
      client.newCall(req).execute().close()
    } catch (_: Exception) {
    }
  }

  private fun normalizeTimeOrNull(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s == "--:--" || s.equals("None", true)) return null
    val m = Regex("""^(\d{1,2}):(\d{2})""").find(s) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || min !in 0..59) return null
    return String.format(Locale.US, "%02d:%02d", h, min)
  }

  private fun parseMinutes(hhmm: String): Int? {
    val m = Regex("""^(\d{2}):(\d{2})$""").find(hhmm) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
  }

  private fun computeTotalDistanceKm(route: List<RouteStation>): Double {
    var total = 0.0
    var prev: RouteStation? = null
    for (s in route) {
      if (s.lat != null && s.lon != null) {
        prev?.let { p ->
          if (p.lat != null && p.lon != null) total += GeoMath.haversineKm(p.lat, p.lon, s.lat, s.lon) * 1.2
        }
        prev = s
      }
    }
    return total
  }
}
