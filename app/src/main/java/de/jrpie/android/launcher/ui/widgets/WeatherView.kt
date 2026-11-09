package de.jrpie.android.launcher.ui.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import de.jrpie.android.launcher.databinding.WidgetWeatherBinding
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.FontSlot
import de.jrpie.android.launcher.weather.WeatherCache
import de.jrpie.android.launcher.weather.WeatherCondition
import de.jrpie.android.launcher.weather.WeatherFetcher
import de.jrpie.android.launcher.weather.WeatherSnapshot
import de.jrpie.android.launcher.widgets.WidgetPanel
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Compact three-column weather widget.
 *
 * The last successful response is read from [WeatherCache] immediately, so the
 * widget is useful offline. Network refreshes happen at most every
 * [REFRESH_INTERVAL_MS] and never on the main thread. All content is static.
 */
class WeatherView(
    context: Context,
    attrs: AttributeSet? = null,
    val appWidgetId: Int,
    val panelId: Int
) : ConstraintLayout(context, attrs) {

    constructor(context: Context, attrs: AttributeSet?) : this(
        context,
        attrs,
        WidgetPanel.HOME.id,
        -1
    )

    private val binding: WidgetWeatherBinding =
        WidgetWeatherBinding.inflate(LayoutInflater.from(context), this, true)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val paint = Paint()
    private val fontMetrics = Paint.FontMetrics()

    private var snapshot: WeatherSnapshot? = null
    private var condition: WeatherCondition? = null

    private var attached = false
    private var windowVisible = true
    private var fetching = false

    private var refreshTicker: Runnable? = null

    private val preferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                key == null -> Unit
                key.startsWith(WEATHER_PREFIX) -> {
                    post {
                        applyStyle()
                        if (key == LauncherPreferences.weather().keys().location()) {
                            // Never present the previous city's weather as if it
                            // belonged to the newly selected location.
                            if (snapshot?.queryLocation != currentQuery()) {
                                snapshot = null
                                condition = null
                                render()
                            }
                            refresh(force = true)
                        }
                    }
                }

                key.startsWith("clock.font") -> post(::applyStyle)
            }
        }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopRefreshTicker()
                Intent.ACTION_SCREEN_ON -> {
                    if (attached && windowVisible) {
                        refreshIfStale()
                        scheduleRefresh()
                    }
                }
            }
        }
    }

    init {
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (attached) return
        attached = true

        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(preferencesListener)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        windowVisible = windowVisibility == VISIBLE
        applyStyle()
        loadCache()

        if (windowVisible) {
            refreshIfStale()
            scheduleRefresh()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!attached) return

        windowVisible = visibility == VISIBLE
        if (windowVisible) {
            refreshIfStale()
            scheduleRefresh()
        } else {
            stopRefreshTicker()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!attached) return
        attached = false

        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)

        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }

        stopRefreshTicker()
    }

    // ---- rendering --------------------------------------------------------

    private fun loadCache() {
        val cached = WeatherCache.load(context) ?: return
        if (cached.queryLocation != currentQuery()) return
        snapshot = cached
        condition = cached.condition
        render()
    }

    private fun render() {
        val snapshot = snapshot
        binding.currentTempText.text = formatTemp(snapshot?.currentTemp)
        binding.locationText.text =
            snapshot?.location?.uppercase(Locale.getDefault()) ?: "--"
        binding.highTempText.text = context.getString(
            R.string.widget_weather_high_format,
            formatTemp(snapshot?.highTemp)
        )
        binding.lowTempText.text = context.getString(
            R.string.widget_weather_low_format,
            formatTemp(snapshot?.lowTemp)
        )

        val condition = condition
        val hasCondition = condition != null
        if (condition != null) {
            binding.weatherIcon.setImageResource(condition.iconRes)
            binding.conditionText.text = context.getString(condition.labelRes)
        } else {
            // Keep the centre column's footprint while there is nothing to show.
            binding.conditionText.text = "--"
        }
        binding.weatherIcon.visibility = if (hasCondition) View.VISIBLE else View.INVISIBLE
        binding.conditionText.visibility = if (hasCondition) View.VISIBLE else View.INVISIBLE
    }

    private fun formatTemp(temp: Int?): String = temp?.let { "$it°" } ?: "--°"

    private fun applyStyle() {
        val color = LauncherPreferences.weather().color()
        binding.currentTempText.setTextColor(color)
        binding.locationText.setTextColor(color)
        binding.highTempText.setTextColor(color)
        binding.lowTempText.setTextColor(color)
        binding.conditionText.setTextColor(color)
        binding.weatherIcon.imageTintList = ColorStateList.valueOf(color)

        val typeface =
            LauncherPreferences.clock().font().getTypeface(context, FontSlot.CLOCK)
                ?: Typeface.DEFAULT
        binding.currentTempText.setTypeface(typeface)
        binding.locationText.setTypeface(typeface)
        binding.highTempText.setTypeface(typeface)
        binding.lowTempText.setTypeface(typeface)
        binding.conditionText.setTypeface(typeface)

        val baseSize = LauncherPreferences.clock().fontSize().toFloat()
        val tempSize = (baseSize * TEMP_SCALE).coerceIn(16f, 26f)
        val locationSize = (baseSize * LOCATION_SCALE).coerceIn(9f, 13f)
        val sideSize = (baseSize * SIDE_SCALE).coerceIn(11f, 16f)
        val conditionSize = (baseSize * CONDITION_SCALE).coerceIn(8f, 11f)

        binding.currentTempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, tempSize)
        binding.locationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, locationSize)
        binding.highTempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSize)
        binding.lowTempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSize)
        binding.conditionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, conditionSize)

        // Give both text columns identical row heights so the temperature and the
        // high/low values line up horizontally.
        val firstRow = maxOf(
            lineHeightPx(typeface, tempSize),
            lineHeightPx(typeface, sideSize)
        )
        val secondRow = maxOf(
            lineHeightPx(typeface, locationSize),
            lineHeightPx(typeface, sideSize)
        )
        setRowHeight(binding.currentTempText, firstRow)
        setRowHeight(binding.highTempText, firstRow)
        setRowHeight(binding.locationText, secondRow)
        setRowHeight(binding.lowTempText, secondRow)
    }

    private fun lineHeightPx(typeface: Typeface, sizeSp: Float): Int {
        paint.typeface = typeface
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            resources.displayMetrics
        )
        paint.getFontMetrics(fontMetrics)
        return ((fontMetrics.descent - fontMetrics.ascent).toInt() + 2).coerceAtLeast(1)
    }

    private fun setRowHeight(view: TextView, height: Int) {
        val params = view.layoutParams
        if (params.height == height) return
        params.height = height
        view.layoutParams = params
    }

    // ---- refresh ----------------------------------------------------------

    private fun refreshIfStale() {
        if (isStale(snapshot, currentQuery())) {
            refresh()
        }
    }

    private fun refresh(force: Boolean = false) {
        if (fetching || !attached || !windowVisible) return

        val query = currentQuery()

        // Multiple weather widgets share one cache; use a fresh snapshot from
        // another instance instead of issuing a duplicate request.
        val cached = WeatherCache.load(context)
        if (cached != null && cached.queryLocation == query && !isStale(cached, query)) {
            val current = snapshot
            if (current == null || cached.updatedAt > current.updatedAt) {
                snapshot = cached
                condition = cached.condition
                render()
            }
            return
        }

        if (!force && !isStale(snapshot, query)) return

        fetching = true
        val appContext = context.applicationContext

        EXECUTOR.execute {
            val result = WeatherFetcher.fetch(appContext, query)
            mainHandler.post {
                fetching = false
                if (!attached || result == null) return@post

                if (result.queryLocation != currentQuery()) {
                    // The location setting changed while this request was in flight.
                    refresh(force = true)
                    return@post
                }

                snapshot = result
                condition = result.condition
                render()
                scheduleRefresh()
            }
        }
    }

    private fun isStale(snapshot: WeatherSnapshot?, query: String): Boolean {
        if (snapshot == null || snapshot.queryLocation != query) return true
        val age = System.currentTimeMillis() - snapshot.updatedAt
        // A future timestamp means the clock moved backwards; refresh rather
        // than keeping the cache alive until the clock catches up.
        return age < 0L || age >= REFRESH_INTERVAL_MS
    }

    private fun currentQuery(): String =
        LauncherPreferences.weather().location().orEmpty().trim()

    /**
     * Runs the next refresh exactly when the current snapshot reaches
     * [REFRESH_INTERVAL_MS], rather than a full interval after every resume.
     */
    private fun scheduleRefresh() {
        stopRefreshTicker()
        if (!attached || !windowVisible) return

        refreshTicker = object : Runnable {
            override fun run() {
                refresh(force = true)
                scheduleRefresh()
            }
        }.also {
            mainHandler.postDelayed(it, nextRefreshDelay())
        }
    }

    private fun nextRefreshDelay(): Long {
        // While a request is in flight, retrying immediately would spin.
        if (fetching) return REFRESH_INTERVAL_MS

        val snapshot = snapshot ?: return 0L
        if (snapshot.queryLocation != currentQuery()) return 0L

        val age = System.currentTimeMillis() - snapshot.updatedAt
        if (age < 0L) return 0L
        return (REFRESH_INTERVAL_MS - age).coerceIn(0L, REFRESH_INTERVAL_MS)
    }

    private fun stopRefreshTicker() {
        refreshTicker?.let { mainHandler.removeCallbacks(it) }
        refreshTicker = null
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    companion object {
        private const val WEATHER_PREFIX = "weather."
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L

        private const val TEMP_SCALE = 0.72f
        private const val LOCATION_SCALE = 0.36f
        private const val SIDE_SCALE = 0.48f
        private const val CONDITION_SCALE = 0.28f

        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
