package com.openhands.tvplayer.ui

import android.content.Intent
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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.FavDatabase
import com.openhands.tvplayer.data.UserChannelStore
import com.openhands.tvplayer.model.Channel
import com.openhands.tvplayer.player.PlaybackController
import com.openhands.tvplayer.player.PlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full-screen native player. It reuses the single ExoPlayer owned by
 * [PlaybackController], loads the whole group as a playlist so Next/Previous
 * change channels, and cycles FIT/FILL/ZOOM on the screen-mode button.
 *
 * The channel list is resolved through [UserChannelStore], so a channel the
 * user added or edited is the one that actually plays — not the bundled copy.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var groupView: TextView
    private lateinit var statusView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var playButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var muteButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var speedButton: TextView
    private var fullscreen = false
    private var controlsVisible = true
    private var userSeeking = false
    private var muted = false

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
        volumeBar = findViewById(R.id.volumeBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        muteButton = findViewById(R.id.btnMute)
        repeatButton = findViewById(R.id.btnRepeat)
        shuffleButton = findViewById(R.id.btnShuffle)
        speedButton = findViewById(R.id.btnSpeed)

        setupTransportButtons()
        setupVolume()
        setupShuffleAndRepeat()
        setupSpeed()

        // Dragging the bar scrubs the stream; live streams have no seek window,
        // so the bar stays non-draggable there and only reports the live edge.
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                hideHandler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val player = PlaybackController.existingPlayer()
                val duration = player?.duration ?: C.TIME_UNSET
                if (player != null && duration > 0) {
                    player.seekTo((duration * sb.progress) / sb.max)
                }
                scheduleHide()
            }

            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = Unit
        })

        playerView.player = PlaybackController.player(this)
        applyResizeMode()
        PlaybackController.stateListener = { state, _, _ ->
            runOnUiThread { onPlayerState(state) }
        }

        loadRequestedGroup()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnResize).setOnClickListener {
            PlaybackController.cycleResize()
            applyResizeMode()
            scheduleHide()
        }
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener {
            toggleFullscreen()
            scheduleHide()
        }

        // Tap the picture to bring the controls back or send them away.
        playerView.setOnClickListener {
            setControlsVisible(!controlsVisible)
        }

        // The service owns the MediaSession, so playback survives this activity.
        startService(Intent(this, PlayerService::class.java))

        // Open straight into fullscreen with the controls shown for a moment.
        toggleFullscreen()
        setControlsVisible(true)
    }

    private fun loadRequestedGroup() {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: ChannelListFragment.MODE_LIVE
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

    private fun setupVolume() {
        val player = PlaybackController.player(this)
        muted = player.volume == 0f
        volumeBar.progress = (player.volume * 100).toInt().coerceIn(0, 100)
        updateMuteIcon()
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                PlaybackController.existingPlayer()?.volume = progress / 100f
                muted = progress == 0
                updateMuteIcon()
            }

            override fun onStartTrackingTouch(sb: SeekBar) = hideHandler.removeCallbacks(hideControls)

            override fun onStopTrackingTouch(sb: SeekBar) = scheduleHide()
        })
        muteButton.setOnClickListener {
            val p = PlaybackController.existingPlayer() ?: return@setOnClickListener
            muted = !muted
            p.volume = if (muted) 0f else 1f
            volumeBar.progress = if (muted) 0 else 100
            updateMuteIcon()
            scheduleHide()
        }
    }

    private fun updateMuteIcon() {
        muteButton.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
        muteButton.contentDescription = getString(if (muted) R.string.unmute else R.string.mute)
    }

    private fun setupShuffleAndRepeat() {
        val player = PlaybackController.player(this)
        tint(shuffleButton, player.shuffleModeEnabled)
        updateRepeatIcon(player.repeatMode)
        shuffleButton.setOnClickListener {
            val p = PlaybackController.existingPlayer() ?: return@setOnClickListener
            val on = !p.shuffleModeEnabled
            p.shuffleModeEnabled = on
            tint(shuffleButton, on)
            scheduleHide()
        }
        repeatButton.setOnClickListener {
            val p = PlaybackController.existingPlayer() ?: return@setOnClickListener
            p.repeatMode = when (p.repeatMode) {
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_ALL
            }
            updateRepeatIcon(p.repeatMode)
            scheduleHide()
        }
    }

    private fun updateRepeatIcon(mode: Int) {
        repeatButton.setImageResource(
            if (mode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        tint(repeatButton, mode != Player.REPEAT_MODE_OFF)
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
        playerView.resizeMode = when (PlaybackController.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun updateNowPlaying() {
        val channel = PlaybackController.currentChannel
        titleView.text = channel?.name.orEmpty()
        groupView.text = channel?.group.orEmpty()
        updateProgress()
    }

    /** Mirrors the player position into the bar, or shows CANLI when live. */
    private fun updateProgress() {
        val player = PlaybackController.existingPlayer() ?: return
        val duration = player.duration
        if (duration > 0) {
            val position = player.currentPosition.coerceIn(0, duration)
            if (!userSeeking) {
                seekBar.progress = ((position * seekBar.max) / duration).toInt()
            }
            timeCurrent.text = formatTime(if (userSeeking) (duration * seekBar.progress) / seekBar.max else position)
            timeTotal.text = formatTime(duration)
        } else {
            // Live stream: the window grows behind the live edge but cannot be
            // scrubbed, so the bar tracks the edge and the label says so.
            val position = player.currentPosition.coerceAtLeast(0)
            timeCurrent.setText(R.string.live)
            timeTotal.text = formatTime(position)
        }
        seekBar.isEnabled = duration > 0
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
            }
            else -> {
                loadingView.isVisible = false
                statusView.setText(R.string.stream_error)
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
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MENU -> {
                    // Any D-pad movement brings the controls back into view.
                    if (!controlsVisible) {
                        setControlsVisible(true)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        hideHandler.removeCallbacks(ticker)
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
        private const val CONTROLS_TIMEOUT_MS = 4000L
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
