package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class TrainTrackingService : Service() {
  companion object {
    const val ACTION_START_OR_UPDATE = "com.example.action.START_OR_UPDATE_TRACKING"
    const val ACTION_STOP = "com.example.action.STOP_TRACKING"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_CONTENT = "extra_content"

    @Volatile
    var onStopRequestedFromNotification: (() -> Unit)? = null

    fun startOrUpdate(context: Context, title: String, content: String) {
      val intent = Intent(context, TrainTrackingService::class.java).apply {
        action = ACTION_START_OR_UPDATE
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_CONTENT, content)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, TrainTrackingService::class.java)
      context.stopService(intent)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      onStopRequestedFromNotification?.invoke()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Smart Train Tracker"
    val content = intent?.getStringExtra(EXTRA_CONTENT) ?: "Tracking live train position..."
    val notification = TrainNotificationHelper.buildTrackingNotification(this, title, content)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        TrainNotificationHelper.TRACKING_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
      )
    } else {
      startForeground(TrainNotificationHelper.TRACKING_NOTIFICATION_ID, notification)
    }

    return START_STICKY
  }
}
