package com.play.tube.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.play.tube.ui.common.PremiumSheet
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.play.tube.R
import com.play.tube.data.LocalStore
import com.play.tube.databinding.FragmentLibraryBinding
import com.play.tube.data.DownloadFolders
import com.play.tube.download.DownloadCenter
import com.play.tube.playback.PlaybackCenter
import com.play.tube.playback.QueueEntry
import com.play.tube.ui.channel.ChannelActivity
import com.play.tube.ui.common.ChannelRowAdapter
import com.play.tube.ui.common.DownloadAdapter
import com.play.tube.ui.common.PlaylistAdapter
import com.play.tube.ui.common.VideoAdapter
import com.play.tube.ui.common.toEntry
import com.play.tube.ui.common.toUiModel
import com.play.tube.ui.player.PlayerActivity
import com.play.tube.ui.playlist.PlaylistActivity
import com.play.tube.ui.music.AudioDownloadLibrary
import com.play.tube.ui.music.NowPlayingActivity

/**
 * Two modes (see [newInstance]): the bottom-nav Downloads tab, and the Library page
 * (History, Favorites, Subscriptions, Playlists) opened from the Menu.
 * Everything is stored locally on the device.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var channelAdapter: ChannelRowAdapter
    private lateinit var downloadAdapter: DownloadAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    /** true = bottom-nav Downloads tab; false = Library page (history, favorites, ...). */
    private val downloadsOnly: Boolean by lazy { arguments?.getBoolean(ARG_DOWNLOADS_ONLY, false) ?: false }

    /** Logical tab ids in TabLayout order (positions shift once Downloads is removed). */
    private var tabIds: List<Int> = listOf(TAB_HISTORY, TAB_FAVORITES, TAB_SUBSCRIPTIONS, TAB_PLAYLISTS, TAB_WATCH_LATER)

    private var tab = TAB_HISTORY

    // Downloads tab: offline search text and the selected folder (null = All).
    private var selectedFolder: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshDownloads = object : Runnable {
        override fun run() { if (_binding != null && tab == TAB_DOWNLOADS) { refresh(); uiHandler.postDelayed(this, 1000) } }
    }
    private var downX = 0f
    private var downY = 0f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        videoAdapter = VideoAdapter(
            onClick = { model -> PlayerActivity.start(requireContext(), model) },
            onLongClick = { model ->
                if (tab == TAB_HISTORY) {
                    LocalStore.removeFromHistory(requireContext(), model.url)
                    Toast.makeText(requireContext(), R.string.removed_from_history, Toast.LENGTH_SHORT).show()
                } else {
                    LocalStore.toggleFavorite(requireContext(), model.toEntry())
                    Toast.makeText(requireContext(), R.string.removed_from_favorites, Toast.LENGTH_SHORT).show()
                }
                refresh()
                true
            }
        )
        channelAdapter = ChannelRowAdapter(
            onClick = { channel ->
                ChannelActivity.start(requireContext(), channel.url, channel.name)
            },
            onUnsubscribe = { channel ->
                LocalStore.toggleSubscription(requireContext(), channel)
                Toast.makeText(requireContext(), R.string.unsubscribed_toast, Toast.LENGTH_SHORT).show()
                refresh()
            }
        )
        downloadAdapter = DownloadAdapter(
            onClick = { record -> openDownload(record) },
            onLongClick = { record -> confirmDelete(record) },
            onPause = { record ->
                DownloadCenter.togglePause(requireContext(), record)
                refresh()
            },
            onMove = { record -> showFolderPicker(record) },
            onDelete = { record -> confirmDelete(record) }
        )
        playlistAdapter = PlaylistAdapter(
            onClick = { playlist ->
                PlaylistActivity.start(requireContext(), playlist.id)
            },
            onLongClick = { playlist -> confirmDeletePlaylist(playlist) }
        )

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = videoAdapter

        if (downloadsOnly) {
            // Bottom-nav "Downloads": just the downloaded videos, no tab strip.
            tabIds = listOf(TAB_DOWNLOADS)
            tab = TAB_DOWNLOADS
            binding.title.setText(R.string.tab_downloads)
            binding.tabs.isVisible = false
            binding.downloadTools.isVisible = true
            bindFolderChips()
        } else {
            // Library page (opened from Menu): downloads live in their own tab now.
            binding.tabs.removeTabAt(TAB_DOWNLOADS)
            binding.title.isVisible = false
        }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                this@LibraryFragment.tab = tabIds.getOrElse(tab.position) { TAB_HISTORY }
                uiHandler.removeCallbacks(refreshDownloads)
                refresh()
                if (this@LibraryFragment.tab == TAB_DOWNLOADS) uiHandler.post(refreshDownloads)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.tabs.selectTab(binding.tabs.getTabAt(tabIds.indexOf(tab).coerceAtLeast(0)))
        binding.list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var switched = false
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; switched = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        val threshold = 56 * resources.displayMetrics.density
                        if (!switched && kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                            switched = true
                            val direction = if (rv.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                            val current = tabIds.indexOf(this@LibraryFragment.tab).coerceAtLeast(0)
                            val next = (current + (if (dx < 0) direction else -direction)).coerceIn(0, tabIds.lastIndex)
                            binding.tabs.getTabAt(next)?.select()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    fun showDownloadsTab() {
        if (_binding == null) return
        binding.tabs.getTabAt(tabIds.indexOf(TAB_DOWNLOADS))?.select()
        refresh()
        uiHandler.removeCallbacks(refreshDownloads)
        uiHandler.post(refreshDownloads)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        uiHandler.removeCallbacks(refreshDownloads)
        if (tab == TAB_DOWNLOADS) uiHandler.post(refreshDownloads)
    }

    override fun onPause() { uiHandler.removeCallbacks(refreshDownloads); super.onPause() }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        uiHandler.removeCallbacks(refreshDownloads)
        if (!hidden && _binding != null) {
            refresh()
            if (tab == TAB_DOWNLOADS) uiHandler.post(refreshDownloads)
        }
    }

    private fun refresh() {
        if (_binding == null) return
        val context = context ?: return
        when (tab) {
            TAB_HISTORY -> bindVideos(LocalStore.history(context), R.string.empty_history)
            TAB_FAVORITES -> bindVideos(LocalStore.videoFavorites(context), R.string.empty_favorites)
            TAB_SUBSCRIPTIONS -> bindChannels()
            TAB_PLAYLISTS -> bindPlaylists()
            TAB_WATCH_LATER -> bindVideos(LocalStore.watchLater(context), R.string.empty_watch_later)
            else -> bindDownloads()
        }
    }

    private fun bindVideos(entries: List<com.play.tube.data.VideoEntry>, emptyText: Int) {
        binding.list.adapter = videoAdapter
        videoAdapter.submitList(entries.map { it.toUiModel() })
        com.play.tube.util.EmptyState.set(binding.emptyView, emptyText, com.play.tube.util.EmptyState.iconFor(emptyText))
        binding.emptyView.isVisible = entries.isEmpty()
    }

    private fun bindChannels() {
        val context = context ?: return
        val channels = LocalStore.subscriptions(context)
        binding.list.adapter = channelAdapter
        channelAdapter.submitList(channels)
        com.play.tube.util.EmptyState.set(binding.emptyView, R.string.empty_subscriptions, R.drawable.ic_subscriptions)
        binding.emptyView.isVisible = channels.isEmpty()
    }

    private fun bindDownloads() {
        val context = context ?: return
        DownloadCenter.refreshStatuses(context)
        // Include audio too so every transfer can be paused, resumed and shared here.
        val all = LocalStore.downloads(context)
        val downloads = if (!downloadsOnly) all else all.filter { record ->
            (selectedFolder == null ||
                    when (selectedFolder) {
                        FOLDER_MUSIC -> record.type == "AUDIO"
                        FOLDER_VIDEOS -> record.type != "AUDIO"
                        else -> DownloadFolders.folderOf(context, record.videoId) == selectedFolder
                    })
        }
        if (binding.list.adapter !== downloadAdapter) binding.list.adapter = downloadAdapter
        (binding.list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        downloadAdapter.submitList(downloads) { downloadAdapter.refreshLiveMetrics() }
        com.play.tube.util.EmptyState.set(
            binding.emptyView,
            if (all.isNotEmpty()) R.string.empty_downloads_search else R.string.empty_downloads,
            R.drawable.ic_download
        )
        binding.emptyView.isVisible = downloads.isEmpty()
        bindPauseAll(context)
        bindStorage(all)
    }

    /**
     * Storage card: bytes taken by Music and Video downloads next to the
     * device's used / free space, drawn as one segmented bar.
     */
    private fun bindStorage(all: List<com.play.tube.data.DownloadRecord>) {
        val b = _binding ?: return
        val ctx = context ?: return
        if (!downloadsOnly) return
        var music = 0L
        var video = 0L
        val seen = HashSet<String>()
        all.forEach { record ->
            record.filePaths.forEach { path ->
                if (!seen.add(path)) return@forEach
                val len = java.io.File(path).takeIf { it.isFile }?.length() ?: 0L
                if (record.type == "AUDIO") music += len else video += len
            }
        }
        val total: Long
        val free: Long
        try {
            val stat = android.os.StatFs(
                (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
            )
            total = stat.totalBytes
            free = stat.availableBytes
        } catch (e: Exception) {
            b.storageCard.isVisible = false
            return
        }
        if (total <= 0L) {
            b.storageCard.isVisible = false
            return
        }
        b.storageCard.isVisible = true
        val other = (total - free - music - video).coerceAtLeast(0L)

        // Tiny segments would vanish on a 128 GB phone, so give any non-zero
        // PlayTube segment a minimum visible share of the bar.
        val minShare = total * 0.015
        val musicW = if (music > 0) maxOf(music.toDouble(), minShare) else 0.0
        val videoW = if (video > 0) maxOf(video.toDouble(), minShare) else 0.0
        fun weight(v: View, w: Double, color: Int) {
            (v.layoutParams as android.widget.LinearLayout.LayoutParams).weight = w.toFloat()
            v.setBackgroundColor(color)
            v.requestLayout()
        }
        val accent = com.play.tube.util.Themes.accentColor(ctx)
        val videoColor = 0xFF4DA3FF.toInt()
        weight(b.segMusic, musicW, accent)
        weight(b.segVideo, videoW, videoColor)
        weight(b.segOther, other.toDouble(), 0x66808080)
        weight(b.segFree, free.toDouble(), 0x22808080)
        b.storageBar.clipToOutline = true
        b.storageBar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }

        fun dot(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            val size = (8 * resources.displayMetrics.density).toInt()
            setSize(size, size)
        }
        b.legendMusic.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(accent), null, null, null)
        b.legendVideo.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(videoColor), null, null, null)
        b.legendMusic.text = getString(
            R.string.storage_music, android.text.format.Formatter.formatShortFileSize(ctx, music)
        )
        b.legendVideo.text = getString(
            R.string.storage_videos, android.text.format.Formatter.formatShortFileSize(ctx, video)
        )
        b.storageFree.text = getString(
            R.string.storage_free,
            android.text.format.Formatter.formatShortFileSize(ctx, free),
            android.text.format.Formatter.formatShortFileSize(ctx, total)
        )
    }

    /** "Pause all" while something runs, "Resume all" when only paused ones remain. */
    private fun bindPauseAll(context: android.content.Context) {
        if (!downloadsOnly || _binding == null) return
        val running = DownloadCenter.anyRunning(context)
        val paused = DownloadCenter.anyPaused(context)
        val btn = binding.pauseAllButton
        btn.isVisible = running || paused
        if (!btn.isVisible) return
        btn.setText(if (running) R.string.download_pause_all else R.string.download_resume_all)
        btn.setOnClickListener {
            DownloadCenter.setAllPaused(context, pause = running)
            bindDownloads()
        }
    }

    // ----- Download folders -----

    /** Rebuilds the "All / folders / + Folder" chip row (only on changes, not every refresh tick). */
    private fun bindFolderChips() {
        val b = _binding ?: return
        val ctx = context ?: return
        val folders = DownloadFolders.folders(ctx)
        if (selectedFolder != null && selectedFolder !in folders &&
            selectedFolder != FOLDER_MUSIC && selectedFolder != FOLDER_VIDEOS) selectedFolder = null
        b.folderRow.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()

        fun addChip(label: String, checked: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = label
                isCheckable = true
                isChecked = checked
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = gap }
            b.folderRow.addView(chip, lp)
        }

        addChip(getString(R.string.folder_all), selectedFolder == null, onClick = {
            selectedFolder = null
            bindFolderChips()
            bindDownloads()
        })
        // Built-in default folders: filled automatically by download type.
        addChip(getString(R.string.folder_music), selectedFolder == FOLDER_MUSIC, onClick = {
            selectedFolder = FOLDER_MUSIC
            bindFolderChips()
            bindDownloads()
        })
        addChip(getString(R.string.folder_videos), selectedFolder == FOLDER_VIDEOS, onClick = {
            selectedFolder = FOLDER_VIDEOS
            bindFolderChips()
            bindDownloads()
        })
        folders.forEach { name ->
            addChip(name, selectedFolder == name, onClick = {
                selectedFolder = name
                bindFolderChips()
                bindDownloads()
            }, onLong = { confirmDeleteFolder(name) })
        }
        addChip(getString(R.string.folder_new_chip), false, onClick = { promptNewFolder(null) })
    }

    private fun showDownloadActions(record: com.play.tube.data.DownloadRecord) {
        PremiumSheet(requireContext())
            .title(record.title)
            .row(R.drawable.ic_folder, getString(R.string.folder_move)) { showFolderPicker(record) }
            .row(R.drawable.ic_delete, getString(R.string.download_delete_action), destructive = true) {
                confirmDelete(record)
            }
            .show()
    }

    private fun showFolderPicker(record: com.play.tube.data.DownloadRecord) {
        val ctx = requireContext()
        val folders = DownloadFolders.folders(ctx)
        val current = DownloadFolders.folderOf(ctx, record.videoId)
        val sheet = PremiumSheet(ctx)
            .icon(R.drawable.ic_folder)
            .title(getString(R.string.folder_move))
            .row(R.drawable.ic_close, getString(R.string.folder_none), selected = current == null) {
                DownloadFolders.assign(ctx, record.videoId, null)
                bindDownloads()
            }
        folders.forEach { name ->
            sheet.row(R.drawable.ic_folder, name, selected = current == name) {
                DownloadFolders.assign(ctx, record.videoId, name)
                bindDownloads()
            }
        }
        sheet.row(R.drawable.ic_add, getString(R.string.folder_new)) { promptNewFolder(record) }
            .show()
    }

    /** Asks for a folder name; when [record] is given the download moves into the new folder. */
    private fun promptNewFolder(record: com.play.tube.data.DownloadRecord?) {
        val ctx = requireContext()
        val sheet = PremiumSheet(ctx)
        sheet.icon(R.drawable.ic_folder)
            .title(getString(R.string.folder_new))
            .input(getString(R.string.folder_name_hint))
            .primary(getString(R.string.folder_create)) {
                val name = sheet.inputText()
                if (DownloadFolders.create(ctx, name)) {
                    if (record != null) DownloadFolders.assign(ctx, record.videoId, name)
                    bindFolderChips()
                    bindDownloads()
                    true
                } else {
                    Toast.makeText(ctx, R.string.folder_exists, Toast.LENGTH_SHORT).show()
                    false
                }
            }
            .secondary(getString(android.R.string.cancel))
            .show()
    }

    private fun confirmDeleteFolder(name: String) {
        val ctx = requireContext()
        val inside = LocalStore.downloads(ctx).filter {
            DownloadFolders.folderOf(ctx, it.videoId) == name
        }
        fun finish() {
            if (selectedFolder == name) selectedFolder = null
            bindFolderChips()
            bindDownloads()
        }
        val sheet = PremiumSheet(ctx)
            .icon(R.drawable.ic_delete)
            .title(getString(R.string.folder_delete_title))
        if (inside.isEmpty()) {
            sheet.message(getString(R.string.folder_delete_confirm, name))
                .primary(getString(R.string.download_delete_action)) {
                    DownloadFolders.delete(ctx, name); finish(); true
                }
        } else {
            // Videos inside: ask what should happen to them.
            sheet.message(getString(R.string.folder_delete_message, name))
                .row(R.drawable.ic_folder, getString(R.string.folder_delete_keep)) {
                    DownloadFolders.delete(ctx, name); finish()
                }
                .row(R.drawable.ic_delete, getString(R.string.folder_delete_all), destructive = true) {
                    inside.forEach { DownloadCenter.delete(ctx, it) }
                    DownloadFolders.delete(ctx, name)
                    finish()
                }
        }
        sheet.secondary(getString(android.R.string.cancel)).show()
    }

    private fun openDownload(record: com.play.tube.data.DownloadRecord) {
        // Same-kind finished downloads in the order shown, so "Next" continues the list.
        val shown = downloadAdapter.currentList.filter { it.status == DownloadCenter.STATUS_DONE }
        if (record.type != DownloadCenter.TYPE_AUDIO) {
            val videos = shown.filter { it.type != DownloadCenter.TYPE_AUDIO }
                .filter { r -> r.filePaths.isNotEmpty() && r.filePaths.all { java.io.File(it).exists() } }
            val idx = videos.indexOfFirst { it.videoId == record.videoId && it.quality == record.quality && it.type == record.type }
            if (idx >= 0) PlaybackCenter.playDownloads(videos, idx) else PlaybackCenter.playDownload(record)
            // Open the full player right away instead of only the mini player.
            com.play.tube.ui.player.PlayerActivity.startResume(requireContext())
            return
        }
        val audios = shown.filter { it.type == DownloadCenter.TYPE_AUDIO }
        val playable = AudioDownloadLibrary.playable(audios)
        val idx = playable.indexOfFirst { (audio, _) -> audio.videoId == record.videoId && audio.quality == record.quality }
        if (idx < 0) {
            Toast.makeText(requireContext(), R.string.download_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        PlaybackCenter.playMusicQueue(
            playable.map { (audio, path) ->
                QueueEntry(
                    url = "file://$path",
                    title = audio.title,
                    uploader = audio.uploader,
                    thumbnailUrl = audio.thumbnailUrl,
                    durationSec = 0L,
                    isMusic = true
                )
            },
            idx
        )
        NowPlayingActivity.start(requireContext())
    }

    private fun bindPlaylists() {
        val context = context ?: return
        val playlists = LocalStore.playlists(context)
        binding.list.adapter = playlistAdapter
        playlistAdapter.submitList(playlists)
        com.play.tube.util.EmptyState.set(binding.emptyView, R.string.empty_playlists, R.drawable.ic_queue_music)
        binding.emptyView.isVisible = playlists.isEmpty()
    }

    private fun confirmDeletePlaylist(playlist: com.play.tube.data.Playlist) {
        PremiumSheet(requireContext())
            .icon(R.drawable.ic_delete)
            .title(getString(R.string.delete_playlist_confirm))
            .message(playlist.name)
            .primary(getString(R.string.download_delete_action)) {
                LocalStore.deletePlaylist(requireContext(), playlist.id)
                Toast.makeText(requireContext(), R.string.playlist_deleted, Toast.LENGTH_SHORT).show()
                refresh()
                true
            }
            .secondary(getString(android.R.string.cancel))
            .show()
    }

    private fun confirmDelete(record: com.play.tube.data.DownloadRecord) {
        PremiumSheet(requireContext())
            .icon(R.drawable.ic_delete)
            .title(getString(R.string.download_delete_confirm))
            .message(record.title)
            .primary(getString(R.string.download_delete_action)) {
                DownloadCenter.delete(requireContext(), record)
                Toast.makeText(requireContext(), R.string.download_deleted, Toast.LENGTH_SHORT).show()
                refresh()
                true
            }
            .secondary(getString(android.R.string.cancel))
            .show()
    }

    override fun onDestroyView() {
        uiHandler.removeCallbacks(refreshDownloads)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val FOLDER_MUSIC = "\u0000builtin_music"
        private const val FOLDER_VIDEOS = "\u0000builtin_videos"
        private const val ARG_DOWNLOADS_ONLY = "downloads_only"

        fun newInstance(downloadsOnly: Boolean): LibraryFragment = LibraryFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_DOWNLOADS_ONLY, downloadsOnly) }
        }

        private const val TAB_HISTORY = 0
        private const val TAB_FAVORITES = 1
        private const val TAB_SUBSCRIPTIONS = 2
        private const val TAB_DOWNLOADS = 3
        private const val TAB_PLAYLISTS = 4
        private const val TAB_WATCH_LATER = 5
    }
}
