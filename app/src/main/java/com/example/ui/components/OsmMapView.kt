package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.OsmTileLoader
import com.example.model.FocusedMapStation
import com.example.model.GpsSample
import com.example.model.LocalizedStrings
import com.example.model.LocatedTrain
import com.example.model.MapSizePreset
import com.example.model.RouteStation
import com.example.model.StationAlarmState
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NtesOrange
import com.example.ui.theme.VerifiedEmeraldDark
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

@Composable
fun OsmMapSection(
  tileLoader: OsmTileLoader,
  route: List<RouteStation>,
  locatedTrain: LocatedTrain?,
  latestUserGps: GpsSample?,
  userBearingDegrees: Double,
  userSpeedKmh: Double,
  insideTrain: Boolean,
  distanceKm: Double?,
  focusedStation: FocusedMapStation?,
  alarmStation: StationAlarmState?,
  mapSizePreset: MapSizePreset,
  isFullscreen: Boolean,
  recenterSignal: Int,
  strings: LocalizedStrings,
  showSizePresets: Boolean = true,
  fillAvailableHeight: Boolean = false,
  onSizePresetSelected: (MapSizePreset) -> Unit,
  onToggleFullscreen: (Boolean) -> Unit,
  onRecenter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("map_section_card"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(modifier = if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = CobaltPrimary,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = strings.mapSectionTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (showSizePresets) {
            MapActionChip(
              label = strings.mapSmallBtn,
              selected = mapSizePreset == MapSizePreset.SMALL,
              onClick = { onSizePresetSelected(MapSizePreset.SMALL) },
              testTag = "map_small_button",
            )
            MapActionChip(
              label = strings.mapLargeBtn,
              selected = mapSizePreset == MapSizePreset.LARGE,
              onClick = { onSizePresetSelected(MapSizePreset.LARGE) },
              testTag = "map_large_button",
            )
          }
          FilledTonalButton(
            onClick = { onToggleFullscreen(true) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = CobaltPrimary,
              contentColor = Color.White,
            ),
            modifier = Modifier
              .height(36.dp)
              .testTag("map_fullscreen_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Fullscreen,
              contentDescription = strings.mapFullscreenBtn,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = strings.mapFullscreenBtn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
          FilledTonalButton(
            onClick = onRecenter,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = VerifiedEmeraldDark,
              contentColor = Color.White,
            ),
            modifier = Modifier
              .height(36.dp)
              .testTag("map_center_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.MyLocation,
              contentDescription = strings.mapCenterBtn,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = strings.mapCenterBtn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      }

      if (!isFullscreen) {
        InteractiveOsmCanvas(
          tileLoader = tileLoader,
          route = route,
          locatedTrain = locatedTrain,
          latestUserGps = latestUserGps,
          userBearingDegrees = userBearingDegrees,
          userSpeedKmh = userSpeedKmh,
          insideTrain = insideTrain,
          distanceKm = distanceKm,
          focusedStation = focusedStation,
          alarmStation = alarmStation,
          recenterSignal = recenterSignal,
          strings = strings,
          modifier = if (fillAvailableHeight) {
            Modifier
              .fillMaxWidth()
              .weight(1f)
          } else {
            Modifier
              .fillMaxWidth()
              .height(mapSizePreset.heightDp.dp)
          },
        )
      }
    }
  }

  if (isFullscreen) {
    Dialog(
      onDismissRequest = { onToggleFullscreen(false) },
      properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
      BackHandler { onToggleFullscreen(false) }
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          InteractiveOsmCanvas(
            tileLoader = tileLoader,
            route = route,
            locatedTrain = locatedTrain,
            latestUserGps = latestUserGps,
            userBearingDegrees = userBearingDegrees,
            userSpeedKmh = userSpeedKmh,
            insideTrain = insideTrain,
            distanceKm = distanceKm,
            focusedStation = focusedStation,
            alarmStation = alarmStation,
            recenterSignal = recenterSignal,
            strings = strings,
            modifier = Modifier.fillMaxSize(),
          )

          Row(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .statusBarsPadding()
              .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilledTonalButton(
              onClick = onRecenter,
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = VerifiedEmeraldDark,
                contentColor = Color.White,
              ),
            ) {
              Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(strings.mapCenterBtn, fontSize = 12.sp)
            }
            FilledTonalButton(
              onClick = { onToggleFullscreen(false) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = CobaltPrimary,
                contentColor = Color.White,
              ),
              modifier = Modifier.testTag("map_exit_fullscreen_button"),
            ) {
              Icon(Icons.Filled.CloseFullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(strings.mapExitFullscreenBtn, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MapActionChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  FilledTonalButton(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    shape = RoundedCornerShape(8.dp),
    colors = ButtonDefaults.filledTonalButtonColors(
      containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
      contentColor = if (selected) {
        CobaltPrimary
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    ),
    modifier = Modifier
      .height(36.dp)
      .testTag(testTag),
  ) {
    Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun InteractiveOsmCanvas(
  tileLoader: OsmTileLoader,
  route: List<RouteStation>,
  locatedTrain: LocatedTrain?,
  latestUserGps: GpsSample?,
  userBearingDegrees: Double,
  userSpeedKmh: Double,
  insideTrain: Boolean,
  distanceKm: Double?,
  focusedStation: FocusedMapStation?,
  alarmStation: StationAlarmState?,
  recenterSignal: Int,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  val tileRevision by tileLoader.tileRevision.collectAsStateWithLifecycle()
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var centerLat by remember { mutableDoubleStateOf(22.57) }
  var centerLon by remember { mutableDoubleStateOf(88.36) }
  var zoom by remember { mutableDoubleStateOf(7.0) }
  var selectedMarkerInfo by remember { mutableStateOf<String?>(null) }

  val infiniteTransition = rememberInfiniteTransition(label = "map_pulse")
  val pulseProgress by infiniteTransition.animateFloat(
    initialValue = 0.2f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1400),
      repeatMode = RepeatMode.Restart,
    ),
    label = "pulse_progress",
  )

  // Focus on tapped station from RouteTimeline
  LaunchedEffect(focusedStation?.signal) {
    if (focusedStation != null) {
      centerLat = focusedStation.lat
      centerLon = focusedStation.lon
      zoom = 13.5
      selectedMarkerInfo = "🚉 ${focusedStation.stationName}"
    }
  }

  // Fit bounds when recenterSignal increments
  LaunchedEffect(recenterSignal) {
    if (recenterSignal > 0) {
      if (locatedTrain != null && latestUserGps != null) {
        centerLat = (locatedTrain.lat + latestUserGps.lat) / 2.0
        centerLon = (locatedTrain.lon + latestUserGps.lon) / 2.0
        val distDeg = kotlin.math.max(
          kotlin.math.abs(locatedTrain.lat - latestUserGps.lat),
          kotlin.math.abs(locatedTrain.lon - latestUserGps.lon),
        )
        zoom = when {
          distDeg < 0.005 -> 15.0
          distDeg < 0.02 -> 14.0
          distDeg < 0.08 -> 12.0
          distDeg < 0.3 -> 10.0
          distDeg < 1.2 -> 8.0
          else -> 6.5
        }
      } else if (locatedTrain != null) {
        centerLat = locatedTrain.lat
        centerLon = locatedTrain.lon
        zoom = 12.5
      } else if (latestUserGps != null) {
        centerLat = latestUserGps.lat
        centerLon = latestUserGps.lon
        zoom = 14.0
      }
    }
  }

  val density = LocalDensity.current
  val tileSizePx = 256.0 * density.density

  Box(
    modifier = modifier
      .clipToBounds()
      .background(Color(0xFFE4EAEF))
      .onSizeChanged { canvasSize = it }
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoomChange, _ ->
          val newZoom = (zoom + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(3.0, 17.5)
          zoom = newZoom
          val worldSize = tileSizePx * 2.0.pow(zoom)
          val centerWorldX = lonToWorldX(centerLon) * worldSize - pan.x
          val centerWorldY = latToWorldY(centerLat) * worldSize - pan.y
          centerLon = worldXToLon((centerWorldX / worldSize).coerceIn(0.0, 1.0))
          centerLat = worldYToLat((centerWorldY / worldSize).coerceIn(0.0, 1.0))
        }
      }
      .pointerInput(Unit) {
        detectTapGestures(
          onDoubleTap = { zoom = (zoom + 1.0).coerceAtMost(17.5) },
          onTap = { selectedMarkerInfo = null },
        )
      },
  ) {
    val width = canvasSize.width.toFloat()
    val height = canvasSize.height.toFloat()

    fun latLonToScreen(lat: Double, lon: Double): Offset {
      val worldSize = tileSizePx * 2.0.pow(zoom)
      val cx = lonToWorldX(centerLon) * worldSize
      val cy = latToWorldY(centerLat) * worldSize
      val px = lonToWorldX(lon) * worldSize
      val py = latToWorldY(lat) * worldSize
      return Offset(
        x = (width / 2f + (px - cx)).toFloat(),
        y = (height / 2f + (py - cy)).toFloat(),
      )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
      val _rev = tileRevision

      val intZoom = floor(zoom).toInt().coerceIn(3, 17)
      val scaleFactor = 2.0.pow(zoom - intZoom)
      val scaledTileSize = (tileSizePx * scaleFactor).toFloat()
      val totalTiles = 1 shl intZoom
      val fullWorldPx = scaledTileSize * totalTiles

      val centerWorldX = lonToWorldX(centerLon) * fullWorldPx
      val centerWorldY = latToWorldY(centerLat) * fullWorldPx
      val topLeftWorldX = centerWorldX - size.width / 2.0
      val topLeftWorldY = centerWorldY - size.height / 2.0

      val startTileX = floor(topLeftWorldX / scaledTileSize).toInt()
      val endTileX = floor((topLeftWorldX + size.width) / scaledTileSize).toInt()
      val startTileY = floor(topLeftWorldY / scaledTileSize).toInt().coerceAtLeast(0)
      val endTileY = floor((topLeftWorldY + size.height) / scaledTileSize).toInt()
        .coerceAtMost(totalTiles - 1)

      for (tx in startTileX..endTileX) {
        for (ty in startTileY..endTileY) {
          val screenX = (tx * scaledTileSize - topLeftWorldX).toFloat()
          val screenY = (ty * scaledTileSize - topLeftWorldY).toFloat()
          val tileBitmap = tileLoader.getTile(intZoom, tx, ty)
          if (tileBitmap != null) {
            drawImage(
              image = tileBitmap,
              dstOffset = IntOffset(screenX.roundToInt(), screenY.roundToInt()),
              dstSize = IntSize(
                (scaledTileSize + 1f).roundToInt(),
                (scaledTileSize + 1f).roundToInt(),
              ),
            )
          } else {
            drawRect(
              color = Color(0xFFDCE4EC),
              topLeft = Offset(screenX, screenY),
              size = androidx.compose.ui.geometry.Size(scaledTileSize, scaledTileSize),
              style = Stroke(width = 1f),
            )
          }
        }
      }

      // Railway polyline
      val validPoints = route.mapNotNull { st ->
        if (st.lat != null && st.lon != null) latLonToScreen(st.lat, st.lon) else null
      }
      if (validPoints.size >= 2) {
        for (i in 0 until validPoints.lastIndex) {
          drawLine(
            color = CobaltPrimary.copy(alpha = 0.75f),
            start = validPoints[i],
            end = validPoints[i + 1],
            strokeWidth = 3.5.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }
      }

      // Station dots + highlight rings for focused / alarm stations
      route.forEach { st ->
        if (st.lat != null && st.lon != null) {
          val pos = latLonToScreen(st.lat, st.lon)
          if (pos.x in -30f..(size.width + 30f) && pos.y in -30f..(size.height + 30f)) {
            val isAlarm = alarmStation != null &&
              alarmStation.stationCode.equals(st.stationCode, ignoreCase = true)
            val isFocused = focusedStation != null &&
              focusedStation.stationCode.equals(st.stationCode, ignoreCase = true) &&
              focusedStation.stationName == st.stationName.ifBlank { st.stationCode }

            if (isAlarm || isFocused) {
              drawCircle(
                color = NtesOrange.copy(alpha = 0.35f),
                radius = 13.dp.toPx(),
                center = pos,
              )
            }
            drawCircle(
              color = Color.White,
              radius = 6.5.dp.toPx(),
              center = pos,
            )
            drawCircle(
              color = if (isAlarm) NtesOrange else CobaltPrimary,
              radius = 4.5.dp.toPx(),
              center = pos,
            )
          }
        }
      }

      // User GPS Accuracy Ring + Pulsing Halo + Heading Cone
      if (latestUserGps != null) {
        val uPt = latLonToScreen(latestUserGps.lat, latestUserGps.lon)
        val metersPerTilePx =
          156543.03392 * cos(Math.toRadians(latestUserGps.lat)) / 2.0.pow(zoom)
        val rawAccPx = if (metersPerTilePx > 0.0 && latestUserGps.accuracy.isFinite()) {
          (latestUserGps.accuracy / metersPerTilePx).toFloat()
        } else {
          14.dp.toPx()
        }
        val accuracyRadiusPx = if (rawAccPx.isFinite()) {
          rawAccPx.coerceIn(14.dp.toPx(), 160.dp.toPx())
        } else {
          14.dp.toPx()
        }

        drawCircle(
          color = Color(0x221565C0),
          radius = accuracyRadiusPx,
          center = uPt,
        )
        drawCircle(
          color = Color(0x661565C0),
          radius = accuracyRadiusPx,
          center = uPt,
          style = Stroke(width = 1.5.dp.toPx()),
        )

        // Pulsing halo
        drawCircle(
          color = Color(0xFF1565C0).copy(alpha = (1f - pulseProgress) * 0.45f),
          radius = (16.dp.toPx() + pulseProgress * 18.dp.toPx()),
          center = uPt,
        )

        // Directional heading arrow when moving >= 2 km/h
        if (userSpeedKmh >= 2.0) {
          val rad = Math.toRadians(userBearingDegrees)
          val tipDist = 26.dp.toPx()
          val baseDist = 13.dp.toPx()
          val halfAngle = Math.toRadians(24.0)
          val tip = Offset(
            uPt.x + (sin(rad) * tipDist).toFloat(),
            uPt.y - (cos(rad) * tipDist).toFloat(),
          )
          val left = Offset(
            uPt.x + (sin(rad - halfAngle) * baseDist).toFloat(),
            uPt.y - (cos(rad - halfAngle) * baseDist).toFloat(),
          )
          val right = Offset(
            uPt.x + (sin(rad + halfAngle) * baseDist).toFloat(),
            uPt.y - (cos(rad + halfAngle) * baseDist).toFloat(),
          )
          val arrowPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
          }
          drawPath(path = arrowPath, color = Color(0xFF1565C0))
        }
      }

      // Pulsing halo around train marker
      if (locatedTrain != null) {
        val tPt = latLonToScreen(locatedTrain.lat, locatedTrain.lon)
        drawCircle(
          color = CobaltPrimary.copy(alpha = (1f - pulseProgress) * 0.45f),
          radius = (19.dp.toPx() + pulseProgress * 22.dp.toPx()),
          center = tPt,
        )
      }

      // Dashed distance line between train & user when outside train
      if (locatedTrain != null && latestUserGps != null && !insideTrain) {
        val trainPt = latLonToScreen(locatedTrain.lat, locatedTrain.lon)
        val userPt = latLonToScreen(latestUserGps.lat, latestUserGps.lon)
        drawLine(
          color = Color(0xFF1565C0),
          start = trainPt,
          end = userPt,
          strokeWidth = 3.dp.toPx(),
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f),
        )
      }
    }

    if (width > 0f && height > 0f) {
      val showAllLabels = zoom >= 9.2 || route.size <= 12
      route.forEachIndexed { index, st ->
        val isFocusedOrAlarm = (focusedStation?.stationCode.equals(st.stationCode, true)) ||
          (alarmStation?.stationCode.equals(st.stationCode, true))
        if (st.lat != null && st.lon != null &&
          (showAllLabels || index == locatedTrain?.idx || isFocusedOrAlarm)
        ) {
          val pos = latLonToScreen(st.lat, st.lon)
          if (pos.x in 0f..width && pos.y in 0f..height) {
            val isRight = index % 2 == 0
            val offsetX = if (isRight) {
              pos.x.roundToInt() + with(density) { 10.dp.roundToPx() }
            } else {
              pos.x.roundToInt() - with(density) { 94.dp.roundToPx() }
            }
            val offsetY = pos.y.roundToInt() - with(density) { 10.dp.roundToPx() }
            Surface(
              modifier = Modifier.offset { IntOffset(offsetX, offsetY) },
              shape = RoundedCornerShape(4.dp),
              color = Color.White.copy(alpha = 0.92f),
              shadowElevation = 2.dp,
            ) {
              Text(
                text = st.stationName.ifBlank { st.stationCode },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isFocusedOrAlarm) NtesOrange else CobaltPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                  .border(
                    1.dp,
                    (if (isFocusedOrAlarm) NtesOrange else CobaltPrimary).copy(alpha = 0.75f),
                    RoundedCornerShape(4.dp),
                  )
                  .padding(horizontal = 5.dp, vertical = 1.dp),
              )
            }
          }
        }
      }

      // Distance badge at midpoint
      if (locatedTrain != null && latestUserGps != null && !insideTrain && distanceKm != null) {
        val midLat = (locatedTrain.lat + latestUserGps.lat) / 2.0
        val midLon = (locatedTrain.lon + latestUserGps.lon) / 2.0
        val midPt = latLonToScreen(midLat, midLon)
        if (midPt.x in 0f..width && midPt.y in 0f..height) {
          Surface(
            modifier = Modifier.offset {
              IntOffset(
                midPt.x.roundToInt() - with(density) { 42.dp.roundToPx() },
                midPt.y.roundToInt() - with(density) { 14.dp.roundToPx() },
              )
            },
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 4.dp,
          ) {
            Text(
              text = "📏 ${String.format(Locale.US, "%.2f", distanceKm.takeIf { it.isFinite() } ?: 0.0)} km",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF1565C0),
              modifier = Modifier
                .border(1.5.dp, Color(0xFF1565C0), RoundedCornerShape(18.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }
      }

      // User GPS Pin
      if (latestUserGps != null) {
        val uPt = latLonToScreen(latestUserGps.lat, latestUserGps.lon)
        if (uPt.x in -40f..(width + 40f) && uPt.y in -40f..(height + 40f)) {
          val pinSizePx = with(density) { 30.dp.roundToPx() }
          Surface(
            onClick = {
              selectedMarkerInfo =
                "${strings.userPopupLabel} (±${latestUserGps.accuracy.roundToInt()}m)"
            },
            modifier = Modifier
              .offset {
                IntOffset(uPt.x.roundToInt() - pinSizePx / 2, uPt.y.roundToInt() - pinSizePx / 2)
              }
              .size(30.dp),
            shape = CircleShape,
            color = Color(0xFF1565C0),
            shadowElevation = 5.dp,
          ) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .border(2.5.dp, Color.White, CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              Box(
                modifier = Modifier
                  .size(9.dp)
                  .clip(CircleShape)
                  .background(Color.White),
              )
            }
          }
        }
      }

      // Train Marker
      if (locatedTrain != null) {
        val tPt = latLonToScreen(locatedTrain.lat, locatedTrain.lon)
        if (tPt.x in -40f..(width + 40f) && tPt.y in -40f..(height + 40f)) {
          val badgeSizePx = with(density) { 38.dp.roundToPx() }
          Surface(
            onClick = {
              selectedMarkerInfo =
                "🚆 ${locatedTrain.name} (${String.format(Locale.US, "%.1f", locatedTrain.speedKmh)} km/h)"
            },
            modifier = Modifier
              .offset {
                IntOffset(tPt.x.roundToInt() - badgeSizePx / 2, tPt.y.roundToInt() - badgeSizePx / 2)
              }
              .size(38.dp),
            shape = CircleShape,
            color = CobaltPrimary,
            shadowElevation = 6.dp,
          ) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .border(2.5.dp, Color.White, CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.Train,
                contentDescription = "Train Marker",
                tint = Color.White,
                modifier = Modifier.size(21.dp),
              )
            }
          }
        }
      }
    }

    if (selectedMarkerInfo != null) {
      Surface(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(10.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
      ) {
        Text(
          text = selectedMarkerInfo ?: "",
          style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }

    Column(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .navigationBarsPadding()
        .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
      ) {
        Column {
          IconButton(
            onClick = { zoom = (zoom + 1.0).coerceAtMost(17.5) },
            modifier = Modifier.size(40.dp),
          ) {
            Icon(Icons.Filled.Add, contentDescription = "Zoom in", tint = CobaltPrimary)
          }
          IconButton(
            onClick = { zoom = (zoom - 1.0).coerceAtLeast(3.0) },
            modifier = Modifier.size(40.dp),
          ) {
            Icon(Icons.Filled.Remove, contentDescription = "Zoom out", tint = CobaltPrimary)
          }
        }
      }
    }

    Surface(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(8.dp),
      shape = RoundedCornerShape(6.dp),
      color = Color.White.copy(alpha = 0.85f),
    ) {
      Text(
        text = "© OpenStreetMap contributors",
        fontSize = 10.sp,
        color = Color(0xFF334155),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      )
    }
  }
}

private fun lonToWorldX(lon: Double): Double = (lon + 180.0) / 360.0

private fun latToWorldY(lat: Double): Double {
  val clamped = lat.coerceIn(-85.0511, 85.0511)
  val rad = Math.toRadians(clamped)
  return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

private fun worldXToLon(x: Double): Double = x * 360.0 - 180.0

private fun worldYToLat(y: Double): Double {
  val n = PI - 2.0 * PI * y
  return Math.toDegrees(atan(sinh(n)))
}
