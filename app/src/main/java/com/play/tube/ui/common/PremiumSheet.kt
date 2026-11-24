package com.play.tube.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.play.tube.R

/**
 * One consistent, rounded "premium" bottom sheet used instead of the plain
 * system dialogs: handle, optional round icon, title / message, tappable
 * rows, a text field and primary / secondary pill buttons.
 */
class PremiumSheet(private val ctx: Context) {

    private val dialog = BottomSheetDialog(ctx)
    private val root = LinearLayout(ctx)
    private var input: EditText? = null
    private val density = ctx.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    init {
        root.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.bg_offline_sheet)
            setPadding(dp(22), dp(10), dp(22), dp(26))
        }
        root.addView(View(ctx).apply { setBackgroundResource(R.drawable.bg_sheet_handle) },
            LinearLayout.LayoutParams(dp(36), dp(4)))
    }

    fun icon(res: Int, red: Boolean = true): PremiumSheet {
        val wrap = FrameLayout(ctx).apply {
            setBackgroundResource(if (red) R.drawable.bg_offline_icon else R.drawable.bg_sheet_row_icon)
        }
        wrap.addView(ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(if (red) Color.WHITE else color(R.color.on_surface))
        }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        root.addView(wrap, LinearLayout.LayoutParams(dp(60), dp(60)).also { it.topMargin = dp(18) })
        return this
    }

    fun title(text: String): PremiumSheet {
        root.addView(TextView(ctx).apply {
            this.text = text
            textSize = 20f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.on_surface))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(14) })
        return this
    }

    fun message(text: String): PremiumSheet {
        root.addView(TextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color(R.color.on_surface_variant))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) })
        return this
    }

    /** A full-width tappable row: round icon, label, optional check mark. */
    fun row(
        iconRes: Int, label: String, selected: Boolean = false,
        destructive: Boolean = false, onClick: () -> Unit
    ): PremiumSheet {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { dialog.dismiss(); onClick() }
        }
        val tint = if (destructive) 0xFFFF3B3B.toInt() else color(R.color.on_surface)
        val iconWrap = FrameLayout(ctx).apply { setBackgroundResource(R.drawable.bg_sheet_row_icon) }
        iconWrap.addView(ImageView(ctx).apply {
            setImageResource(iconRes); setColorFilter(tint)
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        row.addView(iconWrap, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(tint)
            if (selected) setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.marginStart = dp(14) })
        if (selected) {
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check); setColorFilter(0xFFFF0000.toInt())
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(4) })
        return this
    }

    fun input(hint: String): PremiumSheet {
        val et = EditText(ctx).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_sheet_input)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(color(R.color.on_surface))
            setHintTextColor(color(R.color.on_surface_variant))
            textSize = 16f
        }
        input = et
        root.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(18) })
        return this
    }

    /** Custom content (e.g. a text field) placed under the title / message. */
    fun view(v: View): PremiumSheet {
        (v.parent as? ViewGroup)?.removeView(v)
        root.addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(16) })
        return this
    }

    fun onDismiss(cb: () -> Unit): PremiumSheet {
        dialog.setOnDismissListener { cb() }
        return this
    }

    fun dismiss() = dialog.dismiss()

    fun inputText(): String = input?.text?.toString().orEmpty().trim()

    /** Red pill. [onClick] returns true to close the sheet, false to keep it open. */
    fun primary(label: String, onClick: () -> Boolean = { true }): PremiumSheet {
        root.addView(pill(label, R.drawable.bg_offline_btn_primary, Color.WHITE) {
            if (onClick()) dialog.dismiss()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).also { it.topMargin = dp(22) })
        return this
    }

    fun secondary(label: String, onClick: (() -> Unit)? = null): PremiumSheet {
        root.addView(pill(label, R.drawable.bg_offline_btn_secondary, color(R.color.on_surface)) {
            dialog.dismiss()
            onClick?.invoke()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).also { it.topMargin = dp(10) })
        return this
    }

    private fun pill(label: String, bg: Int, textColor: Int, click: () -> Unit) =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setBackgroundResource(bg)
            setOnClickListener { click() }
        }

    fun show() {
        val scroll = ScrollView(ctx).apply { addView(root); isFillViewport = false }
        dialog.setContentView(scroll)
        (scroll.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
        input?.let { et ->
            et.requestFocus()
            et.postDelayed({
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }, 150L)
        }
    }
}
