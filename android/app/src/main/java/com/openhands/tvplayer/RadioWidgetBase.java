package com.openhands.tvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

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
        String a = intent.getAction();
        if (ACTION_PREV.equals(a)) forward(c, "PREV");
        else if (ACTION_NEXT.equals(a)) forward(c, "NEXT");
        else if (ACTION_PLAY.equals(a)) forward(c, "TOGGLE");
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
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play);
            rv.setOnClickPendingIntent(R.id.widget_prev, pending(c, ACTION_PREV, 1));
            rv.setOnClickPendingIntent(R.id.widget_play, pending(c, ACTION_PLAY, 2));
            rv.setOnClickPendingIntent(R.id.widget_next, pending(c, ACTION_NEXT, 3));
        }
        return rv;
    }

    private PendingIntent pending(Context c, String action, int code) {
        Intent i = new Intent(c, getClass()).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, code, i, flags);
    }

    private void forward(Context c, String what) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction("com.openhands.tvplayer.CMD_" + what);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        c.startActivity(i);
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
