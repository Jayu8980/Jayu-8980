package com.autotube.ai.pipeline.voice

import com.autotube.ai.core.model.LongPair
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.pipeline.script.Seeded
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Makes raw TTS output sound edited: trims lead/tail silence, compresses only LONG internal silences
 * (keeping natural breaths), applies a gentle 80 Hz high-pass and peak-normalises to -3 dBFS.
 * Every cut is recorded in a [TimeMap] so word timings can be remapped onto the processed audio.
 *
 * All functions are pure CPU work on mono float samples; call them on Dispatchers.Default.
 */
internal object VoicePostProcessor {

    const val SAMPLE_RATE = 48_000
    private const val FRAME_MS = 10
    private const val FADE_SAMPLES = 240 // 5 ms at 48 kHz
    private const val TARGET_PEAK = 0.7079f // -3 dBFS
    private const val MAX_GAIN = 8f

    data class Options(
        val leadMs: Long = 80,
        val tailMs: Long = 150,
        /** Internal silences longer than this are shortened. */
        val longSilenceMs: Long = 650,
        val compressMinMs: Long = 320,
        val compressMaxMs: Long = 420,
        /** Remaining silences longer than this are reported as pauses. */
        val pauseReportMs: Long = 180,
        val highPass: Boolean = true,
        val normalize: Boolean = true,
        /** Seed for the slight randomisation of compressed silences. */
        val seed: Int = 0,
    )

    class Result(val samples: FloatArray, val map: TimeMap, val pauses: List<LongPair>) {
        val durationMs: Long get() = samples.size * 1000L / SAMPLE_RATE
    }

    /** Piecewise mapping from source time to processed time. */
    class TimeMap(private val segments: List<Seg>, private val totalSamples: Int) {
        /** [srcStart, srcEnd) in source samples, placed at [dstStart] in the output. */
        class Seg(val srcStart: Int, val srcEnd: Int, val dstStart: Int) {
            val dstEnd: Int get() = dstStart + (srcEnd - srcStart)
        }

        /** Maps a source time; times inside removed audio snap to the nearest kept edge. */
        fun map(ms: Long, isEnd: Boolean): Long {
            if (segments.isEmpty()) return ms
            val s = (ms * SAMPLE_RATE / 1000L).toInt()
            val first = segments.first()
            if (s < first.srcStart) return toMs(first.dstStart)
            for ((i, seg) in segments.withIndex()) {
                if (s < seg.srcEnd) {
                    if (s >= seg.srcStart) return toMs(seg.dstStart + (s - seg.srcStart))
                    // Inside a removed gap before this segment.
                    val prev = segments[i - 1]
                    return if (isEnd) toMs(prev.dstEnd) else toMs(seg.dstStart)
                }
            }
            return toMs(min(segments.last().dstEnd, totalSamples))
        }

        fun mapWords(words: List<WordTiming>, durationMs: Long): List<WordTiming> = words.map { w ->
            val start = map(w.startMs, isEnd = false).coerceIn(0, durationMs)
            val end = map(w.endMs, isEnd = true).coerceIn(start, durationMs)
            WordTiming(w.word, start, max(end, min(start + 1, durationMs)))
        }

        private fun toMs(sample: Int): Long = sample * 1000L / SAMPLE_RATE

        companion object {
            fun identity(totalSamples: Int) = TimeMap(listOf(Seg(0, totalSamples, 0)), totalSamples)
        }
    }

    fun process(input: FloatArray, opt: Options): Result {
        if (input.isEmpty()) return Result(input, TimeMap.identity(0), emptyList())
        val frame = SAMPLE_RATE * FRAME_MS / 1000
        val silent = silentFrames(input, frame)
        val firstVoiced = silent.indexOfFirst { !it }
        val lastVoiced = silent.indexOfLast { !it }
        if (firstVoiced < 0) {
            // Nothing but silence/noise: keep as is so the caller can still measure it.
            return Result(input.copyOf(), TimeMap.identity(input.size), emptyList())
        }
        val speechStart = firstVoiced * frame
        val speechEnd = min(input.size, (lastVoiced + 1) * frame)
        val leadSamples = msToSamples(opt.leadMs)
        val tailSamples = msToSamples(opt.tailMs)
        val keepStart = max(0, speechStart - leadSamples)
        val keepEnd = min(input.size, speechEnd + tailSamples)
        val padFront = leadSamples - (speechStart - keepStart)
        val padBack = tailSamples - (keepEnd - speechEnd)

        // Internal long silences -> shortened, slightly randomised.
        val minLongFrames = (opt.longSilenceMs / FRAME_MS).toInt()
        val kept = mutableListOf<IntArray>() // [srcStart, srcEnd)
        var cursor = keepStart
        var f = firstVoiced
        var runIndex = 0
        while (f <= lastVoiced) {
            if (!silent[f]) { f++; continue }
            val runStart = f
            while (f <= lastVoiced && silent[f]) f++
            val runLen = f - runStart
            if (runLen >= minLongFrames) {
                val rs = runStart * frame
                val re = f * frame
                val targetMs = opt.compressMinMs +
                    (Seeded.unit(opt.seed, runIndex, "pause") * (opt.compressMaxMs - opt.compressMinMs)).toLong()
                val target = msToSamples(targetMs).coerceAtMost(re - rs)
                val half1 = target / 2
                val half2 = target - half1
                kept += intArrayOf(cursor, rs + half1)
                cursor = re - half2
            }
            runIndex++
        }
        kept += intArrayOf(cursor, keepEnd)

        val keptLength = kept.sumOf { it[1] - it[0] }
        val out = FloatArray(padFront + keptLength + padBack)
        val segments = ArrayList<TimeMap.Seg>(kept.size)
        var dst = padFront
        for ((i, k) in kept.withIndex()) {
            val len = k[1] - k[0]
            if (len <= 0) continue
            System.arraycopy(input, k[0], out, dst, len)
            if (i > 0) fade(out, dst, len, fadeIn = true)
            if (i < kept.size - 1) fade(out, dst, len, fadeIn = false)
            segments += TimeMap.Seg(k[0], k[1], dst)
            dst += len
        }

        if (opt.highPass) highPass(out, 80.0)
        if (opt.normalize) normalize(out)
        val pauses = detectPauses(out, opt.pauseReportMs)
        return Result(out, TimeMap(segments, out.size), pauses)
    }

    /** Linear-interpolation resampler (speech is band-limited well below Nyquist after TTS). */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty() || fromRate <= 0) return input
        val outLen = (input.size.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] + (input[i1] - input[i0]) * frac
        }
        return out
    }

    /** Interleaved multi-channel -> mono average. */
    fun downmix(samples: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            sum / channels
        }
    }

    /** Silences (start, end) in ms longer than [minMs], excluding the leading and trailing silence. */
    fun detectPauses(samples: FloatArray, minMs: Long): List<LongPair> {
        val frame = SAMPLE_RATE * FRAME_MS / 1000
        val silent = silentFrames(samples, frame)
        val first = silent.indexOfFirst { !it }
        val last = silent.indexOfLast { !it }
        if (first < 0) return emptyList()
        val minFrames = (minMs / FRAME_MS).toInt().coerceAtLeast(1)
        val pauses = mutableListOf<LongPair>()
        var f = first
        while (f <= last) {
            if (!silent[f]) { f++; continue }
            val start = f
            while (f <= last && silent[f]) f++
            if (f - start >= minFrames) pauses += LongPair(start * FRAME_MS.toLong(), f * FRAME_MS.toLong())
        }
        return pauses
    }

    private fun silentFrames(samples: FloatArray, frame: Int): BooleanArray {
        val count = (samples.size + frame - 1) / frame
        val rms = FloatArray(count)
        var peakRms = 0f
        for (i in 0 until count) {
            val from = i * frame
            val to = min(samples.size, from + frame)
            var sum = 0.0
            for (k in from until to) sum += samples[k] * samples[k]
            val r = sqrt(sum / max(1, to - from)).toFloat()
            rms[i] = r
            if (r > peakRms) peakRms = r
        }
        val threshold = max(0.0035f, peakRms * 0.025f)
        return BooleanArray(count) { rms[it] < threshold }
    }

    private fun fade(buf: FloatArray, start: Int, len: Int, fadeIn: Boolean) {
        val n = min(FADE_SAMPLES, len / 2)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            if (fadeIn) buf[start + i] *= g else buf[start + len - 1 - i] *= g
        }
    }

    /** RBJ biquad high-pass, Q = 0.707. */
    private fun highPass(buf: FloatArray, cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / SAMPLE_RATE
        val alpha = sin(w0) / (2.0 * 0.7071)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 + cosW) / 2.0) / a0
        val b1 = (-(1.0 + cosW)) / a0
        val b2 = ((1.0 + cosW) / 2.0) / a0
        val a1 = (-2.0 * cosW) / a0
        val a2 = (1.0 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in buf.indices) {
            val x = buf[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            buf[i] = y.toFloat()
        }
    }

    private fun normalize(buf: FloatArray) {
        var peak = 0f
        for (v in buf) { val a = abs(v); if (a > peak) peak = a }
        if (peak < 1e-4f) return
        val gain = min(TARGET_PEAK / peak, MAX_GAIN)
        for (i in buf.indices) buf[i] = (buf[i] * gain).coerceIn(-1f, 1f)
    }

    private fun msToSamples(ms: Long): Int = (ms * SAMPLE_RATE / 1000L).toInt()
}
