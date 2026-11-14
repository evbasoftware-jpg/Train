package com.example.model

import com.example.util.GeoMath
import java.util.Locale

enum class AdSlot(val key: String) {
  ADS1("ads1"),
  ADS2("ads2"),
  ADS3("ads3"),
  ADS4("ads4"),
  ADS5("ads5"),
}

data class AdItem(
  val imageName: String,
  val areaKeys: Set<String>,
  val latitude: Double?,
  val longitude: Double?,
  val radiusKm: Double?,
  val contactNumber: String,
  val link: String,
  val address: String,
  val timeStartMinutes: Int?,
  val timeStopMinutes: Int?,
  val fromDateYmd: Int?,
  val toDateYmd: Int?,
) {
  fun matchesSlot(slotKey: String): Boolean {
    val clean = slotKey.trim().lowercase(Locale.US)
    return areaKeys.contains(clean)
  }

  fun isEligible(
    slotKey: String,
    nowDateYmd: Int,
    nowTimeMinutes: Int,
    refLat: Double?,
    refLon: Double?,
  ): Boolean {
    if (imageName.isBlank()) return false
    if (!matchesSlot(slotKey)) return false

    if (fromDateYmd != null && nowDateYmd < fromDateYmd) return false
    if (toDateYmd != null && nowDateYmd > toDateYmd) return false

    val start = timeStartMinutes
    val stop = timeStopMinutes
    val hasTimeBounds = !(start == 0 && stop == 0)
    if (hasTimeBounds) {
      if (start != null && stop != null) {
        val inWindow = if (start <= stop) {
          nowTimeMinutes in start..stop
        } else {
          nowTimeMinutes >= start || nowTimeMinutes <= stop
        }
        if (!inWindow) return false
      } else if (start != null) {
        if (nowTimeMinutes < start) return false
      } else if (stop != null) {
        if (nowTimeMinutes > stop) return false
      }
    }

    val adLat = latitude
    val adLon = longitude
    val rad = radiusKm
    if (adLat != null && adLon != null && rad != null && rad > 0.0) {
      if (refLat == null || refLon == null || !refLat.isFinite() || !refLon.isFinite()) {
        return false
      }
      val distKm = GeoMath.haversineKm(refLat, refLon, adLat, adLon)
      if (!distKm.isFinite() || distKm > rad) return false
    }

    return true
  }

  fun normalizedWebsiteUrl(): String? {
    val raw = link.trim()
    if (raw.isEmpty()) return null
    return if (raw.startsWith("http://", ignoreCase = true) ||
      raw.startsWith("https://", ignoreCase = true)
    ) {
      raw
    } else {
      "https://$raw"
    }
  }

  fun cleanPhoneForDial(): String? {
    val trimmed = contactNumber.trim()
    if (trimmed.isEmpty()) return null
    val hasPlus = trimmed.startsWith("+")
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return if (hasPlus) "+$digits" else digits
  }

  fun cleanPhoneForWhatsApp(): String? {
    val digits = contactNumber.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return when {
      digits.length == 10 -> "91$digits"
      digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
      else -> digits
    }
  }
}
