package com.pianoscribe.core.transcribe

import com.pianoscribe.core.model.Note
import kotlin.math.abs

/**
 * Combines the high-resolution piano model with Basic Pitch. The two make different mistakes:
 * the piano model's extra notes are mostly overtones (an octave, twelfth or two octaves above,
 * an octave below) of notes really played; Basic Pitch's are mostly repeated onsets inside a
 * held note. So the piano model leads, and Basic Pitch is used as a second opinion:
 *
 * - a piano-model note Basic Pitch also has (same pitch, onsets within 50 ms) is kept;
 * - an unconfirmed one is kept too, unless it sits at an overtone interval from a confirmed
 *   note starting at the same moment, which is what the piano model's ghosts look like
 *   ([keepUnconfirmed] = false drops every unconfirmed note).
 *
 * Basic Pitch's own extra notes are never added.
 */
object PianoEnsemble {
    /** Same pitch and onsets this close count as the same note. */
    private const val MATCH_SEC = 0.05
    /** Semitone distances (from the confirmed note) where the piano model hears overtones as notes. */
    private val OVERTONES = setOf(-12, 7, 12, 19, 24, 28)

    fun combine(piano: List<Note>, basicPitch: List<Note>, keepUnconfirmed: Boolean = true): List<Note> {
        val bpByPitch = basicPitch.groupBy { it.pitch }
        val (confirmed, unconfirmed) = piano.partition { h -> bpByPitch[h.pitch].orEmpty().any { abs(it.start - h.start) <= MATCH_SEC } }
        if (!keepUnconfirmed) return confirmed.sortedWith(compareBy({ it.start }, { it.pitch }))
        val kept = unconfirmed.filter { h -> confirmed.none { c -> (h.pitch - c.pitch) in OVERTONES && abs(c.start - h.start) <= MATCH_SEC } }
        return (confirmed + kept).sortedWith(compareBy({ it.start }, { it.pitch }))
    }
}
