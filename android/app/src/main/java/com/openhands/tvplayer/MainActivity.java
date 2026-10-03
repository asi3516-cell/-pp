package com.openhands.tvplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * Hosts the bundled channel-list UI in a WebView and plays every stream with
 * the native ExoPlayer, which handles HLS, radio and reconnects far more
 * reliably than the WebView's own video element.
 *
 * A stream that fails is retried up to {@link #MAX_RETRIES} times with a short
 * backoff; after that playback stops. The repeat button keeps a single stream
 * looping, so a server that keeps dropping the connection is picked up again.
 */
@UnstableApi
public class MainActivity extends Activity {

    private static final int MAX_RETRIES = 5;
    private static final int MAX_CONNECTIONS = 20;

    private WebView web;
    private LocalServer server;
    private FrameLayout root;
    private FrameLayout playerHolder;
    private PlayerView playerView;
    private LinearLayout topBar;
    private TextView titleView;
    private Button repeatBtn;
    private ExoPlayer player;
    private MediaSession session;

    private String curUrl = null;
    private String curTitle = "";
    private String curSub = "";
    private int retryCount = 0;
    private boolean repeatOne = false;
    private boolean isFullscreen = false;
    private boolean pageReady = false;
    private String pendingCmd = null;
    private int stageX = 0, stageY = 0, stageW = 0, stageH = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        setContentView(root);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new WidgetBridge(), "AndroidWidget");
        web.addJavascriptInterface(new PlayerBridge(), "AndroidPlayer");
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        createPlayerView();
        createSession();

        server = new LocalServer(getApplicationContext(), getAssets(), 8737);
        try {
            server.start();
        } catch (Exception first) {
            try {
                server = new LocalServer(getApplicationContext(), getAssets(), 0);
                server.start();
            } catch (Exception e) {
                web.loadData("<h2>Sunucu başlatılamadı: " + e.getMessage() + "</h2>",
                        "text/html; charset=utf-8", "UTF-8");
                return;
            }
        }
        web.loadUrl("http://127.0.0.1:" + server.getListeningPort() + "/");
        applyWidgetIntent(getIntent());
    }

    /* ----------------------------- native player ---------------------- */

    private void createPlayerView() {
        playerHolder = new FrameLayout(this);
        playerHolder.setBackgroundColor(Color.BLACK);
        playerHolder.setVisibility(View.GONE);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setBackgroundColor(Color.BLACK);
        playerHolder.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(24, 12, 24, 12);
        topBar.setBackgroundColor(0x99000000);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setMaxLines(1);
        topBar.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button prev = navButton("⏮");
        prev.setOnClickListener(v -> evalJs("window.tvZap && window.tvZap(-1);"));
        Button next = navButton("⏭");
        next.setOnClickListener(v -> evalJs("window.tvZap && window.tvZap(1);"));
        repeatBtn = navButton("⟳");
        repeatBtn.setOnClickListener(v -> setRepeat(!repeatOne));
        Button full = navButton("⛶");
        full.setOnClickListener(v -> setFullscreen(!isFullscreen));
        topBar.addView(prev);
        topBar.addView(next);
        topBar.addView(repeatBtn);
        topBar.addView(full);

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP;
        playerHolder.addView(topBar, tp);

        // The native controller hides itself; keep our channel bar in step.
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener)
                        visibility -> topBar.setVisibility(visibility));

        root.addView(playerHolder, new FrameLayout.LayoutParams(0, 0));
    }

    private Button navButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(130, 110);
        lp.setMargins(8, 0, 8, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** Build (or rebuild) ExoPlayer with the channel's request headers. */
    private void startPlayback(String url, String title, String sub, JSONObject headers) {
        stopPlayback();
        curUrl = url;
        curTitle = title == null ? "" : title;
        curSub = sub == null ? "" : sub;
        retryCount = 0;

        // One shared OkHttp client with a wide connection pool, so a single HLS
        // stream can pull its segments over many parallel connections and never
        // stalls waiting for a free socket.
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(MAX_CONNECTIONS);
        dispatcher.setMaxRequestsPerHost(MAX_CONNECTIONS);
        OkHttpClient http = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(MAX_CONNECTIONS, 5, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        OkHttpDataSource.Factory dataSource = new OkHttpDataSource.Factory(http)
                .setUserAgent("TV Player/1.0 (Android)");
        if (headers != null) {
            HashMap<String, String> props = new HashMap<>();
            for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                String k = it.next();
                props.put(k, headers.optString(k));
            }
            dataSource.setDefaultRequestProperties(props);
        }

        // Generous forward buffer, bounded memory: enough to ride out a network
        // hiccup, but the back buffer is capped so a long session cannot grow
        // the app's memory footprint.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(50000, 120000, 5000, 10000)
                .setBackBuffer(30000, false)
                .setTargetBufferBytes(C.LENGTH_UNSET)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .setLoadControl(loadControl)
                .build();
        player.setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) retryCount = 0;
                notifyState();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) retryCount = 0;
                updateSession(curTitle, curSub, playing);
                notifyState();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handleError();
            }
        });

        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();

        playerView.setPlayer(player);
        playerHolder.setVisibility(View.VISIBLE);
        titleView.setText(curTitle.isEmpty() ? "TV Player" : curTitle);
        updateSession(curTitle, curSub, true);
    }

    /** Retry the same stream a few times, then give up and stop. */
    private void handleError() {
        final String url = curUrl;
        if (url == null) return;
        if (retryCount < MAX_RETRIES) {
            retryCount++;
            final int attempt = retryCount;
            evalJs("window.tvNativeEvent && window.tvNativeEvent('retry'," + attempt + ");");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (player != null && url.equals(curUrl)) {
                        player.prepare();
                        player.play();
                    }
                }
            }, 1200L * attempt);
        } else {
            evalJs("window.tvNativeEvent && window.tvNativeEvent('stopped',0);");
            stopPlayback();
        }
    }

    private void stopPlayback() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
        if (playerHolder != null) playerHolder.setVisibility(View.GONE);
        curUrl = null;
        retryCount = 0;
        updateSession("", "", false);
    }

    private void setRepeat(boolean on) {
        repeatOne = on;
        if (player != null) {
            player.setRepeatMode(on ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
        if (repeatBtn != null) {
            repeatBtn.setBackgroundColor(on ? 0xFF3B82F6 : 0x33FFFFFF);
        }
    }

    private void setFullscreen(boolean on) {
        isFullscreen = on;
        if (playerHolder == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) playerHolder.getLayoutParams();
        if (on) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            web.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            web.setVisibility(View.VISIBLE);
            lp.width = stageW;
            lp.height = stageH;
            lp.leftMargin = stageX;
            lp.topMargin = stageY;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        playerHolder.setLayoutParams(lp);
    }

    private void evalJs(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    private void notifyState() {
        if (player == null) return;
        boolean playing = player.isPlaying();
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        JSONObject o = new JSONObject();
        try {
            o.put("state", playing ? "playing" : "paused");
            o.put("position", pos);
            o.put("duration", dur < 0 ? -1 : dur);
            o.put("buffering", player.getPlaybackState() == Player.STATE_BUFFERING);
            o.put("attempt", retryCount);
        } catch (Exception ignored) { }
        evalJs("window.tvNativeEvent && window.tvNativeEvent('state'," + o + ");");
    }

    /** Called from the web UI to drive the native player. */
    private class PlayerBridge {
        @JavascriptInterface
        public void setBounds(int x, int y, int w, int h) {
            stageX = x; stageY = y; stageW = w; stageH = h;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (playerHolder == null || isFullscreen) return;
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) playerHolder.getLayoutParams();
                    lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y;
                    playerHolder.setLayoutParams(lp);
                }
            });
        }

        @JavascriptInterface
        public void play(final String url, final String title, final String sub,
                         final String headersJson) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    JSONObject h = null;
                    try {
                        if (headersJson != null && !headersJson.isEmpty()) {
                            h = new JSONObject(headersJson);
                        }
                    } catch (Exception ignored) { }
                    startPlayback(url, title, sub, h);
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(new Runnable() { @Override public void run() { stopPlayback(); } });
        }

        @JavascriptInterface
        public void toggle() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (player == null) return;
                    if (player.isPlaying()) player.pause(); else player.play();
                }
            });
        }

        @JavascriptInterface
        public void seekBy(final double seconds) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (player == null) return;
                    long dur = player.getDuration();
                    long target = player.getCurrentPosition() + (long) (seconds * 1000);
                    if (dur > 0) target = Math.max(0, Math.min(dur - 500, target));
                    else target = Math.max(0, target);
                    player.seekTo(target);
                }
            });
        }

        @JavascriptInterface
        public void seekTo(final double ms) {
            runOnUiThread(new Runnable() {
                @Override public void run() { if (player != null) player.seekTo((long) ms); }
            });
        }

        @JavascriptInterface
        public void setMuted(final boolean muted) {
            runOnUiThread(new Runnable() {
                @Override public void run() { if (player != null) player.setVolume(muted ? 0f : 1f); }
            });
        }

        @JavascriptInterface
        public void setRepeatMode(final boolean on) {
            runOnUiThread(new Runnable() { @Override public void run() { setRepeat(on); } });
        }

        @JavascriptInterface
        public void setFullscreenMode(final boolean on) {
            runOnUiThread(new Runnable() { @Override public void run() { setFullscreen(on); } });
        }

        @JavascriptInterface
        public void ready() { PlayerBridgeReady(); }
    }

    /* ----------------------------- media session ---------------------- */

    private void createSession() {
        session = new MediaSession(this, "TVPlayerRadio");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (player != null) player.play(); }
            @Override public void onPause() { if (player != null) player.pause(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSkipToNext() { evalJs("window.tvZap && window.tvZap(1);"); }
            @Override public void onSkipToPrevious() { evalJs("window.tvZap && window.tvZap(-1);"); }
        });
        session.setActive(true);
    }

    private void updateSession(String title, String sub, boolean playing) {
        if (session == null) return;
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title == null ? "" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub == null ? "" : sub);
        session.setMetadata(md.build());
        int st = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState.Builder ps = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f);
        session.setPlaybackState(ps.build());
    }

    /* ----------------------------- widgets ---------------------------- */

    private class WidgetBridge {
        @JavascriptInterface
        public void nowPlaying(final String title, final String sub, final boolean playing) {
            SharedPreferences p = getSharedPreferences(RadioWidgetBase.PREFS, Context.MODE_PRIVATE);
            p.edit()
                    .putString(RadioWidgetBase.K_TITLE, title == null ? "" : title)
                    .putString(RadioWidgetBase.K_SUB, sub == null ? "" : sub)
                    .putBoolean(RadioWidgetBase.K_PLAYING, playing)
                    .apply();
            runOnUiThread(new Runnable() {
                @Override public void run() { RadioWidgetBase.updateAll(MainActivity.this); }
            });
        }

        @JavascriptInterface
        public void setRadio(final boolean on) { }

        @JavascriptInterface
        public void ready() { PlayerBridgeReady(); }
    }

    private void PlayerBridgeReady() {
        pageReady = true;
        if (pendingCmd != null) {
            final String cmd = pendingCmd;
            pendingCmd = null;
            runOnUiThread(new Runnable() { @Override public void run() { widgetCmd(cmd); } });
        }
    }

    private void widgetCmd(String what) {
        // A widget press can cold-start the activity: hold the command until
        // the web UI has loaded, otherwise the JS call lands on a blank page.
        if (!pageReady) { pendingCmd = what; return; }
        evalJs("(window.tvWidgetCmd && window.tvWidgetCmd('" + what + "'));");
    }

    private void applyWidgetIntent(Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (a == null) return;
        if (a.equals("com.openhands.tvplayer.CMD_PREV")) widgetCmd("PREV");
        else if (a.equals("com.openhands.tvplayer.CMD_NEXT")) widgetCmd("NEXT");
        else if (a.equals("com.openhands.tvplayer.CMD_TOGGLE")) widgetCmd("TOGGLE");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyWidgetIntent(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                evalJs("window.tvZap && window.tvZap(1);");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                evalJs("window.tvZap && window.tvZap(-1);");
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (isFullscreen) { setFullscreen(false); return true; }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        if (server != null) server.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
