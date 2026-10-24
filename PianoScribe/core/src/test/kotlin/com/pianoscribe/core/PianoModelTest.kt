package com.pianoscribe.core

import com.pianoscribe.core.transcribe.PianoModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class PianoModelTest {
    private fun empty(frames: Int) = Array(frames) { FloatArray(PianoModel.KEYS) }

    /** A regression bump peaking at [centre] (in frames, may be fractional), like the model's onset output. */
    private fun bump(m: Array<FloatArray>, key: Int, centre: Double) {
        for (f in m.indices) {
            val d = (f - centre) / 2.0
            m[f][key] = maxOf(m[f][key], exp(-d * d).toFloat())
        }
    }

    @Test fun segmentsMatchTheReferenceEnframing() {
        assertEquals(1, PianoModel.segmentCount(16000 * 3))
        assertEquals(1, PianoModel.segmentCount(16000 * 10))
        // 25 s pads to 30 s: segments at 0, 5, 10, 15, 20 s.
        assertEquals(5, PianoModel.segmentCount(16000 * 25))
        val audio = FloatArray(16000 * 12) { it.toFloat() }
        val seg = FloatArray(PianoModel.SEGMENT_SAMPLES)
        PianoModel.fillSegment(audio, 1, seg)
        assertEquals(80000f, seg[0])
        assertEquals(0f, seg[PianoModel.SEGMENT_SAMPLES - 1])
    }

    @Test fun stitchKeepsThreeQuartersAtTheEndsAndTheMiddleHalfInside() {
        val n = PianoModel.SEGMENT_FRAMES
        fun seg(v: Float) = PianoModel.Segment(Array(n) { FloatArray(88) { v } }, empty(n), empty(n), empty(n))
        val out = PianoModel.stitch(listOf(seg(1f), seg(2f), seg(3f)))
        assertEquals(750 + 500 + 750, out.frames)
        assertEquals(1f, out.onset[749][0]); assertEquals(2f, out.onset[750][0]); assertEquals(3f, out.onset[1250][0])
    }

    @Test fun onsetShiftGivesSubFrameTiming() {
        val frames = 400
        val on = empty(frames); val off = empty(frames); val fr = empty(frames); val vel = empty(frames)
        // A4 (key 48): onset at frame 100.3, sounding until frame 180, offset bump at 180.
        bump(on, 48, 100.3); bump(off, 48, 180.0)
        for (f in 100..179) { fr[f][48] = 0.9f; vel[f][48] = 0.6f }
        val notes = PianoModel.notes(PianoModel.Output(on, off, fr, vel))
        assertEquals(1, notes.size)
        val n = notes[0]
        assertEquals(69, n.pitch)
        assertTrue("start ${n.start}", abs(n.start - 1.003) < 0.004)
        assertTrue("end ${n.end}", abs(n.end - 1.80) < 0.02)
        assertEquals((0.6f * 128).toInt(), n.velocity)
    }

    @Test fun repeatedNotesSplitAtEachOnset() {
        val frames = 400
        val on = empty(frames); val off = empty(frames); val fr = empty(frames); val vel = empty(frames)
        bump(on, 40, 50.0); bump(on, 40, 90.0)
        for (f in 50..150) { fr[f][40] = 0.9f; vel[f][40] = 0.5f }
        val notes = PianoModel.notes(PianoModel.Output(on, off, fr, vel))
        assertEquals(listOf(0.5, 0.9), notes.map { Math.round(it.start * 100) / 100.0 })
        assertTrue(notes[0].end <= notes[1].start + 1e-9)
    }
}
