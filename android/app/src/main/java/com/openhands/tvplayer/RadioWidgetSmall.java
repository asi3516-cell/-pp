package com.openhands.tvplayer;

/** Radio widget: station name plus prev / play-pause / next controls. */
public class RadioWidgetSmall extends RadioWidgetBase {
    @Override protected int layoutId() { return R.layout.widget_radio_small; }
    @Override protected boolean hasControls() { return true; }
}
