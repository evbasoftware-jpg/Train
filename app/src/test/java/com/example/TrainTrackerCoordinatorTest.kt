package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.GoogleLanguages
import com.example.service.TrainTrackingCoordinator
import com.example.service.TrainTrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun selectingTrainBetweenDoesNotCrashWithoutLocationPermission() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val coordinator = TrainTrackingCoordinator.getInstance(context)

    coordinator.setBackgroundTrackingEnabled(true)
    coordinator.onSelectTrainBetween("12345")

    val state = coordinator.uiState.value
    assertEquals("12345", state.trainNoInput)
    assertTrue(state.running)
    assertFalse(TrainTrackingService.hasLocationPermission(context))

    // Also verify foreground service guard safely ignores start without location permission
    TrainTrackingService.startOrUpdate(context, "12345", "Test", "Stop")

    coordinator.stopTracking(showBanner = false)
    assertFalse(coordinator.uiState.value.running)
  }
}
