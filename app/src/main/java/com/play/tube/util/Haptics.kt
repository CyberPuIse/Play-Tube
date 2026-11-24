package com.play.tube.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** One place for the light vibration ticks used on taps (like, subscribe, tabs, pause). */
object Haptics {
    fun tick(view: View?) {
        view ?: return
        val c = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
        runCatching { view.performHapticFeedback(c) }
    }

    fun soft(view: View?) {
        view ?: return
        val c = if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.CLOCK_TICK
        else HapticFeedbackConstants.VIRTUAL_KEY
        runCatching { view.performHapticFeedback(c) }
    }
}
