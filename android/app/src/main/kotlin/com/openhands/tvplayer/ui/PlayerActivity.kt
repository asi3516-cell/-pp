package com.openhands.tvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        titleView = findViewById(R.id.playerTitle)
        statusView = findViewById(R.id.playerStatus)

        playerView.player = PlaybackController.player(this)
        applyResizeMode()
        PlaybackController.stateListener = { state, _, _ -> runOnUiThread { onPlayerState(state) } }

        loadRequestedGroup()

        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { PlaybackController.next(this) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { PlaybackController.previous(this) }
        findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { PlaybackController.toggle(this) }
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
        titleView.text = PlaybackController.currentChannel?.name.orEmpty()
    }

    private fun applyResizeMode() {
        playerView.resizeMode = when (PlaybackController.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun onPlayerState(state: String) {
        titleView.text = PlaybackController.currentChannel?.name.orEmpty()
        when (state) {
            "retry", "stopped" -> {
                statusView.text = getString(R.string.stream_error)
                statusView.visibility = View.VISIBLE
            }
            else -> statusView.visibility = View.GONE
        }
    }

    private fun toggleFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val showing = WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars
        if (!showing) {
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
