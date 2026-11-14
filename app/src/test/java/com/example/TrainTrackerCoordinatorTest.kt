package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrainRepository
import com.example.model.GoogleLanguages
import com.example.service.TrackingNotificationContent
import com.example.service.TrainNotificationHelper
import com.example.service.TrainTrackingCoordinator
import com.example.service.TrainTrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrainTrackerCoordinatorTest {

  @Test
  fun googleLanguagesContainsOver100Languages() {
    assertTrue("Expected >= 100 languages, got ${GoogleLanguages.ALL.size}", GoogleLanguages.ALL.size >= 100)
    assertEquals("বাংলা", GoogleLanguages.findByCode("bn").nativeName)
    assertEquals("English", GoogleLanguages.findByCode("en").nativeName)
    assertEquals("हिन्दी", GoogleLanguages.findByCode("hi").nativeName)
  }

  @Test
  fun typingAmpResolvesAhmadpurJnImmediately() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val coordinator = TrainTrackingCoordinator.getInstance(context)

    coordinator.onFromInputChanged("amp")

    val state = coordinator.uiState.value
    assertNotNull(state.fromResolvedStation)
    assertEquals("AMP", state.fromResolvedStation?.code)
    assertEquals("AHMADPUR JN", state.fromResolvedStation?.name)
    assertTrue(state.fromSuggestions.isNotEmpty())
    assertEquals("AMP", state.fromSuggestions.first().code)
    assertEquals("AHMADPUR JN", state.fromSuggestions.first().name)
  }

  @Test
  fun selectingTrainBetweenStartsNotificationAndDoesNotCrash() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val coordinator = TrainTrackingCoordinator.getInstance(context)

    coordinator.onSelectTrainBetween("12345", "Saraighat Express")

    val state = coordinator.uiState.value
    assertEquals("12345", state.trainNoInput)
    assertEquals("Saraighat Express", state.trainName)
    assertTrue(state.running)
    assertFalse(TrainTrackingService.hasLocationPermission(context))

    val notif = TrainNotificationHelper.buildTrackingNotification(
      context,
      TrackingNotificationContent(
        title = "🚆 12345 • Saraighat Express",
        currentLabel = "বর্তমান",
        currentStation = "Ahmadpur Jn",
        nextLabel = "পরবর্তী",
        nextStation = "Sainthia",
        destLabel = "গন্তব্য",
        destStation = "Rampurhat",
        stopLabel = "বন্ধ",
      ),
    )
    assertNotNull(notif)
    assertEquals(0xFF1565C0.toInt(), notif.color)

    TrainTrackingService.startOrUpdate(
      context,
      TrackingNotificationContent(
        title = "🚆 12345 • Saraighat Express",
        currentLabel = "বর্তমান",
        currentStation = "Ahmadpur Jn",
        nextLabel = "পরবর্তী",
        nextStation = "Sainthia",
        destLabel = "গন্তব্য",
        destStation = "Rampurhat",
      ),
    )

    coordinator.stopTracking()
    assertFalse(coordinator.uiState.value.running)
    assertEquals(null, coordinator.uiState.value.statusBanner)
  }

  @Test
  fun resolveDestinationUsesToWhenFromAndToSetOtherwiseFinalRouteStation() {
    val route = listOf(
      com.example.model.RouteStation("HWH", "Howrah", 1, 22.58, 88.34, null, null, null),
      com.example.model.RouteStation("BWN", "Barddhaman", 2, 23.25, 87.87, null, null, null),
      com.example.model.RouteStation("SCL", "Silchar", 3, 24.83, 92.78, null, null, null),
    )

    val withoutFromTo = com.example.model.TrackerUiState(
      fromInput = "",
      toInput = "",
      route = route,
    )
    assertEquals("Silchar", withoutFromTo.resolveDestinationRaw())

    val withToOnly = com.example.model.TrackerUiState(
      fromInput = "",
      toInput = "BARDDHAMAN (BWN)",
      route = route,
    )
    assertEquals("Silchar", withToOnly.resolveDestinationRaw())

    val withBoth = com.example.model.TrackerUiState(
      fromInput = "HOWRAH JN (HWH)",
      toInput = "BARDDHAMAN (BWN)",
      route = route,
    )
    assertEquals("Barddhaman", withBoth.resolveDestinationRaw())
  }

  @Test
  fun enteringTrainNoWithoutFromToAutoStartsNotificationAndAllowsRestart() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val coordinator = TrainTrackingCoordinator.getInstance(context)
    coordinator.stopTracking()
    coordinator.onFromInputChanged("")
    coordinator.onToInputChanged("")
    coordinator.onTrainNoChanged("")

    coordinator.onTrainNoChanged("13175")
    // Allow 250ms debounce in Dispatchers.Main.immediate under Robolectric
    org.robolectric.shadows.ShadowLooper.idleMainLooper(350, java.util.concurrent.TimeUnit.MILLISECONDS)

    assertTrue(coordinator.uiState.value.running)
    assertEquals("13175", coordinator.uiState.value.trainNoInput)

    coordinator.stopTracking()
    assertFalse(coordinator.uiState.value.running)

    // Re-entering same train number after stop also activates notification/tracking
    coordinator.onTrainNoChanged("13175")
    org.robolectric.shadows.ShadowLooper.idleMainLooper(350, java.util.concurrent.TimeUnit.MILLISECONDS)
    assertTrue(coordinator.uiState.value.running)
    coordinator.stopTracking()
  }
}
