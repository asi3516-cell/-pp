package com.openhands.tvplayer.data

import android.content.Context

/**
 * Remembers the categories the user removed. Deleting a group only hides it,
 * so the bundled channel list stays untouched and the group can be brought
 * back later from the Lists tab.
 */
object GroupStore {

    private const val PREFS = "tv_player_groups"
    private const val KEY = "hidden_groups"

    fun hidden(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun hide(context: Context, group: String) = update(context) { it + group }

    fun restore(context: Context, group: String) = update(context) { it - group }

    fun restoreAll(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun update(context: Context, change: (Set<String>) -> Set<String>) {
        val current = hidden(context)
        prefs(context).edit().putStringSet(KEY, change(current)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
