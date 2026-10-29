package com.pianoscribe.core

import com.pianoscribe.core.midi.ScoreBuilder
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.score.Engraver
import com.pianoscribe.core.score.Mark
import com.pianoscribe.core.score.Smufl
import com.pianoscribe.core.score.Spelling
import com.pianoscribe.core.theory.KeyDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngraverTest {
    private val grid = TimeGrid(bpm = 120.0) // a beat is 0.5 s
    private var id = 0L
    private fun n(p: Int, beat: Double, beats: Double, h: Hand = if (p >= 60) Hand.RIGHT else Hand.LEFT, vel: Int = 80) =
        Note(id++, p, beat * 0.5, beats * 0.5, vel, 1f, h)

    private fun key(pc: Int, minor: Boolean = false) = KeyDetector.Key(pc, minor, 1.0)
    private fun name(midi: Int, k: KeyDetector.Key?) = Spelling.spell(midi, k).let { s -> "${s.letter}${when (s.alter) { 1 -> "#"; -1 -> "b"; else -> "" }}${s.octave}" }

    @Test fun spelling() {
        assertEquals(listOf("C#4", "Eb4", "F#4", "G#4", "Bb4"), listOf(61, 63, 66, 68, 70).map { name(it, key(0)) })
        // G major: F# from the signature, F natural stays F.
        assertEquals(listOf("F#4", "F4"), listOf(66, 65).map { name(it, key(7)) })
        // F major: Bb; flat keys spell black keys as flats.
        assertEquals(listOf("Bb4", "Db4"), listOf(70, 61).map { name(it, key(5)) })
        // Minor keys raise the 7th: G# in A minor, C# in D minor, B natural in C minor.
        assertEquals("G#4", name(68, key(9, true)))
        assertEquals("C#4", name(61, key(2, true)))
        assertEquals("B3", name(59, key(0, true)))
        // Gb major (the detector's key for pitch class 6) has Cb, which belongs to the octave above: it sounds as B4.
        assertEquals("Cb5", name(71, key(6)))
    }

    @Test fun unevenEighthsAreWrittenAsEighths() {
        // Eighths played a little early and late: snapped to the eighth grid, not to 16ths.
        val beats = listOf(0.0, 0.46, 1.04, 1.55, 1.98)
        assertEquals(listOf(0, 2, 4, 6, 8), ScoreBuilder.snapOnsets(beats, 4).toList())
        // Real 16ths stay 16ths.
        assertEquals(listOf(0, 1, 2, 3), ScoreBuilder.snapOnsets(listOf(0.0, 0.26, 0.49, 0.76), 4).toList())
    }

    @Test fun valuesShowTheBeat() {
        // 4/4: a half note from beat 2 is two tied quarters; an eighth across a beat is split.
        assertEquals(listOf(4 to 4, 8 to 4), ScoreBuilder.metricSplit(4, 12, 16, 4, 4))
        assertEquals(listOf(2 to 2, 4 to 2), ScoreBuilder.metricSplit(2, 6, 16, 4, 4))
        assertEquals(listOf(0 to 8), ScoreBuilder.metricSplit(0, 8, 16, 4, 4))
        // 6/8: a dotted quarter per group.
        val sixEight = TimeGrid(bpm = 120.0, beatsPerBar = 6, beatUnit = 8)
        assertEquals(6, ScoreBuilder.groupDiv(sixEight))
        assertEquals(listOf(0 to 12), ScoreBuilder.metricSplit(0, 12, 12, 6, 6)) // a full bar: dotted half
        assertEquals(listOf(4 to 2, 6 to 4), ScoreBuilder.metricSplit(4, 10, 12, 6, 6)) // shown at the middle of the bar
    }

    @Test fun staccatoNotesAreWrittenAtFullValue() {
        val notes = listOf(n(72, 0.0, 0.3), n(74, 1.0, 0.3), n(76, 2.0, 2.0))
        val treble = ScoreBuilder.build(notes, grid)[0].treble
        assertEquals(listOf(4, 4, 8), treble.map { it.durDiv })
        assertEquals(listOf(true, true, false), treble.map { it.staccato })
    }

    private fun glyphs(score: com.pianoscribe.core.score.EngravedScore, c: Char) = score.systems.flatMap { it.marks }.filterIsInstance<Mark.Glyph>().filter { it.code == c }

    @Test fun engravesAGrandStaff() {
        // Two bars: four eighths, a quarter and a half in the right hand; a whole note, then an empty bar, in the left.
        val notes = listOf(n(72, 0.0, 0.5), n(74, 0.5, 0.5), n(76, 1.0, 0.5), n(77, 1.5, 0.5), n(79, 2.0, 1.0), n(81, 3.0, 1.0),
            n(84, 4.0, 4.0), n(48, 0.0, 4.0))
        val score = Engraver.engrave(notes, grid, key(0), 100f, Engraver.Options(title = "Test"))
        assertEquals(1, score.systems.size)
        val marks = score.systems[0].marks
        assertEquals(1, glyphs(score, Smufl.G_CLEF).size)
        assertEquals(1, glyphs(score, Smufl.F_CLEF).size)
        // 4/4 on both staves.
        assertEquals(4, glyphs(score, (Smufl.TIME_SIG_0.code + 4).toChar()).size)
        // Eighths beamed by beat: two beams, no flags.
        assertEquals(2, marks.count { it is Mark.Beam })
        assertTrue(glyphs(score, Smufl.FLAG_8_UP).isEmpty() && glyphs(score, Smufl.FLAG_8_DOWN).isEmpty())
        // The empty bass bar gets a whole-bar rest.
        assertEquals(1, glyphs(score, Smufl.REST_WHOLE).size)
        // Every note can be tapped.
        assertEquals(notes.map { it.id }.toSet(), score.systems.flatMap { it.hits }.flatMap { it.noteIds }.toSet())
        assertTrue(score.header.any { it is Mark.Text && it.text == "Test" })
    }

    @Test fun keySignatureAndAccidentalsPerMeasure() {
        // D major (2 sharps): F#, then F natural twice in one bar (one natural sign), F# again in the next bar.
        val notes = listOf(n(66, 0.0, 1.0), n(65, 1.0, 1.0), n(65, 2.0, 1.0), n(67, 3.0, 1.0), n(66, 4.0, 4.0))
        val score = Engraver.engrave(notes, grid, key(2), 100f)
        assertEquals(4, glyphs(score, Smufl.SHARP).size) // 2 per staff in the signature, none on the notes
        assertEquals(1, glyphs(score, Smufl.NATURAL).size)
    }

    @Test fun musicXmlMatchesTheScore() {
        // D major: F# needs no accidental, F natural gets one natural sign per bar; a staccato quarter.
        val notes = listOf(n(66, 0.0, 0.3), n(65, 1.0, 1.0), n(65, 2.0, 1.0), n(62, 3.0, 1.0))
        val xml = com.pianoscribe.core.midi.MusicXmlWriter.write(notes, grid, "D", 2, key(2))
        assertEquals(1, Regex("<accidental>natural</accidental>").findAll(xml).count())
        assertTrue(!xml.contains("<accidental>sharp</accidental>"))
        assertTrue(xml.contains("<step>F</step><alter>1</alter><octave>4</octave>"))
        assertTrue(xml.contains("<staccato/>"))
    }

    @Test fun tiesAcrossTheBarline() {
        val notes = listOf(n(72, 2.0, 4.0)) // beats 3-6: tied over the bar
        val score = Engraver.engrave(notes, grid, key(0), 100f)
        assertEquals(1, score.systems.flatMap { it.marks }.count { it is Mark.Tie })
    }

    @Test fun longScoresBreakIntoSystems() {
        val notes = (0 until 64).map { n(60 + it % 12, it.toDouble(), 1.0) }
        val score = Engraver.engrave(notes, grid, key(0), 80f)
        assertTrue("systems ${score.systems.size}", score.systems.size in 3..8)
        for (s in score.systems) assertTrue(s.marks.filterIsInstance<Mark.Line>().all { it.x2 <= 80.01f })
        // Systems stack downwards and the playhead columns run left to right in time.
        assertTrue(score.systems.zipWithNext().all { (a, b) -> b.top >= a.bottom })
        for (s in score.systems) assertTrue(s.columns.zipWithNext().all { (a, b) -> b.first > a.first && b.second > a.second })
    }
}
