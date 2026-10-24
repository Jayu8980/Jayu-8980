package com.pianoscribe.core

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.rhythm.Quantizer
import com.pianoscribe.core.rhythm.TempoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuantizerTempoTest {
    private fun n(id: Long, p: Int, s: Double, d: Double) = Note(id, p, s, d, 90, 0.9f)

    @Test fun quantizesToSixteenths() {
        val grid = TimeGrid(bpm = 120.0) // 16th = 0.125 s
        val q = Quantizer.quantize(listOf(n(1, 60, 0.13, 0.22), n(2, 62, 0.49, 0.03)), grid, 16)
        assertEquals(0.125, q[0].start, 1e-9)
        assertEquals(0.25, q[0].duration, 1e-9)
        assertEquals(0.5, q[1].start, 1e-9)
        assertEquals(0.125, q[1].duration, 1e-9) // minimum one grid step
    }

    @Test fun quantizesToEighthsWithOffset() {
        val grid = TimeGrid(bpm = 100.0, offset = 0.1) // eighth = 0.3 s
        val q = Quantizer.quantize(listOf(n(1, 60, 0.38, 0.55)), grid, 8)
        assertEquals(0.4, q[0].start, 1e-9)
        assertEquals(0.6, q[0].duration, 1e-9)
    }

    @Test fun mergesDuplicatesAndTrimsOverlaps() {
        val grid = TimeGrid(bpm = 120.0)
        val q = Quantizer.quantize(listOf(n(1, 60, 0.0, 0.5), n(2, 60, 0.01, 0.2), n(3, 60, 0.26, 0.5)), grid, 8)
        assertEquals(2, q.size)
        assertEquals(0.25, q[0].duration, 1e-9)
        assertEquals(0.25, q[1].start, 1e-9)
    }

    @Test fun detectsTempoOfSteadyPulse() {
        for (bpm in listOf(72.0, 96.0, 120.0, 140.0)) {
            val period = 60 / bpm
            val rnd = Random(bpm.toInt())
            val notes = (0 until 48).map { i ->
                Note(i.toLong(), 60 + (i % 5), 0.37 + i * period + rnd.nextDouble(-0.01, 0.01), period * 0.8, if (i % 4 == 0) 110 else 70, 0.9f)
            }
            val g = TempoDetector.detect(notes)
            assertEquals("bpm $bpm", bpm, g.bpm, bpm * 0.03)
            val phaseErr = Math.abs(((g.offset - 0.37) / period) - Math.round((g.offset - 0.37) / period))
            assertTrue("phase $phaseErr", phaseErr < 0.1)
        }
    }
}
