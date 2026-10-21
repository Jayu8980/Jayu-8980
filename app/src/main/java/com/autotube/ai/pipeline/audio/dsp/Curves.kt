package com.autotube.ai.pipeline.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Gain conversions, easing curves and equal-power crossfade helpers shared by the audio code. */
object Curves {

    const val SILENCE_DB = -120f

    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    fun linearToDb(linear: Float): Float =
        if (linear <= 1e-6f) SILENCE_DB else (20.0 * log10(linear.toDouble())).toFloat()

    fun linearToDb(linear: Double): Double =
        if (linear <= 1e-6) SILENCE_DB.toDouble() else 20.0 * log10(linear)

    /** Clamps [t] to 0..1. */
    fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

    /** Hermite smoothstep of [x] between [edge0] and [edge1] (0..1, zero slope at both ends). */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 == edge0) return if (x < edge0) 0f else 1f
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    /** Smootherstep (C2-continuous). */
    fun smootherstep(t: Float): Float {
        val x = clamp01(t)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    fun easeInOut(t: Float): Float {
        val x = clamp01(t)
        return 0.5f - 0.5f * cos(PI * x).toFloat()
    }

    fun easeIn(t: Float): Float {
        val x = clamp01(t)
        return x * x
    }

    fun easeOut(t: Float): Float {
        val x = clamp01(t)
        return 1f - (1f - x) * (1f - x)
    }

    /** Equal-power fade-in gain for crossfade position [t] (0..1). */
    fun equalPowerIn(t: Float): Float = sin(clamp01(t) * (PI / 2.0)).toFloat()

    /** Equal-power fade-out gain for crossfade position [t] (0..1). */
    fun equalPowerOut(t: Float): Float = cos(clamp01(t) * (PI / 2.0)).toFloat()

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Applies a linear-in-time equal-power fade-in over the first [frames] frames (in place). */
    fun fadeIn(samples: FloatArray, channels: Int, frames: Int) {
        val total = samples.size / max(1, channels)
        val n = minOf(frames, total)
        if (n <= 0) return
        for (f in 0 until n) {
            val g = equalPowerIn(f.toFloat() / n)
            val base = f * channels
            for (c in 0 until channels) samples[base + c] *= g
        }
    }

    /** Applies an equal-power fade-out over the last [frames] frames (in place). */
    fun fadeOut(samples: FloatArray, channels: Int, frames: Int) {
        val total = samples.size / max(1, channels)
        val n = minOf(frames, total)
        if (n <= 0) return
        val start = total - n
        for (f in 0 until n) {
            val g = equalPowerOut((f + 1).toFloat() / n)
            val base = (start + f) * channels
            for (c in 0 until channels) samples[base + c] *= g
        }
    }

    /** Equal-power stereo pan gains for [pan] in -1 (left)..1 (right). Returns left gain; see [panRight]. */
    fun panLeft(pan: Float): Float = cos((clamp01((pan + 1f) / 2f)) * (PI / 2.0)).toFloat()

    fun panRight(pan: Float): Float = sin((clamp01((pan + 1f) / 2f)) * (PI / 2.0)).toFloat()
}
