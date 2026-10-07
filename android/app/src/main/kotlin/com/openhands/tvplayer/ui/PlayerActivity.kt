package com.openhands.tvplayer.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.FavDatabase
import com.openhands.tvplayer.data.UserChannelStore
import com.openhands.tvplayer.model.Channel
import com.openhands.tvplayer.player.PlaybackController
import com.openhands.tvplayer.player.PlayerService
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full-screen native player. It reuses the single LibVLC player owned by
 * [PlaybackController], loads the whole group as a playlist so Next/Previous
 * change channels, and cycles FIT/FILL/ZOOM on the screen-mode button.
 *
 * The channel list is resolved through [UserChannelStore], so a channel the
 * user added or edited is the one that actually plays — not the bundled copy.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: VLCVideoLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var groupView: TextView
    private lateinit var statusView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var playButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var repeatButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var speedButton: TextView
    private var fullscreen = false
    private var controlsVisible = true
    private var userSeeking = false
    /** Set once the "CANLI" bar has been painted so a live feed is not
     *  repainted on every ticker tick. */
    private var livePainted = false

    /** The tab this screen was opened from; radio keeps playing on exit. */
    private var playbackMode: String = ChannelListFragment.MODE_LIVE

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            hideHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Lay the player out edge to edge. Without this the decor still reserves
        // space for the system bars, so hiding them left a black band instead of
        // giving the picture the whole screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Draw into the display cutout as well; without this a notch on the
        // left/right edge leaves a black strip in landscape.
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.playerTopBar)
        bottomBar = findViewById(R.id.playerBottomBar)
        titleView = findViewById(R.id.playerTitle)
        groupView = findViewById(R.id.playerGroup)
        statusView = findViewById(R.id.playerStatus)
        loadingView = findViewById(R.id.playerLoading)
        playButton = findViewById(R.id.btnPlay)
        seekBar = findViewById(R.id.seekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        repeatButton = findViewById(R.id.btnRepeat)
        shuffleButton = findViewById(R.id.btnShuffle)
        speedButton = findViewById(R.id.btnSpeed)

        setupTransportButtons()
        setupShuffleAndRepeat()
        setupSpeed()

        // Dragging the bar scrubs the stream when it carries a seek window
        // (a DVR playlist); feeds without one stay non-draggable.
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                hideHandler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val duration = PlaybackController.duration
                if (PlaybackController.seekable && duration > 0) {
                    PlaybackController.existingPlayer()?.setTime((duration * sb.progress) / sb.max)
                }
                scheduleHide()
            }

            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = Unit
        })

        applyResizeMode()
        PlaybackController.stateListener = { state, _, _ ->
            runOnUiThread { onPlayerState(state) }
        }
        PlaybackController.noticeListener = { mirror ->
            runOnUiThread {
                statusView.text = getString(R.string.mirror_try, mirror)
                statusView.isVisible = true
            }
        }

        loadRequestedGroup()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            stopLiveOnExit()
            finish()
        }
        findViewById<ImageButton>(R.id.btnResize).setOnClickListener {
            PlaybackController.cycleResize()
            applyResizeMode()
            scheduleHide()
        }
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener {
            toggleFullscreen()
            scheduleHide()
        }
        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener {
            rotateScreen()
            scheduleHide()
        }

        // Tap the picture to bring the controls back or send them away.
        playerView.setOnClickListener {
            setControlsVisible(!controlsVisible)
        }

        // The service keeps playback alive in the background, so audio survives
        // leaving this screen (radio) while live TV is stopped on exit.
        startService(Intent(this, PlayerService::class.java))

        // Open straight into fullscreen with the controls shown for a moment.
        toggleFullscreen()
        setControlsVisible(true)
    }

    private fun loadRequestedGroup() {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: ChannelListFragment.MODE_LIVE
        playbackMode = mode
        val group = intent.getStringExtra(EXTRA_GROUP)
        val clickedUrl = intent.getStringExtra(EXTRA_URL)

        lifecycleScope.launch {
            val source = withContext(Dispatchers.IO) { resolveSource(mode) }
            if (source.isEmpty()) return@launch

            val groupChannels = source.filter { it.group == group }.ifEmpty { source }
            val startIndex = groupChannels
                .indexOfFirst { it.url == clickedUrl || it.playUrl == clickedUrl }
                .takeIf { it >= 0 } ?: 0

            PlaybackController.setPlaylist(this@PlayerActivity, groupChannels, startIndex)
            updateNowPlaying()
        }
    }

    /** The visible list for the tab: user channels merged in, or favourites. */
    private suspend fun resolveSource(mode: String): List<Channel> {
        if (mode == ChannelListFragment.MODE_FAV) {
            return FavDatabase.get(this).favDao().all().map { it.toChannel() }
        }
        val store = UserChannelStore.all(this, FavDatabase.get(this).userChannelDao())
        return when (mode) {
            ChannelListFragment.MODE_RADIO -> store.filter { it.isRadio }
            ChannelListFragment.MODE_ALL -> store
            else -> store.filter { !it.isRadio }
        }
    }

    /** Shows the transport bars and hides them again after a short pause. */
    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val target = if (visible) 1f else 0f
        topBar.animate().alpha(target).setDuration(180).withEndAction {
            topBar.isVisible = visible
        }.start()
        bottomBar.animate().alpha(target).setDuration(180).withEndAction {
            bottomBar.isVisible = visible
        }.start()
        if (visible) scheduleHide() else hideHandler.removeCallbacks(hideControls)
    }

    private fun scheduleHide() {
        hideHandler.removeCallbacks(hideControls)
        hideHandler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    private fun setupTransportButtons() {
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            PlaybackController.previous(this)
            scheduleHide()
        }
        playButton.setOnClickListener {
            PlaybackController.toggle(this)
            scheduleHide()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            PlaybackController.next(this)
            scheduleHide()
        }
        findViewById<ImageButton>(R.id.btnStop).setOnClickListener {
            PlaybackController.stop(this)
            loadingView.isVisible = false
            statusView.isVisible = false
            playButton.setImageResource(R.drawable.ic_play)
            scheduleHide()
        }
    }

    /**
     * Flips between landscape and portrait. TV boxes are locked to landscape and
     * simply ignore the request, while a phone/tablet re-lays the player out.
     */
    private fun rotateScreen() {
        val next = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        requestedOrientation = next
    }

    private fun updateMuteIcon() = Unit

    private fun setupShuffleAndRepeat() {
        tint(shuffleButton, PlaybackController.shuffleEnabled)
        updateRepeatIcon(PlaybackController.repeatMode)
        shuffleButton.setOnClickListener {
            val on = PlaybackController.toggleShuffle()
            tint(shuffleButton, on)
            scheduleHide()
        }
        repeatButton.setOnClickListener {
            updateRepeatIcon(PlaybackController.cycleRepeat())
            scheduleHide()
        }
    }

    private fun updateRepeatIcon(mode: Int) {
        repeatButton.setImageResource(
            if (mode == REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        tint(repeatButton, mode != REPEAT_OFF)
    }

    /** Tints a control's icon with the accent colour while it is active. */
    private fun tint(button: ImageButton, active: Boolean) {
        button.setColorFilter(
            ContextCompat.getColor(this, if (active) R.color.tv_accent_2 else R.color.tv_text)
        )
    }

    private fun setupSpeed() {
        speedButton.text = String.format(Locale.US, "%.2fx", PlaybackController.speed)
        speedButton.setOnClickListener {
            val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            AlertDialog.Builder(this)
                .setTitle(R.string.speed)
                .setItems(labels) { _, which ->
                    PlaybackController.setSpeed(this, values[which])
                    speedButton.text = String.format(Locale.US, "%.2fx", values[which])
                }
                .show()
        }
    }

    private fun applyResizeMode() {
        PlaybackController.existingPlayer()?.setVideoScale(PlaybackController.scaleType())
    }

    private fun updateNowPlaying() {
        val channel = PlaybackController.currentChannel
        titleView.text = channel?.name.orEmpty()
        groupView.text = channel?.group.orEmpty()
        updateProgress()
    }

    /**
     * Mirrors the player position into the bar. A live channel can report a
     * very large duration (a 24-hour DVR window), which made the bar look like
     * a full day-long recording, so live is detected from the seekable window
     * instead of from the duration alone and shown as CANLI.
     */
    private fun updateProgress() {
        // After Stop the ticker would otherwise repaint "CANLI" over the cleared
        // transport, so leave the bar alone while nothing is loaded.
        if (PlaybackController.currentChannel == null) return
        val duration = PlaybackController.duration
        // Seekable means the stream actually carries a window we can scrub.
        // A live HLS feed with a DVR playlist reports a long duration but a
        // short remaining window; that is still worth showing and scrubbing,
        // so the bar is driven by seekability rather than by "is live".
        val seekable = PlaybackController.seekable && duration > 0
        if (!seekable) {
            // A plain live feed has nothing to repaint each tick; painting the
            // same "CANLI" bar every 500 ms only churns the UI thread.
            if (livePainted) return
            livePainted = true
            seekBar.progress = seekBar.max
            timeCurrent.setText(R.string.live)
            timeTotal.text = ""
            seekBar.isEnabled = false
            return
        }
        livePainted = false
        val position = PlaybackController.position
        val isLive = duration > 0 && duration - position < LIVE_WINDOW_MS
        val clamped = position.coerceIn(0, duration)
        if (!userSeeking) {
            seekBar.progress = ((clamped * seekBar.max) / duration).toInt()
        }
        timeCurrent.text = formatTime(if (userSeeking) (duration * seekBar.progress) / seekBar.max else clamped)
        timeTotal.text = if (isLive) "-" + formatTime(duration - clamped) else formatTime(duration)
        seekBar.isEnabled = true
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private fun onPlayerState(state: String) {
        updateNowPlaying()
        when (state) {
            "playing" -> {
                loadingView.isVisible = false
                statusView.isVisible = false
                PlaybackController.clearErrorReason()
                playButton.setImageResource(R.drawable.ic_pause)
            }
            "paused" -> {
                loadingView.isVisible = false
                statusView.isVisible = false
                playButton.setImageResource(R.drawable.ic_play)
            }
            "buffering" -> {
                loadingView.isVisible = true
                statusView.isVisible = false
            }
            "retry" -> {
                loadingView.isVisible = false
                statusView.setText(R.string.stream_retry)
                statusView.isVisible = true
                playButton.setImageResource(R.drawable.ic_play)
                livePainted = false
            }
            "stopped" -> {
                // An explicit stop is not an error: clear the status and leave
                // the transport showing a plain play button.
                loadingView.isVisible = false
                statusView.isVisible = false
                playButton.setImageResource(R.drawable.ic_play)
                seekBar.progress = 0
                timeCurrent.text = ""
                timeTotal.text = ""
            }
            else -> {
                loadingView.isVisible = false
                // Prefer the concrete reason (region lock, dead address, bad
                // network) over the generic line, so the user knows why.
                statusView.text = PlaybackController.lastErrorReason
                    ?: getString(R.string.stream_error)
                statusView.isVisible = true
                playButton.setImageResource(R.drawable.ic_play)
            }
        }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-bind the video surface each time the screen appears; the shared
        // player outlives this activity, so its surface was detached on pause.
        PlaybackController.attachView(playerView)
        applyResizeMode()
        hideHandler.post(ticker)
    }

    /**
     * Remote-control handling for TV boxes: the D-pad left/right and the media
     * keys change channel, centre / play-pause toggles, and the back key leaves
     * the player. These keys must keep working whether or not the on-screen
     * controls are visible.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    PlaybackController.previous(this)
                    setControlsVisible(true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    PlaybackController.next(this)
                    setControlsVisible(true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    PlaybackController.toggle(this)
                    setControlsVisible(true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    PlaybackController.existingPlayer()?.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    PlaybackController.existingPlayer()?.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    PlaybackController.stop(this)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // Hardware back leaves the screen; stop live TV so its audio
                    // does not linger, while radio keeps playing.
                    stopLiveOnExit()
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    // On a box without a visible list, up/down is the natural
                    // zap gesture; the menu key still summons the controls.
                    PlaybackController.previous(this)
                    setControlsVisible(true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    PlaybackController.next(this)
                    setControlsVisible(true)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    setControlsVisible(true)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        hideHandler.removeCallbacks(ticker)
        // Detach the picture only; the player keeps running so radio continues
        // in the background.
        PlaybackController.detachView()
    }

    /**
     * Leaving live TV stops the stream so audio does not keep playing behind
     * the list. Radio is meant to keep going in the background, so it is left
     * alone and keeps playing from the notification.
     */
    private fun stopLiveOnExit() {
        if (playbackMode == ChannelListFragment.MODE_RADIO) return
        val current = PlaybackController.currentChannel
        if (current != null && current.isRadio) return
        PlaybackController.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideControls)
        hideHandler.removeCallbacks(ticker)
        PlaybackController.stateListener = null
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_GROUP = "extra_group"
        const val EXTRA_URL = "extra_url"
        // Mirror PlaybackController.repeatMode: ALL=0, ONE=1, OFF=2.
        private const val REPEAT_ONE = 1
        private const val REPEAT_OFF = 2
        private const val CONTROLS_TIMEOUT_MS = 4000L
        private const val PROGRESS_INTERVAL_MS = 500L
        // A stream whose remaining seekable window is shorter than this is
        // treated as live, so a 24-hour DVR duration never shows as a timeline.
        private const val LIVE_WINDOW_MS = 6 * 60 * 60 * 1000L
    }
}
