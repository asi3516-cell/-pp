package com.openhands.tvplayer;

/** Smallest widget: station name plus a single play/pause button. */
public class RadioWidgetSmall extends RadioWidgetBase {
    @Override protected int layoutId() { return R.layout.widget_radio_small; }
    @Override protected boolean hasControls() { return false; }
}
