package com.eric.todo.importer

/**
 * Finds tasks that say the same thing in slightly different words.
 * "Call Eddie about PM schedule" ~ "call eddie re: the PM schedule".
 */
object Dupes {

    /** Same task, merge without much doubt. */
    const val SAME = 0.90
    /** Probably the same task; ask. */
    const val SIMILAR = 0.70

    private val filler = setOf(
        "a", "an", "the", "to", "and", "for", "of", "on", "in", "at", "with", "about", "re", "regarding",
        "my", "our", "his", "her", "their", "need", "needs", "i", "we", "please", "get", "go", "do",
        "make", "sure", "that", "this", "it", "is", "be", "up", "out", "from", "by", "or",
    )

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun words(s: String): List<String> =
        normalize(s).split(" ").filter { it.isNotEmpty() && it !in filler }.map(::stem)

    /** Very light stemming so "calls/calling/called" all count as "call". */
    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    private fun bigrams(s: String): List<String> {
        val t = words(s).joinToString(" ")
        if (t.length < 2) return listOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }
    }

    /** 0.0 (unrelated) to 1.0 (identical). */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val wa = words(a).toSet()
        val wb = words(b).toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        if (wa == wb) return 0.97

        val inter = wa.intersect(wb).size.toDouble()
        val jaccard = inter / wa.union(wb).size
        // One is the other plus a detail: "Call Eddie" vs "Call Eddie about PM schedule"
        val containment = inter / minOf(wa.size, wb.size)

        val ba = bigrams(a)
        val bb = bigrams(b).toMutableList()
        var common = 0
        for (g in ba) { if (bb.remove(g)) common++ }
        val dice = 2.0 * common / (ba.size + bigrams(b).size)

        val containScore = if (minOf(wa.size, wb.size) >= 2) containment * 0.85 else 0.0
        return maxOf(dice, jaccard, containScore)
    }

    data class Match(val index: Int, val score: Double)

    /** Best match for [text] among [candidates], or null if nothing is similar enough. */
    fun bestMatch(text: String, candidates: List<String>, threshold: Double = SIMILAR): Match? {
        var best: Match? = null
        candidates.forEachIndexed { i, c ->
            val s = similarity(text, c)
            if (s >= threshold && (best == null || s > best!!.score)) best = Match(i, s)
        }
        return best
    }

    /** Pairs of similar items inside one list, most similar first. */
    fun pairs(texts: List<String>, threshold: Double = SIMILAR): List<Triple<Int, Int, Double>> {
        val out = mutableListOf<Triple<Int, Int, Double>>()
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            val s = similarity(texts[i], texts[j])
            if (s >= threshold) out += Triple(i, j, s)
        }
        return out.sortedByDescending { it.third }
    }
}
