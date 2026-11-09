package de.jrpie.android.launcher.weather

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal wttr.in client. Uses the JSON endpoint and only reads the values the
 * widget displays. There is deliberately no third-party HTTP dependency.
 */
object WeatherFetcher {

    private const val TAG = "WeatherFetcher"
    private const val ENDPOINT = "https://wttr.in"
    private const val TIMEOUT_MS = 10_000

    /**
     * Fetches the current weather for [location]. A blank location lets wttr.in
     * pick a place based on the network.
     *
     * Returns null on any failure. A successful response is written to
     * [WeatherCache] before it is returned.
     */
    fun fetch(context: Context, location: String): WeatherSnapshot? {
        val query = location.trim()
        val url = if (query.isEmpty()) {
            "$ENDPOINT/?format=j1"
        } else {
            "$ENDPOINT/${Uri.encode(query)}?format=j1"
        }

        var connection: HttpURLConnection? = null
        return try {
            val http = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "uLauncher-weather/1.0")
                setRequestProperty("Accept", "application/json")
            }
            connection = http

            if (http.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Weather request failed with HTTP ${http.responseCode}")
                return null
            }

            val body = http.inputStream.bufferedReader().use { it.readText() }
            val snapshot = parse(body, query)
            if (snapshot != null) {
                WeatherCache.save(context, snapshot)
            }
            snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch weather", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(json: String, queryLocation: String): WeatherSnapshot? {
        return try {
            val root = JSONObject(json)
            val current = root.optJSONArray("current_condition")?.optJSONObject(0)
            val today = root.optJSONArray("weather")?.optJSONObject(0)

            val currentTemp = current?.optString("temp_C")?.toIntOrNull()
            val highTemp = today?.optString("maxtempC")?.toIntOrNull()
            val lowTemp = today?.optString("mintempC")?.toIntOrNull()

            if (currentTemp == null && highTemp == null && lowTemp == null) {
                Log.w(TAG, "Weather response did not contain any temperature")
                return null
            }

            val areaName = root.optJSONArray("nearest_area")
                ?.optJSONObject(0)
                ?.optJSONArray("areaName")
                ?.optJSONObject(0)
                ?.optString("value")
                ?.takeIf { it.isNotBlank() }

            WeatherSnapshot(
                currentTemp = currentTemp,
                highTemp = highTemp,
                lowTemp = lowTemp,
                location = areaName ?: queryLocation.takeIf { it.isNotBlank() },
                condition = WeatherCondition.fromCode(
                    current?.optString("weatherCode")?.toIntOrNull()
                ),
                updatedAt = System.currentTimeMillis(),
                queryLocation = queryLocation
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse weather response", e)
            null
        }
    }
}
