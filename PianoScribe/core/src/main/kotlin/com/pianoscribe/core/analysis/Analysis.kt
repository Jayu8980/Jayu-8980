package com.pianoscribe.core.analysis

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.ChordNamer
import com.pianoscribe.core.theory.Fingering
import com.pianoscribe.core.theory.Interval
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.NoteValue
import com.pianoscribe.core.theory.Pitch

/** One step of the note-by-note analyser: a single note or a chord (notes starting together). */
data class Step(val index: Int, val start: Double, val notes: List<Note>) {
    val isChord: Boolean get() = notes.map { it.pitch }.distinct().size > 1
    val end: Double get() = notes.maxOf { it.end }
    val minConfidence: Float get() = notes.minOf { it.confidence }
    val top: Note get() = notes.maxBy { it.pitch }
}

enum class StepFilter { ALL, LOW_CONFIDENCE, CHORD, RIGHT_HAND, LEFT_HAND }

object Steps {
    const val CHORD_TOLERANCE = 0.06
    const val LOW_CONFIDENCE = 0.5f

    fun build(notes: List<Note>, tolerance: Double = CHORD_TOLERANCE): List<Step> {
        val sorted = notes.sortedWith(compareBy({ it.start }, { it.pitch }))
        val groups = ArrayList<MutableList<Note>>()
        for (n in sorted) {
            if (groups.isNotEmpty() && n.start - groups.last().first().start <= tolerance) groups.last() += n
            else groups += mutableListOf(n)
        }
        return groups.mapIndexed { i, g -> Step(i, g.first().start, g) }
    }

    fun matches(step: Step, filter: StepFilter): Boolean = when (filter) {
        StepFilter.ALL -> true
        StepFilter.LOW_CONFIDENCE -> step.minConfidence < LOW_CONFIDENCE
        StepFilter.CHORD -> step.isChord
        StepFilter.RIGHT_HAND -> step.notes.any { it.hand == Hand.RIGHT }
        StepFilter.LEFT_HAND -> step.notes.any { it.hand == Hand.LEFT }
    }

    /** Index of the next (direction = +1) or previous (-1) step matching [filter], or null. */
    fun find(steps: List<Step>, from: Int, direction: Int, filter: StepFilter): Int? {
        var i = from + direction
        while (i in steps.indices) {
            if (matches(steps[i], filter)) return i
            i += direction
        }
        return null
    }

    /** The step sounding at time t (last step starting at or before t). */
    fun indexAt(steps: List<Step>, t: Double): Int {
        var lo = 0
        var hi = steps.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (steps[mid].start <= t + 1e-6) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** A row of the analysis table (CSV/PDF export and the analyser card). */
data class AnalysisRow(
    val index: Int,
    val note: String,
    val sargam: String,
    val midi: Int,
    val frequencyHz: Double,
    val time: Double,
    val barBeat: String,
    val duration: Double,
    val noteValue: String,
    val chord: String,
    val confidence: Float,
    val hand: Hand,
    val finger: Int?,
    val cents: Float,
    val velocity: Int,
    val inKey: Boolean,
    val intervalFromPrevious: String,
)

object AnalysisTable {
    fun rows(notes: List<Note>, grid: TimeGrid, saPitchClass: Int = 0, key: KeyDetector.Key? = KeyDetector.detect(notes)): List<AnalysisRow> {
        val flats = key?.preferFlats ?: false
        val steps = Steps.build(notes)
        val fingers = Fingering.suggest(notes)
        val chordByNote = HashMap<Long, String>()
        for (s in steps) if (s.isChord) {
            val c = ChordNamer.name(s.notes.map { it.pitch }, flats)?.name ?: ""
            for (n in s.notes) chordByNote[n.id] = c
        }
        val sorted = notes.sortedWith(compareBy({ it.start }, { it.pitch }))
        val prevByHand = HashMap<Hand, Int>()
        return sorted.mapIndexed { i, n ->
            val prev = prevByHand[n.hand]
            prevByHand[n.hand] = n.pitch
            AnalysisRow(
                index = i + 1,
                note = Pitch.name(n.pitch, flats),
                sargam = Pitch.sargam(n.pitch, saPitchClass),
                midi = n.pitch,
                frequencyHz = Pitch.frequency(n.pitch),
                time = n.start,
                barBeat = NoteValue.barBeat(n.start, grid).compact,
                duration = n.duration,
                noteValue = NoteValue.nameForSeconds(n.duration, grid),
                chord = chordByNote[n.id] ?: "",
                confidence = n.confidence,
                hand = n.hand,
                finger = fingers[n.id],
                cents = n.cents,
                velocity = n.velocity,
                inKey = key?.contains(n.pitch) ?: true,
                intervalFromPrevious = if (prev == null) "" else Interval.describe(prev, n.pitch),
            )
        }
    }

    fun csv(rows: List<AnalysisRow>): String {
        val sb = StringBuilder("index,note,sargam,midi,frequency_hz,time_s,bar_beat,duration_s,note_value,chord,confidence_pct,hand,finger,cents,velocity,in_key\n")
        for (r in rows) {
            sb.append(r.index).append(',')
                .append(r.note).append(',')
                .append(csvField(r.sargam)).append(',')
                .append(r.midi).append(',')
                .append("%.2f".format(java.util.Locale.US, r.frequencyHz)).append(',')
                .append("%.3f".format(java.util.Locale.US, r.time)).append(',')
                .append(r.barBeat).append(',')
                .append("%.3f".format(java.util.Locale.US, r.duration)).append(',')
                .append(csvField(r.noteValue)).append(',')
                .append(csvField(r.chord)).append(',')
                .append(Math.round(r.confidence * 100)).append(',')
                .append(if (r.hand == Hand.RIGHT) "RH" else "LH").append(',')
                .append(r.finger ?: "").append(',')
                .append(Math.round(r.cents)).append(',')
                .append(r.velocity).append(',')
                .append(if (r.inKey) "yes" else "no").append('\n')
        }
        return sb.toString()
    }

    /** Plain-text note list. */
    fun text(rows: List<AnalysisRow>, title: String, grid: TimeGrid, keyName: String?): String {
        val sb = StringBuilder()
        sb.append(title).append('\n')
        sb.append("Tempo ${NoteValue.formatNumber(grid.bpm)} BPM, ${grid.beatsPerBar}/${grid.beatUnit}")
        if (keyName != null) sb.append(", key $keyName")
        sb.append("\n\n")
        sb.append(String.format(java.util.Locale.US, "%-5s %-5s %-8s %-9s %-8s %-8s %-16s %-8s %-3s %s\n", "#", "Note", "Sargam", "Time(s)", "Bar:Bt", "Dur(s)", "Value", "Chord", "Hd", "Conf"))
        for (r in rows) {
            sb.append(String.format(java.util.Locale.US, "%-5d %-5s %-8s %-9.3f %-8s %-8.3f %-16s %-8s %-3s %d%%\n",
                r.index, r.note, r.sargam, r.time, r.barBeat, r.duration, r.noteValue, r.chord, if (r.hand == Hand.RIGHT) "RH" else "LH", Math.round(r.confidence * 100)))
        }
        return sb.toString()
    }

    private fun csvField(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
