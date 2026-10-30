package com.pianoscribe.core

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.score.Tablature
import com.pianoscribe.core.score.TabWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TablatureTest {
    private var id = 0L
    private fun n(p: Int, beat: Double, beats: Double = 1.0) = Note(id++, p, beat * 0.5, beats * 0.5, 80, 1f, Hand.RIGHT)
    private val grid = TimeGrid(bpm = 120.0)

    @Test fun everyNoteIsPlayableAtItsPitch() {
        val notes = listOf(n(40, 0.0), n(52, 1.0), n(64, 2.0), n(76, 3.0), n(88, 4.0))
        val tab = Tablature.arrange(notes)
        for (t in tab) assertEquals("string ${t.string} fret ${t.fret}", t.pitch, Tablature.TUNING[t.string - 1] + t.fret)
        assertTrue(tab.all { it.fret in 0..Tablature.FRETS && it.string in 1..6 })
    }

    @Test fun anOpenEChordIsTheOpenShape() {
        // E major in its open position: E2 B2 E3 G#3 B3 E4.
        val chord = listOf(40, 47, 52, 56, 59, 64).map { n(it, 0.0, 4.0) }
        val tab = Tablature.arrange(chord).sortedBy { it.string }
        assertEquals(listOf(0, 0, 1, 2, 2, 0), tab.map { it.fret }) // e B G D A E
        assertEquals((1..6).toList(), tab.map { it.string })
    }

    @Test fun chordsUseDistinctStringsWithinAHandSpan() {
        val chords = listOf(listOf(48, 52, 55, 60), listOf(53, 57, 60, 65), listOf(55, 59, 62, 67), listOf(45, 52, 57, 60, 64))
        val notes = chords.flatMapIndexed { i, c -> c.map { p -> n(p, i * 2.0, 2.0) } }
        val tab = Tablature.arrange(notes)
        for (c in tab.groupBy { it.start }.values) {
            assertEquals(c.size, c.map { it.string }.distinct().size)
            val fretted = c.filter { it.fret > 0 }.map { it.fret }
            if (fretted.isNotEmpty()) assertTrue("span ${fretted.max() - fretted.min()}", fretted.max() - fretted.min() <= 4)
        }
    }

    @Test fun outOfRangeNotesMoveByOctaves() {
        val tab = Tablature.arrange(listOf(n(28, 0.0), n(100, 1.0)))
        assertEquals(listOf(40, 76), tab.map { it.pitch }) // the top is E4 + 20 frets = C6 (84)
    }

    @Test fun asciiAndMusicXml() {
        val notes = listOf(n(64, 0.0), n(67, 1.0), n(69, 2.0, 2.0))
        val tab = Tablature.arrange(notes)
        val txt = TabWriter.ascii(tab, grid, "Riff")
        val lines = txt.lines().filter { it.length > 2 && it[1] == '|' }
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.length == lines[0].length })
        assertTrue(lines[0].startsWith("e|0"))
        val xml = TabWriter.musicXml(tab, grid, "Riff")
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            .newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(3, Regex("<technical><string>").findAll(xml).count())
        assertTrue(xml.contains("<sign>TAB</sign>"))
    }
}
