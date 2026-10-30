package com.pianoscribe.core.score

import com.pianoscribe.core.theory.KeyDetector

/**
 * How a MIDI pitch is written in a key: letter (step 0 = C .. 6 = B), alteration and octave.
 * Notes of the key take the key signature's spelling; others are spelled with sharps in sharp
 * keys and flats in flat keys (in C: C#, Eb, F#, G#, Bb), and the leading note of a minor key is
 * the raised seventh (G# in A minor, C# in D minor).
 */
object Spelling {
    data class Spelled(val step: Int, val alter: Int, val octave: Int) {
        /** Diatonic number: 7 per octave, C4 = 28. */
        val diatonic: Int get() = octave * 7 + step
        val letter: Char get() = "CDEFGAB"[step]
    }

    private val NATURAL = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
    private val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3) // B E A D G C F
    /** Black keys in C major / A minor. */
    private val NEUTRAL = mapOf(1 to (0 to 1), 3 to (2 to -1), 6 to (3 to 1), 8 to (4 to 1), 10 to (6 to -1))

    /** Alteration the key signature gives each step (C..B). */
    fun signature(fifths: Int): IntArray {
        val sig = IntArray(7)
        if (fifths > 0) for (i in 0 until minOf(fifths, 7)) sig[SHARP_ORDER[i]] = 1
        if (fifths < 0) for (i in 0 until minOf(-fifths, 7)) sig[FLAT_ORDER[i]] = -1
        return sig
    }

    /** The steps altered by the signature, in the order they are written. */
    fun signatureSteps(fifths: Int): List<Int> =
        if (fifths >= 0) SHARP_ORDER.take(minOf(fifths, 7)) else FLAT_ORDER.take(minOf(-fifths, 7))

    fun spell(midi: Int, key: KeyDetector.Key?): Spelled {
        val fifths = key?.fifths ?: 0
        val sig = signature(fifths)
        val pc = Math.floorMod(midi, 12)
        // In the key signature?
        for (step in 0..6) if (Math.floorMod(NATURAL[step] + sig[step], 12) == pc) return of(midi, step, sig[step])
        // Raised 6th and 7th of a minor key.
        if (key != null && key.minor) {
            for (degree in intArrayOf(9, 11)) if (Math.floorMod(key.tonicPc + degree, 12) == pc) {
                val natural = Math.floorMod(key.tonicPc + degree - 1, 12)
                val step = (0..6).firstOrNull { Math.floorMod(NATURAL[it] + sig[it], 12) == natural }
                if (step != null) return of(midi, step, sig[step] + 1)
            }
        }
        // A white key outside the key signature is its natural letter (F natural in G major).
        val white = NATURAL.indexOf(pc)
        if (white >= 0) return of(midi, white, 0)
        val (step, alter) = when {
            fifths > 0 -> (0..6).first { Math.floorMod(NATURAL[it] + 1, 12) == pc } to 1
            fifths < 0 -> (0..6).first { Math.floorMod(NATURAL[it] - 1, 12) == pc } to -1
            else -> NEUTRAL.getValue(pc)
        }
        return of(midi, step, alter)
    }

    private fun of(midi: Int, step: Int, alter: Int): Spelled {
        // The octave belongs to the letter: B#3 sounds as C4, Cb5 as B4.
        val natural = midi - alter
        return Spelled(step, alter, Math.floorDiv(natural, 12) - 1)
    }
}
