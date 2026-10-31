package com.ryzellx.boardcoach.mod

/** Profil kekuatan engine — port dari elo.js (tabel dipertahankan). */
data class EloProfile(
    val elo: Int,
    val skill: Int,
    val movetime: Int,
    val depth: Int,
    val blunder: Double,
) {
    companion object {
        val TABLE = listOf(
            EloProfile(600, 0, 450, 3, 0.45),
            EloProfile(800, 1, 500, 4, 0.38),
            EloProfile(1000, 3, 550, 5, 0.30),
            EloProfile(1200, 5, 550, 6, 0.22),
            EloProfile(1400, 7, 650, 7, 0.15),
            EloProfile(1600, 9, 750, 8, 0.10),
            EloProfile(1800, 11, 850, 9, 0.06),
            EloProfile(2000, 13, 1000, 10, 0.03),
            EloProfile(2200, 16, 1200, 11, 0.01),
            EloProfile(2500, 18, 1500, 12, 0.0),
            EloProfile(2800, 20, 2000, 14, 0.0),
        )

        fun forElo(elo: Int): EloProfile {
            val e = elo.coerceIn(600, 2800)
            for (i in 0 until TABLE.size - 1) {
                val a = TABLE[i]; val b = TABLE[i + 1]
                if (e >= a.elo && e <= b.elo) {
                    val t = (e - a.elo).toDouble() / (b.elo - a.elo)
                    return EloProfile(
                        elo = e,
                        skill = Math.round(a.skill + (b.skill - a.skill) * t).toInt(),
                        movetime = Math.round(a.movetime + (b.movetime - a.movetime) * t).toInt(),
                        depth = Math.round(a.depth + (b.depth - a.depth) * t).toInt(),
                        blunder = (a.blunder + (b.blunder - a.blunder) * t),
                    )
                }
            }
            return TABLE.last()
        }
    }
}
