package com.ryzellx.boardcoach.mod

import android.graphics.Rect
import kotlin.math.abs

/** Kalibrasi papan: menyimpan peta koordinat layar → petak (file/rank). */
class BoardCalibrator {
    private var boardRect: Rect? = null
    private var screenW = 0
    private var screenH = 0

    fun setScreen(w: Int, h: Int) { screenW = w; screenH = h }

    /** Terima rect papan dari BoardReader.findBoard (dalam koordinat layar). */
    fun calibrate(rect: Rect) {
        boardRect = rect
    }

    val isCalibrated: Boolean get() = boardRect != null
    val rect: Rect? get() = boardRect

    fun clear() { boardRect = null }

    /** Konversi petak → titik pusat layar. flip = papan diputar. */
    fun squareCenter(file: Int, rank: Int, flip: Boolean = false): Pair<Int, Int>? {
        val r = boardRect ?: return null
        val side = r.width().toDouble() / 8
        val f = if (flip) 7 - file else file
        val row = if (flip) rank else 7 - rank
        val x = r.left + (f + 0.5) * side
        val y = r.top + (row + 0.5) * side
        return x.toInt() to y.toInt()
    }

    /** Deteksi apa koordinat ada di dalam papan. */
    fun contains(x: Int, y: Int): Boolean {
        val r = boardRect ?: return false
        return r.contains(x, y)
    }

    /** Konversi titik layar → petak (null kalau di luar). */
    fun pointToSquare(x: Int, y: Int): Pair<Int, Int>? {
        val r = boardRect ?: return null
        if (!r.contains(x, y)) return null
        val side = r.width().toDouble() / 8
        val f = ((x - r.left) / side).toInt().coerceIn(0, 7)
        val rankRow = ((y - r.top) / side).toInt().coerceIn(0, 7)
        return f to (7 - rankRow)
    }

    /** Apakah dua FEN "berbeda" (setelah normalisasi) — dipakai untuk deteksi langkah. */
    companion object {
        fun fenChanged(a: String, b: String): Boolean {
            if (a == b) return false
            val na = a.substringBefore(" ").trim()
            val nb = b.substringBefore(" ").trim()
            return abs(na.length - nb.length) > 1 || na != nb
        }
    }
}
