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
    const val EXTRA_CUR_LABEL = "extra_cur_label"
    const val EXTRA_CUR_STATION = "extra_cur_station"
    const val EXTRA_NXT_LABEL = "extra_nxt_label"
    const val EXTRA_NXT_STATION = "extra_nxt_station"
    const val EXTRA_DST_LABEL = "extra_dst_label"
    const val EXTRA_DST_STATION = "extra_dst_station"
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
      info: TrackingNotificationContent,
    ) {
      if (!hasLocationPermission(context)) {
        TrainNotificationHelper.postFallbackTrackingNotification(context, info)
        return
      }
      try {
        val intent = Intent(context, TrainTrackingService::class.java).apply {
          action = ACTION_START_OR_UPDATE
          putExtra(EXTRA_TITLE, info.title)
          putExtra(EXTRA_CUR_LABEL, info.currentLabel)
          putExtra(EXTRA_CUR_STATION, info.currentStation)
          putExtra(EXTRA_NXT_LABEL, info.nextLabel)
          putExtra(EXTRA_NXT_STATION, info.nextStation)
          putExtra(EXTRA_DST_LABEL, info.destLabel)
          putExtra(EXTRA_DST_STATION, info.destStation)
          putExtra(EXTRA_STOP_LABEL, info.stopLabel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (_: Throwable) {
        TrainNotificationHelper.postFallbackTrackingNotification(context, info)
      }
    }

    fun stop(context: Context) {
      TrainNotificationHelper.cancelTrackingNotification(context)
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
      TrainNotificationHelper.cancelTrackingNotification(this)
      stopSelf()
      return START_NOT_STICKY
    }

    if (!hasLocationPermission(this)) {
      stopSelf()
      return START_NOT_STICKY
    }

    val info = TrackingNotificationContent(
      title = intent?.getStringExtra(EXTRA_TITLE) ?: "Smart Train Tracker",
      currentLabel = intent?.getStringExtra(EXTRA_CUR_LABEL) ?: "Current",
      currentStation = intent?.getStringExtra(EXTRA_CUR_STATION) ?: "—",
      nextLabel = intent?.getStringExtra(EXTRA_NXT_LABEL) ?: "Next",
      nextStation = intent?.getStringExtra(EXTRA_NXT_STATION) ?: "—",
      destLabel = intent?.getStringExtra(EXTRA_DST_LABEL) ?: "Destination",
      destStation = intent?.getStringExtra(EXTRA_DST_STATION) ?: "—",
      stopLabel = intent?.getStringExtra(EXTRA_STOP_LABEL) ?: "Stop",
    )
    val notification = TrainNotificationHelper.buildTrackingNotification(this, info)

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
      TrainNotificationHelper.postFallbackTrackingNotification(this, info)
      stopSelf()
      return START_NOT_STICKY
    }

    return START_STICKY
  }
}
