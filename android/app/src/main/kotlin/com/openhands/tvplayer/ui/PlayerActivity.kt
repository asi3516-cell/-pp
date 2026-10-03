package com.openhands.tvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
    private var fullscreen = false
    private var controlsVisible = true

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControlsVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.playerTopBar)
        bottomBar = findViewById(R.id.playerBottomBar)
        titleView = findViewById(R.id.playerTitle)
        groupView = findViewById(R.id.playerGroup)
        statusView = findViewById(R.id.playerStatus)
        loadingView = findViewById(R.id.playerLoading)
        playButton = findViewById(R.id.btnPlay)

        playerView.player = PlaybackController.player(this)
        applyResizeMode()
        PlaybackController.stateListener = { state, _, _ ->
            runOnUiThread { onPlayerState(state) }
        }

        loadRequestedGroup()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            PlaybackController.next(this)
            scheduleHide()
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            PlaybackController.previous(this)
            scheduleHide()
        }
        playButton.setOnClickListener {
            PlaybackController.toggle(this)
            scheduleHide()
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

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideControls)
        PlaybackController.stateListener = null
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_GROUP = "extra_group"
        const val EXTRA_URL = "extra_url"
        private const val CONTROLS_TIMEOUT_MS = 4000L
    }
}
