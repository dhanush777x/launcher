package de.jrpie.android.launcher.weather

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.jrpie.android.launcher.R

/** Weather categories shown by the widget, mapped from Open-Meteo's WMO codes. */
enum class WeatherCondition(
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int
) {
    SUNNY(R.drawable.ic_weather_sunny_24, R.string.widget_weather_condition_sunny),
    MAINLY_CLEAR(R.drawable.ic_weather_sunny_24, R.string.widget_weather_condition_mainly_clear),
    PARTLY_CLOUDY(R.drawable.ic_weather_partly_cloudy_24, R.string.widget_weather_condition_partly_cloudy),
    OVERCAST(R.drawable.ic_weather_cloudy_24, R.string.widget_weather_condition_overcast),
    FOG(R.drawable.ic_weather_fog_24, R.string.widget_weather_condition_fog),
    DRIZZLE(R.drawable.ic_weather_rain_24, R.string.widget_weather_condition_drizzle),
    FREEZING_DRIZZLE(R.drawable.ic_weather_sleet_24, R.string.widget_weather_condition_freezing_drizzle),
    RAIN(R.drawable.ic_weather_rain_24, R.string.widget_weather_condition_rain),
    HEAVY_RAIN(R.drawable.ic_weather_heavy_rain_24, R.string.widget_weather_condition_heavy_rain),
    FREEZING_RAIN(R.drawable.ic_weather_sleet_24, R.string.widget_weather_condition_freezing_rain),
    SNOW(R.drawable.ic_weather_snow_24, R.string.widget_weather_condition_snow),
    HEAVY_SNOW(R.drawable.ic_weather_snow_24, R.string.widget_weather_condition_heavy_snow),
    SNOW_GRAINS(R.drawable.ic_weather_snow_24, R.string.widget_weather_condition_snow_grains),
    SHOWERS(R.drawable.ic_weather_rain_24, R.string.widget_weather_condition_showers),
    SNOW_SHOWERS(R.drawable.ic_weather_snow_24, R.string.widget_weather_condition_snow_showers),
    THUNDERSTORM(R.drawable.ic_weather_thunderstorm_24, R.string.widget_weather_condition_thunderstorm),
    THUNDERSTORM_HAIL(R.drawable.ic_weather_thunderstorm_24, R.string.widget_weather_condition_thunderstorm_hail),
    SLEET(R.drawable.ic_weather_sleet_24, R.string.widget_weather_condition_sleet);

    companion object {
        fun fromWmoCode(code: Int?): WeatherCondition? = when (code) {
            0 -> SUNNY
            1 -> MAINLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61, 63 -> RAIN
            65 -> HEAVY_RAIN
            66, 67 -> FREEZING_RAIN
            71, 73 -> SNOW
            75 -> HEAVY_SNOW
            77 -> SNOW_GRAINS
            80, 81, 82 -> SHOWERS
            85, 86 -> SNOW_SHOWERS
            95 -> THUNDERSTORM
            96, 99 -> THUNDERSTORM_HAIL
            else -> null
        }

        fun fromName(name: String?): WeatherCondition? =
            entries.firstOrNull { it.name == name }
    }
}
