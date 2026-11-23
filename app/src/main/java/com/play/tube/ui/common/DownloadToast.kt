package com.play.tube.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.play.tube.R
import com.play.tube.ui.MainActivity

/**
 * Tappable toast-style pill: "Download started · View". Unlike a system Toast
 * it can be tapped, which opens the Downloads tab where the live progress
 * (downloaded size / total / speed) is shown.
 */
object DownloadToast {

    private const val TAG = "playtube_download_toast"
    private const val DURATION_MS = 4000L
    private val handler = Handler(Looper.getMainLooper())

    fun showStarted(context: Context) {
        val activity = activityFrom(context)
        val root = activity?.findViewById<ViewGroup>(android.R.id.content)
        if (activity == null || root == null || activity.isFinishing || activity.isDestroyed) {
            // No screen to attach to: fall back to a plain toast.
            Toast.makeText(context.applicationContext, R.string.download_started, Toast.LENGTH_SHORT).show()
            return
        }

        // Only one pill at a time.
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        handler.removeCallbacksAndMessages(null)

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pill = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F2262626"))
            }
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = true
        }
        val message = TextView(activity).apply {
            text = activity.getString(R.string.download_started)
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val dot = TextView(activity).apply {
            text = "  ·  "
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 14f
        }
        val action = TextView(activity).apply {
            text = activity.getString(R.string.download_tap_to_view)
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        pill.addView(message)
        pill.addView(dot)
        pill.addView(action)

        val bottom = if (activity is MainActivity) dp(104) else dp(36)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).also {
            it.bottomMargin = bottom
            it.marginStart = dp(16)
            it.marginEnd = dp(16)
        }

        fun dismiss() {
            handler.removeCallbacksAndMessages(null)
            pill.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(180)
                .withEndAction { (pill.parent as? ViewGroup)?.removeView(pill) }
                .start()
        }

        pill.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            (pill.parent as? ViewGroup)?.removeView(pill)
            MainActivity.openDownloads(activity)
        }

        pill.alpha = 0f
        pill.translationY = dp(12).toFloat()
        root.addView(pill, lp)
        pill.animate().alpha(1f).translationY(0f).setDuration(200).start()
        handler.postDelayed({ dismiss() }, DURATION_MS)
    }

    private fun activityFrom(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            val base = (current as? ContextWrapper)?.baseContext ?: return null
            if (base === current) return null
            current = base
        }
        return null
    }
}
