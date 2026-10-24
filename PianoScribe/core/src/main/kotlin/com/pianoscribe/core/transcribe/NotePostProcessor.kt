package com.pianoscribe.core.transcribe

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import kotlin.math.roundToInt

/** User-tunable transcription settings. */
data class TranscriptionSettings(
    val onsetThreshold: Float = 0.5f,
    val frameThreshold: Float = 0.3f,
    val minNoteLengthMs: Double = 127.7,
    val minPitch: Int = Note.MIN_PIANO,
    val maxPitch: Int = Note.MAX_PIANO,
    val inferOnsets: Boolean = true,
    val melodiaTrick: Boolean = true,
    val energyTolerance: Int = 11,
)

/** A note event in model frames before conversion to seconds. */
data class NoteEvent(val startFrame: Int, val endFrame: Int, val pitch: Int, val amplitude: Float, val onsetStrength: Float)

/**
 * Port of basic_pitch.note_creation.output_to_notes_polyphonic: turns the onset and note
 * posteriorgrams into discrete note events.
 */
object NotePostProcessor {
    private const val P = BasicPitch.N_PITCHES
    private const val MAX_FREQ_IDX = P - 1

    fun extractEvents(act: Activations, s: TranscriptionSettings): List<NoteEvent> {
        val n = act.frames
        if (n < 3) return emptyList()
        val frames = act.note.copyOf()
        var onsets = act.onset.copyOf()
        // Constrain to the pitch range.
        val loIdx = (s.minPitch - BasicPitch.MIDI_OFFSET).coerceIn(0, MAX_FREQ_IDX)
        val hiIdx = (s.maxPitch - BasicPitch.MIDI_OFFSET).coerceIn(0, MAX_FREQ_IDX)
        for (t in 0 until n) for (k in 0 until P) if (k < loIdx || k > hiIdx) {
            frames[t * P + k] = 0f
            onsets[t * P + k] = 0f
        }
        if (s.inferOnsets) onsets = inferOnsets(onsets, frames, n)

        val minLen = BasicPitch.msToFrames(s.minNoteLengthMs)
        val remaining = frames.copyOf()
        val events = ArrayList<NoteEvent>()

        // Onset peaks (strict local maxima in time) above threshold, processed backwards in time.
        val peakT = ArrayList<Int>()
        val peakK = ArrayList<Int>()
        for (t in 1 until n - 1) for (k in 0 until P) {
            val v = onsets[t * P + k]
            if (v >= s.onsetThreshold && v > onsets[(t - 1) * P + k] && v > onsets[(t + 1) * P + k]) {
                peakT += t; peakK += k
            }
        }
        for (pi in peakT.indices.reversed()) {
            val start = peakT[pi]
            val k = peakK[pi]
            if (start >= n - 1) continue
            var i = start + 1
            var miss = 0
            while (i < n - 1 && miss < s.energyTolerance) {
                if (remaining[i * P + k] < s.frameThreshold) miss++ else miss = 0
                i++
            }
            i -= miss
            if (i - start <= minLen) continue
            for (t in start until i) {
                remaining[t * P + k] = 0f
                if (k < MAX_FREQ_IDX) remaining[t * P + k + 1] = 0f
                if (k > 0) remaining[t * P + k - 1] = 0f
            }
            events += NoteEvent(start, i, k + BasicPitch.MIDI_OFFSET, mean(frames, start, i, k), onsets[start * P + k])
        }

        if (s.melodiaTrick) {
            // Equivalent to repeatedly taking the global argmax: cells only ever get zeroed, so the
            // current maximum is always the highest untouched cell in a one-off descending sort.
            val candidates = ArrayList<Int>()
            for (idx in remaining.indices) if (remaining[idx] > s.frameThreshold) candidates += idx
            val sorted = candidates.sortedWith { a, b ->
                val c = remaining[b].compareTo(remaining[a])
                if (c != 0) c else a.compareTo(b)
            }
            val original = remaining.copyOf()
            for (idx in sorted) {
                if (remaining[idx] != original[idx] || remaining[idx] <= s.frameThreshold) continue
                val mid = idx / P
                val k = idx % P
                remaining[idx] = 0f
                var i = mid + 1
                var miss = 0
                while (i < n - 1 && miss < s.energyTolerance) {
                    if (remaining[i * P + k] < s.frameThreshold) miss++ else miss = 0
                    zero(remaining, i, k)
                    i++
                }
                val end = i - 1 - miss
                i = mid - 1
                miss = 0
                while (i > 0 && miss < s.energyTolerance) {
                    if (remaining[i * P + k] < s.frameThreshold) miss++ else miss = 0
                    zero(remaining, i, k)
                    i--
                }
                val start = i + 1 + miss
                if (end - start <= minLen) continue
                var onsetMax = 0f
                for (t in start until minOf(n, start + 3)) onsetMax = maxOf(onsetMax, onsets[t * P + k])
                events += NoteEvent(start, end, k + BasicPitch.MIDI_OFFSET, mean(frames, start, end, k), onsetMax)
            }
        }
        return events
    }

    private fun zero(r: FloatArray, t: Int, k: Int) {
        r[t * P + k] = 0f
        if (k < MAX_FREQ_IDX) r[t * P + k + 1] = 0f
        if (k > 0) r[t * P + k - 1] = 0f
    }

    private fun mean(frames: FloatArray, from: Int, to: Int, k: Int): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (t in from until to) s += frames[t * P + k]
        return (s / (to - from)).toFloat()
    }

    /** get_infered_onsets: adds onsets where the note posteriorgram rises sharply. */
    fun inferOnsets(onsets: FloatArray, frames: FloatArray, n: Int, nDiff: Int = 2): FloatArray {
        val diff = FloatArray(n * P)
        for (t in 0 until n) for (k in 0 until P) {
            var m = Float.MAX_VALUE
            for (d in 1..nDiff) {
                val prev = if (t - d >= 0) frames[(t - d) * P + k] else 0f
                m = minOf(m, frames[t * P + k] - prev)
            }
            diff[t * P + k] = if (t < nDiff || m < 0) 0f else m
        }
        var maxOnset = 0f
        var maxDiff = 0f
        for (v in onsets) if (v > maxOnset) maxOnset = v
        for (v in diff) if (v > maxDiff) maxDiff = v
        val out = FloatArray(n * P)
        for (i in out.indices) {
            val scaled = if (maxDiff > 0) maxOnset * diff[i] / maxDiff else 0f
            out[i] = maxOf(onsets[i], scaled)
        }
        return out
    }

    /**
     * Converts events to notes. [velocity] is round(127 * amplitude) like the reference;
     * confidence blends the sustained note activation with the onset strength.
     */
    fun toNotes(events: List<NoteEvent>, ids: NoteIdGenerator): List<Note> =
        events.map { e ->
            val start = BasicPitch.frameToTime(e.startFrame)
            val end = BasicPitch.frameToTime(e.endFrame)
            val conf = (0.6f * e.amplitude / 0.75f + 0.4f * e.onsetStrength).coerceIn(0f, 1f)
            Note(
                id = ids.next(),
                pitch = e.pitch,
                start = start,
                duration = (end - start).coerceAtLeast(0.01),
                velocity = (127 * e.amplitude).roundToInt().coerceIn(1, 127),
                confidence = conf,
            )
        }.sortedWith(compareBy({ it.start }, { it.pitch }))

    fun process(act: Activations, s: TranscriptionSettings, ids: NoteIdGenerator): List<Note> =
        toNotes(extractEvents(act, s), ids)
}
