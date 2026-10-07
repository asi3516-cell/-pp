package com.openhands.tvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.openhands.tvplayer.R
import com.openhands.tvplayer.data.ChannelRepository
import com.openhands.tvplayer.data.FavDatabase
import com.openhands.tvplayer.data.FavEntity
import com.openhands.tvplayer.data.GroupStore
import com.openhands.tvplayer.data.UserChannelEntity
import com.openhands.tvplayer.data.UserChannelStore
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
    private lateinit var addButton: ImageButton
    private lateinit var emptyAddButton: Button
    private lateinit var adapter: ExpandableChannelAdapter
    private lateinit var liveBadge: TextView
    private lateinit var statChannels: TextView
    private lateinit var statLive: TextView
    private lateinit var statFav: TextView
    private lateinit var chipRow: LinearLayout

    private var allChannels: List<Channel> = emptyList()
    private var query: String = ""
    private var activeGroup: String? = null
    private var favouriteUrls: Set<String> = emptySet()
    private var hiddenGroups: Set<String> = emptySet()
    private val db by lazy { FavDatabase.get(requireContext()) }
    private val dao by lazy { db.favDao() }
    private val userDao by lazy { db.userChannelDao() }

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
        addButton = view.findViewById(R.id.addChannel)
        emptyAddButton = view.findViewById(R.id.emptyAddButton)
        liveBadge = view.findViewById(R.id.liveBadge)
        statChannels = view.findViewById(R.id.statChannels)
        statLive = view.findViewById(R.id.statLive)
        statFav = view.findViewById(R.id.statFav)
        chipRow = view.findViewById(R.id.chipRow)
        val search = view.findViewById<EditText>(R.id.searchInput)
        search.hint = getString(R.string.search_hint)

        headerTitle.setText(
            when (mode) {
                MODE_RADIO -> R.string.tab_radio
                MODE_ALL -> R.string.tab_lists
                MODE_FAV -> R.string.tab_fav
                else -> R.string.tab_live
            }
        )
        headerSub.text = getString(R.string.hero_tagline)
        // The red CANLI badge only belongs on the live and radio tabs.
        liveBadge.isVisible = mode == MODE_LIVE || mode == MODE_RADIO

        adapter = ExpandableChannelAdapter(
            context = requireContext(),
            onChannelClick = { channels, index -> openPlayer(channels, index) },
            onFavouriteToggle = { channel, makeFav -> toggleFavourite(channel, makeFav) },
            onChannelLongClick = { channel -> showChannelOptions(channel) },
            onGroupToggle = { groupPosition -> toggleGroup(groupPosition) },
            onGroupLongClick = { group -> confirmDeleteGroup(group) }
        )
        listView.setAdapter(adapter)
        // Let the D-pad move focus onto the individual rows instead of stopping
        // on the list itself, which is what a remote control needs.
        listView.setItemsCanFocus(true)

        addButton.setOnClickListener { showChannelEditor(null) }
        emptyAddButton.setOnClickListener { showChannelEditor(null) }
        addButton.isVisible = mode != MODE_FAV && mode != MODE_ALL
        // The Lists tab is where removed categories are brought back.
        val restoreButton = view.findViewById<ImageButton>(R.id.restoreGroups)
        restoreButton.isVisible = mode == MODE_ALL
        restoreButton.setOnClickListener { showHiddenGroups() }

        // One category open at a time, so the tree stays readable on a phone.
        listView.setOnGroupClickListener { _, _, groupPosition, _ ->
            collapseOthers(groupPosition)
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
        observeUserChannels()
        observeFavourites()
    }

    override fun onResume() {
        super.onResume()
        refreshNowPlaying()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshNowPlaying()
    }

    /** Re-binds the list so the "ÇALIYOR" badge follows the current channel. */
    private fun refreshNowPlaying() {
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                when (mode) {
                    MODE_RADIO -> UserChannelStore.all(requireContext(), userDao).filter { it.isRadio }
                    MODE_ALL -> UserChannelStore.all(requireContext(), userDao)
                    MODE_FAV -> emptyList()
                    else -> UserChannelStore.all(requireContext(), userDao).filter { !it.isRadio }
                }
            }
            allChannels = list
            hiddenGroups = withContext(Dispatchers.IO) { GroupStore.hidden(requireContext()) }
            render()
        }
    }

    /** Long press on a category header: offer to remove the whole group. */
    private fun confirmDeleteGroup(group: String) {
        val count = allChannels.count { it.group == group }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_group_title, group))
            .setMessage(getString(R.string.delete_group_message, count))
            .setPositiveButton(R.string.delete) { _, _ -> deleteGroup(group) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteGroup(group: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            GroupStore.hide(requireContext(), group)
        }
        hiddenGroups = hiddenGroups + group
        if (activeGroup == group) activeGroup = null
        render()
        toast(R.string.group_deleted)
    }

    /** Brings every removed group back, offered on the Lists tab. */
    private fun restoreAllGroups() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            GroupStore.restoreAll(requireContext())
        }
        hiddenGroups = emptySet()
        render()
        toast(R.string.groups_restored)
    }

    private fun showHiddenGroups() {
        val hidden = hiddenGroups.toList()
        if (hidden.isEmpty()) {
            toast(R.string.no_hidden_groups)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.hidden_groups_title)
            .setItems(hidden.toTypedArray()) { _, which ->
                val group = hidden[which]
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    GroupStore.restore(requireContext(), group)
                }
                hiddenGroups = hiddenGroups - group
                render()
                toast(R.string.group_restored)
            }
            .setPositiveButton(R.string.restore_all) { _, _ -> restoreAllGroups() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeUserChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            userDao.observeAll().collect {
                if (mode != MODE_FAV) loadChannels()
            }
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

    /** Long press: offer to edit or delete the tapped channel. */
    private fun showChannelOptions(channel: Channel) {
        val actions = arrayOf(getString(R.string.edit), getString(R.string.delete))
        AlertDialog.Builder(requireContext())
            .setTitle(channel.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showChannelEditor(channel)
                    1 -> confirmDelete(channel)
                }
            }
            .show()
    }

    /** Opens the add (channel == null) or edit dialog. */
    private fun showChannelEditor(channel: Channel?) {
        val view = layoutInflater.inflate(R.layout.dialog_channel_edit, null)
        val title = view.findViewById<TextView>(R.id.dialogTitle)
        val nameInput = view.findViewById<EditText>(R.id.inputName)
        val urlInput = view.findViewById<EditText>(R.id.inputUrl)
        val groupInput = view.findViewById<EditText>(R.id.inputGroup)
        val logoInput = view.findViewById<EditText>(R.id.inputLogo)
        val errorText = view.findViewById<TextView>(R.id.errorText)

        title.setText(if (channel == null) R.string.add_channel else R.string.edit_channel)
        nameInput.setText(channel?.name.orEmpty())
        urlInput.setText(channel?.url.orEmpty())
        groupInput.setText(channel?.group.orEmpty())
        logoInput.setText(channel?.logo.orEmpty())

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        // Validate by hand so the dialog stays open on a bad address.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            val group = groupInput.text.toString().trim().ifBlank { "Genel" }
            val logo = logoInput.text.toString().trim().takeIf { it.isNotEmpty() }

            val error = when {
                name.isEmpty() -> getString(R.string.error_name_required)
                !isValidAddress(url) -> getString(R.string.error_address_required)
                else -> null
            }
            if (error != null) {
                errorText.text = error
                errorText.isVisible = true
                return@setOnClickListener
            }

            saveChannel(channel, name, url, group, logo)
            dialog.dismiss()
        }
    }

    private fun isValidAddress(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) && url.length > 8

    private fun saveChannel(channel: Channel?, name: String, url: String, group: String, logo: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val row = if (channel == null) {
                UserChannelEntity.added(
                    Channel(name = name, url = url, logo = logo, group = group,
                        source = "user", kind = if (mode == MODE_RADIO) "radio" else "live")
                )
            } else {
                val base = withContext(Dispatchers.IO) { resolveBaseUrl(channel) }
                UserChannelEntity.edited(base, channel, name, url, group, logo)
            }
            withContext(Dispatchers.IO) { userDao.upsert(row) }
            toast(if (channel == null) R.string.channel_added else R.string.channel_saved)
        }
    }

    private fun confirmDelete(channel: Channel) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_channel_title)
            .setMessage(getString(R.string.delete_channel_message, channel.name))
            .setPositiveButton(R.string.delete) { _, _ -> deleteChannel(channel) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteChannel(channel: Channel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val base = withContext(Dispatchers.IO) { resolveBaseUrl(channel) }
            withContext(Dispatchers.IO) {
                if (UserChannelStore.isUserAdded(channel)) {
                    // No bundled row to fall back to: drop it outright.
                    userDao.remove(base)
                } else {
                    userDao.upsert(UserChannelEntity.deleted(base, channel))
                }
            }
            toast(R.string.channel_deleted)
        }
    }

    /**
     * The row key a channel maps to. After an edit the stream URL has already
     * changed, so fall back to the user row that still carries the new URL and
     * read its original key from there.
     */
    private suspend fun resolveBaseUrl(channel: Channel): String {
        ChannelRepository.all(requireContext()).firstOrNull { it.url == channel.url }
            ?.let { return it.url }
        return userDao.all().firstOrNull { it.url == channel.url }?.baseUrl ?: channel.url
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * Narrows the list to one category. Used when a home rail header is tapped,
     * so the user lands in that category rather than at the top of everything.
     */
    fun showGroup(group: String) {
        activeGroup = group
        if (isAdded) render()
    }

    private fun render() {
        val visible = allChannels.filter { it.group !in hiddenGroups }
        val searched = if (query.isBlank()) visible else visible.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.group.contains(query, ignoreCase = true)
        }
        val filtered = activeGroup?.let { g -> searched.filter { it.group == g } } ?: searched
        if (activeGroup != null) {
            // A tapped category becomes a plain open list under the chips; the
            // tree header would just repeat the chip the user already pressed.
            adapter.submitFlat(filtered)
            listView.expandGroup(0)
        } else {
            adapter.submit(ChannelRepository.grouped(filtered))
        }
        updateStats()
        buildChips(searched)

        val empty = filtered.isEmpty()
        emptyState.isVisible = empty
        listView.isVisible = !empty
        emptyAddButton.isVisible = empty && mode != MODE_FAV && mode != MODE_ALL
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

    /** Fills the three header pills with the current tab's numbers. */
    private fun updateStats() {
        // Count only what is actually visible, so the pills match the list
        // after a category has been removed.
        val visible = allChannels.filter { it.group !in hiddenGroups }
        val live = visible.count { !it.isRadio }
        val radio = visible.count { it.isRadio }
        val shown = if (mode == MODE_RADIO) radio else live
        statChannels.text = getString(R.string.stat_channels, shown)
        statLive.text = getString(R.string.stat_live, live)
        statFav.text = getString(R.string.stat_fav, favouriteUrls.size)
    }

    /**
     * Rebuilds the horizontal category strip. "Tümü" clears the filter; tapping
     * any other chip narrows the list to that category.
     */
    private fun buildChips(source: List<Channel>) {
        val counts = LinkedHashMap<String, Int>()
        source.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        chipRow.removeAllViews()
        chipRow.addView(makeChip(getString(R.string.all_categories), activeGroup == null) {
            activeGroup = null
            render()
        })
        counts.forEach { (group, count) ->
            chipRow.addView(makeChip("$group  $count", activeGroup == group) {
                activeGroup = if (activeGroup == group) null else group
                render()
            })
        }
    }

    private fun makeChip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = label
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFC6D2E6.toInt())
            textSize = 12.5f
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(),
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            setPadding(38, 18, 38, 18)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 22 }
            setOnClickListener { onClick() }
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

    /** Closes every category except [groupPosition], keeping one open at a time. */
    private fun collapseOthers(groupPosition: Int) {
        for (i in 0 until adapter.groupCount) {
            if (i != groupPosition) listView.collapseGroup(i)
        }
    }

    /** D-pad activation of a category: expand it, or close it if already open. */
    private fun toggleGroup(groupPosition: Int) {
        if (listView.isGroupExpanded(groupPosition)) {
            listView.collapseGroup(groupPosition)
        } else {
            collapseOthers(groupPosition)
            listView.expandGroup(groupPosition)
        }
    }

    private fun openPlayer(channels: List<Channel>, index: Int) {
        val clicked = channels.getOrNull(index) ?: return
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_MODE, mode)
                .putExtra(PlayerActivity.EXTRA_GROUP, clicked.group)
                .putExtra(PlayerActivity.EXTRA_URL, clicked.url)
        )
    }

    companion object {
        const val MODE_LIVE = "live"
        const val MODE_RADIO = "radio"
        const val MODE_ALL = "all"
        const val MODE_FAV = "fav"
        private const val ARG_MODE = "mode"

        fun newInstance(mode: String): ChannelListFragment = ChannelListFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode) }
        }
    }
}
