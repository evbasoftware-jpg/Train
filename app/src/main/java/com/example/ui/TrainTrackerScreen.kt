package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AppLanguage
import com.example.model.BannerType
import com.example.model.LocalizedStrings
import com.example.model.SavedRoutePair
import com.example.model.SavedTrainEntry
import com.example.model.StationAlarmState
import com.example.model.StationDbEntry
import com.example.model.StatusBanner
import com.example.model.TrackerTab
import com.example.model.TrackerUiState
import com.example.model.TrainBetweenItem
import com.example.model.stringsFor
import com.example.ui.components.ConfidenceScoreCard
import com.example.ui.components.GpsSamplesCard
import com.example.ui.components.MovementDistanceCard
import com.example.ui.components.OsmMapSection
import com.example.ui.components.RouteTimelineCard
import com.example.ui.components.TrainLiveCard
import com.example.ui.components.UserGpsCard
import com.example.ui.theme.CobaltDark
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.HeaderIndigo
import com.example.ui.theme.HeaderWarmCream
import com.example.ui.theme.InfoBannerBlue
import com.example.ui.theme.InfoBannerContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NtesOrange
import com.example.ui.theme.StopCrimson
import com.example.ui.theme.StopCrimsonContainer
import com.example.ui.theme.VerifiedEmeraldContainer
import com.example.ui.theme.VerifiedEmeraldDark
import com.example.viewmodel.TrainTrackerViewModel
import java.util.Locale

@Composable
fun TrainTrackerScreen(
  viewModel: TrainTrackerViewModel,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val strings = stringsFor(state.language)
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) { results ->
    val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
    val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    viewModel.onLocationPermissionResult(fineGranted || coarseGranted)
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) {
    viewModel.onNotificationPermissionHandled()
  }

  LaunchedEffect(Unit) {
    val fine = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    viewModel.setLocationPermissionState(fine || coarse)
  }

  LaunchedEffect(state.needsLocationPermissionPrompt) {
    if (state.needsLocationPermissionPrompt) {
      locationPermissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
    }
  }

  LaunchedEffect(state.needsNotificationPermissionPrompt) {
    if (state.needsNotificationPermissionPrompt) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val already = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!already) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
          viewModel.onNotificationPermissionHandled()
        }
      } else {
        viewModel.onNotificationPermissionHandled()
      }
    }
  }

  BackHandler(enabled = state.activeTab != TrackerTab.LIVE_ROUTE) {
    viewModel.setActiveTab(TrackerTab.LIVE_ROUTE)
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isWideLayout = maxWidth >= 640.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        if (!isWideLayout) {
          NavigationBar(
            windowInsets = WindowInsets.navigationBars,
            containerColor = MaterialTheme.colorScheme.surface,
          ) {
            TrackerTab.entries.forEach { tab ->
              NavigationBarItem(
                selected = state.activeTab == tab,
                onClick = { viewModel.setActiveTab(tab) },
                icon = {
                  Icon(
                    imageVector = tabIcon(tab),
                    contentDescription = tabLabel(tab, strings),
                  )
                },
                label = {
                  Text(
                    text = tabLabel(tab, strings),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                modifier = Modifier.testTag("bottom_tab_${tab.name.lowercase()}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (isWideLayout) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier
              .fillMaxHeight()
              .statusBarsPadding(),
          ) {
            Spacer(modifier = Modifier.height(12.dp))
            TrackerTab.entries.forEach { tab ->
              NavigationRailItem(
                selected = state.activeTab == tab,
                onClick = { viewModel.setActiveTab(tab) },
                icon = {
                  Icon(
                    imageVector = tabIcon(tab),
                    contentDescription = tabLabel(tab, strings),
                  )
                },
                label = {
                  Text(
                    text = tabLabel(tab, strings),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}"),
              )
            }
          }
        }

        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.TopCenter,
        ) {
          Column(
            modifier = Modifier
              .widthIn(max = 1100.dp)
              .fillMaxSize(),
          ) {
            TrackerHeader(
              language = state.language,
              strings = strings,
              onSelectLanguage = viewModel::setLanguage,
            )

            when (state.activeTab) {
              TrackerTab.LIVE_ROUTE -> LiveRouteTabContent(
                state = state,
                strings = strings,
                isWideLayout = isWideLayout,
                onFromChanged = viewModel::onFromInputChanged,
                onToChanged = viewModel::onToInputChanged,
                onStationSelected = { isFrom, entry ->
                  focusManager.clearFocus()
                  viewModel.onStationSelected(isFrom, entry)
                },
                onSwapStations = viewModel::swapStations,
                onSelectBetweenTrain = { trainNo ->
                  focusManager.clearFocus()
                  viewModel.onSelectTrainBetween(trainNo)
                },
                onTrainNoChanged = viewModel::onTrainNoChanged,
                onToggleFavorite = viewModel::toggleFavoriteCurrentTrain,
                onQuickTrainClick = { entry ->
                  focusManager.clearFocus()
                  viewModel.onQuickTrainSelected(entry)
                },
                onRecentRouteClick = { pair ->
                  focusManager.clearFocus()
                  viewModel.onRecentRouteSelected(pair)
                },
                onStartTracking = {
                  focusManager.clearFocus()
                  viewModel.startTracking()
                },
                onStopTracking = { viewModel.stopTracking(showBanner = true) },
                onOpenNtes = {
                  val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://enquiry.indianrail.gov.in/mntes/"),
                  )
                  context.startActivity(intent)
                },
                onRequestPermission = viewModel::requestLocationPermissionPrompt,
                onStationNodeClick = viewModel::onStationNodeClicked,
                onFocusStationOnMap = viewModel::focusStationOnMap,
                onToggleStationAlarm = viewModel::toggleStationAlarm,
                onDismissAlarm = viewModel::dismissStationAlarm,
              )

              TrackerTab.MAP -> MapTabContent(
                state = state,
                strings = strings,
                viewModel = viewModel,
              )

              TrackerTab.GPS_DIAGNOSTICS -> GpsDiagnosticsTabContent(
                state = state,
                strings = strings,
                isWideLayout = isWideLayout,
                onToggleBackgroundTracking = viewModel::setBackgroundTrackingEnabled,
              )
            }
          }
        }
      }
    }
  }
}

private fun tabIcon(tab: TrackerTab) = when (tab) {
  TrackerTab.LIVE_ROUTE -> Icons.Filled.Route
  TrackerTab.MAP -> Icons.Filled.Map
  TrackerTab.GPS_DIAGNOSTICS -> Icons.Filled.GpsFixed
}

private fun tabLabel(tab: TrackerTab, strings: LocalizedStrings) = when (tab) {
  TrackerTab.LIVE_ROUTE -> strings.tabLiveRoute
  TrackerTab.MAP -> strings.tabMap
  TrackerTab.GPS_DIAGNOSTICS -> strings.tabGps
}

@Composable
private fun LiveRouteTabContent(
  state: TrackerUiState,
  strings: LocalizedStrings,
  isWideLayout: Boolean,
  onFromChanged: (String) -> Unit,
  onToChanged: (String) -> Unit,
  onStationSelected: (Boolean, StationDbEntry) -> Unit,
  onSwapStations: () -> Unit,
  onSelectBetweenTrain: (String) -> Unit,
  onTrainNoChanged: (String) -> Unit,
  onToggleFavorite: () -> Unit,
  onQuickTrainClick: (SavedTrainEntry) -> Unit,
  onRecentRouteClick: (SavedRoutePair) -> Unit,
  onStartTracking: () -> Unit,
  onStopTracking: () -> Unit,
  onOpenNtes: () -> Unit,
  onRequestPermission: () -> Unit,
  onStationNodeClick: (Int) -> Unit,
  onFocusStationOnMap: (Int) -> Unit,
  onToggleStationAlarm: (Int) -> Unit,
  onDismissAlarm: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SearchAndControlPanel(
      fromInput = state.fromInput,
      toInput = state.toInput,
      fromSuggestions = state.fromSuggestions,
      toSuggestions = state.toSuggestions,
      betweenLoading = state.betweenLoading,
      betweenError = state.betweenError,
      betweenTrains = state.betweenTrains,
      trainNoInput = state.trainNoInput,
      isCurrentTrainFavorite = state.favoriteTrains.any {
        it.trainNumber == state.trainNoInput.trim()
      },
      favoriteTrains = state.favoriteTrains,
      recentTrains = state.recentTrains,
      recentRoutes = state.recentRoutes,
      running = state.running,
      isWideLayout = isWideLayout,
      strings = strings,
      onFromChanged = onFromChanged,
      onToChanged = onToChanged,
      onStationSelected = onStationSelected,
      onSwapStations = onSwapStations,
      onSelectBetweenTrain = onSelectBetweenTrain,
      onTrainNoChanged = onTrainNoChanged,
      onToggleFavorite = onToggleFavorite,
      onQuickTrainClick = onQuickTrainClick,
      onRecentRouteClick = onRecentRouteClick,
      onStartTracking = onStartTracking,
      onStopTracking = onStopTracking,
      onOpenNtes = onOpenNtes,
    )

    // Destination Alarm Active / Triggered Alert Banner
    AnimatedVisibility(visible = state.stationAlarm != null || state.alarmAlertMessage != null) {
      AlarmBannerBox(
        alarm = state.stationAlarm,
        alertMessage = state.alarmAlertMessage,
        strings = strings,
        onDismiss = onDismissAlarm,
      )
    }

    // Verification Result Banner
    AnimatedVisibility(visible = state.resultBanner != null) {
      state.resultBanner?.let { banner ->
        StatusBannerBox(banner = banner, testTag = "result_banner")
      }
    }

    // Live Fetch / GPS Status Banner
    AnimatedVisibility(visible = state.statusBanner != null) {
      state.statusBanner?.let { banner ->
        StatusBannerBox(
          banner = banner,
          showPermissionAction = !state.locationPermissionGranted &&
            banner.type == BannerType.BAD,
          permissionButtonLabel = strings.grantPermissionBtn,
          onRequestPermission = onRequestPermission,
          testTag = "status_banner",
        )
      }
    }

    RouteTimelineCard(
      route = state.route,
      locatedTrain = state.locatedTrain,
      insideTrain = state.insideTrain,
      userSpeedKmh = state.movementStats.speedKmh,
      running = state.running,
      selectedStationIndex = state.selectedStationIndex,
      stationAlarm = state.stationAlarm,
      strings = strings,
      onStationClick = onStationNodeClick,
      onFocusStationOnMap = onFocusStationOnMap,
      onToggleStationAlarm = onToggleStationAlarm,
    )

    TrainLiveCard(
      trainNo = state.trainNoInput,
      trainName = state.trainName,
      locatedTrain = state.locatedTrain,
      rawSource = state.rawSource,
      insideTrain = state.insideTrain,
      movementStats = state.movementStats,
      strings = strings,
    )

    Spacer(modifier = Modifier.height(6.dp))
  }
}

@Composable
private fun MapTabContent(
  state: TrackerUiState,
  strings: LocalizedStrings,
  viewModel: TrainTrackerViewModel,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (state.locatedTrain != null) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "🚆 ${state.trainNoInput} ${state.trainName?.let { "• $it" } ?: ""}",
              style = MaterialTheme.typography.titleSmall,
              color = CobaltPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${state.locatedTrain.name}${
                if (state.locatedTrain.next.isNotBlank()) " → ${state.locatedTrain.next}" else ""
              }",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          state.verification?.let { v ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                text = "${String.format(Locale.US, "%.2f", v.distanceKm)} km",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CobaltPrimary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
    }

    OsmMapSection(
      tileLoader = viewModel.tileLoader,
      route = state.route,
      locatedTrain = state.locatedTrain,
      latestUserGps = state.gpsSamples.lastOrNull(),
      userBearingDegrees = state.movementStats.bearingDegrees,
      userSpeedKmh = state.movementStats.speedKmh,
      insideTrain = state.insideTrain,
      distanceKm = state.verification?.distanceKm,
      focusedStation = state.focusedMapStation,
      alarmStation = state.stationAlarm,
      mapSizePreset = state.mapSizePreset,
      isFullscreen = state.mapFullscreen,
      recenterSignal = state.recenterSignal,
      strings = strings,
      showSizePresets = false,
      fillAvailableHeight = true,
      onSizePresetSelected = viewModel::setMapSizePreset,
      onToggleFullscreen = viewModel::setMapFullscreen,
      onRecenter = viewModel::triggerRecenterMap,
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
    )
  }
}

@Composable
private fun GpsDiagnosticsTabContent(
  state: TrackerUiState,
  strings: LocalizedStrings,
  isWideLayout: Boolean,
  onToggleBackgroundTracking: (Boolean) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Background Live Notification Switch Card
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("background_tracking_card"),
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.NotificationsActive,
            contentDescription = null,
            tint = CobaltPrimary,
            modifier = Modifier.size(20.dp),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = strings.backgroundTrackingTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = strings.backgroundTrackingDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = state.backgroundTrackingEnabled,
          onCheckedChange = onToggleBackgroundTracking,
          modifier = Modifier.testTag("background_tracking_switch"),
        )
      }
    }

    if (isWideLayout) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ConfidenceScoreCard(
          verification = state.verification,
          strings = strings,
          modifier = Modifier.weight(1f),
        )
        UserGpsCard(
          latestSample = state.gpsSamples.lastOrNull(),
          lowAccuracyMeters = state.lowAccuracyMeters,
          movementStats = state.movementStats,
          strings = strings,
          modifier = Modifier.weight(1f),
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        MovementDistanceCard(
          verification = state.verification,
          movementStats = state.movementStats,
          locatedTrain = state.locatedTrain,
          strings = strings,
          modifier = Modifier.weight(1f),
        )
        GpsSamplesCard(
          samples = state.gpsSamples,
          strings = strings,
          modifier = Modifier.weight(1f),
        )
      }
    } else {
      ConfidenceScoreCard(
        verification = state.verification,
        strings = strings,
      )
      UserGpsCard(
        latestSample = state.gpsSamples.lastOrNull(),
        lowAccuracyMeters = state.lowAccuracyMeters,
        movementStats = state.movementStats,
        strings = strings,
      )
      MovementDistanceCard(
        verification = state.verification,
        movementStats = state.movementStats,
        locatedTrain = state.locatedTrain,
        strings = strings,
      )
      GpsSamplesCard(
        samples = state.gpsSamples,
        strings = strings,
      )
    }

    Spacer(modifier = Modifier.height(6.dp))
  }
}

@Composable
private fun TrackerHeader(
  language: AppLanguage,
  strings: LocalizedStrings,
  onSelectLanguage: (AppLanguage) -> Unit,
) {
  val isDark = isSystemInDarkTheme()
  var langMenuExpanded by remember { mutableStateOf(false) }

  val headerBrush = if (isDark) {
    Brush.verticalGradient(listOf(CobaltDark, Color(0xFF0A2A47)))
  } else {
    Brush.verticalGradient(listOf(HeaderWarmCream, Color(0xFFFFFDE7)))
  }
  val titleColor = if (isDark) Color.White else HeaderIndigo
  val subtitleColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(headerBrush)
      .statusBarsPadding()
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f),
      ) {
        Surface(
          shape = CircleShape,
          color = CobaltPrimary,
          shadowElevation = 4.dp,
          modifier = Modifier.size(38.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.DirectionsRailway,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(21.dp),
            )
          }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = strings.appTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = strings.appSubtitle,
            style = MaterialTheme.typography.labelSmall,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Box {
        Surface(
          onClick = { langMenuExpanded = true },
          shape = RoundedCornerShape(20.dp),
          color = if (isDark) Color(0xFF153452) else Color.White,
          shadowElevation = 2.dp,
          modifier = Modifier.testTag("language_selector_button"),
        ) {
          Row(
            modifier = Modifier
              .border(
                1.dp,
                if (isDark) Color(0xFF2D5378) else Color(0xFFCBD5E1),
                RoundedCornerShape(20.dp),
              )
              .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Language,
              contentDescription = strings.languageLabel,
              tint = CobaltPrimary,
              modifier = Modifier.size(15.dp),
            )
            Text(
              text = language.label,
              style = MaterialTheme.typography.labelMedium,
              color = if (isDark) Color.White else Color(0xFF1E293B),
            )
          }
        }

        DropdownMenu(
          expanded = langMenuExpanded,
          onDismissRequest = { langMenuExpanded = false },
        ) {
          AppLanguage.entries.forEach { lang ->
            DropdownMenuItem(
              text = {
                Text(
                  text = lang.label,
                  fontWeight = if (lang == language) FontWeight.Bold else FontWeight.Normal,
                )
              },
              onClick = {
                langMenuExpanded = false
                onSelectLanguage(lang)
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SearchAndControlPanel(
  fromInput: String,
  toInput: String,
  fromSuggestions: List<StationDbEntry>,
  toSuggestions: List<StationDbEntry>,
  betweenLoading: Boolean,
  betweenError: String?,
  betweenTrains: List<TrainBetweenItem>,
  trainNoInput: String,
  isCurrentTrainFavorite: Boolean,
  favoriteTrains: List<SavedTrainEntry>,
  recentTrains: List<SavedTrainEntry>,
  recentRoutes: List<SavedRoutePair>,
  running: Boolean,
  isWideLayout: Boolean,
  strings: LocalizedStrings,
  onFromChanged: (String) -> Unit,
  onToChanged: (String) -> Unit,
  onStationSelected: (Boolean, StationDbEntry) -> Unit,
  onSwapStations: () -> Unit,
  onSelectBetweenTrain: (String) -> Unit,
  onTrainNoChanged: (String) -> Unit,
  onToggleFavorite: () -> Unit,
  onQuickTrainClick: (SavedTrainEntry) -> Unit,
  onRecentRouteClick: (SavedRoutePair) -> Unit,
  onStartTracking: () -> Unit,
  onStopTracking: () -> Unit,
  onOpenNtes: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (isWideLayout) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Top,
        ) {
          StationInputWithSuggestions(
            label = strings.fromStationLabel,
            placeholder = strings.stationPlaceholder,
            value = fromInput,
            suggestions = fromSuggestions,
            onValueChange = onFromChanged,
            onSuggestionClick = { onStationSelected(true, it) },
            testTag = "from_station_input",
            modifier = Modifier.weight(1f),
          )
          IconButton(
            onClick = onSwapStations,
            modifier = Modifier
              .padding(top = 8.dp)
              .size(48.dp)
              .testTag("swap_stations_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.SwapVert,
              contentDescription = strings.swapStationsDesc,
              tint = CobaltPrimary,
            )
          }
          StationInputWithSuggestions(
            label = strings.toStationLabel,
            placeholder = strings.stationPlaceholder,
            value = toInput,
            suggestions = toSuggestions,
            onValueChange = onToChanged,
            onSuggestionClick = { onStationSelected(false, it) },
            testTag = "to_station_input",
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        StationInputWithSuggestions(
          label = strings.fromStationLabel,
          placeholder = strings.stationPlaceholder,
          value = fromInput,
          suggestions = fromSuggestions,
          onValueChange = onFromChanged,
          onSuggestionClick = { onStationSelected(true, it) },
          testTag = "from_station_input",
        )

        Box(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = Alignment.CenterEnd,
        ) {
          Surface(
            onClick = onSwapStations,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
              .size(40.dp)
              .testTag("swap_stations_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = strings.swapStationsDesc,
                tint = CobaltPrimary,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }

        StationInputWithSuggestions(
          label = strings.toStationLabel,
          placeholder = strings.stationPlaceholder,
          value = toInput,
          suggestions = toSuggestions,
          onValueChange = onToChanged,
          onSuggestionClick = { onStationSelected(false, it) },
          testTag = "to_station_input",
        )
      }

      // Recent Routes Quick Chips
      if (recentRoutes.isNotEmpty() && betweenTrains.isEmpty() && !betweenLoading) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = strings.recentRoutesTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            recentRoutes.forEach { pair ->
              Surface(
                onClick = { onRecentRouteClick(pair) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
              ) {
                Text(
                  text = "${pair.fromInput.substringAfterLast('(').removeSuffix(")")} → ${
                    pair.toInput.substringAfterLast('(').removeSuffix(")")
                  }",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = CobaltPrimary,
                  modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                )
              }
            }
          }
        }
      }

      // Between Stations Result List
      if (betweenLoading) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Text(
            text = strings.loadingTrainsBetween,
            style = MaterialTheme.typography.bodyMedium,
            color = CobaltPrimary,
          )
        }
      } else if (betweenError != null) {
        Text(
          text = betweenError,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          color = StopCrimson,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      } else if (betweenTrains.isNotEmpty()) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          betweenTrains.forEach { item ->
            Surface(
              onClick = { onSelectBetweenTrain(item.trainNumber) },
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .testTag("between_train_item_${item.trainNumber}"),
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text(
                  text = "🚆 ${item.trainNumber} — ${item.trainName}",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "${item.departure} → ${item.arrival} • ${String.format(Locale.US, "%.0f", item.distanceKm)} km",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 12.sp,
                  color = CobaltPrimary,
                )
              }
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

      // 5-digit Train Number Input + Favorite Star
      OutlinedTextField(
        value = trainNoInput,
        onValueChange = onTrainNoChanged,
        label = {
          Text(
            text = strings.trainNoLabel,
            fontWeight = FontWeight.Bold,
          )
        },
        placeholder = { Text(strings.trainNoPlaceholder) },
        leadingIcon = {
          Icon(
            imageVector = Icons.Filled.Train,
            contentDescription = null,
            tint = CobaltPrimary,
          )
        },
        trailingIcon = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (trainNoInput.length == 5) {
              IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("favorite_train_button"),
              ) {
                Icon(
                  imageVector = if (isCurrentTrainFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                  contentDescription = strings.favoriteToggleDesc,
                  tint = if (isCurrentTrainFavorite) NtesOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            if (trainNoInput.isNotEmpty()) {
              IconButton(onClick = { onTrainNoChanged("") }) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear train number")
              }
            }
          }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Number,
          imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
          onSearch = { if (trainNoInput.length == 5) onStartTracking() },
        ),
        textStyle = MaterialTheme.typography.titleMedium.copy(
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("train_number_input"),
      )

      // Favorite & Recent Trains Quick-Tap Chips
      if (favoriteTrains.isNotEmpty()) {
        QuickTrainChipsRow(
          title = strings.favoritesSectionTitle,
          entries = favoriteTrains,
          isFavorite = true,
          onChipClick = onQuickTrainClick,
        )
      }
      if (recentTrains.isNotEmpty()) {
        QuickTrainChipsRow(
          title = strings.recentSectionTitle,
          entries = recentTrains,
          isFavorite = false,
          onChipClick = onQuickTrainClick,
        )
      }

      // Action Buttons Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (trainNoInput.length == 5 && !running) {
          Button(
            onClick = onStartTracking,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CobaltPrimary),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("start_tracking_button"),
          ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(
              text = strings.startTrackingBtn,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        Button(
          onClick = onStopTracking,
          enabled = running,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = StopCrimson,
            disabledContainerColor = StopCrimson.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
          ),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("stop_tracking_button"),
        ) {
          Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text(
            text = strings.stopTrackingBtn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
          )
        }

        Button(
          onClick = onOpenNtes,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = NtesOrange),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("official_ntes_button"),
        ) {
          Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = strings.officialNtesBtn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
private fun QuickTrainChipsRow(
  title: String,
  entries: List<SavedTrainEntry>,
  isFavorite: Boolean,
  onChipClick: (SavedTrainEntry) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      entries.forEach { entry ->
        Surface(
          onClick = { onChipClick(entry) },
          shape = RoundedCornerShape(16.dp),
          color = if (isFavorite) {
            NtesOrange.copy(alpha = 0.14f)
          } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
          },
          modifier = Modifier.testTag("quick_train_chip_${entry.trainNumber}"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.History,
              contentDescription = null,
              tint = if (isFavorite) NtesOrange else CobaltPrimary,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = if (entry.trainName.isNotBlank()) {
                "${entry.trainNumber} • ${entry.trainName}"
              } else {
                entry.trainNumber
              },
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = if (isFavorite) Color(0xFFB26500) else CobaltPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AlarmBannerBox(
  alarm: StationAlarmState?,
  alertMessage: String?,
  strings: LocalizedStrings,
  onDismiss: () -> Unit,
) {
  val isTriggered = alertMessage != null
  val bgColor = if (isTriggered) StopCrimsonContainer else Color(0xFFFFF8E1)
  val fgColor = if (isTriggered) StopCrimson else Color(0xFFB26500)
  val text = alertMessage ?: alarm?.let { strings.alarmBannerTitle(it.stationName) } ?: return

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("alarm_banner"),
    shape = RoundedCornerShape(12.dp),
    color = bgColor,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.NotificationsActive,
        contentDescription = null,
        tint = fgColor,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
        color = fgColor,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = onDismiss) {
        Text(
          text = strings.dismissAlarmBtn,
          color = fgColor,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
        )
      }
    }
  }
}

@Composable
private fun StationInputWithSuggestions(
  label: String,
  placeholder: String,
  value: String,
  suggestions: List<StationDbEntry>,
  onValueChange: (String) -> Unit,
  onSuggestionClick: (StationDbEntry) -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label, fontWeight = FontWeight.SemiBold) },
      placeholder = { Text(placeholder) },
      singleLine = true,
      trailingIcon = {
        if (value.isNotEmpty()) {
          IconButton(onClick = { onValueChange("") }) {
            Icon(Icons.Filled.Clear, contentDescription = "Clear $label")
          }
        }
      },
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag(testTag),
    )

    AnimatedVisibility(visible = suggestions.isNotEmpty()) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp)
          .heightIn(max = 200.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
      ) {
        Column(
          modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState()),
        ) {
          suggestions.forEachIndexed { idx, entry ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSuggestionClick(entry) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.width(8.dp))
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
              ) {
                Text(
                  text = entry.code,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = CobaltPrimary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
            if (idx < suggestions.lastIndex) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatusBannerBox(
  banner: StatusBanner,
  showPermissionAction: Boolean = false,
  permissionButtonLabel: String = "",
  onRequestPermission: () -> Unit = {},
  testTag: String,
) {
  val (bgColor, fgColor, icon) = when (banner.type) {
    BannerType.INFO -> Triple(InfoBannerContainer, InfoBannerBlue, Icons.Filled.Info)
    BannerType.OK -> Triple(VerifiedEmeraldContainer, VerifiedEmeraldDark, Icons.Filled.CheckCircle)
    BannerType.BAD -> Triple(StopCrimsonContainer, StopCrimson, Icons.Filled.Error)
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(testTag),
    shape = RoundedCornerShape(12.dp),
    color = bgColor,
  ) {
    Column(modifier = Modifier.padding(13.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = banner.message,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
          color = fgColor,
          modifier = Modifier.weight(1f),
        )
      }
      if (showPermissionAction) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = onRequestPermission,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.testTag("grant_location_permission_button"),
        ) {
          Text(
            text = permissionButtonLabel,
            color = fgColor,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}
