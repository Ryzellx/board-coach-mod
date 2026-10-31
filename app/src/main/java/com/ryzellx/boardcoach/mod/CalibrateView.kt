package com.ryzellx.boardcoach.mod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Overlay kalibrasi manual: tap 2 pojok papan (kiri-atas, kanan-bawah).
 * Jauh lebih andal daripada auto-detect dari warna.
 */
class CalibrateView(
    context: Context,
    private val onDone: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val bg = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt(); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 42f; textAlign = Paint.Align.CENTER
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER
    }

    private var first: Pair<Float, Float>? = null
    private var second: Pair<Float, Float>? = null
    private var downTime = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val msg = if (first == null) "Tap pojok KIRI-ATAS papan" else "Tap pojok KANAN-BAWAH papan"
        canvas.drawText(msg, width / 2f, 100f, text)
        canvas.drawText("(tekan lama untuk batal)", width / 2f, 146f, sub)

        val f = first
        val s = second
        f?.let { canvas.drawCircle(it.first, it.second, 16f, dot) }
        s?.let { canvas.drawCircle(it.first, it.second, 16f, dot) }
        if (f != null && s != null) {
            val l = minOf(f.first, s.first)
            val t = minOf(f.second, s.second)
            val r = maxOf(f.first, s.first)
            val b = maxOf(f.second, s.second)
            canvas.drawRect(l, t, r, b, line)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (System.currentTimeMillis() - downTime > 900) { onCancel(); return true }
                val p = event.x to event.y
                val f = first
                if (f == null) {
                    first = p
                } else {
                    second = p
                    val l = minOf(f.first, p.first).toInt()
                    val t = minOf(f.second, p.second).toInt()
                    val r = maxOf(f.first, p.first).toInt()
                    val b = maxOf(f.second, p.second).toInt()
                    if (r - l > 120 && b - t > 120) {
                        onDone(Rect(l, t, r, b))
                        return true
                    } else {
                        first = p; second = null
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
