package com.pianoscribe.core.theory

import com.pianoscribe.core.model.TimeGrid
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Note values and bar:beat positions. Beats are quarter notes when the beat unit is 4. */
object NoteValue {
    private val VALUES = listOf(
        6.0 to "dotted whole", 4.0 to "whole", 3.0 to "dotted half", 2.0 to "half",
        1.5 to "dotted quarter", 1.0 to "quarter", 0.75 to "dotted eighth", 2.0 / 3 to "quarter triplet",
        0.5 to "eighth", 1.0 / 3 to "eighth triplet", 0.375 to "dotted 16th", 0.25 to "16th", 0.125 to "32nd",
    )

    /** Name of the closest note value for a duration of [quarterNotes] quarter notes. */
    fun name(quarterNotes: Double): String {
        if (quarterNotes > 7) return "${formatNumber(quarterNotes)} beats (tied)"
        if (quarterNotes < 0.09) return "grace note"
        return VALUES.minBy { abs(kotlin.math.ln(it.first / quarterNotes)) }.second
    }

    fun nameForSeconds(seconds: Double, grid: TimeGrid): String =
        name(seconds / grid.secondsPerBeat * (4.0 / grid.beatUnit))

    data class BarBeat(val bar: Int, val beat: Double) {
        /** "3:2.5" */
        val compact: String get() = "$bar:${formatNumber(beat)}"
        /** "Bar 3, Beat 2.5" */
        val label: String get() = "Bar $bar, Beat ${formatNumber(beat)}"
    }

    /**
     * Converts a time to bar and beat (1-based). Beats are rounded to the nearest [resolution]
     * of a beat (default 1/4 beat = 16th note in 4/4) to hide timing jitter.
     */
    fun barBeat(timeSec: Double, grid: TimeGrid, resolution: Double = 0.25): BarBeat {
        val beatsRaw = grid.timeToBeats(timeSec)
        val beats = (beatsRaw / resolution).roundToLong() * resolution
        val bar = floor(beats / grid.beatsPerBar).toInt()
        val inBar = beats - bar * grid.beatsPerBar
        return BarBeat(bar + 1, inBar + 1)
    }

    fun formatNumber(x: Double): String {
        val r = (x * 100).roundToLong() / 100.0
        return if (r == floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }
}
