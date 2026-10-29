package com.pianoscribe.core.transcribe

import com.pianoscribe.core.analysis.Steps
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.perform.Instrument

/**
 * The instrument a recording is played on, chosen before transcribing, so the notes are written
 * for it: only its range, at most as many notes at once as it can play (one for voice, winds and
 * bowed strings), the high-resolution piano model only for piano, and played back on the closest
 * sound the app has.
 */
enum class SourceInstrument(
    val label: String,
    val low: Int,
    val high: Int,
    /** Notes at once: 1 = a melody instrument. */
    val maxNotes: Int,
    val playback: Instrument,
) {
    PIANO("Piano", 21, 108, 10, Instrument.PIANO),
    GUITAR("Guitar", 40, 88, 6, Instrument.GUITAR),
    BASS("Bass guitar", 28, 67, 1, Instrument.CELLO),
    VOICE("Voice / singing", 40, 84, 1, Instrument.FLUTE),
    VIOLIN("Violin", 55, 103, 1, Instrument.VIOLIN),
    CELLO("Cello", 36, 81, 1, Instrument.CELLO),
    FLUTE("Flute", 60, 98, 1, Instrument.FLUTE),
    CLARINET("Clarinet", 50, 94, 1, Instrument.CLARINET),
    SAXOPHONE("Saxophone", 44, 83, 1, Instrument.CLARINET),
    TRUMPET("Trumpet", 52, 84, 1, Instrument.CLARINET),
    HARP("Harp", 24, 103, 8, Instrument.HARP),
    ORGAN("Organ / keyboard", 24, 103, 10, Instrument.ORGAN),
    ;

    val monophonic: Boolean get() = maxNotes == 1

    /**
     * Writes [notes] for this instrument: notes outside its range are dropped; a melody instrument
     * keeps one note at a time (of notes starting together, the strongest, which is the note
     * played rather than an overtone; a held note ends where the next begins); others keep at most
     * [maxNotes] at once, the bass and the highest.
     */
    fun shape(notes: List<Note>): List<Note> {
        val inRange = notes.filter { it.pitch in low..high }
        if (inRange.isEmpty()) return inRange
        val steps = Steps.build(inRange)
        if (monophonic) {
            val line = steps.map { st -> st.notes.maxWith(compareBy<Note>({ it.velocity }, { -it.pitch })) }
            return line.mapIndexed { i, n ->
                val next = line.getOrNull(i + 1)
                if (next != null && n.end > next.start) n.copy(duration = maxOf(0.03, next.start - n.start - 0.005)) else n
            }
        }
        return steps.flatMap { st ->
            val sorted = st.notes.sortedBy { it.pitch }
            if (sorted.size <= maxNotes) sorted else listOf(sorted.first()) + sorted.takeLast(maxNotes - 1)
        }.sortedWith(compareBy({ it.start }, { it.pitch }))
    }
}
