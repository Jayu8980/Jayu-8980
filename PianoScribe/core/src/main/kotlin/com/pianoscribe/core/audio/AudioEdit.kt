package com.pianoscribe.core.audio

import com.pianoscribe.core.synth.OfflineRenderer
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Edits on mono audio. Ranges are sample indices [from, to); every edit that starts or stops
 * sound inside the audio uses a short fade so it never clicks.
 */
object AudioEdit {
    /** Fade length used at edit boundaries (10 ms). */
    fun edgeSamples(sampleRate: Int) = sampleRate / 100

    private fun clamp(x: FloatArray, from: Int, to: Int) = from.coerceIn(0, x.size) to to.coerceIn(0, x.size)

    /** Removes [from, to) and joins the two sides with a short cross-fade. */
    fun cut(x: FloatArray, from: Int, to: Int, sampleRate: Int): FloatArray {
        val (a, b) = clamp(x, from, to)
        if (b <= a) return x.copyOf()
        val fade = minOf(edgeSamples(sampleRate), a, x.size - b)
        val out = FloatArray(x.size - (b - a))
        System.arraycopy(x, 0, out, 0, a)
        System.arraycopy(x, b, out, a, x.size - b)
        // Overlap the last `fade` samples before the cut with the first after it.
        for (i in 0 until fade) {
            val w = 0.5 - 0.5 * cos(PI * (i + 0.5) / fade)
            val before = x[a - fade + i]
            val after = x[b - fade + i]
            out[a - fade + i] = (before * (1 - w) + after * w).toFloat()
        }
        return out
    }

    /** Keeps only [from, to), with short fades at both ends. */
    fun crop(x: FloatArray, from: Int, to: Int, sampleRate: Int): FloatArray {
        val (a, b) = clamp(x, from, to)
        if (b <= a) return x.copyOf()
        val out = x.copyOfRange(a, b)
        val e = minOf(edgeSamples(sampleRate), out.size / 2)
        if (a > 0) fadeIn(out, 0, e)
        if (b < x.size) fadeOut(out, out.size - e, out.size)
        return out
    }

    /** Replaces [from, to) with silence (fading out into it and back in after it). */
    fun silence(x: FloatArray, from: Int, to: Int, sampleRate: Int): FloatArray {
        val (a, b) = clamp(x, from, to)
        val out = x.copyOf()
        if (b <= a) return out
        val e = minOf(edgeSamples(sampleRate), (b - a) / 2)
        fadeOut(out, a, a + e)
        for (i in a + e until b - e) out[i] = 0f
        fadeIn(out, b - e, b)
        return out
    }

    /** Equal-power fade from silence to full level over [from, to), in place. */
    fun fadeIn(x: FloatArray, from: Int, to: Int) {
        val (a, b) = clamp(x, from, to)
        val n = b - a
        for (i in 0 until n) x[a + i] *= sin(PI / 2 * (i + 0.5) / n).toFloat()
    }

    /** Equal-power fade from full level to silence over [from, to), in place. */
    fun fadeOut(x: FloatArray, from: Int, to: Int) {
        val (a, b) = clamp(x, from, to)
        val n = b - a
        for (i in 0 until n) x[a + i] *= cos(PI / 2 * (i + 0.5) / n).toFloat()
    }

    fun withFadeIn(x: FloatArray, from: Int, to: Int) = x.copyOf().also { fadeIn(it, from, to) }
    fun withFadeOut(x: FloatArray, from: Int, to: Int) = x.copyOf().also { fadeOut(it, from, to) }

    /** Changes the level of [from, to) by [db], ramping the change in and out over 10 ms. */
    fun gain(x: FloatArray, from: Int, to: Int, db: Double, sampleRate: Int): FloatArray {
        val (a, b) = clamp(x, from, to)
        val out = x.copyOf()
        val g = 10.0.pow(db / 20)
        val e = minOf(edgeSamples(sampleRate), (b - a) / 2)
        for (i in a until b) {
            val ramp = when {
                e == 0 -> 1.0
                i < a + e && a > 0 -> (i - a + 0.5) / e
                i >= b - e && b < x.size -> (b - i - 0.5) / e
                else -> 1.0
            }
            out[i] = (x[i] * (1 + (g - 1) * ramp)).toFloat()
        }
        return out
    }

    /** Scales [from, to) so its peak is [peakDb] dBFS. */
    fun normalize(x: FloatArray, from: Int, to: Int, sampleRate: Int, peakDb: Double = -1.0): FloatArray {
        val (a, b) = clamp(x, from, to)
        var peak = 0f
        for (i in a until b) if (abs(x[i]) > peak) peak = abs(x[i])
        if (peak < 1e-6f) return x.copyOf()
        return gain(x, a, b, peakDb - 20 * kotlin.math.log10(peak.toDouble()), sampleRate)
    }

    /** Changes the speed without changing the pitch ([factor] 2 = twice as fast). */
    fun changeTempo(x: FloatArray, factor: Double, sampleRate: Int): FloatArray {
        if (abs(factor - 1) < 1e-6) return x.copyOf()
        val ts = TimeStretch(sampleRate, factor)
        val a = ts.process(x)
        // Push silence through so the last frames come out, then trim to the expected length.
        val tail = ts.process(FloatArray(sampleRate / 10))
        val out = a + tail
        return out.copyOf((x.size / factor).roundToInt().coerceAtMost(out.size))
    }

    /** Changes the pitch by [semitones] without changing the length. */
    fun pitchShift(x: FloatArray, semitones: Double, sampleRate: Int): FloatArray {
        if (abs(semitones) < 1e-6) return x.copyOf()
        val ratio = 2.0.pow(semitones / 12)
        // Stretch to ratio x the length at the same pitch, then play that back ratio x faster.
        val stretched = changeTempo(x, 1 / ratio, sampleRate)
        val shifted = StreamingResampler.resample(stretched, (sampleRate * ratio).roundToInt(), sampleRate)
        return if (shifted.size >= x.size) shifted.copyOf(x.size) else shifted + FloatArray(x.size - shifted.size)
    }

    /** Writes [x] as a 16-bit mono WAV file. */
    fun writeWav(out: OutputStream, x: FloatArray, sampleRate: Int) {
        OfflineRenderer.writeHeader(out, x.size, sampleRate)
        val buf = ByteArray(8192)
        var p = 0
        for (v in x) {
            val s = (v.coerceIn(-1f, 1f) * 32767).roundToInt()
            buf[p++] = s.toByte(); buf[p++] = (s shr 8).toByte()
            if (p == buf.size) { out.write(buf); p = 0 }
        }
        if (p > 0) out.write(buf, 0, p)
    }
}
