package com.pianoscribe.core

import com.pianoscribe.core.transcribe.Crepe
import com.pianoscribe.core.transcribe.MonoNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class CrepeTest {
    /** Network-like outputs: a bump around [midi] (or nothing) per frame, plus a little noise. */
    private fun probs(midis: List<Double?>): FloatArray {
        val out = FloatArray(midis.size * Crepe.BINS) { 0.02f }
        for ((t, m) in midis.withIndex()) {
            if (m == null) continue
            val c = Crepe.midiToBin(m)
            for (b in 0 until Crepe.BINS) out[t * Crepe.BINS + b] = (0.02 + 0.9 * exp(-0.5 * ((b - c) / 1.2) * ((b - c) / 1.2))).toFloat()
        }
        return out
    }

    @Test fun binsAndMidiRoundTrip() {
        // A4 = 440 Hz = MIDI 69.
        assertEquals(69.0, Crepe.centsToMidi(Crepe.binToCents(Crepe.midiToBin(69.0))), 1e-9)
        assertEquals(1997.379, Crepe.binToCents(0.0), 1e-3) // C1-ish, the first bin
    }

    @Test fun decodesAPitchLineWithItsCents() {
        // A slide from 60 to 62 and a held 64.3 (30 cents sharp).
        val line = (0 until 50).map { 60.0 + 2.0 * it / 49 } + List(50) { 64.3 }
        val tr = Crepe.decode(probs(line), line.size)
        assertEquals(60.0, tr.midi[0].toDouble(), 0.1)
        assertEquals(64.3, tr.midi[80].toDouble(), 0.05)
        assertTrue(tr.periodicity[80] > 0.8f)
    }

    @Test fun framesAreNormalised() {
        val audio = FloatArray(4000) { kotlin.math.sin(it * 0.1f) * 0.3f + 0.1f }
        val out = FloatArray(Crepe.WINDOW)
        Crepe.fillFrames(audio, 10, 1, out)
        assertEquals(0.0, out.average(), 1e-4)
        val sd = kotlin.math.sqrt(out.sumOf { it.toDouble() * it } / (Crepe.WINDOW - 1))
        assertEquals(1.0, sd, 1e-3)
    }

    @Test fun segmentsNotesThroughVibratoAndSilences() {
        // C4 with vibrato (40 cents), a rest, then D4 then E4 legato.
        val line = (0 until 60).map { 60.0 + 0.4 * kotlin.math.sin(it * 0.6) } + List<Double?>(20) { null } +
            List(40) { 62.0 } + List(40) { 64.0 }
        val tr = Crepe.decode(probs(line), line.size)
        val db = FloatArray(line.size) { if (line[it] == null) -80f else -20f }
        val notes = MonoNotes.notes(tr, db)
        assertEquals(listOf(60, 62, 64), notes.map { it.pitch })
        assertEquals(0.0, notes[0].start, 0.03)
        assertEquals(0.8, notes[1].start, 0.03)
        assertEquals(1.2, notes[2].start, 0.03)
    }

    @Test fun repeatedNoteIsSplitAtANewAttack() {
        val line = List<Double?>(80) { 67.0 }
        val tr = Crepe.decode(probs(line), line.size)
        // A dip in loudness at frame 39 and a fresh attack at 40.
        val db = FloatArray(80) { when (it) { 38 -> -26f; 39 -> -32f; else -> if (it in 40..41) -18f else -20f } }
        val notes = MonoNotes.notes(tr, db)
        assertEquals(listOf(67, 67), notes.map { it.pitch })
        assertEquals(0.4, notes[1].start, 0.02)
    }
}
