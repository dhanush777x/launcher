package de.jrpie.android.launcher.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/** Persistent cache of geocoding results, expiring after [MAX_AGE_DAYS]. */
object WeatherGeocache {

    private const val TAG = "WeatherGeocache"
    private const val FILE = "weather_geocache"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_TIMESTAMP = "timestamp"

    private const val MAX_AGE_DAYS = 7L
    private const val MAX_ENTRIES = 50

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context, query: String): WeatherLocation? {
        return try {
            val preferences = preferences(context)

            if (preferences.getLong(KEY_SCHEMA, -1L) != WEATHER_SCHEMA_VERSION) {
                // Discard data from an older schema; stamp the current one so this runs once.
                preferences.edit {
                    clear()
                    putLong(KEY_SCHEMA, WEATHER_SCHEMA_VERSION)
                }
                return null
            }

            val key = query.trim().lowercase()
            val timestampKey = KEY_TIMESTAMP + key
            if (!preferences.contains(timestampKey)) return null

            val timestamp = preferences.getLong(timestampKey, 0L)
            if (isExpired(timestamp)) {
                removeEntry(context, key)
                return null
            }

            val latitude = preferences.getFloat(KEY_LATITUDE + key, 0f)
            val longitude = preferences.getFloat(KEY_LONGITUDE + key, 0f)
            val displayName = preferences.getString(KEY_DISPLAY_NAME + key, null)
            WeatherLocation(latitude.toDouble(), longitude.toDouble(), displayName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read geocache", e)
            null
        }
    }

    fun save(context: Context, query: String, location: WeatherLocation) {
        try {
            val key = query.trim().lowercase()
            val now = System.currentTimeMillis()

            preferences(context).edit {
                putLong(KEY_SCHEMA, WEATHER_SCHEMA_VERSION)
                putLong(KEY_TIMESTAMP + key, now)
                putFloat(KEY_LATITUDE + key, location.latitude.toFloat())
                putFloat(KEY_LONGITUDE + key, location.longitude.toFloat())
                putString(KEY_DISPLAY_NAME + key, location.displayName)
            }

            evictStaleEntries(context)
            enforceMaxEntries(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write geocache", e)
        }
    }

    private fun isExpired(timestamp: Long): Boolean {
        val ageDays = (System.currentTimeMillis() - timestamp) / (24 * 60 * 60 * 1000L)
        return ageDays >= MAX_AGE_DAYS
    }

    private fun evictStaleEntries(context: Context) {
        val preferences = preferences(context)
        val allKeys = preferences.all.keys
        val timestampKeys = allKeys.filter { it.startsWith(KEY_TIMESTAMP) }

        timestampKeys.forEach { tsKey ->
            val key = tsKey.removePrefix(KEY_TIMESTAMP)
            val timestamp = preferences.getLong(tsKey, 0L)
            if (isExpired(timestamp)) {
                removeEntry(context, key)
            }
        }
    }

    private fun enforceMaxEntries(context: Context) {
        val preferences = preferences(context)
        val allKeys = preferences.all.keys
        val timestampKeys = allKeys.filter { it.startsWith(KEY_TIMESTAMP) }

        if (timestampKeys.size <= MAX_ENTRIES) return

        val sorted = timestampKeys.sortedBy { preferences.getLong(it, 0L) }
        val toEvict = sorted.take(sorted.size - MAX_ENTRIES)

        toEvict.forEach { tsKey ->
            val key = tsKey.removePrefix(KEY_TIMESTAMP)
            removeEntry(context, key)
        }
    }

    private fun removeEntry(context: Context, key: String) {
        preferences(context).edit {
            remove(KEY_TIMESTAMP + key)
            remove(KEY_LATITUDE + key)
            remove(KEY_LONGITUDE + key)
            remove(KEY_DISPLAY_NAME + key)
        }
    }
}

data class WeatherLocation(
    val latitude: Double,
    val longitude: Double,
    val displayName: String?
)
