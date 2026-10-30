package com.pianoscribe.core.arrange

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.EasyVersion
import com.pianoscribe.core.theory.KeyDetector
import kotlin.math.floor

/**
 * A piano cover of a transcription: the tune in the right hand, as played, and a left-hand
 * accompaniment in one of the usual patterns on the chords of each bar.
 *
 * Each bar's chord comes from the transcription itself (the major, minor or diminished triad whose
 * notes sound longest and loudest in the bar, the bass note counting extra), so the cover follows
 * the song's own harmony; bars with no accompaniment (a tune alone) get chords that fit the tune
 * ([Arranger]'s harmonisation).
 */
object PianoCover {
    enum class Pattern(val label: String, val help: String) {
        BLOCK("Block chords", "The chord on the strong beats."),
        BROKEN("Broken chords", "Root, fifth, octave and tenth in flowing eighths."),
        ALBERTI("Alberti bass", "Root, fifth, third, fifth in eighths, as in Mozart and Clementi."),
        OOM_PAH("Bass and chords", "Bass note then chords: a waltz in 3/4, a march in 4/4."),
    }

    private data class Triad(val rootPc: Int, val third: Int, val fifth: Int) {
        val pcs get() = listOf(rootPc, (rootPc + third) % 12, (rootPc + fifth) % 12)
    }

    private val TRIADS = (0 until 12).flatMap { r -> listOf(Triad(r, 4, 7), Triad(r, 3, 7), Triad(r, 3, 6)) }

    /** The triad of the notes sounding in [t0, t1), or null when there is too little to go on. */
    private fun chordOf(notes: List<Note>, t0: Double, t1: Double): Triad? {
        val w = DoubleArray(12)
        var total = 0.0
        val bass = notes.filter { it.start < t1 && it.end > t0 }.minByOrNull { it.pitch }
        for (n in notes) {
            val overlap = minOf(n.end, t1) - maxOf(n.start, t0)
            if (overlap <= 0) continue
            val x = overlap * (0.5 + n.velocity / 127.0)
            w[Math.floorMod(n.pitch, 12)] += x; total += x
        }
        if (total <= 0 || w.count { it > 0 } < 2) return null
        return TRIADS.maxBy { t ->
            val inChord = t.pcs.sumOf { w[it] }
            var s = inChord - 0.6 * (total - inChord)
            if (bass != null && Math.floorMod(bass.pitch, 12) == t.rootPc) s += 0.25 * total
            if (t.fifth == 6) s -= 0.15 * total // diminished only when clearly there
            s
        }
    }

    fun make(notes: List<Note>, grid: TimeGrid, key: KeyDetector.Key?, pattern: Pattern, ids: NoteIdGenerator = NoteIdGenerator()): List<Note> {
        if (notes.isEmpty()) return emptyList()
        // The tune, an octave up if needed so it sits clear of the accompaniment.
        var tune = EasyVersion.singleLine(notes)
        val median = tune.map { it.pitch }.sorted()[tune.size / 2]
        val shift = when { median < 60 -> 12 * ((67 - median + 11) / 12); median > 88 -> -12; else -> 0 }
        tune = tune.map { it.copy(pitch = (it.pitch + shift).coerceIn(21, 108), hand = Hand.RIGHT, velocity = it.velocity.coerceAtLeast(70)) }
        val p = grid.secondsPerBeat
        val beats = grid.beatsPerBar
        val barLen = p * beats
        val first = floor((notes.minOf { it.start } - grid.offset) / barLen + 1e-6).toInt()
        val last = floor((notes.maxOf { it.end } - grid.offset) / barLen - 1e-6).toInt()
        // Chords for bars without accompaniment: harmonised from the tune.
        val fitted by lazy { Arranger.arrange(tune, grid, key, Style.BALLAD).bars.associate { Math.round((it.start - grid.offset) / barLen).toInt() to it.chord } }
        val out = ArrayList<Note>()
        for (t in tune) out += t.copy(id = ids.next())
        val tuneIds = tune.map { it.id }.toHashSet()
        val accompaniment = notes.filter { it.id !in tuneIds }
        var prev: Triad? = null
        for (bar in first..maxOf(first, last)) {
            val t0 = grid.offset + bar * barLen
            val triad = chordOf(accompaniment, t0, t0 + barLen)
                ?: fitted[bar]?.let { c -> Triad(c.rootPc, if (c.minor || c.diminished) 3 else 4, if (c.diminished) 6 else 7) }
                ?: prev ?: continue
            prev = triad
            val root = 36 + Math.floorMod(triad.rootPc - 36, 12) // C2..B2
            val third = root + 12 + triad.third; val fifth = root + triad.fifth
            fun add(pitch: Int, beat: Double, lenBeats: Double, vel: Int = 62) {
                val start = t0 + beat * p
                if (start >= t0 + barLen - 1e-6) return
                out += Note(ids.next(), pitch, start, minOf(lenBeats * p, t0 + barLen - start) * 0.95, vel, 1f, Hand.LEFT)
            }
            when (pattern) {
                Pattern.BLOCK -> {
                    val hits = if (beats % 2 == 0) (0 until beats step 2).toList() else listOf(0)
                    val len = if (beats % 2 == 0) 2.0 else beats.toDouble()
                    for (b in hits) { add(root, b.toDouble(), len, 70); add(fifth + 12, b.toDouble(), len); add(third, b.toDouble(), len) }
                }
                Pattern.BROKEN -> {
                    val cycle = listOf(root, fifth, root + 12, third + 12, root + 12, fifth)
                    for (i in 0 until beats * 2) add(cycle[i % cycle.size], i * 0.5, if (i == 0) beats.toDouble() else 0.5, if (i == 0) 70 else 58)
                }
                Pattern.ALBERTI -> {
                    val lowRoot = root + 12; val a = listOf(lowRoot, lowRoot + triad.fifth, lowRoot + triad.third, lowRoot + triad.fifth)
                    for (i in 0 until beats * 2) add(a[i % 4], i * 0.5, 0.5, if (i % 4 == 0) 64 else 56)
                }
                Pattern.OOM_PAH -> {
                    for (b in 0 until beats) {
                        if (b == 0) add(root, 0.0, 1.0, 72)
                        else if (beats == 4 && b == 2) add(fifth, 2.0, 1.0, 68)
                        else { add(root + 12 + triad.third, b.toDouble(), 0.9); add(root + 12 + triad.fifth, b.toDouble(), 0.9) }
                    }
                }
            }
        }
        return out.sortedWith(compareBy({ it.start }, { it.pitch }))
    }
}
