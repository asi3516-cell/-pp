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

/**
 * TreeView adapter. Groups are categories, children are the channels inside
 * them; ExpandableListView handles the expand/collapse animation.
 */
class ExpandableChannelAdapter(
    private val context: Context,
    private val onChannelClick: (List<Channel>, Int) -> Unit,
    private val onFavouriteToggle: (Channel, Boolean) -> Unit,
    private val onChannelLongClick: (Channel) -> Unit
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
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_group_item, parent, false)
        val name = groups[groupPosition]
        view.findViewById<TextView>(R.id.groupName).text = name
        view.findViewById<TextView>(R.id.groupCount).text =
            (children[name]?.size ?: 0).toString()
        view.findViewById<ImageView>(R.id.groupChevron).animate()
            .rotation(if (isExpanded) 90f else 0f)
            .setDuration(150)
            .start()
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
        view.findViewById<TextView>(R.id.childSub).text = channel.group
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

        view.setOnClickListener {
            val list = children[groups[groupPosition]] ?: return@setOnClickListener
            onChannelClick(list, childPosition)
        }
        view.setOnLongClickListener {
            onChannelLongClick(channel)
            true
        }
        return view
    }
}
