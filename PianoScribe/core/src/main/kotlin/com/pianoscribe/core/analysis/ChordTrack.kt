package com.pianoscribe.core.analysis

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.ChordNamer
import kotlin.math.ceil
import kotlin.math.floor

/** Chord names over time, for labelling a view: where each new chord begins. */
object ChordTrack {
    data class Label(val time: Double, val name: String, val chord: ChordNamer.Chord)

    /** No transcribed note rings longer than this. */
    private const val MAX_NOTE_SEC = 30.0

    /**
     * Names the chord sounding in each beat (notes overlapping the beat, three or more different
     * notes; a bare interval is not named) and keeps only the changes.
     */
    fun labels(notes: List<Note>, grid: TimeGrid, preferFlats: Boolean = false): List<Label> {
        if (notes.isEmpty()) return emptyList()
        val first = floor(grid.timeToBeats(notes.minOf { it.start })).toInt()
        val last = ceil(grid.timeToBeats(notes.maxOf { it.end })).toInt()
        val sorted = notes.sortedBy { it.start }
        val out = ArrayList<Label>()
        var i0 = 0
        for (b in first until last) {
            val t0 = grid.beatsToTime(b.toDouble())
            val t1 = grid.beatsToTime(b + 1.0)
            // Notes are sorted by start; anything starting more than [MAX_NOTE_SEC] earlier is over.
            while (i0 < sorted.size && sorted[i0].start < t0 - MAX_NOTE_SEC) i0++
            // Sounding for a good part of the beat, so passing notes don't rename the chord.
            val pitches = sorted.subList(i0, sorted.size).asSequence().takeWhile { it.start < t1 }
                .filter { minOf(it.end, t1) - maxOf(it.start, t0) >= (t1 - t0) * 0.4 }.map { it.pitch }.toList()
            if (pitches.map { Math.floorMod(it, 12) }.distinct().size < 3) continue
            val chord = ChordNamer.name(pitches, preferFlats) ?: continue
            // Intervals and clusters are not chord symbols.
            if (chord.intervals.isEmpty()) continue
            if (out.lastOrNull()?.name != chord.name) out += Label(t0, chord.name, chord)
        }
        return out
    }
}
