package com.pianoscribe.core.transcribe

import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Transkun 2.0 piano transcription (Yan & Duan, "Skipping the Frame-Level: Event-Based Piano
 * Transcription With Neural Semi-CRFs", NeurIPS 2021; MIT licence): notes are scored as whole
 * intervals (onset to offset) per key and the best non-overlapping set is decoded exactly.
 *
 * The network runs in ONNX Runtime ([Net]); the rest is here, as the official pipeline does it:
 * the front end (six analysis windows, real FFT, mel filters, log), semi-CRF Viterbi decoding,
 * onset/offset refinement and velocity, and stitching of 16 s segments taken every 8 s.
 */
object Transkun {
    const val SAMPLE_RATE = 44100
    private const val MEL_EPS = 1e-5
    private const val SEGMENT_SEC = 16.0
    private const val HOP_SEC = 8.0

    /** The front end's constants and the model's shape, from frontend.bin (written by the export script). */
    class Frontend(
        val fs: Int, val hop: Int, val win: Int, val nWin: Int, val nMel: Int, val nFrame: Int,
        /** MIDI pitch per output symbol; negative numbers are pedals. */
        val pitches: IntArray,
        val d: Int,
        /** [nWin][win] analysis windows. */
        val windows: Array<FloatArray>,
        /** Mel filter m covers FFT bins melLo[m] until melLo[m] + melW[m].size. */
        val melLo: IntArray,
        val melW: Array<FloatArray>,
    ) {
        val segmentSamples: Int = ceil(SEGMENT_SEC * fs).toInt()
        val featureSize: Int get() = nFrame * nMel * nWin

        companion object {
            fun parse(bytes: ByteArray): Frontend {
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val fs = b.int; val hop = b.int; val win = b.int; val nWin = b.int; val nMel = b.int
                val nFrame = b.int; val nSym = b.int; val d = b.int
                val pitches = IntArray(nSym) { b.int }
                val windows = Array(nWin) { FloatArray(win) { b.float } }
                val nBins = win / 2 + 1
                val fb = FloatArray(nBins * nMel) { b.float } // [bin][mel]
                val lo = IntArray(nMel); val w = arrayOfNulls<FloatArray>(nMel)
                for (m in 0 until nMel) {
                    var first = -1; var last = -1
                    for (k in 0 until nBins) if (fb[k * nMel + m] != 0f) { if (first < 0) first = k; last = k }
                    if (first < 0) { lo[m] = 0; w[m] = FloatArray(0); continue }
                    lo[m] = first; w[m] = FloatArray(last - first + 1) { fb[(first + it) * nMel + m] }
                }
                return Frontend(fs, hop, win, nWin, nMel, nFrame, pitches, d, windows, lo, w.requireNoNulls())
            }
        }
    }

    /**
     * Log-mel features of one segment ([Frontend.segmentSamples] samples at [Frontend.fs]) as
     * [1, nFrame, nMel, nWin]: frames centred every hop, normalised to zero mean and unit variance
     * over the whole segment, each through every window, |rfft|² (orthonormal), mel, log.
     */
    fun features(fe: Frontend, seg: FloatArray, out: FloatArray = FloatArray(fe.featureSize)): FloatArray {
        val win = fe.win; val hop = fe.hop
        val lPad = win / 2
        // Frame t covers padded samples [t*hop, t*hop + win) = seg[t*hop - lPad, ...).
        fun sample(i: Int) = if (i in seg.indices) seg[i].toDouble() else 0.0
        // Mean and (unbiased) standard deviation over all frames, as torch.std over the frame tensor.
        val prefix = DoubleArray(seg.size + 1); val prefix2 = DoubleArray(seg.size + 1)
        for (i in seg.indices) { prefix[i + 1] = prefix[i] + seg[i]; prefix2[i + 1] = prefix2[i] + seg[i].toDouble() * seg[i] }
        var sum = 0.0; var sum2 = 0.0
        for (t in 0 until fe.nFrame) {
            val a = (t * hop - lPad).coerceIn(0, seg.size); val b = (t * hop - lPad + win).coerceIn(0, seg.size)
            sum += prefix[b] - prefix[a]; sum2 += prefix2[b] - prefix2[a]
        }
        val n = fe.nFrame.toDouble() * win
        val mean = sum / n
        val std = sqrt(((sum2 - n * mean * mean) / (n - 1)).coerceAtLeast(0.0))
        val scale = 1.0 / (std + 1e-8)
        val frame = DoubleArray(win)
        val re = DoubleArray(win); val im = DoubleArray(win)
        val nBins = win / 2 + 1
        val power = Array(fe.nWin) { DoubleArray(nBins) }
        val norm = 1.0 / win // orthonormal FFT, squared
        val logEps = ln(MEL_EPS)
        for (t in 0 until fe.nFrame) {
            val base = t * hop - lPad
            for (i in 0 until win) frame[i] = (sample(base + i) - mean) * scale
            // Two real windows per complex FFT: x = a + i b, A[k] = (X[k] + X*[N-k]) / 2, B[k] = (X[k] - X*[N-k]) / 2i.
            var w = 0
            while (w < fe.nWin) {
                val wa = fe.windows[w]; val wb = if (w + 1 < fe.nWin) fe.windows[w + 1] else null
                for (i in 0 until win) { re[i] = frame[i] * wa[i]; im[i] = if (wb != null) frame[i] * wb[i] else 0.0 }
                Fft.fft(re, im)
                for (k in 0 until nBins) {
                    val j = (win - k) % win
                    val ar = (re[k] + re[j]) * 0.5; val ai = (im[k] - im[j]) * 0.5
                    power[w][k] = (ar * ar + ai * ai) * norm
                    if (wb != null) {
                        val br = (im[k] + im[j]) * 0.5; val bi = (re[j] - re[k]) * 0.5
                        power[w + 1][k] = (br * br + bi * bi) * norm
                    }
                }
                w += 2
            }
            for (m in 0 until fe.nMel) {
                val lo = fe.melLo[m]; val ws = fe.melW[m]
                for (c in 0 until fe.nWin) {
                    val p = power[c]
                    var acc = 0.0
                    for (k in ws.indices) acc += p[lo + k] * ws[k]
                    out[(t * fe.nMel + m) * fe.nWin + c] = ((ln(acc + MEL_EPS) - logEps) / -logEps).toFloat()
                }
            }
        }
        return out
    }

    /** Interval scores of some symbols: [n, T(begin), T(end)] and single-frame scores [n, T]. */
    class Scores(val s: FloatBuffer, val diag: FloatBuffer)

    /**
     * The three ONNX models. The network's per-symbol context comes out in chunks of a few
     * symbols, read one at a time, so only one chunk is ever in the app's heap.
     */
    interface Net {
        /** Runs the network on one segment's features; its outputs are then read with [ctx]. */
        fun core(features: FloatArray)
        /** Number of context chunks per segment. */
        val chunks: Int
        /** Context [n, T, D] of chunk [index] of the last [core] run. */
        fun ctx(index: Int): FloatArray
        /** Interval and single-frame scores for a context chunk of [n] symbols. */
        fun score(ctx: FloatArray, n: Int): Scores
        /** Velocity logits [n, 128] and onset/offset refinement [n, 4] for interval features [n, 3D]. */
        fun heads(attr: FloatArray, n: Int): Pair<FloatArray, FloatArray>
    }

    /**
     * Best set of non-overlapping intervals for one symbol (semi-CRF Viterbi, run backwards as the
     * official decoder does). [s] holds scores begin-major at [off] + b*T + e, [diag] the
     * single-frame scores. Decoding starts at frame [start]. Returns (begin, end) pairs in order.
     */
    internal fun viterbiBackward(s: FloatBuffer, off: Int, diag: FloatBuffer, diagOff: Int, t: Int, start: Int): List<IntArray> {
        val q = DoubleArray(t); val ptr = IntArray(t)
        fun d(i: Int) = diag.get(diagOff + i).toDouble()
        val dl = d(t - 1)
        q[t - 1] = if (dl > 0) dl else 0.0
        for (b in t - 2 downTo 0) {
            var best = q[b + 1] // skip comes first, so it wins ties as torch.max picks the first maximum
            var arg = -1
            val row = off + b * t
            for (e in b + 1 until t) {
                val v = q[e] + s.get(row + e)
                if (v > best) { best = v; arg = e }
            }
            ptr[b] = arg
            val db = d(b)
            q[b] = best + if (db > 0) db else 0.0
        }
        val out = ArrayList<IntArray>()
        var j = start
        while (j < t - 1) {
            if (d(j) > 0) out += intArrayOf(j, j)
            val p = ptr[j]
            if (p < 0) j++ else { out += intArrayOf(j, p); j = p }
        }
        if (dl > 0) out += intArrayOf(t - 1, t - 1)
        return out
    }

    /** Mean of a continuous Bernoulli distribution with these logits (torch's formula, Taylor series near 0.5). */
    internal fun continuousBernoulliMean(logit: Float): Double {
        val p = 1.0 / (1.0 + exp(-logit.toDouble()))
        if (p < 0.499 || p > 0.501) return p / (2 * p - 1) + 1 / (ln1p(-p) - ln(p))
        val x = p - 0.5
        return 0.5 + (1.0 / 3.0 + 16.0 / 45.0 * x * x) * x
    }

    private class Event(var start: Double, var end: Double, val pitch: Int, val velocity: Int, val hasOnset: Boolean, var hasOffset: Boolean)

    /** Segments a recording of [samples] samples is cut into. */
    fun segmentCount(fe: Frontend, samples: Int): Int {
        val padN = ceil((SEGMENT_SEC - HOP_SEC) * fe.fs).toInt()
        val step = ceil(HOP_SEC * fe.fs / fe.hop).toInt() * fe.hop
        return (samples + 2 * padN + step - 1) / step
    }

    /**
     * Transcribes [audio] (mono, [Frontend.fs]). [onProgress] gets (segments done, total).
     * Pedals are decoded by the model but not returned.
     */
    fun transcribe(
        fe: Frontend,
        net: Net,
        audio: FloatArray,
        ids: NoteIdGenerator = NoteIdGenerator(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Note> {
        val fs = fe.fs; val hop = fe.hop; val t = fe.nFrame; val d = fe.d
        val padT = SEGMENT_SEC - HOP_SEC
        val padN = ceil(padT * fs).toInt()
        val n = audio.size + 2 * padN
        val step = ceil(HOP_SEC * fs / hop).toInt() * hop
        val segSize = fe.segmentSamples
        val lastIdx = (segSize.toDouble() / hop).roundToInt()
        val frameDur = hop.toDouble() / fs
        val nSym = fe.pitches.size
        val startPos = IntArray(nSym) { floor(padT * fs / hop).toInt() }
        val byPitch = HashMap<Int, ArrayList<Event>>()
        val seg = FloatArray(segSize)
        val feat = FloatArray(fe.featureSize)
        val total = segmentCount(fe, audio.size)
        var segIndex = 0
        var i = 0
        while (i < n) {
            // seg = padded[i, i + segSize), padded = padN zeros + audio + padN zeros.
            for (k in 0 until segSize) {
                val src = i + k - padN
                seg[k] = if (i + k < n && src in audio.indices) audio[src] else 0f
            }
            val begin = i.toDouble() / fs - padT
            features(fe, seg, feat)
            net.core(feat)
            val events = ArrayList<Event>()
            val lastP = IntArray(nSym)
            var s0 = 0
            for (chunk in 0 until net.chunks) {
                val ctx = net.ctx(chunk)
                val cnt = ctx.size / (t * d)
                val scores = net.score(ctx, cnt)
                for (c in 0 until cnt) {
                    val sym = s0 + c
                    val iv = viterbiBackward(scores.s, c * t * t, scores.diag, c * t, t, startPos[sym])
                    if (iv.isEmpty()) continue
                    val attr = FloatArray(iv.size * 3 * d)
                    for ((r, pair) in iv.withIndex()) {
                        val a = (c * t + pair[0]) * d; val b = (c * t + pair[1]) * d
                        val o = r * 3 * d
                        for (x in 0 until d) {
                            val ca = ctx[a + x]; val cb = ctx[b + x]
                            attr[o + x] = ca; attr[o + d + x] = cb; attr[o + 2 * d + x] = ca * cb
                        }
                    }
                    val (vel, of) = net.heads(attr, iv.size)
                    var lastEnd = 0.0
                    var curLast = 0
                    for ((r, pair) in iv.withIndex()) {
                        var v = 0; var best = Float.NEGATIVE_INFINITY
                        for (x in 0 until 128) if (vel[r * 128 + x] > best) { best = vel[r * 128 + x]; v = x }
                        val ofOn = ((continuousBernoulliMean(of[r * 4]) - 0.5) / 0.99).coerceIn(-0.5, 0.5)
                        val ofOff = ((continuousBernoulliMean(of[r * 4 + 1]) - 0.5) / 0.99).coerceIn(-0.5, 0.5)
                        val hasOnset = pair[0] > 0 || of[r * 4 + 2] > 0
                        val hasOffset = pair[1] < lastIdx || of[r * 4 + 3] > 0
                        var st = (pair[0] + ofOn) * frameDur
                        var en = (pair[1] + ofOff) * frameDur
                        st = maxOf(st, lastEnd); en = maxOf(en, st + 1e-8); lastEnd = en
                        events += Event(st, en, fe.pitches[sym], v, hasOnset, hasOffset)
                        if (hasOffset) curLast = pair[1]
                    }
                    lastP[sym] = curLast
                }
                s0 += cnt
            }
            for (sym in 0 until nSym) startPos[sym] = maxOf(lastP[sym] - step / hop, 0)
            events.sortWith(compareBy<Event>({ it.start }, { it.end }, { it.pitch }))
            for (e in events) {
                e.start = maxOf(e.start + begin, 0.0); e.end = maxOf(e.end + begin, e.start)
                val list = byPitch.getOrPut(e.pitch) { ArrayList() }
                val last = list.lastOrNull()
                if (last != null && e.start < last.end) {
                    // The same note seen again from the next segment: a fresh onset replaces it, otherwise it continues.
                    if (e.hasOnset) list[list.size - 1] = e
                    else { last.hasOffset = e.hasOffset; last.end = maxOf(e.end, last.end) }
                    continue
                }
                if (e.hasOnset) list += e
            }
            onProgress(++segIndex, total)
            i += step
        }
        for (list in byPitch.values) list.lastOrNull()?.hasOffset = true
        val all = byPitch.values.flatten().filter { it.hasOffset }.sortedWith(compareBy<Event>({ it.start }, { it.end }, { it.pitch }))
        // A note ends where the next one on the same key begins.
        val lastOf = HashMap<Int, Event>()
        for (e in all) {
            lastOf[e.pitch]?.let { if (it.end > e.start) it.end = e.start }
            lastOf[e.pitch] = e
        }
        return all.filter { it.start < it.end && it.pitch in Note.MIN_PIANO..Note.MAX_PIANO }.map {
            Note(ids.next(), it.pitch, it.start, it.end - it.start, it.velocity.coerceIn(1, 127), 0.9f, if (it.pitch >= Note.MIDDLE_C) Hand.RIGHT else Hand.LEFT)
        }
    }

}
