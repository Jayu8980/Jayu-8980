package com.pianoscribe.app.audio

import com.pianoscribe.core.transcribe.BasicPitch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Decoded or recorded audio waiting to be trimmed and converted. */
data class Draft(
    val file: File,
    val name: String,
    val source: String,
    val lengthSamples: Int,
    val sampleRate: Int = BasicPitch.SAMPLE_RATE,
) {
    val durationSec: Double get() = lengthSamples.toDouble() / sampleRate
}

/**
 * Mono float32 little-endian PCM stored on disk. Audio is kept on disk and read in ranges so a
 * long song never has to sit in memory more than once.
 */
object PcmFile {
    const val BYTES_PER_SAMPLE = 4

    class Writer(file: File) : AutoCloseable {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private val buf = ByteBuffer.allocate(1 shl 15).order(ByteOrder.LITTLE_ENDIAN)
        var samples = 0L
            private set
        var sumSquares = 0.0
            private set
        var peak = 0f
            private set

        fun write(x: FloatArray, off: Int = 0, len: Int = x.size - off) {
            for (i in off until off + len) {
                val v = x[i]
                sumSquares += v.toDouble() * v
                if (abs(v) > peak) peak = abs(v)
                if (buf.remaining() < 4) flushBuf()
                buf.putFloat(v)
            }
            samples += len
        }

        private fun flushBuf() {
            out.write(buf.array(), 0, buf.position())
            buf.clear()
        }

        override fun close() {
            flushBuf()
            out.close()
        }
    }

    fun length(file: File): Int = (file.length() / BYTES_PER_SAMPLE).toInt()

    /** Reads [count] samples starting at [start] into dst[off..]. Returns samples read. */
    fun read(file: File, start: Int, dst: FloatArray, off: Int, count: Int): Int {
        RandomAccessFile(file, "r").use { raf -> return read(raf, start, dst, off, count) }
    }

    fun read(raf: RandomAccessFile, start: Int, dst: FloatArray, off: Int, count: Int): Int {
        val total = (raf.length() / BYTES_PER_SAMPLE).toInt()
        val n = minOf(count, total - start).coerceAtLeast(0)
        if (n == 0) return 0
        val bytes = ByteArray(n * BYTES_PER_SAMPLE)
        raf.seek(start.toLong() * BYTES_PER_SAMPLE)
        raf.readFully(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(dst, off, n)
        return n
    }

    fun readAll(file: File): FloatArray {
        val n = length(file)
        val out = FloatArray(n)
        read(file, 0, out, 0, n)
        return out
    }

    /** Copies samples [from, to) into a new file. */
    fun copyRange(src: File, from: Int, to: Int, dst: File) {
        RandomAccessFile(src, "r").use { raf ->
            Writer(dst).use { w ->
                val chunk = FloatArray(1 shl 16)
                var p = from
                while (p < to) {
                    val n = read(raf, p, chunk, 0, minOf(chunk.size, to - p))
                    if (n <= 0) break
                    w.write(chunk, 0, n)
                    p += n
                }
            }
        }
    }

    /** Peak envelope in [buckets] buckets for waveform drawing (0..1). */
    fun waveform(file: File, buckets: Int): FloatArray {
        val total = length(file)
        val out = FloatArray(buckets)
        if (total == 0) return out
        val per = maxOf(1, total / buckets)
        val chunk = FloatArray(per)
        RandomAccessFile(file, "r").use { raf ->
            for (b in 0 until buckets) {
                val start = (b.toLong() * total / buckets).toInt()
                val n = read(raf, start, chunk, 0, per)
                var m = 0f
                // Subsample long buckets for speed.
                val step = maxOf(1, n / 2048)
                var i = 0
                while (i < n) { m = maxOf(m, abs(chunk[i])); i += step }
                out[b] = m
            }
        }
        val max = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] = (out[i] / max).coerceIn(0f, 1f)
        return out
    }
}
