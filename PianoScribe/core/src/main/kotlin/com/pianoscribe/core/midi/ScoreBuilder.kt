package com.pianoscribe.core.midi

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lays notes out into measures for notation (score view, PDF and MusicXML): quantises to 16th
 * notes, makes one voice per staff (chords share a stem), fills gaps with rests and splits values
 * at bar lines and beats into notatable durations with ties, the way an engraver writes them.
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
        /** The transcribed notes this event shows (for selecting a note in the score). */
        val noteIds: List<Long> = emptyList(),
        /** Loudest note, 1..127 (0 for rests). */
        val velocity: Int = 0,
        /** Played short and detached: written at full value with a staccato dot. */
        val staccato: Boolean = false,
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

    /** Divisions per beat group: a beat, or a dotted quarter in compound meters (6/8, 9/8, 12/8). */
    fun groupDiv(grid: TimeGrid): Int {
        val beatDiv = DIVISIONS * 4 / grid.beatUnit
        return if (grid.beatUnit == 8 && grid.beatsPerBar % 3 == 0 && grid.beatsPerBar > 3) beatDiv * 3 else beatDiv
    }

    /**
     * Splits [start, end) inside one measure into notatable values that show the beat: values
     * shorter than a beat group stay inside their group, longer ones start on a group, and in
     * 4/4 nothing crosses the middle of the bar unless it starts on the downbeat.
     */
    internal fun metricSplit(start: Int, end: Int, measureDiv: Int, group: Int, beatsPerBar: Int): List<Pair<Int, Int>> {
        val half = if (beatsPerBar == 4) measureDiv / 2 else 0
        fun ok(s: Int, d: Int): Boolean {
            val e = s + d
            val inBar = s % measureDiv
            if (d < group) return inBar / group == (inBar + d - 1) / group
            if (inBar % group != 0) return false
            if (half > 0 && inBar != 0 && inBar < half && inBar + d > half) return false
            return e - (s - inBar) <= measureDiv
        }
        val out = ArrayList<Pair<Int, Int>>()
        var s = start
        while (s < end) {
            val d = NOTATABLE.first { it <= end - s && ok(s, it) }
            out += s to d
            s += d
        }
        return out
    }

    fun build(notes: List<Note>, grid: TimeGrid): List<Measure> {
        val divPerBeat = DIVISIONS * 4 / grid.beatUnit
        val measureDiv = grid.beatsPerBar * divPerBeat
        fun toDiv(t: Double) = (grid.timeToBeats(t) * divPerBeat).roundToInt().coerceAtLeast(0)
        val secPerDiv = grid.secondsPerBeat / divPerBeat
        // Onsets on the simplest grid each beat allows (both staves share it, so they line up).
        val snapped = snapOnsets(notes.map { grid.timeToBeats(it.start) }, divPerBeat)
        val startDiv = notes.indices.associate { notes[it].id to snapped[it] }
        fun startOf(n: Note) = startDiv[n.id] ?: toDiv(n.start)
        val treble = voice(notes.filter { it.hand == Hand.RIGHT }, ::startOf, ::toDiv, secPerDiv)
        val bass = voice(notes.filter { it.hand == Hand.LEFT }, ::startOf, ::toDiv, secPerDiv)
        val lastDiv = maxOf(treble.maxOfOrNull { it.startDiv + it.durDiv } ?: 0, bass.maxOfOrNull { it.startDiv + it.durDiv } ?: 0)
        val nMeasures = maxOf(1, (lastDiv + measureDiv - 1) / measureDiv)
        val group = groupDiv(grid)
        val tm = layout(treble, measureDiv, nMeasures, group, grid.beatsPerBar)
        val bm = layout(bass, measureDiv, nMeasures, group, grid.beatsPerBar)
        return (0 until nMeasures).map { Measure(it, tm[it], bm[it]) }
    }

    /** Allowed onset error per grid, in beats: quarters, then eighths; 16ths always fit. */
    private val SNAP = listOf(1 to 0.14, 2 to 0.11, 4 to 1.0)

    /**
     * Snaps onsets (in beats) to divisions, choosing per beat the coarsest grid (quarter, eighth,
     * 16th) that fits every onset in it within tolerance and keeps distinct onsets apart, the way
     * a person writes a slightly uneven performance: as the simple rhythm it is meant to be.
     */
    internal fun snapOnsets(beats: List<Double>, divPerBeat: Int): IntArray {
        val out = IntArray(beats.size)
        // An onset a little before a beat belongs to that beat.
        val byBeat = beats.indices.groupBy { Math.floor(beats[it] + 0.125).toInt() }
        for ((b, idx) in byBeat) {
            // Onsets closer than this are one chord, not two rhythm positions.
            val clusters = idx.map { beats[it] }.sorted().fold(ArrayList<Double>()) { acc, t -> if (acc.isEmpty() || t - acc.last() > 0.09) acc += t; acc }
            // 16ths are the finest grid, so they are taken whatever they merge.
            val (sub, _) = SNAP.dropLast(1).firstOrNull { (s, tol) ->
                val grid = clusters.map { Math.round(it * s) }
                clusters.all { abs(it - Math.round(it * s).toDouble() / s) <= tol } && grid.distinct().size == grid.size
            } ?: SNAP.last()
            for (i in idx) out[i] = (Math.round(beats[i] * sub).toDouble() / sub * divPerBeat).roundToInt().coerceAtLeast(0)
            if (b < 0) for (i in idx) out[i] = out[i].coerceAtLeast(0)
        }
        return out
    }

    private class Chord(val start: Int, var end: Int, val pitches: MutableList<Int>, val ids: MutableList<Long>, var velocity: Int, var playedSec: Double)

    /** Collapses notes into a single voice of chords with no overlaps. */
    private fun voice(notes: List<Note>, startOf: (Note) -> Int, toDiv: (Double) -> Int, secPerDiv: Double): List<Event> {
        val chords = ArrayList<Chord>()
        for (n in notes.sortedWith(compareBy({ startOf(it) }, { it.pitch }))) {
            val s = startOf(n)
            val e = maxOf(s + 1, toDiv(n.end))
            val last = chords.lastOrNull()
            if (last != null && last.start == s) {
                if (n.pitch !in last.pitches) { last.pitches += n.pitch; last.ids += n.id }
                last.end = maxOf(last.end, e)
                last.velocity = maxOf(last.velocity, n.velocity)
                last.playedSec = maxOf(last.playedSec, n.duration)
            } else {
                chords += Chord(s, e, mutableListOf(n.pitch), mutableListOf(n.id), n.velocity, n.duration)
            }
        }
        val staccato = BooleanArray(chords.size)
        for (i in 0 until chords.size - 1) {
            val c = chords[i]
            val slot = chords[i + 1].start - c.start
            // Short and detached: written at full value with a dot, not as a short note and rests.
            if (slot in 1..DIVISIONS && c.playedSec < 0.55 * slot * secPerDiv && c.playedSec < 0.3) {
                staccato[i] = true
                c.end = chords[i + 1].start
            }
            // A gap of a 16th, or up to a quarter of the time to the next chord, is a lift, not a rest worth writing.
            val gap = chords[i + 1].start - c.end
            if (gap in 1..maxOf(1, slot / 4)) c.end = chords[i + 1].start
            if (c.end > chords[i + 1].start) c.end = chords[i + 1].start
        }
        return chords.indices.filter { chords[it].end > chords[it].start }.map { i ->
            val c = chords[i]
            val order = c.pitches.indices.sortedBy { c.pitches[it] }
            Event(c.start, c.end - c.start, order.map { c.pitches[it] }, noteIds = order.map { c.ids[it] }, velocity = c.velocity, staccato = staccato[i])
        }
    }

    private fun layout(events: List<Event>, measureDiv: Int, nMeasures: Int, group: Int, beatsPerBar: Int): List<List<Event>> {
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
                if (e.isRest && s % measureDiv == 0 && segEnd - s == measureDiv) pieces += s to measureDiv
                else pieces += metricSplit(s, segEnd, measureDiv, group, beatsPerBar)
                s = segEnd
            }
            for ((i, pc) in pieces.withIndex()) {
                val m = pc.first / measureDiv
                if (m >= nMeasures) continue
                measures[m] += e.copy(
                    startDiv = pc.first, durDiv = pc.second,
                    tieStart = !e.isRest && i < pieces.size - 1,
                    tieStop = !e.isRest && i > 0,
                    // Marks belong to the attack, not to the tied continuation.
                    staccato = e.staccato && i == 0,
                )
            }
        }
        return measures
    }
}
