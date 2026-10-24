package com.pianoscribe.core.theory

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pitch maths and note naming (Western and Indian Sargam). */
object Pitch {
    const val A4_HZ = 440.0

    private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** Sargam names relative to Sa; komal (flat) swaras marked "(k)", tivra Ma marked "(t)". */
    private val SARGAM = arrayOf("Sa", "Re(k)", "Re", "Ga(k)", "Ga", "Ma", "Ma(t)", "Pa", "Dha(k)", "Dha", "Ni(k)", "Ni")

    fun frequency(midi: Int, cents: Double = 0.0): Double = A4_HZ * 2.0.pow((midi - 69) / 12.0 + cents / 1200.0)

    fun midiFromFrequency(hz: Double): Double = 69 + 12 * log2(hz / A4_HZ)

    fun nearestMidi(hz: Double): Int = midiFromFrequency(hz).roundToInt()

    /** Cents between [hz] and the equal-tempered pitch of [midi]. Positive = sharp. */
    fun cents(hz: Double, midi: Int): Double = 1200 * log2(hz / frequency(midi))

    fun pitchClass(midi: Int): Int = ((midi % 12) + 12) % 12

    /** Scientific pitch octave: middle C (60) is C4. */
    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1

    fun pitchClassName(pc: Int, preferFlats: Boolean = false): String =
        (if (preferFlats) FLAT_NAMES else SHARP_NAMES)[((pc % 12) + 12) % 12]

    /** e.g. 60 -> "C4", 54 -> "F#3". */
    fun name(midi: Int, preferFlats: Boolean = false): String = pitchClassName(pitchClass(midi), preferFlats) + octave(midi)

    /**
     * Sargam name. [saPitchClass] is the pitch class used as Sa (0 = C). The madhya (middle) saptak
     * starts at the Sa at or above middle C; higher octaves get a dot above (taar), lower get a dot
     * below (mandra).
     */
    fun sargam(midi: Int, saPitchClass: Int = 0): String {
        val rel = midi - (60 + saPitchClass)
        val degree = Math.floorMod(rel, 12)
        val octaveOffset = Math.floorDiv(rel, 12)
        val base = SARGAM[degree]
        return when {
            octaveOffset == 0 -> base
            octaveOffset > 0 -> markFirst(base, '̇', octaveOffset)
            else -> markFirst(base, '̣', -octaveOffset)
        }
    }

    /** Sargam name without octave marks, e.g. "Ga(k)". */
    fun sargamDegree(midi: Int, saPitchClass: Int = 0): String = SARGAM[Math.floorMod(midi - saPitchClass, 12)]

    private fun markFirst(s: String, mark: Char, count: Int): String {
        val marks = if (count <= 2) mark.toString().repeat(count) else mark.toString() + count
        return s.substring(0, 1) + marks + s.substring(1)
    }

    fun isBlackKey(midi: Int): Boolean = pitchClass(midi) in intArrayOf(1, 3, 6, 8, 10)
}

/** Interval naming for melody analysis. */
object Interval {
    private val NAMES = arrayOf(
        "unison", "minor 2nd", "major 2nd", "minor 3rd", "major 3rd", "perfect 4th", "tritone",
        "perfect 5th", "minor 6th", "major 6th", "minor 7th", "major 7th", "octave",
    )
    private val COMPOUND = mapOf(13 to "minor 9th", 14 to "major 9th", 15 to "minor 10th", 16 to "major 10th", 17 to "perfect 11th", 19 to "perfect 12th", 24 to "two octaves")

    /** Name of an interval of |semitones|. */
    fun name(semitones: Int): String {
        val s = kotlin.math.abs(semitones)
        if (s <= 12) return NAMES[s]
        COMPOUND[s]?.let { return it }
        val oct = s / 12
        val rem = s % 12
        val octs = if (oct == 1) "an octave" else "$oct octaves"
        return if (rem == 0) "$oct octaves" else "$octs + ${NAMES[rem]}"
    }

    /** "up a major 3rd", "down a perfect 5th", "same note". */
    fun describe(fromMidi: Int, toMidi: Int): String {
        val d = toMidi - fromMidi
        if (d == 0) return "same note"
        val n = name(d)
        val article = if (n.first().isDigit() || n.startsWith("two") || n.startsWith("an ")) "" else if (n.startsWith("o") || n.startsWith("u")) "an " else "a "
        return (if (d > 0) "up " else "down ") + article + n
    }

    enum class Direction { UP, DOWN, SAME, START }

    fun direction(fromMidi: Int?, toMidi: Int): Direction = when {
        fromMidi == null -> Direction.START
        toMidi > fromMidi -> Direction.UP
        toMidi < fromMidi -> Direction.DOWN
        else -> Direction.SAME
    }
}
