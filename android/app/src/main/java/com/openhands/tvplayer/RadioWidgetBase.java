package com.openhands.tvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import com.openhands.tvplayer.player.PlaybackActions;
import com.openhands.tvplayer.player.WidgetIntents;

/**
 * Home-screen widget showing the radio station that is playing, with
 * previous / play-pause / next buttons. Three sizes share this logic and only
 * differ in layout, so the same state drives all of them.
 *
 * Playback lives inside the WebView, so a button press starts (or resumes) the
 * activity with a command action; MainActivity turns that into a JS call.
 */
public abstract class RadioWidgetBase extends AppWidgetProvider {

    public static final String ACTION_PREV = "com.openhands.tvplayer.WIDGET_PREV";
    public static final String ACTION_PLAY = "com.openhands.tvplayer.WIDGET_PLAY";
    public static final String ACTION_NEXT = "com.openhands.tvplayer.WIDGET_NEXT";

    static final String PREFS = "tv_widget";
    static final String K_TITLE = "title";
    static final String K_SUB = "sub";
    static final String K_PLAYING = "playing";

    protected abstract int layoutId();

    protected abstract boolean hasControls();

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        RemoteViews rv = build(c);
        for (int id : ids) mgr.updateAppWidget(id, rv);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        // The button intents go straight to PlayerService; nothing to relay
        // here, so only the periodic APPWIDGET_UPDATE reaches this method.
    }

    RemoteViews build(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String title = p.getString(K_TITLE, "");
        String sub = p.getString(K_SUB, "");
        boolean playing = p.getBoolean(K_PLAYING, false);

        RemoteViews rv = new RemoteViews(c.getPackageName(), layoutId());
        rv.setTextViewText(R.id.widget_title,
                title.isEmpty() ? c.getString(R.string.widget_idle) : title);
        rv.setTextViewText(R.id.widget_sub,
                sub.isEmpty() ? c.getString(R.string.widget_hint) : sub);
        if (hasControls()) {
            rv.setImageViewResource(R.id.widget_play, playing
                    ? R.drawable.ic_widget_pause
                    : R.drawable.ic_widget_play);
            rv.setInt(R.id.widget_play, "setBackgroundResource", playing
                    ? R.drawable.widget_btn_play_active
                    : R.drawable.widget_btn_play);
            rv.setContentDescription(R.id.widget_play, c.getString(playing
                    ? R.string.widget_stop
                    : R.string.widget_start));
            rv.setOnClickPendingIntent(R.id.widget_prev, pending(c, PlaybackActions.ACTION_PREV, 1));
            rv.setOnClickPendingIntent(R.id.widget_play, pending(c, PlaybackActions.ACTION_TOGGLE, 2));
            rv.setOnClickPendingIntent(R.id.widget_next, pending(c, PlaybackActions.ACTION_NEXT, 3));
        }
        return rv;
    }

    /**
     * Buttons drive the MediaSessionService, not the activity: the widget must
     * work while the UI is closed, and the service owns the player. The intents
     * are immutable, which Android 12+ requires.
     */
    private PendingIntent pending(Context c, String action, int code) {
        return WidgetIntents.INSTANCE.service(c, action, code);
    }

    /**
     * Called by the player whenever the radio state changes. Only radio is
     * shown: a TV channel clears the widget instead of driving it.
     */
    public static void publish(Context c, String title, String sub, boolean playing, boolean isRadio) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putString(K_TITLE, isRadio ? title : "")
                .putString(K_SUB, isRadio ? sub : "")
                .putBoolean(K_PLAYING, isRadio && playing)
                .apply();
        updateAll(c);
    }

    /** Refresh every placed widget after the now-playing state changes. */
    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        RadioWidgetBase[] widgets = {
                new RadioWidgetSmall(), new RadioWidgetMedium(), new RadioWidgetLarge()
        };
        for (RadioWidgetBase w : widgets) {
            ComponentName cn = new ComponentName(c, w.getClass());
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids.length == 0) continue;
            RemoteViews rv = w.build(c);
            for (int id : ids) mgr.updateAppWidget(id, rv);
        }
    }
}
