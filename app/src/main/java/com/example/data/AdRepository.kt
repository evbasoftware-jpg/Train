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
import org.json.JSONArray
import org.json.JSONObject

data class AdFetchResult(
  val ads: List<AdItem>,
  val verifiedFromRemote: Boolean,
)

class AdRepository(private val cacheDir: File? = null) {
  companion object {
    const val ADS_SHEET_ID = "137CjMlGjFv--aTDfo6V8yftNb6gVc5G-E3opwb84gfA"
    const val ADS_SHEET_NAME = "ads"
    const val ADS_CSV_URL =
      "https://docs.google.com/spreadsheets/d/$ADS_SHEET_ID/gviz/tq?tqx=out:csv&sheet=$ADS_SHEET_NAME"
    private const val ADS_EXPORT_URL =
      "https://docs.google.com/spreadsheets/d/$ADS_SHEET_ID/export?format=csv"
    private const val APPS_SCRIPT_ADS_URL =
      "https://script.google.com/macros/s/AKfycbxpHggaA7sNDXrwcyNid4Yy-Mfm3nLe8M0VGgplD4XHHc7Ul9LTWDJtpjj4C_4MFGopBQ/exec?action=ads"
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
      val text = file.readText()
      if (isHtmlOrInvalid(text)) return@withContext emptyList()
      parseAdsCsv(text)
    } catch (_: Exception) {
      emptyList()
    }
  }

  suspend fun fetchAds(): List<AdItem> = fetchAdsWithStatus().ads

  suspend fun fetchAdsWithStatus(): AdFetchResult = withContext(Dispatchers.IO) {
    val file = cacheDir?.let { File(it, ADS_CACHE_FILE) }
    val stamp = System.currentTimeMillis()
    val endpoints = listOf(
      "$ADS_CSV_URL&_t=$stamp",
      "$ADS_EXPORT_URL&_t=$stamp",
      "$APPS_SCRIPT_ADS_URL&_t=$stamp",
    )

    for (url in endpoints) {
      try {
        val req = Request.Builder()
          .url(url)
          .cacheControl(CacheControl.FORCE_NETWORK)
          .header("Cache-Control", "no-cache, no-store, must-revalidate")
          .header("Pragma", "no-cache")
          .header("User-Agent", "TrainLiveLocation-Android/1.0")
          .get()
          .build()

        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@use
          val body = resp.body?.string().orEmpty()
          if (body.isBlank() || isHtmlOrInvalid(body)) return@use

          val trimmed = body.trimStart()
          if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonAds = parseAdsJson(trimmed)
            if (jsonAds != null) {
              return@withContext AdFetchResult(ads = jsonAds, verifiedFromRemote = true)
            }
            return@use
          }

          if (!hasAdsCsvHeader(body)) return@use
          val parsed = parseAdsCsv(body)
          if (file != null) {
            try {
              file.parentFile?.mkdirs()
              file.writeText(body)
            } catch (_: Exception) {
            }
          }
          return@withContext AdFetchResult(ads = parsed, verifiedFromRemote = true)
        }
      } catch (_: Exception) {
      }
    }

    AdFetchResult(ads = loadCachedAds(), verifiedFromRemote = false)
  }

  private fun isHtmlOrInvalid(raw: String): Boolean {
    val trimmed = raw.trimStart().lowercase(Locale.US)
    return trimmed.startsWith("<!doctype") ||
      trimmed.startsWith("<html") ||
      trimmed.contains("<title>google sheets") ||
      trimmed.contains("accounts.google.com")
  }

  private fun hasAdsCsvHeader(csvText: String): Boolean {
    val rows = parseRfc4180Csv(csvText)
    if (rows.isEmpty()) return false
    val cols = rows.first().map { normalizeColName(it) }.toSet()
    val hasImg = cols.any { it == "imagename" || it == "image" || it == "img" }
    val hasArea = cols.any { it == "adsareainapp" || it == "adsarea" || it == "area" || it == "slot" }
    return hasImg && hasArea
  }

  private fun parseAdsJson(jsonText: String): List<AdItem>? {
    return try {
      val array: JSONArray = if (jsonText.startsWith("[")) {
        JSONArray(jsonText)
      } else {
        val obj = JSONObject(jsonText)
        if (obj.optBoolean("success", true).not() && !obj.has("ads") && !obj.has("data")) {
          return null
        }
        obj.optJSONArray("ads") ?: obj.optJSONArray("data") ?: return null
      }
      val out = ArrayList<AdItem>(array.length())
      for (i in 0 until array.length()) {
        val row = array.optJSONObject(i) ?: continue
        val norm = HashMap<String, String>()
        val keys = row.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          norm[normalizeColName(k)] = row.opt(k)?.toString()?.trim().orEmpty()
        }
        fun col(vararg aliases: String): String {
          for (a in aliases) {
            val v = norm[a]
            if (!v.isNullOrBlank() && !v.equals("null", ignoreCase = true)) return v
          }
          return ""
        }
        buildAdItem(
          imageName = col("imagename", "image", "img"),
          rawAreas = col("adsareainapp", "adsarea", "area", "slot"),
          rawLat = col("latitude", "lat"),
          rawLon = col("longitude", "lon", "lng"),
          rawRadius = col("radiuskm", "radius"),
          contactNumber = col("contactnumber", "contact", "phone", "mobile"),
          link = col("link", "url", "website"),
          address = col("address", "location"),
          rawStart = col("timetostartads", "starttime", "start"),
          rawStop = col("timetostopads", "stoptime", "endtime", "stop"),
          rawFromDate = col("fromdate", "startdate"),
          rawToDate = col("todate", "enddate"),
        )?.let(out::add)
      }
      out
    } catch (_: Exception) {
      null
    }
  }

  fun parseAdsCsv(csvText: String): List<AdItem> {
    if (isHtmlOrInvalid(csvText)) return emptyList()
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

      buildAdItem(
        imageName = col(row, "imagename", "image", "img"),
        rawAreas = col(row, "adsareainapp", "adsarea", "area", "slot"),
        rawLat = col(row, "latitude", "lat"),
        rawLon = col(row, "longitude", "lon", "lng"),
        rawRadius = col(row, "radiuskm", "radius"),
        contactNumber = col(row, "contactnumber", "contact", "phone", "mobile"),
        link = col(row, "link", "url", "website"),
        address = col(row, "address", "location"),
        rawStart = col(row, "timetostartads", "starttime", "start"),
        rawStop = col(row, "timetostopads", "stoptime", "endtime", "stop"),
        rawFromDate = col(row, "fromdate", "startdate"),
        rawToDate = col(row, "todate", "enddate"),
      )?.let(result::add)
    }
    return result
  }

  private fun buildAdItem(
    imageName: String,
    rawAreas: String,
    rawLat: String,
    rawLon: String,
    rawRadius: String,
    contactNumber: String,
    link: String,
    address: String,
    rawStart: String,
    rawStop: String,
    rawFromDate: String,
    rawToDate: String,
  ): AdItem? {
    val areaKeys = rawAreas.split(',', ';', '|', ' ')
      .map { it.trim().lowercase(Locale.US) }
      .filter { it.isNotEmpty() }
      .toSet()

    if (imageName.isBlank() || areaKeys.isEmpty()) return null

    val lat = rawLat.toDoubleOrNull()?.takeIf { it.isFinite() }
    val lon = rawLon.toDoubleOrNull()?.takeIf { it.isFinite() }
    val radiusKm = rawRadius.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    var startTime = parseTimeToMinutes(rawStart)
    var stopTime = parseTimeToMinutes(rawStop)
    // Sheet rows commonly enter 0 and 0 as placeholder for all-day active ads
    if (startTime == 0 && stopTime == 0) {
      startTime = null
      stopTime = null
    }
    val fromDate = parseDateToYmd(rawFromDate)
    val toDate = parseDateToYmd(rawToDate)

    return AdItem(
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
    )
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

    // Handle yyyy-MM-dd or yyyy/MM/dd or yyyy.MM.dd
    Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""").find(clean)?.let { m ->
      val y = m.groupValues[1].toIntOrNull() ?: return@let
      val mo = m.groupValues[2].toIntOrNull() ?: return@let
      val d = m.groupValues[3].toIntOrNull() ?: return@let
      if (mo in 1..12 && d in 1..31) return y * 10000 + mo * 100 + d
    }

    // Handle dd/MM/yyyy or dd-MM-yyyy or dd.MM.yyyy (standard Indian date format)
    Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$""").find(clean)?.let { m ->
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
