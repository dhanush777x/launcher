package de.jrpie.android.launcher.weather

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.jrpie.android.launcher.R

/**
 * The small set of weather categories shown by the widget, each backed by a
 * Material Symbols vector icon and a short label.
 */
enum class WeatherCondition(
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int
) {
    SUNNY(R.drawable.ic_weather_sunny_24, R.string.widget_weather_condition_sunny),
    PARTLY_CLOUDY(
        R.drawable.ic_weather_partly_cloudy_24,
        R.string.widget_weather_condition_partly_cloudy
    ),
    CLOUDY(R.drawable.ic_weather_cloudy_24, R.string.widget_weather_condition_cloudy),
    RAIN(R.drawable.ic_weather_rain_24, R.string.widget_weather_condition_rain),
    HEAVY_RAIN(R.drawable.ic_weather_heavy_rain_24, R.string.widget_weather_condition_heavy_rain),
    THUNDERSTORM(
        R.drawable.ic_weather_thunderstorm_24,
        R.string.widget_weather_condition_thunderstorm
    ),
    SNOW(R.drawable.ic_weather_snow_24, R.string.widget_weather_condition_snow),
    SLEET(R.drawable.ic_weather_sleet_24, R.string.widget_weather_condition_sleet),
    FOG(R.drawable.ic_weather_fog_24, R.string.widget_weather_condition_fog);

    companion object {
        /**
         * Maps the World Weather Online condition code used by wttr.in to one of
         * the supported icons. Unknown codes are returned as null so the widget
         * can omit the icon instead of showing a made-up condition.
         */
        fun fromCode(code: Int?): WeatherCondition? = when (code) {
            113 -> SUNNY
            116 -> PARTLY_CLOUDY
            119, 122 -> CLOUDY
            143, 149, 248, 260 -> FOG
            200, 386, 389, 392, 395 -> THUNDERSTORM
            179, 227, 230, 323, 326, 329, 332, 335, 338, 368, 371 -> SNOW
            182, 185, 281, 284, 311, 314, 317, 320, 350, 362, 365, 374, 377 -> SLEET
            305, 308, 359 -> HEAVY_RAIN
            176, 263, 266, 293, 296, 299, 302, 353, 356 -> RAIN

            else -> null
        }

        fun fromName(name: String?): WeatherCondition? =
            values().firstOrNull { it.name == name }
    }
}
