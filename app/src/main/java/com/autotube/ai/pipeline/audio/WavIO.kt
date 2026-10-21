package com.autotube.ai.pipeline.audio

import com.autotube.ai.domain.PcmAudio
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Header facts of a RIFF/WAVE file. */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val dataBytes: Long,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val frames: Long get() = if (bytesPerFrame == 0) 0 else dataBytes / bytesPerFrame
    val durationMs: Long get() = if (sampleRate == 0) 0 else frames * 1000L / sampleRate
}

/**
 * Minimal, robust RIFF/WAVE reader and writer: PCM 8/16/24/32-bit integer and 32/64-bit float,
 * including WAVE_FORMAT_EXTENSIBLE. Writing is always 16-bit PCM with TPDF dither.
 */
object WavIO {

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun isWav(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            if (input.read(head) < 12) return@use false
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    fun readInfo(file: File): WavInfo? = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream())).use { readHeader(it) }
    }.getOrNull()

    /** Reads a WAV file into interleaved floats (native rate and channel count). */
    fun read(file: File, maxFrames: Long = Long.MAX_VALUE): PcmAudio =
        file.inputStream().use { read(it, maxFrames) }

    fun read(stream: InputStream, maxFrames: Long = Long.MAX_VALUE): PcmAudio {
        val input = DataInputStream(BufferedInputStream(stream, 1 shl 16))
        val info = readHeader(input) ?: throw IOException("Not a supported WAV file")
        val bytesPerSample = info.bitsPerSample / 8
        if (bytesPerSample <= 0 || info.channels <= 0) throw IOException("Unsupported WAV layout")
        val framesAvailable = if (info.dataBytes in 1 until 0xFFFFFFF0L) info.frames else Long.MAX_VALUE
        val frames = minOf(framesAvailable, maxFrames)
        val out = GrowableFloats(if (frames == Long.MAX_VALUE) 1 shl 20 else (frames * info.channels).toInt())
        val chunk = ByteArray(info.bytesPerFrame * 4096)
        val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        var remainingFrames = frames
        while (remainingFrames > 0) {
            val wantFrames = minOf(4096L, remainingFrames).toInt()
            val wantBytes = wantFrames * info.bytesPerFrame
            val got = readFully(input, chunk, wantBytes)
            val gotFrames = got / info.bytesPerFrame
            if (gotFrames <= 0) break
            bb.clear()
            repeat(gotFrames * info.channels) {
                out.add(readSample(bb, info))
            }
            remainingFrames -= gotFrames
            if (got < wantBytes) break
        }
        return PcmAudio(out.toArray(), info.sampleRate, info.channels)
    }

    private fun readSample(bb: ByteBuffer, info: WavInfo): Float = when {
        info.isFloat && info.bitsPerSample == 32 -> bb.float
        info.isFloat && info.bitsPerSample == 64 -> bb.double.toFloat()
        info.bitsPerSample == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
        info.bitsPerSample == 16 -> bb.short / 32768f
        info.bitsPerSample == 24 -> {
            val b0 = bb.get().toInt() and 0xFF
            val b1 = bb.get().toInt() and 0xFF
            val b2 = bb.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
        }
        info.bitsPerSample == 32 -> bb.int / 2147483648f
        else -> throw IOException("Unsupported WAV bit depth ${info.bitsPerSample}")
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Parses chunks up to the start of "data"; leaves the stream positioned at the sample data. */
    private fun readHeader(input: DataInputStream): WavInfo? {
        val riff = ByteArray(12)
        try {
            input.readFully(riff)
        } catch (_: EOFException) {
            return null
        }
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        val idBytes = ByteArray(4)
        while (true) {
            try {
                input.readFully(idBytes)
            } catch (_: EOFException) {
                return null
            }
            val id = String(idBytes, Charsets.US_ASCII)
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size.toInt())
                    input.readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    format = b.short.toInt() and 0xFFFF
                    channels = b.short.toInt() and 0xFFFF
                    sampleRate = b.int
                    b.int // byte rate
                    b.short // block align
                    bits = b.short.toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE && fmt.size >= 26) {
                        // cbSize(2) validBits(2) channelMask(4) then SubFormat GUID; first 2 bytes = format code.
                        b.position(24)
                        format = b.short.toInt() and 0xFFFF
                    }
                    if (size % 2 == 1L) input.skipBytes(1)
                }
                "data" -> {
                    if (format != FORMAT_PCM && format != FORMAT_FLOAT) return null
                    if (channels <= 0 || sampleRate <= 0) return null
                    return WavInfo(sampleRate, channels, bits, format == FORMAT_FLOAT, size)
                }
                else -> {
                    var toSkip = size + (size and 1L)
                    while (toSkip > 0) {
                        val skipped = input.skip(toSkip)
                        if (skipped <= 0) {
                            if (input.read() < 0) return null
                            toSkip--
                        } else {
                            toSkip -= skipped
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes interleaved floats as 16-bit PCM with TPDF dither. [srcChannels] -> [outChannels]:
     * mono is duplicated to every output channel, otherwise channels are taken in order.
     */
    fun writePcm16(
        outFile: File,
        samples: FloatArray,
        sampleRate: Int,
        srcChannels: Int,
        outChannels: Int = srcChannels,
        gain: Float = 1f,
        seed: Long = 0x5EEDL,
    ): File {
        outFile.parentFile?.mkdirs()
        val frames = samples.size / srcChannels
        val dataBytes = frames.toLong() * outChannels * 2
        val tmp = File(outFile.parentFile, outFile.name + ".part")
        val random = Random(seed)
        BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
            out.write(header(sampleRate, outChannels, dataBytes))
            val block = ByteBuffer.allocate(4096 * outChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
            var f = 0
            while (f < frames) {
                block.clear()
                val n = minOf(4096, frames - f)
                for (k in 0 until n) {
                    val base = (f + k) * srcChannels
                    for (c in 0 until outChannels) {
                        val src = if (srcChannels == 1) samples[base] else samples[base + minOf(c, srcChannels - 1)]
                        // TPDF dither: sum of two uniform variables, +-1 LSB peak.
                        val dither = (random.nextFloat() - random.nextFloat())
                        val v = src * gain * 32767f + dither
                        val q = Math.round(v).coerceIn(-32768, 32767)
                        block.putShort(q.toShort())
                    }
                }
                out.write(block.array(), 0, block.position())
                f += n
            }
        }
        if (outFile.exists()) outFile.delete()
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
        return outFile
    }

    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val clampedData = dataBytes.coerceAtMost(0xFFFFFFFFL - 36)
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + clampedData).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(FORMAT_PCM.toShort())
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * 2)
        b.putShort((channels * 2).toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(clampedData.toInt())
        return b.array()
    }

    /** Fixes the RIFF/data sizes of a WAV written with unknown length. */
    fun patchSizes(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataBytes = raf.length() - 44
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4); raf.write(b.putInt((36 + dataBytes).toInt()).array())
            b.clear()
            raf.seek(40); raf.write(b.putInt(dataBytes.toInt()).array())
        }
    }
}

/** Append-only FloatArray builder without boxing. */
class GrowableFloats(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) grow(size + 1)
        data[size++] = v
    }

    fun addAll(src: FloatArray, offset: Int = 0, length: Int = src.size - offset) {
        if (size + length > data.size) grow(size + length)
        System.arraycopy(src, offset, data, size, length)
        size += length
    }

    private fun grow(min: Int) {
        var cap = data.size
        while (cap < min) cap = if (cap < (1 shl 26)) cap * 2 else cap + (cap shr 1)
        data = data.copyOf(cap)
    }

    fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
}
