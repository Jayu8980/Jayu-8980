package com.autotube.ai.pipeline.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Lookahead brick-wall limiter (stereo-linked), processed in place over a whole buffer.
 *
 * The required gain per frame is held with a sliding minimum over the lookahead window and then
 * smoothed by a box filter of the same length, which guarantees the gain has fully reached its
 * target when the peak plays, with a click-free attack. Release is a smooth one-pole recovery.
 * Audio is not delayed: the lookahead reads ahead in the buffer instead.
 */
class Limiter(
    private val sampleRate: Int,
    private val channels: Int,
    ceilingDb: Float = -1f,
    lookaheadMs: Float = 5f,
    releaseMs: Float = 120f,
) {
    /** Sample ceiling, a little under the true-peak ceiling to leave room for inter-sample overs. */
    private val ceiling = Curves.dbToLinear(ceilingDb - TRUE_PEAK_MARGIN_DB)
    private val lookahead = max(1, (lookaheadMs / 1000f * sampleRate).toInt())
    private val releaseCoef = exp(-1.0 / (releaseMs / 1000.0 * sampleRate)).toFloat()

    /** Returns the number of frames where gain reduction was applied. */
    fun process(samples: FloatArray): Int {
        val frames = samples.size / channels
        if (frames == 0) return 0
        val window = lookahead + 1
        // Ring of required gains for frames i..i+lookahead.
        val req = FloatArray(window)
        // Monotonic deque (indices) for sliding minimum.
        val dq = IntArray(window + 1)
        var head = 0
        var tail = 0
        val holdRing = FloatArray(window) { 1f }
        var holdSum = window.toDouble()
        var holdPos = 0
        var env = 1f
        var reducedFrames = 0

        fun required(frame: Int): Float {
            var peak = 0f
            val base = frame * channels
            for (c in 0 until channels) {
                val a = abs(samples[base + c])
                if (a > peak) peak = a
            }
            return if (peak > ceiling) ceiling / peak else 1f
        }

        fun push(frame: Int) {
            val g = required(frame)
            req[frame % window] = g
            while (tail > head && req[dq[(tail - 1) % (window + 1)] % window] >= g) tail--
            dq[tail % (window + 1)] = frame
            tail++
        }

        val prefill = minOf(lookahead, frames - 1)
        for (k in 0..prefill) push(k)

        for (i in 0 until frames) {
            val ahead = i + lookahead
            if (i > 0 && ahead < frames) push(ahead)
            while (tail > head && dq[head % (window + 1)] < i) head++
            val hold = if (tail > head) req[dq[head % (window + 1)] % window] else 1f

            holdSum += hold - holdRing[holdPos]
            holdRing[holdPos] = hold
            holdPos = (holdPos + 1) % window
            // Every hold value in the box window covers frame i, so the average is <= req[i].
            val smooth = (holdSum / window).toFloat()

            env = if (smooth < env) smooth else smooth + (env - smooth) * releaseCoef
            if (env < 0.9999f) reducedFrames++

            val base = i * channels
            for (c in 0 until channels) {
                var y = samples[base + c] * env
                if (y > ceiling) y = ceiling else if (y < -ceiling) y = -ceiling
                samples[base + c] = y
            }
        }
        return reducedFrames
    }

    companion object {
        const val TRUE_PEAK_MARGIN_DB = 0.3f
    }
}

/** Counts samples whose magnitude exceeds full scale (1.0). */
fun countOvers(samples: FloatArray, threshold: Float = 1.0f): Int {
    var n = 0
    for (s in samples) if (s > threshold || s < -threshold) n++
    return n
}
