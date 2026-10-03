package com.openhands.tvplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openhands.tvplayer.model.Channel

/** A favourite, keyed by stream URL so the same channel never doubles up. */
@Entity(tableName = "favourites")
data class FavEntity(
    @PrimaryKey val url: String,
    val name: String,
    val logo: String?,
    val groupName: String,
    val kind: String,
    val addedAt: Long
) {
    fun toChannel(): Channel = Channel(
        name = name,
        url = url,
        logo = logo,
        group = groupName,
        source = null,
        kind = kind
    )

    companion object {
        fun from(ch: Channel): FavEntity = FavEntity(
            url = ch.playUrl,
            name = ch.name,
            logo = ch.logo,
            groupName = ch.group,
            kind = ch.kind,
            addedAt = System.currentTimeMillis()
        )
    }
}
