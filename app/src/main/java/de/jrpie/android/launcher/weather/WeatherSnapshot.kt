package de.jrpie.android.launcher.weather

/** Data displayed by the weather widget; null fields were missing from the response. */
data class WeatherSnapshot(
    val currentTemp: Int?,
    val highTemp: Int?,
    val lowTemp: Int?,
    val location: String?,
    val condition: WeatherCondition?,
    val updatedAt: Long,
    val queryLocation: String = ""
)
