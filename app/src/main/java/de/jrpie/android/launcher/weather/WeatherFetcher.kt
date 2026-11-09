package de.jrpie.android.launcher.weather

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.roundToInt

/** Client for Open-Meteo's geocoding and forecast APIs. */
object WeatherFetcher {

    private const val TAG = "WeatherFetcher"

    private const val GEOCODING_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"

    private const val TIMEOUT_MS = 10_000

    private val ACCENT_PATTERN = Regex("\\p{M}")

    /** Returns null on failure; a successful response is cached before returning. */
    fun fetch(context: Context, location: String): WeatherSnapshot? {
        val query = location.trim()
        if (query.isEmpty()) return null

        return try {
            val cached = WeatherGeocache.load(context, query)
            val resolved = cached ?: geocodeLocation(query)?.also {
                // Cache only fresh lookups, so hits keep their timestamp and expire.
                WeatherGeocache.save(context, query, it)
            }

            if (resolved == null) {
                Log.w(TAG, "Could not resolve location: $query")
                null
            } else {
                fetchByCoordinates(context, resolved, query)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch weather", e)
            null
        }
    }

    /** Caller is responsible for disconnecting the returned connection. */
    private fun openConnection(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "uLauncher-weather/1.0")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun geocodeLocation(query: String): WeatherLocation? {
        val encodedQuery = Uri.encode(query)
        val urlString = "$GEOCODING_ENDPOINT?name=$encodedQuery&count=1&language=en&format=json"

        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(URL(urlString))

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Geocoding request failed with HTTP ${connection.responseCode}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return null
            val first = results.optJSONObject(0) ?: return null

            if (!first.has("latitude") || !first.has("longitude")) {
                Log.w(TAG, "Geocoding result is missing coordinates")
                return null
            }

            val lat = first.optDouble("latitude", Double.NaN)
            val lon = first.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0
            ) {
                Log.w(TAG, "Geocoding returned invalid coordinates")
                return null
            }

            // City and country are separate fields; show only the city.
            val displayName = first.optString("name").trim().takeIf { it.isNotBlank() }

            WeatherLocation(lat, lon, displayName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not geocode location: $query", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchByCoordinates(
        context: Context,
        location: WeatherLocation,
        queryLocation: String
    ): WeatherSnapshot? {
        val urlString = buildForecastUrl(location.latitude, location.longitude)

        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(URL(urlString))

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Forecast request failed with HTTP ${connection.responseCode}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val snapshot = parse(body, location, queryLocation)
            if (snapshot != null) {
                WeatherCache.save(context, snapshot)
            }
            snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch forecast", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildForecastUrl(lat: Double, lon: Double): String {
        return "$FORECAST_ENDPOINT?" +
            "latitude=$lat&longitude=$lon&" +
            "current=temperature_2m,weather_code&" +
            "daily=temperature_2m_max,temperature_2m_min&" +
            "timezone=auto"
    }

    /** Removes diacritics, e.g. "Hosūr" -> "Hosur". */
    private fun deaccent(text: String?): String? {
        if (text == null) return null
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(ACCENT_PATTERN, "")
    }

    private fun parse(
        json: String,
        location: WeatherLocation,
        queryLocation: String
    ): WeatherSnapshot? {
        return try {
            val root = JSONObject(json)
            val current = root.optJSONObject("current")
            val daily = root.optJSONObject("daily")

            val currentTemp = current?.optDouble("temperature_2m", Double.NaN)
                ?.takeIf { it.isFinite() }?.roundToInt()
            val weatherCode = current?.optInt("weather_code", -1)

            val highTemp = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN)
                ?.takeIf { it.isFinite() }?.roundToInt()
            val lowTemp = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN)
                ?.takeIf { it.isFinite() }?.roundToInt()

            if (currentTemp == null && highTemp == null && lowTemp == null) {
                Log.w(TAG, "Forecast response did not contain any temperature")
                return null
            }

            WeatherSnapshot(
                currentTemp = currentTemp,
                highTemp = highTemp,
                lowTemp = lowTemp,
                location = deaccent(location.displayName ?: queryLocation),
                condition = WeatherCondition.fromWmoCode(weatherCode),
                updatedAt = System.currentTimeMillis(),
                queryLocation = queryLocation
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse weather response", e)
            null
        }
    }
}
