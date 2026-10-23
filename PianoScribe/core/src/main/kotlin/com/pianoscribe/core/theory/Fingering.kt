package com.pianoscribe.core.theory

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import kotlin.math.abs

/**
 * Suggests piano fingers (1 = thumb .. 5 = little finger) with a small dynamic program over each
 * hand's melodic line. Chord notes get fingers by position. This is a practice aid, not an
 * engraver-grade fingering.
 */
object Fingering {
    /** Returns note id -> finger. */
    fun suggest(notes: List<Note>, chordToleranceSec: Double = 0.05): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (hand in Hand.entries) {
            val hn = notes.filter { it.hand == hand }.sortedWith(compareBy({ it.start }, { it.pitch }))
            val groups = group(hn, chordToleranceSec)
            // Melody line: the top note for RH, the bottom note for LH.
            val line = groups.map { g -> if (hand == Hand.RIGHT) g.maxBy { it.pitch } else g.minBy { it.pitch } }
            val fingers = lineFingers(line.map { it.pitch }, hand)
            for ((gi, g) in groups.withIndex()) {
                val lead = line[gi]
                out[lead.id] = fingers[gi]
                if (g.size > 1) assignChord(g, hand, out)
            }
        }
        return out
    }

    private fun group(notes: List<Note>, tol: Double): List<List<Note>> {
        val groups = ArrayList<MutableList<Note>>()
        for (n in notes) {
            if (groups.isNotEmpty() && n.start - groups.last().first().start <= tol) groups.last() += n
            else groups += mutableListOf(n)
        }
        return groups
    }

    private fun assignChord(g: List<Note>, hand: Hand, out: MutableMap<Long, Int>) {
        val sorted = g.sortedBy { it.pitch }
        val n = sorted.size
        for ((i, note) in sorted.withIndex()) {
            // Spread fingers across the chord: RH low->high = 1..5, LH low->high = 5..1.
            val f = run {
                val pos = 1 + Math.round(i * 4.0 / (n - 1)).toInt()
                if (hand == Hand.RIGHT) pos else 6 - pos
            }
            out[note.id] = f.coerceIn(1, 5)
        }
    }

    /** Viterbi over 5 fingers. */
    fun lineFingers(pitches: List<Int>, hand: Hand): IntArray {
        val n = pitches.size
        if (n == 0) return IntArray(0)
        val inf = Double.MAX_VALUE / 4
        val cost = Array(n) { DoubleArray(5) { inf } }
        val back = Array(n) { IntArray(5) }
        for (f in 0 until 5) cost[0][f] = startCost(pitches[0], f + 1)
        for (i in 1 until n) {
            for (f2 in 0 until 5) {
                for (f1 in 0 until 5) {
                    val c = cost[i - 1][f1] + transition(pitches[i - 1], pitches[i], f1 + 1, f2 + 1, hand)
                    if (c < cost[i][f2]) { cost[i][f2] = c; back[i][f2] = f1 }
                }
            }
        }
        val res = IntArray(n)
        var f = (0 until 5).minBy { cost[n - 1][it] }
        for (i in n - 1 downTo 0) {
            res[i] = f + 1
            f = back[i][f]
        }
        return res
    }

    private fun startCost(p: Int, f: Int): Double = (if (Pitch.isBlackKey(p) && f == 1) 2.0 else 0.0) + (if (f == 1 || f == 3) 0.0 else 0.3)

    private fun transition(p1: Int, p2: Int, f1: Int, f2: Int, hand: Hand): Double {
        // Positive interval = moving towards the little finger side (up for RH, down for LH).
        val d = if (hand == Hand.RIGHT) p2 - p1 else p1 - p2
        var c = 0.0
        if (d == 0) return if (f1 == f2) 0.0 else 1.0
        val fd = f2 - f1
        if (fd != 0 && (d > 0) == (fd > 0)) {
            // Normal hand position: roughly 2 semitones per finger, comfortable stretch up to ~1.5x.
            val natural = abs(fd) * 2.0
            val span = abs(d).toDouble()
            c += when {
                span <= natural + 1 -> abs(span - natural) * 0.3
                else -> (span - natural - 1) * 1.5
            }
        } else if (fd == 0) {
            c += 4.0 + abs(d) * 0.5
        } else {
            // Crossing: thumb under (after moving outward to thumb) or finger over the thumb.
            val thumbUnder = f2 == 1 && d > 0 && f1 in 2..4
            val fingerOver = f1 == 1 && d < 0 && f2 in 2..4
            c += if (thumbUnder || fingerOver) 1.5 + abs(d) * 0.3 else 8.0 + abs(d)
        }
        if (Pitch.isBlackKey(p2) && f2 == 1) c += 2.5
        if (Pitch.isBlackKey(p2) && f2 == 5) c += 0.8
        if (abs(d) > 12) c += 1.0
        return c
    }
}

/** Splits notes into right and left hand at [splitPitch] (notes at or above go to the RH). */
object HandSplitter {
    fun split(notes: List<Note>, splitPitch: Int = Note.MIDDLE_C): List<Note> =
        notes.map { it.copy(hand = if (it.pitch >= splitPitch) Hand.RIGHT else Hand.LEFT) }
}
