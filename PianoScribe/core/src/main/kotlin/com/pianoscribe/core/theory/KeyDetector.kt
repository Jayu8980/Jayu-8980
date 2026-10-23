package com.pianoscribe.core.theory

import com.pianoscribe.core.model.Note
import kotlin.math.sqrt

/** Krumhansl-Schmuckler key finding with Krumhansl-Kessler profiles, weighted by note duration. */
object KeyDetector {
    data class Key(val tonicPc: Int, val minor: Boolean, val score: Double) {
        val preferFlats: Boolean get() = if (minor) tonicPc in FLAT_MINOR else tonicPc in FLAT_MAJOR
        val name: String get() = Pitch.pitchClassName(tonicPc, preferFlats) + if (minor) " minor" else " major"
        /** Pitch classes of the scale. Minor includes the raised 6th/7th of melodic/harmonic minor. */
        val scale: Set<Int> get() {
            val steps = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10, 11, 9) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
            return steps.map { (tonicPc + it) % 12 }.toSet()
        }
        /** Number of sharps (positive) or flats (negative) in the key signature. */
        val fifths: Int get() {
            val majorTonic = if (minor) (tonicPc + 3) % 12 else tonicPc
            val f = FIFTHS[majorTonic]
            return f
        }
        fun contains(midi: Int): Boolean = Pitch.pitchClass(midi) in scale
    }

    data class Section(val startSec: Double, val endSec: Double, val key: Key)

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val FLAT_MAJOR = setOf(5, 10, 3, 8, 1, 6)
    private val FLAT_MINOR = setOf(2, 7, 0, 5, 10, 3)
    /** Key signature for major keys by tonic pitch class (Db, Eb, Gb, Ab, Bb as flats). */
    private val FIFTHS = intArrayOf(0, -5, 2, -3, 4, -1, -6, 1, -4, 3, -2, 5)

    fun histogram(notes: Collection<Note>): DoubleArray {
        val h = DoubleArray(12)
        for (n in notes) h[Pitch.pitchClass(n.pitch)] += n.duration.coerceAtLeast(0.05) * (0.5 + n.velocity / 254.0)
        return h
    }

    fun detect(notes: Collection<Note>): Key? {
        if (notes.isEmpty()) return null
        return detect(histogram(notes))
    }

    fun detect(h: DoubleArray): Key? {
        if (h.sum() <= 0) return null
        var best: Key? = null
        for (tonic in 0 until 12) {
            for (minor in listOf(false, true)) {
                val prof = if (minor) MINOR else MAJOR
                val rotated = DoubleArray(12) { prof[Math.floorMod(it - tonic, 12)] }
                val r = correlation(h, rotated)
                if (best == null || r > best.score) best = Key(tonic, minor, r)
            }
        }
        return best
    }

    /** Key per section of [windowSec] seconds (sections with too few notes reuse the global key). */
    fun sections(notes: List<Note>, windowSec: Double = 16.0): List<Section> {
        if (notes.isEmpty()) return emptyList()
        val global = detect(notes) ?: return emptyList()
        val end = notes.maxOf { it.end }
        val out = ArrayList<Section>()
        var t = 0.0
        while (t < end) {
            val sec = notes.filter { it.start < t + windowSec && it.end > t }
            val key = if (sec.size >= 6) detect(sec) ?: global else global
            if (out.isNotEmpty() && out.last().key.tonicPc == key.tonicPc && out.last().key.minor == key.minor) {
                out[out.size - 1] = out.last().copy(endSec = minOf(end, t + windowSec))
            } else {
                out += Section(t, minOf(end, t + windowSec), key)
            }
            t += windowSec
        }
        return out
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }
}
