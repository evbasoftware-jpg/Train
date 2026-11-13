package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class TrainTrackingService : Service() {
  companion object {
    const val ACTION_START_OR_UPDATE = "com.example.action.START_OR_UPDATE_TRACKING"
    const val ACTION_STOP = "com.example.action.STOP_TRACKING"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_CONTENT = "extra_content"
    const val EXTRA_STOP_LABEL = "extra_stop_label"

    @Volatile
    var onStopRequestedFromNotification: (() -> Unit)? = null

    fun hasLocationPermission(context: Context): Boolean {
      val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
      ) == PackageManager.PERMISSION_GRANTED
      val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
      ) == PackageManager.PERMISSION_GRANTED
      return fine || coarse
    }

    fun startOrUpdate(
      context: Context,
      title: String,
      content: String,
      stopLabel: String = "Stop",
    ) {
      if (!hasLocationPermission(context)) return
      try {
        val intent = Intent(context, TrainTrackingService::class.java).apply {
          action = ACTION_START_OR_UPDATE
          putExtra(EXTRA_TITLE, title)
          putExtra(EXTRA_CONTENT, content)
          putExtra(EXTRA_STOP_LABEL, stopLabel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (_: Throwable) {
        // Safely ignore if OS restricts background FGS start
      }
    }

    fun stop(context: Context) {
      try {
        val intent = Intent(context, TrainTrackingService::class.java)
        context.stopService(intent)
      } catch (_: Throwable) {
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      onStopRequestedFromNotification?.invoke()
      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (_: Throwable) {
      }
      stopSelf()
      return START_NOT_STICKY
    }

    if (!hasLocationPermission(this)) {
      stopSelf()
      return START_NOT_STICKY
    }

    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Smart Train Tracker"
    val content = intent?.getStringExtra(EXTRA_CONTENT) ?: "Tracking live train position..."
    val stopLabel = intent?.getStringExtra(EXTRA_STOP_LABEL) ?: "Stop"
    val notification =
      TrainNotificationHelper.buildTrackingNotification(this, title, content, stopLabel)

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
          TrainNotificationHelper.TRACKING_NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
      } else {
        startForeground(TrainNotificationHelper.TRACKING_NOTIFICATION_ID, notification)
      }
    } catch (_: Throwable) {
      stopSelf()
      return START_NOT_STICKY
    }

    return START_STICKY
  }
}
