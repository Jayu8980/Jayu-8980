package com.pianoscribe.core.transcribe

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Constants and windowing of Spotify's Basic Pitch model (ICASSP 2022, "nmp"), mirroring
 * basic_pitch/inference.py so results match the reference implementation.
 */
object BasicPitch {
    const val SAMPLE_RATE = 22050
    const val FFT_HOP = 256
    /** Model input length: 2 s of audio minus one hop. */
    const val AUDIO_N_SAMPLES = SAMPLE_RATE * 2 - FFT_HOP
    const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP // 86 (integer, as in the reference)
    const val ANNOT_N_FRAMES = ANNOTATIONS_FPS * 2 // 172
    const val N_OVERLAPPING_FRAMES = 30
    const val OVERLAP_LEN = N_OVERLAPPING_FRAMES * FFT_HOP
    const val HOP_SIZE = AUDIO_N_SAMPLES - OVERLAP_LEN
    const val N_PITCHES = 88
    const val CONTOUR_BINS = 264
    const val MIDI_OFFSET = 21
    /** Frames kept from each window after trimming half the overlap from both sides. */
    const val FRAMES_PER_WINDOW = ANNOT_N_FRAMES - N_OVERLAPPING_FRAMES

    /** Zeros prepended to the signal before windowing. */
    const val PAD_START = OVERLAP_LEN / 2

    fun windowCount(originalLength: Int): Int =
        ceil((originalLength + PAD_START).toDouble() / HOP_SIZE).toInt().coerceAtLeast(1)

    fun outputFrames(originalLength: Int): Int =
        floor(originalLength * (ANNOTATIONS_FPS.toDouble() / SAMPLE_RATE)).toInt()

    /**
     * Copies window [index] of the padded signal into [dst] (length AUDIO_N_SAMPLES). [read] fills
     * part of a buffer from the unpadded signal: read(dst, dstOffset, srcStart, count) -> samples read.
     */
    fun fillWindow(index: Int, originalLength: Int, dst: FloatArray, read: (FloatArray, Int, Int, Int) -> Int) {
        dst.fill(0f)
        val paddedStart = index.toLong() * HOP_SIZE
        val srcStart = paddedStart - PAD_START
        var dstOff = 0
        var src = srcStart
        if (src < 0) {
            dstOff = (-src).toInt().coerceAtMost(AUDIO_N_SAMPLES)
            src = 0
        }
        val count = minOf(AUDIO_N_SAMPLES - dstOff, (originalLength - src).toInt())
        if (count > 0) read(dst, dstOff, src.toInt(), count)
    }

    /**
     * Frame time in seconds, matching model_frames_to_time() in the reference, which corrects the
     * small drift caused by each window being one hop shorter than 2 s.
     */
    fun frameToTime(frame: Int): Double {
        val original = frame.toDouble() * FFT_HOP / SAMPLE_RATE
        val windowNumber = floor(frame.toDouble() / ANNOT_N_FRAMES)
        val windowOffset = (FFT_HOP.toDouble() / SAMPLE_RATE) * (ANNOT_N_FRAMES - AUDIO_N_SAMPLES.toDouble() / FFT_HOP) + 0.0018
        return original - windowOffset * windowNumber
    }

    /** Inverse of [frameToTime]: the nearest frame to time [t] (seconds). */
    fun timeToFrame(t: Double): Int {
        if (t <= 0) return 0
        // frameToTime is increasing, so start from the linear estimate and step to the nearest frame.
        var f = (t * SAMPLE_RATE / FFT_HOP).toInt()
        while (f > 0 && frameToTime(f) > t) f--
        while (frameToTime(f + 1) <= t) f++
        return if (t - frameToTime(f) <= frameToTime(f + 1) - t) f else f + 1
    }

    fun msToFrames(ms: Double): Int = Math.round(ms / 1000.0 * (SAMPLE_RATE.toDouble() / FFT_HOP)).toInt()
}

/**
 * Model activations for the whole song, stored frame-major ([frame * 88 + pitchIndex]).
 * The contour (3 bins per semitone) is kept quantised to bytes to save memory.
 */
class Activations(val frames: Int) {
    val note = FloatArray(frames * BasicPitch.N_PITCHES)
    val onset = FloatArray(frames * BasicPitch.N_PITCHES)
    val contour = ByteArray(frames * BasicPitch.CONTOUR_BINS)

    fun contourAt(frame: Int, bin: Int): Float = (contour[frame * BasicPitch.CONTOUR_BINS + bin].toInt() and 0xFF) / 255f

    /** Mean contour over [fromFrame, toFrame). */
    fun meanContour(fromFrame: Int, toFrame: Int): FloatArray {
        val out = FloatArray(BasicPitch.CONTOUR_BINS)
        val a = fromFrame.coerceIn(0, frames)
        val b = toFrame.coerceIn(a, frames)
        if (b == a) return out
        for (f in a until b) for (k in 0 until BasicPitch.CONTOUR_BINS) out[k] += contourAt(f, k)
        for (k in out.indices) out[k] /= (b - a)
        return out
    }
}

/**
 * Stitches per-window model outputs into [Activations], trimming the overlap like unwrap_output().
 */
class ActivationAssembler(originalLength: Int) {
    val activations = Activations(BasicPitch.outputFrames(originalLength))
    private val trim = BasicPitch.N_OVERLAPPING_FRAMES / 2

    /**
     * Adds window [index]. Arrays are the model outputs for one window, frame-major:
     * note/onset 172x88, contour 172x264.
     */
    fun add(index: Int, note: FloatArray, onset: FloatArray, contour: FloatArray) {
        val a = activations
        val base = index * BasicPitch.FRAMES_PER_WINDOW
        for (f in 0 until BasicPitch.FRAMES_PER_WINDOW) {
            val dstFrame = base + f
            if (dstFrame >= a.frames) return
            val srcFrame = f + trim
            System.arraycopy(note, srcFrame * BasicPitch.N_PITCHES, a.note, dstFrame * BasicPitch.N_PITCHES, BasicPitch.N_PITCHES)
            System.arraycopy(onset, srcFrame * BasicPitch.N_PITCHES, a.onset, dstFrame * BasicPitch.N_PITCHES, BasicPitch.N_PITCHES)
            val cs = srcFrame * BasicPitch.CONTOUR_BINS
            val cd = dstFrame * BasicPitch.CONTOUR_BINS
            for (k in 0 until BasicPitch.CONTOUR_BINS) {
                a.contour[cd + k] = (contour[cs + k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
        }
    }
}
