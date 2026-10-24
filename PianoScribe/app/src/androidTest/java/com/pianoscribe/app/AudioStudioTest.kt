package com.pianoscribe.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pianoscribe.app.audio.AudioDecoder
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.data.AppSettings
import com.pianoscribe.app.studio.AudioStudio
import com.pianoscribe.app.studio.Separation
import com.pianoscribe.app.transcribe.TranscriptionEngine
import com.pianoscribe.core.audio.AudioCleaner
import com.pianoscribe.core.audio.CleanOptions
import com.pianoscribe.core.audio.Separator
import com.pianoscribe.core.transcribe.AudioType
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The audio studio and clean-up on a real Android runtime, with the bundled stereo test recording. */
@RunWith(AndroidJUnit4::class)
class AudioStudioTest {
    companion object {
        private lateinit var draft: Draft
        private const val SR = BasicPitch.SAMPLE_RATE

        @BeforeClass @JvmStatic
        fun decodeOnce() = runBlocking<Unit> {
            val ctx = TestFixtures.app
            val mono = File(ctx.cacheDir, "studio_test.f32")
            val l = File(ctx.cacheDir, "studio_test_l.f32")
            val r = File(ctx.cacheDir, "studio_test_r.f32")
            val res = AudioDecoder(ctx).decode(TestFixtures.wavUri(), mono, l to r) {}
            assertTrue("stereo file gives left/right channels", res.stereo)
            draft = Draft(mono, "Studio test", "Test", res.samples, left = l, right = r)
        }

        fun recall(notes: List<com.pianoscribe.core.model.Note>, shift: Double = 0.0): Double =
            TestFixtures.expected.count { e -> notes.any { it.pitch == e.pitch && abs(it.start - (e.start + shift)) < 0.08 } }.toDouble() / TestFixtures.expected.size
    }

    @Test fun stereoChannelsLineUpWithTheMonoFile() {
        assertEquals(PcmFile.length(draft.file), PcmFile.length(draft.left!!))
        assertEquals(PcmFile.length(draft.file), PcmFile.length(draft.right!!))
        // The test file's right channel is the left at 90%: it is all "centre".
        assertTrue(Separator.centreShare(PcmFile.readAll(draft.left!!), PcmFile.readAll(draft.right!!)) > 0.9)
    }

    @Test fun cleanRecordingIsLeftAlone() {
        val report = AudioCleaner.analyze(PcmFile.readAll(draft.file), SR)
        assertFalse("no hum: ${report.humHz} ${report.humHarmonics}", report.hasHum)
        assertFalse(report.suggested().removeHum)
    }

    @Test fun humIsRemovedBeforeConverting() = runBlocking<Unit> {
        val x = PcmFile.readAll(draft.file)
        val hummed = File(TestFixtures.app.cacheDir, "hummed.f32")
        PcmFile.Writer(hummed).use { w ->
            w.write(FloatArray(x.size) { i -> x[i] + (0.05 * (1..5).sumOf { h -> sin(2 * PI * 50 * h * i / SR + h) / h }).toFloat() })
        }
        val result = TranscriptionEngine(TestFixtures.app).transcribe(hummed, AppSettings(audioType = AudioType.SOLO_PIANO)) { _, _ -> }
        assertTrue("clean-up: ${result.cleanup}", result.cleanup.any { "50 Hz hum" in it })
        assertTrue("recall with hum removed ${recall(result.notes)}", recall(result.notes) >= 0.8)
    }

    @Test fun studioEditsUndoAndExport() = runBlocking<Unit> {
        val st = AudioStudio(TestFixtures.app, draft)
        assertTrue(st.stereoAvailable)
        val n0 = draft.lengthSamples

        // Stereo centre on version 0; then it is no longer available.
        st.separate(Separation.REMOVE_VOCALS, AppSettings())
        assertEquals(1, st.index)
        assertFalse(st.stereoAvailable)
        assertEquals(n0, st.current.lengthSamples)
        st.undo()
        assertTrue(st.stereoAvailable)

        st.cut(SR, 2 * SR)
        assertEquals(n0 - SR, st.current.lengthSamples)
        st.clean(CleanOptions(noiseReduction = 0.5f, echoReduction = 0.3f, removeHum = true, removeRumble = true, normalize = true))
        val peak = PcmFile.readAll(st.current.file).maxOf { abs(it) }
        assertEquals(0.89f, peak, 0.02f)
        st.tempo(1.25)
        assertEquals((n0 - SR) / 1.25, st.current.lengthSamples.toDouble(), SR * 0.05)
        st.pitch(2)
        assertEquals(4, st.history.size)
        st.undo(); st.undo()
        assertEquals(2, st.history.size)
        st.redo()
        assertEquals(3, st.history.size)
        // A new change after undo drops the undone pitch change.
        st.gain(0, SR, -6.0)
        assertFalse(st.canRedo)

        val wav = st.exportWav("Studio test")
        val head = wav.readBytes().copyOf(4)
        assertEquals("RIFF", String(head))
        assertEquals(44L + st.current.lengthSamples * 2L, wav.length())
        st.discardHistory()
        assertTrue(st.current.file.exists())
        assertTrue("the original is kept", draft.file.exists())
    }

    @Test fun separationsRun() = runBlocking<Unit> {
        val st = AudioStudio(TestFixtures.app, draft)
        val settings = AppSettings(audioType = AudioType.SOLO_PIANO, autoClean = false)
        for (what in listOf(Separation.REMOVE_DRUMS, Separation.DRUMS_ONLY, Separation.MELODY_ONLY, Separation.BASS_ONLY, Separation.REMOVE_MELODY)) {
            st.separate(what, settings)
            assertEquals(draft.lengthSamples, st.current.lengthSamples)
            st.undo()
        }
        // The melody alone still transcribes to the melody notes (72 and up in the test piece).
        st.separate(Separation.MELODY_ONLY, settings)
        val result = TranscriptionEngine(TestFixtures.app).transcribe(st.current.file, settings) { _, _ -> }
        val notes = result.notes
        val melody = TestFixtures.expected.filter { it.pitch >= 72 }
        val found = melody.count { e -> notes.any { it.pitch == e.pitch && abs(it.start - e.start) < 0.08 } }
        assertTrue("melody notes kept: $found of ${melody.size} (piano model used: ${result.pianoModel})", found >= melody.size * 0.75)
        val chordNotes = notes.count { it.pitch < 60 }
        android.util.Log.i("PianoScribeTest", "Melody only: ${notes.size} notes, $found/${melody.size} melody, $chordNotes below middle C")
    }
}
