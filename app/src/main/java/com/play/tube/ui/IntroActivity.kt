package com.play.tube.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.play.tube.R
import com.play.tube.util.AppPrefs
import com.play.tube.util.Themes

/** Three swipeable welcome slides shown once, right after the first splash. */
class IntroActivity : AppCompatActivity() {

    private data class Slide(val icon: Int, val title: Int, val text: Int)

    private val slides = listOf(
        Slide(R.drawable.ic_play_arrow, R.string.intro_title_1, R.string.intro_text_1),
        Slide(R.drawable.ic_download, R.string.intro_title_2, R.string.intro_text_2),
        Slide(R.drawable.ic_favorite, R.string.intro_title_3, R.string.intro_text_3)
    )
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        val pager = findViewById<RecyclerView>(R.id.introPager)
        val dots = findViewById<LinearLayout>(R.id.introDots)
        val next = findViewById<TextView>(R.id.introNext)
        val skip = findViewById<TextView>(R.id.introSkip)

        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pager.layoutManager = lm
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_intro, parent, false)
                v.layoutParams = RecyclerView.LayoutParams(parent.width, ViewGroup.LayoutParams.MATCH_PARENT)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val s = slides[position]
                h.itemView.findViewById<com.play.tube.ui.common.IntroHeroView>(R.id.introBadge).kind = position
                h.itemView.findViewById<TextView>(R.id.introTitle).setText(s.title)
                h.itemView.findViewById<TextView>(R.id.introText).setText(s.text)
            }

            override fun getItemCount() = slides.size
        }
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(pager)

        val d = resources.displayMetrics.density
        val animate = AppPrefs.animations
        // Dots are created once; their width follows the pager position continuously.
        val dotViews = slides.indices.map {
            View(this).also { v ->
                dots.addView(v, LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).also { lp ->
                    lp.marginStart = (4 * d).toInt(); lp.marginEnd = (4 * d).toInt()
                })
            }
        }
        fun updateDots(pos: Float) {
            dotViews.forEachIndexed { i, v ->
                val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                val w = ((8 + 14 * near) * d).toInt()
                val lp = v.layoutParams
                if (lp.width != w) { lp.width = w; v.layoutParams = lp }
                v.setBackgroundResource(if (near >= 0.5f) R.drawable.bg_dot_on else R.drawable.bg_dot_off)
            }
        }
        fun render() {
            val label = if (page == slides.lastIndex) R.string.intro_start else R.string.intro_next
            if (animate && next.text.isNotEmpty()) {
                next.animate().alpha(0f).setDuration(90).withEndAction {
                    next.setText(label)
                    next.animate().alpha(1f).setDuration(140).start()
                }.start()
            } else next.setText(label)
            if (page == slides.lastIndex) skip.animate().alpha(0f).setDuration(160)
                .withEndAction { skip.visibility = View.INVISIBLE }.start()
            else { skip.visibility = View.VISIBLE; skip.animate().alpha(1f).setDuration(160).start() }
        }
        updateDots(0f)
        next.setText(R.string.intro_next)

        // Parallax: icon drifts slower than the page, text fades and slides, badge shrinks away.
        fun parallax() {
            val w = pager.width.toFloat().coerceAtLeast(1f)
            for (i in 0 until pager.childCount) {
                val c = pager.getChildAt(i)
                val off = (c.left / w).coerceIn(-1f, 1f)
                val abs = kotlin.math.abs(off)
                c.findViewById<View>(R.id.introBadge)?.apply {
                    translationX = off * w * 0.30f
                    val s = 1f - 0.3f * abs
                    scaleX = s; scaleY = s
                    alpha = 1f - 0.7f * abs
                }
                c.findViewById<View>(R.id.introTitle)?.apply {
                    translationX = off * w * 0.15f; alpha = 1f - abs * 1.2f
                }
                c.findViewById<View>(R.id.introText)?.apply {
                    translationX = off * w * 0.08f; alpha = 1f - abs * 1.4f
                }
            }
            val v = snap.findSnapView(lm)
            val p = if (v != null) lm.getPosition(v) else page
            val first = lm.findFirstVisibleItemPosition()
            val fv = lm.findViewByPosition(first)
            if (fv != null && first >= 0) updateDots(first - fv.left / w)
            if (p != page && p >= 0 && (v!!.left / w).let { kotlin.math.abs(it) < 0.5f }) {
                page = p; render()
            }
        }

        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { parallax() }
        })
        pager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> parallax() }

        // Entrance: badge pops in, then title and text rise in one after another.
        if (animate) pager.post {
            val first = lm.findViewByPosition(0) ?: return@post
            val badge = first.findViewById<View>(R.id.introBadge)
            val title = first.findViewById<View>(R.id.introTitle)
            val text = first.findViewById<View>(R.id.introText)
            badge.scaleX = 0.4f; badge.scaleY = 0.4f; badge.alpha = 0f
            badge.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(650)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            listOf(title to 220L, text to 360L).forEach { (v, delay) ->
                v.alpha = 0f; v.translationY = 28 * d
                v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(480)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
            listOf(dots, next, skip).forEachIndexed { i, v ->
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(500L + i * 90L).setDuration(400).start()
            }
        }

        next.setOnClickListener {
            next.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                next.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            if (page < slides.lastIndex) pager.smoothScrollToPosition(page + 1) else finishIntro()
        }
        skip.setOnClickListener { finishIntro() }
    }

    private fun finishIntro() {
        AppPrefs.introSeen = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
