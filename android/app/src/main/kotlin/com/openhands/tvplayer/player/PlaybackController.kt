package com.openhands.tvplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.openhands.tvplayer.model.Channel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * The single owner of the LibVLC player instance, shared by the activity
 * (picture) and the foreground service (background playback + widgets).
 *
 * VLC replaced ExoPlayer because it is markedly more tolerant of the malformed
 * and short-lived HLS feeds Turkish broadcasters serve: it recovers from a
 * dropped segment without the whole stream erroring out, and it handles live
 * HLS, radio and reconnects with far less tuning.
 *
 * The playlist is owned here rather than by the engine: VLC plays one media at
 * a time, so Next/Previous swap the media on the single player and the mirror
 * retry logic is driven by the engine's error event.
 */
object PlaybackController {

    /** The stream is retried this many times before a mirror is tried. */
    const val MAX_RETRIES = 2

    /** How many times the same URL is retried before switching to a mirror. */
    private const val SAME_URL_RETRIES = 2

    /** Kept for callers that size their own network pool. */
    const val MAX_CONNECTIONS = 50

    /** How often the stall watchdog samples the play position. */
    private const val STALL_CHECK_MS = 8_000L

    /** Samples without forward progress before the stream is refreshed. */
    private const val STALL_LIMIT = 2

    /** Wait before a failed stream is retried, so a dead host is not hammered. */
    private const val RETRY_DELAY_MS = 1_200L

    /** Mirrors are tried back-to-back: the old one already proved it is dead. */
    private const val MIRROR_DELAY_MS = 300L

    /** No frame this long after playback starts means the mirror is black. */
    private const val BLACKSCREEN_MS = 3_000L

    private const val PREFS = "hh_player"
    private const val PREF_OK_MIRROR = "ok_mirror_"
    private const val PREF_FAIL_MIRROR = "fail_mirror_"

    /**
     * Browser identity used for every stream request. Broadcasters that block
     * the default player User-Agent start answering once this is sent.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Aggregator whose pages hand the player a Referer; mirrors require it. */
    private const val REFERER = "https://www.canlitv.fun/"

    @Volatile
    private var libVlc: LibVLC? = null

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var appContext: Context? = null

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

    /** 0 = ALL, 1 = ONE, 2 = OFF. Cycled by the repeat button. */
    @Volatile
    var repeatMode: Int = 0
        private set

    /** When on, Next/Previous jump to a random channel instead of the neighbour. */
    @Volatile
    var shuffleEnabled: Boolean = false
        private set

    /** Playback speed, kept here so it survives across channel changes. */
    @Volatile
    var speed: Float = 1f
        private set

    /** True when the current stream reports a seekable window. */
    @Volatile
    var seekable: Boolean = false
        private set

    @Volatile
    private var isPlayingFlag: Boolean = false

    /** True once the current media has shown a frame; gates the spinner so a
     *  mid-stream rebuffer cannot pin it to the screen. */
    @Volatile
    private var hasPlayedOnce: Boolean = false

    private var retryCount = 0
    private var urlAttempt = 0

    // Remembering the mirror that last worked lets a channel start straight on
    // it next time instead of re-trying a dead primary. Counts are kept per
    // URL so a bad mirror is skipped even when it is listed first.
    private var prefs: SharedPreferences? = null

    // A stream that produces no frame within BLACKSCREEN_MS is not merely
    // buffering: switch to the next mirror before the user gives up.
    private var mediaStartedAt = 0L
    private var blackChecked = true
    private val blackCheck = object : Runnable {
        override fun run() {
            if (blackChecked || hasPlayedOnce) return
            if (!isPlayingFlag) {
                stallHandler.postDelayed(this, BLACKSCREEN_MS)
                return
            }
            if (mediaPlayer != null && System.currentTimeMillis() - mediaStartedAt < BLACKSCREEN_MS) {
                stallHandler.postDelayed(this, 500L)
                return
            }
            val ch = currentChannel ?: return
            // Radios have no video output, so the black-screen check does not
            // apply: only a live TV feed can silently fail to paint.
            if (ch.isRadio) return
            val streams = ch.urls.filter { it.url.isNotBlank() }
            if (urlAttempt + 1 < streams.size) {
                urlAttempt++
                retryCount = 0
                val next = urlAttempt + 1
                mainHandler.post {
                    noticeListener?.invoke(next)
                    playUrl(ch, urlAttempt)
                }
            }
        }
    }

    // Stall watchdog: a live stream can freeze without ever raising an error,
    // so the play position is sampled and the stream refreshed when it stops
    // moving. It runs on its own thread: sampling player.time and restarting
    // the media on the main thread blocked the UI and risked an ANR.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stallThread = HandlerThread("hh-stall").apply { start() }
    private val stallHandler = Handler(stallThread.looper)
    private var lastPosition = 0L
    private var stalledSamples = 0
    private val stallCheck = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPlayingFlag) {
                val position = player.time
                if (position > lastPosition) {
                    lastPosition = position
                    stalledSamples = 0
                } else if (++stalledSamples >= STALL_LIMIT) {
                    stalledSamples = 0
                    mainHandler.post { refreshStream() }
                }
            } else if (player != null) {
                lastPosition = player.time
                stalledSamples = 0
            }
            stallHandler.postDelayed(this, STALL_CHECK_MS)
        }
    }

    /** Survives an explicit Stop so Play can bring the channel back. */
    @Volatile
    private var lastChannel: Channel? = null

    /** Optional hook so the activity can reflect native state back into the UI. */
    @Volatile
    var stateListener: ((String, Int, Boolean) -> Unit)? = null

    /** Notice for the OSD, carrying the 1-based mirror number being tried. */
    @Volatile
    var noticeListener: ((Int) -> Unit)? = null

    // The service and the activity both want state updates, and only one can
    // own [stateListener]; extra listeners (the notification) go here so they
    // never clobber the on-screen UI.
    private val listeners =
        java.util.Collections.synchronizedSet(mutableSetOf<(String, Int, Boolean) -> Unit>())

    fun addListener(l: (String, Int, Boolean) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String, Int, Boolean) -> Unit) {
        listeners.remove(l)
    }

    private fun dispatch(state: String, index: Int, playing: Boolean) {
        // VLC raises its events on a native thread, but the listeners touch the
        // UI (spinner, notification, widgets). Bounce to the main thread so
        // those updates never run off it, which is what crashed the app.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListeners(state, index, playing)
        } else {
            mainHandler.post { notifyListeners(state, index, playing) }
        }
    }

    private fun notifyListeners(state: String, index: Int, playing: Boolean) {
        stateListener?.invoke(state, index, playing)
        synchronized(listeners) { listeners.toList() }.forEach { it(state, index, playing) }
    }

    /**
     * Human-readable reason the last attempt failed, in Turkish, so the player
     * can tell the user *why* a channel will not open instead of a vague
     * "stream error". Null while things are fine.
     */
    @Volatile
    var lastErrorReason: String? = null
        private set

    // Audio focus + the noisy-headphones receiver, so a stream pauses when the
    // headphones are pulled and stops when another app takes over.
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                mediaPlayer?.pause()
                isPlayingFlag = false
                notifyState()
            }
        }
    }

    /** Clears the stored reason once playback is healthy again. */
    fun clearErrorReason() {
        lastErrorReason = null
    }

    /** True while the underlying engine reports playback. */
    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true || isPlayingFlag

    /** The stream duration in milliseconds, or 0 when unknown. */
    val duration: Long
        get() = mediaPlayer?.length ?: 0L

    /** The current position in milliseconds. */
    val position: Long
        get() = mediaPlayer?.time ?: 0L

    fun player(context: Context): MediaPlayer {
        rememberPrefs(context)
        mediaPlayer?.let { return it }
        synchronized(this) {
            mediaPlayer?.let { return it }
            appContext = context.applicationContext
            val built = build(context.applicationContext)
            mediaPlayer = built
            return built
        }
    }

    fun existingPlayer(): MediaPlayer? = mediaPlayer

    private fun build(context: Context): MediaPlayer {
        // Live playback tuned for stability on weak devices. The cache is long
        // enough to ride out a mobile-network dip, and late frames are allowed
        // to drop: forcing every frame through (the old --no-drop-late-frames /
        // --no-skip-frames pair) let the decoder queue grow without bound and
        // froze the picture on a 720p feed.
        // LibVLC appends --aout/--android-display-chroma to this list itself,
        // so it has to be mutable: an immutable Kotlin listOf() makes the
        // constructor throw UnsupportedOperationException.
        val options = mutableListOf(
            "--network-caching=2500",
            "--live-caching=2500",
            "--file-caching=3000",
            "--drop-late-frames",
            "--skip-frames",
            "--no-video-title-show",
            "--http-referrer=$REFERER",
            "--http-user-agent=$USER_AGENT",
            // Reconnect on its own when a live server drops the connection,
            // instead of surfacing an error to the user.
            "--http-reconnect",
            "--http-continuous",
            // Live HLS timestamps drift; without these VLC freezes the picture
            // to resync instead of simply following the stream.
            "--clock-jitter=0",
            "--clock-synchro=0",
            // Decode as fast as the device allows so a slow CPU cannot build a
            // backlog of frames.
            "--avcodec-fast",
        )
        val vlc = LibVLC(context, options)
        libVlc = vlc
        val player = MediaPlayer(vlc)
        // VLC delivers events on its own native thread; every handler below
        // (state, error retry, media swap) touches player/UI state, so hop to
        // the main thread once here instead of at each call site.
        player.setEventListener { event -> mainHandler.post { onVlcEvent(context, event) } }
        stallHandler.removeCallbacks(stallCheck)
        stallHandler.postDelayed(stallCheck, STALL_CHECK_MS)
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return player
    }

    private fun onVlcEvent(context: Context, event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                isPlayingFlag = true
                hasPlayedOnce = true
                blackChecked = true
                stallHandler.removeCallbacks(blackCheck)
                currentChannel?.let { markMirrorWorked(it, urlAttempt) }
                retryCount = 0
                lastErrorReason = null
                notifyState()
                mainHandler.post { WidgetUpdater.refresh(context) }
            }
            MediaPlayer.Event.Paused -> {
                isPlayingFlag = false
                notifyState()
                mainHandler.post { WidgetUpdater.refresh(context) }
            }
            MediaPlayer.Event.Buffering -> {
                // VLC reports buffering as a percentage. Before the first frame
                // the spinner is useful, but a live stream rebuffers constantly
                // and showing it again would pin it to the screen, so once the
                // media has played it is only ever cleared, never re-shown.
                if (event.buffering >= 100f || hasPlayedOnce) {
                    notifyState()
                } else {
                    isPlayingFlag = false
                    dispatch("buffering", currentIndex, false)
                }
            }
            MediaPlayer.Event.Vout -> {
                // A video output exists: the first frame is up, so the spinner
                // has done its job even if Playing has not arrived yet.
                hasPlayedOnce = true
                blackChecked = true
                stallHandler.removeCallbacks(blackCheck)
                notifyState()
            }
            MediaPlayer.Event.EndReached -> {
                isPlayingFlag = false
                // Respect the repeat mode: ONE loops the same channel, ALL
                // advances through the list, OFF just stops.
                when (repeatMode) {
                    1 -> if (currentChannel != null) playUrl(currentChannel!!, urlAttempt)
                    0 -> if (playlist.isNotEmpty()) {
                        playIndex(context, (currentIndex + 1) % playlist.size)
                    }
                }
            }
            MediaPlayer.Event.Stopped -> {
                isPlayingFlag = false
                notifyState()
            }
            MediaPlayer.Event.EncounteredError -> {
                isPlayingFlag = false
                handleError(context)
            }
            MediaPlayer.Event.SeekableChanged -> {
                seekable = event.seekable
                notifyState()
            }
            MediaPlayer.Event.LengthChanged -> notifyState()
        }
    }

    /**
     * Restarts the current item in place. Used by the stall watchdog when a
     * stream freezes without erroring: it reloads the same URL, which is
     * usually enough to rejoin the live edge.
     */
    private fun refreshStream() {
        val ch = currentChannel ?: return
        playUrl(ch, urlAttempt)
        dispatch("retry", 0, false)
    }

    /**
     * Loads a whole group as the playlist and jumps to [index]. Loading the
     * group (not just one item) is what makes Next/Previous change channels.
     */
    fun setPlaylist(context: Context, channels: List<Channel>, index: Int, autoPlay: Boolean = true) {
        if (channels.isEmpty()) return
        player(context)
        playlist = channels
        currentIndex = index.coerceIn(0, channels.size - 1)
        currentChannel = channels[currentIndex]
        lastChannel = currentChannel
        retryCount = 0
        urlAttempt = 0
        playIndex(context, currentIndex, autoPlay)
    }

    /** Plays the playlist entry at [index] on the single VLC player. */
    private fun playIndex(context: Context, index: Int, autoPlay: Boolean = true) {
        val ch = playlist.getOrNull(index) ?: return
        rememberPrefs(context)
        currentIndex = index
        currentChannel = ch
        lastChannel = ch
        urlAttempt = preferredMirror(ch)
        retryCount = 0
        playUrl(ch, urlAttempt, autoPlay)
    }

    // ---- mirror memory ------------------------------------------------
    // A channel's first URL is not always the one that works, and a mirror
    // that failed once rarely recovers. The last working mirror is stored per
    // channel so the next open starts on it. Counts keep us honest: a URL is
    // only preferred once it has worked more often than it has failed.
    private fun rememberPrefs(context: Context) {
        if (prefs == null) prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun mirrorPrefKey(prefix: String, ch: Channel, url: String): String {
        val hash = (ch.name + "\u0000" + url).hashCode().toString(16)
        return prefix + hash
    }

    private fun preferredMirror(ch: Channel): Int {
        val streams = ch.urls.filter { it.url.isNotBlank() }
        if (streams.size <= 1) return 0
        val store = prefs ?: return 0
        var best = 0
        var bestScore = -1
        streams.forEachIndexed { i, s ->
            val ok = store.getInt(mirrorPrefKey(PREF_OK_MIRROR, ch, s.url), 0)
            val fail = store.getInt(mirrorPrefKey(PREF_FAIL_MIRROR, ch, s.url), 0)
            val score = ok - fail
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        // A mirror with a worse record than the primary is never preferred.
        return if (bestScore <= 0) 0 else best
    }

    private fun markMirrorWorked(ch: Channel, attempt: Int) {
        val store = prefs ?: return
        val s = ch.urls.filter { it.url.isNotBlank() }.getOrNull(attempt) ?: return
        val key = mirrorPrefKey(PREF_OK_MIRROR, ch, s.url)
        store.edit().putInt(key, store.getInt(key, 0) + 1).apply()
    }

    private fun markMirrorFailed(ch: Channel, attempt: Int) {
        val store = prefs ?: return
        val s = ch.urls.filter { it.url.isNotBlank() }.getOrNull(attempt) ?: return
        val key = mirrorPrefKey(PREF_FAIL_MIRROR, ch, s.url)
        store.edit().putInt(key, store.getInt(key, 0) + 1).apply()
    }

    /** The stream URL for one channel, choosing which mirror to use. */
    private fun urlFor(ch: Channel, attempt: Int): String {
        val streams = ch.urls.filter { it.url.isNotBlank() }
        return if (streams.isEmpty()) {
            ch.playUrl
        } else {
            streams[attempt.coerceIn(0, streams.size - 1)].url
        }
    }

    /**
     * Builds a media for [ch]'s [attempt]-th mirror and starts it. The referer
     * and user-agent are set per media as well as globally, because a few
     * mirrors only honour the per-item option.
     */
    private fun playUrl(ch: Channel, attempt: Int, autoPlay: Boolean = true) {
        val vlc = libVlc ?: return
        val player = mediaPlayer ?: return
        val url = urlFor(ch, attempt)
        hasPlayedOnce = false
        // Arm the black-screen check for this media: if no frame appears in
        // BLACKSCREEN_MS the watchdog moves on to the next mirror.
        mediaStartedAt = System.currentTimeMillis()
        blackChecked = false
        stallHandler.removeCallbacks(blackCheck)
        stallHandler.postDelayed(blackCheck, BLACKSCREEN_MS)
        val media = Media(vlc, Uri.parse(url)).apply {
            // Software decoding on purpose. The MediaCodec hardware decoder on
            // many phones and TV boxes shows a single frame and then freezes on
            // these Turkish HLS feeds, or renders nothing at all, which is
            // exactly the "Star plays 1s then stops / TRT 1 stays black"
            // symptom. The streams are 720p/1080p H.264, so software decoding
            // is comfortably fast enough.
            setHWDecoderEnabled(false, false)
            addOption(":avcodec-hw=none")
            addOption(":http-referrer=$REFERER")
            addOption(":http-user-agent=$USER_AGENT")
            addOption(":network-caching=2500")
            addOption(":live-caching=2500")
        }
        player.media = media
        media.release()
        player.rate = speed
        if (autoPlay) {
            requestAudioFocus()
            player.play()
        }
    }

    fun next(context: Context) {
        changeChannel(context, 1)
    }

    fun previous(context: Context) {
        changeChannel(context, -1)
    }

    /**
     * Moves to the neighbouring channel in the playlist. VLC's own chapter
     * navigation is not used: on a live stream it would jump inside the DVR
     * window instead of switching the channel. Wrapping around the list keeps
     * the buttons useful at either end, and the list is rebuilt when Stop
     * cleared it so the buttons keep working.
     */
    private fun changeChannel(context: Context, delta: Int) {
        if (playlist.isEmpty()) return
        val target = if (shuffleEnabled && playlist.size > 1) {
            var r = currentIndex
            while (r == currentIndex) r = (0 until playlist.size).random()
            r
        } else {
            ((currentIndex + delta) % playlist.size + playlist.size) % playlist.size
        }
        playIndex(context, target)
    }

    /** Cycles repeat ALL -> ONE -> OFF and returns the new mode. */
    fun cycleRepeat(): Int {
        repeatMode = (repeatMode + 1) % 3
        return repeatMode
    }

    /** Turns shuffle on/off and returns the new state. */
    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        return shuffleEnabled
    }

    fun toggle(context: Context) {
        val player = mediaPlayer ?: return
        if (isPlaying || player.isPlaying) {
            player.pause()
            isPlayingFlag = false
            abandonAudioFocus()
        } else if (currentChannel == null && playlist.isNotEmpty()) {
            resumeLast(context)
        } else {
            requestAudioFocus()
            player.play()
        }
    }

    /**
     * Re-loads the channel that an explicit Stop cleared, so the play button
     * after Stop behaves like "resume" instead of doing nothing.
     */
    private fun resumeLast(context: Context) {
        if (playlist.isNotEmpty()) {
            playIndex(context, currentIndex.coerceIn(0, playlist.size - 1))
            return
        }
        val ch = lastChannel ?: return
        playlist = listOf(ch)
        playIndex(context, 0)
    }

    fun stop(context: Context) {
        val player = mediaPlayer ?: return
        // Keep the index so Next/Previous can rebuild from the remembered list,
        // but drop the media so the service cannot re-prepare behind the user's
        // back.
        lastChannel = currentChannel
        player.stop()
        isPlayingFlag = false
        currentChannel = null
        seekable = false
        abandonAudioFocus()
        notifyState()
        mainHandler.post { WidgetUpdater.refresh(context) }
    }

    /** Cycles FIT -> FILL -> ZOOM -> FIT and returns the new mode. */
    fun cycleResize(): Int {
        resizeMode = (resizeMode + 1) % 3
        return resizeMode
    }

    /** Sets the playback speed and re-applies it to any live player. */
    fun setSpeed(context: Context, value: Float) {
        speed = value
        player(context).rate = value
    }

    /** Maps the FIT/FILL/ZOOM mode to the matching VLC scale type. */
    fun scaleType(): MediaPlayer.ScaleType = when (resizeMode) {
        1 -> MediaPlayer.ScaleType.SURFACE_FILL
        2 -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
        else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    /**
     * Binds the on-screen video surface to the shared player. VLC keeps the
     * player alive across screens, so the surface has to be attached when the
     * activity appears and detached when it goes away, otherwise a later screen
     * would render into a dead surface.
     */
    fun attachView(layout: org.videolan.libvlc.util.VLCVideoLayout) {
        val player = player(layout.context)
        if (!player.vlcVout.areViewsAttached()) {
            player.attachViews(layout, null, false, false)
        }
        player.setVideoScale(scaleType())
    }

    /** Releases the video surface while keeping the audio stream alive. */
    fun detachView() {
        val player = mediaPlayer ?: return
        if (player.vlcVout.areViewsAttached()) player.detachViews()
    }

    /**
     * A VLC failure does not carry an HTTP status, so the wording stays general
     * but still tells the user the source, not the phone, is at fault.
     */
    private fun describeError(): String =
        "Yayın açılamadı; sunucu yanıt vermedi. Kaynak kapalı olabilir."

    private fun handleError(context: Context) {
        val ch = currentChannel
        lastErrorReason = describeError()
        if (ch == null) {
            dispatch("stopped", 0, false)
            mainHandler.post { WidgetUpdater.refresh(context) }
            return
        }
        // Retry the same stream a couple of times for a transient blip, then
        // fall over to the next mirror before declaring the channel dead.
        if (retryCount < SAME_URL_RETRIES) {
            retryCount++
            if (retryCount >= SAME_URL_RETRIES) currentChannel?.let { markMirrorFailed(it, urlAttempt) }
            dispatch("retry", retryCount, false)
            mainHandler.postDelayed({
                if (currentChannel == ch) playUrl(ch, urlAttempt)
            }, RETRY_DELAY_MS)
            return
        }
        val streams = ch.urls.filter { it.url.isNotBlank() }
        if (urlAttempt + 1 < streams.size) {
            urlAttempt++
            retryCount = 0
            dispatch("retry", 0, false)
            mainHandler.postDelayed({
                if (currentChannel == ch) playUrl(ch, urlAttempt)
            }, MIRROR_DELAY_MS)
            return
        }
        dispatch("stopped", 0, false)
        mainHandler.post { WidgetUpdater.refresh(context) }
    }

    private fun notifyState() {
        val player = mediaPlayer ?: return
        val state = when {
            player.isPlaying || isPlayingFlag -> "playing"
            currentChannel == null -> "stopped"
            else -> "paused"
        }
        dispatch(state, currentIndex, player.isPlaying)
    }

    // ---- Audio focus -------------------------------------------------------

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        mediaPlayer?.pause()
                        isPlayingFlag = false
                        notifyState()
                    }
                }
                .build()
                .also { focusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        registerNoisy()
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val ctx = appContext ?: return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    /**
     * Starts the first radio when nothing is loaded, so a widget tap works from
     * a cold start. Returns false when a stream is already playing.
     */
    fun ensureRadioPlaying(context: Context, channels: List<Channel>): Boolean {
        val current = currentChannel
        if (current != null && current.isRadio) {
            requestAudioFocus()
            mediaPlayer?.play()
            return false
        }
        if (channels.isEmpty()) return false
        setPlaylist(context, channels, 0)
        return true
    }
}
