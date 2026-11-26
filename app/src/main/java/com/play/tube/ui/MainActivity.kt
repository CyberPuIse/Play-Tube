package com.play.tube.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.play.tube.BuildConfig
import com.play.tube.R
import com.play.tube.databinding.ActivityMainBinding
import com.play.tube.playback.PlaybackCenter
import com.play.tube.ui.home.HomeFragment
import com.play.tube.ui.library.LibraryFragment
import com.play.tube.ui.menu.MenuFragment
import com.play.tube.ui.music.MusicFragment
import com.play.tube.ui.music.NowPlayingActivity
import com.play.tube.ui.player.PlayerActivity
import com.play.tube.ui.subscriptions.SubscriptionsFragment
import com.play.tube.util.Net
import com.play.tube.util.AppPrefs
import com.play.tube.util.Themes
import com.play.tube.util.UpdateChecker
import com.play.tube.util.Thumbs
import coil.load
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedId: Int = R.id.navHome
    private val fragments = mutableMapOf<Int, Fragment>()
    private var lastBackPress = 0L

    private val progressHandler = Handler(Looper.getMainLooper())

    // ----- Offline handling -----
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var offlineDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var offlineShown = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: com.play.tube.playback.QueueEntry?) = updateMiniPlayer()

        override fun onPlaybackStateChanged(isPlaying: Boolean) = updateMiniPlayer()

        override fun onQueueChanged() = updateMiniPlayer()

        override fun onAudioOnlyChanged(audioOnly: Boolean) = updateMiniPlayer()

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = updateMiniPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            selectedId = savedInstanceState.getInt(STATE_SELECTED, R.id.navHome)
                .takeIf { it in NAV_IDS } ?: R.id.navHome
            // Re-attach fragments that survived a configuration change.
            NAV_IDS.forEach { id ->
                supportFragmentManager.findFragmentByTag(tagOf(id))?.let {
                    fragments[id] = it
                }
            }
        }

        requestNotificationPermissionIfNeeded()
        silentlyCheckForUpdate()

        binding.navHome.setOnClickListener { onNavTap(R.id.navHome, it) }
        binding.navMusic.setOnClickListener { onNavTap(R.id.navMusic, it) }
        binding.navSubs.setOnClickListener { onNavTap(R.id.navSubs, it) }
        binding.navLibrary.setOnClickListener { onNavTap(R.id.navLibrary, it) }
        binding.navMenu.setOnClickListener { onNavTap(R.id.navMenu, it) }

        // Rounded corners for the live video / artwork box of the mini player.
        binding.miniMedia.clipToOutline = true
        binding.miniMedia.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 12f * resources.displayMetrics.density)
            }
        }

        binding.miniPlayPause.setOnClickListener { PlaybackCenter.togglePlayPause() }
        binding.miniClose.setOnClickListener {
            PlaybackCenter.stopPlayback()
        }
        binding.miniPlayer.setOnClickListener {
            val entry = PlaybackCenter.currentEntry ?: return@setOnClickListener
            if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) {
                NowPlayingActivity.start(this)
            } else {
                PlayerActivity.startResume(this)
            }
        }

        applySelection()
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) showDownloads()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) showDownloads()
    }

    private fun showDownloads() {
        select(R.id.navLibrary)
        supportFragmentManager.executePendingTransactions()
        (fragments[R.id.navLibrary] as? LibraryFragment)?.showDownloadsTab()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, selectedId)
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were paused: re-style ourselves.
        if (Themes.recreateIfNeeded(this)) return
        PlaybackCenter.addListener(playbackListener)
        bindMiniPlayer()
        progressHandler.removeCallbacksAndMessages(null)
        progressHandler.post(progressTick)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (binding.miniPlayer.isVisible) {
                val dur = PlaybackCenter.durationMs
                binding.miniProgress.progress =
                    if (dur > 0) (PlaybackCenter.positionMs * 1000 / dur).toInt().coerceIn(0, 1000) else 0
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
        if (!Net.isOnline(this)) showOfflinePopup()
        silentlyCheckForUpdate()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        uiHandler.removeCallbacksAndMessages(null)
        offlineDialog?.dismiss()
        offlineDialog = null
        super.onStop()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { handleBackOnline() }
            }

            override fun onLost(network: Network) {
                // Wi-Fi <-> mobile hand-over briefly loses a network: re-check after a moment.
                uiHandler.postDelayed({
                    if (!isFinishing && !isDestroyed && !Net.isOnline(this@MainActivity)) {
                        showOfflinePopup()
                        // Lost connection while browsing: swap stale feeds for skeletons.
                        (fragments[R.id.navHome] as? HomeFragment)?.onWentOffline()
                        (fragments[R.id.navSubs] as? SubscriptionsFragment)?.onWentOffline()
                    }
                }, 1500L)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            // No connectivity monitoring: the app still works, just without the popup.
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Already unregistered.
        }
    }

    /** YouTube-style: tell the user they are offline and offer their downloaded videos. */
    private fun showOfflinePopup() {
        if (isFinishing || isDestroyed || offlineShown) return
        offlineShown = true
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_offline, null)
        sheet.setContentView(content)
        // Let the rounded sheet background show instead of the default panel.
        (content.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        content.findViewById<View>(R.id.offlineViewDownloads).setOnClickListener {
            sheet.dismiss()
            showDownloads()
        }
        content.findViewById<View>(R.id.offlineDismiss).setOnClickListener { sheet.dismiss() }
        sheet.setOnDismissListener { if (offlineDialog === sheet) offlineDialog = null }
        offlineDialog = sheet
        sheet.show()
        // Icon pops in with a soft bounce, like the splash button.
        content.findViewById<View>(R.id.offlineIconWrap).apply {
            scaleX = 0f; scaleY = 0f
            animate().scaleX(1f).scaleY(1f).setStartDelay(150).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
    }

    private fun handleBackOnline() {
        if (isFinishing || isDestroyed) return
        uiHandler.removeCallbacksAndMessages(null)
        if (!offlineShown) return
        offlineShown = false
        offlineDialog?.dismiss()
        offlineDialog = null
        Toast.makeText(this, R.string.back_online, Toast.LENGTH_SHORT).show()
        (fragments[R.id.navHome] as? HomeFragment)?.onBackOnline()
        (fragments[R.id.navMusic] as? MusicFragment)?.onBackOnline()
        (fragments[R.id.navSubs] as? SubscriptionsFragment)?.onBackOnline()
    }

    override fun onPause() {
        super.onPause()
        PlaybackCenter.removeListener(playbackListener)
        progressHandler.removeCallbacksAndMessages(null)
        // Hand the video surface over to whoever is coming on top.
        PlaybackCenter.detachView(binding.miniVideo)
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackCenter.removeListener(playbackListener)
    }

    /**
     * Update gate: on every app open ask the server for the latest version;
     * if it is newer than the installed one the updater screen opens (and a
     * mandatory update blocks the app until installed). The last answer is
     * cached, so being offline does not skip the gate.
     */
    private fun silentlyCheckForUpdate() {
        if (!UpdateChecker.isConfigured) return
        // Once per app open (not on every screen change inside the app).
        if (UpdateChecker.checkedThisOpen) return
        UpdateChecker.checkedThisOpen = true
        lifecycleScope.launch {
            val info = UpdateChecker.refresh(this@MainActivity)
            if (info != null && !isFinishing && !isDestroyed) {
                UpdateActivity.start(this@MainActivity)
            }
        }
    }

    /** Android 13+: media notification (background playback controls) needs this. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notification permission is optional: playback works either way.
    }

    private fun bindMiniPlayer() {
        val hasMedia = PlaybackCenter.hasMedia && !PlaybackCenter.inPip
        binding.miniPlayer.isVisible = hasMedia
        if (!hasMedia) {
            PlaybackCenter.detachView(binding.miniVideo)
            return
        }
        if (PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO && !PlaybackCenter.audioOnlyMode) {
            // Live video surface in the mini player, like YouTube: a 16:9
            // box that the picture fills completely (no black bars).
            setMiniMediaWidth(wide = true)
            binding.miniThumb.isVisible = false
            binding.miniVideo.isVisible = true
            PlaybackCenter.attachView(binding.miniVideo)
        } else {
            PlaybackCenter.detachView(binding.miniVideo)
            setMiniMediaWidth(wide = false)
            binding.miniVideo.isVisible = false
            binding.miniThumb.isVisible = true
        }
        updateMiniPlayer()
    }

    private fun setMiniMediaWidth(wide: Boolean) {
        val width = ((if (wide) 92 else 52) * resources.displayMetrics.density).toInt()
        val lp = binding.miniMedia.layoutParams
        if (lp.width != width) {
            lp.width = width
            binding.miniMedia.layoutParams = lp
        }
    }

    private fun updateMiniPlayer() {
        if (isFinishing || isDestroyed) return
        val entry = PlaybackCenter.currentEntry
        val show = entry != null && !PlaybackCenter.inPip
        val wasShown = binding.miniPlayer.isVisible
        binding.miniPlayer.isVisible = show
        if (show && !wasShown && AppPrefs.animations) {
            val d = resources.displayMetrics.density
            binding.miniPlayer.alpha = 0f
            binding.miniPlayer.translationY = 28 * d
            binding.miniPlayer.animate().alpha(1f).translationY(0f).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        if (entry == null) return

        binding.miniTitle.text = entry.title
        binding.miniTitle.isSelected = true
        binding.miniSubtitle.text = entry.uploader
        Thumbs.load(binding.miniThumb, entry.thumbnailUrl)
        binding.miniPlayPause.setImageResource(
            if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    /** Tab tap: light haptic tick; tapping the current tab again scrolls its list to the top. */
    private fun onNavTap(id: Int, view: View) {
        com.play.tube.util.Haptics.soft(view)
        if (id == selectedId) {
            when (id) {
                R.id.navHome -> (fragments[id] as? HomeFragment)?.scrollToTop()
                R.id.navSubs -> (fragments[id] as? SubscriptionsFragment)?.scrollToTop()
            }
            bounceNavIcon(view)
            return
        }
        select(id)
    }

    /** The tapped tab's icon pops with a small overshoot. */
    private fun bounceNavIcon(nav: View) {
        val icon = (nav as? android.view.ViewGroup)?.getChildAt(0) ?: return
        icon.animate().cancel()
        icon.scaleX = 0.7f; icon.scaleY = 0.7f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    private fun select(id: Int) {
        if (id == selectedId) return
        selectedId = id
        applySelection()
    }

    private fun applySelection() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        if (AppPrefs.animations) {
            // Gentle cross-fade + slide-up on the incoming tab.
            tx.setCustomAnimations(
                R.anim.tab_enter, R.anim.tab_exit,
                R.anim.tab_enter, R.anim.tab_exit
            )
        }
        fragments.values.forEach { tx.hide(it) }
        val target = fragments[selectedId] ?: createFragment(selectedId).also {
            fragments[selectedId] = it
            tx.add(R.id.container, it, tagOf(selectedId))
        }
        tx.show(target)
        tx.commit()
        updateMiniPlayer()

        val isActive = { view: View -> view.id == selectedId }
        binding.navHome.isSelected = isActive(binding.navHome)
        binding.navMusic.isSelected = isActive(binding.navMusic)
        binding.navSubs.isSelected = isActive(binding.navSubs)
        binding.navLibrary.isSelected = isActive(binding.navLibrary)
        binding.navMenu.isSelected = isActive(binding.navMenu)
        listOf(binding.navHome, binding.navMusic, binding.navSubs,
            binding.navLibrary, binding.navMenu).firstOrNull { it.isSelected }?.let { bounceNavIcon(it) }
    }

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.navHome -> HomeFragment()
        R.id.navMusic -> MusicFragment()
        R.id.navSubs -> SubscriptionsFragment()
        R.id.navLibrary -> LibraryFragment.newInstance(downloadsOnly = true)
        else -> MenuFragment()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedId != R.id.navHome) {
            select(R.id.navHome)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        fun openDownloads(context: android.content.Context) {
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_DOWNLOADS, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        private const val STATE_SELECTED = "selected_nav"
        private const val REQ_NOTIFICATIONS = 4711
        private val NAV_IDS = intArrayOf(R.id.navHome, R.id.navMusic, R.id.navSubs, R.id.navLibrary, R.id.navMenu)
        private fun tagOf(id: Int) = "frag_$id"
    }
}
