package com.openhands.tvplayer;

/** Medium widget: station name plus previous / play-pause / next controls. */
public class RadioWidgetMedium extends RadioWidgetBase {
    @Override protected int layoutId() { return R.layout.widget_radio_medium; }
    @Override protected boolean hasControls() { return true; }
}
