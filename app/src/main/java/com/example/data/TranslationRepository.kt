package com.example.data

import com.example.model.LocalizedStrings
import com.example.model.stringsForCode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class TranslationRepository(private val cacheDir: File?) {
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  private val dynamicCacheByLang = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

  suspend fun getStringsForLanguage(langCode: String): LocalizedStrings {
    if (langCode == "bn" || langCode == "en" || langCode == "hi") {
      return stringsForCode(langCode)
    }

    return withContext(Dispatchers.IO) {
      val cacheFile = cacheDir?.let { File(it, "lang_v4_${langCode.replace("/", "_")}.json") }
      val english = stringsForCode("en")
      val keys = buildTranslatableMap(english)

      if (cacheFile != null && cacheFile.exists()) {
        val cached = loadFromCache(cacheFile, english)
        if (cached != null) return@withContext cached
      }

      val translatedMap = translateBatchWithGoogle(langCode, keys)
      if (translatedMap != null) {
        saveToCache(cacheFile, translatedMap)
        applyTranslatedMap(english, translatedMap)
      } else {
        english
      }
    }
  }

  suspend fun translateDynamicNames(
    langCode: String,
    rawNames: Collection<String>,
  ): Map<String, String> {
    if (langCode == "en" || rawNames.isEmpty()) return emptyMap()
    val cleanUnique = rawNames.asSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() && it != "—" && it.any { c -> c.isLetter() } }
      .distinct()
      .toList()
    if (cleanUnique.isEmpty()) return emptyMap()

    return withContext(Dispatchers.IO) {
      val langMap = dynamicCacheByLang.getOrPut(langCode) {
        loadDynamicMapFromDisk(langCode)
      }
      val missing = cleanUnique.filter { !langMap.containsKey(it) }
      if (missing.isNotEmpty()) {
        missing.chunked(40).forEach { chunk ->
          val translated = translateListWithGoogle(langCode, chunk)
          if (translated != null && translated.size == chunk.size) {
            chunk.forEachIndexed { idx, original ->
              val v = translated[idx].trim()
              if (v.isNotEmpty()) langMap[original] = v
            }
          }
        }
        saveDynamicMapToDisk(langCode, langMap)
      }
      langMap.toMap()
    }
  }

  private fun buildTranslatableMap(en: LocalizedStrings): LinkedHashMap<String, String> =
    linkedMapOf(
      "appTitle" to en.appTitle,
      "appSubtitle" to en.appSubtitle,
      "languageLabel" to en.languageLabel,
      "searchLanguageHint" to en.searchLanguageHint,
      "fromStationLabel" to en.fromStationLabel,
      "toStationLabel" to en.toStationLabel,
      "stationPlaceholder" to en.stationPlaceholder,
      "swapStationsDesc" to en.swapStationsDesc,
      "loadingTrainsBetween" to en.loadingTrainsBetween,
      "noTrainsFoundTemplate" to en.noTrainsFoundTemplate,
      "trainNoLabel" to en.trainNoLabel,
      "trainNoPlaceholder" to en.trainNoPlaceholder,
      "favoriteToggleDesc" to en.favoriteToggleDesc,
      "favoritesSectionTitle" to en.favoritesSectionTitle,
      "recentSectionTitle" to en.recentSectionTitle,
      "recentRoutesTitle" to en.recentRoutesTitle,
      "startTrackingBtn" to en.startTrackingBtn,
      "stopTrackingBtn" to en.stopTrackingBtn,
      "officialNtesBtn" to en.officialNtesBtn,
      "routeSectionTitle" to en.routeSectionTitle,
      "routeEmptyPlaceholder" to en.routeEmptyPlaceholder,
      "routeNoStations" to en.routeNoStations,
      "tapStationHint" to en.tapStationHint,
      "delayOnTime" to en.delayOnTime,
      "delayLateTemplate" to en.delayLateTemplate,
      "delayEarlyTemplate" to en.delayEarlyTemplate,
      "viewOnMapBtn" to en.viewOnMapBtn,
      "setAlarmBtn" to en.setAlarmBtn,
      "alarmActiveBtn" to en.alarmActiveBtn,
      "alarmBannerTemplate" to en.alarmBannerTemplate,
      "alarmTriggeredTemplate" to en.alarmTriggeredTemplate,
      "dismissAlarmBtn" to en.dismissAlarmBtn,
      "distanceFromTrainTemplate" to en.distanceFromTrainTemplate,
      "trainLiveTitle" to en.trainLiveTitle,
      "waitingLabel" to en.waitingLabel,
      "mapSectionTitle" to en.mapSectionTitle,
      "mapSmallBtn" to en.mapSmallBtn,
      "mapLargeBtn" to en.mapLargeBtn,
      "mapFullscreenBtn" to en.mapFullscreenBtn,
      "mapExitFullscreenBtn" to en.mapExitFullscreenBtn,
      "mapCenterBtn" to en.mapCenterBtn,
      "notifCurrentLabel" to en.notifCurrentLabel,
      "notifNextLabel" to en.notifNextLabel,
      "notifDestinationLabel" to en.notifDestinationLabel,
      "announceCurrentNextTemplate" to en.announceCurrentNextTemplate,
      "announceCurrentOnlyTemplate" to en.announceCurrentOnlyTemplate,
      "passedDistanceTemplate" to en.passedDistanceTemplate,
      "labelPassedCurrent" to en.labelPassedCurrent,
      "labelRemainingNext" to en.labelRemainingNext,
      "meterUnit" to en.meterUnit,
      "kmUnit" to en.kmUnit,
      "kmPerHourUnit" to en.kmPerHourUnit,
      "distanceMovementTitle" to en.distanceMovementTitle,
      "searchingLiveTemplate" to en.searchingLiveTemplate,
      "retryingSignal" to en.retryingSignal,
      "apiTimeoutStopped" to en.apiTimeoutStopped,
      "otherUserGpsMsg" to en.otherUserGpsMsg,
      "gpsVerifiedInsideMsg" to en.gpsVerifiedInsideMsg,
      "gpsSlowNetworkFallback" to en.gpsSlowNetworkFallback,
      "gpsLowAccuracyTemplate" to en.gpsLowAccuracyTemplate,
      "gpsPermissionDenied" to en.gpsPermissionDenied,
      "gpsDisabledOrUnavailable" to en.gpsDisabledOrUnavailable,
      "grantPermissionBtn" to en.grantPermissionBtn,
      "sourceYourGps" to en.sourceYourGps,
      "sourcePassengerGps" to en.sourcePassengerGps,
      "sourceApiKey" to en.sourceApiKey,
      "labelTrain" to en.labelTrain,
      "labelCurrent" to en.labelCurrent,
      "labelNext" to en.labelNext,
      "labelSource" to en.labelSource,
      "labelSpeed" to en.labelSpeed,
      "labelTrainDistance" to en.labelTrainDistance,
      "labelTrackOffset" to en.labelTrackOffset,
      "labelLengthOffset" to en.labelLengthOffset,
      "labelUserSpeed" to en.labelUserSpeed,
      "labelTrainSpeed" to en.labelTrainSpeed,
      "labelHeadingDiff" to en.labelHeadingDiff,
      "unknownStation" to en.unknownStation,
      "userPopupLabel" to en.userPopupLabel,
      "closeLabel" to en.closeLabel,
      "clearLabel" to en.clearLabel,
    )

  private fun applyTranslatedMap(
    base: LocalizedStrings,
    m: Map<String, String>,
  ): LocalizedStrings = base.copy(
    appTitle = "Train Live Location",
    appSubtitle = "",
    languageLabel = m["languageLabel"] ?: base.languageLabel,
    searchLanguageHint = m["searchLanguageHint"] ?: base.searchLanguageHint,
    fromStationLabel = m["fromStationLabel"] ?: base.fromStationLabel,
    toStationLabel = m["toStationLabel"] ?: base.toStationLabel,
    stationPlaceholder = m["stationPlaceholder"] ?: base.stationPlaceholder,
    swapStationsDesc = m["swapStationsDesc"] ?: base.swapStationsDesc,
    loadingTrainsBetween = m["loadingTrainsBetween"] ?: base.loadingTrainsBetween,
    noTrainsFoundTemplate = m["noTrainsFoundTemplate"] ?: base.noTrainsFoundTemplate,
    trainNoLabel = m["trainNoLabel"] ?: base.trainNoLabel,
    trainNoPlaceholder = m["trainNoPlaceholder"] ?: base.trainNoPlaceholder,
    favoriteToggleDesc = m["favoriteToggleDesc"] ?: base.favoriteToggleDesc,
    favoritesSectionTitle = m["favoritesSectionTitle"] ?: base.favoritesSectionTitle,
    recentSectionTitle = m["recentSectionTitle"] ?: base.recentSectionTitle,
    recentRoutesTitle = m["recentRoutesTitle"] ?: base.recentRoutesTitle,
    startTrackingBtn = m["startTrackingBtn"] ?: base.startTrackingBtn,
    stopTrackingBtn = m["stopTrackingBtn"] ?: base.stopTrackingBtn,
    officialNtesBtn = m["officialNtesBtn"] ?: base.officialNtesBtn,
    routeSectionTitle = m["routeSectionTitle"] ?: base.routeSectionTitle,
    routeEmptyPlaceholder = m["routeEmptyPlaceholder"] ?: base.routeEmptyPlaceholder,
    routeNoStations = m["routeNoStations"] ?: base.routeNoStations,
    tapStationHint = m["tapStationHint"] ?: base.tapStationHint,
    delayOnTime = m["delayOnTime"] ?: base.delayOnTime,
    delayLateTemplate = m["delayLateTemplate"] ?: base.delayLateTemplate,
    delayEarlyTemplate = m["delayEarlyTemplate"] ?: base.delayEarlyTemplate,
    viewOnMapBtn = m["viewOnMapBtn"] ?: base.viewOnMapBtn,
    setAlarmBtn = m["setAlarmBtn"] ?: base.setAlarmBtn,
    alarmActiveBtn = m["alarmActiveBtn"] ?: base.alarmActiveBtn,
    alarmBannerTemplate = m["alarmBannerTemplate"] ?: base.alarmBannerTemplate,
    alarmTriggeredTemplate = m["alarmTriggeredTemplate"] ?: base.alarmTriggeredTemplate,
    dismissAlarmBtn = m["dismissAlarmBtn"] ?: base.dismissAlarmBtn,
    distanceFromTrainTemplate = m["distanceFromTrainTemplate"] ?: base.distanceFromTrainTemplate,
    trainLiveTitle = m["trainLiveTitle"] ?: base.trainLiveTitle,
    waitingLabel = m["waitingLabel"] ?: base.waitingLabel,
    mapSectionTitle = m["mapSectionTitle"] ?: base.mapSectionTitle,
    mapSmallBtn = m["mapSmallBtn"] ?: base.mapSmallBtn,
    mapLargeBtn = m["mapLargeBtn"] ?: base.mapLargeBtn,
    mapFullscreenBtn = m["mapFullscreenBtn"] ?: base.mapFullscreenBtn,
    mapExitFullscreenBtn = m["mapExitFullscreenBtn"] ?: base.mapExitFullscreenBtn,
    mapCenterBtn = m["mapCenterBtn"] ?: base.mapCenterBtn,
    notifCurrentLabel = m["notifCurrentLabel"] ?: base.notifCurrentLabel,
    notifNextLabel = m["notifNextLabel"] ?: base.notifNextLabel,
    notifDestinationLabel = m["notifDestinationLabel"] ?: base.notifDestinationLabel,
    announceCurrentNextTemplate = m["announceCurrentNextTemplate"] ?: base.announceCurrentNextTemplate,
    announceCurrentOnlyTemplate = m["announceCurrentOnlyTemplate"] ?: base.announceCurrentOnlyTemplate,
    passedDistanceTemplate = m["passedDistanceTemplate"] ?: base.passedDistanceTemplate,
    labelPassedCurrent = m["labelPassedCurrent"] ?: base.labelPassedCurrent,
    labelRemainingNext = m["labelRemainingNext"] ?: base.labelRemainingNext,
    meterUnit = m["meterUnit"] ?: base.meterUnit,
    kmUnit = m["kmUnit"] ?: base.kmUnit,
    kmPerHourUnit = m["kmPerHourUnit"] ?: base.kmPerHourUnit,
    distanceMovementTitle = m["distanceMovementTitle"] ?: base.distanceMovementTitle,
    searchingLiveTemplate = m["searchingLiveTemplate"] ?: base.searchingLiveTemplate,
    retryingSignal = m["retryingSignal"] ?: base.retryingSignal,
    apiTimeoutStopped = m["apiTimeoutStopped"] ?: base.apiTimeoutStopped,
    otherUserGpsMsg = m["otherUserGpsMsg"] ?: base.otherUserGpsMsg,
    gpsVerifiedInsideMsg = m["gpsVerifiedInsideMsg"] ?: base.gpsVerifiedInsideMsg,
    gpsSlowNetworkFallback = m["gpsSlowNetworkFallback"] ?: base.gpsSlowNetworkFallback,
    gpsLowAccuracyTemplate = m["gpsLowAccuracyTemplate"] ?: base.gpsLowAccuracyTemplate,
    gpsPermissionDenied = m["gpsPermissionDenied"] ?: base.gpsPermissionDenied,
    gpsDisabledOrUnavailable = m["gpsDisabledOrUnavailable"] ?: base.gpsDisabledOrUnavailable,
    grantPermissionBtn = m["grantPermissionBtn"] ?: base.grantPermissionBtn,
    sourceYourGps = m["sourceYourGps"] ?: base.sourceYourGps,
    sourcePassengerGps = m["sourcePassengerGps"] ?: base.sourcePassengerGps,
    sourceApiKey = m["sourceApiKey"] ?: base.sourceApiKey,
    labelTrain = m["labelTrain"] ?: base.labelTrain,
    labelCurrent = m["labelCurrent"] ?: base.labelCurrent,
    labelNext = m["labelNext"] ?: base.labelNext,
    labelSource = m["labelSource"] ?: base.labelSource,
    labelSpeed = m["labelSpeed"] ?: base.labelSpeed,
    labelTrainDistance = m["labelTrainDistance"] ?: base.labelTrainDistance,
    labelTrackOffset = m["labelTrackOffset"] ?: base.labelTrackOffset,
    labelLengthOffset = m["labelLengthOffset"] ?: base.labelLengthOffset,
    labelUserSpeed = m["labelUserSpeed"] ?: base.labelUserSpeed,
    labelTrainSpeed = m["labelTrainSpeed"] ?: base.labelTrainSpeed,
    labelHeadingDiff = m["labelHeadingDiff"] ?: base.labelHeadingDiff,
    unknownStation = m["unknownStation"] ?: base.unknownStation,
    userPopupLabel = m["userPopupLabel"] ?: base.userPopupLabel,
    closeLabel = m["closeLabel"] ?: base.closeLabel,
    clearLabel = m["clearLabel"] ?: base.clearLabel,
  )

  private fun translateBatchWithGoogle(
    targetLang: String,
    keys: LinkedHashMap<String, String>,
  ): Map<String, String>? {
    val entries = keys.entries.toList()
    val translated = translateListWithGoogle(targetLang, entries.map { it.value }) ?: return null
    if (translated.size != entries.size) return null
    val result = LinkedHashMap<String, String>(entries.size)
    entries.forEachIndexed { idx, entry ->
      result[entry.key] = translated[idx].trim().ifEmpty { entry.value }
    }
    return result
  }

  private fun translateListWithGoogle(
    targetLang: String,
    items: List<String>,
  ): List<String>? {
    if (items.isEmpty()) return emptyList()
    return try {
      val joined = items.joinToString("\n") { it.replace("\n", " ") }
      val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
        .addQueryParameter("client", "gtx")
        .addQueryParameter("sl", "en")
        .addQueryParameter("tl", targetLang)
        .addQueryParameter("dt", "t")
        .addQueryParameter("q", joined)
        .build()

      val req = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0")
        .get()
        .build()

      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        val rootArr = JSONArray(body)
        val sentences = rootArr.optJSONArray(0) ?: return null
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
          val seg = sentences.optJSONArray(i)?.optString(0, "") ?: ""
          sb.append(seg)
        }
        val lines = sb.toString().split("\n")
        if (lines.size != items.size) null else lines
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun loadFromCache(file: File, base: LocalizedStrings): LocalizedStrings? {
    return try {
      val obj = JSONObject(file.readText())
      val map = mutableMapOf<String, String>()
      obj.keys().forEach { k -> map[k] = obj.optString(k, "") }
      if (map.isEmpty()) null else applyTranslatedMap(base, map)
    } catch (_: Exception) {
      null
    }
  }

  private fun saveToCache(file: File?, map: Map<String, String>) {
    if (file == null) return
    try {
      file.parentFile?.mkdirs()
      val obj = JSONObject()
      map.forEach { (k, v) -> obj.put(k, v) }
      file.writeText(obj.toString())
    } catch (_: Exception) {
    }
  }

  private fun loadDynamicMapFromDisk(langCode: String): ConcurrentHashMap<String, String> {
    val map = ConcurrentHashMap<String, String>()
    val file = cacheDir?.let { File(it, "names_v1_${langCode.replace("/", "_")}.json") } ?: return map
    if (!file.exists()) return map
    try {
      val obj = JSONObject(file.readText())
      obj.keys().forEach { k ->
        val v = obj.optString(k, "").trim()
        if (v.isNotEmpty()) map[k] = v
      }
    } catch (_: Exception) {
    }
    return map
  }

  private fun saveDynamicMapToDisk(langCode: String, map: Map<String, String>) {
    val file = cacheDir?.let { File(it, "names_v1_${langCode.replace("/", "_")}.json") } ?: return
    try {
      file.parentFile?.mkdirs()
      val obj = JSONObject()
      map.forEach { (k, v) -> obj.put(k, v) }
      file.writeText(obj.toString())
    } catch (_: Exception) {
    }
  }
}
