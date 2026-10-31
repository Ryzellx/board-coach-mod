package com.ryzellx.boardcoach.mod

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Deteksi otomatis posisi papan dari screenshot.
 *
 * Prinsip: papan catur = persegi besar yang 64 petaknya berselang-seling
 * (kelompok genap & ganjil masing-masing berwarna seragam, dan dua kelompok itu
 * berbeda warna). Skor dihitung dari (1) keragaman dalam kelompok dan
 * (2) jarak warna antar kelompok — tanpa perlu tahu tema warnanya.
 *
 * Dijalankan sekali (atau sesekali kalau gagal), bukan tiap frame.
 */
object BoardLocator {

    data class Hit(val rect: Rect, val score: Double)

    // 8 titik sampel di pinggir petak (hindari tengah yang ketutup bidak)
    private val RING = arrayOf(
        doubleArrayOf(0.18, 0.18), doubleArrayOf(0.5, 0.18), doubleArrayOf(0.82, 0.18),
        doubleArrayOf(0.18, 0.5), doubleArrayOf(0.82, 0.5),
        doubleArrayOf(0.18, 0.82), doubleArrayOf(0.5, 0.82), doubleArrayOf(0.82, 0.82),
    )

    fun locate(bmp: Bitmap): Hit? {
        val maxW = 480
        val scale = if (bmp.width > maxW) maxW.toDouble() / bmp.width else 1.0
        val sw = (bmp.width * scale).toInt().coerceAtLeast(64)
        val sh = (bmp.height * scale).toInt().coerceAtLeast(64)
        val small = if (scale < 1.0) Bitmap.createScaledBitmap(bmp, sw, sh, false) else bmp

        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()

        val minSide = minOf(w, h)
        var best: Hit? = null
        var f = 1.0
        while (f >= 0.45) {
            val side = (minSide * f).toInt()
            if (side >= 120) {
                val step = (side / 10).coerceAtLeast(4)
                var cx = 0
                while (cx + side <= w) {
                    var cy = 0
                    while (cy + side <= h) {
                        val s = score(px, w, h, cx, cy, side)
                        if (best == null || s > best!!.score) {
                            best = Hit(Rect(cx, cy, cx + side, cy + side), s)
                        }
                        cy += step
                    }
                    cx += step
                }
            }
            f -= 0.04
        }

        val b = best ?: return null
        if (b.score < 0.5) return null
        if (scale < 1.0) {
            val inv = 1.0 / scale
            return Hit(
                Rect(
                    (b.rect.left * inv).toInt(), (b.rect.top * inv).toInt(),
                    (b.rect.right * inv).toInt(), (b.rect.bottom * inv).toInt(),
                ),
                b.score,
            )
        }
        return b
    }

    private fun score(px: IntArray, w: Int, h: Int, left: Int, top: Int, side: Int): Double {
        val s = side.toDouble() / 8
        val colors = Array(2) { ArrayList<DoubleArray>(32) }
        val sum = Array(2) { DoubleArray(3) }

        for (dy in 0 until 8) {
            for (dx in 0 until 8) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (p in RING) {
                    val x = (left + (dx + p[0]) * s).toInt().coerceIn(0, w - 1)
                    val y = (top + (dy + p[1]) * s).toInt().coerceIn(0, h - 1)
                    val c = px[y * w + x]
                    r += c shr 16 and 0xFF
                    g += c shr 8 and 0xFF
                    b += c and 0xFF
                }
                val col = doubleArrayOf(r / 8, g / 8, b / 8)
                val idx = (dx + dy) % 2
                colors[idx].add(col)
                sum[idx][0] += col[0]; sum[idx][1] += col[1]; sum[idx][2] += col[2]
            }
        }

        val n0 = colors[0].size; val n1 = colors[1].size
        if (n0 == 0 || n1 == 0) return 0.0
        val m0 = doubleArrayOf(sum[0][0] / n0, sum[0][1] / n0, sum[0][2] / n0)
        val m1 = doubleArrayOf(sum[1][0] / n1, sum[1][1] / n1, sum[1][2] / n1)

        var varSum = 0.0
        for (col in colors[0]) varSum += Math.abs(col[0] - m0[0]) + Math.abs(col[1] - m0[1]) + Math.abs(col[2] - m0[2])
        for (col in colors[1]) varSum += Math.abs(col[0] - m1[0]) + Math.abs(col[1] - m1[1]) + Math.abs(col[2] - m1[2])
        val avgVar = varSum / 64.0

        val sep = Math.abs(m0[0] - m1[0]) + Math.abs(m0[1] - m1[1]) + Math.abs(m0[2] - m1[2])

        val sepScore = (sep / 110.0).coerceIn(0.0, 1.0)
        val uniScore = (1.0 - avgVar / 50.0).coerceIn(0.0, 1.0)
        return sepScore * 0.6 + uniScore * 0.4
    }
}
