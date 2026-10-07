package com.openhands.tvplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.ChannelRepository

/**
 * Keeps playback alive while the activity is not in front.
 *
 * With VLC there is no MediaSession to lean on, so this is a plain foreground
 * service that owns the playback notification. The widgets and the notification
 * buttons cannot touch the player object, so they send intents here and this
 * service turns them into player commands.
 */
class PlayerService : Service() {

    private val notificationListener: (String, Int, Boolean) -> Unit = { _, _, _ -> updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Build the player up front so the notification has a title to show and
        // a widget tap right after start finds a ready engine.
        PlaybackController.player(this)
        PlaybackController.addListener(notificationListener)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Widget buttons reach the service through PendingIntent.getService.
        // Android 8+ blocks that from the background unless the service goes to
        // the foreground, so promote it before touching the player.
        promoteToForeground()
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
        updateNotification()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    /** Enters the foreground so background widget taps are allowed to start it. */
    private fun promoteToForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.openhands.tvplayer.ui.BottomNavActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playing = PlaybackController.isPlaying
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(PlaybackController.currentChannel?.name ?: getString(R.string.app_name))
            .setContentText(PlaybackController.currentChannel?.group.orEmpty())
            .setSmallIcon(R.drawable.ic_brand_mic)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(if (playing) R.string.widget_pause else R.string.widget_play),
                WidgetIntents.service(
                    this,
                    if (playing) PlaybackActions.ACTION_PAUSE else PlaybackActions.ACTION_PLAY,
                    10
                )
            )
            .addAction(
                0,
                getString(R.string.widget_prev),
                WidgetIntents.service(this, PlaybackActions.ACTION_PREV, 11)
            )
            .addAction(
                0,
                getString(R.string.widget_next),
                WidgetIntents.service(this, PlaybackActions.ACTION_NEXT, 12)
            )
            .addAction(
                0,
                getString(R.string.stop),
                WidgetIntents.service(this, PlaybackActions.ACTION_STOP, 13)
            )
            .build()
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
        if (!PlaybackController.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PlaybackController.removeListener(notificationListener)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tv_player_playback"
        private const val NOTIFICATION_ID = 1
    }
}
