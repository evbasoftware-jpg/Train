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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

data class TrackingNotificationContent(
  val title: String,
  val currentLabel: String,
  val currentStation: String,
  val nextLabel: String,
  val nextStation: String,
  val destLabel: String,
  val destStation: String,
  val stopLabel: String = "Stop",
)

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
        description = "Shows current stoppage, next stoppage, and destination even when screen is off"
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
    info: TrackingNotificationContent,
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

    val compactCur = "${info.currentLabel}: ${info.currentStation}"
    val compactNxt = "${info.nextLabel}: ${info.nextStation}"
    val compactDst = "${info.destLabel}: ${info.destStation}"

    val compactView = RemoteViews(context.packageName, R.layout.notification_train_compact).apply {
      setTextViewText(R.id.notif_train_title, info.title)
      setTextViewText(R.id.notif_compact_current, compactCur)
      setTextViewText(R.id.notif_compact_next, compactNxt)
      setTextViewText(R.id.notif_compact_dest, compactDst)
    }

    val expandedView = RemoteViews(context.packageName, R.layout.notification_train_expanded).apply {
      setTextViewText(R.id.notif_train_title, info.title)
      setTextViewText(R.id.notif_row_current, "● $compactCur")
      setTextViewText(R.id.notif_row_next, "➜ $compactNxt")
      setTextViewText(R.id.notif_row_dest, "⚑ $compactDst")
    }

    val fallbackSummary = "$compactCur | $compactNxt | $compactDst"

    return NotificationCompat.Builder(context, TRACKING_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_train)
      .setColor(0xFF1565C0.toInt()) // Cobalt Blue (no green)
      .setContentTitle(info.title)
      .setContentText(fallbackSummary)
      .setCustomContentView(compactView)
      .setCustomBigContentView(expandedView)
      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openPendingIntent)
      .addAction(0, info.stopLabel, stopPendingIntent)
      .build()
  }

  fun postFallbackTrackingNotification(
    context: Context,
    info: TrackingNotificationContent,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }
    try {
      val notification = buildTrackingNotification(context, info)
      NotificationManagerCompat.from(context).notify(TRACKING_NOTIFICATION_ID, notification)
    } catch (_: Throwable) {
    }
  }

  fun cancelTrackingNotification(context: Context) {
    try {
      NotificationManagerCompat.from(context).cancel(TRACKING_NOTIFICATION_ID)
    } catch (_: Throwable) {
    }
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
      .setSmallIcon(R.drawable.ic_stat_train)
      .setColor(0xFF1565C0.toInt())
      .setContentTitle(title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(openPendingIntent)
      .build()

    try {
      NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID, notification)
    } catch (_: SecurityException) {
    }
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
    } catch (_: Throwable) {
    }
  }
}
