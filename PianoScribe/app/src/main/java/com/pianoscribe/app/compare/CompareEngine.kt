package com.pianoscribe.app.compare

import android.content.Context
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.ml.ModelRunner
import com.pianoscribe.core.audio.Hpss
import com.pianoscribe.core.compare.AutoFix
import com.pianoscribe.core.compare.Comparator
import com.pianoscribe.core.compare.SpectralJudge
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.synth.OfflineRenderer
import com.pianoscribe.core.synth.Sf2Preset
import com.pianoscribe.core.transcribe.Activations
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** What a comparison found. */
data class CompareReport(
    /** Share of MIDI notes that make the resynthesis sound closer to the recording (an estimate of how many notes are right). */
    val confirmedShare: Double,
    val noteCount: Int,
    /** Notes the recording does not confirm (shown highlighted; auto-fix removes them unless verified). */
    val unconfirmed: Set<Long>,
    /** How much of the recording's sound the MIDI explains, 0..1. Only comparable between versions of the same song. */
    val soundMatch: Double,
    /** Per-second sound match (NaN = near silent). */
    val timeline: DoubleArray,
    /** Notes the recording seems to contain that the MIDI lacks, already checked by ear (spectrally). */
    val possiblyMissing: List<Comparator.Region>,
    /** Resynthesis timbre picked as the closest to the recording. */
    val timbre: Instrument,
    /** The raw model-space comparison, kept for auto-fix. */
    internal val modelComparison: Comparator.Result,
)

data class FixResult(val notes: List<Note>, val changes: AutoFix.Changes, val before: CompareReport, val after: CompareReport, val accepted: Boolean)

/**
 * Compares a transcription with its recording and corrects it (see core/compare): the MIDI is
 * turned back into sound and judged against the recording, both by the note model and by the
 * spectrum. Heavy work runs on Dispatchers.Default; the recording's model output is computed once.
 */
class CompareEngine(
    private val context: Context,
    private val audioFile: File,
    private val songMode: Boolean,
    private val presetFor: suspend (Instrument) -> Sf2Preset?,
) {
    private var audio: FloatArray? = null
    private var inputAct: Activations? = null
    private var judge: SpectralJudge? = null
    private var pianoPreset: Sf2Preset? = null

    /** Progress: step label and 0..1. */
    suspend fun compare(notes: List<Note>, progress: (String, Float) -> Unit): CompareReport = withContext(Dispatchers.Default) {
        prepare(notes, progress)
        report(notes, progress, 0.45f, 1f)
    }

    /**
     * One or two rounds of correction; a round is kept only if the whole-piece spectral difference
     * goes down. [before] must be a report for [notes].
     */
    suspend fun autoFix(notes: List<Note>, before: CompareReport, ids: NoteIdGenerator, splitPitch: Int, progress: (String, Float) -> Unit): FixResult =
        withContext(Dispatchers.Default) {
            prepare(notes, progress)
            val j = judge!!
            var current = notes
            var report = before
            var err = j.error(current)
            var total = AutoFix.Changes()
            var anyAccepted = false
            for (round in 1..2) {
                coroutineContext.ensureActive()
                val base = (round - 1) * 0.5f
                val (fixed, ch) = AutoFix.refine(current, report.modelComparison, j, ids, AutoFix.Options(splitPitch = splitPitch)) { p ->
                    progress("Fixing notes (round $round)", base + 0.3f * p)
                }
                if (ch.total == 0) break
                val e2 = j.error(fixed)
                if (e2 >= err) break
                current = fixed; err = e2; anyAccepted = true
                total = AutoFix.Changes(total.removed + ch.removed, total.added + ch.added, total.merged + ch.merged)
                report = report(current, progress, base + 0.3f, base + 0.5f)
            }
            FixResult(current, total, before, report, anyAccepted)
        }

    private suspend fun prepare(notes: List<Note>, progress: (String, Float) -> Unit) {
        if (audio == null) {
            progress("Reading the recording", 0f)
            val raw = PcmFile.readAll(audioFile)
            // Full-song projects were transcribed from the drum-free version; compare against it too.
            audio = if (songMode) Hpss().separate(raw) { p -> progress("Removing drums", 0.05f * p) }.harmonic else raw
        }
        val a = audio!!
        if (inputAct == null) {
            inputAct = ModelRunner.run(context, a) { d, t -> progress("Listening to the recording ($d/$t)", 0.05f + 0.25f * d / t) }
        }
        if (pianoPreset == null) pianoPreset = presetFor(Instrument.PIANO)
        if (judge == null) {
            progress("Matching the sound of the recording", 0.32f)
            val presets = HashMap<Instrument, Sf2Preset?>()
            for (inst in SpectralJudge.POLY_CANDIDATES + SpectralJudge.MONO_CANDIDATES) presets[inst] = presetFor(inst)
            judge = SpectralJudge.bestMatch(a, BasicPitch.SAMPLE_RATE, notes, { presets[it] })
        } else judge!!.calibrate(notes)
        coroutineContext.ensureActive()
    }

    private suspend fun report(notes: List<Note>, progress: (String, Float) -> Unit, from: Float, to: Float): CompareReport {
        val a = audio!!
        val j = judge!!
        val span = to - from
        // 1. The note model listens to the MIDI turned back into sound (piano: the model knows it best).
        val rendered = FloatArray(a.size)
        var p = 0
        OfflineRenderer.renderMono(notes.map(PlayedNote::of), Instrument.PIANO, pianoPreset, BasicPitch.SAMPLE_RATE, a.size) { b, n ->
            System.arraycopy(b, 0, rendered, p, n); p += n
        }
        val renderAct = ModelRunner.run(context, rendered) { d, t -> progress("Listening to the MIDI ($d/$t)", from + span * 0.5f * d / t) }
        val cmp = Comparator.compare(inputAct!!, renderAct)
        // 2. Each note is judged by ear: does the resynthesis sound closer to the recording with it?
        val unconfirmed = HashSet<Long>()
        for ((i, n) in notes.withIndex()) {
            if (i % 16 == 0) { coroutineContext.ensureActive(); progress("Checking notes by ear (${i + 1}/${notes.size})", from + span * (0.5f + 0.4f * i / notes.size.coerceAtLeast(1))) }
            if (!n.verified && j.contribution(n, notes.filter { it !== n }) <= AutoFix.Options().keepMargin) unconfirmed += n.id
        }
        // 3. Missing stretches that would make the sound closer.
        val missing = cmp.missing.filter { r ->
            r.strength >= AutoFix.Options().addStrength &&
                notes.none { it.pitch == r.pitch && it.start < r.end && r.start < it.end } &&
                j.contribution(Note(-1, r.pitch, r.start, r.end - r.start, (127 * r.strength).toInt().coerceIn(1, 127), r.strength), notes) > AutoFix.Options().keepMargin
        }
        progress("Done", to)
        return CompareReport(
            confirmedShare = if (notes.isEmpty()) 0.0 else 1 - unconfirmed.size.toDouble() / notes.size,
            noteCount = notes.size,
            unconfirmed = unconfirmed,
            soundMatch = j.soundMatch(notes),
            timeline = j.soundMatchTimeline(notes),
            possiblyMissing = missing,
            timbre = j.timbre,
            modelComparison = cmp,
        )
    }
}
