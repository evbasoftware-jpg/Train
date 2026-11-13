package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AppLanguage
import com.example.model.BannerType
import com.example.model.LocalizedStrings
import com.example.model.StationDbEntry
import com.example.model.StatusBanner
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

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) { results ->
    val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
    val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    viewModel.onLocationPermissionResult(fineGranted || coarseGranted)
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
      permissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      BoxWithConstraints(
        modifier = Modifier
          .widthIn(max = 1100.dp)
          .fillMaxSize(),
      ) {
        val isWideLayout = maxWidth >= 640.dp

        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        ) {
          // Top Language Bar + Hero Header
          TrackerHeader(
            language = state.language,
            strings = strings,
            onSelectLanguage = viewModel::setLanguage,
          )

          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            // Search & Train Controls Panel
            SearchAndControlPanel(
              fromInput = state.fromInput,
              toInput = state.toInput,
              fromSuggestions = state.fromSuggestions,
              toSuggestions = state.toSuggestions,
              betweenLoading = state.betweenLoading,
              betweenError = state.betweenError,
              betweenTrains = state.betweenTrains,
              trainNoInput = state.trainNoInput,
              running = state.running,
              isWideLayout = isWideLayout,
              strings = strings,
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
            )

            // Result Banner (Verification result / offset)
            AnimatedVisibility(visible = state.resultBanner != null) {
              state.resultBanner?.let { banner ->
                StatusBannerBox(banner = banner, testTag = "result_banner")
              }
            }

            // Status Banner (Live fetch / GPS status)
            AnimatedVisibility(visible = state.statusBanner != null) {
              state.statusBanner?.let { banner ->
                StatusBannerBox(
                  banner = banner,
                  showPermissionAction = !state.locationPermissionGranted &&
                    banner.type == BannerType.BAD,
                  permissionButtonLabel = strings.grantPermissionBtn,
                  onRequestPermission = { viewModel.requestLocationPermissionPrompt() },
                  testTag = "status_banner",
                )
              }
            }

            // Horizontal Animated Route Timeline
            RouteTimelineCard(
              route = state.route,
              locatedTrain = state.locatedTrain,
              insideTrain = state.insideTrain,
              userSpeedKmh = state.movementStats.speedKmh,
              running = state.running,
              strings = strings,
            )

            // Train Live Data Summary Card
            TrainLiveCard(
              trainNo = state.trainNoInput,
              trainName = state.trainName,
              locatedTrain = state.locatedTrain,
              rawSource = state.rawSource,
              insideTrain = state.insideTrain,
              movementStats = state.movementStats,
              strings = strings,
            )

            // Interactive OpenStreetMap Live View
            OsmMapSection(
              tileLoader = viewModel.tileLoader,
              route = state.route,
              locatedTrain = state.locatedTrain,
              latestUserGps = state.gpsSamples.lastOrNull(),
              insideTrain = state.insideTrain,
              distanceKm = state.verification?.distanceKm,
              mapSizePreset = state.mapSizePreset,
              isFullscreen = state.mapFullscreen,
              recenterSignal = state.recenterSignal,
              strings = strings,
              onSizePresetSelected = viewModel::setMapSizePreset,
              onToggleFullscreen = viewModel::setMapFullscreen,
              onRecenter = viewModel::triggerRecenterMap,
            )

            // Telemetry & GPS Verification Cards (1-col compact / 2-col wide)
            if (isWideLayout) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                UserGpsCard(
                  latestSample = state.gpsSamples.lastOrNull(),
                  lowAccuracyMeters = state.lowAccuracyMeters,
                  movementStats = state.movementStats,
                  strings = strings,
                  modifier = Modifier.weight(1f),
                )
                MovementDistanceCard(
                  verification = state.verification,
                  movementStats = state.movementStats,
                  locatedTrain = state.locatedTrain,
                  strings = strings,
                  modifier = Modifier.weight(1f),
                )
              }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                ConfidenceScoreCard(
                  verification = state.verification,
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
              ConfidenceScoreCard(
                verification = state.verification,
                strings = strings,
              )
              GpsSamplesCard(
                samples = state.gpsSamples,
                strings = strings,
              )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = strings.appSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            )
          }
        }
      }
    }
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
      .padding(horizontal = 16.dp, vertical = 14.dp),
  ) {
    // Top Language Selector Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
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
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Language,
              contentDescription = strings.languageLabel,
              tint = CobaltPrimary,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = "${strings.languageLabel}: ${language.label}",
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

    Spacer(modifier = Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Surface(
        shape = CircleShape,
        color = CobaltPrimary,
        shadowElevation = 4.dp,
        modifier = Modifier.size(44.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Filled.DirectionsRailway,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column {
        Text(
          text = strings.appTitle,
          style = MaterialTheme.typography.headlineSmall,
          color = titleColor,
        )
        Text(
          text = strings.appSubtitle,
          style = MaterialTheme.typography.bodySmall,
          color = subtitleColor,
        )
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
  running: Boolean,
  isWideLayout: Boolean,
  strings: LocalizedStrings,
  onFromChanged: (String) -> Unit,
  onToChanged: (String) -> Unit,
  onStationSelected: (Boolean, StationDbEntry) -> Unit,
  onSwapStations: () -> Unit,
  onSelectBetweenTrain: (String) -> Unit,
  onTrainNoChanged: (String) -> Unit,
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

      // 5-digit Train Number Input
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
          if (trainNoInput.isNotEmpty()) {
            IconButton(onClick = { onTrainNoChanged("") }) {
              Icon(Icons.Filled.Clear, contentDescription = "Clear train number")
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
