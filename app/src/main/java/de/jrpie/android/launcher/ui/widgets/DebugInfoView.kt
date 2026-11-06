package de.jrpie.android.launcher.ui.widgets

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import de.jrpie.android.launcher.databinding.WidgetDebugInfoBinding
import de.jrpie.android.launcher.getDeviceInfo
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.FontSlot

class DebugInfoView(context: Context, attrs: AttributeSet? = null, val appWidgetId: Int) :
    ConstraintLayout(context, attrs) {

    val binding: WidgetDebugInfoBinding =
        WidgetDebugInfoBinding.inflate(LayoutInflater.from(context), this, true)

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("clock.") == true) {
                applyFont()
            }
        }

    init {
        binding.debugInfoText.text = getDeviceInfo()
        applyFont()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun onDetachedFromWindow() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onDetachedFromWindow()
    }

    private fun applyFont() {
        binding.debugInfoText.setTypeface(
            LauncherPreferences.clock().font()
                .getTypeface(context, FontSlot.CLOCK) ?: Typeface.DEFAULT
        )
    }
}
