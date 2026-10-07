package com.openhands.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.openhands.tvplayer.R
import com.openhands.tvplayer.model.Channel

/** Horizontal strip of channel cards inside one home rail. */
class HomeChannelAdapter(
    private val items: List<Channel>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<HomeChannelAdapter.CardHolder>() {

    class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.cardName)
        val meta: TextView = view.findViewById(R.id.cardMeta)
        val initial: TextView = view.findViewById(R.id.cardInitial)
        val logo: ImageView = view.findViewById(R.id.cardLogo)
        val dot: View = view.findViewById(R.id.cardLiveDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_channel_card, parent, false)
        return CardHolder(view)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        val channel = items[position]
        holder.name.text = channel.name
        holder.meta.text = channel.group
        holder.initial.text = channel.initial
        holder.dot.visibility = if (channel.isRadio) View.GONE else View.VISIBLE
        if (channel.logo.isNullOrBlank()) {
            holder.logo.setImageDrawable(null)
        } else {
            Glide.with(holder.itemView).load(channel.logo).centerInside().into(holder.logo)
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size
}
