package com.openhands.tvplayer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openhands.tvplayer.R

/** Host for the three tabs: Live TV, Radio and Favourites. */
class BottomNavActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bottom_nav)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) show(ChannelListFragment.MODE_LIVE)

        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_live -> show(ChannelListFragment.MODE_LIVE)
                R.id.tab_radio -> show(ChannelListFragment.MODE_RADIO)
                R.id.tab_lists -> show(ChannelListFragment.MODE_ALL)
                R.id.tab_fav -> show(ChannelListFragment.MODE_FAV)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    private fun show(mode: String) {
        supportFragmentManager.commit {
            replace(R.id.fragmentHost, ChannelListFragment.newInstance(mode))
        }
    }
}
