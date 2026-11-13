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
import androidx.compose.material3.Surface
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
import com.example.model.StoppageDistances
import com.example.model.VerificationMetrics
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NtesOrange
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrainLiveCard(
  trainNo: String,
  trainName: String?,
  locatedTrain: LocatedTrain?,
  stoppageDistances: StoppageDistances,
  rawSource: String?,
  insideTrain: Boolean,
  movementStats: MovementStats,
  strings: LocalizedStrings,
  localizeName: (String) -> String = { it },
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
      val localizedTrainName = trainName?.takeIf { it.isNotBlank() }?.let(localizeName)
      val displayName = if (!localizedTrainName.isNullOrBlank()) {
        "$trainNo - $localizedTrainName"
      } else {
        trainNo
      }
      val currentStationName = locatedTrain.name.takeIf { it.isNotBlank() }
        ?.let(localizeName)
        ?: strings.unknownStation
      val nextStationName = locatedTrain.next.takeIf { it.isNotBlank() }
        ?.let(localizeName)
        ?: "—"

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

      Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        KeyValueRow(strings.labelTrain, displayName)

        KeyValueRow(strings.labelCurrent, currentStationName)
        stoppageDistances.passedFromCurrentKm?.let { passedKm ->
          DistanceSubBadge(
            label = strings.labelPassedCurrent,
            badgeText = strings.formatPassedDistance(passedKm),
            highlightColor = NtesOrange,
            testTag = "passed_current_distance_badge",
          )
        }

        KeyValueRow(strings.labelNext, nextStationName)
        stoppageDistances.remainingToNextKm?.let { remKm ->
          DistanceSubBadge(
            label = strings.labelRemainingNext,
            badgeText = strings.formatDistance(remKm),
            highlightColor = CobaltPrimary,
            testTag = "remaining_next_distance_badge",
          )
        }

        KeyValueRow(strings.labelSource, sourceLabel)
        KeyValueRow(
          strings.labelSpeed,
          "${String.format(Locale.US, "%.1f", currentSpeed)} ${strings.kmPerHourUnit}",
          mono = true,
        )
      }
    }
  }
}

@Composable
private fun DistanceSubBadge(
  label: String,
  badgeText: String,
  highlightColor: androidx.compose.ui.graphics.Color,
  testTag: String,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 2.dp)
      .testTag(testTag),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
      shape = RoundedCornerShape(6.dp),
      color = highlightColor.copy(alpha = 0.12f),
    ) {
      Text(
        text = badgeText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = highlightColor,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      )
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
          strings.formatDistance(distKm),
          mono = true,
        )
        KeyValueRow(
          strings.labelTrackOffset,
          strings.formatDistance(crossKm),
          mono = true,
        )
        KeyValueRow(
          strings.labelLengthOffset,
          strings.formatDistance(alongKm),
          mono = true,
        )
        KeyValueRow(
          strings.labelUserSpeed,
          "${String.format(Locale.US, "%.1f", userSpd)} ${strings.kmPerHourUnit}",
          mono = true,
        )
        KeyValueRow(
          strings.labelTrainSpeed,
          "${String.format(Locale.US, "%.1f", trainSpd)} ${strings.kmPerHourUnit}",
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
