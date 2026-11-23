package com.play.tube.ui.common

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.play.tube.R
import com.play.tube.playback.PlaybackCenter
import com.play.tube.playback.QueueEntry
import com.play.tube.ui.music.NowPlayingActivity
import com.play.tube.ui.player.PlayerActivity
import com.play.tube.util.AppPrefs
import com.play.tube.util.Thumbs

/**
 * A compact floating mini player that any secondary screen (search, channel,
 * playlist, library, settings ...) can show, so the playing video / song stays
 * reachable everywhere, not only on the Home tabs.
 */
object MiniBar {
    fun attach(activity: AppCompatActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val thumb = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val thumbBox = FrameLayout(activity).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10 * d)
                }
            }
            addView(thumb, FrameLayout.LayoutParams(-1, -1))
        }
        val title = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.on_surface_variant))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(sub)
        }
        fun icon(res: Int, tintRes: Int) = ImageButton(activity).apply {
            setImageResource(res)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(ContextCompat.getColor(activity, tintRes))
        }
        val accent = android.util.TypedValue().also {
            activity.theme.resolveAttribute(R.attr.accent, it, true)
        }.data
        val playPause = icon(R.drawable.ic_pause, R.color.white).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(accent)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val close = icon(R.drawable.ic_close, R.color.on_surface_variant)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(6), dp(11))
            addView(thumbBox, LinearLayout.LayoutParams(dp(78), dp(44)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).also {
                it.marginStart = dp(10); it.marginEnd = dp(4)
            })
            addView(playPause, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val progress = android.widget.ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            progressDrawable = ContextCompat.getDrawable(activity, R.drawable.mini_progress)
        }
        val bar = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_mini_player)
            elevation = 12 * d
            clipToOutline = true
            visibility = View.GONE
            isClickable = true
            val tv = android.util.TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                foreground = ContextCompat.getDrawable(activity, tv.resourceId)
            }
            addView(row, FrameLayout.LayoutParams(-1, -2))
            addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
        }
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).also {
            it.marginStart = dp(12); it.marginEnd = dp(12); it.bottomMargin = dp(12)
        }
        content.addView(bar, lp)
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(12) + b
            v.requestLayout()
            insets
        }

        fun update() {
            if (activity.isFinishing || activity.isDestroyed) return
            val e: QueueEntry? = PlaybackCenter.currentEntry
            if (e == null || PlaybackCenter.inPip) { bar.visibility = View.GONE; return }
            if (bar.visibility != View.VISIBLE && AppPrefs.animations) {
                bar.alpha = 0f; bar.translationY = 28 * d
                bar.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
            bar.visibility = View.VISIBLE
            title.text = e.title
            sub.text = e.uploader
            Thumbs.load(thumb, e.thumbnailUrl)
            playPause.setImageResource(if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        }
        playPause.setOnClickListener { PlaybackCenter.togglePlayPause() }
        close.setOnClickListener { PlaybackCenter.stopPlayback() }
        bar.setOnClickListener {
            if (PlaybackCenter.currentEntry == null) return@setOnClickListener
            if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) NowPlayingActivity.start(activity)
            else PlayerActivity.startResume(activity)
        }

        val listener = object : PlaybackCenter.Listener {
            override fun onItemChanged(entry: QueueEntry?) = update()
            override fun onPlaybackStateChanged(isPlaying: Boolean) = update()
            override fun onQueueChanged() = update()
            override fun onAudioOnlyChanged(audioOnly: Boolean) = update()
            override fun onFavoriteChanged(url: String, isFavorite: Boolean) = update()
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            val tick = object : Runnable {
                override fun run() {
                    if (bar.visibility == View.VISIBLE) {
                        val dur = PlaybackCenter.durationMs
                        progress.progress =
                            if (dur > 0) (PlaybackCenter.positionMs * 1000 / dur).toInt().coerceIn(0, 1000) else 0
                    }
                    bar.postDelayed(this, 500L)
                }
            }
            override fun onStart(owner: LifecycleOwner) {
                PlaybackCenter.addListener(listener); update()
                bar.removeCallbacks(tick); bar.post(tick)
            }
            override fun onStop(owner: LifecycleOwner) {
                PlaybackCenter.removeListener(listener); bar.removeCallbacks(tick)
            }
        })
    }
}
