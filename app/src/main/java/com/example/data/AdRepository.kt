package com.example.data

import com.example.model.AdItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

class AdRepository(private val cacheDir: File? = null) {
  companion object {
    const val ADS_SHEET_ID = "137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA"
    const val ADS_SHEET_NAME = "ads"
    const val ADS_CSV_URL =
      "https://docs.google.com/spreadsheets/d/$ADS_SHEET_ID/gviz/tq?tqx=out:csv&sheet=$ADS_SHEET_NAME"
    private const val ADS_CACHE_FILE = "ads_sheet_cache.csv"

    fun currentLocalDateYmd(calendar: Calendar = Calendar.getInstance()): Int {
      val y = calendar.get(Calendar.YEAR)
      val m = calendar.get(Calendar.MONTH) + 1
      val d = calendar.get(Calendar.DAY_OF_MONTH)
      return y * 10000 + m * 100 + d
    }

    fun currentLocalMinutesOfDay(calendar: Calendar = Calendar.getInstance()): Int =
      calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  suspend fun loadCachedAds(): List<AdItem> = withContext(Dispatchers.IO) {
    val file = cacheDir?.let { File(it, ADS_CACHE_FILE) } ?: return@withContext emptyList()
    if (!file.exists()) return@withContext emptyList()
    try {
      parseAdsCsv(file.readText())
    } catch (_: Exception) {
      emptyList()
    }
  }

  suspend fun fetchAds(): List<AdItem> = withContext(Dispatchers.IO) {
    val file = cacheDir?.let { File(it, ADS_CACHE_FILE) }
    try {
      val req = Request.Builder()
        .url(ADS_CSV_URL)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .header("User-Agent", "TrainLiveLocation-Android/1.0")
        .get()
        .build()

      client.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          val csv = resp.body?.string()
          if (!csv.isNullOrBlank()) {
            val parsed = parseAdsCsv(csv)
            if (file != null) {
              try {
                file.parentFile?.mkdirs()
                file.writeText(csv)
              } catch (_: Exception) {
              }
            }
            return@withContext parsed
          }
        }
      }
    } catch (_: Exception) {
    }
    loadCachedAds()
  }

  fun parseAdsCsv(csvText: String): List<AdItem> {
    val rows = parseRfc4180Csv(csvText)
    if (rows.size < 2) return emptyList()

    val headerMap = HashMap<String, Int>()
    rows.first().forEachIndexed { idx, col ->
      headerMap[normalizeColName(col)] = idx
    }

    fun col(row: List<String>, vararg aliases: String): String {
      for (alias in aliases) {
        val idx = headerMap[alias]
        if (idx != null && idx in row.indices) {
          return row[idx].trim()
        }
      }
      return ""
    }

    val result = ArrayList<AdItem>(rows.size - 1)
    for (r in 1 until rows.size) {
      val row = rows[r]
      if (row.all { it.isBlank() }) continue

      val imageName = col(row, "imagename", "image", "img")
      val rawAreas = col(row, "adsareainapp", "adsarea", "area", "slot")
      val areaKeys = rawAreas.split(',', ';', '|', ' ')
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotEmpty() }
        .toSet()

      if (imageName.isBlank() || areaKeys.isEmpty()) continue

      val lat = col(row, "latitude", "lat").toDoubleOrNull()?.takeIf { it.isFinite() }
      val lon = col(row, "longitude", "lon", "lng").toDoubleOrNull()?.takeIf { it.isFinite() }
      val radiusKm = col(row, "radiuskm", "radius").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
      val contactNumber = col(row, "contactnumber", "contact", "phone", "mobile")
      val link = col(row, "link", "url", "website")
      val address = col(row, "address", "location")
      val startTime = parseTimeToMinutes(col(row, "timetostartads", "starttime", "start"))
      val stopTime = parseTimeToMinutes(col(row, "timetostopads", "stoptime", "endtime", "stop"))
      val fromDate = parseDateToYmd(col(row, "fromdate", "startdate"))
      val toDate = parseDateToYmd(col(row, "todate", "enddate"))

      result.add(
        AdItem(
          imageName = imageName,
          areaKeys = areaKeys,
          latitude = lat,
          longitude = lon,
          radiusKm = radiusKm,
          contactNumber = contactNumber,
          link = link,
          address = address,
          timeStartMinutes = startTime,
          timeStopMinutes = stopTime,
          fromDateYmd = fromDate,
          toDateYmd = toDate,
        ),
      )
    }
    return result
  }

  private fun normalizeColName(raw: String): String =
    raw.trim()
      .removePrefix("\uFEFF")
      .lowercase(Locale.US)
      .replace("_", "")
      .replace(" ", "")
      .replace("-", "")

  private fun parseRfc4180Csv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var currentRow = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
      val ch = text[i]
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < text.length && text[i + 1] == '"') {
            field.append('"')
            i += 2
            continue
          } else {
            inQuotes = false
          }
        } else {
          field.append(ch)
        }
      } else {
        when (ch) {
          '"' -> inQuotes = true
          ',' -> {
            currentRow.add(field.toString())
            field.setLength(0)
          }
          '\r' -> {
            if (i + 1 < text.length && text[i + 1] == '\n') i++
            currentRow.add(field.toString())
            field.setLength(0)
            rows.add(currentRow)
            currentRow = ArrayList()
          }
          '\n' -> {
            currentRow.add(field.toString())
            field.setLength(0)
            rows.add(currentRow)
            currentRow = ArrayList()
          }
          else -> field.append(ch)
        }
      }
      i++
    }
    if (field.isNotEmpty() || currentRow.isNotEmpty()) {
      currentRow.add(field.toString())
      rows.add(currentRow)
    }
    return rows
  }

  fun parseTimeToMinutes(raw: String): Int? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null

    // Handle fractional Google Sheets time (0.0..1.0)
    clean.toDoubleOrNull()?.let { frac ->
      if (frac in 0.0..1.0) {
        return ((frac * 1440.0).toInt()).coerceIn(0, 1439)
      }
    }

    val amPmRegex = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?$""")
    val match = amPmRegex.find(clean) ?: return null
    var hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    val meridiem = match.groupValues[3].uppercase(Locale.US)

    if (minute !in 0..59) return null
    when (meridiem) {
      "AM" -> if (hour == 12) hour = 0
      "PM" -> if (hour in 1..11) hour += 12
    }
    if (hour !in 0..23) return null
    return hour * 60 + minute
  }

  fun parseDateToYmd(raw: String): Int? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null

    // Handle yyyy-MM-dd or yyyy/MM/dd
    Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$""").find(clean)?.let { m ->
      val y = m.groupValues[1].toIntOrNull() ?: return@let
      val mo = m.groupValues[2].toIntOrNull() ?: return@let
      val d = m.groupValues[3].toIntOrNull() ?: return@let
      if (mo in 1..12 && d in 1..31) return y * 10000 + mo * 100 + d
    }

    // Handle dd/MM/yyyy or dd-MM-yyyy (standard Indian date format)
    Regex("""^(\d{1,2})[-/](\d{1,2})[-/](\d{4})$""").find(clean)?.let { m ->
      val first = m.groupValues[1].toIntOrNull() ?: return@let
      val second = m.groupValues[2].toIntOrNull() ?: return@let
      val y = m.groupValues[3].toIntOrNull() ?: return@let
      val (d, mo) = if (second > 12 && first in 1..12) {
        second to first
      } else {
        first to second
      }
      if (mo in 1..12 && d in 1..31) return y * 10000 + mo * 100 + d
    }

    // Handle Google Sheets Date(yyyy,M,d) format if ever present
    Regex("""Date\((\d{4}),(\d{1,2}),(\d{1,2})\)""").find(clean)?.let { m ->
      val y = m.groupValues[1].toIntOrNull() ?: return@let
      val mo = (m.groupValues[2].toIntOrNull() ?: return@let) + 1
      val d = m.groupValues[3].toIntOrNull() ?: return@let
      if (mo in 1..12 && d in 1..31) return y * 10000 + mo * 100 + d
    }

    for (pattern in listOf("dd MMM yyyy", "d MMM yyyy", "MMM dd, yyyy", "yyyy.MM.dd")) {
      try {
        val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val date = sdf.parse(clean) ?: continue
        val cal = Calendar.getInstance().apply { time = date }
        return currentLocalDateYmd(cal)
      } catch (_: Exception) {
      }
    }
    return null
  }
}
