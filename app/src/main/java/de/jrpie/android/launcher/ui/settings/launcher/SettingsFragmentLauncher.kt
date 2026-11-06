package de.jrpie.android.launcher.ui.settings.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.jrpie.android.launcher.Application
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.lock.LockMethod
import de.jrpie.android.launcher.actions.openAppsList
import de.jrpie.android.launcher.icons.CustomIconPackHandle
import de.jrpie.android.launcher.icons.IconPack
import de.jrpie.android.launcher.icons.IconPackHandle
import de.jrpie.android.launcher.icons.loadIconPacks
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.ColorTheme
import de.jrpie.android.launcher.preferences.theme.CustomFont
import de.jrpie.android.launcher.preferences.theme.Font
import de.jrpie.android.launcher.preferences.theme.FontSlot
import de.jrpie.android.launcher.setDefaultHomeScreen
import de.jrpie.android.launcher.ui.widgets.manage.ManageWidgetPanelsActivity
import de.jrpie.android.launcher.ui.widgets.manage.ManageWidgetsActivity
import kotlinx.coroutines.launch

private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "font/opentype",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/x-font-opentype",
    "application/vnd.ms-opentype",
    "application/octet-stream"
)

/**
 * The [SettingsFragmentLauncher] is a used as a tab in the SettingsActivity.
 *
 * It is used to change themes, select wallpapers ... theme related stuff
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {

    // TODO: move
    inner class IconPackRecyclerAdapter(
        val context: Context,
        val onClick: (IconPackHandle) -> Unit
    ) :
        RecyclerView.Adapter<IconPackRecyclerAdapter.ViewHolder>() {
        private val iconPackHandles = loadIconPacks(context)

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.dialog_select_icon_pack_row_label)
            val packageName: TextView =
                itemView.findViewById(R.id.dialog_select_icon_pack_row_package_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view: View = inflater.inflate(R.layout.dialog_select_icon_pack_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val iconPack = iconPackHandles[position]
            holder.label.text = iconPack.getLabel(context)
            holder.packageName.text = (iconPack as? CustomIconPackHandle)?.packageName ?: ""
            holder.itemView.setOnClickListener {
                onClick(iconPack)
            }
        }

        override fun getItemCount(): Int {
            return iconPackHandles.size
        }
    }

    // One launcher per slot keeps the result mapping intact across configuration changes.
    private val themeFontPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> handleFontPickerResult(FontSlot.THEME, uri) }

    private val clockFontPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> handleFontPickerResult(FontSlot.CLOCK, uri) }

    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("clock.") == true) {
                updateVisibility()
            }
        }

    private fun updateVisibility() {
        val showSeconds = findPreference<androidx.preference.Preference>(
            LauncherPreferences.clock().keys().showSeconds()
        )
        val timeVisible = LauncherPreferences.clock().timeVisible()
        showSeconds?.isVisible = timeVisible

        val background = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().background()
        )
        val lightTheme = LauncherPreferences.theme().colorTheme() == ColorTheme.LIGHT
        background?.isVisible = !lightTheme

        val iconPack = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().iconPack()
        )
        iconPack?.summary =
            IconPack.getSelectedIconPack(requireContext()).getLabel(requireContext())

        val monochromeIcons = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().monochromeIcons()
        )
        monochromeIcons?.isVisible = !LauncherPreferences.theme().colorTheme().forceMonochrome

        val hidePausedApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidePausedApps()
        )
        hidePausedApps?.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun onPause() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onPause()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setupFontPreference(FontSlot.THEME)
        setupFontPreference(FontSlot.CLOCK)
        setupCustomFontPreference(FontSlot.THEME)
        setupCustomFontPreference(FontSlot.CLOCK)
        refreshCustomFontSummaries()

        val selectWallpaper = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().wallpaper()
        )
        selectWallpaper?.setOnPreferenceClickListener {
            // https://github.com/LineageOS/android_packages_apps_Trebuchet/blob/6caab89b21b2b91f0a439e1fd8c4510dcb255819/src/com/android/launcher3/views/OptionsPopupView.java#L271
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                .putExtra("com.android.wallpaper.LAUNCH_SOURCE", "app_launched_launcher")
                .putExtra("com.android.launcher3.WALLPAPER_FLAVOR", "focus_wallpaper")
            startActivity(intent)
            true
        }
        val chooseHomeScreen = findPreference<androidx.preference.Preference>(
            LauncherPreferences.general().keys().chooseHomeScreen()
        )
        chooseHomeScreen?.setOnPreferenceClickListener {
            setDefaultHomeScreen(requireContext(), checkDefault = false)
            true
        }
        var iconPack = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().iconPack()
        )
        iconPack?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                .setTitle(getString(R.string.dialog_select_icon_pack_title))
                .setView(R.layout.dialog_select_icon_pack)
                .setNegativeButton(android.R.string.cancel, null)
                .create().also { it.show() }.let { dialog ->
                    val viewManager = LinearLayoutManager(dialog.context)
                    val viewAdapter = IconPackRecyclerAdapter(dialog.context) { handle ->
                        LauncherPreferences.theme().iconPack(handle)
                        (context?.applicationContext as? Application)?.loadApps()
                        dialog.dismiss()
                    }
                    dialog.findViewById<RecyclerView>(R.id.dialog_select_icon_pack_recycler).apply {
                        setHasFixedSize(true)
                        layoutManager = viewManager
                        adapter = viewAdapter
                    }
                }
            true
        }


        val manageWidgets = findPreference<androidx.preference.Preference>(
            LauncherPreferences.widgets().keys().widgets()
        )
        manageWidgets?.setOnPreferenceClickListener {
            startActivity(Intent(requireActivity(), ManageWidgetsActivity::class.java))
            true
        }

        val manageWidgetPanels = findPreference<androidx.preference.Preference>(
            LauncherPreferences.widgets().keys().customPanels()
        )
        manageWidgetPanels?.setOnPreferenceClickListener {
            startActivity(Intent(requireActivity(), ManageWidgetPanelsActivity::class.java))
            true
        }

        val hiddenApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), favorite = false, hidden = true)
            true
        }

        val lockMethod = findPreference<androidx.preference.Preference>(
            LauncherPreferences.actions().keys().lockMethod()
        )

        lockMethod?.setOnPreferenceClickListener {
            LockMethod.chooseMethod(requireContext())
            true
        }

        findPreference<androidx.preference.DropDownPreference>(
            LauncherPreferences.theme().keys().colorTheme()
        )?.apply {
            entries = ColorTheme.entries.filter { x -> x.isAvailable() }
                .map { x -> x.getLabel(requireContext()) }.toTypedArray()
            entryValues = ColorTheme.entries.filter { x -> x.isAvailable() }
                .map { x -> x.name }.toTypedArray()
        }


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            lockMethod?.isVisible = false
        }

        updateVisibility()
    }

    private fun setupFontPreference(slot: FontSlot) {
        val preference = findPreference<DropDownPreference>(fontPreferenceKey(slot))
            ?: return

        preference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == Font.CUSTOM.name &&
                    !CustomFont.isAvailable(requireContext(), slot)
                ) {
                    launchFontPicker(slot)
                    false
                } else {
                    true
                }
            }
    }

    private fun setupCustomFontPreference(slot: FontSlot) {
        val key = when (slot) {
            FontSlot.THEME -> R.string.settings_theme_custom_font_key
            FontSlot.CLOCK -> R.string.settings_clock_custom_font_key
        }

        findPreference<Preference>(getString(key))?.setOnPreferenceClickListener {
            launchFontPicker(slot)
            true
        }
    }

    private fun launchFontPicker(slot: FontSlot) {
        when (slot) {
            FontSlot.THEME -> themeFontPicker.launch(FONT_MIME_TYPES)
            FontSlot.CLOCK -> clockFontPicker.launch(FONT_MIME_TYPES)
        }
    }

    private fun handleFontPickerResult(slot: FontSlot, uri: Uri?) {
        if (uri == null) {
            restoreFontSelection(slot)
            return
        }

        val currentView = view ?: return
        val appContext = currentView.context.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = CustomFont.import(appContext, slot, uri)) {
                is CustomFont.ImportResult.Success -> {
                    if (isAdded) {
                        refreshCustomFontSummary(slot)
                        syncFontPreference(slot)
                    }
                    Toast.makeText(
                        appContext,
                        R.string.settings_custom_font_imported,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is CustomFont.ImportResult.Failure -> {
                    if (isAdded) {
                        restoreFontSelection(slot)
                    }
                    Toast.makeText(
                        appContext,
                        result.reason.messageRes,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun syncFontPreference(slot: FontSlot) {
        findPreference<DropDownPreference>(fontPreferenceKey(slot))?.apply {
            value = currentFont(slot).name
        }
    }

    private fun restoreFontSelection(slot: FontSlot) {
        findPreference<DropDownPreference>(fontPreferenceKey(slot))?.apply {
            value = currentFont(slot).name
        }
    }

    private fun currentFont(slot: FontSlot): Font {
        return when (slot) {
            FontSlot.THEME -> LauncherPreferences.theme().font()
            FontSlot.CLOCK -> LauncherPreferences.clock().font()
        }
    }

    private fun fontPreferenceKey(slot: FontSlot): String {
        return when (slot) {
            FontSlot.THEME -> LauncherPreferences.theme().keys().font()
            FontSlot.CLOCK -> LauncherPreferences.clock().keys().font()
        }
    }

    private fun refreshCustomFontSummaries() {
        refreshCustomFontSummary(FontSlot.THEME)
        refreshCustomFontSummary(FontSlot.CLOCK)
    }

    private fun refreshCustomFontSummary(slot: FontSlot) {
        val currentContext = context ?: return
        val key = when (slot) {
            FontSlot.THEME -> R.string.settings_theme_custom_font_key
            FontSlot.CLOCK -> R.string.settings_clock_custom_font_key
        }

        val summary = CustomFont.displayName(currentContext, slot)
            ?.takeIf { CustomFont.isAvailable(currentContext, slot) }
            ?: getString(R.string.settings_custom_font_not_selected)

        findPreference<Preference>(currentContext.getString(key))?.summary = summary
    }
}