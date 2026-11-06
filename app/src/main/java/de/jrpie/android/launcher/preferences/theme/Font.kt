package de.jrpie.android.launcher.preferences.theme

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import de.jrpie.android.launcher.R

/**
 * The place a font is used. Custom fonts are stored separately for the launcher UI
 * and the clock.
 */
enum class FontSlot(
    internal val fileName: String,
    internal val fontKeyRes: Int,
    internal val nameKeyRes: Int,
    internal val versionKeyRes: Int
) {
    THEME(
        "theme.font",
        R.string.settings_theme_font_key,
        R.string.settings_theme_custom_font_name_key,
        R.string.settings_theme_custom_font_version_key
    ),
    CLOCK(
        "clock.font",
        R.string.settings_clock_font_key,
        R.string.settings_clock_custom_font_name_key,
        R.string.settings_clock_custom_font_version_key
    )
}

/**
 * Changes here must also be added to @array/settings_theme_font_values
 */

@Suppress("unused")
enum class Font(val id: Int, val getTypeface: (Context, FontSlot) -> Typeface?) {
    HACK(
        R.style.fontHack,
        { c, _ -> ResourcesCompat.getFont(c, R.font.hack) }),
    SYSTEM_DEFAULT(
        R.style.fontSystemDefault,
        { _, _ -> Typeface.DEFAULT }),
    SANS_SERIF(
        R.style.fontSansSerif,
        { _, _ -> Typeface.SANS_SERIF }),
    SERIF(
        R.style.fontSerif,
        { _, _ -> Typeface.SERIF }),
    MONOSPACE(
        R.style.fontMonospace,
        { _, _ -> Typeface.MONOSPACE }),
    SERIF_MONOSPACE(
        R.style.fontSerifMonospace,
        { _, _ -> Typeface.create("serif-monospace", Typeface.NORMAL) }),
    CUSTOM(
        R.style.fontCustom,
        { c, slot -> CustomFont.getTypeface(c, slot) }),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        theme.applyStyle(id, true)
    }
}
