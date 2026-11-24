package com.play.tube.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Wraps a skeleton row and sweeps a soft light band across its grey shapes
 * (YouTube-style shimmer). The band is drawn SRC_ATOP, so it only shows on
 * the placeholder shapes, never on the empty background between them.
 */
class ShimmerFrameLayout(context: Context) : FrameLayout(context) {

    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init { setWillNotDraw(false) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val band = (w * 0.8f).coerceAtLeast(1f)
        shader = LinearGradient(
            0f, 0f, band, band * 0.25f,
            intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    override fun dispatchDraw(canvas: Canvas) {
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        if (shader != null) {
            val band = width * 0.8f
            matrix.setTranslate(-band + (width + band) * phase, 0f)
            shader!!.setLocalMatrix(matrix)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        canvas.restoreToCount(sc)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
