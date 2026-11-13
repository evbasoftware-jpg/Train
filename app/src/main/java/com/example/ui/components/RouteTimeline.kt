package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LocalizedStrings
import com.example.model.LocatedTrain
import com.example.model.RouteStation
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StopCrimson
import com.example.ui.theme.StopCrimsonContainer
import com.example.ui.theme.VerifiedEmerald
import com.example.util.GeoMath
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun RouteTimelineCard(
  route: List<RouteStation>,
  locatedTrain: LocatedTrain?,
  insideTrain: Boolean,
  userSpeedKmh: Double,
  running: Boolean,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("route_timeline_card"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Route,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = strings.routeSectionTitle,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (route.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = if (running) strings.routeNoStations else strings.routeEmptyPlaceholder,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        RouteTrackStrip(
          route = route,
          locatedTrain = locatedTrain,
          insideTrain = insideTrain,
          userSpeedKmh = userSpeedKmh,
        )
      }
    }
  }
}

@Composable
private fun RouteTrackStrip(
  route: List<RouteStation>,
  locatedTrain: LocatedTrain?,
  insideTrain: Boolean,
  userSpeedKmh: Double,
) {
  val stationWidthDp = 128.dp
  val scrollState = rememberScrollState()
  val density = LocalDensity.current
  var viewportWidthPx by remember { mutableIntStateOf(0) }

  val activeIdx = if (locatedTrain != null) {
    GeoMath.findNearestRouteStationIndex(
      locatedTrain.lat,
      locatedTrain.lon,
      route,
      locatedTrain.idx.coerceIn(0, route.lastIndex),
    )
  } else {
    0
  }

  val progress = if (locatedTrain != null) {
    GeoMath.computeSegmentProgress(locatedTrain.copy(idx = activeIdx), route)
  } else {
    0.0
  }

  val startOffsetDp = stationWidthDp / 2
  val targetTrainXDp = startOffsetDp + (stationWidthDp * activeIdx) + (stationWidthDp * progress.toFloat())
  val animatedTrainXDp by animateDpAsState(
    targetValue = targetTrainXDp,
    animationSpec = tween(durationMillis = 700),
    label = "moving_train_x",
  )

  val effectiveSpeed = if (insideTrain && userSpeedKmh > 0.0) {
    userSpeedKmh
  } else {
    locatedTrain?.speedKmh ?: 0.0
  }

  LaunchedEffect(activeIdx, progress, viewportWidthPx) {
    if (viewportWidthPx > 0) {
      val trainPx = with(density) { targetTrainXDp.toPx() }
      val targetScroll = (trainPx - viewportWidthPx / 2f).toInt().coerceAtLeast(0)
      scrollState.animateScrollTo(targetScroll)
    }
  }

  val totalStationsWidth = stationWidthDp * route.size
  val trackStart = stationWidthDp / 2
  val trackEnd = totalStationsWidth - (stationWidthDp / 2)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .onSizeChanged { viewportWidthPx = it.width }
      .horizontalScroll(scrollState)
      .padding(top = 8.dp, bottom = 6.dp),
  ) {
    Box(modifier = Modifier.width(totalStationsWidth)) {
      // Continuous railway track line behind dots
      val trackColor = MaterialTheme.colorScheme.outline
      val passedColor = VerifiedEmerald
      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(64.dp),
      ) {
        val lineY = 48.dp.toPx()
        val startX = trackStart.toPx()
        val endX = trackEnd.toPx()
        val trainX = animatedTrainXDp.toPx().coerceIn(startX, endX)

        if (endX > startX) {
          drawLine(
            color = trackColor,
            start = Offset(startX, lineY),
            end = Offset(endX, lineY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
          )
          if (trainX > startX) {
            drawLine(
              color = passedColor,
              start = Offset(startX, lineY),
              end = Offset(trainX, lineY),
              strokeWidth = 4.dp.toPx(),
              cap = StrokeCap.Round,
            )
          }
        }
      }

      // Floating animated train badge above the track
      if (locatedTrain != null) {
        Surface(
          modifier = Modifier
            .offset(x = animatedTrainXDp - 18.dp, y = 2.dp)
            .size(36.dp),
          shape = CircleShape,
          color = CobaltPrimary,
          shadowElevation = 4.dp,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.Train,
              contentDescription = "Train position",
              tint = Color.White,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      // Station nodes
      Row(modifier = Modifier.padding(top = 38.dp)) {
        route.forEachIndexed { index, station ->
          val isPassed = index < activeIdx
          val isActive = index == activeIdx && locatedTrain != null
          val dotColor = when {
            isPassed -> VerifiedEmerald
            isActive -> CobaltPrimary
            else -> Color(0xFF90A4AE)
          }
          val ringColor = when {
            isActive -> CobaltPrimary.copy(alpha = 0.28f)
            isPassed -> VerifiedEmerald.copy(alpha = 0.25f)
            else -> Color.Transparent
          }

          val scheduledText = GeoMath.extractStationTime(station)
          val etaText = if (index >= activeIdx && locatedTrain != null) {
            GeoMath.calculateEta(locatedTrain, station, effectiveSpeed)
          } else {
            null
          }

          Column(
            modifier = Modifier
              .width(stationWidthDp)
              .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(ringColor),
              contentAlignment = Alignment.Center,
            ) {
              Box(
                modifier = Modifier
                  .size(16.dp)
                  .clip(CircleShape)
                  .background(dotColor)
                  .border(2.5.dp, Color.White, CircleShape),
              )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
              text = station.stationName.ifBlank { station.stationCode },
              style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 12.sp,
              ),
              color = if (isActive) CobaltPrimary else MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )

            if (station.stationCode.isNotBlank()) {
              Text(
                text = station.stationCode,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Scheduled time pill
            Row(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = CobaltPrimary,
                modifier = Modifier.size(11.dp),
              )
              Text(
                text = scheduledText,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = CobaltPrimary,
              )
            }

            // Live ETA pill
            if (etaText != null) {
              Spacer(modifier = Modifier.height(3.dp))
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = StopCrimsonContainer,
              ) {
                Text(
                  text = "⏳ $etaText",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = StopCrimson,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}
