package de.jrpie.android.launcher.ui.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.databinding.WidgetStatusBarBinding
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.FontSlot
import de.jrpie.android.launcher.widgets.WidgetPanel

class StatusBarView(
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

    private val binding: WidgetStatusBarBinding =
        WidgetStatusBarBinding.inflate(LayoutInflater.from(context), this, true)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var listening = false

    private var networkType = NetworkType.NONE
    private var batteryPercent = -1
    private var batteryStage = 0

    private val preferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("status_bar.") == true ||
                key?.startsWith("clock.font") == true
            ) {
                post(::update)
            }
        }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::updateBattery)
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                post { updateNetwork(networkCapabilities) }
            } else {
                // Before N this callback fires for every matching network, not only the
                // active one, so resolve the active network instead.
                post { updateNetworkFromActiveNetwork() }
            }
        }

        override fun onLost(network: Network) {
            post { updateNetworkFromActiveNetwork() }
        }
    }

    init {
        update()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (listening) {
            return
        }
        listening = true

        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(preferencesListener)

        val stickyBatteryIntent = ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        stickyBatteryIntent?.let(::updateBattery)

        connectivityManager?.let { manager ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    manager.registerDefaultNetworkCallback(connectivityCallback)
                } else {
                    manager.registerNetworkCallback(
                        NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                        connectivityCallback
                    )
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not register network callback", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not register network callback", e)
            }
            updateNetworkFromActiveNetwork()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!listening) {
            return
        }
        listening = false

        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }

        connectivityManager?.let {
            try {
                it.unregisterNetworkCallback(connectivityCallback)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun update() {
        val color = LauncherPreferences.status_bar().color()
        val tint = ColorStateList.valueOf(color)

        binding.networkIcon.imageTintList = tint
        binding.batteryIcon.imageTintList = tint
        binding.batteryText.setTextColor(color)

        binding.networkIcon.visibility =
            if (networkType == NetworkType.NONE) GONE else VISIBLE
        binding.networkIcon.setImageResource(
            if (networkType == NetworkType.WIFI) {
                R.drawable.ic_status_wifi_24
            } else {
                R.drawable.ic_status_cellular_24
            }
        )
        binding.networkIcon.contentDescription = when (networkType) {
            NetworkType.WIFI -> context.getString(R.string.widget_status_bar_network_wifi)
            NetworkType.CELLULAR -> context.getString(R.string.widget_status_bar_network_mobile)
            NetworkType.NONE -> null
        }

        val batteryKnown = batteryPercent >= 0
        binding.batteryText.visibility = if (batteryKnown) VISIBLE else INVISIBLE
        binding.batteryIcon.visibility = if (batteryKnown) VISIBLE else INVISIBLE
        binding.batteryIcon.setImageResource(BATTERY_ICONS[batteryStage])
        if (batteryKnown) {
            binding.batteryText.text =
                context.getString(R.string.widget_status_bar_battery_format, batteryPercent)
        }

        val typeface = LauncherPreferences.clock().font()
            .getTypeface(context, FontSlot.CLOCK) ?: Typeface.DEFAULT
        binding.batteryText.setTypeface(typeface)
        binding.batteryText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (LauncherPreferences.clock().fontSize() * BATTERY_TEXT_SCALE).coerceIn(10f, 14f)
        )
    }

    private fun updateNetwork(capabilities: NetworkCapabilities) {
        networkType = networkTypeOf(capabilities)
        update()
    }

    private fun updateNetworkFromActiveNetwork() {
        networkType = activeNetworkType()
        update()
    }

    @Suppress("DEPRECATION")
    private fun activeNetworkType(): NetworkType {
        val manager = connectivityManager ?: return NetworkType.NONE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = manager.activeNetwork ?: return NetworkType.NONE
            val capabilities = manager.getNetworkCapabilities(activeNetwork)
                ?: return NetworkType.NONE
            networkTypeOf(capabilities)
        } else {
            when (manager.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                else -> NetworkType.NONE
            }
        }
    }

    private fun networkTypeOf(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.NONE
        }
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return
        }
        val percent = level * 100 / scale
        post {
            batteryPercent = percent
            batteryStage = batteryStage(percent)
            update()
        }
    }

    private fun batteryStage(percent: Int): Int {
        return when {
            percent >= 93 -> 7
            percent >= 78 -> 6
            percent >= 64 -> 5
            percent >= 50 -> 4
            percent >= 35 -> 3
            percent >= 21 -> 2
            percent >= 7 -> 1
            else -> 0
        }
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private enum class NetworkType {
        NONE, WIFI, CELLULAR
    }

    private companion object {
        const val TAG = "StatusBarView"
        const val BATTERY_TEXT_SCALE = 0.375f

        val BATTERY_ICONS = intArrayOf(
            R.drawable.ic_battery_0_24,
            R.drawable.ic_battery_1_24,
            R.drawable.ic_battery_2_24,
            R.drawable.ic_battery_3_24,
            R.drawable.ic_battery_4_24,
            R.drawable.ic_battery_5_24,
            R.drawable.ic_battery_6_24,
            R.drawable.ic_battery_7_24
        )
    }
}
