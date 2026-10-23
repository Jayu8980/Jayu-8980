package com.pianoscribe.app.transcribe

import android.content.Context
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.data.AppSettings
import com.pianoscribe.app.ml.BasicPitchModel
import com.pianoscribe.core.audio.Pcm
import com.pianoscribe.core.audio.PitchAccuracy
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.rhythm.Quantizer
import com.pianoscribe.core.rhythm.TempoDetector
import com.pianoscribe.core.theory.HandSplitter
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.transcribe.ActivationAssembler
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.NotePostProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

class SilentAudioException : IOException("The audio is silent (or almost silent), so there are no notes to find.")
class NoNotesException : IOException("No notes were found. Try lowering the note and onset thresholds in Settings, or use a clearer recording.")

data class TranscriptionResult(val notes: List<Note>, val grid: TimeGrid, val key: KeyDetector.Key?)

/** Progress callback: step label and 0..1 overall progress. */
typealias ProgressListener = (step: String, progress: Float) -> Unit

/**
 * Runs Basic Pitch over a mono 22050 Hz PCM file in overlapping windows (exactly like the
 * reference inference), then turns the activations into notes and adds tuning, tempo, hands and key.
 */
class TranscriptionEngine(private val context: Context) {
    suspend fun transcribe(pcm: File, settings: AppSettings, progress: ProgressListener): TranscriptionResult = withContext(Dispatchers.Default) {
        val length = PcmFile.length(pcm)
        progress("Checking audio", 0f)
        if (length < BasicPitch.SAMPLE_RATE / 4) throw IOException("The audio is too short to transcribe.")
        if (isSilent(pcm, length)) throw SilentAudioException()

        val assembler = ActivationAssembler(length)
        val windows = BasicPitch.windowCount(length)
        BasicPitchModel(context).use { model ->
            RandomAccessFile(pcm, "r").use { raf ->
                val window = FloatArray(BasicPitch.AUDIO_N_SAMPLES)
                for (w in 0 until windows) {
                    coroutineContext.ensureActive()
                    BasicPitch.fillWindow(w, length, window) { dst, off, src, count -> PcmFile.read(raf, src, dst, off, count) }
                    val out = model.run(window)
                    assembler.add(w, out.note, out.onset, out.contour)
                    progress("Listening for notes (${w + 1}/$windows)", 0.05f + 0.75f * (w + 1) / windows)
                }
            }
        }
        coroutineContext.ensureActive()
        progress("Extracting notes", 0.82f)
        val act = assembler.activations
        var notes = NotePostProcessor.process(act, settings.transcription(), NoteIdGenerator())
        if (notes.isEmpty()) throw NoNotesException()

        progress("Measuring tuning", 0.88f)
        val audio = PcmFile.readAll(pcm)
        notes = notes.map { n ->
            val c = PitchAccuracy.measureCents(audio, BasicPitch.SAMPLE_RATE, n.pitch, n.start, n.end)
                ?: run {
                    val f0 = (n.start * BasicPitch.ANNOTATIONS_FPS).toInt()
                    val f1 = (n.end * BasicPitch.ANNOTATIONS_FPS).toInt()
                    PitchAccuracy.centsFromContour(act.meanContour(f0, f1), n.pitch)
                }
            n.copy(cents = c)
        }

        coroutineContext.ensureActive()
        progress("Detecting tempo, hands and key", 0.94f)
        var grid = TempoDetector.detect(notes, settings.beatsPerBar)
        if (settings.pianoOnly) notes = Quantizer.quantize(notes, grid, settings.quantizeDivision)
        notes = HandSplitter.split(notes, settings.splitPoint)
        val key = KeyDetector.detect(notes)
        progress("Done", 1f)
        TranscriptionResult(notes, grid, key)
    }

    private fun isSilent(pcm: File, length: Int): Boolean {
        val chunk = FloatArray(BasicPitch.SAMPLE_RATE)
        var sum = 0.0
        var count = 0L
        var loudest = 0.0
        RandomAccessFile(pcm, "r").use { raf ->
            var p = 0
            while (p < length) {
                val n = PcmFile.read(raf, p, chunk, 0, minOf(chunk.size, length - p))
                if (n <= 0) break
                val r = Pcm.rms(chunk, 0, n)
                loudest = maxOf(loudest, r)
                sum += r * r * n
                count += n
                p += n
            }
        }
        val rmsDb = Pcm.toDb(kotlin.math.sqrt(sum / count.coerceAtLeast(1)))
        return rmsDb < -60 && Pcm.toDb(loudest) < -45
    }
}
