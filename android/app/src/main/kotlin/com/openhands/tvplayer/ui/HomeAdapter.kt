package com.openhands.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openhands.tvplayer.R
import com.openhands.tvplayer.model.Channel

/** Vertical list of home rails; each rail owns a horizontal channel row. */
class HomeAdapter(
    private val onChannelClick: (List<Channel>, Int) -> Unit,
    private val onSectionClick: (HomeSection) -> Unit
) : RecyclerView.Adapter<HomeAdapter.SectionHolder>() {

    private val sections = ArrayList<HomeSection>()

    fun submit(items: List<HomeSection>) {
        sections.clear()
        sections.addAll(items)
        notifyDataSetChanged()
    }

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sectionTitle)
        val count: TextView = view.findViewById(R.id.sectionCount)
        val all: TextView = view.findViewById(R.id.sectionAll)
        val row: RecyclerView = view.findViewById(R.id.sectionRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_section_item, parent, false)
        return SectionHolder(view)
    }

    override fun onBindViewHolder(holder: SectionHolder, position: Int) {
        val section = sections[position]
        holder.title.text = section.title
        holder.count.text = section.full.size.toString()
        holder.row.layoutManager =
            LinearLayoutManager(holder.itemView.context, RecyclerView.HORIZONTAL, false)
        holder.row.adapter = HomeChannelAdapter(section.preview) { index ->
            onChannelClick(section.preview, index)
        }
        val open = View.OnClickListener { onSectionClick(section) }
        holder.title.setOnClickListener(open)
        holder.count.setOnClickListener(open)
        holder.all.setOnClickListener(open)
    }

    override fun getItemCount(): Int = sections.size
}
