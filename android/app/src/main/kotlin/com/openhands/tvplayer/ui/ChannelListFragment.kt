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

    private var allChannels: List<Channel> = emptyList()
    private var query: String = ""
    private var favouriteUrls: Set<String> = emptySet()
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

        adapter = ExpandableChannelAdapter(
            context = requireContext(),
            onChannelClick = { channels, index -> openPlayer(channels, index) },
            onFavouriteToggle = { channel, makeFav -> toggleFavourite(channel, makeFav) },
            onChannelLongClick = { channel -> showChannelOptions(channel) }
        )
        listView.setAdapter(adapter)

        addButton.setOnClickListener { showChannelEditor(null) }
        emptyAddButton.setOnClickListener { showChannelEditor(null) }
        addButton.isVisible = mode != MODE_FAV && mode != MODE_ALL

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
        observeUserChannels()
        observeFavourites()
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
            render()
        }
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

    private fun render() {
        val filtered = if (query.isBlank()) allChannels else allChannels.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.group.contains(query, ignoreCase = true)
        }
        adapter.submit(ChannelRepository.grouped(filtered))

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
