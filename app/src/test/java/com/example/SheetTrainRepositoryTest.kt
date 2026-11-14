package com.example

import com.example.data.SheetSyncManager
import com.example.data.SheetTrainRepository
import com.example.model.RouteStation
import com.example.model.StationDbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetTrainRepositoryTest {

  private val repo = SheetTrainRepository(cacheDir = null)

  @Test
  fun parsesRealThreeSheetHeadersAndMatchesBetweenFromScheduleAndTrain() {
    val stationCsv = """
      "Station_Code","Station_Name","State","Zone","Address","Latitude","Longitude"
      "HWH","HOWRAH JN","WB","ER","","22.5839","88.3426"
      "BWN","BARDDHAMAN JN","WB","ER","","23.2504","87.8705"
      "BHP","BOLPUR S NIKTN","WB","ER","","23.6683","87.6908"
      "AMP","AHMADPUR JN","WB","ER","","23.8359","87.7033"
      "SNT","SAINTHIA","WB","ER","","23.9453","87.6806"
      "RPH","RAMPURHAT","WB","ER","","24.1707","87.7817"
      "XX-BECE","B Cabin","","","","",""
    """.trimIndent()

    val trainCsv = """
      "Train_Number","Train_Name","Train_Type","Zone","From_Station_Code","From_Station_Name","Departure","To_Station_Code","To_Station_Name","Arrival","Duration_Hours","Duration_Minutes","Distance_KM","Return_Train","First_AC","Second_AC","Third_AC","Chair_Car","First_Class","Sleeper","Route_Points_Count","Train_Stops"
      "12347","Sahid Express","EXP","ER","HWH","HOWRAH JN","11:50:00","RPH","RAMPURHAT","16:05:00","4","15","207","12348","0","0","1","1","0","1","6","HWH, BWN, BHP, AMP, SNT, RPH"
      "03017","Local Passenger","PASS","ER","BWN","BARDDHAMAN JN","06:15 AM","AMP","AHMADPUR JN","08:00 AM","1","45","90","","0","0","0","0","0","0","4",""
    """.trimIndent()

    val scheduleCsv = """
      "Schedule_ID","Train_Number","Train_Name","Station_Code","Station_Name","Arrival","Departure","Day"
      "101","03017","Local Passenger","BWN","BARDDHAMAN JN","None","06:15:00","1"
      "102","03017","Local Passenger","BHP","BOLPUR S NIKTN","07:32:00","07:34:00","1"
      "103","03017","Local Passenger","AMP","AHMADPUR JN","08:00:00","None","1"
    """.trimIndent()

    val rawStations = repo.parseStationsCsv(stationCsv)
    assertEquals(7, rawStations.size)

    // mergeStationLists skips dummy XX- rows that have no coordinates
    val stations = repo.mergeStationLists(rawStations, emptyList())
    assertEquals(6, stations.size)
    assertFalse(stations.any { it.code == "XX-BECE" })

    val trains = repo.parseTrainsCsv(trainCsv)
    assertEquals(2, trains.size)
    assertEquals("12347", trains[0].trainNumber)
    assertEquals("HWH, BWN, BHP, AMP, SNT, RPH", trains[0].trainStopsRaw)
    assertEquals(listOf("HWH", "BWN", "BHP", "AMP", "SNT", "RPH"), trains[0].stopTokens)
    assertEquals(6, trains[0].routePointsCount)

    val schedRows = repo.parseScheduleCsv(scheduleCsv)
    assertEquals(3, schedRows.size)
    assertEquals("None", schedRows[0].arrivalRaw)

    // Route built from Schedule rows preserves order & skips "None" cleanly
    val localRoute = repo.buildRouteFromScheduleRows(schedRows, stations)
    assertEquals(listOf("BWN", "BHP", "AMP"), localRoute.map { it.stationCode })
    assertEquals("06:15", localRoute[0].scheduledTimeRaw)
    assertEquals("07:32", localRoute[1].arrival)
    assertEquals("07:34", localRoute[1].departure)

    // Between search BWN -> AMP finds BOTH 03017 (via Schedule) and 12347 (via Train_Stops)
    val forward = repo.combineBetweenMatches("BWN", "AMP", trains, schedRows, stations)
    assertEquals(setOf("03017", "12347"), forward.map { it.trainNumber }.toSet())
    val sahid = forward.first { it.trainNumber == "12347" }
    val local = forward.first { it.trainNumber == "03017" }
    assertEquals("06:15", local.departure)
    assertEquals("08:00", local.arrival)
    assertEquals("Sahid Express", sahid.trainName)

    // Reverse direction does not falsely match -> returns empty so API fallback triggers
    val reverse = repo.combineBetweenMatches("AMP", "BWN", trains, schedRows, stations)
    assertTrue(reverse.isEmpty())
  }

  @Test
  fun detectsIncompleteOrTruncatedSheetRoutesAndFormatsTrainStopsCsv() {
    val trainCsv = """
      "Train_Number","Train_Name","From_Station_Code","From_Station_Name","Departure","To_Station_Code","To_Station_Name","Arrival","Distance_KM","Route_Points_Count","Train_Stops"
      "13147","UTTAR BANGA EXP","SDAH","SEALDAH","19:40","NCB","NEW COOCH BEHAR","07:05","580","121",""
      "04601","JAT Special","HWH","HOWRAH JN","10:00","AMP","AHMADPUR JN","13:00","150","4",""
    """.trimIndent()
    val trains = repo.parseTrainsCsv(trainCsv)
    val uttarBanga = trains[0]
    val special = trains[1]

    // Truncated 13147 schedule (only 2 stops ending at RCD instead of NCB, vs Route_Points_Count 121)
    val truncatedRoute = listOf(
      RouteStation("SDAH", "SEALDAH", 1, 22.56, 88.37, "19:40", "19:40", "19:40"),
      RouteStation("RCD", "RAYCHANDPUR", 2, null, null, "22:10", "22:10", "22:12"),
    )
    assertFalse(repo.isSheetRouteComplete(uttarBanga, truncatedRoute))

    // Synthetic 2-stop route from endpoints when Train_Stops is blank and Route_Points_Count != 2 is incomplete
    val twoStopOnly = listOf(
      RouteStation("HWH", "HOWRAH JN", 1, 22.58, 88.34, "10:00", "10:00", "10:00"),
      RouteStation("AMP", "AHMADPUR JN", 2, 23.83, 87.70, "13:00", "13:00", "13:00"),
    )
    assertFalse(repo.isSheetRouteComplete(special, twoStopOnly))

    // Full route matching destination and sufficient stop count is complete
    val fullSpecialRoute = listOf(
      RouteStation("HWH", "HOWRAH JN", 1, 22.58, 88.34, "10:00", "10:00", "10:00"),
      RouteStation("BWN", "BARDDHAMAN JN", 2, 23.25, 87.87, "11:15", "11:13", "11:15"),
      RouteStation("BHP", "BOLPUR S NIKTN", 3, 23.66, 87.69, "12:15", "12:13", "12:15"),
      RouteStation("AMP", "AHMADPUR JN", 4, 23.83, 87.70, "13:00", "13:00", "13:00"),
    )
    assertTrue(repo.isSheetRouteComplete(special, fullSpecialRoute))
    assertEquals("HWH, BWN, BHP, AMP", SheetSyncManager.formatTrainStopsCsv(fullSpecialRoute))
  }

  @Test
  fun mergeStationListsEnrichesMissingCoordsFromRemoteAndSkipsDummyRows() {
    val sheet = listOf(
      StationDbEntry("Custom Ahmadpur", "AMP", 23.8400, 87.7100),
      StationDbEntry("Bolpur", "BHP", null, null),
      StationDbEntry("Dummy Block", "XX-BECE", null, null),
    )
    val remote = listOf(
      StationDbEntry("Old Ahmadpur", "AMP", 23.0, 87.0),
      StationDbEntry("Bolpur Santiniketan", "BHP", 23.6683, 87.6908),
      StationDbEntry("Suri", "SURI", 23.91, 87.52),
    )
    val merged = repo.mergeStationLists(sheet, remote)
    assertEquals(3, merged.size)
    assertEquals("Custom Ahmadpur", merged[0].name)
    assertEquals(23.8400, merged[0].lat!!, 0.0001)
    assertEquals(23.6683, merged[1].lat!!, 0.0001)
    assertEquals("SURI", merged[2].code)
  }
}
