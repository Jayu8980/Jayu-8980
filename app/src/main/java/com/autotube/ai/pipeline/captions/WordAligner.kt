package com.autotube.ai.pipeline.captions

import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.pipeline.script.ContentText
import kotlin.math.max
import kotlin.math.min

/**
 * Aligns recognised (timed) words to the script's words with a Needleman-Wunsch dynamic program on
 * normalised tokens (works for Devanagari: matras and virama are kept). Captions keep the script
 * spelling but take the recognised timing; unmatched script words are interpolated.
 */
internal object WordAligner {

    class Result(val timings: List<WordTiming>, val matchRatio: Float)

    private const val MATCH = 3
    private const val NEAR = 1
    private const val MISMATCH = -2
    private const val GAP = -1

    fun align(scriptWords: List<String>, recognized: List<WordTiming>, durationMs: Long): Result {
        if (scriptWords.isEmpty()) return Result(emptyList(), 1f)
        val rec = recognized.filter { ContentText.normalizeToken(it.word).isNotEmpty() }.sortedBy { it.startMs }
        if (rec.isEmpty()) return Result(WordTimingEstimator.estimate(scriptWords, durationMs), 0f)

        val a = scriptWords.map(ContentText::normalizeToken)
        val b = rec.map { ContentText.normalizeToken(it.word) }
        val n = a.size
        val m = b.size
        val cols = m + 1
        val score = IntArray((n + 1) * cols)
        // 0 = diagonal, 1 = up (gap in recognised), 2 = left (gap in script)
        val move = ByteArray((n + 1) * cols)
        for (i in 1..n) { score[i * cols] = i * GAP; move[i * cols] = 1.toByte() }
        for (j in 1..m) { score[j] = j * GAP; move[j] = 2.toByte() }
        for (i in 1..n) {
            for (j in 1..m) {
                val diag = score[(i - 1) * cols + j - 1] + similarity(a[i - 1], b[j - 1])
                val up = score[(i - 1) * cols + j] + GAP
                val left = score[i * cols + j - 1] + GAP
                val best = max(diag, max(up, left))
                score[i * cols + j] = best
                move[i * cols + j] = when (best) {
                    diag -> 0
                    up -> 1
                    else -> 2
                }.toByte()
            }
        }

        val starts = LongArray(n) { -1L }
        val ends = LongArray(n) { -1L }
        var strong = 0
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (move[i * cols + j].toInt()) {
                0 -> {
                    val sim = similarity(a[i - 1], b[j - 1])
                    // Substitutions keep the recognised timing too (e.g. "1,000" vs "thousand").
                    if (sim > 0 || a[i - 1].isNotEmpty()) {
                        starts[i - 1] = rec[j - 1].startMs
                        ends[i - 1] = rec[j - 1].endMs
                    }
                    if (sim > 0) strong++
                    i--; j--
                }
                1 -> i--
                else -> j--
            }
        }

        // Several script words mapped onto one recognised word: split that word's time between them.
        var k = 0
        while (k < n) {
            if (starts[k] < 0) { k++; continue }
            var e = k
            while (e + 1 < n && starts[e + 1] == starts[k] && ends[e + 1] == ends[k]) e++
            if (e > k) {
                val span = ends[k] - starts[k]
                val count = e - k + 1
                for (x in k..e) {
                    starts[x] = starts[k] + span * (x - k) / count
                    ends[x] = starts[k] + span * (x - k + 1) / count
                }
            }
            k = e + 1
        }

        interpolate(scriptWords, starts, ends, durationMs)
        val timings = scriptWords.mapIndexed { idx, w -> WordTiming(w, starts[idx], ends[idx]) }
        return Result(WordTimingEstimator.monotonic(timings, durationMs), strong.toFloat() / n)
    }

    /** Fills unmatched words between their matched neighbours, weighted by syllable length. */
    private fun interpolate(words: List<String>, starts: LongArray, ends: LongArray, durationMs: Long) {
        val n = words.size
        var idx = 0
        while (idx < n) {
            if (starts[idx] >= 0) { idx++; continue }
            var runEnd = idx
            while (runEnd + 1 < n && starts[runEnd + 1] < 0) runEnd++
            val from = if (idx > 0) ends[idx - 1] else min(80L, durationMs)
            val to = if (runEnd + 1 < n) starts[runEnd + 1] else max(from, durationMs - 150)
            val weights = (idx..runEnd).map { ContentText.syllableWeight(words[it]).toDouble() + 0.25 }
            val total = weights.sum()
            val span = (to - from).coerceAtLeast(0)
            var cum = 0.0
            for ((offset, w) in (idx..runEnd).withIndex()) {
                val s = from + (cum / total * span).toLong()
                cum += weights[offset]
                val e = from + (cum / total * span).toLong()
                starts[w] = s
                ends[w] = max(e, s + 1)
            }
            idx = runEnd + 1
        }
    }

    private fun similarity(x: String, y: String): Int {
        if (x.isEmpty() || y.isEmpty()) return MISMATCH
        if (x == y) return MATCH
        val longer = max(x.length, y.length)
        if (longer >= 4 && (x.startsWith(y) || y.startsWith(x)) && min(x.length, y.length) >= 3) return NEAR
        val ratio = 1.0 - levenshtein(x, y).toDouble() / longer
        return if (ratio >= 0.6) NEAR else MISMATCH
    }

    private fun levenshtein(x: String, y: String): Int {
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.length]
    }
}
