package de.jrpie.android.launcher.weather

/**
 * Everything the weather widget needs. Missing values are represented by null so
 * that a partially successful request can still be displayed.
 */
data class WeatherSnapshot(
    val currentTemp: Int?,
    val highTemp: Int?,
    val lowTemp: Int?,
    val location: String?,
    val condition: WeatherCondition?,
    val updatedAt: Long,
    /** The location query this snapshot was requested for (blank = automatic). */
    val queryLocation: String = ""
)
