package com.pianoscribe.core.transcribe

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator

/**
 * The high-resolution piano transcription model (Kong et al. 2021, "High-resolution Piano
 * Transcription with Pedals by Regressing Onset and Offset Times"): 16 kHz audio in 10 s
 * segments, 100 frames a second, 88 keys. Instead of yes/no onsets it regresses how close each
 * frame is to an onset or offset, which gives onset times far finer than a frame.
 *
 * This file holds the parts that are plain arithmetic: segmenting, stitching the segment
 * outputs, and turning them into notes (a port of the reference RegressionPostProcessor).
 */
object PianoModel {
    const val SAMPLE_RATE = 16000
    const val SEGMENT_SAMPLES = SAMPLE_RATE * 10
    const val FPS = 100
    const val KEYS = 88
    const val FIRST_KEY = 21
    /** Frames per 10 s segment (centred STFT gives one extra). */
    const val SEGMENT_FRAMES = SEGMENT_SAMPLES / (SAMPLE_RATE / FPS) + 1

    /** Frame-wise model outputs for a whole recording: [frames][keys], values 0..1. */
    class Output(val onset: Array<FloatArray>, val offset: Array<FloatArray>, val frame: Array<FloatArray>, val velocity: Array<FloatArray>) {
        val frames: Int get() = frame.size
    }

    /** One 10 s segment's outputs, each [SEGMENT_FRAMES][KEYS]. */
    class Segment(val onset: Array<FloatArray>, val offset: Array<FloatArray>, val frame: Array<FloatArray>, val velocity: Array<FloatArray>)

    /** Number of 10 s segments for [samples] of 16 kHz audio (segments overlap by half). */
    fun segmentCount(samples: Int): Int {
        val padded = ((samples + SEGMENT_SAMPLES - 1) / SEGMENT_SAMPLES).coerceAtLeast(1) * SEGMENT_SAMPLES
        return (padded - SEGMENT_SAMPLES) / (SEGMENT_SAMPLES / 2) + 1
    }

    /** Fills [dst] with segment [i] of [audio] (zero padded), exactly like the reference enframing. */
    fun fillSegment(audio: FloatArray, i: Int, dst: FloatArray) {
        val start = i * SEGMENT_SAMPLES / 2
        for (k in 0 until SEGMENT_SAMPLES) {
            val idx = start + k
            dst[k] = if (idx < audio.size) audio[idx] else 0f
        }
    }

    /**
     * Joins overlapping segments like the reference deframe: drop each segment's last frame,
     * keep the first 75% of the first segment, the middle 50% of the inner ones, the last 75%
     * of the final one. (The reference then slices by the sample count, which never shortens
     * the frames, so the padded tail is kept too; notes past the end are trimmed by the caller.)
     */
    fun stitch(segments: List<Segment>): Output {
        val st = Stitcher(segments.size)
        for (seg in segments) st.add(seg)
        return st.result()
    }

    /** [stitch] one segment at a time, keeping only the frames that end up in the result. */
    class Stitcher(private val total: Int) {
        private val onset = ArrayList<FloatArray>()
        private val offset = ArrayList<FloatArray>()
        private val frame = ArrayList<FloatArray>()
        private val velocity = ArrayList<FloatArray>()
        private var index = 0

        fun add(s: Segment) {
            val range = if (total == 1) s.frame.indices else {
                val n = SEGMENT_FRAMES - 1
                val q1 = (n * 0.25).toInt()
                val q3 = (n * 0.75).toInt()
                when (index) {
                    0 -> 0 until q3
                    total - 1 -> q1 until n
                    else -> q1 until q3
                }
            }
            for (f in range) { onset += s.onset[f]; offset += s.offset[f]; frame += s.frame[f]; velocity += s.velocity[f] }
            index++
        }

        fun result() = Output(onset.toTypedArray(), offset.toTypedArray(), frame.toTypedArray(), velocity.toTypedArray())
    }

    /** Reference thresholds. */
    data class Settings(
        val onsetThreshold: Float = 0.3f,
        val offsetThreshold: Float = 0.3f,
        val frameThreshold: Float = 0.1f,
    )

    /** Port of RegressionPostProcessor.output_dict_to_detected_notes: model outputs to notes. */
    fun notes(out: Output, settings: Settings = Settings(), ids: NoteIdGenerator = NoteIdGenerator()): List<Note> {
        val frames = out.frames
        val result = ArrayList<Note>()
        val col = FloatArray(frames)
        for (k in 0 until KEYS) {
            for (f in 0 until frames) col[f] = out.onset[f][k]
            val (onB, onShift) = binarize(col, settings.onsetThreshold, 2)
            for (f in 0 until frames) col[f] = out.offset[f][k]
            val (offB, offShift) = binarize(col, settings.offsetThreshold, 4)
            val tuples = detect(out, k, onB, onShift, offB, offShift, settings.frameThreshold)
            for (t in tuples) {
                val start = (t.bgn + t.onShift) / FPS
                val end = (t.fin + t.offShift) / FPS
                val vel = (t.velocity * 128).toInt().coerceIn(1, 127)
                val pitch = k + FIRST_KEY
                result += Note(ids.next(), pitch, start, maxOf(end - start, 0.01), vel, out.onset[t.bgn][k].coerceIn(0f, 1f),
                    if (pitch >= Note.MIDDLE_C) Hand.RIGHT else Hand.LEFT)
            }
        }
        return result.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    /** Peaks above [threshold] that rise for [neighbour] frames and fall for [neighbour] frames, with sub-frame shift. */
    internal fun binarize(x: FloatArray, threshold: Float, neighbour: Int): Pair<BooleanArray, DoubleArray> {
        val n = x.size
        val b = BooleanArray(n)
        val shift = DoubleArray(n)
        for (i in neighbour until n - neighbour) {
            if (x[i] > threshold && monotonic(x, i, neighbour)) {
                b[i] = true
                shift[i] = if (x[i - 1] > x[i + 1]) (x[i + 1] - x[i - 1]).toDouble() / (x[i] - x[i + 1]) / 2
                else (x[i + 1] - x[i - 1]).toDouble() / (x[i] - x[i - 1]) / 2
                if (shift[i].isNaN() || shift[i].isInfinite()) shift[i] = 0.0
            }
        }
        return b to shift
    }

    private fun monotonic(x: FloatArray, n: Int, neighbour: Int): Boolean {
        for (i in 0 until neighbour) {
            if (x[n - i] < x[n - i - 1]) return false
            if (x[n + i] < x[n + i + 1]) return false
        }
        return true
    }

    internal class Tuple(val bgn: Int, val fin: Int, val onShift: Double, val offShift: Double, val velocity: Float)

    /** Port of note_detection_with_onset_offset_regress for one key (including its frame-0 quirk). */
    private fun detect(out: Output, k: Int, onset: BooleanArray, onShift: DoubleArray, offset: BooleanArray, offShift: DoubleArray, frameThreshold: Float): List<Tuple> {
        val tuples = ArrayList<Tuple>()
        val n = onset.size
        // The reference uses Python truthiness, so a note starting exactly at frame 0 is ignored; keep that.
        var bgn = 0
        var frameDisappear = 0
        var offsetOccur = 0
        for (i in 0 until n) {
            if (onset[i]) {
                if (bgn != 0) {
                    val fin = maxOf(i - 1, 0)
                    tuples += Tuple(bgn, fin, onShift[bgn], 0.0, out.velocity[bgn][k])
                    frameDisappear = 0; offsetOccur = 0
                }
                bgn = i
            }
            if (bgn != 0 && i > bgn) {
                if (out.frame[i][k] <= frameThreshold && frameDisappear == 0) frameDisappear = i
                if (offset[i] && offsetOccur == 0) offsetOccur = i
                if (frameDisappear != 0) {
                    val fin = if (offsetOccur != 0 && offsetOccur - bgn > frameDisappear - offsetOccur) offsetOccur else frameDisappear
                    tuples += Tuple(bgn, fin, onShift[bgn], offShift[fin], out.velocity[bgn][k])
                    bgn = 0; frameDisappear = 0; offsetOccur = 0
                }
                if (bgn != 0 && (i - bgn >= 600 || i == n - 1)) {
                    tuples += Tuple(bgn, i, onShift[bgn], offShift[i], out.velocity[bgn][k])
                    bgn = 0; frameDisappear = 0; offsetOccur = 0
                }
            }
        }
        return tuples.sortedBy { it.bgn }
    }
}
