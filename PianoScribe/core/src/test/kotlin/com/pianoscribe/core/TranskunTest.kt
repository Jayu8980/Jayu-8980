package com.pianoscribe.core

import com.pianoscribe.core.transcribe.Transkun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class TranskunTest {
    /** Score of a path: each interval's score, plus every positive single-frame score taken on top. */
    private fun pathScore(s: FloatArray, diag: FloatArray, t: Int, path: List<IntArray>): Double =
        path.sumOf { (b, e) -> if (b == e) diag[b].toDouble() else s[b * t + e].toDouble() }

    /** All sets of non-overlapping intervals (b < e, chained end-to-begin allowed) plus single frames with a positive score. */
    private fun bestBrute(s: FloatArray, diag: FloatArray, t: Int): Double {
        fun best(j: Int): Double {
            if (j >= t - 1) return if (j == t - 1 && diag[t - 1] > 0) diag[t - 1].toDouble() else 0.0
            val single = if (diag[j] > 0) diag[j].toDouble() else 0.0
            var v = best(j + 1)
            for (e in j + 1 until t) v = maxOf(v, s[j * t + e] + best(e))
            return single + v
        }
        return best(0)
    }

    @Test fun viterbiFindsTheBestIntervals() {
        val rnd = Random(3)
        repeat(200) {
            val t = 2 + rnd.nextInt(9)
            val s = FloatArray(t * t) { rnd.nextFloat() * 4 - 3 }
            val diag = FloatArray(t) { rnd.nextFloat() * 2 - 1.5f }
            val path = Transkun.viterbiBackward(FloatBuffer.wrap(s), 0, FloatBuffer.wrap(diag), 0, t, 0)
            // Intervals chain without overlapping.
            val ivs = path.filter { it[0] != it[1] }
            assertTrue(ivs.zipWithNext().all { (a, b) -> a[1] <= b[0] })
            assertEquals(bestBrute(s, diag, t), pathScore(s, diag, t, path), 1e-4)
        }
    }

    @Test fun viterbiStartsAtTheForcedFrame() {
        val t = 6
        val s = FloatArray(t * t) { -1f }
        s[0 * t + 2] = 5f // an interval before the start must not be taken
        s[3 * t + 5] = 2f
        val path = Transkun.viterbiBackward(FloatBuffer.wrap(s), 0, FloatBuffer.wrap(FloatArray(t) { -1f }), 0, t, 3)
        assertEquals(listOf(listOf(3, 5)), path.map { it.toList() })
    }

    @Test fun continuousBernoulliMeanIsSmoothAroundAHalf() {
        assertEquals(0.5, Transkun.continuousBernoulliMean(0f), 1e-9)
        // Continuous across the switch to the Taylor series.
        val inside = Transkun.continuousBernoulliMean(0.0039f)
        val outside = Transkun.continuousBernoulliMean(0.0041f)
        assertEquals(inside, outside, 1e-4)
        assertTrue(Transkun.continuousBernoulliMean(8f) > 0.85)
        assertTrue(Transkun.continuousBernoulliMean(-8f) < 0.15)
    }

    /** A small front end: 3 windows of 16, hop 4, 5 frames, 3 mel filters. */
    private fun tinyFrontend(): Transkun.Frontend {
        val win = 16; val nWin = 3; val nMel = 3; val nBins = win / 2 + 1
        val rnd = Random(7)
        val b = ByteBuffer.allocate(4 * (8 + 2 + nWin * win + nBins * nMel)).order(ByteOrder.LITTLE_ENDIAN)
        for (v in intArrayOf(16, 4, win, nWin, nMel, 5, 2, 4)) b.putInt(v)
        b.putInt(60); b.putInt(61)
        repeat(nWin * win) { b.putFloat(rnd.nextFloat()) }
        for (k in 0 until nBins) for (m in 0 until nMel) b.putFloat(if (k / 3 == m) rnd.nextFloat() + 0.1f else 0f)
        return Transkun.Frontend.parse(b.array())
    }

    @Test fun featuresMatchAPlainDft() {
        val fe = tinyFrontend()
        val seg = FloatArray(17) { sin(it * 0.7f) + 0.3f * cos(it * 2.1f) }
        val got = Transkun.features(fe, seg)
        // Reference: frames, normalised over all of them, plain DFT per window, mel, log.
        val frames = Array(fe.nFrame) { t -> DoubleArray(fe.win) { i -> seg.getOrElse(t * fe.hop - fe.win / 2 + i) { 0f }.toDouble() } }
        val all = frames.flatMap { it.toList() }
        val mean = all.average()
        val std = sqrt(all.sumOf { (it - mean) * (it - mean) } / (all.size - 1))
        for (t in 0 until fe.nFrame) for (c in 0 until fe.nWin) {
            val x = DoubleArray(fe.win) { (frames[t][it] - mean) / (std + 1e-8) * fe.windows[c][it] }
            val power = DoubleArray(fe.win / 2 + 1) { k ->
                var re = 0.0; var im = 0.0
                for (n in x.indices) { re += x[n] * cos(2 * PI * k * n / fe.win); im -= x[n] * sin(2 * PI * k * n / fe.win) }
                (re * re + im * im) / fe.win
            }
            for (m in 0 until fe.nMel) {
                val mel = fe.melW[m].indices.sumOf { power[fe.melLo[m] + it] * fe.melW[m][it] }
                val want = (ln(mel + 1e-5) - ln(1e-5)) / -ln(1e-5)
                val v = got[(t * fe.nMel + m) * fe.nWin + c]
                assertTrue("t $t mel $m win $c: $v vs $want", abs(v - want) < 1e-4)
            }
        }
    }
}
