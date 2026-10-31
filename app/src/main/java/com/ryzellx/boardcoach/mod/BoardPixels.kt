package com.ryzellx.boardcoach.mod

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Buffer piksel untuk SATU area (papan).
 * Satu kali getPixels() → sampling 64 petak jauh lebih cepat daripada
 * ribuan getPixel() (tiap getPixel itu panggilan JNI).
 */
class BoardPixels(bmp: Bitmap, rect: Rect) {
    val w: Int = rect.width().coerceAtLeast(1)
    val h: Int = rect.height().coerceAtLeast(1)
    val px: IntArray = IntArray(w * h)

    init {
        bmp.getPixels(px, 0, w, rect.left, rect.top, w, h)
    }

    fun at(x: Int, y: Int): Int = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
}
