package com.pianoscribe.core

import com.pianoscribe.core.audio.PitchAccuracy
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.ChordNamer
import com.pianoscribe.core.theory.Interval
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.NoteValue
import com.pianoscribe.core.theory.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TheoryTest {
    @Test fun noteNames() {
        assertEquals("C4", Pitch.name(60))
        assertEquals("F#3", Pitch.name(54))
        assertEquals("Gb3", Pitch.name(54, preferFlats = true))
        assertEquals("A0", Pitch.name(21))
        assertEquals("C8", Pitch.name(108))
        assertEquals("B3", Pitch.name(59))
    }

    @Test fun sargamNames() {
        assertEquals("Sa", Pitch.sargam(60))
        assertEquals("Re", Pitch.sargam(62))
        assertEquals("Ga(k)", Pitch.sargam(63))
        assertEquals("Ma(t)", Pitch.sargam(66))
        assertEquals("Ni", Pitch.sargam(71))
        assertEquals("Ṡa", Pitch.sargam(72)) // taar Sa
        assertEquals("P̣a", Pitch.sargam(55)) // mandra Pa
        assertEquals("Sa", Pitch.sargam(62, saPitchClass = 2)) // Sa = D
    }

    @Test fun frequencyAndCents() {
        assertEquals(440.0, Pitch.frequency(69), 1e-9)
        assertEquals(261.6256, Pitch.frequency(60), 1e-3)
        assertEquals(27.5, Pitch.frequency(21), 1e-9)
        assertEquals(0.0, Pitch.cents(440.0, 69), 1e-9)
        assertEquals(100.0, Pitch.cents(Pitch.frequency(70), 69), 1e-6)
        assertEquals(-25.0, Pitch.cents(440.0 * Math.pow(2.0, -25 / 1200.0), 69), 1e-6)
        assertEquals(19.56, Pitch.cents(445.0, 69), 0.01)
        assertEquals(69, Pitch.nearestMidi(452.0))
        assertEquals(70, Pitch.nearestMidi(455.0))
    }

    @Test fun measuredCentsFromAudio() {
        val sr = 22050
        for ((midi, cents) in listOf(69 to 0.0, 60 to 18.0, 48 to -22.0, 76 to 35.0, 33 to 10.0)) {
            val f = Pitch.frequency(midi, cents)
            val x = FloatArray(sr) { (0.5 * sin(2 * PI * f * it / sr) + 0.3 * sin(4 * PI * f * it / sr)).toFloat() }
            val c = PitchAccuracy.measureCents(x, sr, midi, 0.0, 1.0)
            assertNotNull(c)
            assertEquals("midi $midi", cents, c!!.toDouble(), 2.0)
        }
        assertNull(PitchAccuracy.measureCents(FloatArray(100), sr, 60, 0.0, 0.001))
    }

    @Test fun chordNames() {
        fun name(vararg p: Int) = ChordNamer.name(p.toList())!!.name
        assertEquals("C", name(60, 64, 67))
        assertEquals("Am", name(57, 60, 64))
        assertEquals("Am7", name(57, 60, 64, 67))
        assertEquals("G/B", name(59, 62, 67))
        assertEquals("C/E", name(52, 55, 60))
        assertEquals("G7", name(55, 59, 62, 65))
        assertEquals("Cmaj7", name(48, 52, 55, 59))
        assertEquals("Bdim", name(59, 62, 65))
        assertEquals("Dsus4", name(62, 67, 69))
        assertEquals("C6", name(48, 52, 55, 57))
        assertEquals("Caug", name(60, 64, 68))
        assertEquals("C5", name(48, 55))
        assertEquals("C+E", name(60, 64))
        assertEquals("Bb", ChordNamer.name(listOf(58, 62, 65), preferFlats = true)!!.name)
        assertEquals("A#", name(58, 62, 65))
        assertNull(ChordNamer.name(listOf(60, 72)))
    }

    @Test fun chordInversions() {
        assertEquals(0, ChordNamer.name(listOf(60, 64, 67))!!.inversion)
        assertEquals(1, ChordNamer.name(listOf(52, 60, 67))!!.inversion)
        assertEquals(2, ChordNamer.name(listOf(55, 60, 64))!!.inversion)
        assertEquals(3, ChordNamer.name(listOf(53, 55, 59, 62))!!.inversion) // G7/F
        assertEquals("G7/F", ChordNamer.name(listOf(53, 55, 59, 62))!!.name)
    }

    @Test fun keyDetection() {
        fun notes(pitches: List<Int>, durs: List<Double> = pitches.map { 0.5 }): List<Note> {
            var t = 0.0
            return pitches.mapIndexed { i, p -> Note(i.toLong(), p, t, durs[i], 90, 1f).also { t += durs[i] } }
        }
        val cMajor = notes(listOf(60, 62, 64, 65, 67, 69, 71, 72, 67, 64, 60, 55, 60), listOf(1.0, .5, .5, .5, 1.0, .5, .5, 1.5, .5, .5, 1.0, .5, 2.0))
        assertEquals("C major", KeyDetector.detect(cMajor)!!.name)
        val aMinor = notes(listOf(57, 59, 60, 62, 64, 65, 68, 69, 64, 60, 57, 52, 57, 68, 69), listOf(2.0, .5, 1.0, .5, 1.0, .5, .5, 2.0, .5, .5, 1.0, .5, 2.0, .5, 2.0))
        assertEquals("A minor", KeyDetector.detect(aMinor)!!.name)
        val g = notes(listOf(55, 59, 62, 66, 67, 62, 59, 55, 57, 66, 67), listOf(1.0, .5, .5, .5, 1.5, .5, .5, 1.0, .5, .5, 2.0))
        val gk = KeyDetector.detect(g)!!
        assertEquals("G major", gk.name)
        assertEquals(1, gk.fifths)
        assertTrue(gk.contains(66)); assertFalse(gk.contains(65))
        val f = KeyDetector.detect(notes(listOf(53, 57, 60, 58, 53, 55, 57, 58, 60, 65), listOf(1.5, .5, 1.0, .5, 2.0, .5, .5, .5, .5, 2.0)))!!
        assertEquals("F major", f.name); assertEquals(-1, f.fifths)
    }

    @Test fun barBeatConversion() {
        val g = TimeGrid(bpm = 120.0, beatsPerBar = 4)
        assertEquals("Bar 1, Beat 1", NoteValue.barBeat(0.0, g).label)
        assertEquals("Bar 1, Beat 2", NoteValue.barBeat(0.5, g).label)
        assertEquals("Bar 3, Beat 2.5", NoteValue.barBeat(4.75, g).label)
        assertEquals("2:4.75", NoteValue.barBeat(3.875, g).compact)
        assertEquals("Bar 2, Beat 1", NoteValue.barBeat(1.99, g).label) // jitter snaps to the grid
        val waltz = TimeGrid(bpm = 90.0, beatsPerBar = 3, offset = 0.5)
        assertEquals("Bar 2, Beat 1", NoteValue.barBeat(0.5 + 3 * 60 / 90.0, waltz).label)
        assertEquals("Bar 1, Beat 3", NoteValue.barBeat(0.5 + 2 * 60 / 90.0, waltz).label)
    }

    @Test fun noteValues() {
        assertEquals("quarter", NoteValue.name(1.0))
        assertEquals("eighth", NoteValue.name(0.52))
        assertEquals("dotted half", NoteValue.name(3.0))
        assertEquals("dotted quarter", NoteValue.name(1.45))
        assertEquals("16th", NoteValue.name(0.26))
        assertEquals("whole", NoteValue.name(4.1))
        assertEquals("half", NoteValue.nameForSeconds(1.0, TimeGrid(bpm = 120.0)))
    }

    @Test fun intervals() {
        assertEquals("up a major 3rd", Interval.describe(60, 64))
        assertEquals("down a perfect 5th", Interval.describe(67, 60))
        assertEquals("up an octave", Interval.describe(60, 72))
        assertEquals("same note", Interval.describe(60, 60))
        assertEquals("up a major 9th", Interval.describe(60, 74))
        assertEquals("down a minor 2nd", Interval.describe(61, 60))
    }
}
