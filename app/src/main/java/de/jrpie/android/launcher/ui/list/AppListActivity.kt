package de.jrpie.android.launcher.ui.list

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.widget.SearchView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.jrpie.android.launcher.Application
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.LauncherAction
import de.jrpie.android.launcher.apps.AppFilter
import de.jrpie.android.launcher.apps.hidePrivateSpaceWhenLocked
import de.jrpie.android.launcher.apps.isPrivateSpaceLocked
import de.jrpie.android.launcher.apps.isPrivateSpaceSetUp
import de.jrpie.android.launcher.apps.togglePrivateSpaceLock
import de.jrpie.android.launcher.databinding.ActivityListBinding
import de.jrpie.android.launcher.preferences.LauncherPreferences
import kotlinx.coroutines.launch

/**
 * The [AppListActivity] is used to view all apps and edit their settings.
 * The activity itself can also be chosen to be launched as an action.
 *
 * Open uses a slide-up window transition from the bottom edge; home screen fades out
 * gradually. Close is an instant finish() with no exit animation (matches original).
 * The keyboard opens based on the "Start keyboard for search" setting.
 */
class AppListActivity : AbstractListActivity() {
    override val intention = Companion.Intention.VIEW
    private lateinit var binding: ActivityListBinding

    private fun updateLockIcon(locked: Boolean) {
        if (
        // hide lock when private space does not exist
            !isPrivateSpaceSetUp(this)
            // hide lock when private space apps are hidden from the main list and we are not in the private space list
            || (LauncherPreferences.apps().hidePrivateSpaceApps()
                    && privateSpaceVisibility != AppFilter.Companion.AppSetVisibility.EXCLUSIVE)
            // hide lock when private space is locked and the hidden when locked setting is set
            || (locked && hidePrivateSpaceWhenLocked(this))
        ) {
            binding.listLock.visibility = View.GONE
            return
        }

        binding.listLock.visibility = View.VISIBLE

        binding.listLock.setImageDrawable(
            AppCompatResources.getDrawable(
                this,
                if (locked) {
                    R.drawable.baseline_lock_24
                } else {
                    R.drawable.baseline_lock_open_24
                }
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.listLock.tooltipText = getString(
                if (locked) {
                    R.string.tooltip_unlock_private_space
                } else {
                    R.string.tooltip_lock_private_space
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App drawer slides up from the bottom edge; home screen fades out gradually
        overridePendingTransition(R.anim.slide_up_from_bottom, R.anim.fade_out_background)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY
            ) {
                finishWithSpring()
            }
        }

        // Initialise layout
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        useSoftInputResizeWorkaround(binding.listContainer)

        // SearchView lives in the fragment's view
        binding.root.post {
            val fragment = supportFragmentManager.findFragmentById(R.id.list_fragment_container)
            val searchView = fragment?.view?.findViewById<SearchView>(R.id.list_apps_searchview)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

            // Collapse search bar and hide keyboard until drawer opens
            searchView?.clearFocus()
            searchView?.isIconified = true
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)

            // Auto-open keyboard if setting enabled
            if (LauncherPreferences.functionality().searchAutoOpenKeyboard()) {
                searchView?.isIconified = false
                searchView?.requestFocus()
                binding.root.postDelayed({
                    imm.showSoftInput(searchView, InputMethodManager.SHOW_IMPLICIT)
                }, 200)
            }
        }

        binding.listSettings.setOnClickListener {
            LauncherAction.SETTINGS.launch(this@AppListActivity)
        }

        if (privateSpaceVisibility == AppFilter.Companion.AppSetVisibility.EXCLUSIVE) {
            isPrivateSpaceSetUp(this, showToast = true, launchSettings = true)
            if (isPrivateSpaceLocked(this)) {
                togglePrivateSpaceLock(this)
            }
        }

        updateLockIcon(isPrivateSpaceLocked(this))

        val privateSpaceLocked = (this.applicationContext as Application).privateSpaceLocked
        privateSpaceLocked.observe(this) { updateLockIcon(it) }

        val onThemeChanged = (this.applicationContext as Application).onThemeChanged
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                onThemeChanged.collect {
                    recreate()
                }
            }
        }
    }

    override fun onBackPressed() {
        finishWithSpring()
    }

    private fun finishWithSpring() {
        // Close drawer immediately - no exit animation (same as original)
        finish()
    }

    fun updateTitle() {
        val titleResource =
            if (hiddenVisibility == AppFilter.Companion.AppSetVisibility.EXCLUSIVE) {
                R.string.list_title_hidden
            } else if (privateSpaceVisibility == AppFilter.Companion.AppSetVisibility.EXCLUSIVE) {
                R.string.list_title_private_space
            } else if (favoritesVisibility == AppFilter.Companion.AppSetVisibility.EXCLUSIVE) {
                R.string.list_title_favorite
            } else {
                R.string.list_title_view
            }
        binding.listHeading.text = getString(titleResource)
    }

    override fun setOnClicks() {
        binding.listClose.setOnClickListener { finishWithSpring() }
        binding.listLock.setOnClickListener {
            togglePrivateSpaceLock(this)
            if (privateSpaceVisibility == AppFilter.Companion.AppSetVisibility.EXCLUSIVE) {
                finishWithSpring()
            }
        }
    }

    override fun adjustLayout() {
        updateTitle()
    }
}
