package com.play.tube.util

import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.play.tube.R

/** Empty list placeholder: big muted icon above the message. */
object EmptyState {
    fun set(view: TextView, textRes: Int, iconRes: Int) {
        val ctx = view.context
        val d = resources(view)
        val size = (72 * d).toInt()
        val icon = ContextCompat.getDrawable(ctx, iconRes)?.mutate()?.apply {
            setBounds(0, 0, size, size)
            setTint(ContextCompat.getColor(ctx, R.color.on_surface_variant))
            alpha = 150
        }
        view.setText(textRes)
        view.gravity = Gravity.CENTER
        view.setCompoundDrawables(null, icon, null, null)
        view.compoundDrawablePadding = (16 * d).toInt()
        view.setPadding((32 * d).toInt(), view.paddingTop, (32 * d).toInt(), view.paddingBottom)
    }

    private fun resources(v: TextView) = v.resources.displayMetrics.density

    fun iconFor(textRes: Int): Int = when (textRes) {
        R.string.empty_history -> R.drawable.ic_history
        R.string.empty_favorites -> R.drawable.ic_favorite_border
        R.string.empty_watch_later -> R.drawable.ic_watch_later
        R.string.empty_subscriptions -> R.drawable.ic_subscriptions
        R.string.empty_playlists -> R.drawable.ic_queue_music
        else -> R.drawable.ic_download
    }
}
