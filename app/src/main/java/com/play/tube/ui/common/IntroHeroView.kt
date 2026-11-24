package com.play.tube.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.play.tube.util.AppPrefs
import com.play.tube.util.Themes
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated hero illustration for the welcome slides (drawn in code, no
 * assets): [kind] 0 = video cards, 1 = downloads, 2 = personal feed,
 * 3 = Telegram channel.
 */
class IntroHeroView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var kind: Int = 0
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private var phase = 0f
    private val accent = Themes.accentColor(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    private fun wave(offset: Float = 0f) = sin(2 * PI * (phase + offset)).toFloat()
    private fun withAlpha(color: Int, a: Float) =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (AppPrefs.animations && !loop.isStarted) loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2

        // Soft breathing glow behind everything.
        paint.shader = RadialGradient(
            cx, cy, w * 0.5f * (1f + 0.04f * wave()),
            intArrayOf(withAlpha(accent, 0.30f), withAlpha(accent, 0f)), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, w * 0.52f, paint)
        paint.shader = null

        when (kind) {
            0 -> drawCards(canvas, cx, cy)
            1 -> drawDownloads(canvas, cx, cy)
            2 -> drawFeed(canvas, cx, cy)
            else -> drawChannel(canvas, cx, cy)
        }
    }

    private fun card(canvas: Canvas, cx: Float, cy: Float, cw: Float, ch: Float, rot: Float, color: Int, radius: Float) {
        canvas.save()
        canvas.rotate(rot, cx, cy)
        rect.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        paint.color = color
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.restore()
    }

    private fun drawCards(canvas: Canvas, cx: Float, cy: Float) {
        val cw = width * 0.60f
        val ch = cw * 9f / 16f
        card(canvas, cx - 16 * d, cy + 4 * d + wave(0.5f) * 5 * d, cw, ch, -9f, withAlpha(accent, 0.28f), 22 * d)
        card(canvas, cx + 14 * d, cy - 2 * d + wave(0.25f) * 5 * d, cw, ch, 7f, withAlpha(accent, 0.45f), 22 * d)
        val y = cy + wave() * 6 * d
        // Front card
        card(canvas, cx, y, cw, ch, 0f, 0xFF1B1B21.toInt(), 22 * d)
        // Play disc
        paint.color = accent
        canvas.drawCircle(cx, y - 4 * d, 24 * d * (1f + 0.04f * wave(0.1f)), paint)
        path.reset()
        path.moveTo(cx - 7 * d, y - 4 * d - 11 * d)
        path.lineTo(cx - 7 * d, y - 4 * d + 11 * d)
        path.lineTo(cx + 12 * d, y - 4 * d)
        path.close()
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, paint)
        // Progress line
        val left = cx - cw / 2 + 16 * d
        val right = cx + cw / 2 - 16 * d
        val by = y + ch / 2 - 14 * d
        paint.color = 0x33FFFFFF
        rect.set(left, by - 2 * d, right, by + 2 * d)
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
        paint.color = accent
        rect.set(left, by - 2 * d, left + (right - left) * (0.15f + 0.8f * phase), by + 2 * d)
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
    }

    private fun drawDownloads(canvas: Canvas, cx: Float, cy: Float) {
        val pw = width * 0.46f
        val ph = height * 0.84f
        val y = cy + wave() * 4 * d
        // Phone body
        rect.set(cx - pw / 2, y - ph / 2, cx + pw / 2, y + ph / 2)
        paint.color = 0xFF1B1B21.toInt()
        canvas.drawRoundRect(rect, 28 * d, 28 * d, paint)
        // Rows
        val rowH = ph * 0.20f
        val first = y - ph / 2 + ph * 0.18f
        for (i in 0 until 3) {
            val ry = first + i * (rowH + 8 * d)
            rect.set(cx - pw / 2 + 12 * d, ry, cx + pw / 2 - 12 * d, ry + rowH)
            paint.color = 0x22FFFFFF
            canvas.drawRoundRect(rect, 10 * d, 10 * d, paint)
            // thumb
            paint.color = withAlpha(accent, 0.55f)
            rect.set(cx - pw / 2 + 18 * d, ry + 6 * d, cx - pw / 2 + 18 * d + rowH * 1.1f, ry + rowH - 6 * d)
            canvas.drawRoundRect(rect, 6 * d, 6 * d, paint)
            // progress
            val px = cx - pw / 2 + 18 * d + rowH * 1.1f + 8 * d
            val pr = cx + pw / 2 - 18 * d
            val py = ry + rowH / 2
            paint.color = 0x33FFFFFF
            rect.set(px, py - 2 * d, pr, py + 2 * d)
            canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
            val p = when (i) { 0 -> 1f; 1 -> (phase * 1.6f).coerceAtMost(1f); else -> (phase * 0.8f) }
            paint.color = if (p >= 1f) 0xFF2EA66B.toInt() else accent
            rect.set(px, py - 2 * d, px + (pr - px) * p, py + 2 * d)
            canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
        }
        // Download badge bouncing
        val bx = cx + pw / 2 - 6 * d
        val by = y + ph / 2 - 26 * d + wave(0.3f) * 5 * d
        paint.color = accent
        canvas.drawCircle(bx, by, 28 * d, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.4f * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val arrowDy = wave(0.3f) * 2 * d
        canvas.drawLine(bx, by - 10 * d + arrowDy, bx, by + 7 * d + arrowDy, paint)
        path.reset()
        path.moveTo(bx - 8 * d, by + 0 * d + arrowDy)
        path.lineTo(bx, by + 8 * d + arrowDy)
        path.lineTo(bx + 8 * d, by + 0 * d + arrowDy)
        canvas.drawPath(path, paint)
        canvas.drawLine(bx - 10 * d, by + 14 * d, bx + 10 * d, by + 14 * d, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawFeed(canvas: Canvas, cx: Float, cy: Float) {
        // Chips floating around a pulsing heart.
        val chips = listOf("Music", "Tech", "Cricket", "Food")
        val pos = listOf(-0.30f to -0.32f, 0.30f to -0.18f, -0.32f to 0.26f, 0.28f to 0.34f)
        paint.textSize = 13 * d
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        chips.forEachIndexed { i, label ->
            val px = cx + width * pos[i].first
            val py = cy + height * pos[i].second + wave(i * 0.23f) * 6 * d
            val tw = paint.measureText(label)
            rect.set(px - tw / 2 - 14 * d, py - 15 * d, px + tw / 2 + 14 * d, py + 15 * d)
            paint.color = 0xFF1B1B21.toInt()
            canvas.drawRoundRect(rect, 15 * d, 15 * d, paint)
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(label, px - tw / 2, py + 4.5f * d, paint)
        }
        // Heart
        val s = 44 * d * (1f + 0.07f * wave(0.05f))
        heart(cx, cy + 4 * d, s)
        paint.color = accent
        canvas.drawPath(path, paint)
        // Sparkles
        for (i in 0 until 5) {
            val a = 2 * PI * (i / 5.0) + phase * 2 * PI * 0.25
            val r = width * 0.40f
            val sx = cx + (r * kotlin.math.cos(a)).toFloat()
            val sy = cy + (r * 0.7f * sin(a)).toFloat()
            paint.color = withAlpha(accent, 0.35f + 0.35f * wave(i * 0.2f))
            canvas.drawCircle(sx, sy, (2.5f + (i % 2)) * d, paint)
        }
    }

    /** Paper plane inside a pulsing circle, with rings radiating out (Telegram slide). */
    private fun drawChannel(canvas: Canvas, cx: Float, cy: Float) {
        val blue = 0xFF2AABEE.toInt()
        // Two rings expanding out of the circle.
        for (i in 0 until 2) {
            val t = (phase + i * 0.5f) % 1f
            val r = 46 * d + t * 90 * d
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.color = withAlpha(blue, 0.45f * (1f - t))
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.style = android.graphics.Paint.Style.FILL
        // Solid disc that breathes slightly.
        val r0 = 46 * d * (1f + 0.04f * wave())
        paint.color = blue
        canvas.drawCircle(cx, cy, r0, paint)
        // Paper plane, drawn as two triangles meeting at a fold.
        val s = 30 * d
        path.reset()
        path.moveTo(cx - s * 0.9f, cy - s * 0.1f)
        path.lineTo(cx + s * 1.0f, cy - s * 0.85f)
        path.lineTo(cx + s * 0.1f, cy + s * 0.95f)
        path.lineTo(cx - s * 0.15f, cy + s * 0.35f)
        path.close()
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(cx - s * 0.15f, cy + s * 0.35f)
        path.lineTo(cx + s * 1.0f, cy - s * 0.85f)
        path.lineTo(cx + s * 0.1f, cy + s * 0.95f)
        path.close()
        paint.color = withAlpha(0xFFFFFFFF.toInt(), 0.75f)
        canvas.drawPath(path, paint)
    }

    private fun heart(cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy + s * 0.9f)
        path.cubicTo(cx - s * 1.9f, cy - s * 0.1f, cx - s * 0.9f, cy - s * 1.5f, cx, cy - s * 0.55f)
        path.cubicTo(cx + s * 0.9f, cy - s * 1.5f, cx + s * 1.9f, cy - s * 0.1f, cx, cy + s * 0.9f)
        path.close()
    }
}
