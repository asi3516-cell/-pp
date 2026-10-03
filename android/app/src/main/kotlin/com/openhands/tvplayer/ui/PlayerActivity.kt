package com.openhands.tvplayer.ui

import android.content.Intent
import android.os.Bundle
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.ChannelRepository
import com.openhands.tvplayer.model.Channel
import com.openhands.tvplayer.player.PlaybackController
import com.openhands.tvplayer.player.PlayerService

/**
 * Full-screen native player. It reuses the single ExoPlayer owned by
 * [PlaybackController], loads the whole group as a playlist so Next/Previous
 * change channels, and cycles FIT/FILL/ZOOM on the screen-mode button.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var groupView: TextView
    private lateinit var statusView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var playButton: ImageButton
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
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
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { PlaybackController.next(this) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { PlaybackController.previous(this) }
        playButton.setOnClickListener { PlaybackController.toggle(this) }
        findViewById<ImageButton>(R.id.btnResize).setOnClickListener {
            PlaybackController.cycleResize()
            applyResizeMode()
        }
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }

        // The service owns the MediaSession, so playback survives this activity.
        startService(Intent(this, PlayerService::class.java))
    }

    private fun loadRequestedGroup() {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: ChannelListFragment.MODE_LIVE
        val group = intent.getStringExtra(EXTRA_GROUP)
        val index = intent.getIntExtra(EXTRA_INDEX, 0)

        val source = if (mode == ChannelListFragment.MODE_RADIO) {
            ChannelRepository.radio(this)
        } else {
            ChannelRepository.live(this)
        }
        val groupChannels: List<Channel> = source.filter { it.group == group }.ifEmpty { source }
        val clicked = source.getOrNull(index)
        val startIndex = groupChannels.indexOfFirst { it.url == clicked?.url }
            .takeIf { it >= 0 } ?: index.coerceIn(0, (groupChannels.size - 1).coerceAtLeast(0))

        PlaybackController.setPlaylist(this, groupChannels, startIndex)
        updateNowPlaying()
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
        PlaybackController.stateListener = null
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_GROUP = "extra_group"
        const val EXTRA_INDEX = "extra_index"
    }
}
