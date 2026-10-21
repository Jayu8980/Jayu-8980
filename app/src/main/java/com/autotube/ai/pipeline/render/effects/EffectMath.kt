package com.autotube.ai.pipeline.render.effects

import androidx.media3.common.C
import com.autotube.ai.core.model.Easing
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Easing curves and deterministic noise used by the render effects. All inputs are clamped to 0..1. */
internal object EffectMath {

    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun easeInOutCubic(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    fun easeOutCubic(t: Float): Float {
        val x = clamp01(t)
        return 1f - (1f - x).pow(3)
    }

    fun easeInCubic(t: Float): Float {
        val x = clamp01(t)
        return x * x * x
    }

    /** Overshooting ease-out, peaks at ~1.1 before settling on 1. */
    fun easeOutBack(t: Float): Float {
        val x = clamp01(t)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
    }

    fun ease(easing: Easing, t: Float): Float = when (easing) {
        Easing.EASE_IN_OUT -> easeInOutCubic(t)
        Easing.EASE_OUT -> easeOutCubic(t)
        Easing.EASE_IN -> easeInCubic(t)
    }

    /**
     * Ease-in-out envelope over [durationMs] starting at [startMs]: rises for the first [riseFraction],
     * holds, and falls for the last [riseFraction]. Returns 0..1.
     */
    fun envelope(timeMs: Float, startMs: Float, durationMs: Float, riseFraction: Float = 0.35f): Float {
        if (durationMs <= 0f) return 0f
        val local = (timeMs - startMs) / durationMs
        if (local <= 0f || local >= 1f) return 0f
        val rise = riseFraction.coerceIn(0.05f, 0.5f)
        return when {
            local < rise -> easeInOutCubic(local / rise)
            local > 1f - rise -> easeInOutCubic((1f - local) / rise)
            else -> 1f
        }
    }

    /**
     * Smooth, deterministic pseudo-random signal in -1..1 (sum of incommensurate sines). Different
     * [seed]/[channel] combinations give independent-looking paths; same inputs always give the same value.
     */
    fun smoothNoise(seconds: Float, seed: Int, channel: Int): Float {
        var h = seed * 73856093 xor (channel * 19349663)
        fun next(): Float {
            h = h xor (h shl 13)
            h = h xor (h ushr 17)
            h = h xor (h shl 5)
            return (h and 0x7FFFFFFF) / 2147483647f
        }
        var sum = 0f
        var norm = 0f
        val bases = floatArrayOf(0.37f, 0.71f, 1.13f, 1.61f)
        for (i in bases.indices) {
            val freq = bases[i] * (0.8f + 0.4f * next())
            val phase = next() * 2f * PI.toFloat()
            val amp = 1f / (i + 1)
            sum += amp * sin(2f * PI.toFloat() * freq * seconds + phase)
            norm += amp
        }
        return (sum / norm).coerceIn(-1f, 1f)
    }

    /**
     * Converts a 3x3 colour matrix (row-major) plus an offset vector into the column-major 4x4 layout
     * expected by Media3's RgbMatrix (out = M * rgb + offset).
     */
    fun toGlColorMatrix(m: FloatArray, offset: FloatArray): FloatArray {
        require(m.size == 9 && offset.size == 3)
        return floatArrayOf(
            m[0], m[3], m[6], 0f,
            m[1], m[4], m[7], 0f,
            m[2], m[5], m[8], 0f,
            offset[0], offset[1], offset[2], 1f,
        )
    }

    /** Row-major 3x3 multiply: a * b. */
    fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]
            out[r * 3 + c] = s
        }
        return out
    }

    fun mul3Vec(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )
}

/**
 * Converts the presentation timestamps an effect instance receives into a 0-based local clock.
 * Media3 may offset per-item timestamps inside a sequence, so the first timestamp seen is latched.
 */
internal class LatchedClock {
    private var firstUs: Long = C.TIME_UNSET

    /** Microseconds since the first frame this instance processed (never negative). */
    fun elapsedUs(presentationTimeUs: Long): Long {
        if (firstUs == C.TIME_UNSET || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        return presentationTimeUs - firstUs
    }

    fun elapsedMs(presentationTimeUs: Long): Float = elapsedUs(presentationTimeUs) / 1000f
}
