package com.ryzellx.boardcoach.mod

import android.graphics.Bitmap

/**
 * Baca papan catur dari Bitmap hasil screenshot.
 * Strategi: deteksi kotak-kotak papan via warna terang/gelap bergantian,
 * lalu klasifikasi bidak dari warna dominan (putih/hitam) + rasio isi.
 */
object BoardReader {

    const val SIZE = 8
    private val FILES = "abcdefgh"

    /** Papan diputar (main sebagai hitam). */
    @Volatile var flipped = false

    data class Piece(val color: Char, val type: Char) // color: 'w'/'b', type: 'p'..'k'
    data class Square(val file: Int, val rank: Int) { // 0-7, rank 1=baris bawah
        val name: String get() = FILES[file].toString() + (rank + 1)
    }
    class BoardException(msg: String) : Exception(msg)

    /**
     * Cari bounding box 64 petak dari bitmap. Asumsi: papan menempati area cukup besar
     * dan petak terang/gelap berselang-seling. Return null kalau tidak ketemu.
     */
    fun findBoard(bitmap: Bitmap): android.graphics.Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val half = w.coerceAtMost(h) / 2

        // sampel baris horizontal tengah, cari 2 warna paling sering (terang & gelap)
        val hist = HashMap<Int, Int>()
        val yMid = h / 2
        for (x in 0 until w step 8) {
            val c = bitmap.getPixel(x, yMid)
            val key = (c and 0xF8 shl 8) or (c and 0xF8 shl 3) or (c and 0xF8 ushr 3)
            hist[key] = (hist[key] ?: 0) + 1
        }
        if (hist.size < 2) return null

        val sorted = hist.entries.sortedByDescending { it.value }
        if (sorted.size < 2) return null
        val light = sorted[0].key
        val dark = sorted[1].key
        val lightPix = intArrayOf(light shr 16 and 0xFF, light shr 8 and 0xFF, light and 0xFF)
        val darkPix = intArrayOf(dark shr 16 and 0xFF, dark shr 8 and 0xFF, dark and 0xFF)

        // cari persegi terbesar yang rata-rata warnanya alternatif terang/gelap
        var best: android.graphics.Rect? = null
        var bestScore = 0.0

        // coba skala: mulai dari 50% setengah layar sampai 90%
        for (frac in 10 downTo 6) {
            val side = half * frac / 10
            for (cx in 0..(w - side) step 8) {
                for (cy in 0..(h - side) step 8) {
                    val r = android.graphics.Rect(cx, cy, cx + side, cy + side)
                    var score = 0.0
                    var n = 0
                    for (gy in 0 until 8) {
                        for (gx in 0 until 8) {
                            val px = r.left + (gx + 0.5f) * side / 8
                            val py = r.top + (gy + 0.5f) * side / 8
                            val c = bitmap.getPixel(px.toInt(), py.toInt())
                            val rr = c shr 16 and 0xFF
                            val gg = c shr 8 and 0xFF
                            val bb = c and 0xFF
                            val dL = Math.abs(rr - lightPix[0]) + Math.abs(gg - lightPix[1]) + Math.abs(bb - lightPix[2])
                            val dD = Math.abs(rr - darkPix[0]) + Math.abs(gg - darkPix[1]) + Math.abs(bb - darkPix[2])
                            val expectedLight = ((gx + gy) % 2 == 0)
                            if (expectedLight) score += (1.0 - Math.min(dL, dD) / 768.0) else score += (1.0 - Math.min(dL, dD) / 768.0)
                            n++
                        }
                    }
                    score /= n
                    if (score > bestScore) { bestScore = score; best = r }
                }
            }
        }
        return if (bestScore > 0.35) best else null
    }

    /** Baca FEN penuh dari bitmap (auto-deteksi papan). */
    fun readFen(bitmap: Bitmap): String {
        val board = findBoard(bitmap) ?: throw BoardException("papan tidak ketemu")
        return readFenFrom(bitmap, board)
    }

    /** Baca FEN dari rect papan. Orientasi (diputar/nggak) dideteksi otomatis. */
    fun readFenFrom(bitmap: Bitmap, board: android.graphics.Rect): String {
        val bp = BoardPixels(bitmap, board)
        val disp = Array(SIZE) { arrayOfNulls<Char>(SIZE) }
        for (dr in 0 until SIZE) {
            for (df in 0 until SIZE) {
                disp[dr][df] = PieceTemplates.classifyDisplay(bp, df, dr)
            }
        }
        detectOrientation(bp, disp)?.let { flipped = it }
        lastGrid = disp

        val sb = StringBuilder()
        for (arRow in 0 until SIZE) {
            var empty = 0
            for (af in 0 until SIZE) {
                val df = if (flipped) 7 - af else af
                val dr = if (flipped) 7 - arRow else arRow
                val pc = disp[dr][df]
                if (pc == null) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(pc)
                }
            }
            if (empty > 0) sb.append(empty)
            if (arRow < SIZE - 1) sb.append('/')
        }
        val boardPart = sb.toString()
        lastBoardPart = boardPart
        return "$boardPart w - - 0 1"
    }

    /** Part papan (tanpa " w - - 0 1") dari pembacaan terakhir — untuk auto-learn. */
    @Volatile var lastBoardPart: String = ""

    /** Cek FEN masuk akal: 8 baris, tiap baris 8 petak, ada 1 raja tiap warna. */
    fun isValidBoardPart(part: String): Boolean {
        val rows = part.split("/")
        if (rows.size != 8) return false
        var wK = 0; var bK = 0
        for (row in rows) {
            var n = 0
            for (ch in row) {
                if (ch.isDigit()) n += ch - '0'
                else { n++; if (ch == 'K') wK++; if (ch == 'k') bK++ }
            }
            if (n != 8) return false
        }
        return wK == 1 && bK == 1
    }

    /** Grid display (dRow, dFile) dari pembacaan terakhir. */
    @Volatile var lastGrid: Array<Array<Char?>>? = null

    /**
     * Orientasi dari KECERAHAN bidak (bukan jenis bidak yang bisa salah):
     * bidak putih selalu lebih terang. Hanya diputuskan kalau papan sedang
     * di posisi awal (2 baris pinggir terisi penuh) — jadi tidak salah tebak.
     * Ini yang bikin panah dulu muncul di sisi musuh.
     */
    private fun detectOrientation(bp: BoardPixels, disp: Array<Array<Char?>>): Boolean? {
        var topSum = 0.0; var topN = 0
        var botSum = 0.0; var botN = 0
        for (df in 0 until SIZE) {
            for (dr in 0..1) {
                val ex = PieceTemplates.extract(bp, df, dr)
                if (ex.fill >= 0.06f) { topSum += ex.maxLuma; topN++ }
            }
            for (dr in 6..7) {
                val ex = PieceTemplates.extract(bp, df, dr)
                if (ex.fill >= 0.06f) { botSum += ex.maxLuma; botN++ }
            }
        }
        if (topN < 6 || botN < 6) return null
        val tl = topSum / topN
        val bl = botSum / botN
        if (bl - tl > 25) return false   // putih di bawah → normal
        if (tl - bl > 25) return true    // putih di atas → papan diputar
        return null
    }

    /** Orientasi otomatis: putih di 2 baris atas → papan diputar. */
    private fun detectFlip(disp: Array<Array<Char?>>): Boolean? {
        var topW = 0; var topB = 0; var botW = 0; var botB = 0
        for (df in 0 until SIZE) {
            for (dr in 0..1) disp[dr][df]?.let { if (it.isUpperCase()) topW++ else topB++ }
            for (dr in 6..7) disp[dr][df]?.let { if (it.isUpperCase()) botW++ else botB++ }
        }
        if (topW + topB + botW + botB < 8) return null
        if (botW >= topW + 4) return false
        if (topW >= botW + 4) return true
        return null
    }

    private fun Piece.toFenChar(): Char =
        if (color == 'w') type.uppercaseChar() else type

    /** Klasifikasi satu petak: warna dominan & jenis bidak. */
    private fun classifySquare(bitmap: Bitmap, cx: Int, cy: Int, side: Int): Piece? {
        var lightCount = 0
        var darkCount = 0
        var total = 0
        val r0 = (cy - side / 2).coerceAtLeast(0)
        val r1 = (cy + side / 2).coerceAtMost(bitmap.height - 1)
        val c0 = (cx - side / 2).coerceAtLeast(0)
        val c1 = (cx + side / 2).coerceAtMost(bitmap.width - 1)

        for (y in r0 until r1 step 4) {
            for (x in c0 until c1 step 4) {
                val c = bitmap.getPixel(x, y)
                val rr = c shr 16 and 0xFF
                val gg = c shr 8 and 0xFF
                val bb = c and 0xFF
                val lum = (rr * 299 + gg * 587 + bb * 114) / 1000
                total++
                if (lum > 150) lightCount++ else darkCount++
            }
        }
        if (total == 0) return null

        // jenis bidak: asumsikan di petak yang terisi, ada area tengah signifikan
        // yang warnanya kontras dgn latar; baca dulu apakah ada "bidak" sama sekali
        val centerLum = avgLuma(bitmap, cx, cy, side / 5)
        val squareLum = avgLuma(bitmap, cx, cy, side / 2)
        val diff = Math.abs(centerLum - squareLum)
        if (diff < 4) return null // polos, kosong

        val color = if (centerLum > 140) 'w' else 'b'
        // jenis: perkiraan kasar dari rasio "isi" + bentuk. Buat ini sederhana:
        // bidak kecil (pion) sering lebih sedikit mengisi daripada besar, tapi
        // untuk MVP, kita kira semua sebagai 'p' (pion) — engine tetap jalan karena
        // BUTUH total bidak & posisi; jenis yang salah mengurangi akurasi.
        val fillRatio = (lightCount + darkCount).toDouble() / total
        val type = when {
            fillRatio > 0.72 -> 'k' // banyak isi = raja
            fillRatio > 0.6 -> 'q'
            fillRatio > 0.48 -> 'r'
            fillRatio > 0.38 -> 'b'
            fillRatio > 0.28 -> 'n'
            else -> 'p'
        }
        return Piece(color, type)
    }

    private fun avgLuma(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Int {
        var sum = 0L
        var n = 0L
        for (y in (cy - radius).coerceAtLeast(0) until (cy + radius).coerceAtMost(bitmap.height) step 2) {
            for (x in (cx - radius).coerceAtLeast(0) until (cx + radius).coerceAtMost(bitmap.width) step 2) {
                val c = bitmap.getPixel(x, y)
                sum += (c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114
                n++
            }
        }
        return if (n == 0L) 0 else (sum / n / 1000).toInt()
    }
}
