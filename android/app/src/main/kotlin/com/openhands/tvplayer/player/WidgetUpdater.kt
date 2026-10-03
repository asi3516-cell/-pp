package com.openhands.tvplayer.player

import android.content.Context
import com.openhands.tvplayer.RadioWidgetBase

/** Pushes the current radio state to the home-screen widgets. */
object WidgetUpdater {

    fun refresh(context: Context) {
        val player = PlaybackController.existingPlayer()
        val ch = PlaybackController.currentChannel
        val radio = ch != null && ch.isRadio && player != null
        val title = if (radio) ch!!.name else ""
        val sub = if (radio) ch!!.group else ""
        val playing = radio && player!!.isPlaying
        RadioWidgetBase.publish(context.applicationContext, title, sub, playing, radio)
    }
}
