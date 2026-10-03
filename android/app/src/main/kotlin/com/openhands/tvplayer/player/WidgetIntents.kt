package com.openhands.tvplayer.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Builds the widget button intents. FLAG_IMMUTABLE is required from Android 12
 * on; without it the PendingIntent creation throws.
 */
object WidgetIntents {

    fun service(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, requestCode, intent, flags)
    }
}
