package com.openhands.tvplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
            PlaybackActions.ACTION_PLAY -> PlaybackController.existingPlayer()?.play()
            PlaybackActions.ACTION_PAUSE -> PlaybackController.existingPlayer()?.pause()
            PlaybackActions.ACTION_TOGGLE -> PlaybackController.toggle(this)
            PlaybackActions.ACTION_NEXT -> PlaybackController.next(this)
            PlaybackActions.ACTION_PREV -> PlaybackController.previous(this)
            PlaybackActions.ACTION_STOP -> {
                PlaybackController.stop(this)
                stopSelf()
            }
        }
        return START_STICKY
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
