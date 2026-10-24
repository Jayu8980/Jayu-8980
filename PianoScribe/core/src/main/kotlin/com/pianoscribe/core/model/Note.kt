package com.pianoscribe.core.model

/** Which hand plays a note. */
enum class Hand { RIGHT, LEFT }

/**
 * One transcribed note. Times are in seconds from the start of the (trimmed) audio.
 *
 * @param pitch MIDI note number, 21 (A0) to 108 (C8)
 * @param velocity MIDI velocity 1..127
 * @param confidence model confidence 0..1
 * @param cents measured tuning of the played note versus equal temperament, -50..+50
 */
data class Note(
    val id: Long,
    val pitch: Int,
    val start: Double,
    val duration: Double,
    val velocity: Int,
    val confidence: Float,
    val hand: Hand = Hand.RIGHT,
    val cents: Float = 0f,
    val verified: Boolean = false,
) {
    val end: Double get() = start + duration

    companion object {
        const val MIN_PIANO = 21
        const val MAX_PIANO = 108
        const val MIDDLE_C = 60
    }
}

/** Generates note ids that are unique within one project. */
class NoteIdGenerator(start: Long = 1) {
    private var next = start
    fun next(): Long = next++
    fun advancePast(notes: Collection<Note>) {
        val max = notes.maxOfOrNull { it.id } ?: 0
        if (max >= next) next = max + 1
    }
}

/** Global musical context of a song: tempo grid and meter. */
data class TimeGrid(
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    /** Time in seconds of the first downbeat (bar 1, beat 1). */
    val offset: Double = 0.0,
) {
    val secondsPerBeat: Double get() = 60.0 / bpm
    fun timeToBeats(t: Double): Double = (t - offset) / secondsPerBeat
    fun beatsToTime(b: Double): Double = offset + b * secondsPerBeat
}
