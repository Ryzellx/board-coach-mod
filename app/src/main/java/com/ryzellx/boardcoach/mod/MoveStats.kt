package com.ryzellx.boardcoach.mod

/** Akumulasi statistik langkah + akurasi. */
class MoveStats {

    var moves = 0; private set
    var bestCount = 0; private set
    private var lossSum = 0.0
    val counts = LinkedHashMap<Classifier.Cat, Int>()

    fun add(cat: Classifier.Cat, loss: Double) {
        moves++
        if (cat == Classifier.Cat.BEST || cat == Classifier.Cat.BRILLIANT ||
            cat == Classifier.Cat.GREAT || cat == Classifier.Cat.EXCELLENT) bestCount++
        lossSum += loss.coerceAtLeast(0.0)
        counts[cat] = (counts[cat] ?: 0) + 1
    }

    fun accuracy(): Int =
        Classifier.accuracy(if (moves > 0) lossSum / moves else 0.0)

    fun summary(): String {
        if (moves == 0) return "belum ada langkah"
        val sb = StringBuilder()
        sb.append("langkah: $moves\n")
        sb.append("akurasi: ${accuracy()}%\n")
        sb.append("top: $bestCount\n")
        for ((cat, n) in counts) {
            if (n > 0) sb.append("${cat.glyph} ${cat.label}: $n\n")
        }
        return sb.toString().trimEnd()
    }
}
