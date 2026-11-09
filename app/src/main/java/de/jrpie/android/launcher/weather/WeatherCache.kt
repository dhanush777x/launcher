package de.jrpie.android.launcher.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Small persistent cache for the last successful weather request. It lives in a
 * separate preferences file so it never interferes with launcher settings.
 */
object WeatherCache {

    private const val TAG = "WeatherCache"
    private const val FILE = "weather_cache"
    private const val KEY_CURRENT_TEMP = "current_temp"
    private const val KEY_HIGH_TEMP = "high_temp"
    private const val KEY_LOW_TEMP = "low_temp"
    private const val KEY_LOCATION = "location"
    private const val KEY_CONDITION = "condition"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_QUERY_LOCATION = "query_location"
    private const val MISSING_TEMP = Int.MIN_VALUE

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): WeatherSnapshot? {
        return try {
            val preferences = preferences(context)
            if (!preferences.contains(KEY_UPDATED_AT)) return null

            val updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
            if (updatedAt <= 0L) return null

            WeatherSnapshot(
                currentTemp = preferences.getInt(KEY_CURRENT_TEMP, MISSING_TEMP)
                    .takeUnless { it == MISSING_TEMP },
                highTemp = preferences.getInt(KEY_HIGH_TEMP, MISSING_TEMP)
                    .takeUnless { it == MISSING_TEMP },
                lowTemp = preferences.getInt(KEY_LOW_TEMP, MISSING_TEMP)
                    .takeUnless { it == MISSING_TEMP },
                location = preferences.getString(KEY_LOCATION, null)?.takeIf { it.isNotBlank() },
                condition = WeatherCondition.fromName(preferences.getString(KEY_CONDITION, null)),
                updatedAt = updatedAt,
                queryLocation = preferences.getString(KEY_QUERY_LOCATION, "").orEmpty()
            )
        } catch (e: Exception) {
            // A corrupt or outdated cache must never take down the home screen.
            Log.w(TAG, "Could not read weather cache", e)
            null
        }
    }

    fun save(context: Context, snapshot: WeatherSnapshot) {
        preferences(context).edit {
            putInt(KEY_CURRENT_TEMP, snapshot.currentTemp ?: MISSING_TEMP)
            putInt(KEY_HIGH_TEMP, snapshot.highTemp ?: MISSING_TEMP)
            putInt(KEY_LOW_TEMP, snapshot.lowTemp ?: MISSING_TEMP)
            putString(KEY_LOCATION, snapshot.location)
            val condition = snapshot.condition
            if (condition != null) {
                putString(KEY_CONDITION, condition.name)
            } else {
                remove(KEY_CONDITION)
            }
            putLong(KEY_UPDATED_AT, snapshot.updatedAt)
            putString(KEY_QUERY_LOCATION, snapshot.queryLocation)
        }
    }
}
