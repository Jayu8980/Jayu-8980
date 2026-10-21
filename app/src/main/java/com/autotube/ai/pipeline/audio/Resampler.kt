package com.autotube.ai.pipeline.audio

import com.autotube.ai.domain.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Channel conversion and band-limited sample-rate conversion (Blackman-windowed sinc, 32 taps,
 * 512-phase table with linear interpolation between phases). Cutoff tracks the lower of the two
 * Nyquist frequencies so downsampling does not alias.
 */
object Resampler {
    private const val HALF_TAPS = 16
    private const val PHASES = 512

    /** Converts interleaved audio to [channels]: mono -> all, multi -> mono (average), N -> 2 (L/R fold). */
    fun convertChannels(audio: PcmAudio, channels: Int): PcmAudio {
        val src = audio.channels
        if (src == channels) return audio
        val frames = audio.frames
        val out = FloatArray(frames * channels)
        val s = audio.samples
        when {
            src == 1 -> for (f in 0 until frames) {
                val v = s[f]
                val base = f * channels
                for (c in 0 until channels) out[base + c] = v
            }
            channels == 1 -> for (f in 0 until frames) {
                var sum = 0f
                val base = f * src
                for (c in 0 until src) sum += s[base + c]
                out[f] = sum / src
            }
            channels == 2 -> for (f in 0 until frames) {
                val base = f * src
                // Fold extra channels (centre/surround) equally into both sides.
                var l = s[base]
                var r = s[base + 1]
                for (c in 2 until src) {
                    l += s[base + c] * 0.5f
                    r += s[base + c] * 0.5f
                }
                val norm = if (src > 2) 1f / (1f + 0.5f * (src - 2)) else 1f
                out[f * 2] = l * norm
                out[f * 2 + 1] = r * norm
            }
            else -> for (f in 0 until frames) {
                val base = f * src
                for (c in 0 until channels) out[f * channels + c] = s[base + minOf(c, src - 1)]
            }
        }
        return PcmAudio(out, audio.sampleRate, channels)
    }

    fun resample(audio: PcmAudio, targetRate: Int): PcmAudio {
        if (audio.sampleRate == targetRate || audio.frames == 0) return PcmAudio(audio.samples, targetRate, audio.channels)
        val ch = audio.channels
        val inFrames = audio.frames
        val ratio = targetRate.toDouble() / audio.sampleRate
        val outFrames = floor(inFrames * ratio).toInt().coerceAtLeast(1)
        val cutoff = minOf(1.0, ratio) * 0.94
        val table = buildTable(cutoff)
        val out = FloatArray(outFrames * ch)
        val s = audio.samples
        val step = 1.0 / ratio
        val tapScale = cutoff
        val acc = DoubleArray(ch)
        for (o in 0 until outFrames) {
            val pos = o * step
            val center = floor(pos).toInt()
            val frac = pos - center
            java.util.Arrays.fill(acc, 0.0)
            // Taps at input frames center-HALF_TAPS+1 .. center+HALF_TAPS
            for (k in -HALF_TAPS + 1..HALF_TAPS) {
                val idx = center + k
                if (idx < 0 || idx >= inFrames) continue
                val x = k - frac // distance in input samples, in (-HALF_TAPS, HALF_TAPS]
                val w = kernel(table, x)
                if (w == 0f) continue
                val base = idx * ch
                for (c in 0 until ch) acc[c] += s[base + c] * w
            }
            val ob = o * ch
            for (c in 0 until ch) out[ob + c] = (acc[c] * tapScale).toFloat()
        }
        return PcmAudio(out, targetRate, ch)
    }

    fun convert(audio: PcmAudio, sampleRate: Int, channels: Int): PcmAudio {
        // Reduce channels first (less work), expand after resampling.
        return if (channels <= audio.channels) {
            resample(convertChannels(audio, channels), sampleRate)
        } else {
            convertChannels(resample(audio, sampleRate), channels)
        }
    }

    /** Table of the windowed sinc sampled at PHASES points per input sample over [0, HALF_TAPS]. */
    private fun buildTable(cutoff: Double): FloatArray {
        val n = HALF_TAPS * PHASES + 2
        return FloatArray(n) { i ->
            val x = i.toDouble() / PHASES
            if (x >= HALF_TAPS) return@FloatArray 0f
            val arg = PI * x * cutoff
            val sinc = if (abs(arg) < 1e-12) 1.0 else sin(arg) / arg
            val t = (x / HALF_TAPS + 1.0) / 2.0 // 0.5..1 over the half window
            val window = 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
            (sinc * window).toFloat()
        }
    }

    private fun kernel(table: FloatArray, x: Double): Float {
        val ax = abs(x) * PHASES
        val i = ax.toInt()
        if (i >= table.size - 1) return 0f
        val f = (ax - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }
}
