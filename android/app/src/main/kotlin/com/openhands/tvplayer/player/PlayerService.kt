package com.openhands.tvplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.openhands.tvplayer.data.ChannelRepository

/**
 * Keeps playback alive while the activity is not in front and owns the
 * MediaSession that the widgets and the media notification talk to.
 *
 * The widgets cannot touch the player object, so they send intents here and
 * this service turns them into player commands.
 */
class PlayerService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlaybackController.player(this)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PlaybackActions.ACTION_PLAY -> {
                ensureRadioLoaded()
                PlaybackController.existingPlayer()?.play()
            }
            PlaybackActions.ACTION_PAUSE -> PlaybackController.existingPlayer()?.pause()
            PlaybackActions.ACTION_TOGGLE -> {
                ensureRadioLoaded()
                PlaybackController.toggle(this)
            }
            PlaybackActions.ACTION_NEXT -> {
                ensureRadioLoaded()
                PlaybackController.next(this)
            }
            PlaybackActions.ACTION_PREV -> {
                ensureRadioLoaded()
                PlaybackController.previous(this)
            }
            PlaybackActions.ACTION_STOP -> {
                PlaybackController.stop(this)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Widgets must work from a cold start, when nothing has been loaded yet.
     * Loading the radio list here keeps the widget radio-only: it can never
     * start a TV stream.
     */
    private fun ensureRadioLoaded() {
        if (PlaybackController.currentChannel != null) return
        val radios = ChannelRepository.radio(this)
        if (radios.isNotEmpty()) {
            PlaybackController.setPlaylist(this, radios, 0, autoPlay = false)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlaybackController.existingPlayer()
        if (player == null || !player.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
