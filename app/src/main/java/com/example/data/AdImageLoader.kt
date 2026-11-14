package com.example.data

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AdImageStatus {
  data object Loading : AdImageStatus
  data class Ready(val bitmap: ImageBitmap) : AdImageStatus
  data object Failed : AdImageStatus
}

class AdImageLoader(cacheDir: File? = null) {
  companion object {
    private const val GITHUB_RAW_MAIN =
      "https://raw.githubusercontent.com/evbasoft-jpg/Train/main/Ads/"
    private const val GITHUB_JSDELIVR =
      "https://cdn.jsdelivr.net/gh/evbasoft-jpg/Train@main/Ads/"
    private const val GITHUB_RAW_MASTER =
      "https://raw.githubusercontent.com/evbasoft-jpg/Train/master/Ads/"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val client = OkHttpClient.Builder()
    .apply {
      if (cacheDir != null) {
        val adCacheDir = File(cacheDir, "ad_images")
        adCacheDir.mkdirs()
        cache(Cache(adCacheDir, 20L * 1024L * 1024L))
      }
    }
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  private val bitmapCache = object : LruCache<String, ImageBitmap>(40) {}
  private val failedSet = Collections.synchronizedSet(mutableSetOf<String>())
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  private val _imageRevision = MutableStateFlow(0)
  val imageRevision: StateFlow<Int> = _imageRevision.asStateFlow()

  fun getStatus(imageName: String): AdImageStatus {
    val key = imageName.trim()
    if (key.isEmpty()) return AdImageStatus.Failed
    bitmapCache.get(key)?.let { return AdImageStatus.Ready(it) }
    if (failedSet.contains(key)) return AdImageStatus.Failed

    if (inFlight.add(key)) {
      scope.launch {
        try {
          val candidates = buildCandidateUrls(key)
          var loaded: ImageBitmap? = null
          for (url in candidates) {
            loaded = fetchBitmap(url)
            if (loaded != null) break
          }
          if (loaded != null) {
            bitmapCache.put(key, loaded)
          } else {
            failedSet.add(key)
          }
        } catch (_: Throwable) {
          failedSet.add(key)
        } finally {
          inFlight.remove(key)
          _imageRevision.value = _imageRevision.value + 1
        }
      }
    }
    return AdImageStatus.Loading
  }

  fun clearFailures() {
    failedSet.clear()
    _imageRevision.value = _imageRevision.value + 1
  }

  private fun buildCandidateUrls(rawName: String): List<String> {
    if (rawName.startsWith("http://", ignoreCase = true) ||
      rawName.startsWith("https://", ignoreCase = true)
    ) {
      return listOf(rawName)
    }
    val clean = rawName.trim().trimStart('/').removePrefix("Ads/").removePrefix("ads/")
    val urls = LinkedHashSet<String>()
    val namesToTry = if (clean.contains('.')) {
      listOf(clean)
    } else {
      listOf("$clean.png", "$clean.jpg", "$clean.jpeg", "$clean.webp")
    }
    for (name in namesToTry) {
      val encoded = name.split('/').joinToString("/") { Uri.encode(it) }
      urls.add("$GITHUB_RAW_MAIN$encoded")
      urls.add("$GITHUB_JSDELIVR$encoded")
      urls.add("$GITHUB_RAW_MASTER$encoded")
    }
    return urls.toList()
  }

  private fun fetchBitmap(url: String): ImageBitmap? {
    return try {
      val req = Request.Builder()
        .url(url)
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
