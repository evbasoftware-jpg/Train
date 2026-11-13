package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.GpsSample
import com.example.model.LocalizedStrings
import com.example.model.LocatedTrain
import com.example.model.MovementStats
import com.example.model.VerificationMetrics
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.VerifiedEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrainLiveCard(
  trainNo: String,
  trainName: String?,
  locatedTrain: LocatedTrain?,
  rawSource: String?,
  insideTrain: Boolean,
  movementStats: MovementStats,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  TelemetryCardShell(
    title = strings.trainLiveTitle,
    icon = Icons.Filled.Train,
    modifier = modifier.testTag("train_live_card"),
  ) {
    if (locatedTrain == null) {
      Text(
        text = strings.waitingLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val displayName = if (!trainName.isNullOrBlank()) "$trainNo - $trainName" else trainNo
      val currentSpeed = if (insideTrain) {
        if (movementStats.speedKmh > 0.0) movementStats.speedKmh else locatedTrain.speedKmh
      } else {
        locatedTrain.speedKmh
      }
      val sourceLabel = when {
        insideTrain -> strings.sourceYourGps
        rawSource == "gps" -> strings.sourcePassengerGps
        else -> strings.sourceApiKey
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyValueRow(strings.labelTrain, displayName)
        KeyValueRow(strings.labelCurrent, locatedTrain.name.ifBlank { strings.unknownStation })
        KeyValueRow(strings.labelNext, locatedTrain.next.ifBlank { "—" })
        KeyValueRow(strings.labelSource, sourceLabel)
        KeyValueRow(
          strings.labelSpeed,
          "${String.format(Locale.US, "%.1f", currentSpeed)} km/h",
          mono = true,
        )
      }
    }
  }
}

@Composable
fun UserGpsCard(
  latestSample: GpsSample?,
  lowAccuracyMeters: Int?,
  movementStats: MovementStats,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  TelemetryCardShell(
    title = strings.userGpsTitle,
    icon = Icons.Filled.GpsFixed,
    modifier = modifier.testTag("user_gps_card"),
  ) {
    when {
      latestSample != null -> {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          KeyValueRow("Lat", String.format(Locale.US, "%.6f", latestSample.lat), mono = true)
          KeyValueRow("Lng", String.format(Locale.US, "%.6f", latestSample.lon), mono = true)
          KeyValueRow(
            strings.labelAccuracy,
            "±${latestSample.accuracy.roundToInt()} m",
            mono = true,
          )
          KeyValueRow(
            strings.labelSpeed,
            "${String.format(Locale.US, "%.1f", movementStats.speedKmh)} km/h",
            mono = true,
          )
        }
      }
      lowAccuracyMeters != null -> {
        Text(
          text = strings.gpsLowAccuracyNotice(lowAccuracyMeters),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      else -> {
        Text(
          text = strings.waitingLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
fun MovementDistanceCard(
  verification: VerificationMetrics?,
  movementStats: MovementStats,
  locatedTrain: LocatedTrain?,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  TelemetryCardShell(
    title = strings.distanceMovementTitle,
    icon = Icons.Filled.Straighten,
    modifier = modifier.testTag("movement_distance_card"),
  ) {
    if (verification == null || locatedTrain == null) {
      Text(
        text = strings.waitingLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyValueRow(
          strings.labelTrainDistance,
          "${String.format(Locale.US, "%.1f", verification.distanceKm * 1000.0)} m (${String.format(Locale.US, "%.2f", verification.distanceKm)} km)",
          mono = true,
        )
        KeyValueRow(
          strings.labelTrackOffset,
          "${String.format(Locale.US, "%.1f", verification.crossTrackKm * 1000.0)} m",
          mono = true,
        )
        KeyValueRow(
          strings.labelLengthOffset,
          "${String.format(Locale.US, "%.1f", verification.alongTrackKm * 1000.0)} m",
          mono = true,
        )
        KeyValueRow(
          strings.labelUserSpeed,
          "${String.format(Locale.US, "%.1f", movementStats.speedKmh)} km/h",
          mono = true,
        )
        KeyValueRow(
          strings.labelTrainSpeed,
          "${String.format(Locale.US, "%.1f", locatedTrain.speedKmh)} km/h",
          mono = true,
        )
        KeyValueRow(
          strings.labelHeadingDiff,
          "${verification.headingDiffDeg.roundToInt()}°",
          mono = true,
        )
      }
    }
  }
}

@Composable
fun ConfidenceScoreCard(
  verification: VerificationMetrics?,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  val score = verification?.confidencePercent ?: 0
  val animatedSweep by animateFloatAsState(
    targetValue = (score / 100f) * 360f,
    animationSpec = tween(durationMillis = 600),
    label = "confidence_sweep",
  )
  val gaugeColor = when {
    score >= 80 -> VerifiedEmerald
    score > 0 -> CobaltPrimary
    else -> MaterialTheme.colorScheme.outline
  }

  TelemetryCardShell(
    title = strings.confidenceTitle,
    icon = Icons.Filled.Verified,
    modifier = modifier.testTag("confidence_score_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
      ) {
        val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        Canvas(modifier = Modifier.size(88.dp)) {
          drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
          )
          drawArc(
            color = gaugeColor,
            startAngle = -90f,
            sweepAngle = animatedSweep,
            useCenter = false,
            style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
          )
        }
        Text(
          text = "$score%",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold,
          color = gaugeColor,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      val description = when {
        verification == null -> strings.verificationNotStarted
        verification.recognizedInside -> strings.gpsVerifiedScoreText
        else -> strings.gpsMismatchScoreText
      }
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
fun GpsSamplesCard(
  samples: List<GpsSample>,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  TelemetryCardShell(
    title = strings.gpsSamplesTitle,
    icon = Icons.Filled.Analytics,
    modifier = modifier.testTag("gps_samples_card"),
  ) {
    if (samples.isEmpty()) {
      Text(
        text = strings.noSamplesText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 180.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        val reversed = samples.asReversed()
        reversed.forEachIndexed { idx, sample ->
          val sampleNum = samples.size - idx
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = strings.sampleLabel(sampleNum),
              style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
              Text(
                text = "±${sample.accuracy.roundToInt()}m • ${timeFormat.format(Date(sample.timeMillis))}",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = CobaltPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
          if (idx < reversed.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
          }
        }
      }
    }
  }
}

@Composable
private fun TelemetryCardShell(
  title: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CobaltPrimary,
            modifier = Modifier.size(16.dp),
          )
        }
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          color = CobaltPrimary,
        )
      }
      Spacer(modifier = Modifier.height(10.dp))
      content()
    }
  }
}

@Composable
private fun KeyValueRow(
  label: String,
  value: String,
  mono: Boolean = false,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 8.dp),
    )
    Text(
      text = value,
      fontFamily = if (mono) JetBrainsMonoFontFamily else MaterialTheme.typography.bodySmall.fontFamily,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(1f),
    )
  }
}
