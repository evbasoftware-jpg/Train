package com.example.data

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class OsmTileLoader {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  private val cache = object : LruCache<String, ImageBitmap>(120) {}
  private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())

  private val _tileRevision = MutableStateFlow(0)
  val tileRevision: StateFlow<Int> = _tileRevision.asStateFlow()

  fun getTile(z: Int, x: Int, y: Int): ImageBitmap? {
    val maxTile = 1 shl z
    if (y < 0 || y >= maxTile) return null
    val wrappedX = ((x % maxTile) + maxTile) % maxTile
    val key = "$z/$wrappedX/$y"
    cache.get(key)?.let { return it }

    if (inFlight.add(key)) {
      scope.launch {
        try {
          val url = "https://tile.openstreetmap.org/$z/$wrappedX/$y.png"
          val req = Request.Builder()
            .url(url)
            .header("User-Agent", "SmartTrainTracker-Android/1.0")
            .get()
            .build()
          client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
              val bytes = resp.body?.bytes()
              if (bytes != null) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                  cache.put(key, bmp.asImageBitmap())
                  _tileRevision.value = _tileRevision.value + 1
                }
              }
            }
          }
        } catch (_: Exception) {
          // Tile fetch failed gracefully; canvas draws grid background
        } finally {
          inFlight.remove(key)
        }
      }
    }
    return null
  }
}
