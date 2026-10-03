package com.openhands.tvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.ChannelRepository
import com.openhands.tvplayer.data.FavDatabase
import com.openhands.tvplayer.data.FavEntity
import com.openhands.tvplayer.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One tab of the bottom navigation: live TV, radio or favourites. */
class ChannelListFragment : Fragment() {

    private lateinit var listView: ExpandableListView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var listCount: TextView
    private lateinit var searchClear: ImageButton
    private lateinit var adapter: ExpandableChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var query: String = ""
    private var favouriteUrls: Set<String> = emptySet()
    private val dao by lazy { FavDatabase.get(requireContext()).favDao() }

    private val mode: String get() = arguments?.getString(ARG_MODE) ?: MODE_LIVE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_channel_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView = view.findViewById(R.id.expandableList)
        emptyState = view.findViewById(R.id.emptyState)
        emptyTitle = view.findViewById(R.id.emptyTitle)
        emptyHint = view.findViewById(R.id.emptyHint)
        headerTitle = view.findViewById(R.id.headerTitle)
        headerSub = view.findViewById(R.id.headerSub)
        listCount = view.findViewById(R.id.listCount)
        searchClear = view.findViewById(R.id.searchClear)
        val search = view.findViewById<EditText>(R.id.searchInput)
        search.hint = getString(R.string.search_hint)

        headerTitle.setText(
            when (mode) {
                MODE_RADIO -> R.string.tab_radio
                MODE_FAV -> R.string.tab_fav
                else -> R.string.tab_live
            }
        )
        headerSub.text = getString(R.string.app_name)

        adapter = ExpandableChannelAdapter(
            context = requireContext(),
            onChannelClick = { channels, index -> openPlayer(channels, index) },
            onFavouriteToggle = { channel, makeFav -> toggleFavourite(channel, makeFav) }
        )
        listView.setAdapter(adapter)

        // One category open at a time, so the tree stays readable on a phone.
        listView.setOnGroupClickListener { _, _, groupPosition, _ ->
            for (i in 0 until adapter.groupCount) {
                if (i != groupPosition) listView.collapseGroup(i)
            }
            false
        }

        searchClear.setOnClickListener {
            search.setText("")
            search.clearFocus()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                searchClear.isVisible = query.isNotEmpty()
                render()
            }
        })

        loadChannels()
        observeFavourites()
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                when (mode) {
                    MODE_RADIO -> ChannelRepository.radio(requireContext())
                    MODE_FAV -> emptyList()
                    else -> ChannelRepository.live(requireContext())
                }
            }
            allChannels = list
            render()
        }
    }

    private fun observeFavourites() {
        viewLifecycleOwner.lifecycleScope.launch {
            dao.observeAll().collect { favs ->
                favouriteUrls = favs.map { it.url }.toSet()
                adapter.setFavourites(favouriteUrls)
                if (mode == MODE_FAV) {
                    allChannels = favs.map { it.toChannel() }
                    render()
                }
            }
        }
    }

    private fun toggleFavourite(channel: Channel, makeFavourite: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (makeFavourite) dao.add(FavEntity.from(channel)) else dao.remove(channel.playUrl)
        }
    }

    private fun render() {
        val filtered = if (query.isBlank()) allChannels else allChannels.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.group.contains(query, ignoreCase = true)
        }
        adapter.submit(ChannelRepository.grouped(filtered))

        val empty = filtered.isEmpty()
        emptyState.isVisible = empty
        listView.isVisible = !empty
        if (empty) {
            showEmptyState()
        } else {
            listCount.text = resources.getQuantityString(
                R.plurals.channel_count, filtered.size, filtered.size
            )
        }

        // A search result is easier to scan with the categories already open.
        if (query.isNotBlank()) {
            for (i in 0 until adapter.groupCount) listView.expandGroup(i)
        }
    }

    private fun showEmptyState() {
        when {
            query.isNotBlank() -> {
                emptyTitle.setText(R.string.no_results_title)
                emptyHint.text = getString(R.string.no_results_hint, query)
            }
            mode == MODE_FAV -> {
                emptyTitle.setText(R.string.no_favourites)
                emptyHint.setText(R.string.no_favourites_hint)
            }
            else -> {
                emptyTitle.setText(R.string.no_channels)
                emptyHint.setText(R.string.no_channels_hint)
            }
        }
    }

    private fun openPlayer(channels: List<Channel>, index: Int) {
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_MODE, mode)
                .putExtra(PlayerActivity.EXTRA_GROUP, channels.getOrNull(index)?.group)
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
        )
    }

    companion object {
        const val MODE_LIVE = "live"
        const val MODE_RADIO = "radio"
        const val MODE_FAV = "fav"
        private const val ARG_MODE = "mode"

        fun newInstance(mode: String): ChannelListFragment = ChannelListFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode) }
        }
    }
}
