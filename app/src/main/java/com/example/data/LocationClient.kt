package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.example.model.GpsSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface LocationEvent {
  data class Fix(val sample: GpsSample) : LocationEvent
  data object SlowSignalFallback : LocationEvent
  data object UnavailableOrDisabled : LocationEvent
}

class LocationClient(context: Context) {
  private val appContext = context.applicationContext
  private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
  private val locationManager =
    appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  @SuppressLint("MissingPermission")
  fun observeLocationUpdates(): Flow<LocationEvent> = callbackFlow {
    var receivedAnyFix = false

    fun emitLocation(location: Location) {
      if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
      receivedAnyFix = true
      val sample = GpsSample(
        lat = location.latitude,
        lon = location.longitude,
        accuracy = if (location.hasAccuracy()) location.accuracy else 999f,
        speedMetersPerSec = if (location.hasSpeed()) location.speed else null,
        headingDegrees = if (location.hasBearing()) location.bearing else null,
        timeMillis = if (location.time > 0L) location.time else System.currentTimeMillis(),
      )
      trySend(LocationEvent.Fix(sample))
    }

    val fusedCallback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let { emitLocation(it) }
      }
    }

    val platformListener = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        emitLocation(location)
      }

      @Deprecated("Deprecated in Java")
      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

      override fun onProviderEnabled(provider: String) {}

      override fun onProviderDisabled(provider: String) {
        if (!receivedAnyFix) {
          trySend(LocationEvent.SlowSignalFallback)
        }
      }
    }

    var fusedStarted = false
    try {
      val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
        .setMinUpdateIntervalMillis(3_000L)
        .setMaxUpdateDelayMillis(10_000L)
        .build()

      fusedClient.lastLocation.addOnSuccessListener { last ->
        if (last != null) emitLocation(last)
      }

      fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        .addOnFailureListener {
          if (!receivedAnyFix) {
            trySend(LocationEvent.SlowSignalFallback)
          }
        }
      fusedStarted = true
    } catch (_: Exception) {
      fusedStarted = false
    }

    // Also attach LocationManager GPS/Network fallback so emulators without GMS work smoothly
    var lmStarted = false
    try {
      if (locationManager != null) {
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsEnabled && !netEnabled && !fusedStarted) {
          trySend(LocationEvent.UnavailableOrDisabled)
        }
        if (gpsEnabled) {
          locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            5_000L,
            0f,
            platformListener,
            Looper.getMainLooper(),
          )
          lmStarted = true
        }
        if (netEnabled) {
          locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            8_000L,
            0f,
            platformListener,
            Looper.getMainLooper(),
          )
          lmStarted = true
        }
      }
    } catch (_: Exception) {
      if (!fusedStarted && !lmStarted) {
        trySend(LocationEvent.UnavailableOrDisabled)
      }
    }

    awaitClose {
      if (fusedStarted) {
        try {
          fusedClient.removeLocationUpdates(fusedCallback)
        } catch (_: Exception) {
        }
      }
      if (lmStarted) {
        try {
          locationManager?.removeUpdates(platformListener)
        } catch (_: Exception) {
        }
      }
    }
  }
}
