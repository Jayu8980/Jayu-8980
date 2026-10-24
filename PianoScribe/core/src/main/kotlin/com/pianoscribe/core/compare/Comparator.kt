package com.pianoscribe.core.compare

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.transcribe.Activations
import com.pianoscribe.core.transcribe.BasicPitch
import kotlin.math.roundToInt

/**
 * Compares the input recording with the MIDI turned back into sound. Both are run through the same
 * note model (Basic Pitch), so the comparison happens in "which piano key sounds when" space and
 * does not depend much on timbre: a flute recording and its piano rendering agree where the notes
 * agree.
 *
 * The score is a frame-level F1 over (frame, key) cells active above a threshold, with a small time
 * tolerance so a 20 ms difference is not counted as an error.
 */
object Comparator {
    private const val P = BasicPitch.N_PITCHES

    data class Options(
        val threshold: Float = 0.3f,
        /** Frames of timing tolerance either side (2 frames ~ 23 ms). */
        val tolerance: Int = 2,
        /** Shortest disagreement worth reporting, in frames (11 ~ 128 ms, the minimum note length). */
        val minRun: Int = 11,
        /** Gaps up to this many frames inside a run are bridged. */
        val bridge: Int = 2,
        /** Onset peaks at or above this count as a note start. */
        val onsetThreshold: Float = 0.4f,
        /** Onsets within this many frames on the same key match (3 frames ~ 35 ms). */
        val onsetTolerance: Int = 3,
    )

    /** A note start on one key, in model frames. */
    data class Onset(val pitch: Int, val frame: Int) {
        val time: Double get() = BasicPitch.frameToTime(frame)
    }

    /** A stretch of one key where the two sounds disagree. [strength] = mean activation of the side that has it. */
    data class Region(val pitch: Int, val startFrame: Int, val endFrame: Int, val strength: Float) {
        val start: Double get() = BasicPitch.frameToTime(startFrame)
        val end: Double get() = BasicPitch.frameToTime(endFrame)
    }

    data class Result(
        /** Share of what the MIDI plays that is really in the recording. */
        val precision: Double,
        /** Share of what the recording contains that the MIDI plays. */
        val recall: Double,
        val f1: Double,
        /** F1 per second of audio; NaN where both are silent. */
        val timeline: DoubleArray,
        /** In the recording but not in the MIDI. */
        val missing: List<Region>,
        /** In the MIDI but not in the recording. */
        val extra: List<Region>,
        /** Note starts: F1 of matched onsets, and the unmatched ones on each side. */
        val onsetF1: Double = 1.0,
        val missingOnsets: List<Onset> = emptyList(),
        val extraOnsets: List<Onset> = emptyList(),
    ) {
        /** Overall match: the mean of "same keys sounding" and "same note starts". */
        val overall: Double get() = (f1 + onsetF1) / 2
        val percent: Int get() = (overall * 100).roundToInt()
    }

    fun compare(input: Activations, render: Activations, o: Options = Options()): Result {
        val frames = minOf(input.frames, render.frames)
        val inOn = binarize(input, frames, o.threshold)
        val rOn = binarize(render, frames, o.threshold)
        val inCum = prefix(inOn, frames)
        val rCum = prefix(rOn, frames)
        var rActive = 0; var rHit = 0; var iActive = 0; var iHit = 0
        val perSecond = (frames / BasicPitch.ANNOTATIONS_FPS) + 1
        val secR = IntArray(perSecond); val secRHit = IntArray(perSecond); val secI = IntArray(perSecond); val secIHit = IntArray(perSecond)
        val missingCell = BooleanArray(frames * P)
        val extraCell = BooleanArray(frames * P)
        for (t in 0 until frames) {
            val sec = t / BasicPitch.ANNOTATIONS_FPS
            for (k in 0 until P) {
                val idx = t * P + k
                if (rOn[idx]) {
                    rActive++; secR[sec]++
                    if (anyNear(inCum, frames, t, k, o.tolerance)) { rHit++; secRHit[sec]++ } else extraCell[idx] = true
                }
                if (inOn[idx]) {
                    iActive++; secI[sec]++
                    if (anyNear(rCum, frames, t, k, o.tolerance)) { iHit++; secIHit[sec]++ } else missingCell[idx] = true
                }
            }
        }
        val precision = if (rActive == 0) (if (iActive == 0) 1.0 else 0.0) else rHit.toDouble() / rActive
        val recall = if (iActive == 0) 1.0 else iHit.toDouble() / iActive
        val timeline = DoubleArray(perSecond) { s ->
            if (secR[s] == 0 && secI[s] == 0) Double.NaN else {
                val p = if (secR[s] == 0) 1.0 else secRHit[s].toDouble() / secR[s]
                val r = if (secI[s] == 0) 1.0 else secIHit[s].toDouble() / secI[s]
                f1(p, r)
            }
        }
        val inOnsets = onsetPeaks(input, frames, o.onsetThreshold)
        val rOnsets = onsetPeaks(render, frames, o.onsetThreshold)
        val missingOnsets = inOnsets.filter { a -> rOnsets.none { it.pitch == a.pitch && kotlin.math.abs(it.frame - a.frame) <= o.onsetTolerance } }
        val extraOnsets = rOnsets.filter { a -> inOnsets.none { it.pitch == a.pitch && kotlin.math.abs(it.frame - a.frame) <= o.onsetTolerance } }
        val op = if (rOnsets.isEmpty()) 1.0 else 1 - extraOnsets.size.toDouble() / rOnsets.size
        val orr = if (inOnsets.isEmpty()) 1.0 else 1 - missingOnsets.size.toDouble() / inOnsets.size
        return Result(
            precision, recall, f1(precision, recall), timeline,
            missing = runs(missingCell, input, frames, o),
            extra = runs(extraCell, render, frames, o),
            onsetF1 = f1(op, orr),
            missingOnsets = missingOnsets,
            extraOnsets = extraOnsets,
        )
    }

    /** Strict local maxima in time of the onset posteriorgram, at or above [th]. */
    fun onsetPeaks(a: Activations, frames: Int, th: Float): List<Onset> {
        val out = ArrayList<Onset>()
        for (t in 1 until frames - 1) for (k in 0 until P) {
            val v = a.onset[t * P + k]
            if (v >= th && v > a.onset[(t - 1) * P + k] && v >= a.onset[(t + 1) * P + k]) out += Onset(k + BasicPitch.MIDI_OFFSET, t)
        }
        return out
    }

    private fun f1(p: Double, r: Double) = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)

    private fun binarize(a: Activations, frames: Int, th: Float) = BooleanArray(frames * P) { a.note[it] >= th }

    /** Per key, cumulative count of active frames: cum[k * (frames + 1) + t] = active frames before t. */
    private fun prefix(on: BooleanArray, frames: Int): IntArray {
        val cum = IntArray(P * (frames + 1))
        for (k in 0 until P) {
            val base = k * (frames + 1)
            for (t in 0 until frames) cum[base + t + 1] = cum[base + t] + if (on[t * P + k]) 1 else 0
        }
        return cum
    }

    private fun anyNear(cum: IntArray, frames: Int, t: Int, k: Int, tol: Int): Boolean {
        val base = k * (frames + 1)
        val a = maxOf(0, t - tol)
        val b = minOf(frames, t + tol + 1)
        return cum[base + b] - cum[base + a] > 0
    }

    /** Groups flagged cells into per-key runs, bridging short gaps, keeping runs of at least minRun frames. */
    private fun runs(cells: BooleanArray, source: Activations, frames: Int, o: Options): List<Region> {
        val out = ArrayList<Region>()
        for (k in 0 until P) {
            var t = 0
            while (t < frames) {
                if (!cells[t * P + k]) { t++; continue }
                val start = t
                var last = t
                var sum = 0.0
                var n = 0
                var gap = 0
                while (t < frames && gap <= o.bridge) {
                    if (cells[t * P + k]) { last = t; gap = 0; sum += source.note[t * P + k]; n++ } else gap++
                    t++
                }
                val len = last - start + 1
                if (len >= o.minRun) out += Region(k + BasicPitch.MIDI_OFFSET, start, last + 1, (sum / n).toFloat())
            }
        }
        return out.sortedBy { it.startFrame }
    }
}

/**
 * Corrects a transcription by comparing it with the recording:
 * 1. a note whose attack the recording does not have, right after a note of the same key, is merged
 *    into it (one held note had been split);
 * 2. every note is judged by the [SpectralJudge]: it stays only if the resynthesis with it sounds
 *    closer to the recording than without it (this removes notes the model imagined);
 * 3. stretches the recording clearly has but the MIDI lacks become candidate notes, kept only if
 *    the same judge says they make the sound closer.
 * Verified notes are never touched. The caller accepts the result only if the whole-piece
 * spectral difference goes down.
 */
object AutoFix {
    data class Options(
        /** A note must improve the local spectral match by at least this much to stay. */
        val keepMargin: Double = 0.002,
        /** Missing stretches need this mean activation to be tried as a new note. */
        val addStrength: Float = 0.45f,
        val splitPitch: Int = Note.MIDDLE_C,
        val allowAdd: Boolean = true,
    )

    data class Changes(val removed: Int = 0, val added: Int = 0, val merged: Int = 0) {
        val total: Int get() = removed + added + merged
    }

    fun refine(
        notes: List<Note>,
        cmp: Comparator.Result,
        judge: SpectralJudge,
        ids: NoteIdGenerator,
        o: Options = Options(),
        onProgress: (Float) -> Unit = {},
    ): Pair<List<Note>, Changes> {
        val (merged, mergeCount) = mergeSplitNotes(notes, cmp)
        onProgress(0.1f)
        // Judge every note against all the others.
        val kept = ArrayList<Note>(merged.size)
        var removed = 0
        for ((i, n) in merged.withIndex()) {
            if (n.verified || judge.contribution(n, merged.filter { it !== n }) > o.keepMargin) kept += n else removed++
            if (i % 16 == 0) onProgress(0.1f + 0.7f * i / merged.size.coerceAtLeast(1))
        }
        var added = 0
        if (o.allowAdd) {
            for (r in cmp.missing) {
                if (r.strength < o.addStrength) continue
                if (kept.any { it.pitch == r.pitch && it.start < r.end && r.start < it.end }) continue
                val candidate = Note(
                    ids.next(), r.pitch, r.start, r.end - r.start,
                    (127 * r.strength).roundToInt().coerceIn(1, 127), r.strength.coerceIn(0f, 1f),
                    if (r.pitch >= o.splitPitch) Hand.RIGHT else Hand.LEFT,
                )
                if (judge.contribution(candidate, kept) > o.keepMargin) { kept += candidate; added++ }
            }
        }
        onProgress(1f)
        return kept.sortedWith(compareBy({ it.start }, { it.pitch })) to Changes(removed, added, mergeCount)
    }

    /** Step 1: joins a note to the previous same-key note when the recording has no new attack there. */
    fun mergeSplitNotes(notes: List<Note>, cmp: Comparator.Result, tol: Int = 3): Pair<List<Note>, Int> {
        val list = notes.sortedBy { it.start }.toMutableList()
        var merged = 0
        for (eo in cmp.extraOnsets) {
            val i = list.indexOfFirst { it.pitch == eo.pitch && kotlin.math.abs(BasicPitch.timeToFrame(it.start) - eo.frame) <= tol && !it.verified }
            if (i < 0) continue
            val n = list[i]
            val p = list.indexOfLast {
                it.pitch == n.pitch && it !== n && it.start < n.start && !it.verified &&
                    kotlin.math.abs(BasicPitch.timeToFrame(it.end) - BasicPitch.timeToFrame(n.start)) <= 4
            }
            if (p < 0) continue
            list[p] = list[p].copy(duration = maxOf(list[p].end, n.end) - list[p].start, velocity = maxOf(list[p].velocity, n.velocity))
            list.removeAt(i)
            merged++
        }
        return list.sortedWith(compareBy({ it.start }, { it.pitch })) to merged
    }
}
