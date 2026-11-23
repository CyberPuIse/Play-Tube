package com.play.tube.ui.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.play.tube.ui.player.PlayerActivity
import coil.load
import com.play.tube.R
import com.play.tube.data.LocalStore
import com.play.tube.databinding.ActivityNowPlayingBinding
import com.play.tube.download.DownloadCenter
import com.play.tube.playback.PlaybackCenter
import com.play.tube.playback.QueueEntry
import com.play.tube.ui.player.DownloadSheet
import com.play.tube.util.AppPrefs
import com.play.tube.util.PanelTransitions
import com.play.tube.util.Themes
import com.play.tube.util.Thumbs
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Modern music player: large rounded artwork that breathes with play/pause,
 * title + artist with a heart, seek bar, big transport controls, radio and
 * download, plus an "Up next" card / queue button that opens the full queue
 * — so when a radio is running the user can pick any song from it.
 * Playback itself runs in the shared background player so music keeps
 * playing (with notification controls) even when the app is closed.
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) = bind()
        override fun onPlaybackStateChanged(isPlaying: Boolean) = bindPlayButton()
        override fun onResolvingChanged(isResolving: Boolean) {
            binding.loading.isVisible = isResolving
        }

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = bindFavorite()

        // Radio keeps adding songs while it plays: keep "Up next" current.
        override fun onQueueChanged() = bindUpNext()
        override fun onSleepTimerChanged() = bindTimer()
        override fun onSleepTimerExpired() { finish() }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            bindTimer()
            updateDownloadState()
            updateLyricHighlight()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        // Expands UP out of the mini player (and collapses back down).
        PanelTransitions.install(this)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PlaybackCenter.hasMedia) {
            finish()
            return
        }

        // Background music needs the notification: ask again here if the
        // user missed / denied the prompt shown on first launch.
        requestNotificationPermissionIfNeeded()

        binding.backButton.setOnClickListener { finish() }
        binding.modeSwitch.setOnClickListener {
            binding.modeSwitch.isEnabled = false
            lifecycleScope.launch {
                try {
                    val available = if (PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO) {
                        PlaybackCenter.switchBackToVideo()
                        true
                    } else PlaybackCenter.switchMusicToVideo()
                    if (available) {
                        PlayerActivity.startResume(this@NowPlayingActivity)
                        finish()
                    } else Toast.makeText(this@NowPlayingActivity, R.string.switch_video_unavailable, Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Toast.makeText(this@NowPlayingActivity, e.message ?: getString(R.string.switch_video_unavailable), Toast.LENGTH_SHORT).show() }
                finally { binding.modeSwitch.isEnabled = true }
            }
        }
        binding.playPauseButton.setOnClickListener {
            com.play.tube.util.Haptics.soft(it)
            it.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
            PlaybackCenter.togglePlayPause()
        }
        binding.prevButton.setOnClickListener { PlaybackCenter.previous() }
        binding.nextButton.setOnClickListener { PlaybackCenter.next() }
        binding.favoriteButton.setOnClickListener {
            com.play.tube.util.Haptics.soft(it)
            it.animate().scaleX(1.35f).scaleY(1.35f).setDuration(110).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
            val nowFav = PlaybackCenter.toggleFavoriteCurrent()
            Toast.makeText(
                this,
                if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.radioButton.setOnClickListener { restartRadio() }
        binding.lyricsButton.setOnClickListener { toggleLyrics() }
        binding.downloadButton.setOnClickListener { onDownloadClicked() }
        binding.queueButton.setOnClickListener { showQueue() }
        binding.timerButton.setOnClickListener { SleepTimerSheet(this).show() }
        binding.upNextCard.setOnClickListener { showQueue() }

        // The artwork is always a square that fits the space the controls
        // leave over (small phones / landscape included).
        binding.artContainer.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = minOf(r - l, b - t).coerceAtLeast(0)
            val lp = binding.art.layoutParams
            if (size > 0 && (lp.width != size || lp.height != size)) {
                lp.width = size
                lp.height = size
                binding.art.layoutParams = lp
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.position.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                seekBar?.let { PlaybackCenter.seekTo(it.progress.toLong()) }
            }
        })

        bind()
    }

    // ----- Swipe down to collapse into the mini player -----

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false

    /**
     * A downward swipe anywhere on the page (except on the seek bar, which
     * owns its own drags) collapses the player into the mini player, sliding
     * DOWN — the mirror of how it opened.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                swipeTracking = !isOnSeekBar(ev)
            }
            MotionEvent.ACTION_MOVE -> if (swipeTracking) {
                val dy = ev.rawY - swipeDownY
                val dx = kotlin.math.abs(ev.rawX - swipeDownX)
                if (dy > SWIPE_MIN_DISTANCE_PX && dy > dx * 1.5f) {
                    swipeTracking = false
                    // Let the touched child (button / card) drop its pressed state.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    finish()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeTracking = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isOnSeekBar(ev: MotionEvent): Boolean {
        val rect = android.graphics.Rect()
        if (!binding.seekBar.getGlobalVisibleRect(rect)) return false
        // A little slack: the thumb is easy to miss by a finger's width.
        val slack = (16 * resources.displayMetrics.density).toInt()
        rect.inset(-slack, -slack)
        return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    override fun finish() {
        super.finish()
        PanelTransitions.applyClose(this)
    }

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(playbackListener)
        handler.post(progressRunnable)
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        Themes.recreateIfNeeded(this)
    }

    override fun onStop() {
        super.onStop()
        PlaybackCenter.removeListener(playbackListener)
        handler.removeCallbacks(progressRunnable)
    }

    private fun bind() {
        val entry = PlaybackCenter.currentEntry ?: run { finish(); return }
        binding.title.text = entry.title
        binding.subtitle.text = entry.uploader
        Thumbs.load(binding.art, entry.thumbnailUrl)
        applyArtColor(entry.thumbnailUrl)
        if (lyricsUrl != entry.url) { lyricsUrl = null; lyricLines = emptyList(); activeLine = -1 }
        if (lyricsShown) loadLyrics(entry)
        binding.playingLabel.isVisible = true
        bindPlayButton()
        bindFavorite()
        bindUpNext()
        updateProgress()
        updateDownloadState()
    }

    // ----- Album-art colour background -----

    private var artTint = 0
    private var colorJobUrl: String? = null

    /** Pulls a pleasant dominant colour out of the cover and washes the page with it. */
    private fun applyArtColor(url: String) {
        if (url.isBlank() || colorJobUrl == url) return
        colorJobUrl = url
        val request = coil.request.ImageRequest.Builder(this)
            .data(url)
            .allowHardware(false)
            .size(64)
            .target { drawable ->
                val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@target
                val color = dominantColor(bmp)
                if (isFinishing || isDestroyed) return@target
                animateBackground(color)
            }
            .build()
        coil.Coil.imageLoader(this).enqueue(request)
    }

    /** Most vivid mid-tone colour of a small copy of the image. */
    private fun dominantColor(src: android.graphics.Bitmap): Int {
        val bmp = android.graphics.Bitmap.createScaledBitmap(src, 24, 24, true)
        val hsv = FloatArray(3)
        var best = 0
        var bestScore = -1f
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            android.graphics.Color.colorToHSV(c, hsv)
            val vib = hsv[1] * (1f - kotlin.math.abs(hsv[2] - 0.6f))
            if (vib > bestScore) { bestScore = vib; best = c }
        }
        if (bestScore < 0.08f) return 0   // grey cover: keep the default background
        android.graphics.Color.colorToHSV(best, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.75f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun blend(a: Int, b: Int, t: Float) = android.animation.ArgbEvaluator().evaluate(t, a, b) as Int

    private fun animateBackground(target: Int) {
        val base = Themes.color(this, R.attr.screenBg)
        val from = artTint
        artTint = target
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (AppPrefs.animations) 650 else 0
            addUpdateListener {
                val f = it.animatedValue as Float
                val c = if (from == 0) blend(base, if (target == 0) base else target, f)
                else if (target == 0) blend(from, base, f) else blend(from, target, f)
                val bg = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(blend(base, c, 0.60f), blend(base, c, 0.22f), base)
                )
                binding.root.background = bg
            }
            start()
        }
        if (android.os.Build.VERSION.SDK_INT >= 28 && target != 0) {
            binding.art.outlineSpotShadowColor = target
            binding.art.outlineAmbientShadowColor = target
        }
        anim.hashCode()
    }

    // ----- Lyrics -----

    private var lyricsShown = false
    private var lyricsUrl: String? = null
    private var lyricsData: com.play.tube.data.LyricsRepository.Lyrics? = null
    private var lyricLines: List<android.widget.TextView> = emptyList()
    private var activeLine = -1
    private var lyricsJob: kotlinx.coroutines.Job? = null

    private fun toggleLyrics() {
        lyricsShown = !lyricsShown
        com.play.tube.util.Haptics.soft(binding.lyricsButton)
        binding.lyricsButton.setColorFilter(
            if (lyricsShown) Themes.accentColor(this) else getColor(R.color.on_surface)
        )
        val show = if (lyricsShown) binding.lyricsScroll else binding.art
        val hide = if (lyricsShown) binding.art else binding.lyricsScroll
        show.alpha = 0f
        show.isVisible = true
        show.animate().alpha(1f).setDuration(220).start()
        hide.animate().alpha(0f).setDuration(180).withEndAction { hide.isVisible = false }.start()
        if (lyricsShown) PlaybackCenter.currentEntry?.let(::loadLyrics)
    }

    private fun lyricsMessage(text: String) {
        binding.lyricsContainer.removeAllViews()
        lyricLines = emptyList()
        binding.lyricsContainer.addView(android.widget.TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.on_surface_variant))
            setPadding(0, (60 * resources.displayMetrics.density).toInt(), 0, 0)
        })
    }

    private fun loadLyrics(entry: QueueEntry) {
        if (lyricsUrl == entry.url && lyricsData != null) { renderLyrics(); return }
        lyricsUrl = entry.url
        lyricsData = null
        lyricsMessage(getString(R.string.lyrics_loading))
        lyricsJob?.cancel()
        lyricsJob = lifecycleScope.launch {
            val data = com.play.tube.data.LyricsRepository.fetch(
                entry.url, entry.title, entry.uploader, PlaybackCenter.durationMs
            )
            if (lyricsUrl != entry.url) return@launch
            lyricsData = data
            if (data == null) lyricsMessage(getString(R.string.lyrics_not_found)) else renderLyrics()
        }
    }

    private fun renderLyrics() {
        val data = lyricsData ?: return
        val d = resources.displayMetrics.density
        val box = binding.lyricsContainer
        box.removeAllViews()
        activeLine = -1
        lyricLines = data.lines.mapIndexed { i, line ->
            android.widget.TextView(this).apply {
                text = line.text.ifBlank { "♪" }
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.on_surface))
                alpha = if (data.synced) 0.40f else 0.9f
                setPadding((6 * d).toInt(), (7 * d).toInt(), (6 * d).toInt(), (7 * d).toInt())
                if (data.synced) setOnClickListener { PlaybackCenter.seekTo(line.timeMs) }
            }.also { box.addView(it) }
        }
        updateLyricHighlight()
    }

    private fun updateLyricHighlight() {
        val data = lyricsData ?: return
        if (!lyricsShown || !data.synced || lyricLines.isEmpty()) return
        val pos = PlaybackCenter.positionMs + 250
        var idx = -1
        for (i in data.lines.indices) { if (data.lines[i].timeMs <= pos) idx = i else break }
        if (idx == activeLine) return
        activeLine = idx
        lyricLines.forEachIndexed { i, tv ->
            val active = i == idx
            tv.animate().alpha(if (active) 1f else 0.40f).scaleX(if (active) 1.04f else 1f)
                .scaleY(if (active) 1.04f else 1f).setDuration(220).start()
            tv.pivotX = 0f
            tv.setTextColor(if (active) Themes.accentColor(this) else getColor(R.color.on_surface))
        }
        if (idx >= 0) {
            val v = lyricLines[idx]
            binding.lyricsScroll.smoothScrollTo(0, (v.top - binding.lyricsScroll.height / 2 + v.height / 2).coerceAtLeast(0))
        }
    }

    private fun bindPlayButton() {
        val isPlaying = PlaybackCenter.isPlaying
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.playPauseButton.contentDescription = getString(
            if (isPlaying) R.string.cd_pause else R.string.cd_play
        )
        // Artwork "breathes": full size while playing, slightly smaller paused.
        val scale = if (isPlaying) 1f else 0.88f
        if (AppPrefs.animations) {
            binding.art.animate().scaleX(scale).scaleY(scale).setDuration(260).start()
        } else {
            binding.art.animate().cancel()
            binding.art.scaleX = scale
            binding.art.scaleY = scale
        }
    }

    /** "Up next" card + queue button; also flags a radio session in the header. */
    private fun bindUpNext() {
        if (isFinishing) return
        val queue = PlaybackCenter.queue
        val radio = PlaybackCenter.radioMode
        val next = queue.getOrNull(PlaybackCenter.queueIndex + 1)

        binding.playingLabel.setText(
            if (radio) R.string.playing_from_radio else R.string.now_playing
        )
        binding.queueButton.visibility =
            if (queue.size > 1 || radio) android.view.View.VISIBLE else android.view.View.INVISIBLE

        if (next != null) {
            binding.upNextCard.isVisible = true
            binding.upNextLabel.setText(
                if (radio) R.string.up_next_radio_label else R.string.up_next_label
            )
            binding.upNextTitle.text = next.title
        } else {
            // A radio that has not fetched its songs yet keeps the card as an
            // entry point instead of the queue button vanishing.
            binding.upNextCard.isVisible = radio
            binding.upNextLabel.setText(R.string.up_next_radio_label)
            binding.upNextTitle.setText(R.string.queue_loading)
        }
    }

    private fun bindTimer() {
        binding.timerButton.text = SleepTimerSheet.buttonLabel(this)
        binding.timerButton.contentDescription = SleepTimerSheet.accessibilityLabel(this)
    }

    private fun showQueue() {
        QueueSheet(this).show()
    }

    private fun bindFavorite() {
        val entry = PlaybackCenter.currentEntry ?: return
        val isFav = LocalStore.isFavorite(this, entry.url)
        binding.favoriteButton.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.favoriteButton.setColorFilter(
            if (isFav) getColor(R.color.music_green) else getColor(R.color.on_surface)
        )
    }

    private fun updateProgress() {
        if (userSeeking || isFinishing) return
        val duration = PlaybackCenter.durationMs
        val position = PlaybackCenter.positionMs
        binding.seekBar.max = if (duration > 0) duration.toInt() else 0
        binding.seekBar.progress = position.toInt()
        binding.position.text = formatTime(position)
        binding.duration.text = formatTime(duration)
    }

    /** Android 13+: without this the media notification (and its controls) stay hidden. */
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
        // Optional: playback works either way.
    }

    private fun restartRadio() {
        val entry = PlaybackCenter.currentEntry ?: return
        PlaybackCenter.playMusic(entry, radio = true)
        Toast.makeText(this, R.string.radio_started, Toast.LENGTH_SHORT).show()
        // Straight into the radio's queue so any song can be picked.
        showQueue()
    }

    // ----- Download state -----

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (DownloadCenter.isDownloaded(this, entry.url)) {
            Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadSheet()
    }

    /** Icon + status line for downloaded / downloading states. */
    private fun updateDownloadState() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (isFinishing) return
        // Re-sync with the system download manager so a finished download
        // shows "Downloaded" instead of sticking at "Downloading • 100%".
        if (DownloadCenter.hasActiveDownload(this, entry.url)) {
            DownloadCenter.refreshStatuses(this)
        }
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_check)
                binding.downloadButton.setColorFilter(getColor(R.color.music_green))
                binding.downloadStatus.isVisible = false
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(getColor(R.color.on_surface))
                val percent = DownloadCenter.downloadProgress(this, entry.url)
                binding.downloadStatus.text = getString(R.string.downloading_fmt, percent)
                binding.downloadStatus.isVisible = true
            }
            else -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(getColor(R.color.on_surface))
                binding.downloadStatus.isVisible = false
            }
        }
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog ?: run {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        // This is the music player: only the audio section applies.
        DownloadSheet(this, entry, catalog, audioOnly = true).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 4712
        private const val SWIPE_MIN_DISTANCE_PX = 260f

        fun start(context: Context) {
            context.startActivity(Intent(context, NowPlayingActivity::class.java))
        }
    }
}
