package com.example.data

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AdImageStatus {
  data object Loading : AdImageStatus
  data class Ready(val bitmap: ImageBitmap) : AdImageStatus
  data object Failed : AdImageStatus
}

class AdImageLoader(
  cacheDir: File? = null,
  private val assets: AssetManager? = null,
) {
  companion object {
    private const val GITHUB_RAW_MAIN =
      "https://raw.githubusercontent.com/evbasoft-jpg/Train/main/Ads/"
    private const val GITHUB_RAW_MASTER =
      "https://raw.githubusercontent.com/evbasoft-jpg/Train/master/Ads/"
    private const val GITHUB_JSDELIVR =
      "https://cdn.jsdelivr.net/gh/evbasoft-jpg/Train@main/Ads/"
    private val FALLBACK_EXTS = listOf("png", "jpg", "jpeg", "webp")
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val client = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  private val bitmapCache = object : LruCache<String, ImageBitmap>(40) {}
  private val bitmapTimestamps = ConcurrentHashMap<String, Long>()
  private val failedSet = Collections.synchronizedSet(mutableSetOf<String>())
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  @Volatile
  private var refreshEpoch = System.currentTimeMillis()

  private val _imageRevision = MutableStateFlow(0)
  val imageRevision: StateFlow<Int> = _imageRevision.asStateFlow()

  fun getStatus(imageName: String): AdImageStatus {
    val key = imageName.trim()
    if (key.isEmpty()) return AdImageStatus.Failed

    val cached = bitmapCache.get(key)
    val loadedAt = bitmapTimestamps[key] ?: 0L
    if (cached != null) {
      if (refreshEpoch > loadedAt && inFlight.add(key)) {
        // Keep displaying current image without flicker while refreshing live copy in background
        launchLoad(key, refreshEpoch)
      }
      return AdImageStatus.Ready(cached)
    }
    if (failedSet.contains(key)) return AdImageStatus.Failed

    if (inFlight.add(key)) {
      launchLoad(key, refreshEpoch)
    }
    return AdImageStatus.Loading
  }

  private fun launchLoad(key: String, epoch: Long) {
    scope.launch {
      try {
        var loaded: ImageBitmap? = null
        val urls = buildCandidateUrls(key, epoch)
        for (url in urls) {
          loaded = fetchBitmap(url)
          if (loaded != null) break
        }
        if (loaded == null) {
          loaded = loadFromBundledAssets(key)
        }
        if (loaded != null) {
          bitmapCache.put(key, loaded)
          bitmapTimestamps[key] = epoch
          failedSet.remove(key)
        } else if (bitmapCache.get(key) == null) {
          failedSet.add(key)
        }
      } catch (_: Throwable) {
        if (bitmapCache.get(key) == null) {
          failedSet.add(key)
        }
      } finally {
        inFlight.remove(key)
        _imageRevision.value = _imageRevision.value + 1
      }
    }
  }

  fun refreshImages() {
    refreshEpoch = System.currentTimeMillis()
    failedSet.clear()
    _imageRevision.value = _imageRevision.value + 1
  }

  fun clearFailures() = refreshImages()

  private fun buildCandidateFileNames(rawName: String): List<String> {
    val clean = rawName.trim().trimStart('/').removePrefix("Ads/").removePrefix("ads/")
    if (clean.isEmpty()) return emptyList()
    val dotIdx = clean.lastIndexOf('.')
    val hasExt = dotIdx > clean.lastIndexOf('/') && dotIdx > 0
    val ordered = LinkedHashSet<String>()
    if (hasExt) {
      ordered.add(clean)
      val stem = clean.substring(0, dotIdx)
      for (ext in FALLBACK_EXTS) {
        ordered.add("$stem.$ext")
      }
    } else {
      for (ext in FALLBACK_EXTS) {
        ordered.add("$clean.$ext")
      }
    }
    return ordered.toList()
  }

  private fun buildCandidateUrls(rawName: String, epoch: Long): List<String> {
    if (rawName.startsWith("http://", ignoreCase = true) ||
      rawName.startsWith("https://", ignoreCase = true)
    ) {
      val sep = if (rawName.contains('?')) '&' else '?'
      return listOf("$rawName${sep}_t=$epoch")
    }
    val urls = LinkedHashSet<String>()
    for (name in buildCandidateFileNames(rawName)) {
      val encoded = name.split('/').joinToString("/") { Uri.encode(it) }
      urls.add("$GITHUB_RAW_MAIN$encoded?_t=$epoch")
      urls.add("$GITHUB_RAW_MASTER$encoded?_t=$epoch")
      urls.add("$GITHUB_JSDELIVR$encoded")
    }
    return urls.toList()
  }

  private fun loadFromBundledAssets(rawName: String): ImageBitmap? {
    val mgr = assets ?: return null
    val names = buildCandidateFileNames(rawName)
    for (name in names) {
      try {
        mgr.open("Ads/$name").use { stream ->
          val bytes = stream.readBytes()
          val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          if (bmp != null) return bmp.asImageBitmap()
        }
      } catch (_: Exception) {
      }
    }
    // Also check case-insensitive filename inside bundled Ads assets
    val targetLowers = names.map { it.lowercase(Locale.US) }.toSet()
    try {
      val files = mgr.list("Ads") ?: emptyArray()
      for (fileName in files) {
        if (fileName.lowercase(Locale.US) in targetLowers) {
          mgr.open("Ads/$fileName").use { stream ->
            val bytes = stream.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) return bmp.asImageBitmap()
          }
        }
      }
    } catch (_: Exception) {
    }
    return null
  }

  private fun fetchBitmap(url: String): ImageBitmap? {
    return try {
      val req = Request.Builder()
        .url(url)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .header("Cache-Control", "no-cache, no-store, must-revalidate")
        .header("Pragma", "no-cache")
        .header("User-Agent", "TrainLiveLocation-Android/1.0")
        .get()
        .build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val bytes = resp.body?.bytes() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bmp.asImageBitmap()
      }
    } catch (_: Exception) {
      null
    }
  }
}
