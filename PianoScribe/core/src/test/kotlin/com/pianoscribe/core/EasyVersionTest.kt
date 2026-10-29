package com.pianoscribe.core

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.EasyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyVersionTest {
    private var id = 0L
    private fun n(pitch: Int, start: Double, dur: Double = 0.4, vel: Int = 80, hand: Hand = if (pitch >= 60) Hand.RIGHT else Hand.LEFT) =
        Note(id++, pitch, start, dur, vel, 0.9f, hand)

    private val grid = TimeGrid(bpm = 120.0) // 0.5 s per beat

    private fun assertOneAtATime(notes: List<Note>) {
        for (i in 1 until notes.size) assertTrue("overlap at ${notes[i].start}", notes[i - 1].end <= notes[i].start + 1e-9)
    }

    @Test fun keepsTheTuneAndDropsChordsAndBass() {
        val tune = listOf(n(72, 0.0), n(74, 0.5), n(76, 1.0), n(77, 1.5))
        val chords = listOf(0.0, 1.0).flatMap { t -> listOf(n(48, t, 0.9), n(64, t, 0.9), n(67, t, 0.9)) }
        val easy = EasyVersion.make(tune + chords, grid)
        assertEquals(listOf(72, 74, 76, 77), easy.map { it.pitch })
        assertEquals(tune.map { it.id }, easy.map { it.id })
        assertTrue(easy.all { it.hand == Hand.RIGHT })
        assertOneAtATime(easy)
    }

    @Test fun accompanimentUnderAHeldNoteOrDuringARestIsLeftOut() {
        // A long tune note, a chord starting under it, then a rest with a chord well below the tune.
        val tune = listOf(n(76, 0.0, 1.5), n(77, 2.0, 0.4), n(79, 2.5, 0.4), n(76, 3.0, 0.4))
        val underHeld = listOf(n(60, 0.5, 0.9), n(64, 0.5, 0.9), n(67, 0.5, 0.9))
        val inRest = listOf(n(60, 1.6, 0.3), n(64, 1.6, 0.3), n(67, 1.6, 0.3))
        val easy = EasyVersion.make(tune + underHeld + inRest, grid)
        assertEquals(listOf(76, 77, 79, 76), easy.map { it.pitch })
    }

    @Test fun uncertainNotesAreLeftOut() {
        val notes = listOf(n(72, 0.0), n(74, 0.5), n(91, 0.75, vel = 40).copy(confidence = 0.3f), n(76, 1.0))
        assertEquals(listOf(72, 74, 76), EasyVersion.make(notes, grid).map { it.pitch })
    }

    @Test fun aQuietTopNoteIsNotTakenAsTheTune() {
        // A faint note an octave above the tune at the same moment (an overtone ghost).
        val easy = EasyVersion.make(listOf(n(72, 0.0, vel = 90), n(84, 0.01, vel = 30), n(74, 0.5, vel = 90)), grid)
        assertEquals(listOf(72, 74), easy.map { it.pitch })
    }

    @Test fun fastRunsKeepTheNotesOnTheBeat() {
        // Sixteenth notes at 120 bpm (0.125 s apart): only the eighth-note positions stay,
        // and of each pair the one on the beat / half beat wins.
        val run = (0 until 16).map { i -> n(72 + i % 5, i * 0.125, 0.1) }
        val easy = EasyVersion.make(run, grid)
        assertEquals(8, easy.size)
        assertEquals((0 until 8).map { it * 0.25 }, easy.map { it.start })
        for (i in 1 until easy.size) assertTrue(easy[i].start - easy[i - 1].start >= 0.25 - 1e-9)
        assertOneAtATime(easy)
    }

    @Test fun fastTemposKeepAMinimumGapInSeconds() {
        val fast = TimeGrid(bpm = 240.0) // an eighth note is 0.125 s: the 0.2 s floor applies
        val run = (0 until 12).map { i -> n(72, i * 0.125, 0.1) }
        val easy = EasyVersion.make(run, fast)
        for (i in 1 until easy.size) assertTrue(easy[i].start - easy[i - 1].start >= 0.2 - 1e-9)
    }

    @Test fun movesTheLineIntoAComfortableRange() {
        // A tune around C7 comes down by octaves; one far outlier folds in.
        val high = listOf(96, 98, 100, 101, 103, 60).mapIndexed { i, p -> n(p, i * 0.5) }
        val easy = EasyVersion.make(high, grid)
        assertTrue(easy.map { it.pitch }.toString(), easy.all { it.pitch in 60..84 })
        // Steps of the tune keep their intervals (moved together, not note by note).
        assertEquals(listOf(2, 2, 1, 2), easy.take(5).zipWithNext { a, b -> b.pitch - a.pitch })
    }

    @Test fun shortNotesAreHeldALittleLongerButNeverOverlap() {
        val easy = EasyVersion.make(listOf(n(72, 0.0, 0.05), n(74, 0.5, 0.05), n(76, 0.7, 0.05)), grid)
        assertTrue(easy[0].duration >= 0.16 - 1e-9)
        assertOneAtATime(easy)
    }

    @Test fun usesAllNotesWhenTheRightHandIsTooThin() {
        // Only bass notes: still gives a line (moved up into the right-hand range).
        val bass = (0 until 4).map { n(40 + it * 2, it * 0.5) }
        val easy = EasyVersion.make(bass, grid)
        assertEquals(4, easy.size)
        assertTrue(easy.all { it.pitch in 60..84 })
    }

    @Test fun singleLineKeepsTheTuneAsPlayed() {
        // Fast notes, a high register and a held note: nothing is thinned or moved, only the
        // accompaniment goes and overlaps are cut.
        val tune = listOf(n(96, 0.0, 0.3), n(98, 0.125, 0.3), n(100, 0.25, 1.0), n(101, 0.5, 0.2))
        val chord = listOf(n(48, 0.0, 1.0), n(52, 0.0, 1.0), n(55, 0.0, 1.0))
        val line = EasyVersion.singleLine(tune + chord)
        assertEquals(listOf(96, 98, 100, 101), line.map { it.pitch })
        assertEquals(listOf(0.0, 0.125, 0.25, 0.5), line.map { it.start })
        // The held note now ends where the next one starts.
        assertEquals(0.245, line[2].duration, 1e-9)
        assertOneAtATime(line)
    }

    @Test fun intermediateKeepsTwoSimplifiedHands() {
        // Tune with a 32nd-note flourish, and a left hand of four-note chords on every eighth.
        val tune = listOf(n(72, 0.0, 0.5), n(74, 0.5, 0.06), n(76, 0.56, 0.06), n(77, 0.62, 0.38), n(79, 1.0, 1.0))
        val left = (0 until 4).flatMap { i -> listOf(36, 43, 48, 52).map { p -> n(p, i * 0.25, 0.25, hand = Hand.LEFT) } }
        val mid = EasyVersion.intermediate(tune + left, grid)
        val rh = mid.filter { it.hand == Hand.RIGHT }
        val lh = mid.filter { it.hand == Hand.LEFT }
        assertTrue("tune kept: ${rh.map { it.pitch }}", rh.map { it.pitch }.containsAll(listOf(72, 79)))
        assertTrue("fast flourish thinned", rh.size < tune.size)
        assertOneAtATime(rh)
        // Left hand: one chord per beat (0.5 s), two notes each, bass and top.
        val chords = lh.groupBy { it.start }
        assertEquals(2, chords.size)
        assertTrue(chords.values.all { c -> c.map { it.pitch }.sorted() == listOf(36, 52) })
    }

    @Test fun intermediateGivesATuneOnlyAPieceABass() {
        val tune = listOf(n(72, 0.0, 0.5), n(76, 0.5, 0.5), n(79, 1.0, 0.5), n(76, 1.5, 0.5), n(77, 2.0, 0.5), n(81, 2.5, 0.5), n(84, 3.0, 0.5), n(81, 3.5, 0.5))
        val mid = EasyVersion.intermediate(tune, grid)
        val lh = mid.filter { it.hand == Hand.LEFT }
        assertEquals(listOf(48, 53), lh.map { it.pitch }) // bars of 2 s: C E G E, then F A C A
    }

    @Test fun emptyStaysEmpty() = assertTrue(EasyVersion.make(emptyList(), grid).isEmpty())
}
