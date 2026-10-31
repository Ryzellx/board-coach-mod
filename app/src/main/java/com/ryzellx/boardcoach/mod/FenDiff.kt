package com.ryzellx.boardcoach.mod

/** Turunkan langkah UCI dari dua FEN berurutan (diff papan). */
object FenDiff {

    /** Return UCI move (mis. "e2e4", "e7e8q") atau null kalau tak bisa disimpulkan. */
    fun playedMove(fenBefore: String, fenAfter: String): String? {
        val a = boardMap(fenBefore) ?: return null
        val b = boardMap(fenAfter) ?: return null

        val removed = ArrayList<Pair<Int, Char>>() // square -> piece
        val added = ArrayList<Pair<Int, Char>>()

        for (i in 0 until 64) {
            val pa = a[i]; val pb = b[i]
            if (pa != pb) {
                if (pa != '.') removed.add(i to pa)
                if (pb != '.') added.add(i to pb)
            }
        }

        // langkah biasa: 1 hilang, 1 muncul
        if (removed.size == 1 && added.size == 1) {
            val from = sq(removed[0].first)
            val to = sq(added[0].first)
            val promo = promoChar(added[0].second)
            return from + to + promo
        }

        // en passant / tangkap: 2 hilang (asal + korban), 1 muncul
        if (removed.size == 2 && added.size == 1) {
            val toIdx = added[0].first
            // asal = yang punya bidak warna sama dgn yang muncul
            val mover = added[0].second
            val origin = removed.firstOrNull { sameColor(it.second, mover) && it.first != toIdx }
            if (origin != null) {
                val promo = promoChar(mover)
                return sq(origin.first) + sq(toIdx) + promo
            }
        }

        // promosi tanpa tangkap: 1 hilang, 1 muncul sudah ditangani di atas
        return null
    }

    private fun promoChar(piece: Char): String = when (piece.lowercaseChar()) {
        'q' -> "q"; 'r' -> "r"; 'b' -> "b"; 'n' -> "n"; else -> ""
    }

    private fun sameColor(a: Char, b: Char): Boolean =
        (a.isUpperCase() && b.isUpperCase()) || (a.isLowerCase() && b.isLowerCase())

    /** 64 array petak: index = rank*8 + file, '.' kalau kosong. rank0 = baris 8 (atas). */
    private fun boardMap(fen: String): Array<Char>? {
        val boardPart = fen.substringBefore(" ")
        val rows = boardPart.split("/")
        if (rows.size != 8) return null
        val out = Array(64) { '.' }
        for (r in 0 until 8) {
            var f = 0
            for (ch in rows[r]) {
                if (ch.isDigit()) { f += ch - '0' } else {
                    if (f in 0..7) out[r * 8 + f] = ch
                    f++
                }
            }
        }
        return out
    }

    private fun sq(idx: Int): String {
        val rank = idx / 8   // 0 = baris atas = rank 8
        val file = idx % 8
        return "${('a' + file)}${8 - rank}"
    }
}
