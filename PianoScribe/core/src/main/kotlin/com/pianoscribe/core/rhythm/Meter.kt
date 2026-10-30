package com.pianoscribe.core.rhythm

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Time signature and downbeat from the notes, on the beat grid [TempoDetector] found:
 *
 * - **Beats per bar** (2, 3 or 4): every beat gets an accent (loud, long and bass notes starting
 *   on it); the bar length is the period whose strongest phase stands out most from the other
 *   beats. 4 is preferred to 2 unless 2 is clearly better, and anything unclear stays 4/4.
 * - **Downbeat**: that strongest phase, so a piece starting with a pickup gets its first bar right.
 * - **Compound time** (6/8, 9/8, 12/8): when notes inside the beat fall on thirds of it far more
 *   often than on halves, the beat is a dotted quarter and the bar is written in eighths.
 */
object MeterDetector {
    data class Meter(val beatsPerBar: Int, val compound: Boolean, val downbeatPhase: Int, val confidence: Double)

    /** Enough beats to judge from: three bars of the longest candidate. */
    private const val MIN_BEATS = 12

    fun analyze(notes: List<Note>, grid: TimeGrid): Meter {
        val p = grid.secondsPerBeat
        if (notes.size < 6) return Meter(4, false, 0, 0.0)
        val first = floor(grid.timeToBeats(notes.minOf { it.start }) + 0.5).toInt()
        val last = floor(grid.timeToBeats(notes.maxOf { it.start }) + 0.5).toInt()
        val nBeats = last - first + 1
        // Accent of each beat.
        val accent = DoubleArray(nBeats.coerceAtLeast(1))
        var triplet = 0; var duple = 0
        for (n in notes) {
            val b = grid.timeToBeats(n.start)
            val k = floor(b + 0.5).toInt()
            val frac = b - floor(b)
            if (abs(b - k) <= 0.12) {
                val w = 0.5 + n.velocity / 127.0 + 0.5 * minOf(n.duration / p, 2.0) + if (n.pitch < 55) 0.7 else 0.0
                if (k - first in accent.indices) accent[k - first] += w
            } else {
                // Inside the beat: thirds (compound) or halves and quarters (simple)?
                when {
                    abs(frac - 1.0 / 3) <= 0.07 || abs(frac - 2.0 / 3) <= 0.07 -> triplet++
                    abs(frac - 0.5) <= 0.07 || abs(frac - 0.25) <= 0.05 || abs(frac - 0.75) <= 0.05 -> duple++
                }
            }
        }
        val compound = triplet >= 8 && triplet > 2 * duple
        if (nBeats < MIN_BEATS) return Meter(4, compound, 0, 0.0)
        val mean = accent.average()
        val sd = sqrt(accent.sumOf { (it - mean) * (it - mean) } / accent.size).coerceAtLeast(1e-9)
        // How much the strongest phase of period m stands out, in standard deviations.
        fun score(m: Int): Pair<Double, Int> {
            var best = Double.NEGATIVE_INFINITY; var bestPhase = 0
            for (phase in 0 until m) {
                var on = 0.0; var nOn = 0; var off = 0.0; var nOff = 0
                for (i in accent.indices) if ((i % m) == phase) { on += accent[i]; nOn++ } else { off += accent[i]; nOff++ }
                val s = (on / nOn.coerceAtLeast(1) - off / nOff.coerceAtLeast(1)) / sd
                if (s > best) { best = s; bestPhase = phase }
            }
            return best to bestPhase
        }
        val (s2, p2) = score(2)
        val (s3, p3) = score(3)
        val (s4, p4) = score(4)
        val (m, phase, conf) = when {
            s3 > 0.5 && s3 > 1.15 * maxOf(s4, s2) -> Triple(3, p3, s3)
            s4 > 0.3 && s4 >= 0.8 * s2 -> Triple(4, p4, s4)
            s2 > 0.5 && s2 > 1.25 * s4 -> Triple(2, p2, s2)
            else -> Triple(4, if (s4 > 0.3) p4 else 0, s4)
        }
        // In compound time a 4-beat bar whose third beat is about as strong as its first is two bars
        // of 6/8 (much more common than 12/8).
        if (compound && m == 4) {
            fun meanAt(ph: Int) = accent.indices.filter { it % 4 == ph }.map { accent[it] }.average()
            if (meanAt((phase + 2) % 4) >= 0.8 * meanAt(phase)) return Meter(2, true, Math.floorMod(first + phase, 2), conf)
        }
        // Phase counts from the first beat with a note; as an offset from the grid's beat 0.
        return Meter(m, compound, Math.floorMod(first + phase, m), conf)
    }

    /**
     * The grid with the detected time signature: bar 1 starts on the first downbeat at or before
     * the first note (a pickup then sits at the end of an otherwise empty first bar), and in
     * compound time the beat is an eighth (3 per dotted-quarter beat).
     */
    fun apply(notes: List<Note>, grid: TimeGrid): TimeGrid {
        if (notes.isEmpty()) return grid
        val meter = analyze(notes, grid)
        // Downbeat: the grid beat with the detected phase at or before the first note.
        val firstBeat = floor(grid.timeToBeats(notes.minOf { it.start }) + 0.5).toInt()
        val down = firstBeat - Math.floorMod(firstBeat - meter.downbeatPhase, meter.beatsPerBar)
        val offset = grid.beatsToTime(down.toDouble())
        return if (meter.compound) TimeGrid((grid.bpm * 3 * 10).roundToInt() / 10.0, meter.beatsPerBar * 3, 8, offset)
        else TimeGrid(grid.bpm, meter.beatsPerBar, 4, offset)
    }

    /** Compound time: written in eighths, felt in dotted quarters. */
    fun isCompound(grid: TimeGrid) = grid.beatUnit == 8 && grid.beatsPerBar % 3 == 0
}
