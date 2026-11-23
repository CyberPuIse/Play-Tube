package com.play.tube.ui.common

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.play.tube.databinding.ItemSkeletonRowBinding
import com.play.tube.databinding.ItemSkeletonVideoBinding

/**
 * Skeleton loading rows shown inside a RecyclerView while real data is being
 * fetched — replaces the old center spinners everywhere.
 *
 * Two styles:
 *  - [STYLE_VIDEO] mimics a video card (16:9 thumb + 3 text lines)
 *  - [STYLE_ROW]   mimics a compact row (avatar + 2 text lines)
 *
 * Rows pulse with a staggered fade so the loading state feels alive.
 */
class SkeletonAdapter(
    private val style: Int,
    private val count: Int = 8
) : RecyclerView.Adapter<SkeletonAdapter.VH>() {

    companion object {
        const val STYLE_VIDEO = 0
        const val STYLE_ROW = 1
    }

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        var animator: ObjectAnimator? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inner = if (style == STYLE_VIDEO) {
            ItemSkeletonVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ).root
        } else {
            ItemSkeletonRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ).root
        }
        // Detach the inflated root from its LayoutParams-only parent link and
        // put it inside the shimmer wrapper.
        val wrapper = ShimmerFrameLayout(parent.context)
        wrapper.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        wrapper.addView(inner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return VH(wrapper)
    }

    override fun getItemCount(): Int = count

    // Static placeholder views — nothing to bind per position.
    override fun onBindViewHolder(holder: VH, position: Int) = Unit

}

/** Convenience: swap a RecyclerView to skeleton rows and return the adapter. */
fun showSkeleton(list: RecyclerView, style: Int, count: Int = 8): SkeletonAdapter {
    val adapter = SkeletonAdapter(style, count)
    list.adapter = adapter
    return adapter
}

/**
 * Pulse controller for hand-inflated skeleton views in NON-RecyclerView
 * containers (the Music tab's shelf / popular list). [cancel] must be called
 * when real content replaces the placeholders so the animators stop.
 */
class SkeletonPulse {

    private val animators = mutableListOf<ObjectAnimator>()

    fun attach(view: View, index: Int) {
        animators += ObjectAnimator.ofFloat(view, View.ALPHA, 0.45f, 1f).apply {
            duration = 650
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            // Stagger so the placeholders don't flash as one blob.
            startDelay = (index % 6) * 90L
            start()
        }
    }

    fun cancel() {
        animators.forEach { it.cancel() }
        animators.clear()
    }
}
