package com.autotube.ai.pipeline.editor

/**
 * Deterministic pseudo-randomness keyed by integers. The same keys always give the same value,
 * independent of call order, so a plan (and every re-normalization of it) is reproducible while
 * still looking hand-made: shot lengths, motion directions and amounts vary per shot.
 */
object DetRandom {

    /** Uniform value in [0, 1). */
    fun unit(vararg keys: Int): Double {
        var h = SEED
        for (k in keys) {
            h = mix(h xor (k * GOLDEN_A) + GOLDEN_B)
        }
        val bits = mix(h).toLong() and 0xFFFFFFFFL
        return bits.toDouble() / TWO_POW_32
    }

    /** Uniform value in [lo, hi). */
    fun between(lo: Double, hi: Double, vararg keys: Int): Double = lo + (hi - lo) * unit(*keys)

    /** Uniform whole number in [lo, hi]. */
    fun betweenLong(lo: Long, hi: Long, vararg keys: Int): Long {
        if (hi <= lo) return lo
        val span = hi - lo + 1
        return lo + (unit(*keys) * span).toLong().coerceIn(0L, span - 1)
    }

    /** True with probability [p]. */
    fun chance(p: Double, vararg keys: Int): Boolean = unit(*keys) < p

    /** Mixes a string into an int key (stable across runs, unlike identity hashes). */
    fun key(text: String): Int = mix(text.hashCode())

    /** murmur3 fmix32 finalizer. */
    private fun mix(x0: Int): Int {
        var x = x0
        x = x xor (x ushr 16)
        x *= -0x7a143595 // 0x85ebca6b
        x = x xor (x ushr 13)
        x *= -0x3d4d51cb // 0xc2b2ae35
        x = x xor (x ushr 16)
        return x
    }

    private const val SEED = 0x2545F491
    private const val GOLDEN_A = 0x27d4eb2d
    private const val GOLDEN_B = 0x165667b1
    private const val TWO_POW_32 = 4294967296.0
}
