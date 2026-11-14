package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.AdItem
import com.example.model.AdSlot
import com.example.model.BannerType
import com.example.model.GoogleLanguages
import com.example.model.LocalizedStrings
import com.example.model.SavedRoutePair
import com.example.model.SavedTrainEntry
import com.example.model.StationAlarmState
import com.example.model.StationDbEntry
import com.example.model.StatusBanner
import com.example.model.TrainBetweenItem
import com.example.ui.components.AdBannerSlot
import com.example.ui.components.AdDetailsDialog
import com.example.ui.components.OsmMapSection
import com.example.ui.components.RouteTimelineCard
import com.example.ui.components.TrainLiveCard
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
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.theme.VerifiedEmeraldContainer
import com.example.ui.theme.VerifiedEmeraldDark
import com.example.viewmodel.TrainTrackerViewModel
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun TrainTrackerScreen(
  viewModel: TrainTrackerViewModel,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val strings = state.strings
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val mainScrollState = rememberScrollState()
  val coroutineScope = rememberCoroutineScope()
  var mapSectionOffsetPx by remember { mutableIntStateOf(0) }
  var languageDialogOpen by remember { mutableStateOf(false) }
  var selectedAdForDialog by remember { mutableStateOf<AdItem?>(null) }
  val adRefLat = state.latestUserGps?.lat ?: state.locatedTrain?.lat
  val adRefLon = state.latestUserGps?.lon ?: state.locatedTrain?.lon

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

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.refreshAdsNow()
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

  LaunchedEffect(state.needsLocationPermissionPrompt, state.needsNotificationPermissionPrompt) {
    if (state.needsLocationPermissionPrompt) {
      locationPermissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
    } else if (state.needsNotificationPermissionPrompt) {
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

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isWideLayout = maxWidth >= 680.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
          modifier = Modifier
            .widthIn(max = 1040.dp)
            .fillMaxSize(),
        ) {
          TrackerHeader(
            languageCode = state.languageCode,
            isTranslating = state.isTranslatingLanguage,
            strings = strings,
            onOpenLanguagePicker = { languageDialogOpen = true },
          )

          Column(
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(mainScrollState)
              .navigationBarsPadding()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            BrandLogoBannerCard(
              onOpenWebsite = {
                try {
                  val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://aionline.in.net"),
                  )
                  context.startActivity(intent)
                } catch (_: Throwable) {
                }
              },
            )

            // ads1: right above "Train Live Location"
            AdBannerSlot(
              slotKey = AdSlot.ADS1.key,
              ads = state.allAds,
              userLat = adRefLat,
              userLon = adRefLon,
              imageLoader = viewModel.adImageLoader,
              strings = strings,
              onAdClick = { selectedAdForDialog = it },
            )

            SearchAndControlPanel(
              appTitle = strings.appTitle,
              fromInput = state.fromInput,
              toInput = state.toInput,
              fromResolvedStation = state.fromResolvedStation,
              toResolvedStation = state.toResolvedStation,
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
              localizeName = state::localizeName,
              onFromChanged = viewModel::onFromInputChanged,
              onToChanged = viewModel::onToInputChanged,
              onStationSelected = { isFrom, entry ->
                focusManager.clearFocus()
                viewModel.onStationSelected(isFrom, entry)
              },
              onSwapStations = viewModel::swapStations,
              onSelectBetweenTrain = { trainNo, trainName ->
                focusManager.clearFocus()
                viewModel.onSelectTrainBetween(trainNo, trainName)
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
              onStopTracking = { viewModel.stopTracking() },
              onOpenNtes = {
                try {
                  val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://enquiry.indianrail.gov.in/mntes/"),
                  )
                  context.startActivity(intent)
                } catch (_: Throwable) {
                }
              },
              adSlot2Content = {
                // ads2: right above "ট্রেন নম্বর লিখুন"
                AdBannerSlot(
                  slotKey = AdSlot.ADS2.key,
                  ads = state.allAds,
                  userLat = adRefLat,
                  userLon = adRefLon,
                  imageLoader = viewModel.adImageLoader,
                  strings = strings,
                  onAdClick = { selectedAdForDialog = it },
                )
              },
            )

            // Destination Alarm Banner
            AnimatedVisibility(visible = state.stationAlarm != null || state.alarmAlertMessage != null) {
              AlarmBannerBox(
                alarm = state.stationAlarm,
                alertMessage = state.alarmAlertMessage,
                strings = strings,
                localizeName = state::localizeName,
                onDismiss = viewModel::dismissStationAlarm,
              )
            }

            // Live Fetch / GPS Status Banner
            AnimatedVisibility(visible = state.statusBanner != null) {
              state.statusBanner?.let { banner ->
                StatusBannerBox(
                  banner = banner,
                  showPermissionAction = !state.locationPermissionGranted &&
                    banner.type == BannerType.BAD,
                  permissionButtonLabel = strings.grantPermissionBtn,
                  onRequestPermission = viewModel::requestLocationPermissionPrompt,
                  testTag = "status_banner",
                )
              }
            }

            // Train Route Timeline
            RouteTimelineCard(
              route = state.route,
              locatedTrain = state.locatedTrain,
              stoppageDistances = state.stoppageDistances,
              insideTrain = state.insideTrain,
              userSpeedKmh = state.movementStats.speedKmh,
              running = state.running,
              selectedStationIndex = state.selectedStationIndex,
              stationAlarm = state.stationAlarm,
              strings = strings,
              localizeName = state::localizeName,
              onStationClick = viewModel::onStationNodeClicked,
              onFocusStationOnMap = { idx ->
                viewModel.focusStationOnMap(idx)
                coroutineScope.launch {
                  mainScrollState.animateScrollTo(mapSectionOffsetPx)
                }
              },
              onToggleStationAlarm = viewModel::toggleStationAlarm,
            )

            // ads3: right above "ট্রেনের লাইভ তথ্য"
            AdBannerSlot(
              slotKey = AdSlot.ADS3.key,
              ads = state.allAds,
              userLat = adRefLat,
              userLon = adRefLon,
              imageLoader = viewModel.adImageLoader,
              strings = strings,
              onAdClick = { selectedAdForDialog = it },
            )

            // Train Live Data
            TrainLiveCard(
              trainNo = state.trainNoInput,
              trainName = state.trainName,
              locatedTrain = state.locatedTrain,
              stoppageDistances = state.stoppageDistances,
              destinationRaw = state.resolveDestinationRaw(),
              rawSource = state.rawSource,
              insideTrain = state.insideTrain,
              movementStats = state.movementStats,
              strings = strings,
              localizeName = state::localizeName,
            )

            // ads4: right above "ম্যাপ"
            AdBannerSlot(
              slotKey = AdSlot.ADS4.key,
              ads = state.allAds,
              userLat = adRefLat,
              userLon = adRefLon,
              imageLoader = viewModel.adImageLoader,
              strings = strings,
              onAdClick = { selectedAdForDialog = it },
            )

            // Live Map Section (OpenStreetMap)
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                  mapSectionOffsetPx = coords.positionInParent().y.roundToInt()
                },
            ) {
              OsmMapSection(
                tileLoader = viewModel.tileLoader,
                trainNo = state.trainNoInput,
                trainName = state.trainName,
                route = state.route,
                locatedTrain = state.locatedTrain,
                latestUserGps = state.latestUserGps,
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
                localizeName = state::localizeName,
                showSizePresets = true,
                fillAvailableHeight = false,
                onSizePresetSelected = viewModel::setMapSizePreset,
                onToggleFullscreen = viewModel::setMapFullscreen,
                onRecenter = viewModel::triggerRecenterMap,
              )
            }

            // WhatsApp Advertising Contact Button directly below Map
            Button(
              onClick = {
                try {
                  val url =
                    "https://wa.me/919800074626?text=${Uri.encode(strings.advertiseWhatsAppMsg)}"
                  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                  context.startActivity(intent)
                } catch (_: Throwable) {
                }
              },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = VerifiedEmerald,
                contentColor = Color.White,
              ),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("advertise_whatsapp_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = strings.advertiseWhatsAppBtn,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
              )
            }

            // ads5: right below "ম্যাপ"
            AdBannerSlot(
              slotKey = AdSlot.ADS5.key,
              ads = state.allAds,
              userLat = adRefLat,
              userLon = adRefLon,
              imageLoader = viewModel.adImageLoader,
              strings = strings,
              onAdClick = { selectedAdForDialog = it },
            )

            Spacer(modifier = Modifier.height(10.dp))
          }
        }
      }
    }
  }

  if (languageDialogOpen) {
    LanguagePickerDialog(
      currentCode = state.languageCode,
      searchHint = strings.searchLanguageHint,
      title = strings.languageLabel,
      closeDesc = strings.closeLabel,
      clearDesc = strings.clearLabel,
      onSelect = { code ->
        languageDialogOpen = false
        viewModel.setLanguage(code)
      },
      onDismiss = { languageDialogOpen = false },
    )
  }

  selectedAdForDialog?.let { ad ->
    AdDetailsDialog(
      ad = ad,
      imageLoader = viewModel.adImageLoader,
      strings = strings,
      onDismiss = { selectedAdForDialog = null },
    )
  }
}

@Composable
private fun TrackerHeader(
  languageCode: String,
  isTranslating: Boolean,
  strings: LocalizedStrings,
  onOpenLanguagePicker: () -> Unit,
) {
  val isDark = isSystemInDarkTheme()
  val currentLang = remember(languageCode) { GoogleLanguages.findByCode(languageCode) }

  Surface(
    color = if (isDark) CobaltDark else Color.White,
    shadowElevation = 2.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = strings.languageLabel,
            tint = if (isDark) Color(0xFF93C5FD) else CobaltPrimary,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = "${strings.languageLabel}:",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) Color.White else Color(0xFF1E293B),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
          onClick = onOpenLanguagePicker,
          shape = RoundedCornerShape(10.dp),
          color = if (isDark) Color(0xFF153452) else Color.White,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("language_selector_button"),
        ) {
          Row(
            modifier = Modifier
              .border(
                width = 1.5.dp,
                color = if (isDark) Color(0xFF60A5FA) else CobaltPrimary,
                shape = RoundedCornerShape(10.dp),
              )
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = if (currentLang.nativeName.equals(currentLang.englishName, ignoreCase = true)) {
                currentLang.nativeName
              } else {
                "${currentLang.nativeName} (${currentLang.englishName.uppercase(Locale.US)})"
              },
              style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
              color = if (isDark) Color.White else Color(0xFF0F172A),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (isTranslating) {
              CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = CobaltPrimary,
              )
            } else {
              Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = strings.languageLabel,
                tint = if (isDark) Color(0xFF93C5FD) else CobaltPrimary,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    }
  }
}

@Composable
private fun BrandLogoBannerCard(
  onOpenWebsite: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("brand_logo_banner"),
    shape = RoundedCornerShape(18.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFF005AE8), Color(0xFF0045C4)),
          ),
        )
        .padding(horizontal = 16.dp, vertical = 14.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = Color(0xFF0057E7),
          shadowElevation = 6.dp,
        ) {
          Image(
            painter = painterResource(id = R.drawable.brand_logo),
            contentDescription = "AI Online - Digital Support India",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
              .fillMaxWidth(0.76f)
              .clip(RoundedCornerShape(12.dp)),
          )
        }
        Text(
          text = "https://aionline.in.net",
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          textDecoration = TextDecoration.None,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenWebsite)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("brand_website_link"),
        )
      }
    }
  }
}

@Composable
private fun LanguagePickerDialog(
  currentCode: String,
  searchHint: String,
  title: String,
  closeDesc: String,
  clearDesc: String,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val filteredLanguages = remember(query) {
    val q = query.trim().lowercase(Locale.US)
    if (q.isEmpty()) {
      GoogleLanguages.ALL
    } else {
      GoogleLanguages.ALL.filter {
        it.nativeName.lowercase(Locale.US).contains(q) ||
          it.englishName.lowercase(Locale.US).contains(q) ||
          it.code.lowercase(Locale.US).contains(q)
      }
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 520.dp),
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Language,
              contentDescription = null,
              tint = CobaltPrimary,
            )
            Text(
              text = "$title (${GoogleLanguages.ALL.size}+)",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = closeDesc)
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text(searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.Filled.Clear, contentDescription = clearDesc)
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("language_search_input"),
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(
            items = filteredLanguages,
            key = { it.code },
          ) { option ->
            val selected = option.code.equals(currentCode, ignoreCase = true)
            Surface(
              onClick = { onSelect(option.code) },
              shape = RoundedCornerShape(10.dp),
              color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
              } else {
                Color.Transparent
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("lang_option_${option.code}"),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = option.nativeName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                      fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = if (selected) CobaltPrimary else MaterialTheme.colorScheme.onSurface,
                  )
                  if (!option.nativeName.equals(option.englishName, ignoreCase = true)) {
                    Text(
                      text = option.englishName,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                if (selected) {
                  Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = CobaltPrimary,
                    modifier = Modifier.size(18.dp),
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

@Composable
private fun SearchAndControlPanel(
  appTitle: String,
  fromInput: String,
  toInput: String,
  fromResolvedStation: StationDbEntry?,
  toResolvedStation: StationDbEntry?,
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
  localizeName: (String) -> String,
  onFromChanged: (String) -> Unit,
  onToChanged: (String) -> Unit,
  onStationSelected: (Boolean, StationDbEntry) -> Unit,
  onSwapStations: () -> Unit,
  onSelectBetweenTrain: (String, String) -> Unit,
  onTrainNoChanged: (String) -> Unit,
  onToggleFavorite: () -> Unit,
  onQuickTrainClick: (SavedTrainEntry) -> Unit,
  onRecentRouteClick: (SavedRoutePair) -> Unit,
  onStartTracking: () -> Unit,
  onStopTracking: () -> Unit,
  onOpenNtes: () -> Unit,
  adSlot2Content: @Composable () -> Unit,
) {
  val isDark = isSystemInDarkTheme()
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
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 2.dp)
          .testTag("app_title_header"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.DirectionsRailway,
          contentDescription = null,
          tint = CobaltPrimary,
          modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = appTitle,
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
          color = if (isDark) Color.White else HeaderIndigo,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (isWideLayout) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Top,
        ) {
          StationInputWithSuggestions(
            label = strings.fromStationLabel,
            placeholder = strings.stationPlaceholder,
            clearLabel = strings.clearLabel,
            value = fromInput,
            resolvedStation = fromResolvedStation,
            suggestions = fromSuggestions,
            localizeName = localizeName,
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
            clearLabel = strings.clearLabel,
            value = toInput,
            resolvedStation = toResolvedStation,
            suggestions = toSuggestions,
            localizeName = localizeName,
            onValueChange = onToChanged,
            onSuggestionClick = { onStationSelected(false, it) },
            testTag = "to_station_input",
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          StationInputWithSuggestions(
            label = strings.fromStationLabel,
            placeholder = strings.stationPlaceholder,
            clearLabel = strings.clearLabel,
            value = fromInput,
            resolvedStation = fromResolvedStation,
            suggestions = fromSuggestions,
            localizeName = localizeName,
            onValueChange = onFromChanged,
            onSuggestionClick = { onStationSelected(true, it) },
            testTag = "from_station_input",
            showResolvedChip = false,
          )

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            if (fromResolvedStation != null && fromInput.isNotBlank()) {
              Surface(
                onClick = { onStationSelected(true, fromResolvedStation) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f, fill = false),
              ) {
                Text(
                  text = "🚉 ${localizeName(fromResolvedStation.name)} (${fromResolvedStation.code})",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = CobaltPrimary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
              }
            } else {
              Spacer(modifier = Modifier.width(1.dp))
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
              onClick = onSwapStations,
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primaryContainer,
              modifier = Modifier
                .size(34.dp)
                .testTag("swap_stations_button"),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Filled.SwapVert,
                  contentDescription = strings.swapStationsDesc,
                  tint = CobaltPrimary,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }

          StationInputWithSuggestions(
            label = strings.toStationLabel,
            placeholder = strings.stationPlaceholder,
            clearLabel = strings.clearLabel,
            value = toInput,
            resolvedStation = toResolvedStation,
            suggestions = toSuggestions,
            localizeName = localizeName,
            onValueChange = onToChanged,
            onSuggestionClick = { onStationSelected(false, it) },
            testTag = "to_station_input",
          )
        }
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

      // Between Stations Result List (Rendered directly without nested verticalScroll to avoid scroll/click crashes)
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
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          betweenTrains.forEach { item ->
            val isSelectedTrain = trainNoInput.isNotBlank() &&
              item.trainNumber.contains(trainNoInput)
            val safeDist = item.distanceKm.takeIf { it.isFinite() } ?: 0.0
            Surface(
              onClick = { onSelectBetweenTrain(item.trainNumber, item.trainName) },
              shape = RoundedCornerShape(10.dp),
              color = if (isSelectedTrain) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant
              },
              modifier = Modifier
                .fillMaxWidth()
                .border(
                  width = if (isSelectedTrain) 1.5.dp else 1.dp,
                  color = if (isSelectedTrain) CobaltPrimary else MaterialTheme.colorScheme.outline,
                  shape = RoundedCornerShape(10.dp),
                )
                .testTag("between_train_item_${item.trainNumber}"),
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text(
                  text = "🚆 ${item.trainNumber} — ${localizeName(item.trainName)}",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "${item.departure} → ${item.arrival} • ${String.format(Locale.US, "%.0f", safeDist)} ${strings.kmUnit}",
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

      adSlot2Content()

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
            if (trainNoInput.length >= 4) {
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
                Icon(Icons.Filled.Clear, contentDescription = strings.clearLabel)
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
          onSearch = { if (trainNoInput.length >= 4) onStartTracking() },
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
          localizeName = localizeName,
          onChipClick = onQuickTrainClick,
        )
      }
      if (recentTrains.isNotEmpty()) {
        QuickTrainChipsRow(
          title = strings.recentSectionTitle,
          entries = recentTrains,
          isFavorite = false,
          localizeName = localizeName,
          onChipClick = onQuickTrainClick,
        )
      }

      // Action Buttons Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (trainNoInput.length >= 4 && !running) {
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
  localizeName: (String) -> String,
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
                "${entry.trainNumber} • ${localizeName(entry.trainName)}"
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
  localizeName: (String) -> String,
  onDismiss: () -> Unit,
) {
  val isTriggered = alertMessage != null
  val bgColor = if (isTriggered) StopCrimsonContainer else Color(0xFFFFF8E1)
  val fgColor = if (isTriggered) StopCrimson else Color(0xFFB26500)
  val text = alertMessage ?: alarm?.let { strings.alarmBannerTitle(localizeName(it.stationName)) } ?: return

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
  clearLabel: String,
  value: String,
  resolvedStation: StationDbEntry?,
  suggestions: List<StationDbEntry>,
  localizeName: (String) -> String,
  onValueChange: (String) -> Unit,
  onSuggestionClick: (StationDbEntry) -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
  showResolvedChip: Boolean = true,
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
            Icon(Icons.Filled.Clear, contentDescription = clearLabel)
          }
        }
      },
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag(testTag),
    )

    if (showResolvedChip && resolvedStation != null && value.isNotBlank()) {
      Surface(
        onClick = { onSuggestionClick(resolvedStation) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 4.dp),
      ) {
        Text(
          text = "🚉 ${localizeName(resolvedStation.name)} (${resolvedStation.code})",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = CobaltPrimary,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    AnimatedVisibility(visible = suggestions.isNotEmpty()) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
      ) {
        Column(
          modifier = Modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outline,
            RoundedCornerShape(10.dp),
          ),
        ) {
          val capped = suggestions.take(6)
          capped.forEachIndexed { idx, entry ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSuggestionClick(entry) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = localizeName(entry.name),
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
            if (idx < capped.lastIndex) {
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
