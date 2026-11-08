package de.jrpie.android.launcher.ui.widgets

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import de.jrpie.android.launcher.databinding.WidgetNowPlayingBinding
import de.jrpie.android.launcher.media.NowPlayingListenerService
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.FontSlot
import de.jrpie.android.launcher.widgets.WidgetPanel

class NowPlayingView(
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

    private val binding: WidgetNowPlayingBinding =
        WidgetNowPlayingBinding.inflate(LayoutInflater.from(context), this, true)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private var controller: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            post { onMetadata(metadata) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            post { onPlaybackState(state) }
        }

        override fun onSessionDestroyed() {
            post {
                clearController()
                refreshSessions()
            }
        }
    }

    private var title = ""
    private var duration = 0L
    private var playbackState: PlaybackState? = null
    private var listening = false
    private var windowVisible = true
    private var sessionsListenerRegistered = false

    private var progressTicker: Runnable? = null
    private var visualizerTicker: Runnable? = null
    private var hideRunnable: Runnable? = null

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            post { selectSession(controllers) }
        }

    private val preferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith(NOW_PLAYING_PREFIX) == true ||
                key?.startsWith("clock.font") == true
            ) {
                post(::applyStyle)
            }
        }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopTickers()
                Intent.ACTION_SCREEN_ON -> if (isActive(playbackState?.state ?: -1)) {
                    startTickers()
                }
            }
        }
    }

    init {
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        applyStyle()
        listening = true
        windowVisible = windowVisibility == VISIBLE
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
        refreshSessions()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!listening) return
        windowVisible = visibility == VISIBLE
        if (windowVisible) {
            // Pick up access changes made in settings and resume animation.
            refreshSessions()
        } else {
            // Screen off / launcher in background: stop all periodic work.
            stopTickers()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        listening = false
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        stopTickers()
        clearController()
        unregisterSessionListener()
    }

    // ---- session handling -------------------------------------------------

    private fun refreshSessions() {
        if (!listening) return
        val manager = mediaSessionManager ?: return
        if (!NowPlayingListenerService.hasAccess(context)) {
            unregisterSessionListener()
            clearController()
            return
        }
        try {
            val component = NowPlayingListenerService.componentName(context)
            if (!sessionsListenerRegistered) {
                manager.addOnActiveSessionsChangedListener(sessionListener, component)
                sessionsListenerRegistered = true
            }
            selectSession(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing media session access", e)
            clearController()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read media sessions", e)
            clearController()
        }
    }

    private fun unregisterSessionListener() {
        if (!sessionsListenerRegistered) return
        sessionsListenerRegistered = false
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: SecurityException) {
        }
    }

    private fun selectSession(controllers: List<MediaController>?) {
        if (!listening) return
        if (controllers.isNullOrEmpty()) {
            clearController()
            return
        }

        // Prefer the session that is currently playing; otherwise keep the current one.
        val currentToken = controller?.sessionToken
        val chosen = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull { it.sessionToken == currentToken } ?: controllers.first()

        if (chosen.sessionToken != currentToken) {
            clearController()
            controller = chosen
            chosen.registerCallback(controllerCallback, mainHandler)
        }

        onMetadata(chosen.metadata)
        onPlaybackState(chosen.playbackState)
    }

    private fun clearController() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        playbackState = null
        title = ""
        duration = 0L
        stopTickers()
        hide()
    }

    private fun onMetadata(metadata: MediaMetadata?) {
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?.takeIf { it.isNotBlank() }
            ?: ""
        duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        binding.titleText.text = title
        updateContent()
    }

    private fun onPlaybackState(state: PlaybackState?) {
        playbackState = state
        val active = state != null && isActive(state.state)

        if (active) {
            cancelHide()
            updateContent()
            startTickers()
        } else {
            stopVisualizer()
            scheduleHide()
        }
    }

    private fun updateContent() {
        val active = playbackState != null && isActive(playbackState!!.state)
        if (active && title.isNotBlank()) {
            visibility = VISIBLE
            updateProgress()
            startTickers()
        }
    }

    private fun scheduleHide() {
        if (visibility != VISIBLE) {
            hide()
            return
        }
        // Keep the original grace period; repeated paused-state callbacks must not
        // postpone the hide indefinitely.
        if (hideRunnable != null) return
        hideRunnable = Runnable { hide() }.also {
            mainHandler.postDelayed(it, HIDE_DELAY_MS)
        }
    }

    private fun cancelHide() {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun hide() {
        cancelHide()
        visibility = GONE
        stopTickers()
    }

    private fun isActive(state: Int): Boolean = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true

        else -> false
    }

    // ---- progress + visualizer -------------------------------------------

    private fun startTickers() {
        if (!listening || !windowVisible) return
        if (visibility != VISIBLE) return
        if (!isActive(playbackState?.state ?: -1)) return
        if (progressTicker != null) return

        progressTicker = object : Runnable {
            override fun run() {
                updateProgress()
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }.also { mainHandler.post(it) }

        visualizerTicker = object : Runnable {
            override fun run() {
                binding.visualizerView.advance()
                mainHandler.postDelayed(this, VISUALIZER_INTERVAL_MS)
            }
        }.also { mainHandler.post(it) }
    }

    private fun stopTickers() {
        progressTicker?.let { mainHandler.removeCallbacks(it) }
        visualizerTicker?.let { mainHandler.removeCallbacks(it) }
        progressTicker = null
        visualizerTicker = null
        binding.visualizerView.reset()
    }

    private fun stopVisualizer() {
        visualizerTicker?.let { mainHandler.removeCallbacks(it) }
        visualizerTicker = null
        binding.visualizerView.reset()
    }

    private fun updateProgress() {
        val state = playbackState
        if (state == null || duration <= 0L) {
            binding.progressBar.visibility = INVISIBLE
            return
        }
        binding.progressBar.visibility = VISIBLE
        val updated = state.lastPositionUpdateTime
        val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
        val position = if (state.state == PlaybackState.STATE_PLAYING && updated > 0L) {
            state.position +
                ((SystemClock.elapsedRealtime() - updated) * speed).toLong()
        } else {
            state.position
        }
        val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
        binding.progressBar.progress = (fraction * PROGRESS_MAX).toInt()
    }

    // ---- styling ---------------------------------------------------------

    private fun applyStyle() {
        val color = LauncherPreferences.now_playing().color()
        binding.titleText.setTextColor(color)
        binding.titleText.setTypeface(
            LauncherPreferences.clock().font()
                .getTypeface(context, FontSlot.CLOCK) ?: Typeface.DEFAULT
        )
        binding.titleText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (LauncherPreferences.clock().fontSize() * TITLE_SCALE).coerceIn(11f, 16f)
        )
        binding.visualizerView.setColor(color)
        binding.progressBar.progressTintList = ColorStateList.valueOf(color)
        binding.progressBar.progressBackgroundTintList = ColorStateList.valueOf(
            (color and 0x00FFFFFF) or 0x40000000
        )
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    companion object {
        private const val TAG = "NowPlayingView"
        private const val NOW_PLAYING_PREFIX = "now_playing."
        private const val PROGRESS_MAX = 10000
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val VISUALIZER_INTERVAL_MS = 100L
        private const val HIDE_DELAY_MS = 1500L
        private const val TITLE_SCALE = 0.5f
    }
}

/**
 * Minimal bar visualizer. A compact row of small bars rises and falls with a smoothed,
 * randomized motion. Purely procedural, no audio analysis.
 */
class NowPlayingVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val density = resources.displayMetrics.density
    private val barCount = 14
    private val barPitch = 5f * density
    private val barWidth = 2.6f * density
    private val random = java.util.Random()
    private val barLevels = FloatArray(barCount) { 0.35f }
    private val barTargets = FloatArray(barCount) { 0.35f }
    private var barTick = 0
    private var color = 0xFFFFFFFF.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE || width <= 0 || height <= 0) return
        paint.color = color

        val totalWidth = (barCount - 1) * barPitch + barWidth
        val startX = (width - totalWidth) / 2f
        val bottom = height.toFloat()
        val maxBarHeight = height * 0.98f
        val minBarHeight = height * 0.16f
        val radius = barWidth / 2f

        for (i in 0 until barCount) {
            val level = barLevels[i].coerceIn(0f, 1f)
            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * level
            val left = startX + i * barPitch
            canvas.drawRoundRect(
                left,
                bottom - barHeight,
                left + barWidth,
                bottom,
                radius,
                radius,
                paint
            )
        }
    }

    /**
     * Random-walk style motion: each bar eases toward a new random target every other
     * tick, so the row never settles into a repeated, uniform wave.
     */
    private fun updateBars() {
        barTick++
        if (barTick % 2 == 0) {
            for (i in 0 until barCount) {
                // Bias toward quiet bars so occasional tall peaks stand out.
                val raw = random.nextFloat()
                barTargets[i] = raw * raw
            }
        }
        for (i in 0 until barCount) {
            barLevels[i] += (barTargets[i] - barLevels[i]) * 0.38f
        }
    }

    fun setColor(value: Int) {
        color = value
        invalidate()
    }

    fun advance() {
        updateBars()
        invalidate()
    }

    fun reset() {
        barTick = 0
        for (i in 0 until barCount) {
            barLevels[i] = 0.2f + random.nextFloat() * 0.3f
            barTargets[i] = barLevels[i]
        }
        invalidate()
    }
}
