package com.pianoscribe.core

import com.pianoscribe.core.analysis.ChordTrack
import com.pianoscribe.core.midi.MidiReader
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.midi.MusicXmlWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.ChordNamer
import com.pianoscribe.core.transcribe.SourceInstrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordsAndInstrumentTest {
    private val grid = TimeGrid(bpm = 120.0)
    private var id = 0L
    private fun n(p: Int, s: Double, d: Double, v: Int = 80) = Note(++id, p, s, d, v, 0.9f, if (p >= 60) Hand.RIGHT else Hand.LEFT)

    /** One bar each of C, Am/C... : C major, A minor, F major, G7, as half-second-beat block chords. */
    private fun progression(): List<Note> = listOf(
        listOf(48, 64, 67, 72), listOf(45, 64, 69, 72), listOf(41, 65, 69, 72), listOf(43, 65, 71, 74),
    ).flatMapIndexed { bar, ch -> ch.map { n(it, bar * 2.0, 1.95) } }

    @Test fun chordLabelsPerBar() {
        val labels = ChordTrack.labels(progression(), grid)
        assertEquals(listOf("C", "Am", "F", "G7"), labels.map { it.name })
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0), labels.map { it.time })
    }

    @Test fun musicXmlHasHarmony() {
        val notes = progression()
        val xml = MusicXmlWriter.write(notes, grid, "T", chords = ChordTrack.labels(notes, grid))
        val harmonies = Regex("<harmony.*?</harmony>").findAll(xml).map { it.value }.toList()
        assertEquals(4, harmonies.size)
        assertTrue(harmonies[0].contains("<root-step>C</root-step>") && harmonies[0].contains(">major</kind>"))
        assertTrue(harmonies[1].contains("<root-step>A</root-step>") && harmonies[1].contains("text=\"m\">minor</kind>"))
        assertTrue(harmonies[3].contains("<root-step>G</root-step>") && harmonies[3].contains(">dominant</kind>"))
        // One per bar, before the bar's first note.
        val measures = xml.split("<measure ").drop(1)
        for (m in measures.take(4)) assertTrue(m.indexOf("<harmony") in 0 until m.indexOf("<note>"))
        // Without chords, no harmony.
        assertTrue("<harmony" !in MusicXmlWriter.write(notes, grid, "T"))
    }

    @Test fun parsesChordNames() {
        val c = MusicXmlWriter.parseChord("F#m7/C#")!!
        assertEquals('F', c.rootStep); assertEquals(1, c.rootAlter); assertEquals("m7", c.suffix); assertEquals('C' to 1, c.bass)
        val b = MusicXmlWriter.parseChord("Bbmaj7")!!
        assertEquals('B', b.rootStep); assertEquals(-1, b.rootAlter); assertEquals("maj7", b.suffix); assertNull(b.bass)
        assertNull(MusicXmlWriter.parseChord("x"))
    }

    @Test fun midiChordMarkersAndTrack() {
        val notes = progression()
        val labels = ChordTrack.labels(notes, grid)
        val plain = MidiReader.read(MidiWriter.write(notes, grid, "T", chords = labels))
        assertEquals(3, plain.tracks)
        val markers = plain.events.filter { it.status == 0xFF06 }
        assertEquals(listOf("C", "Am", "F", "G7"), markers.map { String(it.data) })
        assertEquals(listOf(0L, 1920L, 3840L, 5760L), markers.map { it.tick })

        val f = MidiReader.read(MidiWriter.write(notes, grid, "T", chords = labels, chordTrack = true))
        assertEquals(4, f.tracks)
        val ons = f.events.filter { it.track == 3 && it.status == 0x92 && it.data[1] > 0 }
        val byTick = ons.groupBy({ it.tick }, { it.data[0].toInt() })
        assertEquals(listOf(48, 52, 55), byTick[0L])
        assertEquals(listOf(57, 60, 64), byTick[1920L])
        assertEquals(listOf(55, 59, 62, 65), byTick[5760L])
    }

    @Test fun chordVoicingWithBassAndNinth() {
        val slash = ChordNamer.name(listOf(40, 60, 64, 67))!! // C/E
        assertEquals("C/E", slash.name)
        assertEquals(listOf(40, 48, 52, 55), MidiWriter.chordPitches(slash))
        val nine = ChordNamer.name(listOf(48, 52, 55, 62))!! // Cadd9
        assertEquals(listOf(48, 52, 55, 62), MidiWriter.chordPitches(nine))
    }

    @Test fun melodyInstrumentKeepsOneNoteAtATime() {
        // A flute line with a quieter overtone an octave up and a note out of its range.
        val notes = listOf(n(72, 0.0, 0.6, 90), n(84, 0.0, 0.6, 50), n(74, 0.5, 0.5, 85), n(40, 1.0, 0.5, 90), n(76, 1.0, 0.5, 80))
        val shaped = SourceInstrument.FLUTE.shape(notes)
        assertEquals(listOf(72, 74, 76), shaped.map { it.pitch })
        // The held first note ends where the second begins.
        assertTrue(shaped[0].end <= shaped[1].start)
    }

    @Test fun guitarKeepsSixNotesInRange() {
        val notes = listOf(30, 40, 45, 50, 55, 59, 64, 69, 76).map { n(it, 0.0, 1.0) }
        val shaped = SourceInstrument.GUITAR.shape(notes)
        assertEquals(listOf(40, 55, 59, 64, 69, 76), shaped.map { it.pitch })
        // Piano keeps everything in its range.
        assertEquals(9, SourceInstrument.PIANO.shape(notes).size)
    }

    @Test fun bassKeepsTheLowLine() {
        val notes = listOf(n(36, 0.0, 0.5, 90), n(48, 0.0, 0.5, 60), n(72, 0.0, 0.5, 90), n(43, 0.5, 0.5, 85))
        assertEquals(listOf(36, 43), SourceInstrument.BASS.shape(notes).map { it.pitch })
    }
}
