package com.openhands.tvplayer;

/** Smallest widget: station name only, tapping opens the player. */
public class RadioWidgetSmall extends RadioWidgetBase {
    @Override protected int layoutId() { return R.layout.widget_radio_small; }
    @Override protected boolean hasControls() { return false; }
}
