package com.openhands.tvplayer;

/** Large widget: full controls plus a live badge and the station's category. */
public class RadioWidgetLarge extends RadioWidgetBase {
    @Override protected int layoutId() { return R.layout.widget_radio_large; }
    @Override protected boolean hasControls() { return true; }
    @Override protected boolean hasStop() { return true; }
}
