package de.jrpie.android.launcher.ui.util

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView as FrameworkSearchView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.Font
import de.jrpie.android.launcher.preferences.theme.FontSlot
import de.jrpie.android.launcher.ui.widgets.ClockView
import de.jrpie.android.launcher.ui.widgets.DebugInfoView

/**
 * Applies the custom launcher font to all text views that are inflated by an activity.
 *
 * An Android theme can only refer to fonts that are part of the APK. A user supplied
 * font therefore has to be applied programmatically.
 *
 * This has to be called before [AppCompatActivity.onCreate], because AppCompat installs
 * its own factory there and a layout inflater only accepts a single factory. Instead of
 * wrapping the factory afterwards, we delegate view creation to the AppCompat delegate.
 */
internal fun Activity.installCustomFontLayoutInflaterFactory() {
    if (LauncherPreferences.theme().font() != Font.CUSTOM) {
        return
    }

    val typeface = Font.CUSTOM.getTypeface(this, FontSlot.THEME) ?: return
    val inflater = layoutInflater

    if (inflater.factory2 is CustomFontLayoutInflaterFactory) {
        return
    }

    val delegate = if (this is AppCompatActivity) {
        // AppCompat does not install its factory when one is already present, so we
        // have to forward view creation to its delegate ourselves.
        getDelegate() as? LayoutInflater.Factory2
    } else {
        inflater.factory2 ?: inflater.factory?.let { Factory2Adapter(it) }
    }

    try {
        inflater.factory2 = CustomFontLayoutInflaterFactory(delegate, typeface, packageName)
    } catch (_: IllegalStateException) {
        // A factory has already been installed and cannot be replaced. This should not
        // happen, as the factory is installed before AppCompat sets up its own.
        Log.w("CustomFont", "Could not install the layout inflater factory")
    }
}

/**
 * Applies the custom font to views that are created programmatically and therefore
 * do not pass through the layout inflater, e.g. toolbar titles and search fields.
 */
internal fun Activity.applyCustomFontToProgrammaticViews() {
    if (LauncherPreferences.theme().font() != Font.CUSTOM) {
        return
    }

    val typeface = Font.CUSTOM.getTypeface(this, FontSlot.THEME) ?: return
    val content = findViewById<View>(android.R.id.content) ?: return
    content.applyCustomFont(typeface)
}

private fun View.applyCustomFont(typeface: Typeface) {
    // App widgets are rendered by other applications and must keep their own fonts.
    if (this is AppWidgetHostView) {
        return
    }

    // These widgets have their own (clock) font.
    if (this is ClockView || this is DebugInfoView) {
        return
    }

    if (this is TextView) {
        setTypeface(typeface, this.typeface?.style ?: Typeface.NORMAL)
    }

    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyCustomFont(typeface)
        }
    }
}

private class Factory2Adapter(
    private val factory: LayoutInflater.Factory
) : LayoutInflater.Factory2 {
    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? = factory.onCreateView(name, context, attrs)

    override fun onCreateView(
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? = factory.onCreateView(name, context, attrs)
}

private class CustomFontLayoutInflaterFactory(
    private val delegate: LayoutInflater.Factory2?,
    private val typeface: Typeface,
    private val packageName: String
) : LayoutInflater.Factory2 {

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        // RemoteViews are inflated with the context of the app that provides the widget.
        // The custom launcher font must not leak into third party app widgets.
        if (context.packageName != packageName) {
            return null
        }

        val view = delegate?.onCreateView(parent, name, context, attrs)
            ?: createTextView(context, name, attrs)
            ?: return null

        if (view is TextView || view is SearchView || view is FrameworkSearchView) {
            // Search views create their text field programmatically, so applying the
            // typeface to the whole subtree also covers that field.
            view.applyCustomFont(typeface)
        }

        return view
    }

    override fun onCreateView(
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? = onCreateView(null, name, context, attrs)

    private fun createTextView(
        context: Context,
        name: String,
        attrs: AttributeSet
    ): View? {
        val candidates = if (name.indexOf('.') >= 0) {
            listOf(name)
        } else {
            listOf("android.widget.$name", "android.webkit.$name", "android.app.$name")
        }

        for (candidate in candidates) {
            try {
                val clazz = Class.forName(candidate, false, context.classLoader)
                if (!TextView::class.java.isAssignableFrom(clazz)) {
                    continue
                }

                // This is the same reflection based path used by LayoutInflater itself.
                // It does not call the factory again, so it cannot recurse into us.
                return LayoutInflater.from(context).createView(candidate, null, attrs)
            } catch (_: Exception) {
                // Try the next candidate, or let the default inflater handle the view.
            }
        }

        return null
    }
}
