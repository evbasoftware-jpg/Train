package com.example.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
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
    val hasFine = ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasFine && !hasCoarse) {
      trySend(LocationEvent.UnavailableOrDisabled)
      close()
      return@callbackFlow
    }

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

      fusedClient.lastLocation
        .addOnSuccessListener { last -> if (last != null) emitLocation(last) }
        .addOnFailureListener { }

      fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        .addOnFailureListener {
          if (!receivedAnyFix) {
            trySend(LocationEvent.SlowSignalFallback)
          }
        }
      fusedStarted = true
    } catch (_: Throwable) {
      fusedStarted = false
    }

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
    } catch (_: Throwable) {
      if (!fusedStarted && !lmStarted) {
        trySend(LocationEvent.UnavailableOrDisabled)
      }
    }

    awaitClose {
      if (fusedStarted) {
        try {
          fusedClient.removeLocationUpdates(fusedCallback)
        } catch (_: Throwable) {
        }
      }
      if (lmStarted) {
        try {
          locationManager?.removeUpdates(platformListener)
        } catch (_: Throwable) {
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun fetchLastKnownLocation(onFix: (GpsSample) -> Unit) = fetchCurrentOrLastLocation(onFix)

  @SuppressLint("MissingPermission")
  fun fetchCurrentOrLastLocation(onFix: (GpsSample) -> Unit) {
    val hasFine = ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return

    fun deliver(location: Location) {
      if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
      onFix(
        GpsSample(
          lat = location.latitude,
          lon = location.longitude,
          accuracy = if (location.hasAccuracy()) location.accuracy else 999f,
          speedMetersPerSec = if (location.hasSpeed()) location.speed else null,
          headingDegrees = if (location.hasBearing()) location.bearing else null,
          timeMillis = if (location.time > 0L) location.time else System.currentTimeMillis(),
        ),
      )
    }

    var deliveredBestTime = -1L
    fun deliverIfFresher(loc: Location?) {
      if (loc == null) return
      val t = if (loc.time > 0L) loc.time else System.currentTimeMillis()
      if (t >= deliveredBestTime) {
        deliveredBestTime = t
        deliver(loc)
      }
    }

    // Seed from platform LocationManager cached providers right away
    try {
      val lm = locationManager
      if (lm != null) {
        val candidates = listOfNotNull(
          runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
          runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
          runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull(),
        )
        candidates.maxByOrNull { it.time }?.let { deliverIfFresher(it) }
      }
    } catch (_: Throwable) {
    }

    // Request fresh active fix via FusedLocationProviderClient + LocationManager fallback
    try {
      fusedClient.lastLocation
        .addOnSuccessListener { last -> deliverIfFresher(last) }
        .addOnFailureListener { }

      val priority = if (hasFine) {
        Priority.PRIORITY_HIGH_ACCURACY
      } else {
        Priority.PRIORITY_BALANCED_POWER_ACCURACY
      }
      fusedClient.getCurrentLocation(priority, null)
        .addOnSuccessListener { cur ->
          if (cur != null) {
            deliverIfFresher(cur)
          } else {
            requestSinglePlatformFix(::deliverIfFresher)
          }
        }
        .addOnFailureListener {
          requestSinglePlatformFix(::deliverIfFresher)
        }
    } catch (_: Throwable) {
      requestSinglePlatformFix(::deliverIfFresher)
    }
  }

  @SuppressLint("MissingPermission")
  private fun requestSinglePlatformFix(onLocation: (Location?) -> Unit) {
    val lm = locationManager ?: return
    val providers = buildList {
      if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
      if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    if (providers.isEmpty()) return

    var completed = false
    lateinit var listener: LocationListener
    listener = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        if (!completed) {
          completed = true
          try { lm.removeUpdates(listener) } catch (_: Throwable) {}
          onLocation(location)
        }
      }

      @Deprecated("Deprecated in Java")
      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
      override fun onProviderEnabled(provider: String) {}
      override fun onProviderDisabled(provider: String) {}
    }

    providers.forEach { p ->
      try {
        lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
      } catch (_: Throwable) {
      }
    }
  }
}
