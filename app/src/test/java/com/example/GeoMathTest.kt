package com.example

import com.example.model.CurrentLocationDto
import com.example.model.GpsSample
import com.example.model.LocatedTrain
import com.example.model.MovementStats
import com.example.model.RouteStation
import com.example.model.StationDbEntry
import com.example.util.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

  @Test
  fun extractStationCode_handlesParenthesesAndDbMatch() {
    val db = listOf(
      StationDbEntry("HOWRAH JN", "HWH", 22.583, 88.342),
      StationDbEntry("NEW JALPAIGURI", "NJP", 26.682, 88.443),
    )
    assertEquals("NJP", GeoMath.extractStationCode("NEW JALPAIGURI (NJP)", db))
    assertEquals("HWH", GeoMath.extractStationCode("howrah jn", db))
    assertEquals("SDAH", GeoMath.extractStationCode("sdah", db))
  }

  @Test
  fun filterStationSuggestions_ranksExactCodeFirstEvenBehindSubstrings() {
    val db = listOf(
      StationDbEntry("CHAMPAPUKUR", "CQR", 22.6, 88.8),
      StationDbEntry("CHAMPA", "CPH", 22.0, 82.6),
      StationDbEntry("RAMPURHAT", "RPH", 24.1, 87.7),
      StationDbEntry("SAMPURNA", "SMP", 23.0, 87.0),
      StationDbEntry("AHMADPUR JN", "AMP", 23.8, 87.7),
    )
    val results = GeoMath.filterStationSuggestions("amp", db, limit = 2)
    assertEquals("AMP", results.first().code)
    assertEquals("AHMADPUR JN", results.first().name)
    assertEquals("AHMADPUR JN", GeoMath.resolveStation("amp", db)?.name)
  }

  @Test
  fun verification_rejectsStationaryAndAcceptsMovingAlignedGps() {
    val train = LocatedTrain(
      lat = 22.5700,
      lon = 88.3600,
      name = "Howrah",
      next = "Bandel",
      speedKmh = 60.0,
      bearingDegrees = 10.0,
      idx = 0,
    )

    val movingSample = GpsSample(
      lat = 22.5710,
      lon = 88.3602,
      accuracy = 10f,
      speedMetersPerSec = 15f,
      headingDegrees = 15f,
      timeMillis = 1_000_000L,
    )
    val movingMetrics = GeoMath.computeVerification(
      user = movingSample,
      movement = MovementStats(speedKmh = 54.0, bearingDegrees = 15.0, netDisplacementKm = 0.08),
      train = train,
    )
    assertTrue(movingMetrics.recognizedInside)
    assertEquals(95, movingMetrics.confidencePercent)

    // Stationary phone right next to the train MUST NOT track the train
    val stationaryNearSample = GpsSample(
      lat = 22.5705,
      lon = 88.3600,
      accuracy = 8f,
      speedMetersPerSec = 0f,
      headingDegrees = 0f,
      timeMillis = 1_000_000L,
    )
    val nearMetrics = GeoMath.computeVerification(
      user = stationaryNearSample,
      movement = MovementStats(speedKmh = 0.0, bearingDegrees = 0.0, netDisplacementKm = 0.0),
      train = train,
    )
    assertFalse(nearMetrics.recognizedInside)
    assertEquals(20, nearMetrics.confidencePercent)

    val farSample = GpsSample(
      lat = 22.6200,
      lon = 88.4000,
      accuracy = 12f,
      speedMetersPerSec = 0f,
      headingDegrees = 0f,
      timeMillis = 1_000_000L,
    )
    val farMetrics = GeoMath.computeVerification(
      user = farSample,
      movement = MovementStats(speedKmh = 0.0, bearingDegrees = 0.0),
      train = train,
    )
    assertFalse(farMetrics.recognizedInside)
    assertEquals(20, farMetrics.confidencePercent)
  }

  @Test
  fun stoppageDistances_computePassedAndRemainingCorrectly() {
    val route = listOf(
      RouteStation("HWH", "Howrah", 1, 22.000, 88.000, "10:00", "10:00", "10:05"),
      RouteStation("BWN", "Barddhaman", 2, 22.100, 88.000, "10:20", "10:20", "10:22"),
      RouteStation("RPH", "Rampurhat", 3, 22.200, 88.000, "10:45", "10:45", "10:47"),
    )

    // Mid-way between HWH (22.000) and BWN (22.100) — ~4.45 km past HWH, ~6.67 km before BWN
    val midLat = 22.040
    val progressedIdx = GeoMath.resolveProgressStationIndex(midLat, 88.000, route, 0)
    assertEquals(0, progressedIdx)

    val train = LocatedTrain(midLat, 88.000, "Howrah", "Barddhaman", 60.0, 0.0, progressedIdx)
    val distances = GeoMath.computeStoppageDistances(train, route)
    assertNotNull(distances.passedFromCurrentKm)
    assertNotNull(distances.remainingToNextKm)
    assertTrue(distances.passedFromCurrentKm!! in 4.0..5.0)
    assertTrue(distances.remainingToNextKm!! in 6.0..7.2)

    // Arriving within 250m of BWN advances active index to BWN
    val arrivedIdx = GeoMath.resolveProgressStationIndex(22.099, 88.000, route, 0)
    assertEquals(1, arrivedIdx)
  }

  @Test
  fun locateTrain_interpolatesSegmentProgressWhenCoordinatesOmitted() {
    val route = listOf(
      RouteStation("HWH", "Howrah", 1, 22.0, 88.0, "10:00", "10:00", "10:05"),
      RouteStation("BWN", "Barddhaman", 2, 23.0, 88.0, "11:30", "11:30", "11:32"),
    )
    val located = GeoMath.locateTrain(
      currentLocation = CurrentLocationDto(
        stationCode = "HWH",
        stationName = null,
        sequence = null,
        lat = null,
        lon = null,
        speedKmh = 72.0,
        bearingDegrees = null,
        segmentProgress = 0.5,
        nextStationName = null,
      ),
      route = route,
    )
    assertEquals(22.5, located.lat, 0.001)
    assertEquals(88.0, located.lon, 0.001)
    assertEquals("Howrah", located.name)
    assertEquals("Barddhaman", located.next)
  }

  @Test
  fun extractStationTime_and_calculateEta_workCorrectly() {
    val station = RouteStation("NJP", "New Jalpaiguri", 1, 26.68, 88.44, "2026-10-04 22:58:00", null, null)
    assertEquals("22:58", GeoMath.extractStationTime(station))

    val train = LocatedTrain(26.0, 88.44, "Malda", "NJP", 60.0, 0.0, 0)
    val eta = GeoMath.calculateEta(train, station, 60.0, nowMillis = 1_700_000_000_000L)
    assertNotNull(eta)
    assertTrue(Regex("""\d{2}:\d{2}""").matches(eta!!))
  }

  @Test
  fun computeDelayStatus_handlesOnTimeDelayedEarlyAndMidnightWrap() {
    assertEquals(
      com.example.model.DelayStatus.OnTime,
      GeoMath.computeDelayStatus("10:30", "10:32"),
    )
    assertEquals(
      com.example.model.DelayStatus.Delayed(20),
      GeoMath.computeDelayStatus("10:30", "10:50"),
    )
    assertEquals(
      com.example.model.DelayStatus.Early(10),
      GeoMath.computeDelayStatus("10:30", "10:20"),
    )
    assertEquals(
      com.example.model.DelayStatus.Delayed(15),
      GeoMath.computeDelayStatus("23:55", "00:10"),
    )
    assertEquals(
      com.example.model.DelayStatus.Unknown,
      GeoMath.computeDelayStatus("--:--", "10:30"),
    )
  }
}
