package com.pianoscribe.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

class UnsupportedAudioException(message: String) : IOException(message)

/**
 * Decodes any audio format the device supports (MP3, WAV, M4A/AAC, OGG/Opus/Vorbis, FLAC) with
 * MediaExtractor + MediaCodec, downmixes to mono and resamples to 22050 Hz while streaming
 * straight to a PCM file. With [stereoOut], a two-channel file's left and right channels are
 * also written (resampled the same way) for stereo separation.
 */
class AudioDecoder(private val context: Context) {
    /** [stereo] is true when the left/right files were written. */
    data class Result(val samples: Int, val rms: Double, val peak: Float, val stereo: Boolean = false)

    suspend fun decode(uri: Uri, out: File, stereoOut: Pair<File, File>? = null, onProgress: (Float) -> Unit): Result {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                android.util.Log.w("PianoScribe", "setDataSource failed for $uri", e)
                throw UnsupportedAudioException("This file can't be opened. It may be damaged or not an audio file. (${e.javaClass.simpleName}: ${e.message})")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("No audio track found in this file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0) }
            } catch (e: Exception) {
                throw UnsupportedAudioException("This phone has no decoder for $mime audio.")
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamingResampler(sampleRate, BasicPitch.SAMPLE_RATE)
            val writer = PcmFile.Writer(out)
            // Stereo side files: only for two or more channels; dropped if the format changes channel count.
            var stereo = stereoOut != null && channels >= 2
            val lw = if (stereo) PcmFile.Writer(stereoOut!!.first) else null
            val rw = if (stereo) PcmFile.Writer(stereoOut!!.second) else null
            var lRes = StreamingResampler(sampleRate, BasicPitch.SAMPLE_RATE)
            var rRes = StreamingResampler(sampleRate, BasicPitch.SAMPLE_RATE)
            try {
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastProgress = -1f
                var floatBuf = FloatArray(0)
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val t = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, size, t, 0)
                                extractor.advance()
                                if (durationUs > 0) {
                                    val p = (t.toDouble() / durationUs).toFloat().coerceIn(0f, 1f)
                                    if (p - lastProgress > 0.01f) { lastProgress = p; onProgress(p) }
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (channels < 2) stereo = false
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            if (newRate != sampleRate) {
                                writer.write(resampler.flush())
                                if (stereo) { lw!!.write(lRes.flush()); rw!!.write(rRes.flush()) }
                                sampleRate = newRate
                                resampler = StreamingResampler(sampleRate, BasicPitch.SAMPLE_RATE)
                                lRes = StreamingResampler(sampleRate, BasicPitch.SAMPLE_RATE)
                                rRes = StreamingResampler(sampleRate, BasicPitch.SAMPLE_RATE)
                            }
                        }
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(outIdx)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.LITTLE_ENDIAN)
                                val frames: Int
                                when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> {
                                        val n = info.size / 4
                                        if (floatBuf.size < n) floatBuf = FloatArray(n)
                                        buf.asFloatBuffer().get(floatBuf, 0, n)
                                        frames = n / channels
                                    }
                                    else -> {
                                        val n = info.size / 2
                                        if (floatBuf.size < n) floatBuf = FloatArray(n)
                                        val sb = buf.asShortBuffer()
                                        for (i in 0 until n) floatBuf[i] = sb.get(i) / 32768f
                                        frames = n / channels
                                    }
                                }
                                if (stereo) {
                                    lw!!.write(lRes.process(channel(floatBuf, channels, frames, 0, leftBuf).also { leftBuf = it }))
                                    rw!!.write(rRes.process(channel(floatBuf, channels, frames, 1, rightBuf).also { rightBuf = it }))
                                }
                                val mono = downmix(floatBuf, channels, frames)
                                writer.write(resampler.process(mono))
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                writer.write(resampler.flush())
                if (stereo) { lw!!.write(lRes.flush()); rw!!.write(rRes.flush()) }
            } finally {
                writer.close()
                lw?.close()
                rw?.close()
                runCatching { codec.stop() }
                codec.release()
            }
            onProgress(1f)
            if (writer.samples == 0L) throw UnsupportedAudioException("The file decoded to no audio.")
            // The channel files must line up with the mono file sample for sample.
            val stereoOk = stereo && lw!!.samples == writer.samples && rw!!.samples == writer.samples
            if (!stereoOk && stereoOut != null) { stereoOut.first.delete(); stereoOut.second.delete() }
            return Result(writer.samples.toInt(), kotlin.math.sqrt(writer.sumSquares / writer.samples), writer.peak, stereoOk)
        } finally {
            extractor.release()
        }
    }

    private var monoBuf = FloatArray(0)
    private var leftBuf = FloatArray(0)
    private var rightBuf = FloatArray(0)

    /** Channel [c] of interleaved audio, reusing [buf] when it has the right size. */
    private fun channel(x: FloatArray, channels: Int, frames: Int, c: Int, buf: FloatArray): FloatArray {
        val out = if (buf.size == frames) buf else FloatArray(frames)
        for (f in 0 until frames) out[f] = x[f * channels + c]
        return out
    }

    private fun downmix(x: FloatArray, channels: Int, frames: Int): FloatArray {
        if (monoBuf.size != frames) monoBuf = FloatArray(frames)
        if (channels <= 1) {
            System.arraycopy(x, 0, monoBuf, 0, frames)
            return monoBuf
        }
        val inv = 1f / channels
        for (f in 0 until frames) {
            var s = 0f
            val b = f * channels
            for (c in 0 until channels) s += x[b + c]
            monoBuf[f] = s * inv
        }
        return monoBuf
    }

    companion object {
        fun displayName(context: Context, uri: Uri): String {
            var name: String? = null
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            }
            return (name ?: uri.lastPathSegment ?: "Audio").substringBeforeLast('.')
        }
    }
}
