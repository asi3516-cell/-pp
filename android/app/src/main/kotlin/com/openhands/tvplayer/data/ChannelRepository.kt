package com.openhands.tvplayer.data

import android.content.Context
import com.openhands.tvplayer.model.Channel
import org.json.JSONArray

/**
 * Reads the channel list bundled in the APK assets. The list is parsed once and
 * kept in memory: it is small, read-only and must be available with no network
 * and no storage permission.
 */
object ChannelRepository {

    private const val ASSET = "channels.json"

    @Volatile
    private var cache: List<Channel>? = null

    fun all(context: Context): List<Channel> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val parsed = parse(context)
            cache = parsed
            return parsed
        }
    }

    fun live(context: Context): List<Channel> = all(context).filter { !it.isRadio }

    fun radio(context: Context): List<Channel> = all(context).filter { it.isRadio }

    /**
     * Groups channels by category, preserving the order they appear in the
     * bundled list. That order is the broadcaster importance ranking (TRT 1,
     * Show TV, Star TV, Kanal 7, ATV ...), so sorting here would throw the
     * ranking away. Categories keep their first-seen order too, and an "Hepsi"
     * (All) group is pinned to the top.
     */
    fun grouped(channels: List<Channel>): LinkedHashMap<String, List<Channel>> {
        if (channels.isEmpty()) return LinkedHashMap()

        val byGroup = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in channels) {
            val key = ch.group.ifBlank { "Genel" }
            byGroup.getOrPut(key) { mutableListOf() }.add(ch)
        }

        val out = LinkedHashMap<String, List<Channel>>()
        out[ALL_GROUP] = channels
        for ((key, list) in byGroup) out[key] = list
        return out
    }

    /** The synthetic "show everything" group pinned to the top of every list. */
    const val ALL_GROUP = "Hepsi"

    private fun parse(context: Context): List<Channel> {
        val text = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Channel>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                val urls = ArrayList<Channel.Stream>()
                o.optJSONArray("urls")?.let { ua ->
                    for (j in 0 until ua.length()) {
                        val u = ua.optJSONObject(j) ?: continue
                        val su = u.optString("url")
                        if (su.isNotBlank()) urls.add(Channel.Stream(su, u.optString("label")))
                    }
                }
                out.add(
                    Channel(
                        name = o.optString("name").ifBlank { "Kanal" },
                        url = url,
                        logo = o.optString("logo").takeIf { it.isNotBlank() },
                        group = o.optString("group").ifBlank { "Genel" },
                        source = o.optString("source").takeIf { it.isNotBlank() },
                        kind = o.optString("kind").ifBlank { "live" },
                        urls = urls
                    )
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }
}
