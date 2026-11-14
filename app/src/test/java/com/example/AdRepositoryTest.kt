package com.example

import com.example.data.AdRepository
import com.example.model.AdItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRepositoryTest {
  private val repo = AdRepository()

  @Test
  fun parseAdsCsv_parsesAllExpectedColumnsAndQuotes() {
    val csv = """
      Image_name,Ads_area_in_App,Latitude,Longitude,Radius_KM,Contact_Number,link,Address,Time_to_Start_ads,Time_to_Stop_ads,From_Date,To_Date
      "banner1.jpg","ads1, ads3",23.8359,87.7033,10,"+91 98765 43210","aionline.in.net","Station Road, Ahmadpur",08:00 AM,08:30 PM,14/11/2026,2026-12-31
    """.trimIndent()

    val parsed = repo.parseAdsCsv(csv)
    assertEquals(1, parsed.size)
    val ad = parsed.first()
    assertEquals("banner1.jpg", ad.imageName)
    assertEquals(setOf("ads1", "ads3"), ad.areaKeys)
    assertEquals(23.8359, ad.latitude!!, 0.0001)
    assertEquals(87.7033, ad.longitude!!, 0.0001)
    assertEquals(10.0, ad.radiusKm!!, 0.0001)
    assertEquals("+91 98765 43210", ad.contactNumber)
    assertEquals("https://aionline.in.net", ad.normalizedWebsiteUrl())
    assertEquals("+919876543210", ad.cleanPhoneForDial())
    assertEquals("919876543210", ad.cleanPhoneForWhatsApp())
    assertEquals(480, ad.timeStartMinutes)
    assertEquals(1230, ad.timeStopMinutes)
    assertEquals(20261114, ad.fromDateYmd)
    assertEquals(20261231, ad.toDateYmd)
  }

  @Test
  fun parseTimeAndDate_handlesAmPm24HrAndIndianDates() {
    assertEquals(0, repo.parseTimeToMinutes("12:00 AM"))
    assertEquals(720, repo.parseTimeToMinutes("12:00 PM"))
    assertEquals(1355, repo.parseTimeToMinutes("22:35"))
    assertEquals(720, repo.parseTimeToMinutes("0.5"))
    assertNull(repo.parseTimeToMinutes(""))

    assertEquals(20261114, repo.parseDateToYmd("14/11/2026"))
    assertEquals(20261114, repo.parseDateToYmd("2026-11-14"))
    assertEquals(20260305, repo.parseDateToYmd("Date(2026,2,5)"))
  }

  @Test
  fun adEligibility_enforcesSlotDateTimeAndGeoRadius() {
    val geoAd = AdItem(
      imageName = "promo.png",
      areaKeys = setOf("ads2", "ads4"),
      latitude = 23.8359,
      longitude = 87.7033,
      radiusKm = 5.0,
      contactNumber = "9876543210",
      link = "https://example.com",
      address = "Ahmadpur",
      timeStartMinutes = 22 * 60, // Overnight 22:00 -> 06:00
      timeStopMinutes = 6 * 60,
      fromDateYmd = 20261110,
      toDateYmd = 20261120,
    )

    // Matching slot, date, overnight time (23:00), and within 5 km
    assertTrue(geoAd.isEligible("ads2", 20261114, 23 * 60, 23.8400, 87.7100))
    // Matching slot & early morning overnight window (05:00)
    assertTrue(geoAd.isEligible("ads4", 20261114, 5 * 60, 23.8359, 87.7033))
    // Wrong slot
    assertFalse(geoAd.isEligible("ads1", 20261114, 23 * 60, 23.8359, 87.7033))
    // Outside date range
    assertFalse(geoAd.isEligible("ads2", 20261125, 23 * 60, 23.8359, 87.7033))
    // Outside overnight time window (14:00)
    assertFalse(geoAd.isEligible("ads2", 20261114, 14 * 60, 23.8359, 87.7033))
    // Far outside 5 km radius (~100 km away in Kolkata)
    assertFalse(geoAd.isEligible("ads2", 20261114, 23 * 60, 22.5726, 88.3639))
    // Missing location when geo radius is specified
    assertFalse(geoAd.isEligible("ads2", 20261114, 23 * 60, null, null))
  }

  @Test
  fun adPhoneHelpers_normalize10DigitAndZeroPrefixedNumbers() {
    val tenDigit = AdItem("a.png", setOf("ads5"), null, null, null, "98765-43210", "", "", null, null, null, null)
    assertEquals("9876543210", tenDigit.cleanPhoneForDial())
    assertEquals("919876543210", tenDigit.cleanPhoneForWhatsApp())

    val zeroPrefixed = AdItem("a.png", setOf("ads5"), null, null, null, "09876543210", "", "", null, null, null, null)
    assertEquals("919876543210", zeroPrefixed.cleanPhoneForWhatsApp())
  }

  @Test
  fun parseAdsCsv_handlesZeroTimesAsAllDayAndRejectsHtmlLoginPages() {
    val csvWithZeros = """
      Image_name,Ads_area_in_App,Latitude,Longitude,Radius_KM,Contact_Number,link,Address,Time_to_Start_ads,Time_to_Stop_ads,From_Date,To_Date
      img1.jpg,ads1,,,,,,0,0,,
      img2.png,ads2,,,,,,,12:35:00 AM,11:40:00 PM,,
    """.trimIndent()

    // Verify exact column alignment (12 columns) with 0 and 0 times
    val alignedCsv = """
      Image_name,Ads_area_in_App,Latitude,Longitude,Radius_KM,Contact_Number,link,Address,Time_to_Start_ads,Time_to_Stop_ads,From_Date,To_Date
      img1.jpg,ads1,,,,,,,0,0,12-11-2026,13-11-2026
      img2.png,ads2,,,,,,,12:35:00 AM,11:40:00 PM,,
    """.trimIndent()

    val parsed = repo.parseAdsCsv(alignedCsv)
    assertEquals(2, parsed.size)
    val zeroAd = parsed[0]
    assertNull(zeroAd.timeStartMinutes)
    assertNull(zeroAd.timeStopMinutes)
    assertTrue(zeroAd.isEligible("ads1", 20261113, 14 * 60, null, null))

    val timedAd = parsed[1]
    assertEquals(35, timedAd.timeStartMinutes)
    assertEquals(23 * 60 + 40, timedAd.timeStopMinutes)
    assertTrue(timedAd.isEligible("ads2", 20261114, 14 * 60, null, null))

    val htmlLogin = "<!DOCTYPE html><html><head><title>Google Sheets - Sign in</title></head><body></body></html>"
    assertTrue(repo.parseAdsCsv(htmlLogin).isEmpty())
    assertTrue(repo.parseAdsCsv(csvWithZeros).size <= 2)
  }
}
