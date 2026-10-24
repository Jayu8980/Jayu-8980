package com.pianoscribe.core.midi

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlin.math.roundToInt

/**
 * Lays notes out into measures for notation (sheet view and MusicXML): quantises to 16th notes,
 * makes one voice per staff (chords share a stem), fills gaps with rests and splits values at bar
 * lines and into notatable durations with ties.
 */
object ScoreBuilder {
    /** Divisions per quarter note: 4 = 16th-note resolution. */
    const val DIVISIONS = 4

    data class Event(
        val startDiv: Int,
        val durDiv: Int,
        /** Empty = rest. */
        val pitches: List<Int>,
        val tieStart: Boolean = false,
        val tieStop: Boolean = false,
    ) {
        val isRest: Boolean get() = pitches.isEmpty()
        val type: String get() = typeOf(durDiv).first
        val dotted: Boolean get() = typeOf(durDiv).second
    }

    data class Measure(val index: Int, val treble: List<Event>, val bass: List<Event>)

    /** (MusicXML type name, dotted) for a notatable duration in divisions. */
    fun typeOf(d: Int): Pair<String, Boolean> = when (d) {
        16 -> "whole" to false
        24 -> "whole" to true
        12 -> "half" to true
        8 -> "half" to false
        6 -> "quarter" to true
        4 -> "quarter" to false
        3 -> "eighth" to true
        2 -> "eighth" to false
        1 -> "16th" to false
        else -> "quarter" to false
    }

    private val NOTATABLE = intArrayOf(24, 16, 12, 8, 6, 4, 3, 2, 1)

    /** Splits a duration into notatable pieces, largest first. */
    fun splitDuration(d: Int): List<Int> {
        val out = ArrayList<Int>()
        var rem = d
        while (rem > 0) {
            val v = NOTATABLE.first { it <= rem }
            out += v
            rem -= v
        }
        return out
    }

    fun build(notes: List<Note>, grid: TimeGrid): List<Measure> {
        val divPerBeat = DIVISIONS * 4 / grid.beatUnit
        val measureDiv = grid.beatsPerBar * divPerBeat
        fun toDiv(t: Double) = (grid.timeToBeats(t) * divPerBeat).roundToInt().coerceAtLeast(0)
        val treble = voice(notes.filter { it.hand == Hand.RIGHT }, ::toDiv)
        val bass = voice(notes.filter { it.hand == Hand.LEFT }, ::toDiv)
        val lastDiv = maxOf(treble.maxOfOrNull { it.startDiv + it.durDiv } ?: 0, bass.maxOfOrNull { it.startDiv + it.durDiv } ?: 0)
        val nMeasures = maxOf(1, (lastDiv + measureDiv - 1) / measureDiv)
        val tm = layout(treble, measureDiv, nMeasures)
        val bm = layout(bass, measureDiv, nMeasures)
        return (0 until nMeasures).map { Measure(it, tm[it], bm[it]) }
    }

    private class Chord(val start: Int, var end: Int, val pitches: MutableList<Int>)

    /** Collapses notes into a single voice of chords with no overlaps. */
    private fun voice(notes: List<Note>, toDiv: (Double) -> Int): List<Event> {
        val chords = ArrayList<Chord>()
        for (n in notes.sortedWith(compareBy({ it.start }, { it.pitch }))) {
            val s = toDiv(n.start)
            val e = maxOf(s + 1, toDiv(n.end))
            val last = chords.lastOrNull()
            if (last != null && last.start == s) {
                if (n.pitch !in last.pitches) last.pitches += n.pitch
                last.end = maxOf(last.end, e)
            } else {
                chords += Chord(s, e, mutableListOf(n.pitch))
            }
        }
        for (i in 0 until chords.size - 1) {
            if (chords[i].end > chords[i + 1].start) chords[i].end = chords[i + 1].start
        }
        return chords.filter { it.end > it.start }.map { Event(it.start, it.end - it.start, it.pitches.sorted()) }
    }

    private fun layout(events: List<Event>, measureDiv: Int, nMeasures: Int): List<List<Event>> {
        val measures = List(nMeasures) { ArrayList<Event>() }
        // Insert rests into gaps, then cut at barlines and split into notatable values.
        val full = ArrayList<Event>()
        var pos = 0
        for (e in events) {
            if (e.startDiv > pos) full += Event(pos, e.startDiv - pos, emptyList())
            full += e
            pos = e.startDiv + e.durDiv
        }
        val total = nMeasures * measureDiv
        if (pos < total) full += Event(pos, total - pos, emptyList())
        for (e in full) {
            var s = e.startDiv
            val end = e.startDiv + e.durDiv
            val pieces = ArrayList<Pair<Int, Int>>()
            while (s < end) {
                val m = s / measureDiv
                val mEnd = (m + 1) * measureDiv
                val segEnd = minOf(end, mEnd)
                // Whole-bar rests are written as one rest.
                if (e.isRest && s % measureDiv == 0 && segEnd - s == measureDiv) {
                    pieces += s to measureDiv
                } else {
                    var p = s
                    for (d in splitDuration(segEnd - s)) { pieces += p to d; p += d }
                }
                s = segEnd
            }
            for ((i, pc) in pieces.withIndex()) {
                val m = pc.first / measureDiv
                if (m >= nMeasures) continue
                measures[m] += Event(
                    pc.first, pc.second, e.pitches,
                    tieStart = !e.isRest && i < pieces.size - 1,
                    tieStop = !e.isRest && i > 0,
                )
            }
        }
        return measures
    }
}
