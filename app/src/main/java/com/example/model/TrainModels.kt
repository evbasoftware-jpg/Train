package com.example.model

data class SavedTrainEntry(
  val trainNumber: String,
  val trainName: String = "",
)

data class SavedRoutePair(
  val fromInput: String,
  val toInput: String,
)

sealed interface DelayStatus {
  data object OnTime : DelayStatus
  data class Delayed(val minutes: Int) : DelayStatus
  data class Early(val minutes: Int) : DelayStatus
  data object Unknown : DelayStatus
}

data class StationAlarmState(
  val stationCode: String,
  val stationName: String,
  val lat: Double,
  val lon: Double,
  val triggered: Boolean = false,
)

data class FocusedMapStation(
  val stationCode: String,
  val stationName: String,
  val lat: Double,
  val lon: Double,
  val signal: Int,
)

data class LocalizedStrings(
  val appTitle: String,
  val appSubtitle: String,
  val languageLabel: String,
  val searchLanguageHint: String,
  val fromStationLabel: String,
  val toStationLabel: String,
  val stationPlaceholder: String,
  val swapStationsDesc: String,
  val loadingTrainsBetween: String,
  val noTrainsFoundTemplate: String,
  val trainNoLabel: String,
  val trainNoPlaceholder: String,
  val favoriteToggleDesc: String,
  val favoritesSectionTitle: String,
  val recentSectionTitle: String,
  val recentRoutesTitle: String,
  val startTrackingBtn: String,
  val stopTrackingBtn: String,
  val officialNtesBtn: String,
  val routeSectionTitle: String,
  val routeEmptyPlaceholder: String,
  val routeNoStations: String,
  val tapStationHint: String,
  val delayOnTime: String,
  val delayLateTemplate: String,
  val delayEarlyTemplate: String,
  val viewOnMapBtn: String,
  val setAlarmBtn: String,
  val alarmActiveBtn: String,
  val alarmBannerTemplate: String,
  val alarmTriggeredTemplate: String,
  val dismissAlarmBtn: String,
  val distanceFromTrainTemplate: String,
  val trainLiveTitle: String,
  val waitingLabel: String,
  val mapSectionTitle: String,
  val mapSmallBtn: String,
  val mapLargeBtn: String,
  val mapFullscreenBtn: String,
  val mapExitFullscreenBtn: String,
  val mapCenterBtn: String,
  val backgroundTrackingTitle: String,
  val backgroundTrackingDesc: String,
  val distanceMovementTitle: String,
  val searchingLiveTemplate: String,
  val retryingSignal: String,
  val trackingStopped: String,
  val apiTimeoutStopped: String,
  val otherUserGpsMsg: String,
  val gpsVerifiedInsideMsg: String,
  val trainLocSetFromUserGps: String,
  val offsetTemplate: String,
  val gpsSlowNetworkFallback: String,
  val gpsLowAccuracyTemplate: String,
  val gpsPermissionDenied: String,
  val gpsDisabledOrUnavailable: String,
  val grantPermissionBtn: String,
  val sourceYourGps: String,
  val sourcePassengerGps: String,
  val sourceApiKey: String,
  val labelTrain: String,
  val labelCurrent: String,
  val labelNext: String,
  val labelSource: String,
  val labelSpeed: String,
  val labelTrainDistance: String,
  val labelTrackOffset: String,
  val labelLengthOffset: String,
  val labelUserSpeed: String,
  val labelTrainSpeed: String,
  val labelHeadingDiff: String,
  val unknownStation: String,
  val userPopupLabel: String,
) {
  fun noTrainsFound(from: String, to: String): String =
    noTrainsFoundTemplate.replace("{FROM}", from).replace("{TO}", to)

  fun delayLateMinutes(mins: Int): String =
    delayLateTemplate.replace("{MIN}", mins.toString())

  fun delayEarlyMinutes(mins: Int): String =
    delayEarlyTemplate.replace("{MIN}", mins.toString())

  fun alarmBannerTitle(station: String): String =
    alarmBannerTemplate.replace("{ST}", station)

  fun alarmTriggeredMsg(trainNo: String, station: String): String =
    alarmTriggeredTemplate.replace("{TR}", trainNo).replace("{ST}", station)

  fun distanceFromTrainLabel(distKm: String): String =
    distanceFromTrainTemplate.replace("{DIST}", distKm)

  fun searchingLiveTrain(trainNo: String): String =
    searchingLiveTemplate.replace("{TR}", trainNo)

  fun offsetResult(crossM: String, alongM: String): String =
    offsetTemplate.replace("{CROSS}", crossM).replace("{ALONG}", alongM)

  fun gpsLowAccuracyNotice(accMeters: Int): String =
    gpsLowAccuracyTemplate.replace("{ACC}", accMeters.toString())
}

fun stringsForCode(langCode: String): LocalizedStrings = when (langCode) {
  "bn" -> LocalizedStrings(
    appTitle = "স্মার্ট লাইভ ট্রেন ট্র্যাকার",
    appSubtitle = "Live Train • User GPS • Distance • Route",
    languageLabel = "ভাষা",
    searchLanguageHint = "ভাষা খুঁজুন (১০০+ ভাষা)...",
    fromStationLabel = "স্টেশন থেকে",
    toStationLabel = "স্টেশন পর্যন্ত",
    stationPlaceholder = "স্টেশনের নাম/কোড লিখুন",
    swapStationsDesc = "স্টেশন অদলবদল করুন",
    loadingTrainsBetween = "⏳ ট্রেনের তালিকা লোড হচ্ছে...",
    noTrainsFoundTemplate = "❌ {FROM} থেকে {TO} রুটে কোনো ট্রেন পাওয়া যায়নি।",
    trainNoLabel = "ট্রেন নম্বর লিখুন",
    trainNoPlaceholder = "যেমন: 12345",
    favoriteToggleDesc = "প্রিয় তালিকায় যোগ/বাদ দিন",
    favoritesSectionTitle = "প্রিয় ট্রেন",
    recentSectionTitle = "সাম্প্রতিক ট্রেন",
    recentRoutesTitle = "সাম্প্রতিক রুট",
    startTrackingBtn = "ট্র্যাক করুন",
    stopTrackingBtn = "ট্র্যাকিং বন্ধ করুন",
    officialNtesBtn = "অফিসিয়াল NTES",
    routeSectionTitle = "ট্রেনের রুট ও বর্তমান অবস্থান",
    routeEmptyPlaceholder = "ট্রেনের তথ্য লোড হলে রুট দেখা যাবে...",
    routeNoStations = "Route station পাওয়া যায়নি।",
    tapStationHint = "স্টেশনে ট্যাপ করে ম্যাপে দেখুন বা অ্যালার্ম সেট করুন",
    delayOnTime = "সময়মতো",
    delayLateTemplate = "+{MIN}মি বিলম্ব",
    delayEarlyTemplate = "-{MIN}মি আগে",
    viewOnMapBtn = "ম্যাপে দেখুন",
    setAlarmBtn = "অ্যালার্ম দিন",
    alarmActiveBtn = "অ্যালার্ম চালু",
    alarmBannerTemplate = "🔔 অ্যালার্ম সেট: {ST} (৩ কিমির মধ্যে অ্যালার্ট)",
    alarmTriggeredTemplate = "🔔 সতর্কবার্তা: ট্রেন {TR} আপনার গন্তব্য {ST}-এর কাছাকাছি পৌঁছেছে!",
    dismissAlarmBtn = "অ্যালার্ম বন্ধ",
    distanceFromTrainTemplate = "ট্রেন থেকে দূরত্ব: {DIST} কিমি",
    trainLiveTitle = "Train Live Data",
    waitingLabel = "অপেক্ষা করছে...",
    mapSectionTitle = "ট্রেন এবং ব্যবহারকারীর লাইভ অবস্থান",
    mapSmallBtn = "ছোট",
    mapLargeBtn = "বড়",
    mapFullscreenBtn = "Fullscreen",
    mapExitFullscreenBtn = "বন্ধ করুন",
    mapCenterBtn = "Center",
    backgroundTrackingTitle = "নোটিফিকেশন ট্র্যাকিং (স্ক্রিন বন্ধ থাকলেও)",
    backgroundTrackingDesc = "অ্যাপ স্ক্রিন বন্ধ থাকলেও নোটিফিকেশনের মাধ্যমে ট্রেনের অবস্থান ট্র্যাক ও আপডেট করবে",
    distanceMovementTitle = "Distance & Movement",
    searchingLiveTemplate = "⏳ ট্রেন {TR} এর live তথ্য খোঁজা হচ্ছে...",
    retryingSignal = "📡 সিগন্যাল খোঁজা হচ্ছে, পুনরায় চেষ্টা চলছে...",
    trackingStopped = "⏹ ট্র্যাকিং বন্ধ করা হয়েছে।",
    apiTimeoutStopped = "⏹️ API-only tracking 20 মিনিট পরে বন্ধ হয়েছে।",
    otherUserGpsMsg = "📡 অন্য ব্যবহারকারীর GPS থেকে ট্রেনের অবস্থান দেখানো হচ্ছে।",
    gpsVerifiedInsideMsg = "📱 GPS যাচাই হয়েছে। গতি ও দিক অনুযায়ী আপনি ট্রেনের ভেতরে আছেন।",
    trainLocSetFromUserGps = "📍 ট্রেনের লোকেশন আপনার লাইভ GPS থেকে সেট করা হয়েছে।",
    offsetTemplate = "📏 ট্রেন থেকে অফসেট: পাশে {CROSS}m, দৈর্ঘ্যে {ALONG}m",
    gpsSlowNetworkFallback = "📡 GPS signal ধীর—network/Wi‑Fi location চেষ্টা করছি...",
    gpsLowAccuracyTemplate = "সিগন্যাল পাওয়া গেছে, কিন্তু accuracy ±{ACC}m",
    gpsPermissionDenied = "❌ Location permission বন্ধ আছে। অনুগ্রহ করে Location অনুমতি দিন।",
    gpsDisabledOrUnavailable = "❌ Location পাওয়া যাচ্ছে না। ফোনের Location/GPS ON করুন।",
    grantPermissionBtn = "Location অনুমতি দিন",
    sourceYourGps = "📱 আপনার লাইভ GPS",
    sourcePassengerGps = "📱 অন্য যাত্রীর GPS",
    sourceApiKey = "🔑 API Key",
    labelTrain = "Train",
    labelCurrent = "Current",
    labelNext = "Next",
    labelSource = "Source",
    labelSpeed = "Speed",
    labelTrainDistance = "Train Distance",
    labelTrackOffset = "Track Offset",
    labelLengthOffset = "Length Offset",
    labelUserSpeed = "User Speed",
    labelTrainSpeed = "Train Speed",
    labelHeadingDiff = "Heading Diff",
    unknownStation = "অজ্ঞাত",
    userPopupLabel = "আপনার বর্তমান অবস্থান",
  )

  "hi" -> LocalizedStrings(
    appTitle = "स्मार्ट लाइव ट्रेन ट्रैकर",
    appSubtitle = "Live Train • User GPS • Distance • Route",
    languageLabel = "भाषा",
    searchLanguageHint = "भाषा खोजें (100+ भाषाएं)...",
    fromStationLabel = "कहाँ से स्टेशन",
    toStationLabel = "कहाँ तक स्टेशन",
    stationPlaceholder = "स्टेशन नाम या कोड लिखें",
    swapStationsDesc = "स्टेशन बदलें",
    loadingTrainsBetween = "⏳ ट्रेनों की सूची लोड हो रही है...",
    noTrainsFoundTemplate = "❌ {FROM} से {TO} रूट पर कोई ट्रेन नहीं मिली।",
    trainNoLabel = "ट्रेन नंबर दर्ज करें",
    trainNoPlaceholder = "जैसे: 12345",
    favoriteToggleDesc = "पसंदीदा सूची में जोड़ें/हटाएं",
    favoritesSectionTitle = "पसंदीदा ट्रेनें",
    recentSectionTitle = "हाल की ट्रेनें",
    recentRoutesTitle = "हाल के रूट",
    startTrackingBtn = "ट्रैक करें",
    stopTrackingBtn = "ट्रैकिंग बंद करें",
    officialNtesBtn = "आधिकारिक NTES",
    routeSectionTitle = "ट्रेन का रूट और वर्तमान स्थिति",
    routeEmptyPlaceholder = "ट्रेन डेटा लोड होने पर रूट दिखेगा...",
    routeNoStations = "रूट स्टेशन नहीं मिले।",
    tapStationHint = "मैप पर देखने या अलार्म सेट करने के लिए स्टेशन पर टैप करें",
    delayOnTime = "समय पर",
    delayLateTemplate = "+{MIN}मि देरी",
    delayEarlyTemplate = "-{MIN}मि पहले",
    viewOnMapBtn = "मैप पर देखें",
    setAlarmBtn = "अलार्म सेट करें",
    alarmActiveBtn = "अलार्म चालू",
    alarmBannerTemplate = "🔔 अलार्म सेट: {ST} (3 किमी के भीतर अलर्ट)",
    alarmTriggeredTemplate = "🔔 स्टेशन अलर्ट: ट्रेन {TR} आपके गंतव्य {ST} के पास पहुँच गई है!",
    dismissAlarmBtn = "अलार्म बंद",
    distanceFromTrainTemplate = "ट्रेन से दूरी: {DIST} किमी",
    trainLiveTitle = "Train Live Data",
    waitingLabel = "प्रतीक्षा कर रहा है...",
    mapSectionTitle = "ट्रेन और उपयोगकर्ता की लाइव स्थिति",
    mapSmallBtn = "छोटा",
    mapLargeBtn = "बड़ा",
    mapFullscreenBtn = "Fullscreen",
    mapExitFullscreenBtn = "बंद करें",
    mapCenterBtn = "Center",
    backgroundTrackingTitle = "नोटिफिकेशन ट्रैकिंग (स्क्रीन बंद रहने पर भी)",
    backgroundTrackingDesc = "स्क्रीन बंद होने पर भी नोटिफिकेशन के माध्यम से ट्रेन की लोकेशन ट्रैक करेगा",
    distanceMovementTitle = "Distance & Movement",
    searchingLiveTemplate = "⏳ ट्रेन {TR} की लाइव जानकारी खोजी जा रही है...",
    retryingSignal = "📡 सिग्नल खोजा जा रहा है, पुनः प्रयास जारी...",
    trackingStopped = "⏹ ट्रैकिंग बंद कर दी गई है।",
    apiTimeoutStopped = "⏹️ API-only ट्रैकिंग 20 मिनट बाद बंद हो गई।",
    otherUserGpsMsg = "📡 अन्य यात्री के GPS से ट्रेन की स्थिति दिखाई जा रही है।",
    gpsVerifiedInsideMsg = "📱 GPS सत्यापित। गति और दिशा के अनुसार आप ट्रेन के अंदर हैं।",
    trainLocSetFromUserGps = "📍 ट्रेन की लोकेशन आपके लाइव GPS से सेट की गई है।",
    offsetTemplate = "📏 ट्रेन से ऑफ़सेट: बगल में {CROSS}m, लंबाई में {ALONG}m",
    gpsSlowNetworkFallback = "📡 GPS सिग्नल धीमा है—नेटवर्क लोकेशन का प्रयास जारी...",
    gpsLowAccuracyTemplate = "सिग्नल मिला, लेकिन सटीकता ±{ACC}m",
    gpsPermissionDenied = "❌ लोकेशन अनुमति बंद है। कृपया लोकेशन अनुमति दें।",
    gpsDisabledOrUnavailable = "❌ लोकेशन उपलब्ध नहीं है। फ़ोन का GPS चालू करें।",
    grantPermissionBtn = "लोकेशन अनुमति दें",
    sourceYourGps = "📱 आपका लाइव GPS",
    sourcePassengerGps = "📱 अन्य यात्री का GPS",
    sourceApiKey = "🔑 API Key",
    labelTrain = "Train",
    labelCurrent = "Current",
    labelNext = "Next",
    labelSource = "Source",
    labelSpeed = "Speed",
    labelTrainDistance = "Train Distance",
    labelTrackOffset = "Track Offset",
    labelLengthOffset = "Length Offset",
    labelUserSpeed = "User Speed",
    labelTrainSpeed = "Train Speed",
    labelHeadingDiff = "Heading Diff",
    unknownStation = "अज्ञात",
    userPopupLabel = "आपकी वर्तमान स्थिति",
  )

  else -> LocalizedStrings(
    appTitle = "Smart Live Train Tracker",
    appSubtitle = "Live Train • User GPS • Distance • Route",
    languageLabel = "Language",
    searchLanguageHint = "Search language (100+ languages)...",
    fromStationLabel = "From Station",
    toStationLabel = "To Station",
    stationPlaceholder = "Enter station name or code",
    swapStationsDesc = "Swap stations",
    loadingTrainsBetween = "⏳ Loading trains between stations...",
    noTrainsFoundTemplate = "❌ No trains found from {FROM} to {TO}.",
    trainNoLabel = "Enter Train Number",
    trainNoPlaceholder = "e.g. 12345",
    favoriteToggleDesc = "Toggle favorite train",
    favoritesSectionTitle = "Favorite Trains",
    recentSectionTitle = "Recent Trains",
    recentRoutesTitle = "Recent Routes",
    startTrackingBtn = "Track Train",
    stopTrackingBtn = "Stop Tracking",
    officialNtesBtn = "Official NTES",
    routeSectionTitle = "Train Route & Current Position",
    routeEmptyPlaceholder = "Route will appear once train data loads...",
    routeNoStations = "No route stations found.",
    tapStationHint = "Tap any station to focus on map or set a wake-up alarm",
    delayOnTime = "On Time",
    delayLateTemplate = "+{MIN}m Late",
    delayEarlyTemplate = "-{MIN}m Early",
    viewOnMapBtn = "View on Map",
    setAlarmBtn = "Set Alarm",
    alarmActiveBtn = "Alarm Active",
    alarmBannerTemplate = "🔔 Alarm active for {ST} (alerts within 3 km)",
    alarmTriggeredTemplate = "🔔 Wake-up Alert: Train {TR} is approaching {ST}!",
    dismissAlarmBtn = "Dismiss",
    distanceFromTrainTemplate = "Distance from train: {DIST} km",
    trainLiveTitle = "Train Live Data",
    waitingLabel = "Waiting...",
    mapSectionTitle = "Live Train & User Location",
    mapSmallBtn = "Small",
    mapLargeBtn = "Large",
    mapFullscreenBtn = "Fullscreen",
    mapExitFullscreenBtn = "Exit",
    mapCenterBtn = "Center",
    backgroundTrackingTitle = "Notification Tracking (Even Screen Off)",
    backgroundTrackingDesc = "Tracks live train location & station alarm in notifications even when screen is off",
    distanceMovementTitle = "Distance & Movement",
    searchingLiveTemplate = "⏳ Fetching live data for train {TR}...",
    retryingSignal = "📡 Searching signal, retrying automatically...",
    trackingStopped = "⏹ Tracking stopped.",
    apiTimeoutStopped = "⏹️ API-only tracking stopped after 20 minutes.",
    otherUserGpsMsg = "📡 Showing train location from another passenger's GPS.",
    gpsVerifiedInsideMsg = "📱 GPS verified. Speed and heading match — you are inside the train.",
    trainLocSetFromUserGps = "📍 Train location updated from your live GPS.",
    offsetTemplate = "📏 Offset from train: {CROSS}m lateral, {ALONG}m along track",
    gpsSlowNetworkFallback = "📡 GPS signal slow—trying network/Wi-Fi location...",
    gpsLowAccuracyTemplate = "Signal acquired, low accuracy ±{ACC}m",
    gpsPermissionDenied = "❌ Location permission is off. Please allow Location access.",
    gpsDisabledOrUnavailable = "❌ Location unavailable. Please enable device GPS/Location.",
    grantPermissionBtn = "Allow Location",
    sourceYourGps = "📱 Your Live GPS",
    sourcePassengerGps = "📱 Passenger GPS",
    sourceApiKey = "🔑 API Key",
    labelTrain = "Train",
    labelCurrent = "Current",
    labelNext = "Next",
    labelSource = "Source",
    labelSpeed = "Speed",
    labelTrainDistance = "Train Distance",
    labelTrackOffset = "Track Offset",
    labelLengthOffset = "Length Offset",
    labelUserSpeed = "User Speed",
    labelTrainSpeed = "Train Speed",
    labelHeadingDiff = "Heading Diff",
    unknownStation = "Unknown",
    userPopupLabel = "Your Current Location",
  )
}

data class StationDbEntry(
  val name: String,
  val code: String,
  val lat: Double?,
  val lon: Double?,
)

data class TrainBetweenItem(
  val trainNumber: String,
  val trainName: String,
  val departure: String,
  val arrival: String,
  val distanceKm: Double,
)

data class RouteStation(
  val stationCode: String,
  val stationName: String,
  val sequence: Int?,
  val lat: Double?,
  val lon: Double?,
  val scheduledTimeRaw: String?,
  val arrival: String?,
  val departure: String?,
)

data class CurrentLocationDto(
  val stationCode: String?,
  val stationName: String?,
  val sequence: Int?,
  val lat: Double?,
  val lon: Double?,
  val speedKmh: Double?,
  val bearingDegrees: Double?,
  val segmentProgress: Double?,
  val nextStationName: String?,
)

data class LiveTrainPayload(
  val trainName: String?,
  val source: String?,
  val currentLocation: CurrentLocationDto?,
  val route: List<RouteStation>,
)

data class LocatedTrain(
  val lat: Double,
  val lon: Double,
  val name: String,
  val next: String,
  val speedKmh: Double,
  val bearingDegrees: Double,
  val idx: Int,
)

data class GpsSample(
  val lat: Double,
  val lon: Double,
  val accuracy: Float,
  val speedMetersPerSec: Float?,
  val headingDegrees: Float?,
  val timeMillis: Long,
)

data class MovementStats(
  val speedKmh: Double,
  val bearingDegrees: Double,
)

data class VerificationMetrics(
  val distanceKm: Double,
  val crossTrackKm: Double,
  val alongTrackKm: Double,
  val headingDiffDeg: Double,
  val confidencePercent: Int,
  val isMovingWithTrain: Boolean,
  val isWithinBounds: Boolean,
  val recognizedInside: Boolean,
)

enum class BannerType {
  INFO,
  OK,
  BAD,
}

data class StatusBanner(
  val message: String,
  val type: BannerType,
)

enum class MapSizePreset(val heightDp: Int) {
  SMALL(280),
  LARGE(460),
}

data class TrackerUiState(
  val languageCode: String = "bn",
  val isTranslatingLanguage: Boolean = false,
  val strings: LocalizedStrings = stringsForCode("bn"),
  val fromInput: String = "",
  val toInput: String = "",
  val fromSuggestions: List<StationDbEntry> = emptyList(),
  val toSuggestions: List<StationDbEntry> = emptyList(),
  val stationDbLoaded: Boolean = false,
  val betweenTrains: List<TrainBetweenItem> = emptyList(),
  val betweenLoading: Boolean = false,
  val betweenError: String? = null,
  val trainNoInput: String = "",
  val favoriteTrains: List<SavedTrainEntry> = emptyList(),
  val recentTrains: List<SavedTrainEntry> = emptyList(),
  val recentRoutes: List<SavedRoutePair> = emptyList(),
  val running: Boolean = false,
  val insideTrain: Boolean = false,
  val trainName: String? = null,
  val rawSource: String? = null,
  val route: List<RouteStation> = emptyList(),
  val locatedTrain: LocatedTrain? = null,
  val selectedStationIndex: Int? = null,
  val stationAlarm: StationAlarmState? = null,
  val alarmAlertMessage: String? = null,
  val focusedMapStation: FocusedMapStation? = null,
  val backgroundTrackingEnabled: Boolean = false,
  val latestUserGps: GpsSample? = null,
  val movementStats: MovementStats = MovementStats(0.0, 0.0),
  val verification: VerificationMetrics? = null,
  val lowAccuracyMeters: Int? = null,
  val locationPermissionGranted: Boolean = false,
  val needsLocationPermissionPrompt: Boolean = false,
  val needsNotificationPermissionPrompt: Boolean = false,
  val statusBanner: StatusBanner? = null,
  val resultBanner: StatusBanner? = null,
  val mapSizePreset: MapSizePreset = MapSizePreset.SMALL,
  val mapFullscreen: Boolean = false,
  val recenterSignal: Int = 0,
)
