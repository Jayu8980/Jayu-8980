package com.pianoscribe.core

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.rhythm.MeterDetector
import com.pianoscribe.core.rhythm.TempoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterTest {
    private var id = 0L
    private fun n(p: Int, s: Double, d: Double, v: Int) = Note(++id, p, s, d, v, 0.9f, if (p >= 60) Hand.RIGHT else Hand.LEFT)

    /** Bars of [beats] beats at [beat] s: bass on beat 1 (and on 3 in 4/4), chords on the others, a tune on every beat. */
    private fun piece(beats: Int, bars: Int, beat: Double = 0.5, start: Double = 0.4): List<Note> {
        val tune = listOf(72, 74, 76, 77, 79, 77, 76, 74)
        val out = ArrayList<Note>()
        for (b in 0 until bars) for (k in 0 until beats) {
            val t = start + (b * beats + k) * beat
            out += n(tune[(b * beats + k) % tune.size], t, beat * 0.9, 72)
            if (k == 0) out += n(43, t, beat * beats * 0.9, 100)
            else if (beats == 4 && k == 2) out += n(48, t, beat * 1.8, 85)
            else { out += n(64, t, beat * 0.5, 55); out += n(67, t, beat * 0.5, 55) }
        }
        return out
    }

    private fun meterOf(notes: List<Note>): TimeGrid = MeterDetector.apply(notes, TempoDetector.detect(notes))

    @Test fun waltzIsInThree() {
        val g = meterOf(piece(3, 16))
        assertEquals(3, g.beatsPerBar); assertEquals(4, g.beatUnit)
        // Bar 1 starts on the first bass note.
        assertEquals(0.4, g.offset, 0.03)
    }

    @Test fun marchIsInFour() {
        val g = meterOf(piece(4, 12))
        assertEquals(4, g.beatsPerBar); assertEquals(4, g.beatUnit)
        assertEquals(0.4, g.offset, 0.03)
    }

    @Test fun pickupBeforeTheFirstBar() {
        // A waltz with one pickup note a beat before the first downbeat.
        val notes = listOf(n(71, 0.9, 0.45, 70)) + piece(3, 16, start = 1.4)
        val g = meterOf(notes)
        assertEquals(3, g.beatsPerBar)
        // Bar 2 starts on the first bass note at 1.4 s; the pickup is the last beat of bar 1.
        assertEquals(1.4 - 3 * 0.5, g.offset, 0.03)
        assertEquals(2.0, g.timeToBeats(0.9), 0.06)
    }

    @Test fun jigIsInSixEight() {
        // Dotted-quarter beats of 0.6 s, each three eighths; bass on every other beat.
        val out = ArrayList<Note>()
        val tune = listOf(72, 74, 76, 79, 77, 76)
        for (beat in 0 until 32) {
            val t = 0.3 + beat * 0.6
            for (e in 0 until 3) out += n(tune[(beat * 3 + e) % tune.size], t + e * 0.2, 0.18, if (e == 0) 80 else 60)
            if (beat % 2 == 0) out += n(45, t, 1.1, 100) else out += n(57, t, 0.5, 60)
        }
        val g = meterOf(out)
        assertTrue(MeterDetector.isCompound(g))
        assertEquals(6, g.beatsPerBar); assertEquals(8, g.beatUnit)
        assertEquals(0.2, g.secondsPerBeat, 0.01) // an eighth
    }

    @Test fun tooLittleMusicStaysInFour() {
        val notes = piece(3, 2)
        val m = MeterDetector.analyze(notes, TempoDetector.detect(notes))
        assertEquals(4, m.beatsPerBar)
        assertFalse(m.compound)
    }
}
