package com.play.tube.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.play.tube.R
import com.play.tube.databinding.ActivityLibraryBinding
import com.play.tube.util.Themes

/**
 * Library page (history, favorites, subscriptions, playlists), opened from the
 * Menu. Downloads have their own bottom-nav tab.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.play.tube.ui.common.MiniBar.attach(this)

        binding.backButton.setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment.newInstance(downloadsOnly = false))
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        Themes.recreateIfNeeded(this)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LibraryActivity::class.java))
        }
    }
}
