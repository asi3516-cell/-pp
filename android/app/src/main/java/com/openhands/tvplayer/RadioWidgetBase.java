package com.openhands.tvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

import com.openhands.tvplayer.player.PlaybackActions;
import com.openhands.tvplayer.player.WidgetIntents;

/**
 * Home-screen radio widget. It shows the station that is playing and, for the
 * medium and large sizes, drives playback through the MediaSessionService so
 * the buttons keep working while the app is closed.
 *
 * Playback state is published by the player into SharedPreferences and read
 * back here on every update, so all three sizes stay in sync.
 */
public abstract class RadioWidgetBase extends AppWidgetProvider {

    static final String PREFS = "tv_widget";
    static final String K_TITLE = "title";
    static final String K_SUB = "sub";
    static final String K_PLAYING = "playing";

    protected abstract int layoutId();

    /** Whether this size carries the prev / play / next (and stop) buttons. */
    protected abstract boolean hasControls();

    /** Whether this size carries the stop button. */
    protected boolean hasStop() { return false; }

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(c));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        // Button presses are PendingIntents aimed straight at PlayerService;
        // only APPWIDGET_UPDATE reaches this provider.
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

        // Tapping the card itself opens the app on the radio tab.
        rv.setOnClickPendingIntent(R.id.widget_root, openApp(c));

        if (hasControls()) {
            rv.setViewVisibility(R.id.widget_live, playing ? View.VISIBLE : View.GONE);
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
        } else {
            // The compact size has only the play button.
            rv.setImageViewResource(R.id.widget_play, playing
                    ? R.drawable.ic_widget_pause
                    : R.drawable.ic_widget_play);
            rv.setInt(R.id.widget_play, "setBackgroundResource", playing
                    ? R.drawable.widget_btn_play_active
                    : R.drawable.widget_btn_play);
            rv.setOnClickPendingIntent(R.id.widget_play, pending(c, PlaybackActions.ACTION_TOGGLE, 2));
        }
        return rv;
    }

    /** Opens the app on the radio tab, so a tap on the card shows the station. */
    private PendingIntent openApp(Context c) {
        Intent intent = new Intent(c, com.openhands.tvplayer.ui.BottomNavActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 9, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Buttons drive the MediaSessionService, not the activity: the widget must
     * work while the UI is closed and the service owns the player. The intents
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
        RadioWidgetSmall w = new RadioWidgetSmall();
        ComponentName cn = new ComponentName(c, w.getClass());
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids.length == 0) return;
        RemoteViews rv = w.build(c);
        for (int id : ids) mgr.updateAppWidget(id, rv);
    }
}
