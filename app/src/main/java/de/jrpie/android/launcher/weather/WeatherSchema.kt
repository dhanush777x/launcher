package de.jrpie.android.launcher.weather

/**
 * Version of the persisted weather data contract. Bump when cached weather data
 * changes shape or meaning; caches from other versions are discarded on read.
 * Independent of the app version.
 */
internal const val WEATHER_SCHEMA_VERSION = 1L
