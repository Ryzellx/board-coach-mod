package com.ryzellx.boardcoach.mod

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Pengenal bidak berbasis TEMPLATE MATCHING.
 *
 * Cara kerja:
 *  1. "Belajar bidak" → user set papan ke posisi awal, kita crop 32 petak yang
 *     sudah diketahui isinya (RNBQKBNR / 8 pion hitam / 8 pion putih) → simpan
 *     mask 16x16 tiap jenis sebagai template.
 *  2. Saat baca papan: tiap petak di-crop, di-mask (piksel yang beda dari warna
 *     latar petak), lalu dicocokkan ke template terdekat (SSD) + dicocokkan warna.
 *
 * Jauh lebih akurat daripada heuristik fill-ratio (yang cuma nebak dari "seberapa
 * penuh" petak). Tanpa aset eksternal — template diambil dari layar user sendiri,
 * jadi otomatis cocok dengan tema bidak apa pun (neo, classic, dll).
 */
object PieceTemplates {

    const val N = 16                 // resolusi mask per sisi
    private const val THRESH = 110   // ambang jarak warna latar→bidak
    private const val MIN_FILL = 0.06f

    private val templates = HashMap<String, FloatArray>()   // "wp","wn",... -> mask rata-rata
    private val samples = HashMap<String, Int>()

    /** Akurasi baca terakhir (0..1) — untuk indikator di panel. */
    @Volatile var lastConfidence: Float = 0f

    fun isLearned(): Boolean = templates.size >= 10

    fun clear() { templates.clear(); samples.clear(); lastConfidence = 0f }

    fun learnedCount(): Int = templates.size

    data class Sq(val mask: FloatArray, val fill: Float, val luma: Int)

    /* ---------------- geometri ---------------- */

    fun squareRect(board: Rect, file: Int, rank: Int, flip: Boolean): Rect {
        val side = board.width().toDouble() / 8
        val f = if (flip) 7 - file else file
        val r = if (flip) rank else 7 - rank          // rank 0 (rank1) di bawah kalau tidak flip
        val l = (board.left + f * side).toInt()
        val t = (board.top + r * side).toInt()
        return Rect(l, t, (board.left + (f + 1) * side).toInt(), (board.top + (r + 1) * side).toInt())
    }

    /* ---------------- ekstraksi mask ---------------- */

    fun extract(bmp: Bitmap, sq: Rect): Sq {
        val inset = (sq.width() * 0.16).toInt().coerceAtLeast(1)
        val cs = intArrayOf(
            bmp.getPixel((sq.left + inset).coerceIn(0, bmp.width - 1), (sq.top + inset).coerceIn(0, bmp.height - 1)),
            bmp.getPixel((sq.right - inset).coerceIn(0, bmp.width - 1), (sq.top + inset).coerceIn(0, bmp.height - 1)),
            bmp.getPixel((sq.left + inset).coerceIn(0, bmp.width - 1), (sq.bottom - inset).coerceIn(0, bmp.height - 1)),
            bmp.getPixel((sq.right - inset).coerceIn(0, bmp.width - 1), (sq.bottom - inset).coerceIn(0, bmp.height - 1)),
        )
        val rs = cs.map { it shr 16 and 0xFF }.sorted()
        val gs = cs.map { it shr 8 and 0xFF }.sorted()
        val bs = cs.map { it and 0xFF }.sorted()
        val bgR = rs[1]; val bgG = gs[1]; val bgB = bs[1]

        val mask = FloatArray(N * N)
        var cnt = 0
        var lumaSum = 0L
        val stepX = sq.width().toDouble() / N
        val stepY = sq.height().toDouble() / N

        for (gy in 0 until N) {
            for (gx in 0 until N) {
                val x = (sq.left + (gx + 0.5) * stepX).toInt().coerceIn(0, bmp.width - 1)
                val y = (sq.top + (gy + 0.5) * stepY).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                val d = Math.abs(r - bgR) + Math.abs(g - bgG) + Math.abs(b - bgB)
                if (d > THRESH) {
                    mask[gy * N + gx] = 1f
                    cnt++
                    lumaSum += (r * 299 + g * 587 + b * 114) / 1000
                }
            }
        }
        val fill = cnt.toFloat() / (N * N)
        val luma = if (cnt > 0) (lumaSum / cnt).toInt() else 0
        return Sq(mask, fill, luma)
    }

    /* ---------------- belajar ---------------- */

    /** Posisi awal standar: map (file, rank) -> piece FEN char. */
    private fun startPosition(): List<Triple<Int, Int, Char>> {
        val out = ArrayList<Triple<Int, Int, Char>>()
        val back = "RNBQKBNR"
        for (f in 0 until 8) {
            out.add(Triple(f, 0, back[f]))      // putih baris 1
            out.add(Triple(f, 1, 'P'))          // pion putih
            out.add(Triple(f, 6, 'p'))          // pion hitam
            out.add(Triple(f, 7, back[f].lowercaseChar())) // hitam baris 8
        }
        return out
    }

    /** Belajar dari frame yang sekarang. Return jumlah template yang berhasil. */
    fun learn(bmp: Bitmap, board: Rect): Int {
        val acc = HashMap<String, FloatArray>()
        val n = HashMap<String, Int>()

        for ((f, r, pc) in startPosition()) {
            val sq = squareRect(board, f, r, false)
            val ex = extract(bmp, sq)
            if (ex.fill < MIN_FILL) continue
            val key = keyOf(pc)
            val cur = acc.getOrPut(key) { FloatArray(N * N) }
            for (i in ex.mask.indices) cur[i] += ex.mask[i]
            n[key] = (n[key] ?: 0) + 1
        }

        templates.clear(); samples.clear()
        for ((k, sum) in acc) {
            val c = n[k] ?: 0
            if (c < 2) continue
            for (i in sum.indices) sum[i] /= c
            templates[k] = sum
            samples[k] = c
        }
        return templates.size
    }

    private fun keyOf(pc: Char): String =
        (if (pc.isUpperCase()) "w" else "b") + pc.lowercaseChar()

    /* ---------------- klasifikasi ---------------- */

    /** Return FEN char ('P','n',...) atau null kalau petak kosong. */
    fun classifyChar(bmp: Bitmap, board: Rect, file: Int, rank: Int, flip: Boolean): Char? {
        val ex = extract(bmp, squareRect(board, file, rank, flip))
        if (ex.fill < MIN_FILL) return null
        val color = if (ex.luma > 150) 'w' else 'b'

        if (!isLearned()) {
            // fallback heuristik (kalau belum belajar)
            val t = when {
                ex.fill > 0.72 -> 'k'; ex.fill > 0.6 -> 'q'; ex.fill > 0.48 -> 'r'
                ex.fill > 0.38 -> 'b'; ex.fill > 0.28 -> 'n'; else -> 'p'
            }
            lastConfidence = 0.3f
            return if (color == 'w') t.uppercaseChar() else t
        }

        var bestKey = ""
        var bestDist = Float.MAX_VALUE
        for ((k, t) in templates) {
            if (k[0] != color) continue
            var d = 0f
            for (i in ex.mask.indices) {
                val dd = ex.mask[i] - t[i]
                d += dd * dd
            }
            if (d < bestDist) { bestDist = d; bestKey = k }
        }
        if (bestKey.isEmpty()) return null
        lastConfidence = (1f - bestDist / (N * N)).coerceIn(0f, 1f)
        val type = bestKey[1]
        return if (color == 'w') type.uppercaseChar() else type
    }
}
