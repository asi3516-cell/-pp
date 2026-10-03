package com.openhands.tvplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openhands.tvplayer.model.Channel

/**
 * A channel the user added, edited or deleted in the app.
 *
 * The bundled list is read-only, so user changes are kept here and merged on
 * top of it. A row with [deleted] = true hides a bundled channel with the same
 * [baseUrl]; any other row overrides it (or adds a brand new channel).
 */
@Entity(tableName = "user_channels")
data class UserChannelEntity(
    /** Identifies the bundled entry this overrides (its original stream URL). */
    @PrimaryKey val baseUrl: String,
    val name: String,
    val url: String,
    val logo: String?,
    val groupName: String,
    val kind: String,
    val deleted: Boolean,
    /** Null = an edit of a bundled channel; set = a channel the user added. */
    val added: Boolean,
    val updatedAt: Long
) {
    fun toChannel(): Channel = Channel(
        name = name.ifBlank { "Kanal" },
        url = url,
        logo = logo?.takeIf { it.isNotBlank() },
        group = groupName.ifBlank { "Genel" },
        source = if (added) "user" else null,
        kind = kind.ifBlank { "live" }
    )

    companion object {
        fun added(ch: Channel): UserChannelEntity = UserChannelEntity(
            baseUrl = ch.url,
            name = ch.name,
            url = ch.url,
            logo = ch.logo,
            groupName = ch.group,
            kind = ch.kind,
            deleted = false,
            added = true,
            updatedAt = System.currentTimeMillis()
        )

        fun edited(original: Channel, name: String, url: String, group: String, logo: String?):
            UserChannelEntity = UserChannelEntity(
            baseUrl = original.url,
            name = name,
            url = url,
            logo = logo?.takeIf { it.isNotBlank() },
            groupName = group,
            kind = original.kind,
            deleted = false,
            added = original.source == "user",
            updatedAt = System.currentTimeMillis()
        )

        fun deleted(ch: Channel): UserChannelEntity = UserChannelEntity(
            baseUrl = ch.url,
            name = ch.name,
            url = ch.url,
            logo = ch.logo,
            groupName = ch.group,
            kind = ch.kind,
            deleted = true,
            added = ch.source == "user",
            updatedAt = System.currentTimeMillis()
        )
    }
}
