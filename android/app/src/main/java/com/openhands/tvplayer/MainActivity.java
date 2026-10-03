package com.openhands.tvplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Hosts the bundled web player in a WebView and backs it with LocalServer so
 * HLS playback and the JSON API work exactly like the desktop build.
 *
 * The remote's track / channel buttons are mapped to channel zapping, which is
 * what you want when watching full screen rather than seeking inside the video.
 */
public class MainActivity extends Activity {

    private WebView web;
    private LocalServer server;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCb;
    private MediaSession session;
    private boolean radioMode = false;
    private boolean pageReady = false;
    private String pendingCmd = null;

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
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new WidgetBridge(), "AndroidWidget");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCb = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenView == null) return;
                root.removeView(fullscreenView);
                fullscreenView = null;
                web.setVisibility(View.VISIBLE);
                if (fullscreenCb != null) fullscreenCb.onCustomViewHidden();
                fullscreenCb = null;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        createSession();

        server = new LocalServer(getApplicationContext(), getAssets(), 8737);
        try {
            server.start();
        } catch (Exception first) {
            // Preferred port taken: fall back to an ephemeral one. Favorites
            // live in localStorage keyed by origin, so the fixed port keeps
            // them across restarts whenever it is available.
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

    /** Media session keeps audio alive in the background and in the car. */
    private void createSession() {
        if (Build.VERSION.SDK_INT < 21) return;
        session = new MediaSession(this, "TVPlayerRadio");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() { widgetCmd("PLAY"); }

            @Override
            public void onPause() { widgetCmd("PAUSE"); }

            @Override
            public void onStop() { widgetCmd("STOP"); }

            @Override
            public void onSkipToNext() { widgetCmd("NEXT"); }

            @Override
            public void onSkipToPrevious() { widgetCmd("PREV"); }
        });
        session.setActive(true);
    }

    /** Forward a zapping direction to the web player. */
    private void zap(int delta) {
        if (web != null) {
            web.evaluateJavascript(
                    "(window.tvZap && window.tvZap(" + delta + "));", null);
        }
    }

    /** Radio widgets drive the web player through this bridge. */
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
                @Override
                public void run() {
                    RadioWidgetBase.updateAll(MainActivity.this);
                    updateSession(title, sub, playing);
                }
            });
        }

        @JavascriptInterface
        public void setRadio(final boolean on) {
            radioMode = on;
            if (on) web.evaluateJavascript("(window.tvKeepAlive && window.tvKeepAlive());", null);
        }

        @JavascriptInterface
        public void ready() {
            pageReady = true;
            if (pendingCmd != null) {
                final String cmd = pendingCmd;
                pendingCmd = null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { widgetCmd(cmd); }
                });
            }
        }
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

    private void widgetCmd(String what) {
        if (web == null) return;
        if (!pageReady) { pendingCmd = what; return; }
        web.evaluateJavascript(
                "(window.tvWidgetCmd && window.tvWidgetCmd('" + what + "'));", null);
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
                zap(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (fullscreenView != null) {
                    onHideCustomViewViaWeb();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void onHideCustomViewViaWeb() {
        if (fullscreenView != null) {
            root.removeView(fullscreenView);
            fullscreenView = null;
            web.setVisibility(View.VISIBLE);
            if (fullscreenCb != null) fullscreenCb.onCustomViewHidden();
            fullscreenCb = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (server != null) server.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
