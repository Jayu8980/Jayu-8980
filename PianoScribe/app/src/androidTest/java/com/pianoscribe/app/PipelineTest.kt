package com.pianoscribe.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pianoscribe.app.audio.AudioDecoder
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.compare.CompareEngine
import com.pianoscribe.app.data.AppSettings
import com.pianoscribe.app.data.ProjectEntity
import com.pianoscribe.app.export.ExportFormat
import com.pianoscribe.app.export.Exporter
import com.pianoscribe.app.playback.PlayMode
import com.pianoscribe.app.playback.PlaybackEngine
import com.pianoscribe.app.transcribe.TranscriptionEngine
import com.pianoscribe.core.midi.MidiReader
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.Performer
import com.pianoscribe.core.transcribe.AudioType
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * End-to-end pipeline on a real Android runtime: MediaCodec decoding, the TFLite model, Room,
 * the AudioTrack engine and every export, using the bundled test recording.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    companion object {
        private lateinit var pcm: File
        private lateinit var notes: List<Note>
        private lateinit var grid: TimeGrid
        private var transcribeMs = 0L

        private val container get() = (TestFixtures.app.applicationContext as PianoScribeApp).container

        @BeforeClass @JvmStatic
        fun transcribeOnce() = runBlocking<Unit> {
            pcm = File(TestFixtures.app.cacheDir, "test_decoded.f32")
            AudioDecoder(TestFixtures.app).decode(TestFixtures.wavUri(), pcm) {}
            val t0 = System.currentTimeMillis()
            val result = TranscriptionEngine(TestFixtures.app).transcribe(pcm, AppSettings(audioType = AudioType.SOLO_PIANO)) { _, _ -> }
            transcribeMs = System.currentTimeMillis() - t0
            notes = result.notes
            grid = result.grid
        }

        /** Expected notes found: same pitch, start within 80 ms. */
        fun recall(found: List<Note>): Double =
            TestFixtures.expected.count { e -> found.any { it.pitch == e.pitch && abs(it.start - e.start) < 0.08 } }.toDouble() / TestFixtures.expected.size
    }

    @Test fun decodesStereo44kToMono22k() {
        val samples = PcmFile.length(pcm)
        assertEquals(TestFixtures.DURATION_SEC * BasicPitch.SAMPLE_RATE, samples.toDouble(), BasicPitch.SAMPLE_RATE * 0.01)
        val audio = PcmFile.readAll(pcm)
        assertTrue("audio is not silent", audio.maxOf { abs(it) } > 0.3f)
    }

    @Test fun transcribesTheMelodyAndChords() {
        val r = recall(notes)
        assertTrue("found ${(r * 100).toInt()}% of the expected notes: ${notes.map { it.pitch to "%.2f".format(it.start) }}", r >= 0.8)
        assertTrue(notes.all { it.pitch in 21..108 && it.velocity in 1..127 && it.duration > 0 && it.confidence in 0f..1f })
        assertTrue("tempo ${grid.bpm}", grid.bpm in 40.0..250.0)
        android.util.Log.i("PianoScribeTest", "Transcribed ${TestFixtures.DURATION_SEC} s in $transcribeMs ms, ${notes.size} notes, recall $r")
    }

    @Test fun fullSongModeRunsAndKeepsTheNotes() = runBlocking<Unit> {
        val result = TranscriptionEngine(TestFixtures.app).transcribe(pcm, AppSettings(audioType = AudioType.FULL_SONG)) { _, _ -> }
        assertTrue(result.songMode)
        assertTrue("song-mode recall ${recall(result.notes)}", recall(result.notes) >= 0.7)
    }

    @Test fun autoModeTreatsSoloPianoAsSoloPiano() = runBlocking<Unit> {
        val result = TranscriptionEngine(TestFixtures.app).transcribe(pcm, AppSettings(audioType = AudioType.AUTO)) { _, _ -> }
        assertTrue("solo piano should not switch to song mode", !result.songMode)
    }

    @Test fun projectRoundTripsThroughRoom() = runBlocking<Unit> {
        val repo = container.repository
        val now = System.currentTimeMillis()
        val id = repo.create(ProjectEntity(name = "Pipeline test", createdAt = now, updatedAt = now, audioPath = "", durationSec = TestFixtures.DURATION_SEC,
            bpm = grid.bpm, beatsPerBar = 4, beatUnit = 4, gridOffset = grid.offset, splitPoint = 60, quantizeDivision = 0, keyName = null, noteCount = 0, source = "test"))
        repo.save(repo.load(id)!!.entity, notes)
        val loaded = repo.load(id)!!
        assertEquals(notes.sortedBy { it.id }, loaded.notes.sortedBy { it.id })
        repo.delete(id)
        assertEquals(null, repo.load(id))
    }

    @Test fun everyExportProducesAValidFile() = runBlocking<Unit> {
        val preset = container.soundFonts.preset(Instrument.PIANO)
        assertNotNull("bundled piano SoundFont loads", preset)
        val exporter = Exporter(TestFixtures.app)
        for (inst in listOf(Instrument.PIANO, Instrument.FLUTE)) {
            val part = Performer.perform(notes, grid, inst)
            val input = Exporter.Input("Test song", notes, grid, null, 0, part, inst, if (inst == Instrument.PIANO) preset else null, 0.6f)
            for (f in ExportFormat.entries) {
                val file = exporter.export(f, input)
                assertTrue("$f for $inst written", file.length() > 40)
                val head = file.readBytes().copyOf(8)
                when (f) {
                    ExportFormat.MIDI, ExportFormat.MIDI_PERFORMED -> {
                        val midi = MidiReader.read(file.readBytes())
                        assertEquals(3, midi.tracks)
                        val programs = midi.events.filter { it.status and 0xF0 == 0xC0 }.map { it.data[0].toInt() }.distinct()
                        assertEquals(listOf(if (f == ExportFormat.MIDI) 0 else inst.gmProgram), programs)
                    }
                    ExportFormat.WAV -> assertEquals("RIFF", String(head, 0, 4))
                    ExportFormat.PNG -> assertEquals(0x89.toByte(), head[0])
                    ExportFormat.NOTES_PDF, ExportFormat.ANALYSIS_PDF -> assertEquals("%PDF", String(head, 0, 4))
                    ExportFormat.MUSICXML -> assertTrue(file.readText().contains("<score-partwise"))
                    else -> assertTrue(file.readText().isNotBlank())
                }
            }
        }
    }

    @Test fun compareAndAutoFixRun() = runBlocking<Unit> {
        val ce = CompareEngine(TestFixtures.app, pcm, songMode = false) { container.soundFonts.preset(it) }
        val report = ce.compare(notes) { _, _ -> }
        assertTrue(report.confirmedShare in 0.0..1.0)
        assertEquals(notes.size, report.noteCount)
        assertTrue(report.soundMatch in 0.0..1.0)
        val fix = ce.autoFix(notes, report, NoteIdGenerator(10_000), 60) { _, _ -> }
        // Auto-fix may or may not change anything, but it must never lose the tune.
        assertTrue("recall after auto-fix ${recall(fix.notes)}", recall(fix.notes) >= 0.8)
        android.util.Log.i("PianoScribeTest", "Compare: confirmed ${report.confirmedShare}, timbre ${report.timbre}, fix ${fix.changes} accepted=${fix.accepted}")
    }

    @Test fun playbackEngineRunsEveryMode() = runBlocking<Unit> {
        val preset = container.soundFonts.preset(Instrument.PIANO)
        val engine = PlaybackEngine(preset, pcm, Instrument.PIANO)
        try {
            engine.duration = TestFixtures.DURATION_SEC
            engine.setNotes(Performer.perform(notes, grid, Instrument.PIANO))
            for (mode in PlayMode.entries) {
                engine.mode = mode
                engine.seek(0.5)
                engine.play()
                Thread.sleep(700)
                assertTrue("$mode is playing", engine.state.value.playing)
                engine.pause()
            }
            engine.speed = 0.5
            engine.setInstrument(Instrument.VIOLIN, null, 0.8f)
            engine.setNotes(Performer.perform(notes, grid, Instrument.VIOLIN))
            engine.play(); Thread.sleep(500); engine.pause()
            // At 1x, 1.6 s after starting at 1.0 s the position would be 2.6 s without the loop.
            engine.setInstrument(Instrument.PIANO, preset, 0.6f)
            engine.speed = 1.0
            engine.loop = 1.0..2.0
            engine.seek(1.0); engine.play(); Thread.sleep(1600)
            assertTrue("loop keeps position inside 1..2 s: ${engine.state.value.position}", engine.state.value.position in 0.9..2.1)
            engine.pause()
        } finally {
            engine.release()
        }
    }
}
