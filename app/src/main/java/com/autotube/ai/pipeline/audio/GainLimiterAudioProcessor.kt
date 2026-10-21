package com.autotube.ai.pipeline.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.autotube.ai.pipeline.audio.dsp.Curves
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * Media3 audio processor for the renderer: applies a static gain followed by a soft-knee peak
 * limiter (stereo-linked, ~1 ms attack, ~80 ms release) with a hard safety clip at [ceilingDbfs].
 * Accepts 16-bit PCM and 32-bit float PCM and outputs the same format it receives.
 */
class GainLimiterAudioProcessor(
    gainDb: Float = 0f,
    ceilingDbfs: Float = -1f,
) : BaseAudioProcessor() {

    private val gain = Curves.dbToLinear(gainDb)
    private val ceilingDb = ceilingDbfs
    private val ceiling = Curves.dbToLinear(ceilingDbfs)
    private val thresholdDb = ceilingDbfs - 2f
    private val kneeDb = 4f
    private val ratio = 20f

    private var attackCoef = 0f
    private var releaseCoef = 0f
    /** Current smoothed gain reduction in dB (<= 0). */
    private var reductionDb = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        attackCoef = exp(-1.0 / (0.001 * rate)).toFloat()
        releaseCoef = exp(-1.0 / (0.080 * rate)).toFloat()
        reductionDb = 0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = max(1, format.channelCount)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = channels * bytesPerSample
        val frames = remaining / frameBytes
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(frames * frameBytes)
        val frame = FloatArray(channels)
        repeat(frames) {
            var peak = 0f
            for (c in 0 until channels) {
                val v = (if (isFloat) input.float else input.short / 32768f) * gain
                frame[c] = v
                val a = abs(v)
                if (a > peak) peak = a
            }
            val targetDb = computeReductionDb(peak)
            reductionDb = if (targetDb < reductionDb) {
                targetDb + (reductionDb - targetDb) * attackCoef
            } else {
                targetDb + (reductionDb - targetDb) * releaseCoef
            }
            val g = Curves.dbToLinear(reductionDb)
            for (c in 0 until channels) {
                var y = frame[c] * g
                if (y > ceiling) y = ceiling else if (y < -ceiling) y = -ceiling
                if (isFloat) {
                    output.putFloat(y)
                } else {
                    output.putShort(Math.round(y * 32767f).coerceIn(-32768, 32767).toShort())
                }
            }
        }
        // Skip any trailing partial frame so the input is fully consumed.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    /** Static soft-knee curve: gain change in dB for a frame peak (linear). */
    private fun computeReductionDb(peak: Float): Float {
        if (peak <= 1e-6f) return 0f
        val x = (20.0 * log10(peak.toDouble())).toFloat()
        val lowerKnee = thresholdDb - kneeDb / 2f
        val upperKnee = thresholdDb + kneeDb / 2f
        val y = when {
            x <= lowerKnee -> x
            x < upperKnee -> {
                val d = x - lowerKnee
                x + (1f / ratio - 1f) * d * d / (2f * kneeDb)
            }
            else -> thresholdDb + (x - thresholdDb) / ratio
        }
        return (minOf(y, ceilingDb) - x).coerceAtMost(0f)
    }

    override fun onReset() {
        reductionDb = 0f
    }
}
