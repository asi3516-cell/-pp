package com.openhands.tvplayer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openhands.tvplayer.R

/**
 * Host for the tabs: Live TV, Radio, Lists and Favourites.
 *
 * Fragments are added once and then hidden/shown instead of being replaced, so
 * each tab keeps its scroll position and which category was open when you come
 * back to it.
 */
class BottomNavActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bottom_nav)

        bottomNav = findViewById(R.id.bottomNav)
        val nav = bottomNav
        if (savedInstanceState == null) show(MODE_HOME)

        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> show(MODE_HOME)
                R.id.tab_live -> show(ChannelListFragment.MODE_LIVE)
                R.id.tab_radio -> show(ChannelListFragment.MODE_RADIO)
                R.id.tab_lists -> show(ChannelListFragment.MODE_ALL)
                R.id.tab_fav -> show(ChannelListFragment.MODE_FAV)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    /**
     * Opens a tab, optionally narrowed to one category. The home rails use this
     * to jump straight into a category instead of making the user find it again
     * in the list.
     */
    fun openTab(mode: String, group: String?) {
        val item = when (mode) {
            MODE_HOME -> R.id.tab_home
            ChannelListFragment.MODE_RADIO -> R.id.tab_radio
            ChannelListFragment.MODE_ALL -> R.id.tab_lists
            ChannelListFragment.MODE_FAV -> R.id.tab_fav
            else -> R.id.tab_live
        }
        bottomNav.selectedItemId = item
        show(mode)
        if (group != null) {
            supportFragmentManager.findFragmentByTag("tab_$mode")
                ?.let { it as? ChannelListFragment }
                ?.showGroup(group)
        }
    }

    private fun show(mode: String) {
        val tag = "tab_$mode"
        val manager = supportFragmentManager
        val existing = manager.findFragmentByTag(tag)
        manager.commit {
            setReorderingAllowed(true)
            for (fragment in manager.fragments) {
                if (fragment !== existing) hide(fragment)
            }
            if (existing == null) {
                val fragment: Fragment = if (mode == MODE_HOME) {
                    HomeFragment()
                } else {
                    ChannelListFragment.newInstance(mode)
                }
                add(R.id.fragmentHost, fragment, tag)
            } else {
                show(existing)
            }
        }
    }

    companion object {
        const val MODE_HOME = "home"
    }
}
