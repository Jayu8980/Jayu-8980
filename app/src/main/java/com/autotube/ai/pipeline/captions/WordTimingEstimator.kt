package com.autotube.ai.pipeline.captions

import com.autotube.ai.core.model.LongPair
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.pipeline.script.ContentText
import kotlin.math.roundToLong

/**
 * Estimates word timings when neither STT nor the TTS engine provided them: the speech part of the
 * file (minus lead/tail padding and detected pauses) is distributed over the words, weighted by
 * syllable (akshara) length, and pauses are kept between words rather than inside them.
 */
internal object WordTimingEstimator {

    private class Segment(val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    fun estimate(
        words: List<String>,
        durationMs: Long,
        pauses: List<LongPair> = emptyList(),
        leadMs: Long = 80,
        tailMs: Long = 150,
    ): List<WordTiming> {
        if (words.isEmpty() || durationMs <= 0) return emptyList()
        val usePadding = durationMs > (leadMs + tailMs) * 3
        val regionStart = if (usePadding) leadMs else 0L
        val regionEnd = if (usePadding) durationMs - tailMs else durationMs
        val segments = speechSegments(regionStart, regionEnd, pauses)
        val speechTotal = segments.sumOf { it.length }.coerceAtLeast(1)

        // Weights: the word itself, plus a short trailing silence at punctuation when no pauses were detected.
        val usePunctuationGaps = pauses.isEmpty()
        val wordWeights = words.map { ContentText.syllableWeight(it).toDouble() + 0.25 }
        val gapWeights = words.mapIndexed { i, w ->
            when {
                !usePunctuationGaps || i == words.size - 1 -> 0.0
                ContentText.endsSentence(w) -> 0.9
                ContentText.endsClause(w) -> 0.4
                else -> 0.0
            }
        }
        val totalWeight = wordWeights.sum() + gapWeights.sum()

        var cum = 0.0
        val raw = words.mapIndexed { i, w ->
            val s = cum / totalWeight * speechTotal
            cum += wordWeights[i]
            val e = cum / totalWeight * speechTotal
            cum += gapWeights[i]
            Triple(w, toAbsolute(s, segments, false), toAbsolute(e, segments, true))
        }

        // A word must not straddle a pause: move it to the side that holds most of it.
        val fixed = raw.map { (w, s0, e0) ->
            var s = s0
            var e = e0
            for (p in pauses) {
                if (s < p.first && e > p.second) {
                    if (p.first - s >= e - p.second) e = p.first else s = p.second
                }
            }
            WordTiming(w, s, e.coerceAtLeast(s + 1))
        }
        return monotonic(fixed, durationMs)
    }

    /** Ensures start/end order, no overlaps and bounds inside [0, durationMs]. */
    fun monotonic(words: List<WordTiming>, durationMs: Long): List<WordTiming> {
        val out = ArrayList<WordTiming>(words.size)
        var prevEnd = 0L
        for ((i, w) in words.withIndex()) {
            val start = w.startMs.coerceIn(prevEnd, durationMs.coerceAtLeast(prevEnd))
            val nextStart = words.getOrNull(i + 1)?.startMs ?: durationMs
            val end = w.endMs.coerceAtMost(nextStart.coerceAtLeast(start + 1)).coerceAtMost(durationMs).coerceAtLeast(start)
            out += WordTiming(w.word, start, end)
            prevEnd = end
        }
        return out
    }

    private fun speechSegments(start: Long, end: Long, pauses: List<LongPair>): List<Segment> {
        if (end <= start) return listOf(Segment(0, end.coerceAtLeast(1)))
        val result = mutableListOf<Segment>()
        var cursor = start
        for (p in pauses.sortedBy { it.first }) {
            val ps = p.first.coerceIn(start, end)
            val pe = p.second.coerceIn(start, end)
            if (pe <= ps) continue
            if (ps > cursor) result += Segment(cursor, ps)
            cursor = maxOf(cursor, pe)
        }
        if (end > cursor) result += Segment(cursor, end)
        return result.ifEmpty { listOf(Segment(start, end)) }
    }

    private fun toAbsolute(pos: Double, segments: List<Segment>, isEnd: Boolean): Long {
        var remaining = pos
        for ((i, seg) in segments.withIndex()) {
            val len = seg.length.toDouble()
            val last = i == segments.size - 1
            // Positions exactly on a boundary: ends stay in the earlier segment, starts move to the next.
            if (remaining < len || (isEnd && remaining <= len) || last) {
                return (seg.start + remaining.coerceAtMost(len)).roundToLong()
            }
            remaining -= len
        }
        return segments.last().end
    }
}
