package com.pianoscribe.core

import com.pianoscribe.core.arrange.Arranger
import com.pianoscribe.core.arrange.Drum
import com.pianoscribe.core.arrange.SongRenderer
import com.pianoscribe.core.arrange.Style
import com.pianoscribe.core.midi.MidiReader
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class ArrangerTest {
    private val grid = TimeGrid(bpm = 120.0) // 2 s bars in 4/4
    private val cMajor = KeyDetector.Key(0, minor = false, score = 1.0)
    private var id = 0L

    /** Quarter notes from bar [bar], one per pitch. */
    private fun bar(bar: Int, vararg pitches: Int) = pitches.mapIndexed { i, p -> Note(id++, p, bar * 2.0 + i * 0.5, 0.45, 80, 1f, Hand.RIGHT) }

    private val tune = bar(0, 72, 76, 79, 76) + bar(1, 77, 81, 84, 81) + bar(2, 79, 83, 74, 79) + bar(3, 72, 76, 72, 79)

    @Test fun chordsFollowTheMelody() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.POP)
        // C E G | F A C | G B D | C E C G
        assertEquals(listOf("C", "F", "G", "C"), song.bars.map { it.chord.name() })
        assertEquals("C – F – G – C", song.progression())
    }

    @Test fun minorKeysUseTheMajorDominant() {
        val aMinor = KeyDetector.Key(9, minor = true, score = 1.0)
        val melody = bar(0, 69, 72, 76, 72) + bar(1, 76, 80, 83, 80) + bar(2, 69, 72, 69, 76)
        val song = Arranger.arrange(melody, grid, aMinor, Style.POP)
        assertEquals(listOf("Am", "E", "Am"), song.bars.take(3).map { it.chord.name() })
    }

    @Test fun seventhsInLofiAndBallad() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.LOFI)
        assertEquals(listOf("Cmaj7", "Fmaj7", "G7", "Cmaj7"), song.bars.map { it.chord.name() })
    }

    @Test fun partsPlayTheChordsInRange() {
        for (style in Style.entries) {
            val song = Arranger.arrange(tune, grid, cMajor, style)
            for (b in song.bars) {
                val pcs = b.chord.pitchClasses.toSet()
                val chordNotes = song.chords.filter { it.start >= b.start - 0.01 && it.start < b.end - 0.01 }
                assertTrue("$style ${b.chord.name()}: chord notes", chordNotes.isNotEmpty() && chordNotes.all { Pitch.pitchClass(it.pitch) in pcs || style == Style.ROCK })
                assertTrue("$style: chords around middle C", chordNotes.all { it.pitch in 40..76 })
                val firstBass = song.bass.first { abs(it.start - b.start) < 0.01 }
                assertEquals("$style: bass on the root", b.chord.rootPc, Pitch.pitchClass(firstBass.pitch))
                assertTrue(song.bass.all { it.pitch in 36..60 })
            }
            assertEquals(tune.size, song.melody.size)
        }
    }

    @Test fun voiceLeadingMovesLittle() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.POP)
        val voicings = song.bars.map { b -> song.chords.filter { abs(it.start - b.start) < 0.01 }.map { it.pitch }.sorted() }
        for ((a, b) in voicings.zipWithNext()) {
            val moved = b.sumOf { p -> a.minOf { abs(it - p) } }
            assertTrue("voicing $a -> $b moves $moved semitones", moved <= 6)
        }
    }

    @Test fun backbeatOnTwoAndFour() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.POP)
        val bar = song.bars[1]
        fun beats(d: Drum) = song.drums.filter { it.drum == d && it.time >= bar.start && it.time < bar.end }.map { Math.round((it.time - bar.start) / grid.secondsPerBeat * 2) / 2.0 }
        assertEquals(listOf(1.0, 3.0), beats(Drum.SNARE))
        assertEquals(listOf(0.0, 2.0), beats(Drum.KICK))
        assertEquals(8, beats(Drum.CLOSED_HAT).size)
    }

    @Test fun threeFourHasNoBackbeatOnOne() {
        val waltz = TimeGrid(bpm = 120.0, beatsPerBar = 3)
        val melody = (0 until 9).map { Note(id++, listOf(72, 76, 79)[it % 3], it * 0.5, 0.45, 80, 1f) }
        val song = Arranger.arrange(melody, waltz, cMajor, Style.POP)
        val first = song.bars[0]
        assertTrue(song.drums.none { it.drum == Drum.SNARE && abs(it.time - first.start) < 0.01 })
        assertTrue(song.drums.any { it.drum == Drum.KICK && abs(it.time - first.start) < 0.01 })
    }

    @Test fun lofiSwingsTheOffbeats() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.LOFI)
        val bar = song.bars[1]
        val offHat = song.drums.first { it.drum == Drum.CLOSED_HAT && it.time > bar.start + 0.1 && it.time < bar.start + grid.secondsPerBeat }
        assertEquals(bar.start + grid.secondsPerBeat * (0.5 + 1.0 / 6), offHat.time, 1e-6)
    }

    @Test fun midiHasATrackPerPartAndDrumsOnChannelTen() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.ROCK)
        val f = MidiReader.read(SongRenderer.midi(song, "Test"))
        assertEquals(5, f.tracks) // conductor, melody, chords, bass, drums
        val noteOns = f.events.filter { it.status and 0xF0 == 0x90 && (it.data.getOrNull(1)?.toInt() ?: 0) > 0 }
        val drumOns = noteOns.filter { it.status and 0x0F == MidiWriter.DRUM_CHANNEL }
        assertEquals(song.drums.size, drumOns.size)
        assertTrue(drumOns.all { (it.data[0].toInt() and 0xFF) in Drum.entries.map { d -> d.gmNote } })
        assertEquals(song.melody.size, noteOns.count { it.track == 1 })
    }

    @Test fun rendersAMixedWav() {
        val song = Arranger.arrange(tune, grid, cMajor, Style.POP)
        val out = ByteArrayOutputStream()
        SongRenderer.renderWav(song, { null }, 22050, out)
        val bytes = out.toByteArray()
        assertEquals("RIFF", String(bytes.copyOf(4)))
        val frames = (bytes.size - 44) / 2
        assertEquals((song.end + 2.5) * 22050, frames.toDouble(), 2.0)
        var peak = 0
        for (i in 0 until frames) peak = maxOf(peak, abs((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)))
        assertTrue("not silent, not clipped: $peak", peak in 3000..32767)
    }

    @Test fun emptyMelodyGivesAnEmptySong() {
        val song = Arranger.arrange(emptyList(), grid, cMajor, Style.POP)
        assertTrue(song.bars.isEmpty() && song.drums.isEmpty())
    }
}
