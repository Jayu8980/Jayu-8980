package com.pianoscribe.core

import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.transcribe.ActivationAssembler
import com.pianoscribe.core.transcribe.Activations
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.NotePostProcessor
import com.pianoscribe.core.transcribe.TranscriptionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePostProcessorTest {
    private val P = BasicPitch.N_PITCHES

    private fun act(frames: Int, block: Activations.() -> Unit) = Activations(frames).apply(block)
    private fun Activations.note(t: Int, midi: Int, v: Float) { note[t * P + midi - 21] = v }
    private fun Activations.onset(t: Int, midi: Int, v: Float) { onset[t * P + midi - 21] = v }

    @Test fun windowGeometryMatchesReference() {
        assertEquals(43844, BasicPitch.AUDIO_N_SAMPLES)
        assertEquals(36164, BasicPitch.HOP_SIZE)
        assertEquals(3840, BasicPitch.PAD_START)
        assertEquals(142, BasicPitch.FRAMES_PER_WINDOW)
        assertEquals(11, BasicPitch.msToFrames(127.7))
        // 10 s of audio
        assertEquals(860, BasicPitch.outputFrames(220500))
        assertEquals(7, BasicPitch.windowCount(220500))
        assertEquals(0.0, BasicPitch.frameToTime(0), 1e-9)
        assertEquals(171 * 256 / 22050.0, BasicPitch.frameToTime(171), 1e-9)
        // window drift correction kicks in from frame 172
        assertTrue(BasicPitch.frameToTime(172) < 172 * 256 / 22050.0)
    }

    @Test fun fillWindowPadsStart() {
        val n = 50000
        val src = FloatArray(n) { (it + 1).toFloat() }
        val dst = FloatArray(BasicPitch.AUDIO_N_SAMPLES)
        BasicPitch.fillWindow(0, n, dst) { d, off, s, c -> System.arraycopy(src, s, d, off, c); c }
        assertEquals(0f, dst[3839]); assertEquals(1f, dst[3840])
        BasicPitch.fillWindow(1, n, dst) { d, off, s, c -> System.arraycopy(src, s, d, off, c); c }
        assertEquals((36164 - 3840 + 1).toFloat(), dst[0])
        // tail is zero padded
        assertEquals(0f, dst[BasicPitch.AUDIO_N_SAMPLES - 1])
    }

    @Test fun assemblerTrimsOverlap() {
        val asm = ActivationAssembler(220500)
        val note = FloatArray(172 * P) { (it / P).toFloat() / 1000f }
        asm.add(0, note, FloatArray(172 * P), FloatArray(172 * 264))
        asm.add(1, note, FloatArray(172 * P), FloatArray(172 * 264))
        val a = asm.activations
        assertEquals(15 / 1000f, a.note[0], 1e-6f)
        assertEquals(156 / 1000f, a.note[141 * P], 1e-6f)
        assertEquals(15 / 1000f, a.note[142 * P], 1e-6f)
    }

    @Test fun singleOnsetMakesOneNote() {
        val a = act(200) {
            for (t in 20 until 60) note(t, 60, 0.8f)
            onset(19, 60, 0.2f); onset(20, 60, 0.9f); onset(21, 60, 0.3f)
        }
        val notes = NotePostProcessor.process(a, TranscriptionSettings(melodiaTrick = false, inferOnsets = false), NoteIdGenerator())
        assertEquals(1, notes.size)
        val n = notes[0]
        assertEquals(60, n.pitch)
        assertEquals(BasicPitch.frameToTime(20), n.start, 1e-9)
        assertEquals(BasicPitch.frameToTime(60) - BasicPitch.frameToTime(20), n.duration, 1e-9)
        // velocity = round(127 * mean activation)
        assertEquals(102, n.velocity) // round(127 * 0.8)
    }

    @Test fun shortNotesAndLowOnsetsAreDropped() {
        val a = act(200) {
            for (t in 20 until 26) note(t, 64, 0.9f) // 6 frames < 11
            onset(20, 64, 0.9f)
            for (t in 100 until 160) note(t, 67, 0.9f)
            onset(100, 67, 0.4f) // below onset threshold
        }
        val notes = NotePostProcessor.process(a, TranscriptionSettings(melodiaTrick = false, inferOnsets = false), NoteIdGenerator())
        assertTrue(notes.isEmpty())
    }

    @Test fun energyToleranceBridgesGaps() {
        val a = act(300) {
            for (t in 10 until 50) note(t, 72, 0.8f)
            for (t in 55 until 90) note(t, 72, 0.8f) // 5-frame gap < tolerance 11
            onset(10, 72, 0.9f)
        }
        val notes = NotePostProcessor.process(a, TranscriptionSettings(melodiaTrick = false, inferOnsets = false), NoteIdGenerator())
        assertEquals(1, notes.size)
        assertEquals(BasicPitch.frameToTime(90), notes[0].end, 1e-9)
    }

    @Test fun melodiaRecoversNotesWithoutOnsets() {
        val a = act(300) { for (t in 100 until 150) note(t, 50, 0.7f) }
        val off = NotePostProcessor.process(a, TranscriptionSettings(melodiaTrick = false, inferOnsets = false), NoteIdGenerator())
        assertTrue(off.isEmpty())
        val on = NotePostProcessor.process(a, TranscriptionSettings(melodiaTrick = true, inferOnsets = false), NoteIdGenerator())
        assertEquals(1, on.size)
        assertEquals(50, on[0].pitch)
        assertEquals(BasicPitch.frameToTime(100), on[0].start, 1e-9)
        assertEquals(BasicPitch.frameToTime(149), on[0].end, 1e-9)
    }

    @Test fun pitchRangeIsRespected() {
        val a = act(200) {
            for (t in 20 until 80) { note(t, 30, 0.9f); note(t, 90, 0.9f) }
            onset(20, 30, 0.9f); onset(20, 90, 0.9f)
        }
        val notes = NotePostProcessor.process(a, TranscriptionSettings(minPitch = 40, maxPitch = 80), NoteIdGenerator())
        assertTrue(notes.isEmpty())
    }

    @Test fun inferredOnsetsSplitRepeatedNotes() {
        val a = act(300) {
            for (t in 20 until 60) note(t, 60, 0.9f)
            for (t in 80 until 120) note(t, 60, 0.9f)
            onset(20, 60, 0.9f) // second attack has no onset, inferred from the rise
        }
        val notes = NotePostProcessor.process(a, TranscriptionSettings(melodiaTrick = false, inferOnsets = true), NoteIdGenerator())
        assertEquals(2, notes.size)
    }
}
