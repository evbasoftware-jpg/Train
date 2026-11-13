package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LocalizedStrings
import com.example.model.LocatedTrain
import com.example.model.MovementStats
import com.example.model.VerificationMetrics
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
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
      }.takeIf { it.isFinite() } ?: 0.0
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
      val distKm = verification.distanceKm.takeIf { it.isFinite() } ?: 0.0
      val crossKm = verification.crossTrackKm.takeIf { it.isFinite() } ?: 0.0
      val alongKm = verification.alongTrackKm.takeIf { it.isFinite() } ?: 0.0
      val userSpd = movementStats.speedKmh.takeIf { it.isFinite() } ?: 0.0
      val trainSpd = locatedTrain.speedKmh.takeIf { it.isFinite() } ?: 0.0
      val headDiff = (verification.headingDiffDeg.takeIf { it.isFinite() } ?: 0.0).roundToInt()

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyValueRow(
          strings.labelTrainDistance,
          "${String.format(Locale.US, "%.1f", distKm * 1000.0)} m (${String.format(Locale.US, "%.2f", distKm)} km)",
          mono = true,
        )
        KeyValueRow(
          strings.labelTrackOffset,
          "${String.format(Locale.US, "%.1f", crossKm * 1000.0)} m",
          mono = true,
        )
        KeyValueRow(
          strings.labelLengthOffset,
          "${String.format(Locale.US, "%.1f", alongKm * 1000.0)} m",
          mono = true,
        )
        KeyValueRow(
          strings.labelUserSpeed,
          "${String.format(Locale.US, "%.1f", userSpd)} km/h",
          mono = true,
        )
        KeyValueRow(
          strings.labelTrainSpeed,
          "${String.format(Locale.US, "%.1f", trainSpd)} km/h",
          mono = true,
        )
        KeyValueRow(
          strings.labelHeadingDiff,
          "$headDiff°",
          mono = true,
        )
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
