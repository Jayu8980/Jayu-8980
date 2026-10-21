package com.autotube.ai.pipeline.audio.synth

import com.autotube.ai.pipeline.audio.dsp.Biquad
import com.autotube.ai.pipeline.audio.dsp.Curves
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Subtle room tone: decorrelated pink noise, band-limited (80 Hz - 2.5 kHz) with slow movement,
 * calibrated to [levelDbfs] RMS (about -45 dBFS by default). Streams in constant memory.
 */
@Singleton
class AmbienceSynth @Inject constructor() {

    fun open(seed: Long, sampleRate: Int = 48_000, levelDbfs: Float = -45f): MusicStream =
        RoomToneStream(seed, sampleRate, levelDbfs)

    private class PinkNoise(seed: Long) {
        private val rng = XorShift(seed)
        private var b0 = 0f
        private var b1 = 0f
        private var b2 = 0f
        private var b3 = 0f
        private var b4 = 0f
        private var b5 = 0f
        private var b6 = 0f

        /** Paul Kellet's refined pink filter. */
        fun next(): Float {
            val white = rng.bipolar()
            b0 = 0.99886f * b0 + white * 0.0555179f
            b1 = 0.99332f * b1 + white * 0.0750759f
            b2 = 0.96900f * b2 + white * 0.1538520f
            b3 = 0.86650f * b3 + white * 0.3104856f
            b4 = 0.55000f * b4 + white * 0.5329522f
            b5 = -0.7616f * b5 - white * 0.0168980f
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
            b6 = white * 0.115926f
            return pink * 0.11f
        }
    }

    private class RoomToneStream(seed: Long, override val sampleRate: Int, levelDbfs: Float) : MusicStream {
        override val label: String = "room tone"
        private val left = PinkNoise(seed)
        private val right = PinkNoise(seed xor 0x7F4A7C15L)
        private val hpL = Biquad.highPass(sampleRate, 80.0)
        private val hpR = Biquad.highPass(sampleRate, 80.0)
        private val lpL = Biquad.lowPass(sampleRate, 2500.0)
        private val lpR = Biquad.lowPass(sampleRate, 2400.0)
        private val lfoRate = 2.0 * PI * (0.05 + XorShift(seed).nextFloat() * 0.05) / sampleRate
        private var lfoPhase = 0.0
        private val gain: Float

        init {
            // Measure the RMS of the filtered noise once (2 s warm-up), then scale to the target level.
            val probe = 2 * sampleRate
            var sum = 0.0
            repeat(probe) {
                val l = lpL.process(hpL.process(left.next()))
                val r = lpR.process(hpR.process(right.next()))
                sum += (l * l + r * r) / 2.0
            }
            val rms = sqrt(sum / probe).toFloat().coerceAtLeast(1e-6f)
            gain = Curves.dbToLinear(levelDbfs) / rms
        }

        override fun read(dst: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                lfoPhase += lfoRate
                if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
                val move = 1f + 0.22f * sin(lfoPhase).toFloat()
                val l = lpL.process(hpL.process(left.next())) * gain * move
                val r = lpR.process(hpR.process(right.next())) * gain * move
                dst[f * 2] = l
                dst[f * 2 + 1] = r
            }
        }
    }
}
