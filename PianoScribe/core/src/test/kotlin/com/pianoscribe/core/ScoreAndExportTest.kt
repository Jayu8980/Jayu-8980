package com.pianoscribe.core

import com.pianoscribe.core.analysis.AnalysisTable
import com.pianoscribe.core.analysis.StepFilter
import com.pianoscribe.core.analysis.Steps
import com.pianoscribe.core.edit.EditHistory
import com.pianoscribe.core.edit.NoteOps
import com.pianoscribe.core.midi.MusicXmlWriter
import com.pianoscribe.core.midi.ScoreBuilder
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.Fingering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreAndExportTest {
    private val grid = TimeGrid(bpm = 120.0)
    private fun n(id: Long, p: Int, s: Double, d: Double, h: Hand = Hand.RIGHT, c: Float = 0.9f) = Note(id, p, s, d, 90, c, h)

    @Test fun splitDurations() {
        assertEquals(listOf(4), ScoreBuilder.splitDuration(4))
        assertEquals(listOf(4, 1), ScoreBuilder.splitDuration(5))
        assertEquals(listOf(6, 1), ScoreBuilder.splitDuration(7))
        assertEquals(listOf(12, 2), ScoreBuilder.splitDuration(14))
    }

    @Test fun measuresFillWithRestsAndTieAcrossBarlines() {
        // Quarter on beat 1, then a half note starting on beat 4 that crosses the barline.
        val notes = listOf(n(1, 72, 0.0, 0.5), n(2, 74, 1.5, 1.0), n(3, 48, 0.0, 2.0, Hand.LEFT))
        val m = ScoreBuilder.build(notes, grid)
        assertEquals(2, m.size)
        for (meas in m) {
            assertEquals(16, meas.treble.sumOf { it.durDiv })
            assertEquals(16, meas.bass.sumOf { it.durDiv })
        }
        val t0 = m[0].treble
        assertEquals(listOf(72), t0[0].pitches); assertEquals(4, t0[0].durDiv)
        assertTrue(t0[1].isRest)
        assertTrue(t0.last().tieStart); assertEquals(listOf(74), t0.last().pitches)
        assertTrue(m[1].treble.first().tieStop)
        assertEquals("whole", m[0].bass[0].type)
    }

    @Test fun musicXmlIsWellFormed() {
        val notes = listOf(n(1, 61, 0.0, 0.5), n(2, 65, 0.0, 0.5), n(3, 41, 0.0, 1.0, Hand.LEFT))
        val xml = MusicXmlWriter.write(notes, grid, "Test & <Song>", 0)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("score-partwise", doc.documentElement.tagName)
        assertEquals(1, doc.getElementsByTagName("measure").length)
        assertEquals(1, doc.getElementsByTagName("chord").length)
        assertEquals(2, doc.getElementsByTagName("staves").item(0).textContent.toInt())
        assertTrue(xml.contains("<step>C</step><alter>1</alter><octave>4</octave>"))
        assertTrue(xml.contains("Test &amp; &lt;Song&gt;"))
    }

    @Test fun stepsGroupChordsAndFilter() {
        val notes = listOf(n(1, 60, 0.0, 0.5), n(2, 64, 0.02, 0.5), n(3, 67, 0.03, 0.5), n(4, 62, 1.0, 0.5, c = 0.3f), n(5, 40, 2.0, 0.5, Hand.LEFT))
        val steps = Steps.build(notes)
        assertEquals(3, steps.size)
        assertTrue(steps[0].isChord)
        assertEquals(1, Steps.find(steps, 0, 1, StepFilter.LOW_CONFIDENCE))
        assertEquals(2, Steps.find(steps, 0, 1, StepFilter.LEFT_HAND))
        assertEquals(0, Steps.find(steps, 2, -1, StepFilter.CHORD))
        assertEquals(null, Steps.find(steps, 2, 1, StepFilter.ALL))
        assertEquals(1, Steps.indexAt(steps, 1.2))
    }

    @Test fun analysisCsv() {
        val notes = listOf(n(1, 60, 0.0, 0.5), n(2, 64, 0.0, 0.5), n(3, 67, 0.0, 0.5), n(4, 62, 1.0, 0.25))
        val rows = AnalysisTable.rows(notes, grid)
        assertEquals(4, rows.size)
        assertEquals("C", rows[0].chord)
        assertEquals("1:3", rows[3].barBeat)
        assertEquals("eighth", rows[3].noteValue)
        val csv = AnalysisTable.csv(rows).lines()
        assertTrue(csv[0].startsWith("index,note,sargam"))
        assertEquals("1,C4,Sa,60,261.63,0.000,1:1,0.500,quarter,C,90,RH,", csv[1].substringBefore(",0,90,yes").let { it.substring(0, it.lastIndexOf(',') + 1) })
    }

    @Test fun fingeringScale() {
        val scale = listOf(60, 62, 64, 65, 67, 69, 71, 72).mapIndexed { i, p -> n(i.toLong() + 1, p, i * 0.5, 0.5) }
        val f = Fingering.suggest(scale)
        assertEquals(8, f.size)
        assertTrue(f.values.all { it in 1..5 })
        // Starts on the thumb and uses a thumb-under somewhere in the octave.
        assertEquals(1, f[1L])
        assertTrue((2L..8L).any { f[it] == 1 })
        val chord = listOf(n(1, 60, 0.0, 1.0), n(2, 64, 0.0, 1.0), n(3, 67, 0.0, 1.0))
        val fc = Fingering.suggest(chord)
        assertEquals(setOf(1, 3, 5), fc.values.toSet())
    }

    @Test fun editOpsAndHistory() {
        val ids = NoteIdGenerator(10)
        val base = listOf(n(1, 60, 0.0, 1.0), n(2, 62, 1.0, 1.0))
        val h = EditHistory(base)
        h.push(NoteOps.transpose(h.current, 12))
        assertEquals(72, h.current[0].pitch)
        h.push(NoteOps.split(h.current, 1, 0.5, ids))
        assertEquals(3, h.current.size)
        h.push(NoteOps.mergeWithNext(h.current, 1))
        assertEquals(2, h.current.size)
        assertEquals(1.0, h.current.first { it.id == 1L }.duration, 1e-9)
        h.undo(); h.undo()
        assertEquals(2, h.current.size); assertEquals(72, h.current[0].pitch)
        h.redo()
        assertEquals(3, h.current.size)
        h.undo(); h.undo()
        assertEquals(base, h.current)
        assertFalse(h.canUndo)
        val slower = NoteOps.changeTempo(base, 120.0, 60.0)
        assertEquals(2.0, slower[1].start, 1e-9); assertEquals(2.0, slower[1].duration, 1e-9)
        assertEquals(108, NoteOps.transpose(listOf(n(1, 105, 0.0, 1.0)), 12)[0].pitch)
    }
}
