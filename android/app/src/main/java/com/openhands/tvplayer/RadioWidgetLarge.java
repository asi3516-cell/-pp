package com.openhands.tvplayer;

/** Large widget: full controls plus the station's category as a subtitle. */
public class RadioWidgetLarge extends RadioWidgetBase {
    @Override protected int layoutId() { return R.layout.widget_radio_large; }
    @Override protected boolean hasControls() { return true; }
}
