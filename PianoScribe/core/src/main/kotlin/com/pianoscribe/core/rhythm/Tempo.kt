package com.pianoscribe.core.rhythm

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Tempo and beat-phase estimation from note onsets. */
object TempoDetector {
    private const val RATE = 100.0 // envelope frames per second

    /** Returns the detected grid; [beatsPerBar] is kept as given (4/4 by default). */
    fun detect(notes: List<Note>, beatsPerBar: Int = 4, minBpm: Double = 50.0, maxBpm: Double = 200.0): TimeGrid {
        if (notes.size < 4) return TimeGrid(120.0, beatsPerBar)
        val end = notes.maxOf { it.start } + 1.0
        val len = (end * RATE).toInt() + 1
        val env = DoubleArray(len)
        for (n in notes) {
            val i = (n.start * RATE).roundToInt().coerceIn(0, len - 1)
            val w = 0.3 + n.velocity / 127.0
            env[i] += w
            if (i + 1 < len) env[i + 1] += w * 0.5
            if (i > 0) env[i - 1] += w * 0.5
        }
        val mean = env.average()
        for (i in env.indices) env[i] -= mean

        val minLag = (60.0 / maxBpm * RATE).toInt()
        val maxLag = (60.0 / minBpm * RATE).toInt().coerceAtMost(len - 1)
        if (maxLag <= minLag) return TimeGrid(120.0, beatsPerBar)
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            if (lag >= len) break
            var s = 0.0
            for (i in 0 until len - lag) s += env[i] * env[i + lag]
            ac[lag] = s / (len - lag)
        }
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * RATE / lag
            // Log-normal prior centred at 110 BPM, plus support from the double period.
            val prior = exp(-0.5 * (ln(bpm / 110.0) / 0.5).let { it * it })
            val dbl = if (lag * 2 <= maxLag + 1) ac[lag * 2] * 0.5 else 0.0
            val score = (ac[lag] + dbl) * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        // Parabolic refinement of the lag.
        var lag = bestLag.toDouble()
        if (bestLag in (minLag + 1) until maxLag) {
            val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
            val d = a - 2 * b + c
            if (d < 0) lag += 0.5 * (a - c) / d
        }
        var bpm = 60.0 * RATE / lag
        bpm = (bpm * 10).roundToInt() / 10.0
        val offset = phase(notes, 60.0 / bpm)
        return TimeGrid(bpm, beatsPerBar, 4, offset)
    }

    /** Chooses the beat offset (0..period) that puts the most onset weight on the grid. */
    fun phase(notes: List<Note>, period: Double): Double {
        val steps = 48
        var best = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        for (s in 0 until steps) {
            val off = period * s / steps
            var score = 0.0
            for (n in notes) {
                val x = (n.start - off) / period
                val d = abs(x - Math.round(x))
                score += (0.3 + n.velocity / 127.0) * exp(-(d * d) / (2 * 0.06 * 0.06))
            }
            if (score > bestScore) { bestScore = score; best = off }
        }
        // Keep the first downbeat near the first note.
        val first = notes.minOf { it.start }
        var o = best
        while (o + period <= first + 1e-6) o += period
        return o
    }
}

/** Snaps note starts and ends to a 1/8 or 1/16 note grid. */
object Quantizer {
    /** [division] = 8 for eighth notes, 16 for sixteenth notes (in beatUnit = 4). */
    fun quantize(notes: List<Note>, grid: TimeGrid, division: Int): List<Note> {
        require(division > 0)
        val step = grid.secondsPerBeat * 4.0 / division
        val snapped = notes.map { n ->
            val s = snap(n.start, grid.offset, step)
            var e = snap(n.end, grid.offset, step)
            if (e <= s + 1e-9) e = s + step
            n.copy(start = s, duration = e - s)
        }
        // Merge duplicates (same pitch and start after snapping) and remove same-pitch overlaps.
        val byPitch = snapped.groupBy { it.pitch }
        val out = ArrayList<Note>()
        for ((_, list) in byPitch) {
            val sorted = list.sortedBy { it.start }
            var cur: Note? = null
            for (n in sorted) {
                val c = cur
                if (c == null) { cur = n; continue }
                if (abs(n.start - c.start) < 1e-6) {
                    cur = c.copy(duration = maxOf(c.duration, n.duration), velocity = maxOf(c.velocity, n.velocity), confidence = maxOf(c.confidence, n.confidence))
                } else {
                    out += if (c.end > n.start + 1e-9) c.copy(duration = n.start - c.start) else c
                    cur = n
                }
            }
            cur?.let { out += it }
        }
        return out.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    fun snap(t: Double, offset: Double, step: Double): Double = offset + Math.round((t - offset) / step) * step
}
