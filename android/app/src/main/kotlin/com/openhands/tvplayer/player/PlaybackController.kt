package com.openhands.tvplayer.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.openhands.tvplayer.model.Channel
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single owner of the ExoPlayer instance, shared by the activity (picture)
 * and the MediaSessionService (background playback + widgets). Both sides call
 * into this object so there is never a second player fighting for the audio
 * focus.
 */
object PlaybackController {

    /** The stream is retried this many times before the UI is told it failed. */
    const val MAX_RETRIES = 5

    /** Parallel connections kept open for one stream, to avoid freezing. */
    const val MAX_CONNECTIONS = 20

    @Volatile
    private var exo: ExoPlayer? = null

    @Volatile
    var playlist: List<Channel> = emptyList()
        private set

    @Volatile
    var currentIndex: Int = 0
        private set

    @Volatile
    var currentChannel: Channel? = null
        private set

    /** 0 = FIT, 1 = FILL, 2 = ZOOM. Cycled by the screen-mode button. */
    @Volatile
    var resizeMode: Int = 0
        private set

    private var retryCount = 0
    private var lastUrl: String? = null
    private var lastError: PlaybackException? = null

    /** Optional hook so the activity can reflect native state back into the UI. */
    @Volatile
    var stateListener: ((String, Int, Boolean) -> Unit)? = null

    fun player(context: Context): ExoPlayer {
        exo?.let { return it }
        synchronized(this) {
            exo?.let { return it }
            val built = build(context.applicationContext)
            exo = built
            return built
        }
    }

    fun existingPlayer(): ExoPlayer? = exo

    private fun build(context: Context): ExoPlayer {
        // A generous connection pool and dispatcher let one stream open many
        // parallel segment requests, which is what keeps an HLS live edge from
        // stalling behind a slow segment.
        val dispatcher = Dispatcher().apply { maxRequests = MAX_CONNECTIONS; maxRequestsPerHost = MAX_CONNECTIONS }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(MAX_CONNECTIONS, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val dataSource = OkHttpDataSource.Factory(client)

        // The back buffer is capped so a long session cannot grow without bound;
        // only the forward buffer is allowed to reach the longer duration.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 5_000, 10_000)
            .setBackBuffer(30_000, false)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Repeat the whole playlist so Next on the last item loops to the first.
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retryCount = 0
                notifyState()
                WidgetUpdater.refresh(context)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retryCount = 0
                notifyState()
                WidgetUpdater.refresh(context)
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                if (idx in playlist.indices) {
                    currentIndex = idx
                    currentChannel = playlist[idx]
                }
                notifyState()
                WidgetUpdater.refresh(context)
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = error
                handleError(context)
            }
        })
        return player
    }

    /**
     * Loads a whole group as the playlist and jumps to [index]. Loading the
     * group (not just one item) is what makes Next/Previous change channels.
     */
    fun setPlaylist(context: Context, channels: List<Channel>, index: Int, autoPlay: Boolean = true) {
        if (channels.isEmpty()) return
        val player = player(context)
        playlist = channels
        currentIndex = index.coerceIn(0, channels.size - 1)
        currentChannel = channels[currentIndex]

        val items = channels.map { ch ->
            MediaItem.Builder()
                .setUri(ch.playUrl)
                .setMediaId(ch.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ch.name)
                        .setArtist(ch.group)
                        .setArtworkUri(ch.logo?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, currentIndex, C.TIME_UNSET)
        player.prepare()
        if (autoPlay) player.play()
        retryCount = 0
        lastUrl = channels[currentIndex].playUrl
        notifyState()
        WidgetUpdater.refresh(context)
    }

    fun next(context: Context) {
        val player = existingPlayer() ?: return
        player.seekToNextMediaItem()
        player.play()
    }

    fun previous(context: Context) {
        val player = existingPlayer() ?: return
        player.seekToPreviousMediaItem()
        player.play()
    }

    fun toggle(context: Context) {
        val player = existingPlayer() ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stop(context: Context) {
        val player = existingPlayer() ?: return
        player.stop()
        notifyState()
        WidgetUpdater.refresh(context)
    }

    /** Cycles FIT -> FILL -> ZOOM -> FIT and returns the new mode. */
    fun cycleResize(): Int {
        resizeMode = (resizeMode + 1) % 3
        return resizeMode
    }

    private fun handleError(context: Context) {
        val player = existingPlayer() ?: return
        if (retryCount < MAX_RETRIES) {
            retryCount++
            stateListener?.invoke("retry", retryCount, false)
            player.prepare()
            player.play()
        } else {
            stateListener?.invoke("stopped", 0, false)
            WidgetUpdater.refresh(context)
        }
    }

    private fun notifyState() {
        val player = existingPlayer() ?: return
        stateListener?.invoke(
            if (player.isPlaying) "playing" else "paused",
            player.currentMediaItemIndex,
            player.isPlaying
        )
    }
}
