package com.openhands.iptvplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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

        server = new LocalServer(getAssets(), 8737);
        try {
            server.start();
        } catch (Exception first) {
            // Preferred port taken: fall back to an ephemeral one. Favorites
            // live in localStorage keyed by origin, so the fixed port keeps
            // them across restarts whenever it is available.
            try {
                server = new LocalServer(getAssets(), 0);
                server.start();
            } catch (Exception e) {
                web.loadData("<h2>Sunucu başlatılamadı: " + e.getMessage() + "</h2>",
                        "text/html; charset=utf-8", "UTF-8");
                return;
            }
        }
        web.loadUrl("http://127.0.0.1:" + server.getListeningPort() + "/");
    }

    /** Forward a zapping direction to the web player. */
    private void zap(int delta) {
        if (web != null) {
            web.evaluateJavascript(
                    "(window.iptvZap && window.iptvZap(" + delta + "));", null);
        }
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
        if (server != null) server.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
