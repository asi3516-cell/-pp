package com.openhands.tvplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.openhands.tvplayer.R
import com.openhands.tvplayer.model.Channel
import com.openhands.tvplayer.player.PlaybackController

/**
 * TreeView adapter. Groups are categories, children are the channels inside
 * them; ExpandableListView handles the expand/collapse animation.
 */
class ExpandableChannelAdapter(
    private val context: Context,
    private val onChannelClick: (List<Channel>, Int) -> Unit,
    private val onFavouriteToggle: (Channel, Boolean) -> Unit,
    private val onChannelLongClick: (Channel) -> Unit,
    private val onGroupToggle: (Int) -> Unit,
    private val onGroupLongClick: (String) -> Unit
) : BaseExpandableListAdapter() {

    private val groups = ArrayList<String>()
    private val children = HashMap<String, List<Channel>>()
    private val favouriteUrls = HashSet<String>()

    fun submit(grouped: LinkedHashMap<String, List<Channel>>) {
        groups.clear()
        children.clear()
        groups.addAll(grouped.keys)
        grouped.forEach { (group, list) -> children[group] = list }
        notifyDataSetChanged()
    }

    /**
     * Shows one category as a flat, always-open list. The category name is
     * already in the chip above, so the group header would be redundant and the
     * channels are placed directly under the chips.
     */
    fun submitFlat(channels: List<Channel>) {
        groups.clear()
        children.clear()
        groups.add(FLAT)
        children[FLAT] = channels
        notifyDataSetChanged()
    }

    fun setFavourites(urls: Collection<String>) {
        favouriteUrls.clear()
        favouriteUrls.addAll(urls)
        notifyDataSetChanged()
    }

    override fun getGroupCount(): Int = groups.size

    override fun getChildrenCount(groupPosition: Int): Int =
        children[groups[groupPosition]]?.size ?: 0

    override fun getGroup(groupPosition: Int): Any = groups[groupPosition]

    override fun getChild(groupPosition: Int, childPosition: Int): Any =
        children[groups[groupPosition]]!![childPosition]

    override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

    override fun getChildId(groupPosition: Int, childPosition: Int): Long = childPosition.toLong()

    override fun hasStableIds(): Boolean = false

    override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true

    override fun getGroupView(
        groupPosition: Int,
        isExpanded: Boolean,
        convertView: View?,
        parent: ViewGroup?
    ): View {
        val name = groups[groupPosition]
        // The flat mode has a single synthetic group that carries the channels
        // with no visible header.
        if (name == FLAT) {
            // Always inflate fresh: a recycled tree header must not leak in.
            return LayoutInflater.from(context)
                .inflate(R.layout.list_flat_header, parent, false)
        }
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_group_item, parent, false)
        view.findViewById<TextView>(R.id.groupName).text = name
        view.findViewById<TextView>(R.id.groupCount).text =
            (children[name]?.size ?: 0).toString()
        view.findViewById<ImageView>(R.id.groupChevron).animate()
            .rotation(if (isExpanded) 90f else 0f)
            .setDuration(150)
            .start()
        // A D-pad "click" on the focused card toggles the category, so TV
        // boxes can open a group without a touchscreen. Touch taps still go
        // through ExpandableListView's own group-click handling.
        view.findViewById<View>(R.id.groupCard).setOnClickListener {
            onGroupToggle(groupPosition)
        }
        view.findViewById<View>(R.id.groupCard).setOnLongClickListener {
            onGroupLongClick(name)
            true
        }
        return view
    }

    override fun getChildView(
        groupPosition: Int,
        childPosition: Int,
        isLastChild: Boolean,
        convertView: View?,
        parent: ViewGroup?
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_child_item, parent, false)
        val channel = children[groups[groupPosition]]!![childPosition]

        view.findViewById<TextView>(R.id.childName).text = channel.name
        view.findViewById<TextView>(R.id.childInitial).text = channel.initial

        val logo = view.findViewById<ImageView>(R.id.childLogo)
        if (channel.logo.isNullOrBlank()) {
            logo.setImageDrawable(null)
        } else {
            Glide.with(context).load(channel.logo).centerInside().into(logo)
        }

        val fav = view.findViewById<ImageButton>(R.id.childFav)
        fav.setImageResource(
            if (favouriteUrls.contains(channel.playUrl)) R.drawable.ic_star_filled
            else R.drawable.ic_star_outline
        )
        fav.contentDescription = context.getString(R.string.favourite)
        fav.setOnClickListener {
            onFavouriteToggle(channel, !favouriteUrls.contains(channel.playUrl))
        }

        // Mark the channel that is currently playing so it is easy to find in a
        // long list. The player lives in a singleton, so read it at bind time.
        val nowPlaying = PlaybackController.currentChannel?.playUrl
        val isPlaying = channel.playUrl == nowPlaying
        view.findViewById<View>(R.id.childNowPlaying).visibility =
            if (isPlaying) View.VISIBLE else View.GONE

        val card = view.findViewById<View>(R.id.rowCard)
        card.setBackgroundResource(
            if (isPlaying) R.drawable.bg_channel_card_focus else R.drawable.bg_channel_card
        )

        // The live dot only makes sense for television; a radio row has no
        // picture to broadcast.
        view.findViewById<View>(R.id.childLiveDot).visibility =
            if (channel.isRadio) View.GONE else View.VISIBLE

        card.setOnClickListener {
            val list = children[groups[groupPosition]] ?: return@setOnClickListener
            onChannelClick(list, childPosition)
        }
        card.setOnLongClickListener {
            onChannelLongClick(channel)
            true
        }
        return view
    }

    companion object {
        /** Synthetic group name that carries a flat, always-open category. */
        private const val FLAT = "\u0000flat"
    }
}
