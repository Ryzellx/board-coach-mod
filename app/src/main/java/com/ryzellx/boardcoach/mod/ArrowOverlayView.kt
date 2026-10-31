package com.ryzellx.boardcoach.mod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Lapisan panah di atas seluruh layar (di dalam window overlay). */
class ArrowOverlayView(context: Context) : View(context) {

    data class Arrow(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val color: Int)

    private val arrows = ArrayList<Arrow>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setArrows(list: List<Arrow>) {
        arrows.clear()
        arrows.addAll(list)
        invalidate()
    }

    fun clear() {
        arrows.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (a in arrows) {
            paint.color = a.color
            headPaint.color = a.color
            canvas.drawLine(a.fromX, a.fromY, a.toX, a.toY, paint)

            // kepala panah
            val dx = a.toX - a.fromX
            val dy = a.toY - a.fromY
            val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (len < 1f) continue
            val ux = dx / len
            val uy = dy / len
            val size = 26f
            val baseX = a.toX - ux * size
            val baseY = a.toY - uy * size
            val nx = -uy
            val ny = ux
            val head = Path()
            head.moveTo(a.toX, a.toY)
            head.lineTo(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
            head.lineTo(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
            head.close()
            canvas.drawPath(head, headPaint)
        }
    }
}
