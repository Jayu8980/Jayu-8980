package com.pianoscribe.core.synth

import com.pianoscribe.core.arrange.Drum
import com.pianoscribe.core.arrange.DrumHit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Drum sounds made on the phone, no samples: a kick (a sine falling from about 120 to 50 Hz),
 * a snare (noise with a 180 Hz body), a rim click, hi-hats and a crash (bright noise with short
 * or long decays). Mixes the hits that sound in each block into the output.
 */
class DrumSynth(private val sampleRate: Int, hits: List<DrumHit>) {
    private val hits = hits.sortedBy { it.time }
    private var first = 0

    /** Adds the drums for frames [frame] until [frame] + [len] into [out] from [off]. */
    fun render(out: FloatArray, off: Int, len: Int, frame: Long, gain: Float) {
        val t0 = frame.toDouble() / sampleRate
        val t1 = (frame + len).toDouble() / sampleRate
        while (first < hits.size && hits[first].time + length(hits[first].drum) < t0) first++
        var h = first
        while (h < hits.size && hits[h].time < t1) {
            val hit = hits[h]
            val start = Math.round(hit.time * sampleRate)
            val dur = (length(hit.drum) * sampleRate).toLong()
            val a = maxOf(frame, start); val b = minOf(frame + len, start + dur)
            val amp = gain * hit.velocity / 127f
            for (i in a until b) out[off + (i - frame).toInt()] += amp * sample(hit.drum, (i - start).toInt(), h)
            h++
        }
    }

    private fun length(d: Drum) = when (d) {
        Drum.KICK -> 0.5; Drum.SNARE -> 0.3; Drum.RIM -> 0.08; Drum.CLOSED_HAT -> 0.08; Drum.OPEN_HAT -> 0.4; Drum.CRASH -> 1.8
    }

    /** Deterministic white noise in -1..1 for sample [i] of hit [seed]. */
    private fun noise(i: Int, seed: Int): Float {
        var x = i * 374761393 + seed * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 32767.5f - 1f
    }

    /** Noise with the low end removed (the difference of neighbours), for cymbals. */
    private fun bright(i: Int, seed: Int) = 0.5f * (noise(i, seed) - noise(i - 1, seed))

    private fun sample(d: Drum, i: Int, seed: Int): Float {
        val t = i.toDouble() / sampleRate
        return when (d) {
            Drum.KICK -> {
                val phase = 2 * PI * (50 * t + 70.0 / 30 * (1 - exp(-30 * t)))
                (sin(phase) * exp(-8 * t) * 1.1).toFloat()
            }
            Drum.SNARE -> (noise(i, seed) * exp(-20 * t) * 0.55 + sin(2 * PI * 180 * t) * exp(-30 * t) * 0.45).toFloat()
            Drum.RIM -> (sin(2 * PI * 820 * t) * exp(-80 * t) * 0.6 + noise(i, seed) * exp(-150 * t) * 0.3).toFloat()
            Drum.CLOSED_HAT -> (bright(i, seed) * exp(-60 * t) * 0.5).toFloat()
            Drum.OPEN_HAT -> (bright(i, seed) * exp(-8 * t) * 0.4).toFloat()
            Drum.CRASH -> (bright(i, seed) * exp(-2.5 * t) * 0.45).toFloat()
        }
    }
}
