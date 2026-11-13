package com.example.util

import com.example.model.CurrentLocationDto
import com.example.model.GpsSample
import com.example.model.LocatedTrain
import com.example.model.MovementStats
import com.example.model.RouteStation
import com.example.model.StationDbEntry
import com.example.model.VerificationMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
  private val TIME_REGEX = Regex("""(\d{1,2}:\d{2})""")
  private val PAREN_CODE_REGEX = Regex("""\(\s*([A-Za-z0-9]+)\s*\)""")

  fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sinLat = sin(dLat / 2.0)
    val sinLon = sin(dLon / 2.0)
    val q = sinLat * sinLat +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinLon * sinLon
    return r * 2.0 * atan2(sqrt(q), sqrt(1.0 - q))
  }

  fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = PI / 180.0
    val y = sin((lon2 - lon1) * r) * cos(lat2 * r)
    val x = cos(lat1 * r) * sin(lat2 * r) -
      sin(lat1 * r) * cos(lat2 * r) * cos((lon2 - lon1) * r)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
  }

  fun extractStationCode(input: String, stationDb: List<StationDbEntry>): String {
    val v = input.trim()
    if (v.isEmpty()) return ""
    val parenMatch = PAREN_CODE_REGEX.find(v)
    if (parenMatch != null) {
      return parenMatch.groupValues[1].trim().uppercase(Locale.US)
    }
    val upper = v.uppercase(Locale.US)
    val dbMatch = stationDb.firstOrNull {
      it.name.uppercase(Locale.US) == upper || it.code == upper
    }
    if (dbMatch != null) return dbMatch.code
    return upper
  }

  fun filterStationSuggestions(
    query: String,
    stationDb: List<StationDbEntry>,
    limit: Int = 15,
  ): List<StationDbEntry> {
    val valUpper = query.trim().uppercase(Locale.US)
    if (valUpper.length < 2) return emptyList()
    return stationDb.asSequence()
      .filter { it.code.contains(valUpper) || it.name.uppercase(Locale.US).contains(valUpper) }
      .take(limit)
      .toList()
  }

  fun extractStationTime(station: RouteStation): String {
    val raw = station.scheduledTimeRaw?.takeIf { it.isNotBlank() } ?: "--:--"
    if (raw == "--:--") return raw
    val match = TIME_REGEX.find(raw)
    return match?.groupValues?.getOrNull(1) ?: raw.take(5)
  }

  fun calculateEta(
    trainPos: LocatedTrain?,
    station: RouteStation,
    trainSpeedKmh: Double,
    nowMillis: Long = System.currentTimeMillis(),
  ): String? {
    if (trainPos == null || station.lat == null || station.lon == null) return null
    if (!trainPos.lat.isFinite() || !trainPos.lon.isFinite()) return null

    val distKm = haversineKm(trainPos.lat, trainPos.lon, station.lat, station.lon)
    val effectiveSpeed = if (trainSpeedKmh > 15.0) trainSpeedKmh else 45.0
    val trackDist = distKm * 1.2
    val timeInMinutes = ((trackDist / effectiveSpeed) * 60.0).roundToInt()

    val arrivalDate = Date(nowMillis + timeInMinutes * 60_000L)
    val sdf = SimpleDateFormat("HH:mm", Locale.US)
    return sdf.format(arrivalDate)
  }

  fun computeMovement(samples: List<GpsSample>): MovementStats {
    if (samples.size < 2) return MovementStats(0.0, 0.0)
    val a = samples[samples.size - 2]
    val b = samples.last()
    val dtHours = (b.timeMillis - a.timeMillis) / 3_600_000.0
    val computedSpeed = if (dtHours > 0.0) {
      haversineKm(a.lat, a.lon, b.lat, b.lon) / dtHours
    } else {
      0.0
    }
    val speedKmh = if (b.speedMetersPerSec != null && b.speedMetersPerSec >= 0f) {
      b.speedMetersPerSec * 3.6
    } else {
      computedSpeed
    }
    val bearing = if (b.headingDegrees != null && b.headingDegrees >= 0f) {
      b.headingDegrees.toDouble()
    } else {
      bearingDegrees(a.lat, a.lon, b.lat, b.lon)
    }
    return MovementStats(
      speedKmh = if (speedKmh.isFinite()) speedKmh else 0.0,
      bearingDegrees = if (bearing.isFinite()) bearing else 0.0,
    )
  }

  fun computeVerification(
    user: GpsSample,
    movement: MovementStats,
    train: LocatedTrain,
  ): VerificationMetrics {
    val d = haversineKm(user.lat, user.lon, train.lat, train.lon)
    val trainBearing = train.bearingDegrees
    val userHeading = if (movement.bearingDegrees.isFinite()) movement.bearingDegrees else 0.0

    var angleDiff = abs(userHeading - trainBearing)
    if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff

    val isMovingWithTrain = (movement.speedKmh >= 8.0) && (angleDiff <= 55.0)
    val userBearing = bearingDegrees(train.lat, train.lon, user.lat, user.lon)
    val relAngleRad = Math.toRadians(userBearing - trainBearing)
    val crossTrack = abs(d * sin(relAngleRad))
    val alongTrack = abs(d * cos(relAngleRad))
    val isWithinBounds = d <= 0.2 // 200m
    val recognizedInside = isMovingWithTrain || isWithinBounds
    val confidence = when {
      isMovingWithTrain -> 95
      isWithinBounds -> 85
      else -> 20
    }

    return VerificationMetrics(
      distanceKm = d,
      crossTrackKm = crossTrack,
      alongTrackKm = alongTrack,
      headingDiffDeg = angleDiff,
      confidencePercent = confidence,
      isMovingWithTrain = isMovingWithTrain,
      isWithinBounds = isWithinBounds,
      recognizedInside = recognizedInside,
    )
  }

  fun locateTrain(
    currentLocation: CurrentLocationDto?,
    route: List<RouteStation>,
    fallbackStationLabel: String = "Current Location",
  ): LocatedTrain {
    val c = currentLocation
    val rawCode = (c?.stationCode ?: "").trim().uppercase(Locale.US)
    val rawName = (c?.stationName ?: "").trim().uppercase(Locale.US)
    val seq = c?.sequence
    var idx = -1

    if (rawCode.isNotEmpty()) {
      idx = route.indexOfFirst { it.stationCode.trim().uppercase(Locale.US) == rawCode }
    }
    if (idx < 0 && rawName.isNotEmpty()) {
      idx = route.indexOfFirst {
        val sn = it.stationName.trim().uppercase(Locale.US)
        sn == rawName || sn.contains(rawName) || rawName.contains(sn)
      }
    }
    if (idx < 0 && seq != null) {
      idx = route.indexOfFirst { it.sequence == seq }
    }
    val cLat = c?.lat
    val cLon = c?.lon
    if (idx < 0 && cLat != null && cLon != null && route.isNotEmpty()) {
      var minD = Double.POSITIVE_INFINITY
      route.forEachIndexed { k, st ->
        if (st.lat != null && st.lon != null) {
          val dVal = haversineKm(cLat, cLon, st.lat, st.lon)
          if (dVal < minD) {
            minD = dVal
            idx = k
          }
        }
      }
    }
    if (idx < 0) idx = 0

    val stationAtIdx = route.getOrNull(idx)
    val nextStation = route.getOrNull(idx + 1)
    val stName = c?.stationName?.takeIf { it.isNotBlank() }
      ?: stationAtIdx?.stationName?.takeIf { it.isNotBlank() }
      ?: stationAtIdx?.stationCode?.takeIf { it.isNotBlank() }
      ?: fallbackStationLabel
    val nextName = nextStation?.stationName?.takeIf { it.isNotBlank() }
      ?: nextStation?.stationCode
      ?: ""

    if (cLat != null && cLon != null && cLat.isFinite() && cLon.isFinite()) {
      return LocatedTrain(
        lat = cLat,
        lon = cLon,
        name = stName,
        next = nextName,
        speedKmh = c.speedKmh ?: 0.0,
        bearingDegrees = c.bearingDegrees ?: 0.0,
        idx = idx,
      )
    }

    val a = route.getOrNull(idx)
    val b = route.getOrNull(idx + 1)
    val p = (c?.segmentProgress ?: 0.0).coerceIn(0.0, 1.0)
    if (a?.lat != null && a.lon != null && b?.lat != null && b.lon != null) {
      return LocatedTrain(
        lat = a.lat + (b.lat - a.lat) * p,
        lon = a.lon + (b.lon - a.lon) * p,
        name = stName,
        next = nextName,
        speedKmh = c?.speedKmh ?: 0.0,
        bearingDegrees = c?.bearingDegrees ?: bearingDegrees(a.lat, a.lon, b.lat, b.lon),
        idx = idx,
      )
    }

    if (a?.lat != null && a.lon != null) {
      return LocatedTrain(
        lat = a.lat,
        lon = a.lon,
        name = stName,
        next = nextName,
        speedKmh = c?.speedKmh ?: 0.0,
        bearingDegrees = c?.bearingDegrees ?: 0.0,
        idx = idx,
      )
    }

    throw IllegalStateException("Live coordinates missing from API response.")
  }

  fun findNearestRouteStationIndex(lat: Double, lon: Double, route: List<RouteStation>, defaultIdx: Int): Int {
    if (route.isEmpty()) return defaultIdx
    var minD = Double.POSITIVE_INFINITY
    var bestIdx = defaultIdx
    route.forEachIndexed { k, st ->
      if (st.lat != null && st.lon != null) {
        val d = haversineKm(lat, lon, st.lat, st.lon)
        if (d < minD) {
          minD = d
          bestIdx = k
        }
      }
    }
    return bestIdx
  }

  fun computeSegmentProgress(train: LocatedTrain, route: List<RouteStation>): Double {
    val idx = train.idx
    if (idx < 0 || idx >= route.size - 1) return 0.0
    val a = route[idx]
    val b = route[idx + 1]
    if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) return 0.0
    val totalDist = haversineKm(a.lat, a.lon, b.lat, b.lon)
    val coveredDist = haversineKm(a.lat, a.lon, train.lat, train.lon)
    return if (totalDist > 0.0 && coveredDist < totalDist) {
      (coveredDist / totalDist).coerceIn(0.0, 0.95)
    } else {
      0.0
    }
  }
}
