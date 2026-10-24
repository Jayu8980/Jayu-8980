package com.pianoscribe.app.studio

import android.content.Context
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.data.AppSettings
import com.pianoscribe.app.transcribe.TranscriptionEngine
import com.pianoscribe.core.audio.AudioCleaner
import com.pianoscribe.core.audio.AudioEdit
import com.pianoscribe.core.audio.AudioReport
import com.pianoscribe.core.audio.CleanOptions
import com.pianoscribe.core.audio.Hpss
import com.pianoscribe.core.audio.Separator
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** What "Separate" can pull out of the audio. */
enum class Separation(val label: String, val needsStereo: Boolean = false, val usesNotes: Boolean = false) {
    REMOVE_DRUMS("Remove drums"),
    DRUMS_ONLY("Drums only"),
    REMOVE_VOCALS("Remove vocals (stereo centre)", needsStereo = true),
    VOCALS_ONLY("Vocals only (stereo centre)", needsStereo = true),
    MELODY_ONLY("Melody only", usesNotes = true),
    BASS_ONLY("Bass line only", usesNotes = true),
    ACCOMPANIMENT_ONLY("Accompaniment only", usesNotes = true),
    REMOVE_MELODY("Remove the melody", usesNotes = true),
}

/**
 * Edits a draft recording: clean-up, separation and editing, each one a new version on disk so
 * every step can be undone and compared with the one before.
 *
 * Version 0 is the decoded file itself; stereo separation works on its left/right channels, so
 * it is available only on version 0 (before any other change).
 */
class AudioStudio(context: Context, original: Draft) {
    private val dir = File(context.cacheDir, "studio").apply { mkdirs() }
    private val appContext = context.applicationContext
    private val base = original
    private val versions = arrayListOf(Version(original.file, "Original", original.lengthSamples))

    class Version(val file: File, val label: String, val samples: Int)

    var index = 0
        private set

    val sampleRate: Int get() = BasicPitch.SAMPLE_RATE
    val current: Draft get() = versions[index].let { v ->
        if (index == 0) base else base.copy(file = v.file, lengthSamples = v.samples, left = null, right = null)
    }
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < versions.size - 1
    val stereoAvailable: Boolean get() = index == 0 && base.isStereo
    /** The version before the current one, to hear what the last change did. */
    val previousFile: File? get() = if (index > 0) versions[index - 1].file else null
    val lastChange: String? get() = if (index > 0) versions[index].label else null
    /** Labels of all changes up to the current version. */
    val history: List<String> get() = versions.subList(1, index + 1).map { it.label }

    fun undo(): Draft { if (canUndo) index--; return current }
    fun redo(): Draft { if (canRedo) index++; return current }

    private fun read(): FloatArray = PcmFile.readAll(versions[index].file)

    /** Runs [op] on the current audio and keeps the result as a new version. */
    private suspend fun commit(label: String, op: suspend (FloatArray) -> FloatArray): Draft = withContext(Dispatchers.Default) {
        val out = op(read())
        require(out.isNotEmpty()) { "Nothing would be left of the audio." }
        // A new change discards anything that was undone.
        while (versions.size > index + 1) {
            val v = versions.removeAt(versions.size - 1)
            if (v.file != base.file) v.file.delete()
        }
        // Unique names: the draft this studio started from may itself be an earlier studio version.
        val file = File.createTempFile("version", ".f32", dir)
        withContext(Dispatchers.IO) { PcmFile.Writer(file).use { it.write(out) } }
        versions += Version(file, label, out.size)
        index = versions.size - 1
        current
    }

    suspend fun analyze(): AudioReport = withContext(Dispatchers.Default) { AudioCleaner.analyze(read(), sampleRate) }

    suspend fun clean(options: CleanOptions, onProgress: (Float) -> Unit = {}): Draft {
        val parts = buildList {
            if (options.removeHum) add("hum")
            if (options.removeRumble) add("rumble")
            if (options.noiseReduction > 0f) add("noise ${Math.round(options.noiseReduction * 100)}%")
            if (options.echoReduction > 0f) add("echo ${Math.round(options.echoReduction * 100)}%")
            if (options.normalize) add("level")
        }
        return commit("Clean up: " + parts.joinToString(", ")) { x -> AudioCleaner.clean(x, sampleRate, options, AudioCleaner.analyze(x, sampleRate), onProgress) }
    }

    /**
     * Separates part of the audio. Note-based parts first transcribe the current audio with
     * [settings] to find where each part's notes are.
     */
    suspend fun separate(what: Separation, settings: AppSettings, onProgress: (String, Float) -> Unit = { _, _ -> }): Draft {
        if (what.needsStereo) require(stereoAvailable) { "Stereo separation needs the original stereo file, before any other change." }
        return commit(what.label) { x ->
            when (what) {
                Separation.REMOVE_DRUMS, Separation.DRUMS_ONLY -> {
                    val h = Hpss().separate(x) { onProgress("Separating tones from hits", it) }.harmonic
                    // The masks add up to one, so the hits are what the tones leave.
                    if (what == Separation.REMOVE_DRUMS) h else FloatArray(x.size) { x[it] - h[it] }
                }
                Separation.REMOVE_VOCALS, Separation.VOCALS_ONLY -> {
                    val l = PcmFile.readAll(base.left!!)
                    val r = PcmFile.readAll(base.right!!)
                    Separator.centre(l, r, keepCentre = what == Separation.VOCALS_ONLY) { onProgress("Separating the stereo centre", it) }
                }
                else -> {
                    val notes = TranscriptionEngine(appContext)
                        .transcribe(versions[index].file, settings) { step, p -> onProgress("Finding the notes: $step", p * 0.8f) }
                        .notes
                    val parts = Separator.parts(notes)
                    val target = when (what) {
                        Separation.BASS_ONLY -> parts.bass
                        Separation.ACCOMPANIMENT_ONLY -> parts.accompaniment
                        else -> parts.melody
                    }
                    require(target.isNotEmpty()) { "No notes were found for that part." }
                    val others = notes.filter { n -> target.none { it === n } }
                    Separator.isolate(x, sampleRate, target, others, keep = what != Separation.REMOVE_MELODY) { onProgress("Separating the part", 0.8f + 0.2f * it) }
                }
            }
        }
    }

    suspend fun cut(from: Int, to: Int) = commit("Cut ${span(from, to)}") { AudioEdit.cut(it, from, to, sampleRate) }
    suspend fun crop(from: Int, to: Int) = commit("Keep ${span(from, to)}") { AudioEdit.crop(it, from, to, sampleRate) }
    suspend fun silence(from: Int, to: Int) = commit("Silence ${span(from, to)}") { AudioEdit.silence(it, from, to, sampleRate) }
    suspend fun fadeIn(from: Int, to: Int) = commit("Fade in ${span(from, to)}") { AudioEdit.withFadeIn(it, from, to) }
    suspend fun fadeOut(from: Int, to: Int) = commit("Fade out ${span(from, to)}") { AudioEdit.withFadeOut(it, from, to) }
    suspend fun gain(from: Int, to: Int, db: Double) = commit("Volume ${if (db > 0) "+" else ""}${Math.round(db)} dB ${span(from, to)}") { AudioEdit.gain(it, from, to, db, sampleRate) }
    suspend fun normalize(from: Int, to: Int) = commit("Normalise ${span(from, to)}") { AudioEdit.normalize(it, from, to, sampleRate) }
    suspend fun tempo(factor: Double) = commit("Tempo ${Math.round(factor * 100)}%") { AudioEdit.changeTempo(it, factor, sampleRate) }
    suspend fun pitch(semitones: Int) = commit("Pitch ${if (semitones > 0) "+" else ""}$semitones semitones") { AudioEdit.pitchShift(it, semitones.toDouble(), sampleRate) }

    private fun span(from: Int, to: Int) = "%.1f-%.1f s".format(from.toDouble() / sampleRate, to.toDouble() / sampleRate)

    /** Writes the current version as a 16-bit WAV into the shareable exports folder. */
    suspend fun exportWav(title: String): File = withContext(Dispatchers.IO) {
        val dirOut = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "audio" }
        val file = File(dirOut, "$safe-edited.wav")
        BufferedOutputStream(FileOutputStream(file)).use { AudioEdit.writeWav(it, read(), sampleRate) }
        file
    }

    /** Deletes every version except the current one (which the draft now points at). */
    fun discardHistory() {
        val keep = versions[index].file
        for (v in versions) if (v.file != keep && v.file != base.file) v.file.delete()
    }
}
