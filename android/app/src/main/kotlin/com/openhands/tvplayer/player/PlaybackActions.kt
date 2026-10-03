package com.openhands.tvplayer.player

/** Intents the widgets and the media notification send to [PlayerService]. */
object PlaybackActions {
    const val ACTION_PLAY = "com.openhands.tvplayer.action.PLAY"
    const val ACTION_PAUSE = "com.openhands.tvplayer.action.PAUSE"
    const val ACTION_TOGGLE = "com.openhands.tvplayer.action.TOGGLE"
    const val ACTION_NEXT = "com.openhands.tvplayer.action.NEXT"
    const val ACTION_PREV = "com.openhands.tvplayer.action.PREV"
    const val ACTION_STOP = "com.openhands.tvplayer.action.STOP"

    const val EXTRA_URL = "extra_url"
    const val EXTRA_NAME = "extra_name"
    const val EXTRA_GROUP = "extra_group"
    const val EXTRA_KIND = "extra_kind"
    const val EXTRA_LOGO = "extra_logo"
}
