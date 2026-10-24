package com.pianoscribe.app.transcribe

import android.content.Context
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.data.AppSettings
import com.pianoscribe.app.ml.ModelRunner
import com.pianoscribe.app.ml.PianoModelRunner
import com.pianoscribe.core.audio.AudioCleaner
import com.pianoscribe.core.audio.Hpss
import com.pianoscribe.core.audio.Pcm
import com.pianoscribe.core.audio.PitchAccuracy
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.rhythm.Quantizer
import com.pianoscribe.core.rhythm.TempoDetector
import com.pianoscribe.core.theory.HandSplitter
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.transcribe.AudioType
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.NotePostProcessor
import com.pianoscribe.core.transcribe.PianoEnsemble
import com.pianoscribe.core.transcribe.PianoModel
import com.pianoscribe.core.transcribe.SongCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

class SilentAudioException : IOException("The audio is silent (or almost silent), so there are no notes to find.")
class NoNotesException(songMode: Boolean = false) : IOException(
    if (songMode) "No notes were found, even after removing the drums. Try a section where the melody is clear, or lower the note and onset thresholds in Settings."
    else "No notes were found. If this is a song with drums or vocals, set the audio type to \"Full song\" on the trim screen. You can also lower the note and onset thresholds in Settings.",
)

/**
 * @param songMode true when the audio was treated as a full song (drums removed, clean-up applied)
 * @param retriedSensitive true when too few notes were found and lower thresholds were used
 * @param cleanup what was filtered out before listening ("50 Hz hum removed"), if anything
 * @param pianoModel true when the high-resolution piano model made the notes (with Basic Pitch)
 */
data class TranscriptionResult(
    val notes: List<Note>,
    val grid: TimeGrid,
    val key: KeyDetector.Key?,
    val songMode: Boolean = false,
    val retriedSensitive: Boolean = false,
    val cleanup: List<String> = emptyList(),
    val pianoModel: Boolean = false,
)

/** Progress callback: step label and 0..1 overall progress. */
typealias ProgressListener = (step: String, progress: Float) -> Unit

/**
 * Runs Basic Pitch over a mono 22050 Hz PCM file in overlapping windows (exactly like the
 * reference inference), then turns the activations into notes and adds tuning, tempo, hands and key.
 *
 * Full songs take an extra path: drums are removed first (harmonic/percussive separation) so the
 * model hears tones rather than hits, and the notes are cleaned up afterwards (overtone ghosts,
 * faint background notes, too many simultaneous notes). "Auto" decides from a quick separation of
 * a few excerpts.
 */
class TranscriptionEngine(private val context: Context) {
    companion object {
        /** Longer recordings use Basic Pitch alone, to keep memory in bounds. */
        const val PIANO_MODEL_MAX_SEC = 15 * 60
    }

    suspend fun transcribe(input: File, settings: AppSettings, progress: ProgressListener): TranscriptionResult = withContext(Dispatchers.Default) {
        val length = PcmFile.length(input)
        progress("Checking audio", 0f)
        if (length < BasicPitch.SAMPLE_RATE / 4) throw IOException("The audio is too short to transcribe.")
        if (isSilent(input, length)) throw SilentAudioException()

        // 0. Mains hum and rumble confuse the model (measured: hum costs 10-15% of notes), so
        // filter them out when the analysis finds them. Nothing else is changed.
        var pcm = input
        val cleanup = ArrayList<String>()
        if (settings.autoClean) {
            progress("Checking for hum and rumble", 0.005f)
            val audio = PcmFile.readAll(input)
            val report = AudioCleaner.analyze(audio, BasicPitch.SAMPLE_RATE)
            val options = report.suggested().copy(normalize = false)
            if (!options.isEmpty) {
                progress(if (report.hasHum) "Removing ${report.humHz} Hz hum" else "Removing rumble", 0.008f)
                val cleaned = AudioCleaner.clean(audio, BasicPitch.SAMPLE_RATE, options, report)
                pcm = File(context.cacheDir, "cleaned.f32")
                PcmFile.Writer(pcm).use { it.write(cleaned) }
                if (options.removeHum) cleanup += "${report.humHz} Hz hum removed"
                if (options.removeRumble) cleanup += "rumble removed"
            }
        }
        coroutineContext.ensureActive()

        // 1. Decide how to treat the audio and, for songs, remove the drums.
        val songMode = when (settings.audioType) {
            AudioType.SOLO_PIANO -> false
            AudioType.FULL_SONG -> true
            AudioType.AUTO -> {
                progress("Checking for drums and band", 0.01f)
                looksLikeSong(pcm, length)
            }
        }
        coroutineContext.ensureActive()
        val analysis: File
        if (songMode) {
            progress("Full song: removing drums", 0.03f)
            val audio = PcmFile.readAll(pcm)
            val harmonic = Hpss().separate(audio) { p -> progress("Full song: removing drums", 0.03f + 0.17f * p) }.harmonic
            analysis = File(context.cacheDir, "analysis.f32")
            PcmFile.Writer(analysis).use { it.write(harmonic) }
        } else {
            analysis = pcm
        }
        coroutineContext.ensureActive()

        // 2. The models. Solo piano also gets the high-resolution piano model, which leads.
        val usePiano = !songMode && settings.highAccuracyPiano && PianoModelRunner.isAvailable(context) &&
            length <= PIANO_MODEL_MAX_SEC * BasicPitch.SAMPLE_RATE
        val modelStart = if (songMode) 0.2f else 0.05f
        val modelEnd = if (usePiano) 0.3f else 0.8f
        val act = RandomAccessFile(analysis, "r").use { raf ->
            ModelRunner.run(context, length, { dst, off, src, count -> PcmFile.read(raf, src, dst, off, count) }) { done, total ->
                progress("Listening for notes ($done/$total)", modelStart + (modelEnd - modelStart) * done / total)
            }
        }
        val pianoOut = if (usePiano) {
            PianoModelRunner.run(context, PcmFile.readAll(analysis)) { done, total ->
                progress("High-accuracy piano model ($done/$total)", 0.3f + 0.5f * done / total)
            }
        } else null
        coroutineContext.ensureActive()

        // 3. Notes, with one more sensitive pass if a busy mix gave almost nothing.
        progress("Extracting notes", 0.82f)
        val durationSec = length.toDouble() / BasicPitch.SAMPLE_RATE
        var notes = NotePostProcessor.process(act, settings.transcription(), NoteIdGenerator())
        var retried = false
        if (SongCleanup.tooFewNotes(notes.size, durationSec)) {
            progress("Few notes found: listening more closely", 0.84f)
            val sensitive = NotePostProcessor.process(act, SongCleanup.sensitive(settings.transcription()), NoteIdGenerator())
            if (sensitive.size > notes.size) { notes = sensitive; retried = true }
        }
        if (pianoOut != null) {
            progress("Combining the piano model with Basic Pitch", 0.85f)
            val ids = NoteIdGenerator()
            val piano = PianoModel.notes(pianoOut, ids = ids)
                .filter { it.start < durationSec && it.pitch in settings.minPitch..settings.maxPitch }
                .map { if (it.end > durationSec) it.copy(duration = maxOf(0.02, durationSec - it.start)) else it }
            // The piano model leads; Basic Pitch is the second opinion that weeds out its overtone ghosts.
            if (piano.isNotEmpty()) { notes = PianoEnsemble.combine(piano, notes); retried = false }
        }
        if (songMode) {
            progress("Full song: cleaning up notes", 0.86f)
            val cleaned = SongCleanup.apply(notes, SongCleanup.Options(splitPitch = settings.splitPoint))
            // Never let the clean-up empty a transcription that found something.
            if (cleaned.isNotEmpty()) notes = cleaned
        }
        if (notes.isEmpty()) throw NoNotesException(songMode)

        progress("Measuring tuning", 0.88f)
        val audio = PcmFile.readAll(analysis)
        notes = notes.map { n ->
            val c = PitchAccuracy.measureCents(audio, BasicPitch.SAMPLE_RATE, n.pitch, n.start, n.end)
                ?: run {
                    val f0 = (n.start * BasicPitch.ANNOTATIONS_FPS).toInt()
                    val f1 = (n.end * BasicPitch.ANNOTATIONS_FPS).toInt()
                    PitchAccuracy.centsFromContour(act.meanContour(f0, f1), n.pitch)
                }
            n.copy(cents = c)
        }
        if (analysis != pcm) analysis.delete()
        if (pcm != input) pcm.delete()

        coroutineContext.ensureActive()
        progress("Detecting tempo, hands and key", 0.94f)
        val grid = TempoDetector.detect(notes, settings.beatsPerBar)
        if (settings.pianoOnly) notes = Quantizer.quantize(notes, grid, settings.quantizeDivision)
        notes = HandSplitter.split(notes, settings.splitPoint)
        val key = KeyDetector.detect(notes)
        progress("Done", 1f)
        TranscriptionResult(notes, grid, key, songMode, retried, cleanup, pianoModel = pianoOut != null)
    }

    /**
     * Runs the harmonic/percussive separation on up to three 12 s excerpts (start, middle, end)
     * and reports a full song when drums make up a clear share of the energy.
     */
    private fun looksLikeSong(pcm: File, length: Int): Boolean {
        val excerpt = 12 * BasicPitch.SAMPLE_RATE
        val starts = if (length <= excerpt * 3) listOf(0) else listOf(0, length / 2 - excerpt / 2, length - excerpt)
        var weighted = 0.0
        var total = 0
        for (s in starts) {
            val n = minOf(excerpt, length - s)
            val buf = FloatArray(n)
            PcmFile.read(pcm, s, buf, 0, n)
            weighted += Hpss().separate(buf).percussiveRatio * n
            total += n
        }
        return total > 0 && weighted / total > SongCleanup.PERCUSSIVE_SONG_THRESHOLD
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
