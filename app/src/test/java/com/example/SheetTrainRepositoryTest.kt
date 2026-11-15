package com.example

import com.example.data.SheetSyncManager
import com.example.data.SheetTrainRepository
import com.example.model.RouteStation
import com.example.model.RunningScheduleHelper
import com.example.model.StationDbEntry
import com.example.model.stringsForCode
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

  @Test
  fun excludesNonStopPassThroughStationsAndSortsChronologically() {
    val stations = listOf(
      StationDbEntry("BARDDHAMAN JN", "BWN", 23.2504, 87.8705),
      StationDbEntry("BOLPUR S NIKTN", "BHP", 23.6683, 87.6908),
      StationDbEntry("AHMADPUR JN", "AMP", 23.8359, 87.7033),
    )

    val scheduleCsv = """
      "Schedule_ID","Train_Number","Train_Name","Station_Code","Station_Name","Arrival","Departure","Day"
      "1","12363","KOLKATA CHITPUR - HALDIBARI SF Intercity Exp","BWN","BARDDHAMAN JN","10:34:00","10:36:00","1"
      "2","12363","KOLKATA CHITPUR - HALDIBARI SF Intercity Exp","AMP","AHMADPUR JN","11:47:00","11:47:00","1"
      "3","13161","KOLKATA CHITPUR - BALURGHAT Tebhaga Exp","BWN","BARDDHAMAN JN","14:31:00","14:34:00","1"
      "4","13161","KOLKATA CHITPUR - BALURGHAT Tebhaga Exp","AMP","AHMADPUR JN","15:51:00","15:51:00","1"
      "5","12345","HOWRAH - GUWAHATI Saraighat SF Exp","BWN","BARDDHAMAN JN","17:07:00","17:09:00","1"
      "6","12345","HOWRAH - GUWAHATI Saraighat SF Exp","AMP","AHMADPUR JN","18:18:00","18:18:00","1"
      "7","12377","Padatik Express","BWN","BARDDHAMAN JN","00:23:00","00:25:00","2"
      "8","12377","Padatik Express","AMP","AHMADPUR JN","01:37:00","01:37:00","2"
      "9","12343","Darjeeling Mail","BWN","BARDDHAMAN JN","23:43:00","23:46:00","1"
      "10","12343","Darjeeling Mail","AMP","AHMADPUR JN","00:58:00","00:58:00","2"
      "11","13071","Jamalpur Express","BWN","BARDDHAMAN JN","22:50:00","22:52:00","1"
      "12","13071","Jamalpur Express","AMP","AHMADPUR JN","00:05:00","00:05:00","2"
      "13","12374","Sealdah Rampurhat Exp","BWN","BARDDHAMAN JN","12:00:00","12:02:00","1"
      "14","12374","Sealdah Rampurhat Exp","AMP","AHMADPUR JN","None","None","1"
      "15","13173","Sabroom Kanchanjungha Express","BWN","BARDDHAMAN JN","08:34:00","08:36:00","1"
      "16","13173","Sabroom Kanchanjungha Express","AMP","AHMADPUR JN","09:44:00","09:46:00","1"
      "17","13017","Ganadevta Express","BWN","BARDDHAMAN JN","07:35:00","07:37:00","1"
      "18","13017","Ganadevta Express","BHP","BOLPUR S NIKTN","08:25:00","08:27:00","1"
      "19","13017","Ganadevta Express","AMP","AHMADPUR JN","08:53:00","08:54:00","1"
      "20","63063","Barddhaman - Sahibganj MEMU","BWN","BARDDHAMAN JN","None","06:05:00","1"
      "21","63063","Barddhaman - Sahibganj MEMU","AMP","AHMADPUR JN","07:29:00","None","1"
    """.trimIndent()

    val schedRows = repo.parseScheduleCsv(scheduleCsv)
    val matches = repo.combineBetweenMatches("BWN", "AMP", emptyList(), schedRows, stations)

    // Non-stop pass-through trains (Arr == Dep or None/None at AMP) must NOT appear
    assertEquals(listOf("63063", "13017", "13173"), matches.map { it.trainNumber })
    assertEquals(listOf("06:05", "07:37", "08:36"), matches.map { it.departure })

    // Route built for live tracking preserves pass-through station with isHalt = false so rider sees it marked
    val intercityRows = schedRows.filter { it.trainNumber == "12363" } +
      listOf(schedRows.first { it.trainNumber == "13017" && it.stationCode == "BHP" })
    val route = repo.buildRouteFromScheduleRows(intercityRows, stations)
    assertTrue(route.any { it.stationCode == "BWN" && it.isHalt })
    assertTrue(route.any { it.stationCode == "AMP" && !it.isHalt })
    // Commercial Train_Stops excludes pass-through stations (AMP)
    assertEquals("BHP, BWN", SheetSyncManager.formatTrainStopsCsv(route))
  }

  @Test
  fun parsesRunningDaysAndEvaluatesFrequencyAndTodayStatus() {
    val bn = stringsForCode("bn")
    val en = stringsForCode("en")

    val trainCsv = """
      "Train_Number","Train_Name","From_Station_Code","From_Station_Name","Departure","To_Station_Code","To_Station_Name","Arrival","Distance_KM","Route_Points_Count","Train_Stops","Running_Days"
      "63063","Barddhaman - Sahibganj MEMU","BWN","BARDDHAMAN JN","06:05","SBG","SAHIBGANJ JN","11:30","200","25","BWN, AMP","Sun, Mon, Tue, Wed, Thu, Fri, Sat"
      "13175","Silchar Kanchanjungha Express","SDAH","SEALDAH","06:35","SCL","SILCHAR","18:00","1350","40","SDAH, BWN, AMP","Sun, Tue, Thu"
      "12377","Padatik Express","SDAH","SEALDAH","23:20","NJP","NEW JALPAIGURI","09:15","565","12","SDAH, BWN","1111111"
    """.trimIndent()
    val parsed = repo.parseTrainsCsv(trainCsv)
    assertEquals("Sun, Tue, Thu", parsed[1].runningDaysRaw)

    val daily = RunningScheduleHelper.evaluate(parsed[0].runningDaysRaw, strings = bn, todayDow = 1)
    assertEquals("প্রতিদিন", daily.frequencyLabel)
    assertEquals(true, daily.runsToday)

    val bitmaskDaily = RunningScheduleHelper.evaluate(parsed[2].runningDaysRaw, strings = en, todayDow = 5)
    assertEquals("Runs Daily", bitmaskDaily.frequencyLabel)
    assertEquals(7, bitmaskDaily.daysOfWeek.size)

    val triWeeklyOnTue = RunningScheduleHelper.evaluate(parsed[1].runningDaysRaw, strings = bn, todayDow = 2)
    assertEquals("সপ্তাহে 3 দিন", triWeeklyOnTue.frequencyLabel)
    assertEquals(true, triWeeklyOnTue.runsToday)

    val triWeeklyOnMon = RunningScheduleHelper.evaluate(parsed[1].runningDaysRaw, strings = bn, todayDow = 1)
    assertEquals(false, triWeeklyOnMon.runsToday)

    val weekly = RunningScheduleHelper.evaluate("Fri", strings = bn, todayDow = 5)
    assertEquals("সাপ্তাহিক", weekly.frequencyLabel)
    assertEquals(true, weekly.runsToday)

    val monthly = RunningScheduleHelper.evaluate("Monthly", strings = bn, todayDow = 1)
    assertEquals("মাসিক", monthly.frequencyLabel)
    assertEquals(null, monthly.runsToday)
  }

  @Test
  fun originAndDestinationAreAlwaysHaltsEvenWhenCorruptedOrSameTimeAndOriginSortsFirst() {
    val stations = listOf(
      StationDbEntry("SEALDAH", "SDAH", 22.5678, 88.3705),
      StationDbEntry("DAKSHINESWAR", "DAKE", 22.6548, 88.3619),
      StationDbEntry("BARDDHAMAN JN", "BWN", 23.2504, 87.8705),
      StationDbEntry("SILCHAR", "SCL", 24.8333, 92.7789),
    )

    val trainCsv = """
      "Train_Number","Train_Name","From_Station_Code","From_Station_Name","Departure","To_Station_Code","To_Station_Name","Arrival","Distance_KM","Route_Points_Count","Train_Stops","Running_Days"
      "13175","Silchar Kanchanjungha Express","SDAH","SEALDAH","06:50","SCL","SILCHAR","14:40","1350","4","SDAH, BWN, SCL","Mon, Wed, Sat"
    """.trimIndent()
    val entry = repo.parseTrainsCsv(trainCsv).first()

    // SDAH mistakenly received 16:46 as Arrival from an old passive observer while Departure is 06:50
    // SCL destination has arr == dep (14:40 / 14:40) without Train_Stops listing intermediate DKAE
    val corruptedScheduleCsv = """
      "Schedule_ID","Train_Number","Train_Name","Station_Code","Station_Name","Arrival","Departure","Day"
      "1","13175","Silchar Kanchanjungha Express","SDAH","SEALDAH","16:46:00","06:50:00","1"
      "2","13175","Silchar Kanchanjungha Express","DAKE","DAKSHINESWAR","07:06:00","07:06:00","1"
      "3","13175","Silchar Kanchanjungha Express","BWN","BARDDHAMAN JN","08:34:00","08:36:00","1"
      "4","13175","Silchar Kanchanjungha Express","SCL","SILCHAR","14:40:00","14:40:00","2"
    """.trimIndent()

    val schedRows = repo.parseScheduleCsv(corruptedScheduleCsv)
    val route = repo.buildRouteFromScheduleRows(schedRows, stations, entry)

    assertEquals(listOf("SDAH", "DAKE", "BWN", "SCL"), route.map { it.stationCode })
    assertEquals("06:50", route[0].scheduledTimeRaw)
    assertTrue("Origin SDAH must always be a halt", route[0].isHalt)
    assertFalse("Pass-through DAKE should be non-halt", route[1].isHalt)
    assertTrue("Commercial BWN must be a halt", route[2].isHalt)
    assertTrue("Destination SCL must always be a halt", route[3].isHalt)
  }

  @Test
  fun handlesIsHaltColumnAndMetaStopsForMemuAndBetweenMatching() {
    val stations = listOf(
      StationDbEntry("BARDDHAMAN JN", "BWN", 23.2504, 87.8705),
      StationDbEntry("AHMADPUR JN", "AMP", 23.8359, 87.7033),
    )

    val scheduleCsv = """
      "Schedule_ID","Train_Number","Train_Name","Station_Code","Station_Name","Arrival","Departure","Day","Is_Halt"
      "1","63581","Barddhaman - Rampurhat MEMU","BWN","BARDDHAMAN JN","08:55:00","08:55:00","1","Y"
      "2","63581","Barddhaman - Rampurhat MEMU","AMP","AHMADPUR JN","10:20:00","10:20:00","1","Y"
      "3","12345","Saraighat SF Exp","BWN","BARDDHAMAN JN","17:07:00","17:09:00","1","Y"
      "4","12345","Saraighat SF Exp","AMP","AHMADPUR JN","18:18:00","18:18:00","1","N"
    """.trimIndent()

    val rows = repo.parseScheduleCsv(scheduleCsv)
    val matches = repo.combineBetweenMatches("BWN", "AMP", emptyList(), rows, stations)
    assertEquals(listOf("63581"), matches.map { it.trainNumber })
    assertEquals("08:55", matches[0].departure)
    assertEquals("10:20", matches[0].arrival)
  }

  @Test
  fun estimatesMultiDayLocationAlongRouteUsingIstAndRunningDays() {
    val trainCsv = """
      "Train_Number","Train_Name","From_Station_Code","From_Station_Name","Departure","To_Station_Code","To_Station_Name","Arrival","Distance_KM","Route_Points_Count","Train_Stops","Running_Days"
      "13175","Silchar Kanchanjungha Express","SDAH","SEALDAH","06:50","SCL","SILCHAR","14:40","1350","4","SDAH, BWN, AMP, SCL","Mon, Wed, Sat"
    """.trimIndent()
    val entry = repo.parseTrainsCsv(trainCsv).first()
    val route = listOf(
      RouteStation("SDAH", "SEALDAH", 1, 22.5678, 88.3705, "06:50", "06:50", "06:50", isHalt = true, day = 1),
      RouteStation("BWN", "BARDDHAMAN JN", 2, 23.2504, 87.8705, "08:36", "08:34", "08:36", isHalt = true, day = 1),
      RouteStation("AMP", "AHMADPUR JN", 3, 23.8359, 87.7033, "09:46", "09:44", "09:46", isHalt = true, day = 1),
      RouteStation("SCL", "SILCHAR", 4, 24.8333, 92.7789, "14:40", "14:40", "14:40", isHalt = true, day = 2),
    )

    // 2026-11-16 is Monday; 09:10 IST -> between BWN (08:34) and AMP (09:44)
    val istCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata")).apply {
      set(2026, java.util.Calendar.NOVEMBER, 16, 9, 10, 0)
    }
    val monMorning = repo.estimateLocationFromSheetSchedule(entry, route, istCal.timeInMillis)
    assertEquals("BWN", monMorning?.stationCode)
    assertTrue((monMorning?.segmentProgress ?: 0.0) > 0.4)
    assertTrue((monMorning?.speedKmh ?: 0.0) >= 20.0)

    // 2026-11-17 is Tuesday 09:10 IST: Monday train IS still on Day 2 between AMP (Day 1 09:44) and SCL (Day 2 14:40)!
    val tueCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata")).apply {
      set(2026, java.util.Calendar.NOVEMBER, 17, 9, 10, 0)
    }
    val tueMorning = repo.estimateLocationFromSheetSchedule(entry, route, tueCal.timeInMillis)
    assertEquals("AMP", tueMorning?.stationCode)
    assertTrue((tueMorning?.speedKmh ?: 0.0) >= 20.0)

    // 2026-11-17 Tuesday 16:50 IST: Monday train ended at 14:40, and no Tuesday departure -> idle at origin SDAH
    val tueEveCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata")).apply {
      set(2026, java.util.Calendar.NOVEMBER, 17, 16, 50, 0)
    }
    val tueEve = repo.estimateLocationFromSheetSchedule(entry, route, tueEveCal.timeInMillis)
    assertEquals("SDAH", tueEve?.stationCode)
    assertEquals(0.0, tueEve?.speedKmh ?: -1.0, 0.01)
  }
}
