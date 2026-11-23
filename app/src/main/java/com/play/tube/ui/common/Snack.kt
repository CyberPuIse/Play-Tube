package com.play.tube.ui.common

import android.view.View
import android.view.ViewGroup
import com.google.android.material.snackbar.Snackbar
import com.play.tube.R

/** Floating snackbar (above the navbar) with an optional red "Undo" action. */
object Snack {
    fun show(anchor: View, message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val sb = Snackbar.make(anchor, message, Snackbar.LENGTH_LONG)
        if (actionLabel != null && onAction != null) {
            sb.setAction(actionLabel) { onAction() }
            sb.setActionTextColor(0xFFFF5252.toInt())
        }
        val d = anchor.resources.displayMetrics.density
        (sb.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = (104 * d).toInt()
            it.marginStart = (12 * d).toInt()
            it.marginEnd = (12 * d).toInt()
            sb.view.layoutParams = it
        }
        sb.show()
    }

    fun undo(anchor: View, messageRes: Int, onUndo: () -> Unit) =
        show(anchor, anchor.context.getString(messageRes), anchor.context.getString(R.string.snack_undo), onUndo)
}
