package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AdImageLoader
import com.example.data.AdImageStatus
import com.example.data.AdRepository
import com.example.model.AdItem
import com.example.model.LocalizedStrings
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.VerifiedEmerald
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ReadyAdSlide(
  val ad: AdItem,
  val bitmap: ImageBitmap,
)

@Composable
fun AdBannerSlot(
  slotKey: String,
  ads: List<AdItem>,
  userLat: Double?,
  userLon: Double?,
  imageLoader: AdImageLoader,
  strings: LocalizedStrings,
  onAdClick: (AdItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val imageRev by imageLoader.imageRevision.collectAsStateWithLifecycle()
  var timeTick by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    while (true) {
      delay(10_000L)
      timeTick++
    }
  }

  val eligibleAds = remember(slotKey, ads, userLat, userLon, timeTick) {
    val nowDate = AdRepository.currentLocalDateYmd()
    val nowMin = AdRepository.currentLocalMinutesOfDay()
    ads.filter { it.isEligible(slotKey, nowDate, nowMin, userLat, userLon) }
  }

  val readySlides = remember(eligibleAds, imageRev) {
    eligibleAds.mapNotNull { ad ->
      when (val st = imageLoader.getStatus(ad.imageName)) {
        is AdImageStatus.Ready -> ReadyAdSlide(ad, st.bitmap)
        else -> null
      }
    }
  }

  if (readySlides.isEmpty()) return

  val pagerState = rememberPagerState(
    initialPage = 0,
    pageCount = { readySlides.size },
  )

  LaunchedEffect(readySlides.size, pagerState.currentPage, pagerState.isScrollInProgress) {
    if (readySlides.size > 1 && !pagerState.isScrollInProgress) {
      delay(6_000L)
      val nextPage = (pagerState.currentPage + 1) % readySlides.size
      pagerState.animateScrollToPage(
        page = nextPage,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
      )
    }
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .testTag("ad_slot_$slotKey"),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = tween(durationMillis = 250)),
    ) {
      HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxWidth(),
      ) { page ->
        val slide = readySlides[page]
        Image(
          bitmap = slide.bitmap,
          contentDescription = slide.ad.address.ifBlank { strings.adBadgeLabel },
          contentScale = ContentScale.FillWidth,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 185.dp)
            .clickable { onAdClick(slide.ad) },
        )
      }

      Surface(
        shape = RoundedCornerShape(bottomStart = 8.dp),
        color = Color(0xAA0F172A),
        modifier = Modifier.align(Alignment.TopEnd),
      ) {
        Text(
          text = strings.adBadgeLabel,
          color = Color.White,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
      }

      if (readySlides.size > 1) {
        val currentIdx = pagerState.currentPage.coerceIn(0, readySlides.lastIndex)
        Row(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x88000000))
            .padding(horizontal = 8.dp, vertical = 3.dp),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          readySlides.indices.forEach { idx ->
            Box(
              modifier = Modifier
                .size(if (idx == currentIdx) 7.dp else 5.dp)
                .clip(CircleShape)
                .background(if (idx == currentIdx) Color.White else Color(0x88FFFFFF))
                .clickable {
                  scope.launch {
                    pagerState.animateScrollToPage(
                      page = idx,
                      animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                    )
                  }
                },
            )
          }
        }
      }
    }
  }
}

@Composable
fun AdDetailsDialog(
  ad: AdItem,
  imageLoader: AdImageLoader,
  strings: LocalizedStrings,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val imageRev by imageLoader.imageRevision.collectAsStateWithLifecycle()
  val imgStatus = remember(ad.imageName, imageRev) { imageLoader.getStatus(ad.imageName) }
  val dialPhone = remember(ad.contactNumber) { ad.cleanPhoneForDial() }
  val waPhone = remember(ad.contactNumber) { ad.cleanPhoneForWhatsApp() }
  val websiteUrl = remember(ad.link) { ad.normalizedWebsiteUrl() }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("ad_details_dialog"),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = strings.adDetailsTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = strings.closeLabel)
          }
        }

        if (imgStatus is AdImageStatus.Ready) {
          Image(
            bitmap = imgStatus.bitmap,
            contentDescription = strings.adDetailsTitle,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 220.dp)
              .clip(RoundedCornerShape(12.dp)),
          )
        }

        if (ad.address.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = strings.adAddressLabel,
                tint = CobaltPrimary,
                modifier = Modifier.size(20.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = strings.adAddressLabel,
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = ad.address,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }

        if (dialPhone != null) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Phone,
                  contentDescription = strings.adContactLabel,
                  tint = CobaltPrimary,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text = "${strings.adContactLabel}: ${ad.contactNumber.trim()}",
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Button(
                  onClick = {
                    try {
                      val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialPhone"))
                      context.startActivity(intent)
                    } catch (_: Throwable) {
                    }
                  },
                  shape = RoundedCornerShape(10.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = CobaltPrimary),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                  modifier = Modifier
                    .weight(1f)
                    .testTag("ad_call_button"),
                ) {
                  Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text(
                    text = strings.adCallBtn,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }

                if (waPhone != null) {
                  Button(
                    onClick = {
                      try {
                        val intent = Intent(
                          Intent.ACTION_VIEW,
                          Uri.parse("https://wa.me/$waPhone"),
                        )
                        context.startActivity(intent)
                      } catch (_: Throwable) {
                      }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VerifiedEmerald),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                      .weight(1f)
                      .testTag("ad_whatsapp_button"),
                  ) {
                    Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                      text = strings.adWhatsAppBtn,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
        }

        if (websiteUrl != null) {
          Button(
            onClick = {
              try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))
                context.startActivity(intent)
              } catch (_: Throwable) {
              }
            },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0057E7)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("ad_open_link_button"),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = strings.adOpenLinkBtn,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(strings.closeLabel, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}
