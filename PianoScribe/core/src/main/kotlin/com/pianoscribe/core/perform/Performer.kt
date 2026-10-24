package com.pianoscribe.core.perform

import com.pianoscribe.core.analysis.Steps
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A note as it is actually played.
 *
 * @param slur joined to the previous note without a new attack (wind and bowed legato)
 */
data class PlayedNote(
    val pitch: Int,
    val start: Double,
    val duration: Double,
    val velocity: Int,
    val hand: Hand = Hand.RIGHT,
    val slur: Boolean = false,
) {
    val end: Double get() = start + duration

    companion object {
        fun of(n: Note) = PlayedNote(n.pitch, n.start, n.duration, n.velocity, n.hand)
    }
}

/**
 * Turns the (edited) transcription into a part for one instrument, played the way a musician would.
 *
 * Arrangement (always): single-note instruments get the melody line only, and every note is moved
 * by octaves into the instrument's range. Human feel (scaled by [Options.feel], 0 = exact):
 * - dynamics: phrase arches, accents on strong beats, melody voiced above the accompaniment
 * - timing: small correlated drift, chords rolled low to high (wider for harp, strummed on guitar),
 *   the melody slightly ahead of the chord on keyboards, and a slow-down over the final bars
 * - articulation: keyboard legato, plucked notes left ringing, wind tonguing and breaths at
 *   phrase ends, wind and bowed slurs between close notes
 *
 * Deterministic for a given [Options.seed], so the same project always sounds the same.
 */
object Performer {
    data class Options(
        val feel: Float = 0.6f,
        val seed: Long = 1,
        /** Allow the final slow-down; off when the part must stay in time with the original audio. */
        val tempoChanges: Boolean = true,
    )

    fun perform(notes: List<Note>, grid: TimeGrid, instrument: Instrument, options: Options = Options()): List<PlayedNote> {
        if (notes.isEmpty()) return emptyList()
        val feel = options.feel.coerceIn(0f, 1f)
        var part = arrange(notes, instrument)
        if (feel <= 0f) return part
        val rnd = Random(options.seed)
        part = shapeDynamics(part, grid, instrument, feel, rnd)
        part = shapeTiming(part, instrument, feel, rnd)
        part = articulate(part, grid, instrument, feel)
        if (options.tempoChanges) part = slowDownAtEnd(part, grid, feel)
        return part.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    // ------------------------------------------------------------------ arrangement

    fun arrange(notes: List<Note>, instrument: Instrument): List<PlayedNote> {
        val sorted = notes.sortedWith(compareBy({ it.start }, { it.pitch }))
        val part = if (instrument.monophonic) melodyLine(sorted) else sorted.map(PlayedNote::of)
        val fitted = if (instrument.monophonic) moveLine(part, instrument) else part.map { it.copy(pitch = fold(it.pitch, instrument)) }
        // Folding can stack two notes on one key; keep the louder.
        val unique = fitted.groupBy { it.pitch to Math.round(it.start * 1000) }.values.map { g -> g.maxBy { it.velocity } }
        val limited = if (unique.size > 1 && instrument.maxNotes < 32) limitChordSize(unique, instrument.maxNotes) else unique
        return limited.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    /** Top note of each onset (from the right hand when it carries the tune), with no overlaps. */
    fun melodyLine(notes: List<Note>): List<PlayedNote> {
        val rh = notes.filter { it.hand == Hand.RIGHT }
        val source = if (rh.size >= notes.size / 4 && rh.isNotEmpty()) rh else notes
        val tops = Steps.build(source).map { it.top }
        return tops.mapIndexed { i, n ->
            val next = tops.getOrNull(i + 1)
            val dur = if (next == null) n.duration else minOf(n.duration, next.start - n.start - 0.005)
            PlayedNote(n.pitch, n.start, dur.coerceAtLeast(0.03), n.velocity, Hand.RIGHT)
        }
    }

    /** Shifts a melody by whole octaves so its median sits near the sweet spot, then folds stragglers. */
    private fun moveLine(line: List<PlayedNote>, instrument: Instrument): List<PlayedNote> {
        if (line.isEmpty()) return line
        val median = line.map { it.pitch }.sorted()[line.size / 2]
        val shift = 12 * Math.round((instrument.sweetSpot - median) / 12.0).toInt()
        return line.map { it.copy(pitch = fold(it.pitch + shift, instrument)) }
    }

    fun fold(pitch: Int, instrument: Instrument): Int {
        var p = pitch
        while (p < instrument.low) p += 12
        while (p > instrument.high) p -= 12
        return p
    }

    /** Keeps the bass and the highest notes when a chord has more notes than the instrument can play. */
    private fun limitChordSize(part: List<PlayedNote>, max: Int): List<PlayedNote> {
        val groups = ArrayList<MutableList<PlayedNote>>()
        for (n in part.sortedBy { it.start }) {
            if (groups.isNotEmpty() && n.start - groups.last().first().start <= Steps.CHORD_TOLERANCE) groups.last() += n else groups += mutableListOf(n)
        }
        return groups.flatMap { g ->
            if (g.size <= max) g else {
                val byPitch = g.sortedBy { it.pitch }
                listOf(byPitch.first()) + byPitch.takeLast(max - 1)
            }
        }
    }

    // ------------------------------------------------------------------ dynamics

    private fun shapeDynamics(part: List<PlayedNote>, grid: TimeGrid, instrument: Instrument, feel: Float, rnd: Random): List<PlayedNote> {
        val phrases = phraseBounds(part, grid)
        val groups = onsetGroups(part)
        val topOf = HashMap<PlayedNote, Boolean>()
        val bassOf = HashMap<PlayedNote, Boolean>()
        for (g in groups) {
            val rh = g.filter { it.hand == Hand.RIGHT }
            val lh = g.filter { it.hand == Hand.LEFT }
            rh.maxByOrNull { it.pitch }?.let { topOf[it] = true }
            lh.minByOrNull { it.pitch }?.let { bassOf[it] = true }
        }
        return part.map { n ->
            var delta = 0.0
            // Phrase arch: rise to the middle of the phrase and relax at its end.
            val ph = phrases.lastOrNull { it.first <= n.start + 1e-6 } ?: (n.start to n.end)
            val len = (ph.second - ph.first).coerceAtLeast(0.2)
            val pos = ((n.start - ph.first) / len).coerceIn(0.0, 1.0)
            delta += 10 * sin(PI * pos) - 4
            // Metric accents.
            val beats = grid.timeToBeats(n.start)
            val beat = Math.floorMod(beats.roundToInt(), grid.beatsPerBar)
            val onBeat = abs(beats - beats.roundToInt()) < 0.1
            delta += when {
                onBeat && beat == 0 -> 6.0
                onBeat && grid.beatsPerBar % 2 == 0 && beat == grid.beatsPerBar / 2 -> 3.0
                !onBeat && abs((beats - floor(beats)) - 0.5) < 0.1 -> -2.0
                else -> 0.0
            }
            // Voicing: melody on top, inner voices back, bass supportive.
            if (!instrument.monophonic) delta += when {
                topOf[n] == true -> 6.0
                bassOf[n] == true -> 2.0
                n.hand == Hand.RIGHT -> -5.0
                else -> -4.0
            }
            delta += gaussian(rnd) * 3
            n.copy(velocity = (n.velocity + feel * delta).roundToInt().coerceIn(12, 127))
        }
    }

    /** Phrase start/end times: a new phrase starts after a clear gap or every four bars. */
    fun phraseBounds(part: List<PlayedNote>, grid: TimeGrid): List<Pair<Double, Double>> {
        if (part.isEmpty()) return emptyList()
        // Latest end per onset time, in onset order.
        val endAt = java.util.TreeMap<Double, Double>()
        for (n in part) endAt.merge(n.start, n.end) { a, b -> maxOf(a, b) }
        val maxLen = grid.secondsPerBeat * grid.beatsPerBar * 4
        val gapLimit = maxOf(0.35, grid.secondsPerBeat * 0.75)
        val out = ArrayList<Pair<Double, Double>>()
        val first = endAt.firstEntry()
        var phraseStart = first.key
        var lastEnd = first.value
        for ((t, e) in endAt.tailMap(first.key, false)) {
            if (t - lastEnd > gapLimit || t - phraseStart > maxLen) {
                out += phraseStart to lastEnd
                phraseStart = t
            }
            lastEnd = maxOf(lastEnd, e)
        }
        out += phraseStart to lastEnd
        return out
    }

    // ------------------------------------------------------------------ timing

    private fun shapeTiming(part: List<PlayedNote>, instrument: Instrument, feel: Float, rnd: Random): List<PlayedNote> {
        val roll = when (instrument.family) {
            Family.PLUCKED -> if (instrument == Instrument.HARP) 0.022 else 0.012
            Family.KEYS -> if (instrument == Instrument.ORGAN) 0.0 else 0.004
            else -> 0.0
        }
        val lead = if (instrument == Instrument.PIANO || instrument == Instrument.ELECTRIC_PIANO) 0.008 else 0.0
        var drift = 0.0
        val out = ArrayList<PlayedNote>(part.size)
        for (g in onsetGroups(part)) {
            // Correlated drift: humans speed up and hold back a little, not at random per note.
            drift = (0.5 * drift + gaussian(rnd) * 0.007).coerceIn(-0.018, 0.018)
            val ordered = g.sortedBy { it.pitch }
            val top = ordered.last()
            for ((i, n) in ordered.withIndex()) {
                var shift = drift + i * roll
                if (n === top && ordered.size > 1 && n.hand == Hand.RIGHT) shift -= lead
                val s = (n.start + feel * shift).coerceAtLeast(0.0)
                out += n.copy(start = s, duration = (n.end - s).coerceAtLeast(0.03))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ articulation

    private fun articulate(part: List<PlayedNote>, grid: TimeGrid, instrument: Instrument, feel: Float): List<PlayedNote> = when (instrument.family) {
        Family.KEYS -> keyboardLegato(part, if (instrument == Instrument.ORGAN) -0.005 else 0.015 * feel)
        Family.PLUCKED -> part.map { it.copy(duration = it.duration + feel * minOf(it.duration * 0.6, 0.6)) }
        Family.WIND, Family.BOWED -> lineArticulation(part, grid, instrument, feel)
    }

    /** Connects notes in each hand that are almost touching, overlapping by [overlap] seconds. */
    private fun keyboardLegato(part: List<PlayedNote>, overlap: Double): List<PlayedNote> {
        val out = ArrayList<PlayedNote>(part.size)
        for ((_, hn) in part.groupBy { it.hand }) {
            val starts = hn.map { it.start }.distinct().sorted()
            for (n in hn) {
                val next = starts.firstOrNull { it > n.start + 0.03 } ?: run { out += n; continue }
                val gap = next - n.end
                out += if (gap in -0.02..0.08) n.copy(duration = (next + overlap - n.start).coerceAtLeast(0.03)) else n
            }
        }
        return out
    }

    /** Tonguing, breaths and slurs for a single melodic line. */
    private fun lineArticulation(line: List<PlayedNote>, grid: TimeGrid, instrument: Instrument, feel: Float): List<PlayedNote> {
        val sorted = line.sortedBy { it.start }
        val phraseStarts = phraseBounds(sorted, grid).map { it.first }.toSet()
        val wind = instrument.family == Family.WIND
        val out = ArrayList<PlayedNote>(sorted.size)
        var slurNext = false
        for ((i, n) in sorted.withIndex()) {
            val cur = if (slurNext) n.copy(slur = true) else n
            slurNext = false
            val next = sorted.getOrNull(i + 1)
            if (next == null) { out += cur; break }
            val gap = next.start - cur.end
            val interval = abs(next.pitch - cur.pitch)
            val phraseEnd = next.start in phraseStarts
            out += when {
                phraseEnd && wind -> {
                    // Breathe before the next phrase.
                    cur.copy(duration = (next.start - cur.start - 0.12 * feel).coerceIn(0.03, cur.duration))
                }
                gap < 0.12 && interval in 1..(if (wind) 7 else 12) && feel >= 0.25f -> {
                    // Slur: hold into the next note so the instrument changes pitch without a new attack.
                    slurNext = true
                    cur.copy(duration = next.start - cur.start + 0.02)
                }
                gap < 0.12 -> cur.copy(duration = (next.start - cur.start - (if (wind) 0.03 else 0.02)).coerceAtLeast(0.03))
                else -> cur
            }
        }
        return out
    }

    // ------------------------------------------------------------------ tempo

    /** Slows down by up to 15% (at full feel) over the last two bars, at most the final 4 s. */
    private fun slowDownAtEnd(part: List<PlayedNote>, grid: TimeGrid, feel: Float): List<PlayedNote> {
        val end = part.maxOf { it.end }
        val window = minOf(4.0, grid.secondsPerBeat * grid.beatsPerBar * 2)
        val t0 = end - window
        if (window <= 0 || t0 <= 0) return part
        val k = 0.15 * feel
        fun warp(t: Double) = if (t <= t0) t else t + k * (t - t0) * (t - t0) / (2 * window)
        return part.map {
            val s = warp(it.start)
            it.copy(start = s, duration = (warp(it.end) - s).coerceAtLeast(0.03))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun onsetGroups(part: List<PlayedNote>): List<List<PlayedNote>> {
        val groups = ArrayList<MutableList<PlayedNote>>()
        for (n in part.sortedBy { it.start }) {
            if (groups.isNotEmpty() && n.start - groups.last().first().start <= Steps.CHORD_TOLERANCE) groups.last() += n else groups += mutableListOf(n)
        }
        return groups
    }

    private fun gaussian(rnd: Random): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        val v = rnd.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }
}
