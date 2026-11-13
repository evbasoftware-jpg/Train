package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object TrainNotificationHelper {
  const val TRACKING_CHANNEL_ID = "train_live_tracking"
  const val ALARM_CHANNEL_ID = "station_wakeup_alarm"
  const val TRACKING_NOTIFICATION_ID = 2001
  const val ALARM_NOTIFICATION_ID = 2002

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java) ?: return

      val trackingChannel = NotificationChannel(
        TRACKING_CHANNEL_ID,
        "Live Train Tracking",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Shows ongoing live train location and next station"
      }

      val alarmChannel = NotificationChannel(
        ALARM_CHANNEL_ID,
        "Destination Station Alarm",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Alerts when the train approaches your selected destination station"
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 700)
      }

      manager.createNotificationChannel(trackingChannel)
      manager.createNotificationChannel(alarmChannel)
    }
  }

  fun buildTrackingNotification(
    context: Context,
    title: String,
    content: String,
  ): Notification {
    ensureChannels(context)

    val openIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
      context,
      0,
      openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val stopIntent = Intent(context, TrainTrackingService::class.java).apply {
      action = TrainTrackingService.ACTION_STOP
    }
    val stopPendingIntent = PendingIntent.getService(
      context,
      1,
      stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(context, TRACKING_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(title)
      .setContentText(content)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openPendingIntent)
      .addAction(0, "Stop", stopPendingIntent)
      .build()
  }

  fun triggerStationAlarm(
    context: Context,
    title: String,
    message: String,
  ) {
    ensureChannels(context)
    vibrateAlarm(context)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    val openIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
      context,
      2,
      openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(openPendingIntent)
      .build()

    NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID, notification)
  }

  @Suppress("DEPRECATION")
  private fun vibrateAlarm(context: Context) {
    try {
      val pattern = longArrayOf(0, 450, 200, 450, 200, 650)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
      } else {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
          vibrator?.vibrate(pattern, -1)
        }
      }
    } catch (_: Exception) {
    }
  }
}
