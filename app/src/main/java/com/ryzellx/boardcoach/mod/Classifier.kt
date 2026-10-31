package com.ryzellx.boardcoach.mod

/**
 * Klasifikasi langkah — port dari classify.js (tanpa DOM).
 * Diberi eval sebelum/sesudah + langkah yg dimainkan vs best.
 */
object Classifier {

    enum class Cat(val key: String, val label: String, val glyph: String) {
        BRILLIANT("brilliant", "Brilliant", "!!"),
        GREAT("great", "Great", "!"),
        BEST("best", "Best", "★"),
        EXCELLENT("excellent", "Excellent", "✓"),
        GOOD("good", "Good", "✓"),
        BOOK("book", "Book", "▤"),
        FORCED("forced", "Forced", "→"),
        INACCURACY("inaccuracy", "Inaccuracy", "?!"),
        MISTAKE("mistake", "Mistake", "?"),
        MISS("miss", "Miss", "✗"),
        BLUNDER("blunder", "Blunder", "??"),
    }

    fun classify(
        cpBefore: Double,
        cpAfter: Double,
        legalCount: Int,
        isBook: Boolean,
        playedUci: String,
        bestUci: String,
        secondGap: Double,
        matBefore: Int,
        matAfter: Int,
    ): Cat {
        if (legalCount == 1) return Cat.FORCED
        if (isBook) return Cat.BOOK
        val loss = Math.max(0.0, cpBefore - cpAfter)
        val isBest = playedUci == bestUci

        val hadMate = cpBefore > 90000
        val stillMate = cpAfter > 90000
        if (hadMate && !stillMate) return Cat.MISS

        val sac = matAfter - matBefore
        if (isBest && sac <= -1 && loss <= 40 && cpAfter >= 40 && cpAfter < 90000) {
            return Cat.BRILLIANT
        }
        if (isBest && secondGap >= 120) return Cat.GREAT
        if (isBest) return Cat.BEST

        return when {
            loss <= 20 -> Cat.EXCELLENT
            loss <= 50 -> Cat.GOOD
            loss <= 100 -> Cat.INACCURACY
            cpBefore >= 250 && cpAfter < 100 -> Cat.MISS
            loss <= 250 -> Cat.MISTAKE
            else -> Cat.BLUNDER
        }
    }

    fun emptyStats(): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        Cat.entries.forEach { m[it.key] = 0 }
        m["moves"] = 0
        return m
    }

    fun addToStats(stats: MutableMap<String, Int>, cat: Cat?) {
        if (cat != null) stats[cat.key] = (stats[cat.key] ?: 0) + 1
        stats["moves"] = (stats["moves"] ?: 0) + 1
    }

    fun accuracy(avgLoss: Double): Int {
        val l = Math.max(0.0, avgLoss)
        val a = 103.1668 * Math.exp(-0.04354 * l) - 3.1669
        return a.coerceIn(0.0, 100.0).toInt()
    }
}
