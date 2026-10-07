package com.openhands.tvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.ChannelRepository
import com.openhands.tvplayer.data.FavDatabase
import com.openhands.tvplayer.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One rail on the home screen: a title and the channels it previews. */
data class HomeSection(
    val title: String,
    val preview: List<Channel>,
    val full: List<Channel>,
    val group: String?
)

/**
 * Home tab. A vertical list of category rails, each rail a horizontal strip of
 * channel cards. Tapping a card opens the player with the whole rail as its
 * playlist, so next/previous move along the rail.
 */
class HomeFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var adapter: HomeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.homeList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.setHasFixedSize(true)
        adapter = HomeAdapter(
            onChannelClick = { channels, index -> openPlayer(channels, index) },
            onSectionClick = { section -> openSection(section) }
        )
        list.adapter = adapter
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { ChannelRepository.all(requireContext()) }
            val favourites = withContext(Dispatchers.IO) {
                FavDatabase.get(requireContext()).favDao().favouriteUrls().toSet()
            }
            adapter.submit(buildSections(all, favourites))
        }
    }

    private fun buildSections(all: List<Channel>, favourites: Set<String>): List<HomeSection> {
        val sections = ArrayList<HomeSection>()
        val live = all.filter { !it.isRadio }
        val radio = all.filter { it.isRadio }

        // Favourites come first when there are any, because that is the rail a
        // returning user actually wants.
        if (favourites.isNotEmpty()) {
            val favs = all.filter { it.url in favourites }
            if (favs.isNotEmpty()) {
                sections += HomeSection(getString(R.string.tab_fav), favs.take(PREVIEW), favs, null)
            }
        }

        if (live.isNotEmpty()) {
            sections += HomeSection(getString(R.string.tab_live), live.take(PREVIEW), live, null)
        }
        // Category rails in broadcaster order, minus the non-working bucket,
        // which exists for testing rather than browsing.
        ChannelRepository.grouped(live).forEach { (group, items) ->
            if (group != BAD_GROUP && sections.size < MAX_SECTIONS) {
                sections += HomeSection(group, items.take(PREVIEW), items, group)
            }
        }
        if (radio.isNotEmpty()) {
            sections += HomeSection(
                getString(R.string.tab_radio), radio.take(PREVIEW), radio, RADIO_GROUP
            )
        }
        return sections
    }

    private fun openPlayer(channels: List<Channel>, index: Int) {
        val clicked = channels.getOrNull(index) ?: return
        // The mode has to follow the channel, not the tab the rail lives on:
        // a radio in the Radio rail must open as radio, or the player resolves
        // it against the live list and ends up showing TRT 1 instead.
        val mode = if (clicked.isRadio) ChannelListFragment.MODE_RADIO
        else ChannelListFragment.MODE_LIVE
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_MODE, mode)
                .putExtra(PlayerActivity.EXTRA_GROUP, clicked.group)
                .putExtra(PlayerActivity.EXTRA_URL, clicked.url)
        )
    }

    /** Opens the matching tab, optionally narrowed to one category. */
    private fun openSection(section: HomeSection) {
        val mode = when (section.group) {
            RADIO_GROUP -> ChannelListFragment.MODE_RADIO
            null -> ChannelListFragment.MODE_ALL
            else -> ChannelListFragment.MODE_LIVE
        }
        (activity as? BottomNavActivity)?.openTab(mode, section.group)
    }

    companion object {
        private const val PREVIEW = 20
        private const val MAX_SECTIONS = 12
        private const val BAD_GROUP = "Calismayanlar"
        private const val RADIO_GROUP = "__radio__"
    }
}
