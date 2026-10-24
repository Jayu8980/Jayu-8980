package com.pianoscribe.core.transcribe

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import kotlin.math.abs

/**
 * Combines the high-resolution piano model with Basic Pitch. The piano model leads (it is far
 * more precise on piano); a Basic Pitch note it has no match for is added only when the piano
 * model's own frame output shows that key sounding there, i.e. the piano model heard the note
 * but its onset detector missed it (typically fast repeated notes).
 */
object PianoEnsemble {
    /** Same pitch and onsets this close count as the same note. */
    private const val MATCH_SEC = 0.05
    /** Mean frame activity of the piano model over the first 60 ms of the candidate. */
    private const val FRAME_SUPPORT = 0.35f

    fun combine(piano: List<Note>, pianoOut: PianoModel.Output, basicPitch: List<Note>, ids: NoteIdGenerator = NoteIdGenerator().apply { advancePast(piano) }): List<Note> {
        val byPitch = piano.groupBy { it.pitch }
        val added = ArrayList<Note>()
        for (n in basicPitch) {
            val key = n.pitch - PianoModel.FIRST_KEY
            if (key !in 0 until PianoModel.KEYS) continue
            if (byPitch[n.pitch].orEmpty().any { abs(it.start - n.start) <= MATCH_SEC }) continue
            val f0 = (n.start * PianoModel.FPS).toInt()
            val f1 = minOf(pianoOut.frames, f0 + 6)
            if (f0 < 0 || f0 >= f1) continue
            var s = 0f
            for (f in f0 until f1) s += pianoOut.frame[f][key]
            if (s / (f1 - f0) >= FRAME_SUPPORT) added += n.copy(id = ids.next())
        }
        return (piano + added).sortedWith(compareBy({ it.start }, { it.pitch }))
    }
}
