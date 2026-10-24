package com.pianoscribe.core

import com.pianoscribe.core.midi.MidiReader
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.Performer
import com.pianoscribe.core.perform.PlayedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PerformerTest {
    private val grid = TimeGrid(bpm = 120.0)
    private fun n(id: Long, p: Int, s: Double, d: Double, h: Hand = Hand.RIGHT, v: Int = 80) = Note(id, p, s, d, v, 0.9f, h)

    /** Four bars: C-major chords in the left hand and a stepwise melody in the right. */
    private val song: List<Note> = run {
        val out = ArrayList<Note>()
        var id = 1L
        for (bar in 0 until 4) {
            val t = bar * 2.0
            for (p in listOf(48, 52, 55)) out += n(id++, p, t, 1.9, Hand.LEFT, 70)
            for (k in 0 until 4) out += n(id++, listOf(72, 74, 76, 77)[k] + (bar % 2) * 2, t + k * 0.5, 0.45)
        }
        out
    }

    @Test fun zeroFeelPlaysExactlyAsWrittenOnPiano() {
        val played = Performer.perform(song, grid, Instrument.PIANO, Performer.Options(feel = 0f))
        assertEquals(song.size, played.size)
        val exact = song.sortedWith(compareBy({ it.start }, { it.pitch })).map(PlayedNote::of)
        assertEquals(exact, played)
    }

    @Test fun monophonicInstrumentGetsTheMelodyOnly() {
        val line = Performer.perform(song, grid, Instrument.FLUTE, Performer.Options(feel = 0f))
        assertEquals(16, line.size) // the 16 melody notes, no chord notes
        for (i in 0 until line.size - 1) assertTrue("no overlap at $i", line[i].end <= line[i + 1].start + 1e-9)
        assertTrue(line.all { it.pitch in Instrument.FLUTE.low..Instrument.FLUTE.high })
    }

    @Test fun melodyMovesByOctavesIntoTheInstrumentRange() {
        val low = listOf(n(1, 40, 0.0, 0.5), n(2, 43, 0.5, 0.5), n(3, 45, 1.0, 0.5))
        val flute = Performer.perform(low, grid, Instrument.FLUTE, Performer.Options(feel = 0f))
        assertTrue(flute.all { it.pitch in 60..96 })
        // Whole-octave shift keeps the tune: intervals unchanged.
        assertEquals(3, flute[1].pitch - flute[0].pitch)
        assertEquals(2, flute[2].pitch - flute[1].pitch)
        assertEquals(0, (flute[0].pitch - 40) % 12)
        val cello = Performer.perform(listOf(n(1, 96, 0.0, 0.5)), grid, Instrument.CELLO, Performer.Options(feel = 0f))
        assertTrue(cello.single().pitch in 36..76)
    }

    @Test fun guitarPlaysAtMostSixNotesAtOnce() {
        val big = (0 until 9).map { n(it.toLong(), 48 + it * 3, 0.0, 1.0) }
        val g = Performer.perform(big, grid, Instrument.GUITAR, Performer.Options(feel = 0f))
        assertEquals(6, g.size)
        assertEquals(g.minOf { it.pitch }, Performer.fold(48, Instrument.GUITAR))
    }

    @Test fun humanFeelStaysCloseToTheScore() {
        val played = Performer.perform(song, grid, Instrument.PIANO, Performer.Options(feel = 1f, tempoChanges = false))
        assertEquals(song.size, played.size)
        val byKey = song.groupBy { it.pitch }
        for (p in played) {
            val nearest = byKey.getValue(p.pitch).minBy { abs(it.start - p.start) }
            assertTrue("timing ${p.start - nearest.start}", abs(p.start - nearest.start) < 0.05)
            assertTrue("velocity ${p.velocity} vs ${nearest.velocity}", abs(p.velocity - nearest.velocity) <= 25)
        }
        // Something actually changed.
        assertTrue(played.zip(song.sortedWith(compareBy({ it.start }, { it.pitch }))).any { (a, b) -> a.start != b.start || a.velocity != b.velocity })
    }

    @Test fun pianoVoicesTheMelodyAboveTheChords() {
        val played = Performer.perform(song, grid, Instrument.PIANO, Performer.Options(feel = 1f, tempoChanges = false))
        val melody = played.filter { it.hand == Hand.RIGHT }.map { it.velocity }.average()
        val chords = played.filter { it.hand == Hand.LEFT && it.pitch != 48 }.map { it.velocity }.average()
        assertTrue("melody $melody vs inner chord notes $chords", melody - 80 > chords - 70)
    }

    @Test fun harpRollsChordsLowToHigh() {
        val chord = listOf(n(1, 48, 1.0, 1.0), n(2, 55, 1.0, 1.0), n(3, 64, 1.0, 1.0), n(4, 72, 1.0, 1.0))
        val played = Performer.perform(chord, grid, Instrument.HARP, Performer.Options(feel = 1f, tempoChanges = false)).sortedBy { it.pitch }
        for (i in 1 until played.size) assertTrue("rolled", played[i].start > played[i - 1].start)
        assertTrue(played.last().start - played.first().start in 0.04..0.1)
    }

    @Test fun windSlursCloseNotesAndBreathesBetweenPhrases() {
        val tune = listOf(
            n(1, 72, 0.0, 0.48), n(2, 74, 0.5, 0.48), n(3, 76, 1.0, 0.9),
            n(4, 72, 3.0, 0.48), n(5, 74, 3.5, 0.9), // new phrase after a 1.1 s rest
        )
        val flute = Performer.perform(tune, grid, Instrument.FLUTE, Performer.Options(feel = 1f, tempoChanges = false)).sortedBy { it.start }
        assertEquals(listOf(false, true, true, false, true), flute.map { it.slur })
        // Slurred notes hold into the next one so the synth glides instead of re-attacking.
        assertTrue(flute[0].end > flute[1].start)
        // A short tune with notes that just touch: the last note before the rest is not cut short.
        assertTrue(flute[2].duration >= 0.85)
    }

    @Test fun endingSlowsDown() {
        val even = (0 until 24).map { n(it.toLong(), 72, it * 0.5, 0.45) }
        val played = Performer.perform(even, grid, Instrument.PIANO, Performer.Options(feel = 1f)).sortedBy { it.start }
        val firstGap = played[2].start - played[1].start
        val lastGap = played.last().start - played[played.size - 2].start
        assertTrue("last gap $lastGap vs first $firstGap", lastGap > firstGap * 1.05)
    }

    @Test fun sameSeedSameResult() {
        val a = Performer.perform(song, grid, Instrument.VIOLIN, Performer.Options(feel = 0.8f, seed = 42))
        val b = Performer.perform(song, grid, Instrument.VIOLIN, Performer.Options(feel = 0.8f, seed = 42))
        assertEquals(a, b)
    }

    @Test fun midiCarriesTheInstrumentProgram() {
        val f = MidiReader.read(MidiWriter.write(listOf(n(1, 72, 0.0, 0.5)), grid, "T", program = Instrument.FLUTE.gmProgram))
        val programs = f.events.filter { it.status and 0xF0 == 0xC0 }.map { it.data[0].toInt() }
        assertEquals(listOf(73, 73), programs)
    }
}
