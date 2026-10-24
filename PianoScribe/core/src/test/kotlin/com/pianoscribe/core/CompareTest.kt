package com.pianoscribe.core

import com.pianoscribe.core.compare.AutoFix
import com.pianoscribe.core.compare.Comparator
import com.pianoscribe.core.compare.SpectralJudge
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.synth.OfflineRenderer
import com.pianoscribe.core.transcribe.Activations
import com.pianoscribe.core.transcribe.BasicPitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareTest {
    private val P = BasicPitch.N_PITCHES
    private val rate = 22050

    private fun act(frames: Int, block: Activations.() -> Unit) = Activations(frames).apply(block)
    private fun Activations.hold(midi: Int, from: Int, to: Int, v: Float = 0.8f) {
        for (t in from until to) note[t * P + midi - 21] = v
        onset[from * P + midi - 21] = 0.9f
    }

    @Test fun timeToFrameInvertsFrameToTime() {
        for (f in listOf(0, 1, 100, 171, 172, 173, 500, 1000, 5000)) assertEquals(f, BasicPitch.timeToFrame(BasicPitch.frameToTime(f)))
    }

    @Test fun identicalSoundsMatchFully() {
        val a = act(300) { hold(60, 10, 80); hold(64, 100, 200) }
        val r = Comparator.compare(a, a)
        assertEquals(1.0, r.f1, 1e-9)
        assertEquals(1.0, r.onsetF1, 1e-9)
        assertEquals(100, r.percent)
        assertTrue(r.missing.isEmpty() && r.extra.isEmpty())
    }

    @Test fun smallTimingDifferencesAreTolerated() {
        val a = act(300) { hold(60, 10, 80) }
        val b = act(300) { hold(60, 12, 82) }
        assertTrue(Comparator.compare(a, b).f1 > 0.97)
    }

    @Test fun reportsMissingAndExtraStretches() {
        val input = act(300) { hold(60, 10, 80); hold(67, 100, 160) }
        val render = act(300) { hold(60, 10, 80); hold(72, 200, 260) }
        val r = Comparator.compare(input, render)
        assertEquals(listOf(67), r.missing.map { it.pitch })
        assertEquals(100, r.missing.single().startFrame)
        assertEquals(listOf(72), r.extra.map { it.pitch })
        assertTrue(r.f1 < 0.7)
        assertEquals(1, r.missingOnsets.size); assertEquals(1, r.extraOnsets.size)
    }

    @Test fun mergesANoteSplitInTwo() {
        // The recording has one attack; the MIDI re-strikes the same key right after the first note.
        val input = act(300) { hold(60, 10, 100) }
        val render = act(300) { hold(60, 10, 50); hold(60, 50, 100) }
        val cmp = Comparator.compare(input, render)
        val notes = listOf(
            Note(1, 60, BasicPitch.frameToTime(10), BasicPitch.frameToTime(50) - BasicPitch.frameToTime(10), 80, 0.8f),
            Note(2, 60, BasicPitch.frameToTime(50), BasicPitch.frameToTime(100) - BasicPitch.frameToTime(50), 80, 0.8f),
        )
        val (merged, count) = AutoFix.mergeSplitNotes(notes, cmp)
        assertEquals(1, count)
        assertEquals(1, merged.size)
        assertEquals(BasicPitch.frameToTime(100), merged.single().end, 1e-9)
    }

    private fun recording(notes: List<Note>, inst: Instrument, seconds: Double): FloatArray {
        val out = FloatArray((seconds * rate).toInt()); var p = 0
        OfflineRenderer.renderMono(notes.map(PlayedNote::of), inst, null, rate, out.size) { b, n -> System.arraycopy(b, 0, out, p, n); p += n }
        return out
    }

    private val real = listOf(
        Note(1, 60, 0.2, 0.8, 90, 0.9f), Note(2, 64, 1.2, 0.8, 90, 0.9f), Note(3, 67, 2.2, 0.8, 90, 0.9f), Note(4, 72, 3.2, 0.8, 90, 0.9f),
    )

    @Test fun judgeKeepsRealNotesAndRejectsImaginedOnes() {
        val audio = recording(real, Instrument.GUITAR, 4.5)
        val judge = SpectralJudge(audio, rate, null, Instrument.GUITAR).apply { calibrate(real) }
        for (n in real) assertTrue("real ${n.pitch}", judge.contribution(n, real.filter { it !== n }) > 0.002)
        val ghost = Note(9, 83, 1.2, 0.5, 60, 0.4f)
        assertTrue("ghost", judge.contribution(ghost, real) < 0)
        // And the whole-piece score agrees.
        assertTrue(judge.error(real) < judge.error(real + ghost))
        assertTrue(judge.soundMatch(real) > judge.soundMatch(real.drop(1)))
    }

    @Test fun bestMatchPicksTheRecordingsTimbre() {
        val audio = recording(real, Instrument.ORGAN, 4.5)
        val judge = SpectralJudge.bestMatch(audio, rate, real, { null })
        assertEquals(Instrument.ORGAN, judge.timbre)
    }

    @Test fun autoFixRemovesGhostsAndAddsMissedNotes() {
        val audio = recording(real, Instrument.ELECTRIC_PIANO, 4.5)
        val ghost = Note(9, 83, 1.2, 0.5, 60, 0.4f)
        val transcribed = real.filter { it.id != 3L } + ghost // missed note 3, imagined a ghost
        val judge = SpectralJudge(audio, rate, null, Instrument.ELECTRIC_PIANO).apply { calibrate(transcribed) }
        // The model-space comparison would report note 3 as missing; build that result directly.
        val f0 = BasicPitch.timeToFrame(2.2)
        val missing = Comparator.Region(67, f0, BasicPitch.timeToFrame(3.0), 0.7f)
        val cmp = Comparator.Result(0.8, 0.8, 0.8, DoubleArray(0), listOf(missing), emptyList())
        val (fixed, ch) = AutoFix.refine(transcribed, cmp, judge, NoteIdGenerator(100))
        assertEquals(1, ch.removed)
        assertEquals(1, ch.added)
        assertEquals(setOf(60, 64, 67, 72), fixed.map { it.pitch }.toSet())
        assertTrue(judge.error(fixed) < judge.error(transcribed))
        // Verified notes are never removed, even if the sound disagrees.
        val (keep, _) = AutoFix.refine(transcribed.map { if (it.id == 9L) it.copy(verified = true) else it }, cmp, judge, NoteIdGenerator(100))
        assertTrue(keep.any { it.id == 9L })
    }
}
