package com.pianoscribe.core.score

import com.pianoscribe.core.analysis.Steps
import com.pianoscribe.core.model.Note
import kotlin.math.abs

/** One note on the guitar: [string] 1 = high E .. 6 = low E, [fret] 0 = open. */
data class TabNote(val noteId: Long, val pitch: Int, val string: Int, val fret: Int, val start: Double, val duration: Double)

/**
 * Fingers notes on a guitar in standard tuning (E2 A2 D3 G3 B3 E4, 20 frets): every chord gets one
 * note per string within a hand span of four frets (open strings are free), and a Viterbi pass
 * over the chords keeps the hand where it is when it can. Notes out of range move by octaves;
 * chords with more than six notes keep their bass and top notes.
 */
object Tablature {
    val TUNING = intArrayOf(64, 59, 55, 50, 45, 40) // strings 1..6
    const val FRETS = 20
    private val LOW = TUNING.last()
    private val HIGH = TUNING.first() + FRETS
    private const val SPAN = 4
    private const val KEEP = 24 // fingerings kept per chord

    private class Fingering(val strings: IntArray, val frets: IntArray, val cost: Double) {
        /** Where the hand is: the mean fretted position, or null for all open strings. */
        val position: Double? = frets.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
    }

    fun arrange(notes: List<Note>): List<TabNote> {
        val chords = ArrayList<List<Note>>()
        val options = ArrayList<List<Fingering>>()
        for (step in Steps.build(notes)) {
            var chord = playable(step.notes)
            var f = fingerings(chord.map { it.pitch })
            // Out of reach as a whole: leave out inner notes (keeping the top, then the bass) until it fits.
            while (f.isEmpty() && chord.size > 1) {
                chord = if (chord.size > 2) chord.filterIndexed { i, _ -> i != chord.size - 2 } else chord.drop(1)
                f = fingerings(chord.map { it.pitch })
            }
            if (f.isNotEmpty()) { chords += chord; options += f }
        }
        if (chords.isEmpty()) return emptyList()
        // Viterbi: fingering cost plus hand movement between chords.
        val score = options.map { DoubleArray(it.size) }
        val back = options.map { IntArray(it.size) }
        for (j in options[0].indices) score[0][j] = options[0][j].cost
        for (i in 1 until options.size) for (j in options[i].indices) {
            var best = Double.MAX_VALUE
            for (k in options[i - 1].indices) {
                val v = score[i - 1][k] + move(options[i - 1][k], options[i][j])
                if (v < best) { best = v; back[i][j] = k }
            }
            score[i][j] = best + options[i][j].cost
        }
        var j = score.last().indices.minBy { score.last()[it] }
        val pick = IntArray(options.size)
        for (i in options.size - 1 downTo 0) { pick[i] = j; j = back[i][j] }
        return chords.indices.flatMap { i ->
            val f = options[i][pick[i]]
            chords[i].mapIndexed { n, note -> TabNote(note.id, note.pitch, f.strings[n] + 1, f.frets[n], note.start, note.duration) }
        }
    }

    /** Moves notes into the guitar's range and keeps at most six (bass and top first). */
    private fun playable(chord: List<Note>): List<Note> {
        val inRange = chord.map { n ->
            var p = n.pitch
            while (p < LOW) p += 12
            while (p > HIGH) p -= 12
            n.copy(pitch = p)
        }.distinctBy { it.pitch }.sortedBy { it.pitch }
        if (inRange.size <= 6) return inRange
        return (listOf(inRange.first()) + inRange.drop(1).takeLast(5)).sortedBy { it.pitch }
    }

    /** All ways to play [pitches] (low to high) on distinct strings within the span, cheapest first. */
    private fun fingerings(pitches: List<Int>): List<Fingering> {
        val out = ArrayList<Fingering>()
        val strings = IntArray(pitches.size)
        val frets = IntArray(pitches.size)
        fun rec(i: Int, used: Int) {
            if (i == pitches.size) {
                val fretted = frets.filter { it > 0 }
                val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
                if (span > SPAN) return
                // Prefer low positions, a small stretch and open strings in low positions.
                val cost = span * 0.6 + (fretted.maxOrNull() ?: 0) * 0.08 + fretted.size * 0.05
                out += Fingering(strings.copyOf(), frets.copyOf(), cost)
                return
            }
            for (s in TUNING.indices) {
                if (used and (1 shl s) != 0) continue
                val fret = pitches[i] - TUNING[s]
                if (fret !in 0..FRETS) continue
                // Low notes on low strings: a higher pitch never goes on a lower string than the one below it.
                if (i > 0 && s >= strings[i - 1]) continue
                strings[i] = s; frets[i] = fret
                rec(i + 1, used or (1 shl s))
            }
        }
        rec(0, 0)
        return out.sortedBy { it.cost }.take(KEEP)
    }

    private fun move(a: Fingering, b: Fingering): Double {
        val pa = a.position ?: return 0.0
        val pb = b.position ?: return 0.0
        return abs(pa - pb) * 0.5
    }
}
