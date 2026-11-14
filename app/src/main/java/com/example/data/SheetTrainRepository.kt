package com.example.data

import com.example.model.CurrentLocationDto
import com.example.model.RouteStation
import com.example.model.StationDbEntry
import com.example.model.TrainBetweenItem
import com.example.util.GeoMath
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

data class SheetTrainEntry(
  val trainNumber: String,
  val trainName: String,
  val fromCode: String,
  val fromName: String,
  val departureRaw: String,
  val toCode: String,
  val toName: String,
  val arrivalRaw: String,
  val distanceKm: Double,
  val routePointsCount: Int,
  val trainStopsRaw: String,
  val stopTokens: List<String>,
)

data class SheetScheduleRow(
  val scheduleId: Long?,
  val trainNumber: String,
  val trainName: String,
  val stationCode: String,
  val stationName: String,
  val arrivalRaw: String,
  val departureRaw: String,
  val day: Int?,
  val rowOrder: Int,
)

data class SheetTrainLookup(
  val entry: SheetTrainEntry?,
  val route: List<RouteStation>,
  val scheduleRows: List<SheetScheduleRow>,
  val isComplete: Boolean,
  val needsTrainStopsFill: Boolean,
)

class SheetTrainRepository(private val cacheDir: File? = null) {
  companion object {
    const val SHEET_ID = AdRepository.ADS_SHEET_ID
    const val BASE_GVIZ_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/gviz/tq?tqx=out:csv"
    const val TRAIN_SHEET_CSV_URL = "$BASE_GVIZ_URL&sheet=Train"
    const val STATION_SHEET_CSV_URL = "$BASE_GVIZ_URL&sheet=Station"
    const val SCHEDULE_SHEET_CSV_URL = "$BASE_GVIZ_URL&sheet=Schedule"

    private const val STATION_CACHE_FILE = "station_sheet_cache.csv"
    private const val STATION_CACHE_TTL_MS = 5 * 60_000L

    private val TIME_TOKEN_REGEX = Regex("""(\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AaPp][Mm])?)""")
    private val PAREN_CODE_REGEX = Regex("""\(\s*([A-Za-z0-9]+)\s*\)""")
    private val SPLIT_STOPS_REGEX = Regex("""[,;|\n\r]+|->|→""")
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  @Volatile
  private var cachedStations: List<StationDbEntry> = emptyList()

  @Volatile
  private var lastStationsFetchMs: Long = 0L

  suspend fun loadCachedSheetStations(): List<StationDbEntry> = withContext(Dispatchers.IO) {
    if (cachedStations.isNotEmpty()) return@withContext cachedStations
    val csv = readCacheFile(STATION_CACHE_FILE) ?: return@withContext emptyList()
    parseStationsCsv(csv).also { if (it.isNotEmpty()) cachedStations = it }
  }

  suspend fun fetchSheetStations(forceRefresh: Boolean = false): List<StationDbEntry> =
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      if (!forceRefresh && cachedStations.isNotEmpty() && now - lastStationsFetchMs < STATION_CACHE_TTL_MS) {
        return@withContext cachedStations
      }
      val file = cacheDir?.let { File(it, STATION_CACHE_FILE) }
      if (!forceRefresh && file != null && file.exists() && now - file.lastModified() < STATION_CACHE_TTL_MS) {
        val fromDisk = loadCachedSheetStations()
        if (fromDisk.isNotEmpty()) {
          lastStationsFetchMs = now
          return@withContext fromDisk
        }
      }
      val body = downloadCsv(STATION_SHEET_CSV_URL)
      if (body != null && hasStationHeader(body)) {
        val parsed = parseStationsCsv(body)
        writeCacheFile(STATION_CACHE_FILE, body)
        cachedStations = parsed
        lastStationsFetchMs = now
        return@withContext parsed
      }
      loadCachedSheetStations()
    }

  suspend fun searchTrainsBetweenFromSheet(
    fromCodeOrName: String,
    toCodeOrName: String,
    stationDb: List<StationDbEntry>,
  ): List<TrainBetweenItem> = coroutineScope {
    val sheetStations = fetchSheetStations()
    val effectiveDb = mergeStationLists(sheetStations, stationDb)
    val fromRes = GeoMath.resolveStation(fromCodeOrName, effectiveDb)
    val toRes = GeoMath.resolveStation(toCodeOrName, effectiveDb)
    val fromCode = sanitizeTqIdent((fromRes?.code ?: GeoMath.extractStationCode(fromCodeOrName, effectiveDb)).uppercase(Locale.US))
    val toCode = sanitizeTqIdent((toRes?.code ?: GeoMath.extractStationCode(toCodeOrName, effectiveDb)).uppercase(Locale.US))
    if (fromCode.length < 2 || toCode.length < 2 || fromCode == toCode) return@coroutineScope emptyList()

    val trainDeferred = async(Dispatchers.IO) {
      val tq = "select * where (E = '$fromCode' and H = '$toCode') or (V contains '$fromCode' and V contains '$toCode')"
      downloadGvizQuery("Train", tq)?.let(::parseTrainsCsv).orEmpty()
    }
    val schedDeferred = async(Dispatchers.IO) {
      val tq = "select A, B, C, D, E, F, G, H where D = '$fromCode' or D = '$toCode'"
      downloadGvizQuery("Schedule", tq)?.let(::parseScheduleCsv).orEmpty()
    }

    val trainEntries = trainDeferred.await()
    val schedRows = schedDeferred.await()
    combineBetweenMatches(
      fromCode = fromCode,
      toCode = toCode,
      trainEntries = trainEntries,
      scheduleRows = schedRows,
      stationDb = effectiveDb,
    )
  }

  suspend fun findTrainRouteFromSheet(
    trainNo: String,
    stationDb: List<StationDbEntry>,
  ): SheetTrainLookup? = coroutineScope {
    val cleanDigits = trainNo.filter { it.isDigit() }
    if (cleanDigits.isEmpty()) return@coroutineScope null
    val variants = buildTrainNumberVariants(cleanDigits)

    val sheetStations = fetchSheetStations()
    val effectiveDb = mergeStationLists(sheetStations, stationDb)

    val trainDeferred = async(Dispatchers.IO) {
      val cond = variants.joinToString(" or ") { v ->
        if (v.all(Char::isDigit)) "A = ${v.toLongOrNull() ?: 0} or A = '$v'" else "A = '$v'"
      }
      downloadGvizQuery("Train", "select * where $cond")?.let(::parseTrainsCsv).orEmpty()
    }
    val schedDeferred = async(Dispatchers.IO) {
      val cond = variants.joinToString(" or ") { v ->
        if (v.all(Char::isDigit)) "B = ${v.toLongOrNull() ?: 0} or B = '$v'" else "B = '$v'"
      }
      downloadGvizQuery("Schedule", "select A, B, C, D, E, F, G, H where $cond")?.let(::parseScheduleCsv).orEmpty()
    }

    val entry = trainDeferred.await().firstOrNull()
    val schedRows = sortScheduleRows(schedDeferred.await())
    if (entry == null && schedRows.isEmpty()) return@coroutineScope null

    val routeFromSched = buildRouteFromScheduleRows(schedRows, effectiveDb)
    val routeFromTrain = entry?.let { buildRouteFromTrainEntry(it, effectiveDb) }.orEmpty()

    val chosenRoute = when {
      routeFromSched.size >= routeFromTrain.size && routeFromSched.isNotEmpty() -> routeFromSched
      routeFromTrain.isNotEmpty() -> routeFromTrain
      else -> emptyList()
    }

    val completeness = isSheetRouteComplete(entry, chosenRoute)
    val needsStops = entry == null || entry.trainStopsRaw.isBlank()
    SheetTrainLookup(
      entry = entry,
      route = chosenRoute,
      scheduleRows = schedRows,
      isComplete = completeness,
      needsTrainStopsFill = needsStops,
    )
  }

  fun combineBetweenMatches(
    fromCode: String,
    toCode: String,
    trainEntries: List<SheetTrainEntry>,
    scheduleRows: List<SheetScheduleRow>,
    stationDb: List<StationDbEntry>,
  ): List<TrainBetweenItem> {
    val byTrain = LinkedHashMap<String, TrainBetweenItem>()

    // 1) Matches from Schedule sheet (handles all intermediate stoppages)
    val grouped = scheduleRows
      .filter { it.trainNumber.isNotBlank() }
      .groupBy { normalizeTrainKey(it.trainNumber) }

    for ((_, rows) in grouped) {
      val fromRow = rows.firstOrNull { it.stationCode.equals(fromCode, ignoreCase = true) } ?: continue
      val toRow = rows.firstOrNull { it.stationCode.equals(toCode, ignoreCase = true) } ?: continue
      if (!isChronologicallyBefore(fromRow, toRow)) continue

      val dep = formatSheetTime(fromRow.departureRaw) ?: formatSheetTime(fromRow.arrivalRaw) ?: "--:--"
      val arr = formatSheetTime(toRow.arrivalRaw) ?: formatSheetTime(toRow.departureRaw) ?: "--:--"
      val fromDb = stationDb.firstOrNull { it.code.equals(fromCode, ignoreCase = true) }
      val toDb = stationDb.firstOrNull { it.code.equals(toCode, ignoreCase = true) }
      val distKm = if (fromDb?.lat != null && fromDb.lon != null && toDb?.lat != null && toDb.lon != null) {
        GeoMath.haversineKm(fromDb.lat, fromDb.lon, toDb.lat, toDb.lon) * 1.2
      } else {
        0.0
      }

      val key = normalizeTrainKey(fromRow.trainNumber)
      byTrain[key] = TrainBetweenItem(
        trainNumber = fromRow.trainNumber,
        trainName = fromRow.trainName.ifBlank { toRow.trainName },
        departure = dep,
        arrival = arr,
        distanceKm = distKm,
      )
    }

    // 2) Matches from Train sheet (direct From_Station_Code -> To_Station_Code & Train_Stops)
    for (entry in trainEntries) {
      val key = normalizeTrainKey(entry.trainNumber)
      val route = buildRouteFromTrainEntry(entry, stationDb)
      val fromIdx = route.indexOfFirst { it.stationCode.equals(fromCode, ignoreCase = true) }
      val toIdx = if (fromIdx >= 0) {
        (fromIdx + 1 until route.size).firstOrNull { route[it].stationCode.equals(toCode, ignoreCase = true) } ?: -1
      } else {
        -1
      }

      if (fromIdx >= 0 && toIdx > fromIdx) {
        val dep = formatSheetTime(route[fromIdx].departure ?: route[fromIdx].scheduledTimeRaw.orEmpty())
          ?: (if (fromIdx == 0) formatSheetTime(entry.departureRaw) else null)
          ?: "--:--"
        val arr = formatSheetTime(route[toIdx].arrival ?: route[toIdx].scheduledTimeRaw.orEmpty())
          ?: (if (toIdx == route.lastIndex) formatSheetTime(entry.arrivalRaw) else null)
          ?: "--:--"
        val segDist = computeRouteSegmentDistanceKm(route, fromIdx, toIdx)
        val dist = if (fromIdx == 0 && toIdx == route.lastIndex && entry.distanceKm > 0.0) {
          entry.distanceKm
        } else if (segDist > 0.0) {
          segDist
        } else {
          byTrain[key]?.distanceKm ?: 0.0
        }
        val existing = byTrain[key]
        byTrain[key] = TrainBetweenItem(
          trainNumber = entry.trainNumber,
          trainName = entry.trainName.ifBlank { existing?.trainName.orEmpty() },
          departure = if (dep != "--:--") dep else existing?.departure ?: "--:--",
          arrival = if (arr != "--:--") arr else existing?.arrival ?: "--:--",
          distanceKm = if (dist > 0.0) dist else existing?.distanceKm ?: 0.0,
        )
      } else if (byTrain.containsKey(key) && entry.distanceKm > 0.0) {
        val ex = byTrain.getValue(key)
        if (ex.trainName.isBlank() && entry.trainName.isNotBlank()) {
          byTrain[key] = ex.copy(trainName = entry.trainName)
        }
      }
    }

    return byTrain.values.toList()
  }

  fun mergeStationLists(
    primary: List<StationDbEntry>,
    secondary: List<StationDbEntry>,
  ): List<StationDbEntry> {
    if (primary.isEmpty()) return secondary
    val secByCode = HashMap<String, StationDbEntry>(secondary.size)
    secondary.forEach { st ->
      val c = st.code.trim().uppercase(Locale.US)
      if (c.isNotEmpty()) secByCode.putIfAbsent(c, st)
    }
    val seenCodes = HashSet<String>(primary.size + secondary.size)
    val out = ArrayList<StationDbEntry>(primary.size + secondary.size)
    primary.forEach { st ->
      val c = st.code.trim().uppercase(Locale.US)
      val backup = secByCode[c]
      val enriched = if (st.lat == null || st.lon == null) {
        st.copy(
          lat = st.lat ?: backup?.lat,
          lon = st.lon ?: backup?.lon,
          name = st.name.ifBlank { backup?.name ?: c },
        )
      } else {
        st
      }
      if (enriched.lat == null && (c.startsWith("XX-") || c.startsWith("YY-"))) {
        return@forEach
      }
      out.add(enriched)
      if (c.isNotEmpty()) seenCodes.add(c)
    }
    secondary.forEach { st ->
      val c = st.code.trim().uppercase(Locale.US)
      if (c.isNotEmpty() && !seenCodes.contains(c)) {
        seenCodes.add(c)
        out.add(st)
      }
    }
    return out
  }

  fun buildRouteFromTrainEntry(
    entry: SheetTrainEntry,
    stationDb: List<StationDbEntry>,
  ): List<RouteStation> {
    val startT = formatSheetTime(entry.departureRaw)
    val endT = formatSheetTime(entry.arrivalRaw)
    val fromStop = entry.fromCode.takeIf { it.isNotBlank() }?.let {
      parseStopToken("${entry.fromName} ($it)", stationDb, startT)
    }
    val toStop = entry.toCode.takeIf { it.isNotBlank() }?.let {
      parseStopToken("${entry.toName} ($it)", stationDb, endT)
    }
    val midStops = entry.stopTokens.mapNotNull { parseStopToken(it, stationDb, null) }

    val combined = ArrayList<RouteStation>(midStops.size + 2)
    if (midStops.isEmpty()) {
      fromStop?.let(combined::add)
      toStop?.let { if (combined.isEmpty() || !sameStation(combined.last(), it)) combined.add(it) }
    } else {
      combined.addAll(midStops)
      if (fromStop != null && !sameStation(combined.first(), fromStop)) combined.add(0, fromStop)
      if (toStop != null && !sameStation(combined.last(), toStop)) combined.add(toStop)
    }
    return combined.mapIndexed { idx, st -> st.copy(sequence = idx + 1) }
  }

  fun buildRouteFromScheduleRows(
    rows: List<SheetScheduleRow>,
    stationDb: List<StationDbEntry>,
  ): List<RouteStation> {
    if (rows.isEmpty()) return emptyList()
    val sorted = sortScheduleRows(rows)
    val dbByCode = stationDb.associateBy { it.code.uppercase(Locale.US) }
    return sorted.mapIndexed { idx, r ->
      val code = r.stationCode.trim().uppercase(Locale.US)
      val dbHit = dbByCode[code]
      val arr = formatSheetTime(r.arrivalRaw)
      val dep = formatSheetTime(r.departureRaw)
      val sched = arr ?: dep ?: "--:--"
      RouteStation(
        stationCode = code,
        stationName = r.stationName.ifBlank { dbHit?.name ?: code },
        sequence = idx + 1,
        lat = dbHit?.lat,
        lon = dbHit?.lon,
        scheduledTimeRaw = sched,
        arrival = arr ?: sched,
        departure = dep ?: sched,
      )
    }
  }

  fun isSheetRouteComplete(entry: SheetTrainEntry?, route: List<RouteStation>): Boolean {
    if (route.size < 2) return false
    if (entry != null) {
      if (entry.toCode.isNotBlank()) {
        val lastCode = route.last().stationCode
        if (!lastCode.equals(entry.toCode, ignoreCase = true)) return false
      }
      if (entry.routePointsCount > 4 && route.size < (entry.routePointsCount / 3)) {
        return false
      }
      if (route.size == 2 && entry.trainStopsRaw.isBlank() && entry.routePointsCount != 2) {
        return false
      }
    }
    return true
  }

  fun estimateLocationFromSheetSchedule(
    entry: SheetTrainEntry?,
    route: List<RouteStation>,
  ): CurrentLocationDto? {
    if (route.isEmpty()) return null
    val firstTime = parseSheetTimeMinutes(entry?.departureRaw ?: route.first().scheduledTimeRaw.orEmpty())
    val lastTime = parseSheetTimeMinutes(entry?.arrivalRaw ?: route.last().scheduledTimeRaw.orEmpty())
    val cal = Calendar.getInstance()
    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    var prog = 0.0
    if (firstTime != null && lastTime != null && route.size >= 2) {
      val dur = if (lastTime >= firstTime) lastTime - firstTime else (1440 - firstTime) + lastTime
      if (dur > 0) {
        val elp = if (lastTime >= firstTime) nowMin - firstTime else if (nowMin >= firstTime) nowMin - firstTime else (1440 - firstTime) + nowMin
        if (elp in 0..dur) prog = (elp.toDouble() / dur).coerceIn(0.0, 1.0)
      }
    }
    val idx = (prog * (route.size - 1)).toInt().coerceIn(0, route.lastIndex)
    val cur = route[idx]
    val next = route.getOrNull(idx + 1)
    return CurrentLocationDto(
      stationCode = cur.stationCode.ifBlank { null },
      stationName = cur.stationName.ifBlank { cur.stationCode },
      sequence = idx + 1,
      lat = cur.lat,
      lon = cur.lon,
      speedKmh = 0.0,
      bearingDegrees = null,
      segmentProgress = if (route.size >= 2) ((prog * (route.size - 1)) - idx).coerceIn(0.0, 1.0) else 0.0,
      nextStationName = next?.stationName?.ifBlank { next.stationCode },
    )
  }

  fun parseStationsCsv(csvText: String): List<StationDbEntry> {
    val rows = parseRfc4180Csv(csvText)
    if (rows.size < 2) return emptyList()
    val hdr = headerIndexMap(rows.first())
    val out = ArrayList<StationDbEntry>(rows.size)
    for (i in 1 until rows.size) {
      val r = rows[i]
      if (r.all { it.isBlank() }) continue
      val code = colVal(r, hdr, "stationcode", "code").uppercase(Locale.US)
      val name = colVal(r, hdr, "stationname", "name")
      if (code.isBlank() && name.isBlank()) continue
      val lat = colVal(r, hdr, "latitude", "lat").replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
      val lon = colVal(r, hdr, "longitude", "lon", "lng").replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
      out.add(StationDbEntry(name = name.ifBlank { code }, code = code, lat = lat, lon = lon))
    }
    return out
  }

  fun parseTrainsCsv(csvText: String): List<SheetTrainEntry> {
    val rows = parseRfc4180Csv(csvText)
    if (rows.size < 2) return emptyList()
    val hdr = headerIndexMap(rows.first())
    val stopsIdx = hdr["trainstops"] ?: hdr["stopstations"] ?: hdr["stops"]
    val out = ArrayList<SheetTrainEntry>(rows.size)
    for (i in 1 until rows.size) {
      val r = rows[i]
      if (r.all { it.isBlank() }) continue
      val num = colVal(r, hdr, "trainnumber", "trainno", "number").filter { it.isDigit() }
      if (num.isBlank()) continue
      val stopsTokens = ArrayList<String>()
      var rawStopsStr = ""
      if (stopsIdx != null && stopsIdx < r.size) {
        rawStopsStr = r[stopsIdx].trim()
        for (c in stopsIdx until r.size) {
          val cell = r[c].trim()
          if (cell.isEmpty()) continue
          SPLIT_STOPS_REGEX.split(cell).forEach { part ->
            part.trim().takeIf { it.isNotEmpty() }?.let(stopsTokens::add)
          }
        }
      }
      out.add(
        SheetTrainEntry(
          trainNumber = num,
          trainName = colVal(r, hdr, "trainname", "name"),
          fromCode = colVal(r, hdr, "fromstationcode", "fromcode", "from").uppercase(Locale.US),
          fromName = colVal(r, hdr, "fromstationname", "fromname"),
          departureRaw = colVal(r, hdr, "departure", "starttime"),
          toCode = colVal(r, hdr, "tostationcode", "tocode", "to").uppercase(Locale.US),
          toName = colVal(r, hdr, "tostationname", "toname"),
          arrivalRaw = colVal(r, hdr, "arrival", "reachtime"),
          distanceKm = colVal(r, hdr, "distancekm", "distance").toDoubleOrNull() ?: 0.0,
          routePointsCount = colVal(r, hdr, "routepointscount").toIntOrNull() ?: 0,
          trainStopsRaw = rawStopsStr,
          stopTokens = stopsTokens,
        ),
      )
    }
    return out
  }

  fun parseScheduleCsv(csvText: String): List<SheetScheduleRow> {
    val rows = parseRfc4180Csv(csvText)
    if (rows.size < 2) return emptyList()
    val hdr = headerIndexMap(rows.first())
    val out = ArrayList<SheetScheduleRow>(rows.size)
    for (i in 1 until rows.size) {
      val r = rows[i]
      if (r.all { it.isBlank() }) continue
      val trainNo = colVal(r, hdr, "trainnumber", "trainno", "number").filter { it.isDigit() }
      val stCode = colVal(r, hdr, "stationcode", "code").uppercase(Locale.US)
      if (trainNo.isBlank() && stCode.isBlank()) continue
      out.add(
        SheetScheduleRow(
          scheduleId = colVal(r, hdr, "scheduleid", "id").toLongOrNull(),
          trainNumber = trainNo,
          trainName = colVal(r, hdr, "trainname", "name"),
          stationCode = stCode,
          stationName = colVal(r, hdr, "stationname", "name"),
          arrivalRaw = colVal(r, hdr, "arrival"),
          departureRaw = colVal(r, hdr, "departure"),
          day = colVal(r, hdr, "day").toIntOrNull(),
          rowOrder = i,
        ),
      )
    }
    return out
  }

  private fun sortScheduleRows(rows: List<SheetScheduleRow>): List<SheetScheduleRow> =
    rows.sortedWith(
      compareBy<SheetScheduleRow>(
        { it.day ?: Int.MAX_VALUE },
        { parseSheetTimeMinutes(it.arrivalRaw) ?: parseSheetTimeMinutes(it.departureRaw) ?: Int.MAX_VALUE },
        { it.scheduleId ?: Long.MAX_VALUE },
        { it.rowOrder },
      ),
    )

  private fun isChronologicallyBefore(from: SheetScheduleRow, to: SheetScheduleRow): Boolean {
    val fMin = (from.day?.let { (it - 1) * 1440 } ?: 0) +
      (parseSheetTimeMinutes(from.departureRaw) ?: parseSheetTimeMinutes(from.arrivalRaw) ?: -1)
    val tMin = (to.day?.let { (it - 1) * 1440 } ?: 0) +
      (parseSheetTimeMinutes(to.arrivalRaw) ?: parseSheetTimeMinutes(to.departureRaw) ?: -1)
    if (fMin >= 0 && tMin >= 0 && fMin != tMin) return tMin > fMin
    if (from.scheduleId != null && to.scheduleId != null && from.scheduleId != to.scheduleId) {
      return to.scheduleId > from.scheduleId
    }
    return to.rowOrder > from.rowOrder
  }

  private fun parseStopToken(raw: String, stationDb: List<StationDbEntry>, fallbackTime: String?): RouteStation? {
    var working = raw.trim()
    if (working.isEmpty()) return null
    var embeddedTime: String? = null
    TIME_TOKEN_REGEX.find(working)?.let { m ->
      embeddedTime = formatSheetTime(m.groupValues[1])
      working = working.removeRange(m.range).replace(Regex("""\(\s*\)"""), "").trim(' ', '-', '|', ':')
    }
    if (working.isEmpty()) return null
    val parenCode = PAREN_CODE_REGEX.find(working)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
    val noParen = working.replace(PAREN_CODE_REGEX, "").trim(' ', '-', ':', '|')
    val resolved = GeoMath.resolveStation(working, stationDb)
      ?: parenCode?.let { pc -> stationDb.firstOrNull { it.code.equals(pc, ignoreCase = true) } }
      ?: noParen.takeIf { it.isNotEmpty() }?.let { GeoMath.resolveStation(it, stationDb) }
    val code = resolved?.code?.uppercase(Locale.US) ?: parenCode
      ?: (if (Regex("""^[A-Za-z0-9]{1,6}$""").matches(working)) working.uppercase(Locale.US) else "")
    val name = resolved?.name?.ifBlank { noParen.ifBlank { code } } ?: noParen.ifBlank { code }
    val sched = embeddedTime ?: fallbackTime ?: "--:--"
    return RouteStation(code, name, null, resolved?.lat, resolved?.lon, sched, sched, sched)
  }

  private fun sameStation(a: RouteStation, b: RouteStation): Boolean =
    (a.stationCode.isNotBlank() && a.stationCode.equals(b.stationCode, ignoreCase = true)) ||
      (a.stationName.isNotBlank() && a.stationName.equals(b.stationName, ignoreCase = true))

  private fun computeRouteSegmentDistanceKm(route: List<RouteStation>, fromIdx: Int, toIdx: Int): Double {
    var total = 0.0
    var prev: RouteStation? = null
    for (i in fromIdx..toIdx) {
      val s = route[i]
      if (s.lat != null && s.lon != null) {
        prev?.let { p ->
          if (p.lat != null && p.lon != null) total += GeoMath.haversineKm(p.lat, p.lon, s.lat, s.lon) * 1.2
        }
        prev = s
      }
    }
    return total
  }

  private fun parseSheetTimeMinutes(raw: String): Int? {
    val s = raw.trim()
    if (s.isEmpty() || s.equals("None", ignoreCase = true)) return null
    val frac = s.toDoubleOrNull()
    if (frac != null && frac in 0.0..1.0) return (frac * 1440.0).toInt().coerceIn(0, 1439)
    val m = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?$""").find(s) ?: return null
    var h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    val mer = m.groupValues[3].uppercase(Locale.US)
    if (min !in 0..59) return null
    if (mer == "AM" && h == 12) h = 0
    if (mer == "PM" && h in 1..11) h += 12
    return if (h in 0..23) h * 60 + min else null
  }

  fun formatSheetTime(raw: String): String? {
    val m = parseSheetTimeMinutes(raw) ?: return null
    return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
  }

  private fun buildTrainNumberVariants(digits: String): List<String> = buildList {
    add(digits)
    val noZero = digits.trimStart('0').ifEmpty { digits }
    if (!contains(noZero)) add(noZero)
    if (digits.length < 5) {
      val pad = digits.padStart(5, '0')
      if (!contains(pad)) add(pad)
    }
  }

  private fun normalizeTrainKey(raw: String): String =
    raw.filter { it.isDigit() }.trimStart('0').ifEmpty { raw.trim() }

  private fun sanitizeTqIdent(raw: String): String =
    raw.replace(Regex("""[^A-Za-z0-9\-_ ]"""), "").trim()

  private fun downloadGvizQuery(sheet: String, tq: String): String? {
    val encSheet = URLEncoder.encode(sheet, "UTF-8")
    val encTq = URLEncoder.encode(tq, "UTF-8")
    return downloadCsv("$BASE_GVIZ_URL&sheet=$encSheet&tq=$encTq")
  }

  private fun downloadCsv(baseUrl: String): String? {
    val url = "$baseUrl&_t=${System.currentTimeMillis()}"
    return try {
      val req = Request.Builder()
        .url(url)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .header("User-Agent", "TrainLiveLocation-Android/1.0")
        .get()
        .build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val body = resp.body?.string().orEmpty()
        if (body.isBlank() || isHtmlOrInvalid(body)) null else body
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun headerIndexMap(firstRow: List<String>): Map<String, Int> {
    val map = HashMap<String, Int>()
    firstRow.forEachIndexed { idx, col ->
      val k = col.trim().removePrefix("\uFEFF").lowercase(Locale.US)
        .replace("_", "").replace(" ", "").replace("-", "")
      if (k.isNotEmpty()) map.putIfAbsent(k, idx)
    }
    return map
  }

  private fun colVal(row: List<String>, map: Map<String, Int>, vararg aliases: String): String {
    for (a in aliases) {
      val idx = map[a]
      if (idx != null && idx in row.indices) return row[idx].trim()
    }
    return ""
  }

  private fun hasStationHeader(csv: String): Boolean {
    val r = parseRfc4180Csv(csv).firstOrNull() ?: return false
    val m = headerIndexMap(r)
    return (m.containsKey("stationcode") || m.containsKey("code")) &&
      (m.containsKey("stationname") || m.containsKey("name"))
  }

  private fun isHtmlOrInvalid(raw: String): Boolean {
    val t = raw.trimStart().lowercase(Locale.US)
    return t.startsWith("<!doctype") || t.startsWith("<html") || t.contains("accounts.google.com")
  }

  private fun readCacheFile(name: String): String? =
    cacheDir?.let { File(it, name) }?.takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

  private fun writeCacheFile(name: String, content: String) {
    cacheDir?.let { File(it, name) }?.runCatching {
      parentFile?.mkdirs()
      writeText(content)
    }
  }

  private fun parseRfc4180Csv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var inQ = false
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (inQ) {
        if (c == '"') {
          if (i + 1 < text.length && text[i + 1] == '"') {
            field.append('"')
            i++
          } else {
            inQ = false
          }
        } else {
          field.append(c)
        }
      } else {
        when (c) {
          '"' -> inQ = true
          ',' -> { row.add(field.toString()); field.setLength(0) }
          '\r' -> {
            if (i + 1 < text.length && text[i + 1] == '\n') i++
            row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList()
          }
          '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList() }
          else -> field.append(c)
        }
      }
      i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
      row.add(field.toString()); rows.add(row)
    }
    return rows
  }
}
