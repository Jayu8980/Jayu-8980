package com.autotube.ai.pipeline.audio.synth

import com.autotube.ai.core.model.SfxType
import com.autotube.ai.domain.PcmAudio
import com.autotube.ai.pipeline.audio.dsp.Biquad
import com.autotube.ai.pipeline.audio.dsp.Curves
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Procedural sound effects (stereo, peak-normalized to about -1 dBFS). Deterministic per seed so a
 * re-render sounds identical. For [SfxType.RISER] the sound ends exactly at its sync point; the
 * engine places it so that the end lands on the cue.
 */
@Singleton
class SfxSynth @Inject constructor() {

    fun generate(type: SfxType, seed: Long, sampleRate: Int = 48_000, riserMs: Long = 2000): PcmAudio {
        val rng = XorShift(seed * 6364136223846793005L + type.ordinal)
        val audio = when (type) {
            SfxType.WHOOSH -> whoosh(sampleRate, rng, durationSec = 0.75f, lowHz = 350f, highHz = 3200f, q = 1.4f)
            SfxType.SWOOSH_SOFT -> whoosh(sampleRate, rng, durationSec = 0.5f, lowHz = 250f, highHz = 1300f, q = 0.9f)
            SfxType.RISER -> riser(sampleRate, rng, (riserMs.coerceIn(1000, 3000)) / 1000f)
            SfxType.IMPACT -> impact(sampleRate, rng)
            SfxType.POP -> pop(sampleRate, rng)
            SfxType.CLICK -> click(sampleRate, rng)
        }
        normalizePeak(audio.samples, 0.89f)
        return audio
    }

    /** Band-passed noise whose centre sweeps up then down (doppler-like) while panning across. */
    private fun whoosh(sr: Int, rng: XorShift, durationSec: Float, lowHz: Float, highHz: Float, q: Float): PcmAudio {
        val frames = (durationSec * sr).toInt()
        val out = FloatArray(frames * 2)
        val bpL = Biquad()
        val bpR = Biquad()
        val peakAt = 0.55f + rng.nextFloat() * 0.1f
        val direction = if (rng.chance(0.5f)) 1f else -1f
        for (f in 0 until frames) {
            val t = f.toFloat() / frames
            if (f % 32 == 0) {
                // Rises to the pass-by, then falls (pitch drop as it passes).
                val shape = if (t < peakAt) Curves.easeIn(t / peakAt) else 1f - Curves.easeOut((t - peakAt) / (1f - peakAt)) * 0.75f
                val fc = lowHz * (highHz / lowHz).toDouble().pow(shape.toDouble())
                bpL.bandPass(sr, fc * 0.97, q.toDouble())
                bpR.bandPass(sr, fc * 1.03, q.toDouble())
            }
            val env = if (t < peakAt) Curves.easeIn(t / peakAt) else (1f - (t - peakAt) / (1f - peakAt)).let { it * it }
            val pan = direction * (t * 2f - 1f) * 0.8f
            val l = bpL.process(rng.bipolar()) * env * Curves.panLeft(pan)
            val r = bpR.process(rng.bipolar()) * env * Curves.panRight(pan)
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
        return PcmAudio(out, sr, 2)
    }

    /** Rising filtered noise plus an upward pitch sweep, crescendo, widening, hard stop at the end. */
    private fun riser(sr: Int, rng: XorShift, durationSec: Float): PcmAudio {
        val frames = (durationSec * sr).toInt()
        val out = FloatArray(frames * 2)
        val bpL = Biquad()
        val bpR = Biquad()
        var phase = 0.0
        var phase2 = 0.0
        val endFade = (0.025f * sr).toInt()
        for (f in 0 until frames) {
            val t = f.toFloat() / frames
            if (f % 32 == 0) {
                val fc = 300.0 * (6000.0 / 300.0).pow(t.toDouble())
                bpL.bandPass(sr, fc, 1.2)
                bpR.bandPass(sr, fc * 1.05, 1.2)
            }
            val toneHz = 180.0 * (1200.0 / 180.0).pow((t * t).toDouble())
            phase += 2 * PI * toneHz / sr
            phase2 += 2 * PI * toneHz * 1.007 / sr
            val tone = (sin(phase) * 0.5 + sin(phase2) * 0.5).toFloat() * 0.35f
            val env = t * t * (0.4f + 0.6f * t)
            val width = 0.3f + 0.7f * t
            val nL = bpL.process(rng.bipolar())
            val nR = bpR.process(rng.bipolar())
            val common = (nL + nR) * 0.5f
            var l = (common + (nL - common) * width) * 1.6f + tone
            var r = (common + (nR - common) * width) * 1.6f + tone
            var g = env
            if (f >= frames - endFade) g *= (frames - f).toFloat() / endFade
            l *= g; r *= g
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
        return PcmAudio(out, sr, 2)
    }

    /** Sub drop + noise burst + short diffuse tail. */
    private fun impact(sr: Int, rng: XorShift): PcmAudio {
        val frames = (1.6f * sr).toInt()
        val out = FloatArray(frames * 2)
        val lp = Biquad.lowPass(sr, 2200.0, 0.7)
        var phase = 0.0
        val burst = FloatArray(frames)
        for (f in 0 until frames) {
            val sec = f.toFloat() / sr
            val freq = 34.0 + 70.0 * exp(-sec * 9.0)
            phase += 2 * PI * freq / sr
            val sub = sin(phase).toFloat() * exp(-sec * 2.6f)
            val noise = lp.process(rng.bipolar()) * exp(-sec * 28f)
            burst[f] = noise
            val v = Voices.softClip((sub * 0.9f + noise * 0.8f) * 1.3f)
            out[f * 2] = v
            out[f * 2 + 1] = v
        }
        addTail(out, burst, sr, rng, seconds = 1.2f, wet = 0.35f)
        return PcmAudio(out, sr, 2)
    }

    private fun pop(sr: Int, rng: XorShift): PcmAudio {
        val frames = (0.12f * sr).toInt()
        val out = FloatArray(frames * 2)
        var phase = 0.0
        val base = 480f + rng.nextFloat() * 120f
        for (f in 0 until frames) {
            val sec = f.toFloat() / sr
            val freq = base + 650f * (1f - exp(-sec * 60f))
            phase += 2 * PI * freq / sr
            val attack = minOf(1f, f / (0.002f * sr))
            val v = sin(phase).toFloat() * attack * exp(-sec * 38f)
            out[f * 2] = v
            out[f * 2 + 1] = v
        }
        return PcmAudio(out, sr, 2)
    }

    private fun click(sr: Int, rng: XorShift): PcmAudio {
        val frames = (0.03f * sr).toInt()
        val out = FloatArray(frames * 2)
        val hp = Biquad.highPass(sr, 1500.0, 0.7)
        var phase = 0.0
        for (f in 0 until frames) {
            val sec = f.toFloat() / sr
            phase += 2 * PI * 2300.0 / sr
            val v = (hp.process(rng.bipolar()) * exp(-sec * 400f) + sin(phase).toFloat() * exp(-sec * 220f) * 0.6f)
            out[f * 2] = v
            out[f * 2 + 1] = v * 0.95f
        }
        return PcmAudio(out, sr, 2)
    }

    /** Cheap stereo diffuse tail: the excitation convolved with decaying, decorrelated noise. */
    private fun addTail(out: FloatArray, excitation: FloatArray, sr: Int, rng: XorShift, seconds: Float, wet: Float) {
        val frames = out.size / 2
        val tailLen = (seconds * sr).toInt()
        // Use the energy envelope of the excitation instead of a full convolution (fast, smooth).
        var energy = 0f
        val decay = exp(-1f / (seconds * sr / 6.9f))
        val lpL = Biquad.lowPass(sr, 3000.0, 0.7)
        val lpR = Biquad.lowPass(sr, 2800.0, 0.7)
        for (f in 0 until minOf(frames, tailLen + excitation.size)) {
            val e = if (f < excitation.size) abs(excitation[f]) else 0f
            energy = maxOf(e, energy * decay)
            out[f * 2] += lpL.process(rng.bipolar()) * energy * wet
            out[f * 2 + 1] += lpR.process(rng.bipolar()) * energy * wet
        }
    }

    private fun normalizePeak(samples: FloatArray, target: Float) {
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        if (peak < 1e-6f) return
        val g = target / peak
        for (i in samples.indices) samples[i] *= g
    }
}
