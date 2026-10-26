package com.pianoscribe.core.perform

import com.pianoscribe.core.analysis.Steps
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlin.math.abs
import kotlin.math.floor

/**
 * An easy version for learning: the tune alone, one note at a time, for the right hand.
 *
 * 1. **The tune**: the top note of each moment (from the right hand when it carries the tune),
 *    skipping a top note much quieter than the others there (usually an overtone), notes the
 *    transcription is unsure of, and the accompaniment: a note starting under a tune note that is
 *    still ringing, or a chord well below where the tune has just been (while the tune rests).
 * 2. **Fast runs simplified**: at most one note per [Options.minGapBeats] of the beat (and never
 *    closer than [Options.minGapSec]); of notes that close, the one nearest the beat wins, then the
 *    louder one, so the shape of the tune stays and the passing notes go.
 * 3. **One note at a time**: no overlaps; very short notes are held a little longer.
 * 4. **A comfortable range**: [Options.low]..[Options.high]. The whole line moves by the octaves
 *    that fit the most notes in (as little as possible, then nearest [Options.sweetSpot]), and the
 *    odd note still outside folds in by octaves.
 *
 * Notes keep their ids, so the analyser, fingering and practice mode work on the easy version.
 */
object EasyVersion {
    data class Options(
        /** Shortest distance between two notes, in beats (0.5 = an eighth note in 4/4). */
        val minGapBeats: Double = 0.5,
        /** ... and in seconds, for fast tempos. */
        val minGapSec: Double = 0.2,
        val low: Int = 60,
        val high: Int = 84,
        val sweetSpot: Int = 67,
    )

    /** A top note quieter than this share of the loudest note of its moment is not taken as the tune. */
    private const val QUIET_TOP = 0.6
    /** Notes the transcription is unsure of are left out (unless most notes are, as in a quiet recording). */
    private const val MIN_CONFIDENCE = 0.45f
    /** A tune note still ringing this long after a new onset is held over it. */
    private const val HELD_SEC = 0.1
    /** A chord whose top is this many semitones below the recent tune is accompaniment. */
    private const val BELOW_TUNE = 4

    fun make(notes: List<Note>, grid: TimeGrid, options: Options = Options()): List<Note> {
        if (notes.isEmpty()) return emptyList()
        val line = thin(tune(notes), grid, options)
        return fitRange(sustain(line, options), options)
    }

    /**
     * Step 1: the top note of each moment, except where it is accompaniment: a note starting
     * below a tune note that is still ringing, or a chord (3+ notes) well below where the tune
     * has recently been (the accompaniment going on while the tune rests).
     */
    internal fun tune(notes: List<Note>): List<Note> {
        val rh = notes.filter { it.hand == Hand.RIGHT }
        val fromRh = rh.size >= notes.size / 4 && rh.isNotEmpty()
        val minConf = if (notes.count { it.confidence >= MIN_CONFIDENCE } * 2 >= notes.size) MIN_CONFIDENCE else 0f
        val chosen = ArrayList<Note>()
        for (step in Steps.build(notes)) {
            val candidates = step.notes.filter { (!fromRh || it.hand == Hand.RIGHT) && it.confidence >= minConf }
            if (candidates.isEmpty()) continue
            val loudest = candidates.maxOf { it.velocity }
            val top = candidates.filter { it.velocity >= QUIET_TOP * loudest }.maxBy { it.pitch }
            val held = chosen.lastOrNull()?.takeIf { it.end > step.start + HELD_SEC }
            if (held != null && top.pitch < held.pitch) continue
            val recent = chosen.takeLast(4).map { it.pitch }.sorted()
            if (recent.isNotEmpty() && step.notes.size >= 3 && top.pitch < recent[recent.size / 2] - BELOW_TUNE) continue
            chosen += top
        }
        return chosen
    }

    /** Step 2: drop notes closer together than the minimum gap, keeping the more important one. */
    internal fun thin(line: List<Note>, grid: TimeGrid, options: Options): List<Note> {
        val gap = maxOf(options.minGapSec, options.minGapBeats * grid.secondsPerBeat)
        val kept = ArrayList<Note>()
        for (n in line) {
            val last = kept.lastOrNull()
            if (last == null || n.start - last.start >= gap - 1e-9) { kept += n; continue }
            // Too close to the last kept note: keep whichever matters more, if the swap still
            // leaves room after the note before it.
            val before = kept.getOrNull(kept.size - 2)
            val fits = before == null || n.start - before.start >= gap - 1e-9
            if (fits && importance(n, grid) > importance(last, grid)) kept[kept.size - 1] = n
        }
        return kept
    }

    /** A stronger beat position counts most, then loudness, then length. */
    private fun importance(n: Note, grid: TimeGrid): Double =
        metricWeight(grid.timeToBeats(n.start)) + 0.5 * n.velocity / 127.0 + 0.2 * minOf(n.duration / grid.secondsPerBeat, 1.0)

    /** 1 on the beat, 0.6 on the half beat, 0.3 on a quarter beat, 0 elsewhere. */
    internal fun metricWeight(beats: Double): Double {
        val f = beats - floor(beats)
        fun near(x: Double) = abs(f - x) <= 0.06
        return when {
            near(0.0) || near(1.0) -> 1.0
            near(0.5) -> 0.6
            near(0.25) || near(0.75) -> 0.3
            else -> 0.0
        }
    }

    /** Step 3: no overlaps, short notes held a little longer (up to the next note). */
    internal fun sustain(line: List<Note>, options: Options): List<Note> = line.mapIndexed { i, n ->
        val next = line.getOrNull(i + 1)
        val wanted = maxOf(n.duration, options.minGapSec * 0.8)
        val dur = if (next == null) wanted else minOf(wanted, next.start - n.start - 0.01)
        n.copy(duration = dur.coerceAtLeast(0.05), hand = Hand.RIGHT, velocity = n.velocity.coerceAtLeast(50))
    }

    /**
     * Step 4: shift the whole line by the octaves that put the most notes in range (moving as
     * little as possible, then nearest the sweet spot), then fold stragglers in.
     */
    internal fun fitRange(line: List<Note>, options: Options): List<Note> {
        if (line.isEmpty()) return line
        val median = line.map { it.pitch }.sorted()[line.size / 2]
        val shift = (-6..6).map { it * 12 }.sortedWith(
            compareByDescending<Int> { s -> line.count { it.pitch + s in options.low..options.high } }
                .thenBy { abs(it) }
                .thenBy { abs(median + it - options.sweetSpot) },
        ).first()
        return line.map { n ->
            var p = n.pitch + shift
            while (p < options.low) p += 12
            while (p > options.high) p -= 12
            n.copy(pitch = p)
        }
    }
}
