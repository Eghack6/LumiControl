package com.lumicontrol.app.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class TrajectoryOverlayView(context: Context) : View(context) {

    private data class SwipeAnim(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val startTime: Long,
        val duration: Long,
        val fadeOut: Long = 600L
    )

    private val anims = mutableListOf<SwipeAnim>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hadAnimations = false

    var onEmpty: (() -> Unit)? = null

    fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        anims.add(SwipeAnim(x1, y1, x2, y2, System.currentTimeMillis(), duration.coerceAtLeast(1)))
        if (anims.size > 6) anims.removeFirst()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val iterator = anims.iterator()
        var needsMore = false

        while (iterator.hasNext()) {
            val a = iterator.next()
            val elapsed = now - a.startTime
            val totalLife = a.duration + a.fadeOut
            if (elapsed > totalLife) { iterator.remove(); continue }
            needsMore = true

            val progress = (elapsed.toFloat() / a.duration).coerceIn(0f, 1f)
            val cx = a.x1 + (a.x2 - a.x1) * progress
            val cy = a.y1 + (a.y2 - a.y1) * progress

            val fadeElapsed = (elapsed - a.duration).coerceAtLeast(0)
            val alpha = ((1f - fadeElapsed / a.fadeOut) * 220).toInt().coerceIn(0, 220)

            // 1. traveled path (solid, from start to current dot)
            if (progress > 0f) {
                paint.color = Color.argb(alpha, 233, 69, 96)
                paint.strokeWidth = 3f
                paint.style = Paint.Style.STROKE
                canvas.drawLine(a.x1, a.y1, cx, cy, paint)
            }

            // 2. remaining path (faded, from current dot to end)
            if (progress < 1f) {
                paint.color = Color.argb((alpha * 0.25f).toInt(), 233, 69, 96)
                paint.strokeWidth = 2f
                canvas.drawLine(cx, cy, a.x2, a.y2, paint)
            }

            // 3. moving dot (bright, bigger)
            paint.color = Color.argb(alpha.coerceAtMost(200), 233, 69, 96)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, 7f, paint)

            // 4. dot border (always visible)
            paint.color = Color.argb(alpha, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawCircle(cx, cy, 7f, paint)
        }

        if (anims.isEmpty() && hadAnimations) {
            hadAnimations = false
            onEmpty?.invoke()
        }
        if (anims.isNotEmpty()) hadAnimations = true

        if (needsMore) postInvalidateOnAnimation()
    }

    fun clear() {
        anims.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anims.clear()
    }
}
