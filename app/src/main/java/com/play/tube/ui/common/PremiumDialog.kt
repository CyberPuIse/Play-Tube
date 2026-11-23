package com.play.tube.ui.common

import android.content.Context
import android.content.DialogInterface
import android.view.View
import com.play.tube.R

/**
 * Drop-in for AlertDialog.Builder that renders the app's premium bottom sheet
 * (rounded sheet, red pill buttons, choice rows) instead of the stock dialog.
 */
class PremiumDialog(private val ctx: Context) {

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var positiveText: CharSequence? = null
    private var positive: DialogInterface.OnClickListener? = null
    private var negativeText: CharSequence? = null
    private var negative: DialogInterface.OnClickListener? = null
    private var items: Array<out CharSequence>? = null
    private var checked = -1
    private var choice: DialogInterface.OnClickListener? = null
    private var customView: View? = null

    fun setTitle(res: Int) = apply { title = ctx.getString(res) }
    fun setTitle(text: CharSequence?) = apply { title = text }
    fun setMessage(res: Int) = apply { message = ctx.getString(res) }
    fun setMessage(text: CharSequence?) = apply { message = text }
    fun setView(v: View) = apply { customView = v }

    fun setPositiveButton(res: Int, l: DialogInterface.OnClickListener?) =
        apply { positiveText = ctx.getString(res); positive = l }

    fun setPositiveButton(text: CharSequence?, l: DialogInterface.OnClickListener?) =
        apply { positiveText = text; positive = l }

    fun setNegativeButton(res: Int, l: DialogInterface.OnClickListener?) =
        apply { negativeText = ctx.getString(res); negative = l }

    fun setNegativeButton(text: CharSequence?, l: DialogInterface.OnClickListener?) =
        apply { negativeText = text; negative = l }

    fun setSingleChoiceItems(
        labels: Array<out CharSequence>, checkedItem: Int, l: DialogInterface.OnClickListener?
    ) = apply { items = labels; checked = checkedItem; choice = l }

    fun show() {
        val sheet = PremiumSheet(ctx)
        val handle = object : DialogInterface {
            override fun dismiss() = sheet.dismiss()
            override fun cancel() = sheet.dismiss()
        }
        title?.let { sheet.title(it.toString()) }
        message?.let { sheet.message(it.toString()) }
        customView?.let { sheet.view(it) }
        items?.forEachIndexed { index, label ->
            sheet.row(
                if (index == checked) R.drawable.ic_check else R.drawable.ic_circle_outline,
                label.toString(), selected = index == checked
            ) { choice?.onClick(handle, index) }
        }
        positiveText?.let { text ->
            sheet.primary(text.toString()) { positive?.onClick(handle, DialogInterface.BUTTON_POSITIVE); true }
        }
        negativeText?.let { text ->
            sheet.secondary(text.toString()) { negative?.onClick(handle, DialogInterface.BUTTON_NEGATIVE) }
        }
        sheet.show()
    }
}
