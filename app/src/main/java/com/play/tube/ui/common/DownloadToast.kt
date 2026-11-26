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
    private const val DURATION_MS = 6000L
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F5242426"))
            }
            elevation = dp(12).toFloat()
            isClickable = true
            isFocusable = true
        }
        val titleView = TextView(activity).apply {
            text = activity.getString(R.string.dl_started_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        val sub = TextView(activity).apply {
            text = activity.getString(R.string.dl_started_sub)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun btn(label: String, color: String, bold: Boolean) = TextView(activity).apply {
            text = label
            setTextColor(Color.parseColor(color))
            textSize = 14f
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
        }
        val notNow = btn(activity.getString(R.string.dl_started_not_now), "#B3FFFFFF", false)
        val view = btn(activity.getString(R.string.dl_started_view), "#FF5252", true)
        buttons.addView(notNow)
        buttons.addView(view)
        pill.addView(titleView)
        pill.addView(sub)
        pill.addView(buttons)

        val bottom = if (activity is MainActivity) dp(96) else dp(28)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).also {
            it.bottomMargin = bottom
            it.marginStart = dp(14)
            it.marginEnd = dp(14)
        }

        fun dismiss() {
            handler.removeCallbacksAndMessages(null)
            pill.animate().alpha(0f).translationY(dp(120).toFloat()).setDuration(220)
                .withEndAction { (pill.parent as? ViewGroup)?.removeView(pill) }
                .start()
        }

        view.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            (pill.parent as? ViewGroup)?.removeView(pill)
            MainActivity.openDownloads(activity)
        }
        notNow.setOnClickListener { dismiss() }

        // Slides up from the bottom with a small overshoot.
        pill.alpha = 0f
        pill.translationY = dp(120).toFloat()
        root.addView(pill, lp)
        pill.animate().alpha(1f).translationY(0f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
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
