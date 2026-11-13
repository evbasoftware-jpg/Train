package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.model.DelayStatus
import com.example.model.LocalizedStrings
import com.example.model.LocatedTrain
import com.example.model.RouteStation
import com.example.model.StationAlarmState
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NtesOrange
import com.example.ui.theme.StopCrimson
import com.example.ui.theme.StopCrimsonContainer
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.theme.VerifiedEmeraldContainer
import com.example.ui.theme.VerifiedEmeraldDark
import com.example.util.GeoMath
import java.util.Locale

@Composable
fun RouteTimelineCard(
  route: List<RouteStation>,
  locatedTrain: LocatedTrain?,
  insideTrain: Boolean,
  userSpeedKmh: Double,
  running: Boolean,
  selectedStationIndex: Int?,
  stationAlarm: StationAlarmState?,
  strings: LocalizedStrings,
  onStationClick: (Int) -> Unit,
  onFocusStationOnMap: (Int) -> Unit,
  onToggleStationAlarm: (Int) -> Unit,
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

      if (route.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = strings.tapStationHint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

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
          selectedStationIndex = selectedStationIndex,
          stationAlarm = stationAlarm,
          strings = strings,
          onStationClick = onStationClick,
        )

        val selectedStation = selectedStationIndex?.let { route.getOrNull(it) }
        AnimatedVisibility(visible = selectedStation != null && selectedStationIndex != null) {
          if (selectedStation != null && selectedStationIndex != null) {
            val stName = selectedStation.stationName.ifBlank { selectedStation.stationCode }
            val hasCoords = selectedStation.lat != null && selectedStation.lon != null
            val distFromTrain = if (locatedTrain != null && hasCoords) {
              GeoMath.haversineKm(
                locatedTrain.lat,
                locatedTrain.lon,
                selectedStation.lat!!,
                selectedStation.lon!!,
              )
            } else {
              null
            }
            val isAlarmSet = stationAlarm != null &&
              stationAlarm.stationCode.equals(selectedStation.stationCode, ignoreCase = true) &&
              stationAlarm.stationName == stName

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .testTag("selected_station_action_bar"),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = if (selectedStation.stationCode.isNotBlank()) {
                      "$stName (${selectedStation.stationCode})"
                    } else {
                      stName
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                  if (distFromTrain != null) {
                    Text(
                      text = strings.distanceFromTrainLabel(
                        String.format(Locale.US, "%.1f", distFromTrain),
                      ),
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      color = CobaltPrimary,
                    )
                  }
                }

                if (hasCoords) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    FilledTonalButton(
                      onClick = { onFocusStationOnMap(selectedStationIndex) },
                      shape = RoundedCornerShape(8.dp),
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                      colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CobaltPrimary,
                        contentColor = Color.White,
                      ),
                      modifier = Modifier
                        .weight(1f)
                        .testTag("station_view_on_map_button"),
                    ) {
                      Icon(
                        Icons.Filled.Map,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Spacer(Modifier.width(6.dp))
                      Text(strings.viewOnMapBtn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                      onClick = { onToggleStationAlarm(selectedStationIndex) },
                      shape = RoundedCornerShape(8.dp),
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                      colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAlarmSet) NtesOrange else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isAlarmSet) Color.White else CobaltPrimary,
                      ),
                      modifier = Modifier
                        .weight(1f)
                        .testTag("station_toggle_alarm_button"),
                    ) {
                      Icon(
                        imageVector = if (isAlarmSet) {
                          Icons.Filled.NotificationsActive
                        } else {
                          Icons.Filled.NotificationsNone
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Spacer(Modifier.width(6.dp))
                      Text(
                        text = if (isAlarmSet) strings.alarmActiveBtn else strings.setAlarmBtn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                }
              }
            }
          }
        }
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
  selectedStationIndex: Int?,
  stationAlarm: StationAlarmState?,
  strings: LocalizedStrings,
  onStationClick: (Int) -> Unit,
) {
  val stationWidthDp = 132.dp
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

      Row(modifier = Modifier.padding(top = 38.dp)) {
        route.forEachIndexed { index, station ->
          val isPassed = index < activeIdx
          val isActive = index == activeIdx && locatedTrain != null
          val isSelected = selectedStationIndex == index
          val stName = station.stationName.ifBlank { station.stationCode }
          val hasAlarm = stationAlarm != null &&
            stationAlarm.stationCode.equals(station.stationCode, ignoreCase = true) &&
            stationAlarm.stationName == stName

          val dotColor = when {
            hasAlarm -> NtesOrange
            isPassed -> VerifiedEmerald
            isActive -> CobaltPrimary
            else -> Color(0xFF90A4AE)
          }
          val ringColor = when {
            isSelected -> NtesOrange.copy(alpha = 0.35f)
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
          val delayStatus = if (etaText != null) {
            GeoMath.computeDelayStatus(scheduledText, etaText)
          } else {
            DelayStatus.Unknown
          }

          Column(
            modifier = Modifier
              .width(stationWidthDp)
              .clip(RoundedCornerShape(10.dp))
              .then(
                if (isSelected) {
                  Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .border(1.dp, CobaltPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                } else {
                  Modifier
                },
              )
              .minimumInteractiveComponentSize()
              .clickable { onStationClick(index) }
              .padding(horizontal = 6.dp, vertical = 4.dp)
              .testTag("route_station_node_$index"),
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

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              if (hasAlarm) {
                Icon(
                  imageVector = Icons.Filled.NotificationsActive,
                  contentDescription = null,
                  tint = NtesOrange,
                  modifier = Modifier
                    .size(13.dp)
                    .padding(end = 2.dp),
                )
              }
              Text(
                text = stName,
                style = MaterialTheme.typography.bodySmall.copy(
                  fontWeight = if (isActive || isSelected) FontWeight.Bold else FontWeight.SemiBold,
                  fontSize = 12.sp,
                ),
                color = if (isActive) CobaltPrimary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }

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

            // Delay / On-Time badge
            when (delayStatus) {
              DelayStatus.OnTime -> {
                Spacer(modifier = Modifier.height(3.dp))
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = VerifiedEmeraldContainer,
                ) {
                  Text(
                    text = strings.delayOnTime,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = VerifiedEmeraldDark,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                  )
                }
              }
              is DelayStatus.Delayed -> {
                Spacer(modifier = Modifier.height(3.dp))
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = StopCrimsonContainer,
                ) {
                  Text(
                    text = strings.delayLateMinutes(delayStatus.minutes),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = StopCrimson,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                  )
                }
              }
              is DelayStatus.Early -> {
                Spacer(modifier = Modifier.height(3.dp))
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                  Text(
                    text = strings.delayEarlyMinutes(delayStatus.minutes),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CobaltPrimary,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                  )
                }
              }
              DelayStatus.Unknown -> {}
            }
          }
        }
      }
    }
  }
}
