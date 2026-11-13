package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.SavedRoutePair
import com.example.model.SavedTrainEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.trackerDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "smart_train_tracker_prefs",
)

data class PersistedPrefs(
  val language: AppLanguage = AppLanguage.BN,
  val favoriteTrains: List<SavedTrainEntry> = emptyList(),
  val recentTrains: List<SavedTrainEntry> = emptyList(),
  val recentRoutes: List<SavedRoutePair> = emptyList(),
  val backgroundTrackingEnabled: Boolean = false,
)

class TrackerPreferences(private val context: Context) {
  companion object {
    private val KEY_LANGUAGE = stringPreferencesKey("language_code")
    private val KEY_FAVORITES = stringPreferencesKey("favorite_trains_json")
    private val KEY_RECENTS = stringPreferencesKey("recent_trains_json")
    private val KEY_RECENT_ROUTES = stringPreferencesKey("recent_routes_json")
    private val KEY_BG_TRACKING = booleanPreferencesKey("background_tracking_enabled")
  }

  val prefsFlow: Flow<PersistedPrefs> = context.trackerDataStore.data.map { prefs ->
    val langCode = prefs[KEY_LANGUAGE] ?: AppLanguage.BN.code
    val lang = AppLanguage.entries.firstOrNull { it.code == langCode } ?: AppLanguage.BN
    PersistedPrefs(
      language = lang,
      favoriteTrains = decodeTrainList(prefs[KEY_FAVORITES]),
      recentTrains = decodeTrainList(prefs[KEY_RECENTS]),
      recentRoutes = decodeRouteList(prefs[KEY_RECENT_ROUTES]),
      backgroundTrackingEnabled = prefs[KEY_BG_TRACKING] ?: false,
    )
  }

  suspend fun saveLanguage(language: AppLanguage) {
    context.trackerDataStore.edit { it[KEY_LANGUAGE] = language.code }
  }

  suspend fun saveBackgroundTracking(enabled: Boolean) {
    context.trackerDataStore.edit { it[KEY_BG_TRACKING] = enabled }
  }

  suspend fun toggleFavoriteTrain(entry: SavedTrainEntry) {
    context.trackerDataStore.edit { prefs ->
      val current = decodeTrainList(prefs[KEY_FAVORITES]).toMutableList()
      val existingIdx = current.indexOfFirst { it.trainNumber == entry.trainNumber }
      if (existingIdx >= 0) {
        current.removeAt(existingIdx)
      } else {
        current.add(0, entry)
      }
      prefs[KEY_FAVORITES] = encodeTrainList(current.take(10))
    }
  }

  suspend fun addRecentTrain(entry: SavedTrainEntry) {
    if (entry.trainNumber.length != 5) return
    context.trackerDataStore.edit { prefs ->
      val current = decodeTrainList(prefs[KEY_RECENTS]).toMutableList()
      val existingName = current.firstOrNull { it.trainNumber == entry.trainNumber }?.trainName
      current.removeAll { it.trainNumber == entry.trainNumber }
      val resolvedName = entry.trainName.ifBlank { existingName ?: "" }
      current.add(0, SavedTrainEntry(entry.trainNumber, resolvedName))
      prefs[KEY_RECENTS] = encodeTrainList(current.take(6))

      // Also update favorite name if already favorited
      val favs = decodeTrainList(prefs[KEY_FAVORITES]).toMutableList()
      val favIdx = favs.indexOfFirst { it.trainNumber == entry.trainNumber }
      if (favIdx >= 0 && resolvedName.isNotBlank() && favs[favIdx].trainName.isBlank()) {
        favs[favIdx] = favs[favIdx].copy(trainName = resolvedName)
        prefs[KEY_FAVORITES] = encodeTrainList(favs)
      }
    }
  }

  suspend fun addRecentRoute(pair: SavedRoutePair) {
    if (pair.fromInput.isBlank() || pair.toInput.isBlank()) return
    context.trackerDataStore.edit { prefs ->
      val current = decodeRouteList(prefs[KEY_RECENT_ROUTES]).toMutableList()
      current.removeAll {
        it.fromInput.equals(pair.fromInput, ignoreCase = true) &&
          it.toInput.equals(pair.toInput, ignoreCase = true)
      }
      current.add(0, pair)
      prefs[KEY_RECENT_ROUTES] = encodeRouteList(current.take(5))
    }
  }

  private fun encodeTrainList(list: List<SavedTrainEntry>): String {
    val arr = JSONArray()
    list.forEach {
      arr.put(
        JSONObject().apply {
          put("n", it.trainNumber)
          put("name", it.trainName)
        },
      )
    }
    return arr.toString()
  }

  private fun decodeTrainList(raw: String?): List<SavedTrainEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val num = obj.optString("n", "").trim()
          val name = obj.optString("name", "").trim()
          if (num.isNotEmpty()) add(SavedTrainEntry(num, name))
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun encodeRouteList(list: List<SavedRoutePair>): String {
    val arr = JSONArray()
    list.forEach {
      arr.put(
        JSONObject().apply {
          put("f", it.fromInput)
          put("t", it.toInput)
        },
      )
    }
    return arr.toString()
  }

  private fun decodeRouteList(raw: String?): List<SavedRoutePair> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val f = obj.optString("f", "").trim()
          val t = obj.optString("t", "").trim()
          if (f.isNotEmpty() && t.isNotEmpty()) add(SavedRoutePair(f, t))
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }
}
