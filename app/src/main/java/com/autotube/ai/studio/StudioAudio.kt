package com.autotube.ai.studio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.autotube.ai.pipeline.audio.WavIO
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Volume with optional linear fade in/out for one media item. Time is counted from the first sample
 * after configure/flush, i.e. from the start of the (clipped) item.
 */
class VolumeFadeAudioProcessor(
    private val volume: Float,
    private val fadeInMs: Long = 0L,
    private val fadeOutMs: Long = 0L,
    private val durationMs: Long = 0L,
) : BaseAudioProcessor() {

    private var framesDone = 0L
    private var sampleRate = 44_100

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        framesDone = 0L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = max(1, format.channelCount)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * if (isFloat) 4 else 2
        val frames = remaining / frameBytes
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(frames * frameBytes)
        val fadeInFrames = fadeInMs * sampleRate / 1000L
        val fadeOutFrames = fadeOutMs * sampleRate / 1000L
        val totalFrames = durationMs * sampleRate / 1000L
        repeat(frames) {
            var g = volume
            if (fadeInFrames > 0 && framesDone < fadeInFrames) g *= framesDone.toFloat() / fadeInFrames
            if (fadeOutFrames > 0 && totalFrames > 0) {
                val left = totalFrames - framesDone
                if (left < fadeOutFrames) g *= (left.coerceAtLeast(0L)).toFloat() / fadeOutFrames
            }
            for (c in 0 until channels) {
                if (isFloat) {
                    output.putFloat((input.float * g).coerceIn(-1f, 1f))
                } else {
                    output.putShort(Math.round(input.short * g).coerceIn(-32768, 32767).toShort())
                }
            }
            framesDone++
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        framesDone = 0L
    }

    override fun onReset() {
        framesDone = 0L
    }
}

/** Silent WAV files used to pad audio sequences (images, muted clips, layer offsets). */
object SilenceWav {

    private const val RATE = 44_100
    private const val CHANNELS = 2

    /** Returns a cached silent stereo WAV exactly [durationMs] long. */
    fun ensure(dir: File, durationMs: Long): File {
        dir.mkdirs()
        val ms = durationMs.coerceAtLeast(1L)
        val file = File(dir, "silence_$ms.wav")
        val frames = ms * RATE / 1000L
        val dataBytes = frames * CHANNELS * 2
        if (file.isFile && file.length() == 44 + dataBytes) return file
        val tmp = File(dir, file.name + ".part")
        BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
            out.write(WavIO.header(RATE, CHANNELS, dataBytes))
            val zeros = ByteArray(64 * 1024)
            var left = dataBytes
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                out.write(zeros, 0, n)
                left -= n
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file
    }
}
