package com.openhands.tvplayer.data

import android.content.Context
import com.openhands.tvplayer.model.Channel

/**
 * The channel list the UI actually shows: the bundled list with the user's own
 * additions, edits and deletions merged on top.
 *
 * Rows in `user_channels` are keyed by the original stream URL. A row hides its
 * bundled channel when [UserChannelEntity.deleted] is set, otherwise it replaces
 * it. Rows the user added have no bundled counterpart and are appended.
 */
object UserChannelStore {

    suspend fun all(context: Context, dao: UserChannelDao): List<Channel> {
        val bundled = ChannelRepository.all(context)
        val rows = dao.all()
        val byBase = HashMap<String, UserChannelEntity>(rows.size)
        for (row in rows) byBase[row.baseUrl] = row

        val out = ArrayList<Channel>(bundled.size + rows.size)
        val bundledUrls = HashSet<String>(bundled.size)
        for (ch in bundled) {
            bundledUrls.add(ch.url)
            val row = byBase[ch.url]
            when {
                row == null -> out.add(ch)
                row.deleted -> Unit
                else -> out.add(row.toChannel())
            }
        }

        // Newest additions first, and only those that do not override a
        // bundled channel (those were already handled above).
        for (row in rows) {
            if (row.added && !row.deleted && row.baseUrl !in bundledUrls) {
                out.add(row.toChannel())
            }
        }
        return out
    }

    /** True when the channel was added by the user (so it can be removed). */
    fun isUserAdded(channel: Channel): Boolean = channel.source == "user"
}
