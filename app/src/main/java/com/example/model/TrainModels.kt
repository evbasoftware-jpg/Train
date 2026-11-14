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
  val notifCurrentLabel: String,
  val notifNextLabel: String,
  val notifDestinationLabel: String,
  val announceCurrentNextTemplate: String,
  val announceCurrentOnlyTemplate: String,
  val passedDistanceTemplate: String,
  val labelPassedCurrent: String,
  val labelRemainingNext: String,
  val meterUnit: String,
  val kmUnit: String,
  val kmPerHourUnit: String,
  val distanceMovementTitle: String,
  val searchingLiveTemplate: String,
  val retryingSignal: String,
  val apiTimeoutStopped: String,
  val otherUserGpsMsg: String,
  val gpsVerifiedInsideMsg: String,
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
  val closeLabel: String,
  val clearLabel: String,
  val adBadgeLabel: String,
  val adDetailsTitle: String,
  val adAddressLabel: String,
  val adContactLabel: String,
  val adCallBtn: String,
  val adWhatsAppBtn: String,
  val adOpenLinkBtn: String,
  val advertiseWhatsAppBtn: String,
  val advertiseWhatsAppMsg: String,
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

  fun announceStop(current: String, next: String): String =
    if (next.isNotBlank()) {
      announceCurrentNextTemplate.replace("{CUR}", current).replace("{NXT}", next)
    } else {
      announceCurrentOnlyTemplate.replace("{CUR}", current)
    }

  fun formatDistance(km: Double): String {
    val safeKm = km.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val meters = kotlin.math.round(safeKm * 1000.0).toInt()
    return if (meters < 1000) {
      "$meters $meterUnit"
    } else {
      "${String.format(java.util.Locale.US, "%.2f", safeKm)} $kmUnit"
    }
  }

  fun formatPassedDistance(km: Double): String =
    passedDistanceTemplate.replace("{DIST}", formatDistance(km))
}

fun stringsForCode(langCode: String): LocalizedStrings = when (langCode) {
  "bn" -> LocalizedStrings(
    appTitle = "Train Live Location",
    appSubtitle = "",
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
    routeNoStations = "রুট স্টেশন পাওয়া যায়নি।",
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
    trainLiveTitle = "ট্রেনের লাইভ তথ্য",
    waitingLabel = "অপেক্ষা করছে...",
    mapSectionTitle = "ট্রেন এবং ব্যবহারকারীর লাইভ অবস্থান",
    mapSmallBtn = "ছোট",
    mapLargeBtn = "বড়",
    mapFullscreenBtn = "পূর্ণ পর্দা",
    mapExitFullscreenBtn = "বন্ধ করুন",
    mapCenterBtn = "মাঝে আনুন",
    notifCurrentLabel = "বর্তমান",
    notifNextLabel = "পরবর্তী",
    notifDestinationLabel = "গন্তব্য",
    announceCurrentNextTemplate = "অনুগ্রহ করে শুনুন। বর্তমান স্টপেজ {CUR}। পরবর্তী স্টপেজ {NXT}।",
    announceCurrentOnlyTemplate = "অনুগ্রহ করে শুনুন। বর্তমান স্টপেজ {CUR}।",
    passedDistanceTemplate = "{DIST} পেরিয়ে গেছে",
    labelPassedCurrent = "বর্তমান স্টেশন থেকে",
    labelRemainingNext = "পরবর্তী স্টেশনের দূরত্ব",
    meterUnit = "মিটার",
    kmUnit = "কিলোমিটার",
    kmPerHourUnit = "কিমি/ঘণ্টা",
    distanceMovementTitle = "দূরত্ব ও গতিবিধি",
    searchingLiveTemplate = "⏳ ট্রেন {TR}-এর লাইভ তথ্য খোঁজা হচ্ছে...",
    retryingSignal = "📡 সিগন্যাল খোঁজা হচ্ছে, পুনরায় চেষ্টা চলছে...",
    apiTimeoutStopped = "⏹️ API ট্র্যাকিং ২০ মিনিট পরে বন্ধ হয়েছে।",
    otherUserGpsMsg = "📡 অন্য যাত্রীর GPS থেকে ট্রেনের অবস্থান দেখানো হচ্ছে।",
    gpsVerifiedInsideMsg = "📱 চলমান GPS ট্রেনের গতিপথের সাথে মিলেছে — ফোনের GPS দিয়ে লাইভ ট্র্যাকিং চলছে।",
    gpsSlowNetworkFallback = "📡 GPS সিগন্যাল ধীর—নেটওয়ার্ক লোকেশন চেষ্টা করছি...",
    gpsLowAccuracyTemplate = "সিগন্যাল পাওয়া গেছে, কিন্তু accuracy ±{ACC} মিটার",
    gpsPermissionDenied = "❌ লোকেশন অনুমতি বন্ধ আছে। অনুগ্রহ করে লোকেশন অনুমতি দিন।",
    gpsDisabledOrUnavailable = "❌ লোকেশন পাওয়া যাচ্ছে না। ফোনের GPS চালু করুন।",
    grantPermissionBtn = "লোকেশন অনুমতি দিন",
    sourceYourGps = "📱 আপনার লাইভ GPS",
    sourcePassengerGps = "📱 অন্য যাত্রীর GPS",
    sourceApiKey = "🔑 সার্ভার ডেটা",
    labelTrain = "ট্রেন",
    labelCurrent = "বর্তমান",
    labelNext = "পরবর্তী",
    labelSource = "উৎস",
    labelSpeed = "গতি",
    labelTrainDistance = "ট্রেনের দূরত্ব",
    labelTrackOffset = "লাইন থেকে দূরত্ব",
    labelLengthOffset = "দৈর্ঘ্য দূরত্ব",
    labelUserSpeed = "ব্যবহারকারীর গতি",
    labelTrainSpeed = "ট্রেনের গতি",
    labelHeadingDiff = "দিক পার্থক্য",
    unknownStation = "অজ্ঞাত",
    userPopupLabel = "আপনার বর্তমান অবস্থান",
    closeLabel = "বন্ধ করুন",
    clearLabel = "মুছুন",
    adBadgeLabel = "বিজ্ঞাপন",
    adDetailsTitle = "বিজ্ঞাপনের তথ্য",
    adAddressLabel = "ঠিকানা",
    adContactLabel = "যোগাযোগ নম্বর",
    adCallBtn = "কল করুন",
    adWhatsAppBtn = "WhatsApp",
    adOpenLinkBtn = "ওয়েবসাইট খুলুন",
    advertiseWhatsAppBtn = "বিজ্ঞাপনের জন্য WhatsApp এ যোগাযোগ করুন",
    advertiseWhatsAppMsg = "আমি ট্রেন লাইভ লোকেশন অ্যাপে বিজ্ঞাপন দিতে চাই",
  )

  "hi" -> LocalizedStrings(
    appTitle = "Train Live Location",
    appSubtitle = "",
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
    trainLiveTitle = "ट्रेन लाइव डेटा",
    waitingLabel = "प्रतीक्षा कर रहा है...",
    mapSectionTitle = "ट्रेन और उपयोगकर्ता की लाइव स्थिति",
    mapSmallBtn = "छोटा",
    mapLargeBtn = "बड़ा",
    mapFullscreenBtn = "फुलस्क्रीन",
    mapExitFullscreenBtn = "बंद करें",
    mapCenterBtn = "केंद्रित करें",
    notifCurrentLabel = "वर्तमान",
    notifNextLabel = "अगला",
    notifDestinationLabel = "गंतव्य",
    announceCurrentNextTemplate = "कृपया ध्यान दें। वर्तमान स्टॉपेज {CUR}। अगला स्टॉपेज {NXT}।",
    announceCurrentOnlyTemplate = "कृपया ध्यान दें। वर्तमान स्टॉपेज {CUR}।",
    passedDistanceTemplate = "{DIST} पार कर चुकी है",
    labelPassedCurrent = "वर्तमान स्टेशन से",
    labelRemainingNext = "अगले स्टेशन की दूरी",
    meterUnit = "मीटर",
    kmUnit = "किलोमीटर",
    kmPerHourUnit = "किमी/घंटा",
    distanceMovementTitle = "दूरी और गति",
    searchingLiveTemplate = "⏳ ट्रेन {TR} की लाइव जानकारी खोजी जा रही है...",
    retryingSignal = "📡 सिग्नल खोजा जा रहा है, पुनः प्रयास जारी...",
    apiTimeoutStopped = "⏹️ API ट्रैकिंग 20 मिनट बाद बंद हो गई।",
    otherUserGpsMsg = "📡 अन्य यात्री के GPS से ट्रेन की स्थिति दिखाई जा रही है।",
    gpsVerifiedInsideMsg = "📱 चलता हुआ GPS ट्रेन से मेल खाता है — फ़ोन GPS से लाइव ट्रैकिंग चालू।",
    gpsSlowNetworkFallback = "📡 GPS सिग्नल धीमा है—नेटवर्क लोकेशन का प्रयास जारी...",
    gpsLowAccuracyTemplate = "सिग्नल मिला, सटीकता ±{ACC} मीटर",
    gpsPermissionDenied = "❌ लोकेशन अनुमति बंद है। कृपया लोकेशन अनुमति दें।",
    gpsDisabledOrUnavailable = "❌ लोकेशन उपलब्ध नहीं है। फ़ोन का GPS चालू करें।",
    grantPermissionBtn = "लोकेशन अनुमति दें",
    sourceYourGps = "📱 आपका लाइव GPS",
    sourcePassengerGps = "📱 अन्य यात्री का GPS",
    sourceApiKey = "🔑 सर्वर डेटा",
    labelTrain = "ट्रेन",
    labelCurrent = "वर्तमान",
    labelNext = "अगला",
    labelSource = "स्रोत",
    labelSpeed = "गति",
    labelTrainDistance = "ट्रेन की दूरी",
    labelTrackOffset = "ट्रैक से दूरी",
    labelLengthOffset = "लंबाई दूरी",
    labelUserSpeed = "आपकी गति",
    labelTrainSpeed = "ट्रेन की गति",
    labelHeadingDiff = "दिशा अंतर",
    unknownStation = "अज्ञात",
    userPopupLabel = "आपकी वर्तमान स्थिति",
    closeLabel = "बंद करें",
    clearLabel = "साफ़ करें",
    adBadgeLabel = "विज्ञापन",
    adDetailsTitle = "विज्ञापन विवरण",
    adAddressLabel = "पता",
    adContactLabel = "संपर्क नंबर",
    adCallBtn = "कॉल करें",
    adWhatsAppBtn = "WhatsApp",
    adOpenLinkBtn = "वेबसाइट खोलें",
    advertiseWhatsAppBtn = "विज्ञापन के लिए WhatsApp पर संपर्क करें",
    advertiseWhatsAppMsg = "मैं ट्रेन लाइव लोकेशन ऐप में विज्ञापन देना चाहता हूँ",
  )

  else -> LocalizedStrings(
    appTitle = "Train Live Location",
    appSubtitle = "",
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
    notifCurrentLabel = "Current",
    notifNextLabel = "Next",
    notifDestinationLabel = "Destination",
    announceCurrentNextTemplate = "May I have your attention please. Current stoppage is {CUR}. Next stoppage is {NXT}.",
    announceCurrentOnlyTemplate = "May I have your attention please. Current stoppage is {CUR}.",
    passedDistanceTemplate = "{DIST} passed",
    labelPassedCurrent = "From Current Station",
    labelRemainingNext = "Distance to Next Station",
    meterUnit = "m",
    kmUnit = "km",
    kmPerHourUnit = "km/h",
    distanceMovementTitle = "Distance & Movement",
    searchingLiveTemplate = "⏳ Fetching live data for train {TR}...",
    retryingSignal = "📡 Searching signal, retrying automatically...",
    apiTimeoutStopped = "⏹️ API-only tracking stopped after 20 minutes.",
    otherUserGpsMsg = "📡 Showing train location from another passenger's GPS.",
    gpsVerifiedInsideMsg = "📱 Moving GPS matches train path — tracking via your phone GPS.",
    gpsSlowNetworkFallback = "📡 GPS signal slow—trying network/Wi-Fi location...",
    gpsLowAccuracyTemplate = "Signal acquired, low accuracy ±{ACC}m",
    gpsPermissionDenied = "❌ Location permission is off. Please allow Location access.",
    gpsDisabledOrUnavailable = "❌ Location unavailable. Please enable device GPS/Location.",
    grantPermissionBtn = "Allow Location",
    sourceYourGps = "📱 Your Live GPS",
    sourcePassengerGps = "📱 Passenger GPS",
    sourceApiKey = "🔑 Server Data",
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
    closeLabel = "Close",
    clearLabel = "Clear",
    adBadgeLabel = "Ad",
    adDetailsTitle = "Ad Details",
    adAddressLabel = "Address",
    adContactLabel = "Contact Number",
    adCallBtn = "Call",
    adWhatsAppBtn = "WhatsApp",
    adOpenLinkBtn = "Visit Website",
    advertiseWhatsAppBtn = "Contact on WhatsApp for Advertising",
    advertiseWhatsAppMsg = "I want to advertise in the Train Live Location app",
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
  val netDisplacementKm: Double = 0.0,
)

data class StoppageDistances(
  val passedFromCurrentKm: Double?,
  val remainingToNextKm: Double?,
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
  val translatedNames: Map<String, String> = emptyMap(),
  val fromInput: String = "",
  val toInput: String = "",
  val fromResolvedStation: StationDbEntry? = null,
  val toResolvedStation: StationDbEntry? = null,
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
  val stoppageDistances: StoppageDistances = StoppageDistances(null, null),
  val selectedStationIndex: Int? = null,
  val stationAlarm: StationAlarmState? = null,
  val alarmAlertMessage: String? = null,
  val focusedMapStation: FocusedMapStation? = null,
  val latestUserGps: GpsSample? = null,
  val movementStats: MovementStats = MovementStats(0.0, 0.0),
  val verification: VerificationMetrics? = null,
  val lowAccuracyMeters: Int? = null,
  val locationPermissionGranted: Boolean = false,
  val needsLocationPermissionPrompt: Boolean = false,
  val needsNotificationPermissionPrompt: Boolean = false,
  val statusBanner: StatusBanner? = null,
  val mapSizePreset: MapSizePreset = MapSizePreset.SMALL,
  val mapFullscreen: Boolean = false,
  val recenterSignal: Int = 0,
  val allAds: List<AdItem> = emptyList(),
) {
  fun localizeName(raw: String): String =
    raw.takeIf { it.isNotBlank() }?.let { translatedNames[it] ?: it } ?: raw

  fun resolveDestinationRaw(): String? {
    val hasFromAndTo = fromInput.trim().isNotEmpty() && toInput.trim().isNotEmpty()
    if (hasFromAndTo) {
      toResolvedStation?.name?.takeIf { it.isNotBlank() }?.let { return it }
      val parenCode = Regex("""\(\s*([A-Za-z0-9]+)\s*\)""").find(toInput)?.groupValues?.getOrNull(1)
      val toCodeOrText = (parenCode ?: toInput).trim()
      if (toCodeOrText.isNotEmpty()) {
        route.firstOrNull {
          it.stationCode.equals(toCodeOrText, ignoreCase = true) ||
            it.stationName.equals(toCodeOrText, ignoreCase = true)
        }?.stationName?.takeIf { it.isNotBlank() }?.let { return it }
      }
      val cleaned = toInput.substringBefore('(').trim()
      return cleaned.ifBlank { toInput.trim() }
    }
    return route.lastOrNull()?.let { st ->
      st.stationName.ifBlank { st.stationCode }
    }?.takeIf { it.isNotBlank() }
  }
}
