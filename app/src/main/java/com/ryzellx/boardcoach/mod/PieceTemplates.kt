package com.ryzellx.boardcoach.mod

import android.graphics.Rect

/**
 * Pengenal bidak TEMPLATE MATCHING — versi AUTO.
 *
 * Tidak ada langkah manual: begitu papan terlihat dalam posisi awal standar,
 * template 12 bidak diambil otomatis dari layar user (jadi cocok untuk tema
 * bidak apa pun). Kalau belum belajar → heuristik bentuk sebagai cadangan.
 *
 * Semua sampling lewat BoardPixels (satu getPixels per frame) supaya cepat.
 */
object PieceTemplates {

    const val N = 16
    private const val THRESH = 110
    private const val MIN_FILL = 0.06f

    private val templates = HashMap<String, FloatArray>()

    @Volatile var lastConfidence: Float = 0f

    fun isLearned(): Boolean = templates.size >= 10
    fun learnedCount(): Int = templates.size
    fun clear() { templates.clear(); lastConfidence = 0f }

    data class Sq(val mask: FloatArray, val fill: Float, val luma: Int)

    /* ---------- geometri (koordinat DISPLAY: dRow 0 = atas layar) ---------- */

    fun squareOf(bp: BoardPixels, dFile: Int, dRow: Int): Rect {
        val side = bp.w.toDouble() / 8
        val l = (dFile * side).toInt()
        val t = (dRow * side).toInt()
        return Rect(l, t, (l + side).toInt(), (t + side).toInt())
    }

    /* ---------- ekstraksi mask satu petak ---------- */

    fun extract(bp: BoardPixels, dFile: Int, dRow: Int): Sq {
        val sq = squareOf(bp, dFile, dRow)
        val inset = (sq.width() * 0.16).toInt().coerceAtLeast(1)
        val cs = intArrayOf(
            bp.at(sq.left + inset, sq.top + inset),
            bp.at(sq.right - inset, sq.top + inset),
            bp.at(sq.left + inset, sq.bottom - inset),
            bp.at(sq.right - inset, sq.bottom - inset),
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
                val c = bp.at(
                    sq.left + ((gx + 0.5) * stepX).toInt(),
                    sq.top + ((gy + 0.5) * stepY).toInt(),
                )
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
        return Sq(mask, cnt.toFloat() / (N * N), if (cnt > 0) (lumaSum / cnt).toInt() else 0)
    }

    /* ---------- klasifikasi (koordinat display) ---------- */

    fun classifyDisplay(bp: BoardPixels, dFile: Int, dRow: Int): Char? {
        val ex = extract(bp, dFile, dRow)
        if (ex.fill < MIN_FILL) return null
        val color = if (ex.luma > 150) 'w' else 'b'

        if (!isLearned()) {
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

    /* ---------- belajar otomatis ---------- */

    private fun startCells(): List<Triple<Int, Int, Char>> {
        val back = "RNBQKBNR"
        val out = ArrayList<Triple<Int, Int, Char>>()
        for (f in 0 until 8) {
            out.add(Triple(f, 0, back[f]))
            out.add(Triple(f, 1, 'P'))
            out.add(Triple(f, 6, 'p'))
            out.add(Triple(f, 7, back[f].lowercaseChar()))
        }
        return out
    }

    /** Belajar dari buffer frame sekarang. Return jumlah template (maks 12). */
    fun learn(bp: BoardPixels, flip: Boolean): Int {
        val acc = HashMap<String, FloatArray>()
        val n = HashMap<String, Int>()

        for ((f, r, pc) in startCells()) {
            val df = if (flip) 7 - f else f
            val dr = if (flip) r else 7 - r
            val ex = extract(bp, df, dr)
            if (ex.fill < MIN_FILL) continue
            val key = (if (pc.isUpperCase()) "w" else "b") + pc.lowercaseChar()
            val cur = acc.getOrPut(key) { FloatArray(N * N) }
            for (i in ex.mask.indices) cur[i] += ex.mask[i]
            n[key] = (n[key] ?: 0) + 1
        }

        templates.clear()
        for ((k, sum) in acc) {
            val c = n[k] ?: 0
            if (c < 2) continue
            for (i in sum.indices) sum[i] /= c
            templates[k] = sum
        }
        return templates.size
    }

    private val START_FENS = setOf(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr",
    )

    fun looksLikeStart(boardPart: String): Boolean = START_FENS.contains(boardPart)

    /**
     * Deteksi posisi awal secara STRUKTURAL (tidak bergantung jenis bidak yang
     * mungkin salah diklasifikasi): 32 bidak, hanya di 4 baris pinggir, tiap
     * baris pinggir penuh 8. Ini pemicu auto-learn.
     */
    fun looksLikeOpening(disp: Array<Array<Char?>>?): Boolean {
        if (disp == null) return false
        var count = 0
        for (dr in 0 until 8) {
            for (df in 0 until 8) {
                if (disp[dr][df] != null) {
                    if (dr in 2..5) return false
                    count++
                }
            }
        }
        if (count != 32) return false
        for (dr in intArrayOf(0, 1, 6, 7)) {
            var n = 0
            for (df in 0 until 8) if (disp[dr][df] != null) n++
            if (n != 8) return false
        }
        return true
    }
}
