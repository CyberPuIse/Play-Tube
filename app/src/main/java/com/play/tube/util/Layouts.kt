package com.play.tube.util

import android.content.Context
import android.view.View
import android.animation.ValueAnimator
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

object Layouts {
    /** 1 column on phones, 2 on small tablets / landscape, 3 on large screens. */
    fun videoSpan(context: Context): Int {
        val w = context.resources.configuration.screenWidthDp
        return when {
            w >= 900 -> 3
            w >= 600 -> 2
            else -> 1
        }
    }
}

/**
 * Slides [header] away in step with the scroll (any speed, even very slow)
 * and brings it back when scrolling up.
 *
 * Only translations change while scrolling (no relayout, no scroll
 * compensation), so it stays perfectly smooth on slow drags. The list's own
 * container is lifted by the same amount and given a negative bottom margin
 * of the header height, so no gap opens at the bottom.
 */
class HeaderHider(private val list: RecyclerView, private val header: View) {
    /** The direct sibling of [header] that holds the list (the refresh layout). */
    private val container: View = run {
        var v: View = list
        while (v.parent != null && v.parent !== header.parent && v.parent is View) v = v.parent as View
        v
    }
    private var full = 0
    private var offset = 0f
    private var snap: ValueAnimator? = null

    init {
        header.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && h != full && offset == 0f) setup(h)
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (full == 0 || dy == 0) return
                snap?.cancel()
                val next = if (!rv.canScrollVertically(-1)) 0f
                else (offset + dy).coerceIn(0f, full.toFloat())
                move(next)
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) snap?.cancel()
                if (newState != RecyclerView.SCROLL_STATE_IDLE || full == 0) return
                if (offset <= 0f || offset >= full) return
                val goal = if (offset < full / 2f) 0f else full.toFloat()
                snap = ValueAnimator.ofFloat(offset, goal).apply {
                    duration = 160
                    addUpdateListener { move(it.animatedValue as Float) }
                    start()
                }
            }
        })
    }

    private fun setup(h: Int) {
        full = h
        val lp = container.layoutParams
        if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != -h) {
            lp.bottomMargin = -h
            container.layoutParams = lp
        }
    }

    private fun move(value: Float) {
        offset = value
        header.translationY = -value
        container.translationY = -value
        header.alpha = if (full > 0) 1f - 0.9f * (value / full) else 1f
    }
}
