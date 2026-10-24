package com.pianoscribe.core.theory

/**
 * Names chords from a set of sounding MIDI pitches: "C", "Am7", "G/B", "Fmaj7", "Bdim", "Dsus4"...
 */
object ChordNamer {
    data class Chord(
        val name: String,
        val rootPc: Int,
        val bassPc: Int,
        val quality: String,
        /** 0 = root position, 1 = first inversion, 2 = second, 3 = third. -1 when the bass is not a chord tone. */
        val inversion: Int,
        val noteNames: List<String>,
    ) {
        val inversionName: String get() = when (inversion) {
            0 -> "root position"
            1 -> "1st inversion"
            2 -> "2nd inversion"
            3 -> "3rd inversion"
            else -> "slash chord"
        }
    }

    /** suffix, intervals from root (first = root), and ordered chord tones for inversion lookup. */
    private class Template(val suffix: String, val intervals: IntArray, val quality: String)

    private val TEMPLATES = listOf(
        Template("", intArrayOf(0, 4, 7), "major"),
        Template("m", intArrayOf(0, 3, 7), "minor"),
        Template("dim", intArrayOf(0, 3, 6), "diminished"),
        Template("aug", intArrayOf(0, 4, 8), "augmented"),
        Template("sus4", intArrayOf(0, 5, 7), "suspended 4th"),
        Template("sus2", intArrayOf(0, 2, 7), "suspended 2nd"),
        Template("7", intArrayOf(0, 4, 7, 10), "dominant 7th"),
        Template("maj7", intArrayOf(0, 4, 7, 11), "major 7th"),
        Template("m7", intArrayOf(0, 3, 7, 10), "minor 7th"),
        Template("m7b5", intArrayOf(0, 3, 6, 10), "half-diminished 7th"),
        Template("dim7", intArrayOf(0, 3, 6, 9), "diminished 7th"),
        Template("mMaj7", intArrayOf(0, 3, 7, 11), "minor-major 7th"),
        Template("6", intArrayOf(0, 4, 7, 9), "major 6th"),
        Template("m6", intArrayOf(0, 3, 7, 9), "minor 6th"),
        Template("7sus4", intArrayOf(0, 5, 7, 10), "dominant 7th sus4"),
        Template("add9", intArrayOf(0, 4, 7, 2), "major add 9"),
        Template("madd9", intArrayOf(0, 3, 7, 2), "minor add 9"),
        Template("9", intArrayOf(0, 4, 7, 10, 2), "dominant 9th"),
        Template("maj9", intArrayOf(0, 4, 7, 11, 2), "major 9th"),
        Template("m9", intArrayOf(0, 3, 7, 10, 2), "minor 9th"),
        // Common voicings that omit the fifth.
        Template("7", intArrayOf(0, 4, 10), "dominant 7th (no 5th)"),
        Template("maj7", intArrayOf(0, 4, 11), "major 7th (no 5th)"),
        Template("m7", intArrayOf(0, 3, 10), "minor 7th (no 5th)"),
        Template("5", intArrayOf(0, 7), "power chord"),
    )

    /** Chord-tone degree of an interval: 1 = third (or sus tone), 2 = fifth, 3 = seventh/sixth. */
    private fun degree(interval: Int, t: Template): Int = when (interval) {
        3, 4 -> 1
        5 -> 1
        2 -> if (t.intervals.size == 3) 1 else -1
        6, 7, 8 -> 2
        9, 10, 11 -> 3
        else -> 0
    }

    /**
     * Returns null for fewer than two distinct pitch classes. For two pitch classes that are not
     * a fifth, the result is an interval dyad such as "C+E".
     */
    fun name(pitches: Collection<Int>, preferFlats: Boolean = false): Chord? {
        if (pitches.isEmpty()) return null
        val sorted = pitches.sorted()
        val bassPc = Pitch.pitchClass(sorted.first())
        val pcs = sorted.map { Pitch.pitchClass(it) }.distinct()
        if (pcs.size < 2) return null
        val pcSet = pcs.toSet()

        var best: Chord? = null
        var bestScore = Int.MIN_VALUE
        for (root in pcs) {
            val rel = pcSet.map { Math.floorMod(it - root, 12) }.toSet()
            for ((ti, t) in TEMPLATES.withIndex()) {
                val tones = t.intervals.toSet()
                if (!rel.containsAll(tones)) continue
                val extra = rel.size - tones.size
                if (extra > 1) continue
                // Prefer complete matches, the bass as root, richer templates and earlier (common) ones.
                var score = 100 - extra * 40 + tones.size * 6 - ti
                if (root == bassPc) score += 25
                if (tones.size == 2 && pcSet.size > 2) score -= 30
                if (score > bestScore) {
                    bestScore = score
                    val rootName = Pitch.pitchClassName(root, preferFlats)
                    var name = rootName + t.suffix
                    val inversion = if (root == bassPc) 0 else {
                        val bassInterval = Math.floorMod(bassPc - root, 12)
                        if (bassInterval in tones) degree(bassInterval, t) else -1
                    }
                    if (root != bassPc) name += "/" + Pitch.pitchClassName(bassPc, preferFlats)
                    best = Chord(name, root, bassPc, t.quality, inversion, sorted.map { Pitch.name(it, preferFlats) })
                }
            }
        }
        if (best != null) return best
        if (pcs.size == 2) {
            val names = sorted.map { Pitch.name(it, preferFlats) }
            val iv = Interval.name(Math.floorMod(pcs[1] - pcs[0], 12))
            return Chord(
                Pitch.pitchClassName(pcs[0], preferFlats) + "+" + Pitch.pitchClassName(pcs[1], preferFlats),
                pcs[0], bassPc, iv, 0, names,
            )
        }
        // Cluster without a recognisable shape.
        return Chord(
            pcs.joinToString("-") { Pitch.pitchClassName(it, preferFlats) },
            bassPc, bassPc, "cluster", 0, sorted.map { Pitch.name(it, preferFlats) },
        )
    }
}
