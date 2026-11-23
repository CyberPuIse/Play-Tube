package com.play.tube.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3R
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.play.tube.R
import com.play.tube.data.CommentsPage
import com.play.tube.data.LocalStore
import com.play.tube.data.YtRepository
import com.play.tube.databinding.ActivityPlayerBinding
import com.play.tube.download.DownloadCenter
import com.play.tube.playback.PlaybackCenter
import com.play.tube.playback.QueueEntry
import com.play.tube.playback.StreamCatalog
import com.play.tube.playback.effectiveHeight
import com.play.tube.playback.toVideoEntry
import com.play.tube.ui.channel.ChannelActivity
import com.play.tube.ui.common.CommentAdapter
import com.play.tube.ui.common.VideoAdapter
import com.play.tube.ui.common.toQueueEntry
import com.play.tube.ui.common.toUiModel
import com.play.tube.util.AppPrefs
import com.play.tube.util.Formatters
import com.play.tube.util.Avatars
import com.play.tube.util.PanelTransitions
import com.play.tube.util.Themes
import com.play.tube.util.Thumbs
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * YouTube-style watch page: standard 16:9 player on top, title, a foldable
 * description card (views · date, tap to expand), channel row with
 * subscribe, action pills (favorite / share / download / background audio /
 * PiP), a comments card that opens a full comments sheet, and related
 * videos below. Quality, playback
 * speed and the dubbing audio track live inside the player's gear menu.
 * A swipe down on the video shrinks playback into the mini player.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var relatedAdapter: VideoAdapter
    private var channelSubCount: Long = -1L

    private var swipeTriggered = false
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: android.view.ScaleGestureDetector
    private var pinchFactor = 1f
    private var pinchedThisGesture = false
    private var gestureTracking = false

    // Swipe-down-to-minimize (live drag) and swipe-up-to-fullscreen in portrait.
    private var minimizeDragging = false
    private var skipExitTransition = false
    private var fullscreenSwipeDone = false

    // Seek-bar thumbnail preview (storyboard sprite sheets), cached per video.
    private var previewUrlKey: String? = null
    private val previewSheets = HashMap<Int, android.graphics.Bitmap>()
    private val previewLoading = HashSet<Int>()

    // "Up next in 5" countdown.
    private var autoplayTimer: android.os.CountDownTimer? = null
    private var rippleHide: Runnable? = null
    private var gestureStartedInLandscape = false
    private var portraitGestureRejected = false
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0.5f
    private var gestureAxis = PlayerGestureMath.Axis.NONE
    private var gestureStartPosition = 0L
    private var pendingSeekTarget: Long? = null

    // Double-tap skipping: taps in quick succession add up (+10s, +20s, ...).
    private var lastSkipAt = 0L
    private var lastSkipForward = true
    private var skipAccumulatedSec = 0

    // Press-and-hold = temporary 2x speed (released on finger up).
    private var longPressSpeedActive = false
    private var speedBeforeLongPress = 1.0f
    private val hideGestureFeedback = Runnable { binding.gestureFeedback.isVisible = false }
    private var retiringPip = false

    /** Mirrors the built-in controller's visibility (gear / seek controls). */
    private var controllerVisible = false

    // ----- Description (fold / unfold) -----
    private var descExpanded = false
    private var descBoundFor: String? = null

    // ----- Comments -----
    private var commentsFor: String? = null
    private var commentsPage: CommentsPage? = null
    private var commentsJob: Job? = null

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) {
            updateQueueControls()
            if (entry != null && entry.url != loadedUrl) {
                loadedUrl = entry.url
                bind(entry)
            }
        }

        override fun onQueueChanged() = updateQueueControls()
        override fun onSleepTimerExpired() { finish() }
        override fun onSleepTimerChanged() {
            binding.actionTimerLabel.text = com.play.tube.ui.music.SleepTimerSheet.buttonLabel(this@PlayerActivity)
            binding.actionTimer.contentDescription = com.play.tube.ui.music.SleepTimerSheet.accessibilityLabel(this@PlayerActivity)
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) = updateQueueControls()

        override fun onVideoFrameRendered() {
            binding.errorView.isVisible = false
            binding.loading.isVisible = false
        }

        override fun onResolvingChanged(isResolving: Boolean) {
            updateQueueControls()
            binding.loading.isVisible = isResolving && !binding.errorView.isVisible
        }

        override fun onCatalogReady() {
            updateQueueControls()
            bindCatalog()
        }

        override fun onError(message: String) {
            binding.loading.isVisible = false
            binding.errorText.text = message
            binding.errorView.isVisible = true
        }

        override fun onLiveContentBlocked() {
            Toast.makeText(this@PlayerActivity, R.string.live_not_supported, Toast.LENGTH_SHORT).show()
            finish()
        }

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = updateFavoriteUi()

        override fun onAudioOnlyChanged(audioOnly: Boolean) = bindCatalog()

        override fun onNotice(message: String) {
            Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private var loadedUrl: String? = null

    private val downloadHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        // The page is a translucent window: while it is dragged down the screen
        // underneath (home, search, ...) shows through instead of black.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // A fresh watch page must not leave the previous pinned Activity behind.
        PlaybackCenter.closePreviousPip()

        // Expands UP out of the mini player (and collapses back down).
        PanelTransitions.install(this)

        // Edge-to-edge from the very first frame — THE punch-hole fix:
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES alone only lets the
        // WINDOW cover the cutout area; with the default decor-fits-system-
        // windows behaviour the framework still pads the content away from
        // the cutout, so the notch stayed a black band in fullscreen.
        // Going fully edge-to-edge + SHORT_EDGES lets the video really render
        // under the punch-hole in every scale mode (insets are then applied
        // manually to the overlay buttons below).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        // Watching a video should keep the screen on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The fullscreen + scale buttons only appear together with the
        // player's own controls (gear / seek bar), like the official app.
        binding.fullscreenButton.isVisible = false
        binding.settingsButton.setOnClickListener { showVideoSettingsSheet() }
        // Explicit object: a lambda would be ambiguous between the two
        // setControllerVisibilityListener overloads.
        binding.playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    controllerVisible = visibility == View.VISIBLE
                    updateQueueControls()
                    syncFullscreenButtonVisibility()
                }
            }
        )

        // Watch-page chrome that depends on the resolved catalog: the action
        // pills and the subscribe button only appear once the video is
        // actually loaded (they used to flash in with placeholder labels
        // the moment the screen opened).
        binding.actionRow.isVisible = false
        binding.subscribeButton.isVisible = false

        // Foldable description + comments entry (both appear once the
        // video's catalog has loaded).
        binding.description.setLinkTextColor(Themes.accentColor(this))
        binding.descCard.setOnClickListener { toggleDescription() }
        binding.descToggle.setOnClickListener { toggleDescription() }
        binding.commentsCard.setOnClickListener { openComments() }

        relatedAdapter = VideoAdapter(onClick = { model ->
            // Play inside this page so swipe/mini flow keeps working.
            val entry = model.toQueueEntry()
            PlaybackCenter.playVideo(entry)
        })
        binding.relatedList.layoutManager = LinearLayoutManager(this)
        binding.relatedList.adapter = relatedAdapter

        binding.retryButton.setOnClickListener { retry() }
        // Same collapse action as NowPlayingActivity: keep the shared queue/player alive.
        binding.backButton.setOnClickListener { finish() }

        binding.actionFavorite.setOnClickListener { toggleFavorite() }
        binding.actionPlaylist.setOnClickListener { showPlaylistSheet() }
        binding.actionShare.setOnClickListener { shareVideo() }
        binding.actionDownload.setOnClickListener { onDownloadClicked() }
        binding.actionTimerLabel.text = com.play.tube.ui.music.SleepTimerSheet.buttonLabel(this)
        binding.actionTimer.setOnClickListener {
            com.play.tube.ui.music.SleepTimerSheet(this).show()
        }
        binding.modeSwitch.setOnClickListener { openAudioView() }
        binding.playerView.setShowRewindButton(false)
        binding.playerView.setShowFastForwardButton(false)
        binding.queuePrevious.setOnClickListener {
            PlaybackCenter.previous()
            binding.playerView.showController()
        }
        binding.queueNext.setOnClickListener {
            PlaybackCenter.next()
            binding.playerView.showController()
        }
        binding.queuePlayPause.setOnClickListener {
            PlaybackCenter.togglePlayPause()
            binding.playerView.showController()
            updateQueueControls()
        }

        binding.channelInfo.setOnClickListener { openChannel() }
        binding.subscribeButton.setOnClickListener { toggleSubscribe() }

        // Fullscreen toggle: portrait <-> landscape without restarting the activity.
        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }

        applyScaleMode()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeTriggered = false
                fullscreenSwipeDone = false
                gestureAxis = PlayerGestureMath.Axis.NONE
                portraitGestureRejected = false
                gestureStartPosition = PlaybackCenter.positionMs
                pendingSeekTarget = null
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                gestureStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                gestureStartBrightness = window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: (android.provider.Settings.System.getInt(contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // A tap right after a double-tap skip keeps skipping in that direction.
                if (canSeekFromVideoSurface() &&
                    android.os.SystemClock.elapsedRealtime() - lastSkipAt < SKIP_CHAIN_MS) {
                    skipByStep(e.x >= binding.playerView.width / 2f)
                    return true
                }
                if (binding.playerView.isControllerFullyVisible) binding.playerView.hideController()
                else binding.playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Preserve intentional double-tap seeks in both orientations.
                if (!canSeekFromVideoSurface()) return true
                skipByStep(e.x >= binding.playerView.width / 2f)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Hold anywhere on the picture for 2x speed (until the finger lifts).
                if (gestureAxis != PlayerGestureMath.Axis.NONE || portraitGestureRejected) return
                if (!PlaybackCenter.hasMedia || longPressSpeedActive) return
                longPressSpeedActive = true
                speedBeforeLongPress = PlaybackCenter.playbackSpeed
                PlaybackCenter.setSpeed(2.0f)
                showGestureFeedback(getString(R.string.gesture_hold_speed))
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false
                if (pinchedThisGesture) return true
                // Raw screen coordinates: the page itself moves while it is dragged
                // down, so view-local coordinates would feed back and make it jitter.
                val totalY = start.rawY - e2.rawY
                val totalX = e2.rawX - start.rawX
                if (portraitGestureRejected) return true
                if (gestureAxis == PlayerGestureMath.Axis.NONE) {
                    gestureAxis = PlayerGestureMath.chooseAxis(totalX, totalY, dp(14).toFloat())
                    if (PlayerGestureMath.rejectAmbiguousPortraitMovement(
                            gestureStartedInLandscape, totalX, totalY, dp(14).toFloat())) {
                        portraitGestureRejected = true
                        return true
                    }
                    if (gestureAxis != PlayerGestureMath.Axis.NONE) {
                        swipeTriggered = true
                    }
                }
                if (gestureAxis == PlayerGestureMath.Axis.NONE) return true
                if (gestureAxis == PlayerGestureMath.Axis.HORIZONTAL) {
                    // Still consume this gesture: dragging into the bar must not
                    // transfer ownership or synthesize a new seek-bar ACTION_DOWN.
                    if (!canSeekFromVideoSurface()) return true
                    val pixelsPerTenSeconds = PlayerGestureMath.pixelsPerTenSeconds(
                        binding.playerView.width.toFloat(), PlaybackCenter.durationMs
                    )
                    pendingSeekTarget = PlayerGestureMath.horizontalSeekTarget(
                        gestureStartPosition, PlaybackCenter.durationMs, totalX, pixelsPerTenSeconds
                    )
                    showGestureFeedback(formatGestureSeek(pendingSeekTarget!!))
                    return true
                }
                val width = binding.playerView.width
                val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (!landscape) {
                    // Portrait: no volume / brightness. Pull down = minimize, push up = fullscreen.
                    val pull = -totalY - dp(14)
                    if (pull > 0f) {
                        minimizeDragging = true
                        val h = resources.displayMetrics.heightPixels.toFloat()
                        val progress = (pull / (h * 0.6f)).coerceIn(0f, 1f)
                        binding.root.pivotX = binding.root.width / 2f
                        binding.root.pivotY = 0f
                        binding.root.translationY = pull * 0.9f
                        // Page shrinks a little as it is pulled; the home screen shows through behind it.
                        val s = 1f - 0.10f * progress
                        binding.root.scaleX = s; binding.root.scaleY = s
                        binding.root.alpha = (1f - 0.35f * progress)
                    } else if (totalY > dp(90) && !minimizeDragging && !fullscreenSwipeDone) {
                        fullscreenSwipeDone = true
                        toggleFullscreen()
                    }
                    return true
                }
                // Landscape: centre swipe-down leaves, sides = brightness / volume.
                val startX = start.rawX - binding.playerView.let { v ->
                    val loc = IntArray(2); v.getLocationOnScreen(loc); loc[0].toFloat() }
                if (startX > width * 0.35f && startX < width * 0.65f) {
                    if (totalY < -dp(100)) finish()
                    return true
                }
                val fraction = totalY / (binding.playerView.height * 0.75f).coerceAtLeast(dp(220).toFloat())
                if (startX >= width / 2f) {
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val volume = PlayerGestureMath.volume(gestureStartVolume, fraction, max)
                    try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
                    catch (_: SecurityException) { return true }
                    showGestureFeedback(getString(R.string.gesture_volume, if (max > 0) volume * 100 / max else 0))
                } else {
                    val brightness = PlayerGestureMath.brightness(gestureStartBrightness, fraction)
                    window.attributes = window.attributes.apply { screenBrightness = brightness }
                    showGestureFeedback(getString(R.string.gesture_brightness, (brightness * 100).toInt()))
                }
                return true
            }
        })

        // Pinch out = fullscreen / fill the screen, pinch in = back to fit / exit fullscreen.
        scaleDetector = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                    pinchFactor = 1f
                    pinchedThisGesture = true
                    return true
                }

                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    pinchFactor *= detector.scaleFactor
                    return true
                }

                override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                    handlePinch(pinchFactor)
                }
            }
        )
        // Quick-scale (double-tap + drag) would fight the double-tap skip gesture.
        scaleDetector.isQuickScaleEnabled = false

        hookGearMenu()
        hookScrubPreview()

        applyOrientation(resources.configuration)

        if (intent.getBooleanExtra(EXTRA_ATTACH, false) && PlaybackCenter.hasMedia) {
            loadedUrl = PlaybackCenter.currentEntry?.url
            PlaybackCenter.currentEntry?.let { showCoverUntilFirstFrame(it.thumbnailUrl) }
            PlaybackCenter.currentEntry?.let(::bindAfterFirstFrame)
        } else {
            val entry = entryFromIntent()
            if (entry == null) {
                finish()
                return
            }
            if (PlaybackCenter.currentEntry?.url == entry.url && PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO) {
                PlaybackCenter.switchBackToVideo()
                loadedUrl = entry.url
                bindAfterFirstFrame(entry)
            } else {
                PlaybackCenter.playVideo(entry)
            }
        }
    }

    /** Keep unavailable queue actions visible but dimmed, never replace them with seek. */
    private fun updateQueueControls() {
        if (!::binding.isInitialized) return
        val player = PlaybackCenter.sessionPlayer
        listOf(
            binding.queuePrevious to Player.COMMAND_SEEK_TO_PREVIOUS,
            binding.queueNext to Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        ).forEach { (button, command) ->
            button.isEnabled = player.isCommandAvailable(command)
            button.alpha = if (button.isEnabled) 1f else 0.35f
        }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        binding.queuePlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        binding.queuePlayPause.contentDescription = getString(
            if (playing) Media3R.string.exo_controls_pause_description else Media3R.string.exo_controls_play_description)
        binding.transportControls.isVisible = controllerVisible && !isInPictureInPictureMode
        if (player.playbackState != Player.STATE_ENDED && autoplayTimer != null) cancelAutoplayUi()
    }

    // ----- Autoplay countdown -----

    private fun startAutoplayCountdown(): Boolean {
        if (isInPictureInPictureMode || isFinishing || !::binding.isInitialized) return false
        val next = PlaybackCenter.nextQueueEntry() ?: return false
        cancelAutoplayUi()
        binding.autoplayTitle.text = next.title
        binding.autoplayOverlay.isVisible = true
        binding.autoplayCountdown.text = getString(R.string.autoplay_up_next, 5)
        binding.autoplayCancel.setOnClickListener { cancelAutoplayUi() }
        binding.autoplayPlayNow.setOnClickListener {
            cancelAutoplayUi()
            PlaybackCenter.proceedAutoplay()
        }
        autoplayTimer = object : android.os.CountDownTimer(5_000L, 1_000L) {
            override fun onTick(msLeft: Long) {
                binding.autoplayCountdown.text =
                    getString(R.string.autoplay_up_next, ((msLeft + 999) / 1000).toInt())
            }

            override fun onFinish() {
                autoplayTimer = null
                binding.autoplayOverlay.isVisible = false
                PlaybackCenter.proceedAutoplay()
            }
        }.start()
        return true
    }

    private fun cancelAutoplayUi() {
        autoplayTimer?.cancel()
        autoplayTimer = null
        if (::binding.isInitialized) binding.autoplayOverlay.isVisible = false
    }

    // ----- Seek-bar thumbnail preview -----

    private fun hookScrubPreview() {
        val bar = binding.playerView.findViewById<View>(Media3R.id.exo_progress) as? DeliberateSeekTimeBar ?: return
        bar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                showPreview(bar, position)
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                showPreview(bar, position)
            }

            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                binding.previewCard.isVisible = false
            }
        })
    }

    private fun formatClock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun showPreview(bar: View, position: Long) {
        val duration = PlaybackCenter.durationMs
        if (duration <= 0) return
        val card = binding.previewCard
        binding.previewTime.text = formatClock(position)
        // Chapter title under the time, when the video has chapters.
        val chapter = PlaybackCenter.catalog?.chapters?.lastOrNull { it.startSec * 1000L <= position }
        if (chapter != null) binding.previewTime.text = "${formatClock(position)} · ${chapter.title}"
        card.isVisible = true
        // Horizontal position follows the scrubber.
        val barLoc = IntArray(2); bar.getLocationOnScreen(barLoc)
        val rootLoc = IntArray(2); binding.root.getLocationOnScreen(rootLoc)
        val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
        val centerX = barLoc[0] - rootLoc[0] + bar.width * fraction
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val maxX = (binding.root.width - card.measuredWidth).coerceAtLeast(0).toFloat()
        card.translationX = (centerX - card.measuredWidth / 2f).coerceIn(0f, maxX)
        bindPreviewFrame(position)
    }

    private fun bindPreviewFrame(position: Long) {
        val frames = PlaybackCenter.catalog?.previewFrames
        if (frames == null) { binding.previewImage.isVisible = false; return }
        val key = PlaybackCenter.currentEntry?.url
        if (key != previewUrlKey) {
            previewUrlKey = key
            previewSheets.clear(); previewLoading.clear()
        }
        val perPage = (frames.framesPerPageX * frames.framesPerPageY).coerceAtLeast(1)
        val index = (position / frames.durationPerFrame).toInt().coerceIn(0, (frames.totalCount - 1).coerceAtLeast(0))
        val page = (index / perPage).coerceAtMost(frames.urls.size - 1)
        val sheet = previewSheets[page]
        if (sheet == null) {
            binding.previewImage.isVisible = false
            if (previewLoading.add(page)) {
                val url = frames.urls[page]
                lifecycleScope.launch {
                    val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            c.setRequestProperty("User-Agent", "Mozilla/5.0")
                            c.connectTimeout = 8000; c.readTimeout = 8000
                            c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                        }.getOrNull()
                    }
                    previewLoading.remove(page)
                    if (bmp != null && key == previewUrlKey) previewSheets[page] = bmp
                }
            }
            return
        }
        val inPage = index % perPage
        val col = inPage % frames.framesPerPageX
        val row = inPage / frames.framesPerPageX
        val x = col * frames.frameWidth
        val y = row * frames.frameHeight
        if (x + frames.frameWidth > sheet.width || y + frames.frameHeight > sheet.height) return
        binding.previewImage.setImageBitmap(
            android.graphics.Bitmap.createBitmap(sheet, x, y, frames.frameWidth, frames.frameHeight)
        )
        binding.previewImage.isVisible = true
    }

    private fun openAudioView() {
        PlaybackCenter.switchToAudioOnly()
        com.play.tube.ui.music.NowPlayingActivity.start(this)
        finish()
    }

    private fun showGestureFeedback(text: String) {
        binding.gestureFeedback.text = text
        binding.gestureFeedback.isVisible = true
        downloadHandler.removeCallbacks(hideGestureFeedback)
        downloadHandler.postDelayed(hideGestureFeedback, 900)
    }

    private fun formatGestureSeek(positionMs: Long): String {
        val seconds = positionMs / 1_000L
        val time = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        val delta = (positionMs - gestureStartPosition) / 1_000L
        val sign = if (delta >= 0) "+" else "-"
        return "$time  ($sign${kotlin.math.abs(delta)}s)"
    }

    /** Skips by the configured step; quick repeated skips in one direction accumulate. */
    private fun skipByStep(forward: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSkipAt > SKIP_CHAIN_MS || forward != lastSkipForward) skipAccumulatedSec = 0
        lastSkipAt = now
        lastSkipForward = forward
        val step = AppPrefs.seekStepSec
        PlaybackCenter.seekTo(
            PlayerGestureMath.seekTarget(
                PlaybackCenter.positionMs, PlaybackCenter.durationMs, forward, step * 1_000L
            )
        )
        skipAccumulatedSec += step
        showSkipRipple(forward, skipAccumulatedSec)
    }

    /** Soft half-circle flash on the tapped side with "▶▶ 10 seconds". */
    private fun showSkipRipple(forward: Boolean, seconds: Int) {
        val v = if (forward) binding.rippleRight else binding.rippleLeft
        val other = if (forward) binding.rippleLeft else binding.rippleRight
        other.animate().cancel(); other.alpha = 0f; other.isVisible = false
        v.text = (if (forward) "▶▶\n" else "◀◀\n") + getString(R.string.skip_seconds, seconds)
        v.animate().cancel()
        v.isVisible = true
        if (v.alpha < 0.5f) {
            // Fresh tap: pop in from slightly smaller; chained taps just pulse.
            v.scaleX = 0.82f; v.scaleY = 0.82f
        }
        v.alpha = 1f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        rippleHide?.let { v.removeCallbacks(it) }
        rippleHide = Runnable {
            v.animate().alpha(0f).setDuration(320).withEndAction { v.isVisible = false }.start()
        }
        v.postDelayed(rippleHide, SKIP_CHAIN_MS.coerceAtLeast(500L))
        com.play.tube.util.Haptics.soft(v)
    }

    /** Pinch gesture result: >1 zoom in (fullscreen / fill), <1 zoom out (fit / exit fullscreen). */
    private fun handlePinch(factor: Float) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        when {
            factor > 1.12f -> {
                if (!landscape) {
                    toggleFullscreen()
                } else if (AppPrefs.fullscreenScale == AppPrefs.SCALE_FIT) {
                    AppPrefs.fullscreenScale = AppPrefs.SCALE_CROP
                    applyScaleMode()
                    showGestureFeedback(getString(R.string.scale_crop))
                }
            }
            factor < 0.88f -> {
                if (landscape) {
                    if (AppPrefs.fullscreenScale != AppPrefs.SCALE_FIT) {
                        AppPrefs.fullscreenScale = AppPrefs.SCALE_FIT
                        applyScaleMode()
                        showGestureFeedback(getString(R.string.scale_fit))
                    } else {
                        toggleFullscreen()
                    }
                }
            }
        }
    }

    private fun canSeekFromVideoSurface(): Boolean = PlayerGestureMath.canSeekVideoSurface(
        gestureStartedInLandscape,
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    )

    // Dispatch above the controller's empty scrim, but leave real buttons and the seek bar alone.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::gestureDetector.isInitialized || isInPictureInPictureMode) return super.dispatchTouchEvent(event)
        val point = IntArray(2)
        binding.playerView.getLocationOnScreen(point)
        val x = event.rawX - point[0]
        val y = event.rawY - point[1]
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            portraitGestureRejected = false
            pinchedThisGesture = false
            gestureStartedInLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val inside = x >= 0 && x < binding.playerView.width && y >= 0 && y < binding.playerView.height
            gestureTracking = inside && !hitsPlayerControl(binding.root, event.rawX.toInt(), event.rawY.toInt())
        }
        if (gestureTracking) {
            scaleDetector.onTouchEvent(event)
            val local = MotionEvent.obtain(event)
            local.setLocation(x, y)
            if (!gestureStartedInLandscape && (event.pointerCount > 1 ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
                local.action = MotionEvent.ACTION_CANCEL
                gestureDetector.onTouchEvent(local)
                portraitGestureRejected = true
                pendingSeekTarget = null
            } else if (!portraitGestureRejected) {
                gestureDetector.onTouchEvent(local)
            }
            local.recycle()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (longPressSpeedActive) {
                    longPressSpeedActive = false
                    PlaybackCenter.setSpeed(speedBeforeLongPress)
                    binding.gestureFeedback.isVisible = false
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && !portraitGestureRejected && canSeekFromVideoSurface()) {
                    pendingSeekTarget?.let(PlaybackCenter::seekTo)
                }
                pendingSeekTarget = null
                gestureTracking = false
                if (minimizeDragging) {
                    minimizeDragging = false
                    val ty = binding.root.translationY
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    if (event.actionMasked == MotionEvent.ACTION_UP && ty > dp(110)) {
                        // Finish the slide ourselves, then close with no second animation.
                        binding.root.animate().translationY(h).alpha(0f).scaleX(0.88f).scaleY(0.88f)
                            .setDuration(((h - ty) / h * 260f).toLong().coerceIn(120L, 260L))
                            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                            .withEndAction {
                                skipExitTransition = true
                                finish()
                            }.start()
                    } else {
                        binding.root.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                    }
                }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun hitsPlayerControl(view: View, x: Int, y: Int): Boolean {
        if (!view.isShown) return false
        val rect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return false
        if (view is DeliberateSeekTimeBar &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return view.hitsVisibleSeekBar((x - location[0]).toFloat(), (y - location[1]).toFloat())
        }
        if (view is android.widget.ImageButton || view is android.widget.Button ||
            view is androidx.media3.ui.DefaultTimeBar || view === binding.modeSwitch) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) if (hitsPlayerControl(view.getChildAt(i), x, y)) return true
        }
        return false
    }

    /**
     * Puts quality / speed / audio-track selection behind the gear button of
     * the player's built-in controller.
     */
    private fun hookGearMenu() {
        val gear = binding.playerView.findViewById<View>(Media3R.id.exo_settings)
        // Settings live in the top-right overlay button now.
        gear?.visibility = View.GONE
    }

    /** All overlay buttons follow the built-in controller's visibility. */
    private fun syncFullscreenButtonVisibility() {
        val visible = !isInPictureInPictureMode && controllerVisible
        // Single-tap reveal and auto-hide apply to collapse just like transport.
        binding.backButton.isVisible = visible
        binding.transportControls.isVisible = visible
        binding.fullscreenButton.isVisible = visible
        binding.modeSwitch.isVisible = visible
        binding.settingsButton.isVisible = visible
    }

    /** Latest top inset from the status bar / cutout (0 once bars are hidden). */
    private var lastTopInset = 0

    /**
     * Keeps the whole video visible on the portrait watch page: the player
     * box sits BELOW the status bar / punch-hole. The window being
     * edge-to-edge (needed for fullscreen) used to slide the video under
     * them, cutting off its top strip in normal mode. Landscape fullscreen
     * keeps topMargin 0 so the video still renders under the (hidden) bars
     * and the cutout — the punch-hole fullscreen fix. PiP gets 0 too (the
     * floating window has no insets).
     */
    private fun applyPlayerTopInset() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topMargin = if (landscape || isInPictureInPictureMode) 0 else lastTopInset
        }
    }

    /**
     * Manual insets now that the window draws edge-to-edge: the overlay
     * buttons keep a safe distance from the status bar / cutout and the
     * page content clears the gesture bar.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val rtl = root.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val startInset = if (rtl) bars.right else bars.left
            val endInset = if (rtl) bars.left else bars.right

            binding.backButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                // Portrait playerView already clears the status bar; don't add it twice.
                topMargin = dp(4) + if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) bars.top else 0
                marginStart = dp(4) + startInset
            }
            binding.fullscreenButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                marginEnd = endInset
                bottomMargin = dp(4)
            }
            binding.modeSwitch.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = dp(4)
                marginEnd = dp(2)
            }
            binding.settingsButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = dp(6)
                marginEnd = endInset + dp(6)
            }
            lastTopInset = bars.top
            applyPlayerTopInset()
            binding.scrollArea.setPadding(
                binding.scrollArea.paddingLeft,
                binding.scrollArea.paddingTop,
                binding.scrollArea.paddingRight,
                bars.bottom + dp(24)
            )
            insets
        }
    }

    /** Real fullscreen: hide status + navigation bars while landscape. */
    private fun applyImmersive(landscape: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ----- Fullscreen scale mode -----

    /**
     * Applies the scale preference (fit / crop / stretch). It is a FULLSCREEN
     * setting: the portrait watch page is always RESIZE_MODE_FIT so no part
     * of the video is ever cropped inside the normal player box.
     */
    private fun applyScaleMode() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val mode = if (landscape) when (AppPrefs.fullscreenScale) {
            AppPrefs.SCALE_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AppPrefs.SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = mode
    }


    private fun toggleFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (retiringPip || isFinishing) return
        PlaybackCenter.addListener(playbackListener)
        PlaybackCenter.autoplayInterceptor = { startAutoplayCountdown() }
        // A visible PiP activity may be STARTED/PAUSED, never RESUMED.
        PlaybackCenter.setPipState(binding.playerView, isInPictureInPictureMode, ::closeReplacedPip)
        PlaybackCenter.attachView(binding.playerView)
        updateQueueControls()
        // Playback can start resolving BEFORE this page is listening (Play
        // all / a playlist video starts the queue, then opens the page), so
        // pick the spinner state up here instead of missing it.
        binding.loading.isVisible = PlaybackCenter.isResolving && !binding.errorView.isVisible
        downloadHandler.post(downloadPoll)
    }

    override fun onResume() {
        super.onResume()
        if (retiringPip || isFinishing) return
        if (Themes.recreateIfNeeded(this)) return
        applyCloseTransition()
        PlaybackCenter.attachView(binding.playerView)
    }

    // ----- Close animation -----

    /**
     * Android 14+: registers the close animation once for this page. The
     * default activity-close animation slides the page sideways (left to
     * right on most devices), which reads wrong when the video is really
     * collapsing DOWN into the mini player at the bottom of the screen.
     */
    private fun applyCloseTransition() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (AppPrefs.animations) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_down_out)
        } else {
            clearOverrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)
        }
    }

    /**
     * Every way out of the watch page (swipe down, back button / gesture,
     * "play in background") collapses into the mini player, so all of them
     * slide down. Older Android has no per-activity registration: the
     * override has to be requested right after finish().
     */
    @Suppress("DEPRECATION")
    override fun finish() {
        if (skipExitTransition) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            }
            super.finish()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overridePendingTransition(0, 0)
            return
        }
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && AppPrefs.animations) {
            overridePendingTransition(0, R.anim.slide_down_out)
        }
    }

    // Do NOT detach in onPause: Android/ROMs can pause us before delivering
    // the PiP-mode callback. PiP stays visible while paused and needs the
    // same TextureView output. Detach only once the activity is not visible.

    override fun onStop() {
        super.onStop()
        PlaybackCenter.autoplayInterceptor = null
        if (autoplayTimer != null) {
            // Left the page mid-countdown: hand over right away, as before.
            cancelAutoplayUi()
            PlaybackCenter.proceedAutoplay()
        }
        PlaybackCenter.detachView(binding.playerView)
        // A stopped/temporarily hidden pinned Activity still needs to be
        // reachable for replacement cleanup; only a real exit releases it.
        if (!isInPictureInPictureMode || isFinishing || retiringPip) {
            PlaybackCenter.setPipState(binding.playerView, false)
        }
        if (longPressSpeedActive) {
            longPressSpeedActive = false
            PlaybackCenter.setSpeed(speedBeforeLongPress)
        }
        downloadHandler.removeCallbacks(downloadPoll)
        downloadHandler.removeCallbacks(hideGestureFeedback)
        PlaybackCenter.removeListener(playbackListener)
        // Return to sensor portrait so the next video starts the standard way.
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onDestroy() {
        coverListener?.let { runCatching { PlaybackCenter.player.removeListener(it) } }
        PlaybackCenter.setPipState(binding.playerView, false)
        PlaybackCenter.detachView(binding.playerView)
        super.onDestroy()
    }

    // ----- PiP -----

    private fun closeReplacedPip() {
        if (retiringPip) return
        retiringPip = true
        PlaybackCenter.removeListener(playbackListener)
        PlaybackCenter.detachView(binding.playerView)
        // Finish the pinned Activity itself, not just its surface or global flag.
        // Never stop/pause the shared engine: it is about to play the new item.
        finish()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (retiringPip || isFinishing) return
        PlaybackCenter.setPipState(binding.playerView, isInPictureInPictureMode, ::closeReplacedPip)
        // Recover ownership even if pause/configuration callbacks arrived
        // first. attachView is idempotent and uses the raw rendering player.
        if (isInPictureInPictureMode) PlaybackCenter.attachView(binding.playerView)
        binding.playerView.useController = !isInPictureInPictureMode
        applyOrientation(newConfig)
        if (!isInPictureInPictureMode) binding.playerView.showController()
    }

    private fun applyPipLayout() {
        // Both mode and configuration callbacks use this path: orientation
        // must never restore the watch page over the tiny PiP video output.
        binding.transportControls.isVisible = false
        binding.playerView.hideController()
        binding.backButton.isVisible = false
        binding.fullscreenButton.isVisible = false
        binding.settingsButton.isVisible = false
        binding.modeSwitch.isVisible = false
        binding.gestureFeedback.isVisible = false
        binding.scrollArea.isVisible = false
        binding.loading.isVisible = false
        binding.errorView.isVisible = false
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
            dimensionRatio = null
            topMargin = 0
        }
    }

    // ----- Orientation -----

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(config: Configuration) {
        if (isInPictureInPictureMode) {
            applyPipLayout()
            return
        }
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) {
            // Do not carry a fullscreen-only brightness override back to portrait.
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            binding.gestureFeedback.isVisible = false
        }
        // This activity handles rotation without reinflating the controller.
        // Clear only the portrait bottom strip; restore Media3's original
        // background in landscape without changing seek layout or behavior.
        binding.playerView.findViewById<View>(Media3R.id.exo_bottom_bar)?.setBackgroundResource(
            if (landscape) Media3R.color.exo_bottom_bar_background else android.R.color.transparent
        )
        // The portrait-only dimension is also read by Media3's layout manager,
        // so resizing cannot put the old 52dp gap back. Apply it immediately
        // on rotation too, before the next controller size/layout callback.
        binding.playerView.findViewById<View>(Media3R.id.exo_progress)
            ?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = resources.getDimensionPixelSize(Media3R.dimen.exo_styled_progress_margin_bottom)
            }
        // Keep settings clickable above the lowered 48dp seek touch target.
        // Empty transparent row space still passes touches through to the bar.
        // Restore the original zero Z/order for landscape controls.
        binding.playerView.findViewById<View>(Media3R.id.exo_bottom_bar)?.translationZ =
            if (landscape) 0f else dp(1).toFloat()
        binding.fullscreenButton.setImageResource(
            if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        // Landscape video = immersive: no notification bar, no nav bar, the
        // video fills the whole screen. Portrait restores normal system bars.
        applyImmersive(landscape)
        if (landscape) {
            binding.scrollArea.isVisible = false
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
        } else {
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = 0
                dimensionRatio = "16:9"
            }
        }
        // Orientation-dependent state: the scale preference is
        // fullscreen-only and the portrait box needs its status-bar offset.
        applyScaleMode()
        applyPlayerTopInset()
        syncFullscreenButtonVisibility()
    }

    // ----- Binding -----

    private fun entryFromIntent(): QueueEntry? {
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        return QueueEntry(
            url = url,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty(),
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMB).orEmpty(),
            durationSec = intent.getLongExtra(EXTRA_DURATION, 0L)
        )
    }

    /**
     * Opening from the mini player must feel instant: show the cheap parts
     * (title, uploader) at once and do the heavy binding (history / download
     * state / related ranking / description parsing) after the first frame.
     */
    private var coverListener: androidx.media3.common.Player.Listener? = null

    /**
     * The mini player hands its video surface over to this page. Until the new
     * surface draws its first frame the thumbnail is shown instead of black.
     */
    private fun showCoverUntilFirstFrame(thumbUrl: String) {
        if (thumbUrl.isBlank()) return
        val cover = binding.playerCover
        cover.alpha = 1f
        cover.isVisible = true
        com.play.tube.util.Thumbs.load(cover, thumbUrl)
        var done = false
        fun hide() {
            if (done) return
            done = true
            coverListener?.let { runCatching { PlaybackCenter.player.removeListener(it) } }
            coverListener = null
            cover.animate().alpha(0f).setDuration(180).withEndAction { cover.isVisible = false }.start()
        }
        val l = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { hide() }
        }
        coverListener = l
        PlaybackCenter.player.addListener(l)
        // Start drawing into this page's surface right away, not after the window animation.
        PlaybackCenter.attachView(binding.playerView)
        cover.postDelayed({ hide() }, 1100)
    }

    private fun bindAfterFirstFrame(entry: QueueEntry) {
        binding.title.text = entry.title
        binding.channelName.text = entry.uploader
        binding.root.doOnPreDraw {
            binding.root.post {
                if (!isFinishing && !isDestroyed) bind(entry)
            }
        }
    }

    private fun bind(entry: QueueEntry) {
        cancelAutoplayUi()
        binding.errorView.isVisible = false
        binding.title.text = entry.title
        binding.views.text = ""
        binding.channelName.text = entry.uploader
        binding.channelSubs.text = ""
        binding.channelAvatar.setImageResource(0)
        loadChannelInfo()

        updateFavoriteUi()
        updateDownloadUi()

        // History + favourites may have changed, list them below.
        bindCatalog()
    }

    /** Pushes chapter boundaries to the seek bar (no-op when none). */
    private fun bindChapterMarkers(catalog: StreamCatalog?) {
        val bar = binding.playerView.findViewById<View>(Media3R.id.exo_progress)
            as? DeliberateSeekTimeBar ?: return
        val starts = catalog?.chapters?.map { it.startSec * 1000L }?.toLongArray() ?: LongArray(0)
        bar.setChapterMarkers(starts)
    }

    private fun bindCatalog() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog

        // Pills + subscribe stay hidden until the catalog (uploader info,
        // views, related list) has actually loaded for this video.
        binding.actionRow.isVisible = catalog != null
        bindChapterMarkers(catalog)

        if (catalog != null) {
            binding.title.text = catalog.name.ifBlank { entry.title }
            bindDescription(catalog, entry.url)
            maybeLoadComments(entry.url)
            binding.channelName.text = catalog.uploaderName.ifBlank { entry.uploader }
            if (catalog.uploaderAvatarUrl.isNotBlank()) {
                Thumbs.load(binding.channelAvatar, catalog.uploaderAvatarUrl)
            }
            // Up-next list: YouTube's order blended with the on-device taste profile.
            val snap = com.play.tube.data.RecommendEngine.snapshot(this)
            val rel = catalog.related
            val ranked = if (!snap.hasSignal) rel else rel.withIndex()
                .sortedByDescending { (i, item) ->
                    com.play.tube.data.RecommendEngine.score(item, snap) + (rel.size - i) * 0.08
                }.map { it.value }
            relatedAdapter.submitList(ranked.map { it.toUiModel() })
        } else {
            // New video still resolving: nothing of the old one may linger.
            binding.descCard.isVisible = false
            descBoundFor = null
            resetComments()
        }
        updateSubscribeUi()
    }

    // ----- Description card -----

    /** Views · date line plus the description, folded to 3 lines by default. */
    private fun bindDescription(catalog: StreamCatalog, url: String) {
        val meta = listOfNotNull(
            catalog.viewCount.takeIf { it >= 0 }
                ?.let { getString(R.string.views_fmt, Formatters.formatViewCount(it)) },
            catalog.uploadDateText.takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        binding.views.text = meta
        binding.descCard.isVisible = true

        // Already bound for this video (e.g. audio-only toggle): keep the
        // user's fold state instead of collapsing under them.
        if (descBoundFor == url) return
        descBoundFor = url
        descExpanded = false
        applyDescriptionState(animate = false)

        val text = CommentAdapter.bodyToText(catalog.description, catalog.descriptionIsHtml)
        val hasDescription = text.isNotBlank()
        binding.description.isVisible = hasDescription
        binding.descCard.isClickable = hasDescription
        binding.descChevron.isVisible = hasDescription
        binding.descToggle.isVisible = false
        if (hasDescription) {
            binding.description.text = text
            // Only offer "Show more" when the text really is cut off.
            binding.description.doOnPreDraw { updateDescToggleVisibility() }
        }
    }

    private fun updateDescToggleVisibility() {
        val layout = binding.description.layout ?: return
        val truncated = layout.lineCount > 0 &&
            layout.getEllipsisCount(layout.lineCount - 1) > 0
        val show = descExpanded || truncated
        binding.descToggle.isVisible = show
        binding.descChevron.isVisible = show
    }

    private fun toggleDescription() {
        if (!binding.description.isVisible) return
        descExpanded = !descExpanded
        applyDescriptionState(animate = true)
    }

    private fun applyDescriptionState(animate: Boolean) {
        val animated = animate && AppPrefs.animations
        if (animated) {
            android.transition.TransitionManager.beginDelayedTransition(
                binding.scrollContent,
                android.transition.ChangeBounds().apply { duration = 200 }
            )
        }
        binding.description.maxLines = if (descExpanded) Int.MAX_VALUE else DESC_COLLAPSED_LINES
        // Links only work while expanded: a link-enabled TextView swallows
        // taps, which would stop the card itself from toggling.
        binding.description.movementMethod =
            if (descExpanded) LinkMovementMethod.getInstance() else null
        binding.descToggle.setText(
            if (descExpanded) R.string.desc_show_less else R.string.desc_show_more
        )
        val target = if (descExpanded) 0f else 180f
        if (animated) {
            binding.descChevron.animate().rotation(target).setDuration(200).start()
        } else {
            binding.descChevron.animate().cancel()
            binding.descChevron.rotation = target
        }
        if (descExpanded) {
            binding.descToggle.isVisible = true
            binding.descChevron.isVisible = true
        }
    }

    // ----- Comments card -----

    private fun resetComments() {
        commentsJob?.cancel()
        commentsJob = null
        commentsFor = null
        commentsPage = null
        binding.commentsCard.isVisible = false
    }

    /** Fetches the first comments page once per video for the preview card. */
    private fun maybeLoadComments(url: String) {
        // Downloaded files have no online comments.
        if (url.startsWith("file://")) {
            binding.commentsCard.isVisible = false
            return
        }
        binding.commentsCard.isVisible = true
        if (commentsFor == url) return
        commentsFor = url
        commentsPage = null
        binding.commentsCount.text = ""
        binding.commentsPreviewRow.isVisible = false
        binding.commentsStatus.setText(R.string.comments_loading)
        binding.commentsStatus.isVisible = true
        commentsJob?.cancel()
        commentsJob = lifecycleScope.launch {
            try {
                val page = YtRepository.comments(url)
                if (commentsFor != url) return@launch
                commentsPage = page
                bindCommentsPreview(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (commentsFor != url) return@launch
                // The sheet retries on open, so a tap on the card still works.
                binding.commentsStatus.setText(R.string.comments_error)
                binding.commentsStatus.isVisible = true
            }
        }
    }

    private fun bindCommentsPreview(page: CommentsPage) {
        binding.commentsCount.text =
            if (page.total > 0) Formatters.formatViewCount(page.total.toLong()) else ""
        when {
            page.disabled -> {
                binding.commentsPreviewRow.isVisible = false
                binding.commentsStatus.setText(R.string.comments_disabled)
                binding.commentsStatus.isVisible = true
            }
            page.items.isEmpty() -> {
                binding.commentsPreviewRow.isVisible = false
                binding.commentsStatus.setText(R.string.comments_empty)
                binding.commentsStatus.isVisible = true
            }
            else -> {
                val top = page.items.first()
                Avatars.load(binding.commentsPreviewAvatar, top.avatarUrl, top.author)
                binding.commentsPreviewText.text = CommentAdapter.commentText(top).toString()
                binding.commentsPreviewRow.isVisible = true
                binding.commentsStatus.isVisible = false
            }
        }
    }

    private fun openComments() {
        val url = PlaybackCenter.currentEntry?.url ?: return
        CommentsSheet(
            context = this,
            scope = lifecycleScope,
            videoUrl = url,
            initial = commentsPage,
            onLoaded = { loaded ->
                if (commentsFor == url) {
                    commentsPage = loaded
                    bindCommentsPreview(loaded)
                }
            }
        ).show()
    }

    private fun loadChannelInfo() {
        val uploaderUrl = PlaybackCenter.catalog?.uploaderUrl ?: return
        lifecycleScope.launch {
            try {
                val channel = YtRepository.channelInfo(uploaderUrl)
                channelSubCount = channel.subscriberCount
                binding.channelSubs.text =
                    getString(R.string.subscribers_fmt, Formatters.formatViewCount(channel.subscriberCount))
                updateSubscribeUi()
            } catch (e: Exception) {
                // Sub count is optional decoration; ignore failures.
            }
        }
    }

    private fun updateFavoriteUi() {
        val entry = PlaybackCenter.currentEntry ?: return
        val isFav = LocalStore.isFavorite(this, entry.url)
        binding.actionFavoriteIcon.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.actionFavoriteIcon.setColorFilter(
            if (isFav) Themes.accentColor(this) else getColor(R.color.on_surface)
        )
        binding.actionFavoriteLabel.text =
            getString(if (isFav) R.string.saved_short else R.string.action_favorite)
    }

    // ----- Download pill -----

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                // Already downloaded: offer to delete it right here instead of
                // sending the user to Library → Downloads.
                confirmDeleteDownload()
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                com.play.tube.ui.MainActivity.openDownloads(this)
            }
            else -> showDownloadSheet()
        }
    }

    /** Deletes every finished download record of the current video. */
    private fun confirmDeleteDownload() {
        val entry = PlaybackCenter.currentEntry ?: return
        com.play.tube.ui.common.PremiumDialog(this)
            .setTitle(R.string.download_delete_confirm)
            .setMessage(entry.title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadCenter.recordsFor(this, entry.url).forEach { record ->
                    DownloadCenter.delete(this, record)
                }
                Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
                updateDownloadUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Add to playlist -----

    /** The current video as a storable entry (playlists reuse VideoEntry). */
    private fun currentVideoEntry(): com.play.tube.data.VideoEntry? =
        PlaybackCenter.currentEntry?.toVideoEntry()

    /**
     * "Add to playlist" picker: every existing playlist (tapping one adds —
     * or removes, when the video is already in it) plus a "New playlist"
     * row that creates one on the spot.
     */
    private fun showPlaylistSheet() {
        val video = currentVideoEntry() ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.playlist_sheet_title)))

        root.addView(
            sheetRow("+ " + getString(R.string.new_playlist), selected = false) {
                sheet.dismiss()
                showNewPlaylistDialog()
            }
        )

        val playlists = LocalStore.playlists(this)
        if (playlists.isEmpty()) {
            root.addView(hintRow(getString(R.string.playlist_sheet_empty)))
        } else {
            playlists.forEach { playlist ->
                val added = playlist.items.any { it.url == video.url }
                root.addView(
                    sheetMenuRow(
                        playlist.name,
                        if (added) getString(R.string.playlist_added) else ""
                    ) {
                        if (added) {
                            LocalStore.removeFromPlaylist(this, playlist.id, video.url)
                            Toast.makeText(
                                this,
                                getString(R.string.removed_from_playlist_fmt, playlist.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            LocalStore.addToPlaylist(this, playlist.id, video)
                            Toast.makeText(
                                this,
                                getString(R.string.saved_to_playlist_fmt, playlist.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        sheet.dismiss()
                    }
                )
            }
        }

        showSheet(sheet, root, peekDp = 380)
    }

    /** Name input dialog that creates a playlist and saves the video into it. */
    private fun showNewPlaylistDialog() {
        val video = currentVideoEntry() ?: return
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.new_playlist_hint)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        com.play.tube.ui.common.PremiumDialog(this)
            .setTitle(R.string.new_playlist)
            .setView(container)
            .setPositiveButton(R.string.create_playlist) { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(
                        this, R.string.playlist_name_empty, Toast.LENGTH_SHORT
                    ).show()

                    else -> {
                        val created = LocalStore.createPlaylist(this, name)
                        if (created == null) {
                            Toast.makeText(
                                this, R.string.playlist_name_exists, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            LocalStore.addToPlaylist(this, created.id, video)
                            Toast.makeText(
                                this,
                                getString(R.string.saved_to_playlist_fmt, name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Small dim helper line used inside bottom sheets. */
    private fun hintRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(4), dp(10), dp(4), dp(6))
        setTextColor(getColor(R.color.on_surface_variant))
    }

    private fun updateDownloadUi() {
        val entry = PlaybackCenter.currentEntry ?: return
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_check)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.music_green))
                binding.actionDownloadLabel.text = getString(R.string.downloaded_label)
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                val percent = DownloadCenter.downloadProgress(this, entry.url)
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_download)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.on_surface))
                binding.actionDownloadLabel.text = getString(R.string.downloading_fmt, percent)
            }
            else -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_download)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.on_surface))
                binding.actionDownloadLabel.text = getString(R.string.action_download)
            }
        }
    }

    private val downloadPoll = object : Runnable {
        override fun run() {
            binding.actionTimerLabel.text = com.play.tube.ui.music.SleepTimerSheet.buttonLabel(this@PlayerActivity)
            binding.actionTimer.contentDescription = com.play.tube.ui.music.SleepTimerSheet.accessibilityLabel(this@PlayerActivity)
            val entry = PlaybackCenter.currentEntry
            if (entry != null && !isFinishing) {
                // Keep the registry in sync with the built-in download
                // engine so a finished download flips to "Downloaded" right
                // away (jobs also self-report, this covers app restarts).
                if (DownloadCenter.hasActiveDownload(this@PlayerActivity, entry.url)) {
                    DownloadCenter.refreshStatuses(this@PlayerActivity)
                }
                updateDownloadUi()
            }
            downloadHandler.postDelayed(this, 1000)
        }
    }

    private fun toggleFavorite() {
        val nowFav = PlaybackCenter.toggleFavoriteCurrent()
        Toast.makeText(
            this,
            if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareVideo() {
        val entry = PlaybackCenter.currentEntry ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, entry.url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    }

    private fun openChannel() {
        val entry = PlaybackCenter.currentEntry ?: return
        val channelUrl = PlaybackCenter.catalog?.uploaderUrl ?: return
        if (channelUrl.isBlank()) return
        ChannelActivity.start(this, channelUrl, entry.uploader)
    }

    private fun toggleSubscribe() {
        val entry = PlaybackCenter.currentEntry ?: return
        val channelUrl = PlaybackCenter.catalog?.uploaderUrl ?: return
        if (channelUrl.isBlank()) return
        val channelEntry = com.play.tube.data.ChannelEntry(
            url = channelUrl,
            name = PlaybackCenter.catalog?.uploaderName ?: entry.uploader,
            avatarUrl = PlaybackCenter.catalog?.uploaderAvatarUrl.orEmpty(),
            subscriberCount = channelSubCount
        )
        val nowSubscribed = LocalStore.toggleSubscription(this, channelEntry)
        Toast.makeText(
            this,
            if (nowSubscribed) R.string.subscribed_toast else R.string.unsubscribed_toast,
            Toast.LENGTH_SHORT
        ).show()
        updateSubscribeUi()
    }

    private fun updateSubscribeUi() {
        val channelUrl = PlaybackCenter.catalog?.uploaderUrl
        if (channelUrl.isNullOrBlank()) {
            binding.subscribeButton.isVisible = false
            return
        }
        // Show it again: onCreate hides the button until the catalog loads,
        // and nothing ever set it back to visible, so it never re-appeared.
        binding.subscribeButton.isVisible = true
        val subscribed = LocalStore.isSubscribed(this, channelUrl)
        binding.subscribeButton.text = getString(if (subscribed) R.string.subscribed else R.string.subscribe)
        binding.subscribeButton.setBackgroundResource(
            if (subscribed) R.drawable.bg_subscribe_on else R.drawable.bg_subscribe_off
        )
        // The "subscribed" pill has a light background in the light theme,
        // so its label must flip to a dark color there (stays white on accent).
        binding.subscribeButton.setTextColor(
            getColor(if (subscribed) R.color.subscribe_on_text else R.color.white)
        )
    }

    private fun retry() {
        binding.errorView.isVisible = false
        PlaybackCenter.retryCurrentVideo()
    }

    // ----- Bottom sheets -----

    private fun sheetRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(24))
    }

    private fun sheetTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(getColor(R.color.on_surface))
        setTypeface(null, Typeface.BOLD)
    }

    private fun sheetRow(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(if (selected) getColor(R.color.white) else Themes.onSurfaceColor(this@PlayerActivity))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(
                    if (selected) Themes.accentColor(this@PlayerActivity)
                    else Themes.elevatedColor(this@PlayerActivity)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /**
     * A category row for the gear menu: title on the left, the currently
     * selected value on the right and a chevron — like the official
     * YouTube player's settings menu.
     */
    private fun sheetMenuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Themes.elevatedColor(this@PlayerActivity))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(this@PlayerActivity).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(this@PlayerActivity).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.on_surface_variant))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
            addView(
                android.widget.ImageView(this@PlayerActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(getColor(R.color.on_surface_variant))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                }
            )
        }

    private fun showSheet(sheet: BottomSheetDialog, root: LinearLayout, peekDp: Int = 420) {
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(peekDp)
        sheet.show()
    }

    /**
     * The gear menu: categories first (quality, playback speed, audio
     * track) — picking one opens its own list, like the official app.
     *
     * Quality and audio-track need the resolved stream catalog, so they only
     * exist for online playback. Downloaded videos have no catalog — they
     * used to hit the "cannot be played" gate here and the whole gear menu
     * was unusable; playback speed works everywhere, so the menu always
     * shows at least that.
     */
    private fun showVideoSettingsSheet() {
        val catalog = PlaybackCenter.catalog

        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.video_settings)))

        if (catalog != null) {
            root.addView(
                sheetMenuRow(getString(R.string.settings_quality_section), qualitySummary(catalog)) {
                    sheet.dismiss()
                    showQualitySheet()
                }
            )
        }
        root.addView(
            sheetMenuRow(getString(R.string.settings_speed_section), speedSummary()) {
                sheet.dismiss()
                showSpeedSheet()
            }
        )
        root.addView(
            sheetMenuRow(getString(R.string.settings_sleep_timer), sleepTimerSummary()) {
                sheet.dismiss()
                com.play.tube.ui.music.SleepTimerSheet(this).show()
            }
        )
        root.addView(
            sheetMenuRow(
                getString(R.string.settings_seek_step),
                getString(R.string.seek_step_seconds, AppPrefs.seekStepSec)
            ) {
                sheet.dismiss()
                showSeekStepSheet()
            }
        )
        root.addView(
            sheetToggleRow(getString(R.string.settings_loop), PlaybackCenter.videoLoop) { on ->
                PlaybackCenter.setVideoLoop(on)
            }
        )
        root.addView(
            sheetToggleRow(getString(R.string.settings_skip_silence), AppPrefs.skipSilence) { on ->
                AppPrefs.skipSilence = on
                PlaybackCenter.setSkipSilence(on)
            }
        )
        if (catalog != null && catalog.audioTracks.size > 1) {
            root.addView(
                sheetMenuRow(getString(R.string.settings_audio_track_section), audioTrackSummary(catalog)) {
                    sheet.dismiss()
                    showAudioTrackSheet()
                }
            )
        }

        if (catalog != null && catalog.subtitles.isNotEmpty()) {
            val current = catalog.subtitles.firstOrNull { it.lang == PlaybackCenter.subtitleLang }
            root.addView(
                sheetMenuRow(
                    getString(R.string.settings_captions),
                    current?.label ?: getString(R.string.captions_off)
                ) {
                    sheet.dismiss()
                    showCaptionsSheet()
                }
            )
        }
        if (catalog != null && catalog.chapters.isNotEmpty()) {
            root.addView(
                sheetMenuRow(
                    getString(R.string.settings_chapters),
                    catalog.chapters.size.toString()
                ) {
                    sheet.dismiss()
                    showChaptersSheet()
                }
            )
        }

        showSheet(sheet, root, peekDp = 520)
    }

    private fun showCaptionsSheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_captions)))
        root.addView(
            sheetRow(getString(R.string.captions_off), PlaybackCenter.subtitleLang == null) {
                PlaybackCenter.setSubtitleLanguage(null)
                sheet.dismiss()
            }
        )
        catalog.subtitles.forEach { sub ->
            root.addView(
                sheetRow(sub.label, PlaybackCenter.subtitleLang == sub.lang) {
                    PlaybackCenter.setSubtitleLanguage(sub.lang)
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root)
    }

    private fun showChaptersSheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_chapters)))
        val nowSec = PlaybackCenter.player.currentPosition / 1000
        val currentIdx = catalog.chapters.indexOfLast { it.startSec <= nowSec }
        catalog.chapters.forEachIndexed { i, ch ->
            val t = ch.startSec
            val stamp = if (t >= 3600) {
                String.format("%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
            } else {
                String.format("%d:%02d", t / 60, t % 60)
            }
            root.addView(
                sheetRow("$stamp  ${ch.title}", i == currentIdx) {
                    PlaybackCenter.player.seekTo(ch.startSec * 1000L)
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root)
    }

    private fun sleepTimerSummary(): String =
        if (PlaybackCenter.sleepTimerMode == com.play.tube.playback.SleepTimerState.Mode.OFF) {
            getString(R.string.setting_off)
        } else {
            com.play.tube.ui.music.SleepTimerSheet.buttonLabel(this)
        }

    /** A settings row that flips On/Off in place (no sheet reopen). */
    private fun sheetToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        var state = initial
        lateinit var row: LinearLayout
        row = sheetMenuRow(label, getString(if (state) R.string.setting_on else R.string.setting_off)) {
            state = !state
            onChange(state)
            (row.getChildAt(1) as? TextView)?.text =
                getString(if (state) R.string.setting_on else R.string.setting_off)
        }
        return row
    }

    private fun showSeekStepSheet() {
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_seek_step)))
        listOf(5, 10, 15, 30).forEach { sec ->
            root.addView(
                sheetRow(getString(R.string.seek_step_seconds, sec), AppPrefs.seekStepSec == sec) {
                    AppPrefs.seekStepSec = sec
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root, peekDp = 360)
    }

    private fun qualitySummary(catalog: StreamCatalog): String {
        val current = PlaybackCenter.effectiveSelectedHeight(catalog)
            ?: return getString(R.string.quality_auto_short)
        if (current == -1) return getString(R.string.quality_audio_only)
        val fps = catalog.videoOnly.firstOrNull { it.effectiveHeight() == current }?.fps
            ?: catalog.muxed.firstOrNull { it.effectiveHeight() == current }?.fps ?: 0
        return if (fps > 30) "${current}p $fps" else "${current}p"
    }

    private fun speedSummary(): String =
        if (PlaybackCenter.playbackSpeed == 1.0f) {
            getString(R.string.speed_normal)
        } else {
            "${PlaybackCenter.playbackSpeed}x"
        }

    private fun audioTrackSummary(catalog: StreamCatalog): String {
        val selected = PlaybackCenter.selectedAudioTrackId
        val track = catalog.audioTracks.firstOrNull { it.id == selected }
            ?: catalog.audioTracks.firstOrNull()
            ?: return ""
        return track.label + if (track.isOriginal) {
            " (${getString(R.string.audio_track_original)})"
        } else {
            ""
        }
    }

    private fun showQualitySheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_quality_section)))

        val currentHeight = PlaybackCenter.effectiveSelectedHeight(catalog)
        root.addView(
            sheetRow(getString(R.string.quality_auto), currentHeight == null) {
                PlaybackCenter.setVideoQuality(null)
                sheet.dismiss()
            }
        )
        val useMuxed = catalog.videoOnly.isEmpty()
        val candidates = if (useMuxed) catalog.muxed else catalog.videoOnly
        candidates.forEach { v ->
            val h = v.effectiveHeight()
            val label = "${h}p" +
                (if (v.fps > 30) " ${v.fps}fps" else "") +
                (if (useMuxed) " (muxed)" else "")
            root.addView(sheetRow(label, currentHeight == h) {
                PlaybackCenter.setVideoQuality(h)
                sheet.dismiss()
            })
        }
        root.addView(
            sheetRow(getString(R.string.quality_audio_only), currentHeight == -1) {
                PlaybackCenter.setVideoQuality(-1)
                sheet.dismiss()
            }
        )
        showSheet(sheet, root)
    }

    private fun showSpeedSheet() {
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_speed_section)))

        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val currentSpeed = PlaybackCenter.playbackSpeed
        speeds.forEach { s ->
            root.addView(
                sheetRow(
                    if (s == 1.0f) getString(R.string.speed_normal) else "${s}x",
                    s == currentSpeed
                ) {
                    PlaybackCenter.setSpeed(s)
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root)
    }

    private fun showAudioTrackSheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_audio_track_section)))

        val currentTrack = PlaybackCenter.selectedAudioTrackId
        catalog.audioTracks.forEach { t ->
            val label = t.label + if (t.isOriginal) {
                " (${getString(R.string.audio_track_original)})"
            } else {
                ""
            }
            root.addView(
                sheetRow(label, currentTrack == t.id) {
                    PlaybackCenter.setAudioTrack(t.id)
                    Toast.makeText(
                        this,
                        getString(R.string.audio_track_switched_to, label),
                        Toast.LENGTH_SHORT
                    ).show()
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root)
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog ?: run {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        DownloadSheet(this, entry, catalog).show()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_UPLOADER = "extra_uploader"
        private const val EXTRA_THUMB = "extra_thumb"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_ATTACH = "extra_attach"
        private const val SWIPE_MIN_DISTANCE = 130f
        private const val SKIP_CHAIN_MS = 800L
        private const val DESC_COLLAPSED_LINES = 2

        fun start(context: Context, model: com.play.tube.ui.common.VideoUiModel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, model.url)
                putExtra(EXTRA_TITLE, model.title)
                putExtra(EXTRA_UPLOADER, model.uploader)
                putExtra(EXTRA_THUMB, model.thumbnailUrl)
                putExtra(EXTRA_DURATION, model.durationSec)
            }
            context.startActivity(intent, openOptions(context))
        }

        /** Same slide/fade is requested by the caller, so it starts with the very first frame. */
        private fun openOptions(context: Context): Bundle? =
            if (AppPrefs.animations) android.app.ActivityOptions
                .makeCustomAnimation(context, R.anim.slide_up_in, R.anim.hold).toBundle() else null

        /** Re-opens the page for whatever is already playing in the mini player. */
        fun startResume(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_ATTACH, true)
            }
            context.startActivity(intent, openOptions(context))
        }
    }
}
