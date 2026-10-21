package com.autotube.ai.pipeline.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.domain.AudioIO
import com.autotube.ai.domain.PcmAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [AudioIO] on top of MediaExtractor + MediaCodec (MP3, AAC/M4A, Opus, Vorbis, FLAC, ...) with a
 * direct RIFF parser for WAV, band-limited resampling, 16-bit dithered WAV output and Sonic time
 * stretching.
 */
@Singleton
class AudioIOImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioIO {

    override suspend fun decode(file: File, sampleRate: Int, channels: Int): PcmAudio =
        decode(file, sampleRate, channels, Long.MAX_VALUE)

    /** Like [decode] but stops after [maxDurationMs] of audio (protects memory with very long files). */
    suspend fun decode(file: File, sampleRate: Int, channels: Int, maxDurationMs: Long): PcmAudio =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) {
                throw PipelineException("Audio file missing", "The audio file ${file.name} could not be found.")
            }
            val raw = try {
                if (WavIO.isWav(file)) {
                    val info = WavIO.readInfo(file)
                    val maxFrames = if (info != null && maxDurationMs != Long.MAX_VALUE) maxDurationMs * info.sampleRate / 1000 else Long.MAX_VALUE
                    runCatching { WavIO.read(file, maxFrames) }.getOrElse { decodeWithCodec(DataSource.FromFile(file), maxDurationMs) }
                } else {
                    decodeWithCodec(DataSource.FromFile(file), maxDurationMs)
                }
            } catch (e: PipelineException) {
                throw e
            } catch (e: IOException) {
                throw PipelineException("Could not read audio", "The audio file ${file.name} could not be decoded.", cause = e)
            } catch (e: IllegalStateException) {
                throw PipelineException("Could not read audio", "The audio file ${file.name} uses an unsupported format.", cause = e)
            }
            ensureActive()
            withContext(Dispatchers.Default) { Resampler.convert(raw, sampleRate, channels) }
        }

    override suspend fun decodeAsset(assetPath: String, sampleRate: Int, channels: Int): PcmAudio =
        decodeAsset(assetPath, sampleRate, channels, Long.MAX_VALUE)

    suspend fun decodeAsset(assetPath: String, sampleRate: Int, channels: Int, maxDurationMs: Long): PcmAudio =
        withContext(Dispatchers.IO) {
            val assets = context.assets
            val raw: PcmAudio = if (assetPath.endsWith(".wav", ignoreCase = true)) {
                assets.open(assetPath).use { WavIO.read(it, Long.MAX_VALUE) }
            } else {
                val fd: AssetFileDescriptor? = try {
                    assets.openFd(assetPath)
                } catch (_: FileNotFoundException) {
                    null // compressed inside the APK
                } catch (_: IOException) {
                    null
                }
                if (fd != null) {
                    fd.use { decodeWithCodec(DataSource.FromAsset(it), maxDurationMs) }
                } else {
                    val cache = File(context.cacheDir, "asset_audio/" + assetPath.replace('/', '_'))
                    if (!cache.exists() || cache.length() == 0L) {
                        cache.parentFile?.mkdirs()
                        assets.open(assetPath).use { input -> cache.outputStream().use { input.copyTo(it) } }
                    }
                    decodeWithCodec(DataSource.FromFile(cache), maxDurationMs)
                }
            }
            withContext(Dispatchers.Default) { Resampler.convert(raw, sampleRate, channels) }
        }

    override fun fromPcm16(bytes: ByteArray, sampleRate: Int, channels: Int): PcmAudio {
        val count = bytes.size / 2
        val out = FloatArray(count - count % channels.coerceAtLeast(1))
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) out[i] = bb.short / 32768f
        return PcmAudio(out, sampleRate, channels)
    }

    override suspend fun writeWav(audio: PcmAudio, outFile: File): File = withContext(Dispatchers.IO) {
        WavIO.writePcm16(outFile, audio.samples, audio.sampleRate, audio.channels)
    }

    override suspend fun durationMs(file: File): Long = withContext(Dispatchers.IO) {
        WavIO.readInfo(file)?.let { info -> if (info.frames > 0) return@withContext info.durationMs }
        val fromRetriever = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
        if (fromRetriever != null && fromRetriever > 0) return@withContext fromRetriever
        val pcm = decodeWithCodec(DataSource.FromFile(file), Long.MAX_VALUE)
        pcm.durationMs
    }

    override fun timeStretch(audio: PcmAudio, speed: Float): PcmAudio {
        val s = speed.coerceIn(0.25f, 4f)
        if (abs(s - 1f) < 0.001f || audio.frames == 0) return PcmAudio(audio.samples.copyOf(), audio.sampleRate, audio.channels)
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(s)
        sonic.setPitch(1f)
        try {
            sonic.configure(AudioProcessor.AudioFormat(audio.sampleRate, audio.channels, C.ENCODING_PCM_16BIT))
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw PipelineException("Speed change failed", "This audio format cannot be re-timed.", cause = e)
        }
        sonic.flush()
        val out = GrowableFloats((audio.samples.size / s).toInt() + 4096)
        val chunkFrames = 8192
        val input = ByteBuffer.allocateDirect(chunkFrames * audio.channels * 2).order(ByteOrder.nativeOrder())
        val src = audio.samples
        var pos = 0
        fun drain() {
            while (true) {
                val o = sonic.output
                if (!o.hasRemaining()) break
                val ob = o.order(ByteOrder.nativeOrder())
                while (ob.remaining() >= 2) out.add(ob.short / 32768f)
            }
        }
        while (pos < src.size) {
            input.clear()
            val n = minOf(chunkFrames * audio.channels, src.size - pos)
            for (i in 0 until n) {
                val v = (src[pos + i] * 32767f).coerceIn(-32768f, 32767f)
                input.putShort(Math.round(v).toShort())
            }
            input.flip()
            while (input.hasRemaining()) {
                sonic.queueInput(input)
                drain()
            }
            pos += n
        }
        sonic.queueEndOfStream()
        var guard = 0
        while (!sonic.isEnded && guard++ < 100_000) drain()
        drain()
        sonic.reset()
        return PcmAudio(out.toArray(), audio.sampleRate, audio.channels)
    }

    // ------------------------------------------------------------------ MediaCodec decoding

    private sealed interface DataSource {
        fun applyTo(extractor: MediaExtractor)

        class FromFile(val file: File) : DataSource {
            override fun applyTo(extractor: MediaExtractor) = extractor.setDataSource(file.absolutePath)
        }

        class FromAsset(val fd: AssetFileDescriptor) : DataSource {
            override fun applyTo(extractor: MediaExtractor) =
                extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        }
    }

    private fun decodeWithCodec(source: DataSource, maxDurationMs: Long): PcmAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            source.applyTo(extractor)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Audio track without MIME type")

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val maxUs = if (maxDurationMs == Long.MAX_VALUE) Long.MAX_VALUE else maxDurationMs * 1000
            val out = GrowableFloats(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleLoops = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex) ?: throw IOException("Decoder input buffer unavailable")
                        val size = extractor.readSampleData(buf, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > maxUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, time.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        idleLoops = 0
                        if (info.size > 0) {
                            val ob = decoder.getOutputBuffer(outIndex)
                            if (ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val ordered = ob.order(ByteOrder.nativeOrder())
                                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    while (ordered.remaining() >= 4) out.add(ordered.float)
                                } else {
                                    while (ordered.remaining() >= 2) out.add(ordered.short / 32768f)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    else -> {
                        // INFO_TRY_AGAIN_LATER
                        if (inputDone && ++idleLoops > 500) outputDone = true // ~5 s with no output after EOS
                    }
                }
            }
            if (channels <= 0) throw IOException("Invalid channel count")
            val samples = out.toArray()
            val usable = samples.size - samples.size % channels
            return PcmAudio(if (usable == samples.size) samples else samples.copyOf(usable), sampleRate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
