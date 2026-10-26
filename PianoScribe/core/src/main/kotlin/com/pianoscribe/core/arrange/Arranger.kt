package com.pianoscribe.core.arrange

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.Pitch
import kotlin.math.abs
import kotlin.math.floor

/** Accompaniment styles: which instruments play, and the bass, chord and drum patterns. */
enum class Style(val label: String, val chordInstrument: Instrument, val sevenths: Boolean, val swing: Boolean, val drums: Boolean) {
    POP("Pop", Instrument.PIANO, sevenths = false, swing = false, drums = true),
    BALLAD("Ballad", Instrument.PIANO, sevenths = true, swing = false, drums = true),
    LOFI("Lo-fi", Instrument.ELECTRIC_PIANO, sevenths = true, swing = true, drums = true),
    ROCK("Rock", Instrument.ORGAN, sevenths = false, swing = false, drums = true),
}

/** General MIDI drum notes (channel 10). */
enum class Drum(val gmNote: Int) { KICK(36), RIM(37), SNARE(38), CLOSED_HAT(42), OPEN_HAT(46), CRASH(49) }

data class DrumHit(val drum: Drum, val time: Double, val velocity: Int)

/** A chord: root pitch class, major or minor (or diminished), optionally with its seventh. */
data class Chord(val rootPc: Int, val minor: Boolean, val diminished: Boolean = false, val seventh: Int? = null) {
    /** Pitch classes, root first. */
    val pitchClasses: List<Int> get() {
        val third = if (minor || diminished) 3 else 4
        val fifth = if (diminished) 6 else 7
        return listOfNotNull(0, third, fifth, seventh).map { (rootPc + it) % 12 }
    }

    fun name(preferFlats: Boolean = false): String {
        val root = Pitch.pitchClassName(rootPc, preferFlats)
        return root + when {
            diminished -> "dim"
            minor && seventh != null -> "m7"
            minor -> "m"
            seventh == 11 -> "maj7"
            seventh == 10 -> "7"
            else -> ""
        }
    }
}

/** One bar of the song and its chord. */
data class Bar(val start: Double, val end: Double, val chord: Chord)

/** The finished arrangement: the melody plus chords, bass and drums, all in song time. */
data class Song(
    val style: Style,
    val grid: TimeGrid,
    val key: KeyDetector.Key,
    val bars: List<Bar>,
    val melody: List<PlayedNote>,
    val melodyInstrument: Instrument,
    val chords: List<PlayedNote>,
    val bass: List<PlayedNote>,
    val drums: List<DrumHit>,
) {
    val end: Double get() = bars.lastOrNull()?.end ?: 0.0
    fun progression(preferFlats: Boolean = key.preferFlats): String =
        bars.map { it.chord.name(preferFlats) }.fold(ArrayList<String>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }.joinToString(" – ")
}

/**
 * Makes a song out of a melody: chords that fit it, a bass line and drums in a [Style].
 *
 * **Harmony** (the approach of Simon, Morris & Basu, "MySong", CHI 2008): one chord per bar from
 * the chords of the key. Each candidate is scored by how well the bar's melody fits it (chord
 * notes count in proportion to how long and on how strong a beat they sound; other notes of the
 * key count a little against, notes outside the key more), and a Viterbi pass over the bars adds
 * the usual progressions (I to IV, V or vi; ii to V; V to I ...), starting and ending on the home
 * chord. **Parts**: chords voiced close together around middle C with smooth voice leading, the
 * bass on the chord roots, and drums, each in the style's pattern (a backbeat on 2 and 4 in 4/4).
 */
object Arranger {
    fun arrange(melodyNotes: List<Note>, grid: TimeGrid, key: KeyDetector.Key?, style: Style,
                melodyInstrument: Instrument = Instrument.PIANO): Song {
        val melody = melodyNotes.sortedBy { it.start }
        val k = key ?: KeyDetector.detect(melody) ?: KeyDetector.Key(0, minor = false, score = 0.0)
        val barLen = grid.secondsPerBeat * grid.beatsPerBar
        if (melody.isEmpty()) return Song(style, grid, k, emptyList(), emptyList(), melodyInstrument, emptyList(), emptyList(), emptyList())
        val first = floor((melody.first().start - grid.offset) / barLen + 1e-6).toInt()
        val last = floor((melody.maxOf { it.end } - grid.offset) / barLen - 1e-6).toInt()
        val starts = (first..maxOf(first, last)).map { grid.offset + it * barLen }
        val chords = harmonize(melody, starts, barLen, grid, k).map { if (style.sevenths) withSeventh(it, k) else it }
        val bars = starts.indices.map { i -> Bar(starts[i], starts[i] + barLen, chords[i]) }
        return Song(
            style, grid, k, bars,
            melody = melody.map { PlayedNote(it.pitch, it.start, it.duration, (it.velocity + 15).coerceAtMost(120), Hand.RIGHT) },
            melodyInstrument = melodyInstrument,
            chords = chordPart(bars, grid, style),
            bass = bassPart(bars, grid, style),
            drums = if (style.drums) drumPart(bars, grid, style) else emptyList(),
        )
    }

    // ------------------------------------------------------------------ harmony

    /** The chords of the key, by scale degree: (semitones above the tonic, minor?, diminished?). */
    private fun diatonic(key: KeyDetector.Key): List<Chord> {
        val degrees = if (!key.minor) listOf(0 to false, 2 to true, 4 to true, 5 to false, 7 to false, 9 to true)
        // Minor keys: the major V from the harmonic minor, as in most tonal music.
        else listOf(0 to true, 3 to false, 5 to true, 7 to false, 8 to false, 10 to false)
        return degrees.map { (d, m) -> Chord((key.tonicPc + d) % 12, minor = m) }
    }

    /** Good moves between chords, by index into [diatonic]: I ii iii IV V vi (major) or i III iv V VI VII (minor). */
    private val goodMajor = mapOf(0 to setOf(1, 3, 4, 5), 1 to setOf(4, 3), 2 to setOf(5, 3), 3 to setOf(4, 0, 1), 4 to setOf(0, 5), 5 to setOf(3, 1, 4))
    private val goodMinor = mapOf(0 to setOf(2, 3, 4, 5), 1 to setOf(4, 2, 5), 2 to setOf(3, 0), 3 to setOf(0, 4), 4 to setOf(2, 5, 1), 5 to setOf(1, 0))

    internal fun harmonize(melody: List<Note>, barStarts: List<Double>, barLen: Double, grid: TimeGrid, key: KeyDetector.Key): List<Chord> {
        val cands = diatonic(key)
        val good = if (key.minor) goodMinor else goodMajor
        val fit = barStarts.map { b -> cands.map { c -> fit(melody, b, b + barLen, grid, c, key) } }
        val n = barStarts.size
        val score = Array(n) { DoubleArray(cands.size) }
        val back = Array(n) { IntArray(cands.size) }
        for (c in cands.indices) score[0][c] = fit[0][c] + if (c == 0) START_HOME else 0.0
        for (i in 1 until n) for (c in cands.indices) {
            var best = Double.NEGATIVE_INFINITY
            for (p in cands.indices) {
                val move = when { p == c -> STAY; c in good.getValue(p) -> GOOD_MOVE; else -> OTHER_MOVE }
                val v = score[i - 1][p] + move
                if (v > best) { best = v; back[i][c] = p }
            }
            score[i][c] = best + fit[i][c] + if (i == n - 1 && c == 0) END_HOME else 0.0
        }
        var c = score[n - 1].indices.maxBy { score[n - 1][it] }
        val out = IntArray(n)
        for (i in n - 1 downTo 0) { out[i] = c; c = back[i][c] }
        return out.map { cands[it] }
    }

    private const val START_HOME = 0.6
    private const val END_HOME = 1.0
    private const val STAY = 0.0
    private const val GOOD_MOVE = 0.35
    private const val OTHER_MOVE = -0.25

    /** How well the melody between [from] and [to] fits [chord]. */
    internal fun fit(melody: List<Note>, from: Double, to: Double, grid: TimeGrid, chord: Chord, key: KeyDetector.Key): Double {
        val pcs = chord.pitchClasses
        var s = 0.0
        for (n in melody) {
            val a = maxOf(n.start, from); val b = minOf(n.end, to)
            if (b <= a) continue
            val beats = (b - a) / grid.secondsPerBeat
            val onset = if (n.start >= from) metric(grid.timeToBeats(n.start), grid.beatsPerBar) else 0.5
            val w = beats * (1 + onset)
            val pc = Pitch.pitchClass(n.pitch)
            s += when {
                pc == chord.rootPc -> 1.1 * w
                pc in pcs -> w
                key.contains(n.pitch) -> -0.3 * w
                else -> -0.8 * w
            }
        }
        return s
    }

    /** 1 on the first beat of the bar, 0.6 on other beats, 0.2 between beats. */
    private fun metric(beats: Double, beatsPerBar: Int): Double {
        val inBar = ((beats % beatsPerBar) + beatsPerBar) % beatsPerBar
        val toBeat = abs(inBar - Math.round(inBar))
        return when {
            toBeat > 0.1 -> 0.2
            Math.round(inBar) % beatsPerBar == 0L -> 1.0
            else -> 0.6
        }
    }

    /** Adds the chord's seventh from the key: major 7 on I and IV, 7 on V, minor 7 on minor chords. */
    private fun withSeventh(c: Chord, key: KeyDetector.Key): Chord {
        if (c.diminished) return c
        val dominant = (key.tonicPc + 7) % 12 == c.rootPc && !c.minor
        return c.copy(seventh = when { c.minor -> 10; dominant -> 10; else -> 11 })
    }

    // ------------------------------------------------------------------ parts

    /** Chord tones placed close together between [LOW] and [LOW] + 15, moving as little as possible from [prev]. */
    internal fun voice(chord: Chord, prev: List<Int>?): List<Int> {
        val pcs = chord.pitchClasses
        val options = (0 until pcs.size).map { inv ->
            val order = pcs.drop(inv) + pcs.take(inv)
            var p = LOW + ((order[0] - LOW) % 12 + 12) % 12
            order.map { pc -> while (Pitch.pitchClass(p) != pc) p++; p.also { p++ } }
        }
        if (prev == null) return options.minBy { abs(it.average() - (LOW + 7)) }
        return options.minBy { v -> v.sumOf { p -> prev.minOf { abs(it - p) } } }
    }

    private const val LOW = 52 // E3

    private fun swingTime(t: Double, bar: Bar, grid: TimeGrid, style: Style): Double {
        if (!style.swing) return t
        val beat = (t - bar.start) / grid.secondsPerBeat
        // Off-beat eighths come a little late (about 2:1).
        return if (abs(beat - floor(beat) - 0.5) < 0.01) t + grid.secondsPerBeat / 6 else t
    }

    internal fun chordPart(bars: List<Bar>, grid: TimeGrid, style: Style): List<PlayedNote> {
        val out = ArrayList<PlayedNote>()
        val beat = grid.secondsPerBeat
        val n = grid.beatsPerBar
        var prev: List<Int>? = null
        for ((i, bar) in bars.withIndex()) {
            val v = voice(bar.chord, prev).also { prev = it }
            val lastBar = i == bars.size - 1
            fun hit(atBeat: Double, lenBeats: Double, vel: Int, pitches: List<Int> = v) {
                val t = swingTime(bar.start + atBeat * beat, bar, grid, style)
                for (p in pitches) out += PlayedNote(p, t, lenBeats * beat * 0.95, vel, Hand.LEFT)
            }
            if (lastBar) { hit(0.0, n.toDouble(), 70); continue }
            when (style) {
                // Steady chords on every beat.
                Style.POP -> for (b in 0 until n) hit(b.toDouble(), 0.9, if (b == 0) 68 else 58)
                // Broken chords up and back down in eighths.
                Style.BALLAD -> {
                    val up = v + (v.first() + 12)
                    val line = up + up.reversed().drop(1).dropLast(1)
                    for (e in 0 until n * 2) hit(e * 0.5, 1.0, if (e == 0) 62 else 50, listOf(line[e % line.size]))
                }
                // Held, laid-back chords: on the first beat and a push before the middle of the bar.
                Style.LOFI -> { hit(0.0, n / 2.0 - 0.5, 60); hit(n / 2.0 - 0.5, n / 2.0 + 0.5, 54) }
                // Driving eighths on the root, fifth and octave.
                Style.ROCK -> {
                    val r = v.minOf { it }.let { lo -> lo - (Pitch.pitchClass(lo) - bar.chord.rootPc + 12) % 12 }
                    for (e in 0 until n * 2) hit(e * 0.5, 0.45, if (e % 2 == 0) 66 else 56, listOf(r, r + 7, r + 12))
                }
            }
        }
        return out
    }

    internal fun bassPart(bars: List<Bar>, grid: TimeGrid, style: Style): List<PlayedNote> {
        val out = ArrayList<PlayedNote>()
        val beat = grid.secondsPerBeat
        val n = grid.beatsPerBar
        for ((i, bar) in bars.withIndex()) {
            val root = 36 + (bar.chord.rootPc - 36 % 12 + 12) % 12 // C2..B2
            val fifth = root + 7
            fun note(atBeat: Double, lenBeats: Double, pitch: Int, vel: Int) {
                val t = swingTime(bar.start + atBeat * beat, bar, grid, style)
                out += PlayedNote(pitch, t, lenBeats * beat * 0.9, vel, Hand.LEFT)
            }
            if (i == bars.size - 1) { note(0.0, n.toDouble(), root, 80); continue }
            when (style) {
                Style.POP -> for (b in 0 until n) note(b.toDouble(), 1.0, if (b == n / 2 && n % 2 == 0) fifth else root, if (b == 0) 85 else 72)
                Style.BALLAD -> if (n % 2 == 0) { note(0.0, n / 2.0, root, 78); note(n / 2.0, n / 2.0, fifth, 70) } else note(0.0, n.toDouble(), root, 78)
                Style.LOFI -> { note(0.0, 1.5, root, 80); note(n - 1.5, 1.0, root + 12, 64); note(n - 0.5, 0.5, fifth, 66) }
                Style.ROCK -> for (e in 0 until n * 2) note(e * 0.5, 0.5, root, if (e % 2 == 0) 88 else 76)
            }
        }
        return out
    }

    internal fun drumPart(bars: List<Bar>, grid: TimeGrid, style: Style): List<DrumHit> {
        val out = ArrayList<DrumHit>()
        val beat = grid.secondsPerBeat
        val n = grid.beatsPerBar
        for ((i, bar) in bars.withIndex()) {
            fun hit(d: Drum, atBeat: Double, vel: Int) { out += DrumHit(d, swingTime(bar.start + atBeat * beat, bar, grid, style), vel) }
            if (i == bars.size - 1) { hit(Drum.KICK, 0.0, 100); hit(Drum.CRASH, 0.0, 90); continue }
            if (i % 4 == 0 && style != Style.BALLAD && style != Style.LOFI) hit(Drum.CRASH, 0.0, if (i == 0) 90 else 70)
            for (b in 0 until n) {
                val backbeat = if (n % 2 == 0) b % 2 == 1 else b > 0
                val strong = b == 0 || (n % 2 == 0 && b == n / 2)
                when (style) {
                    Style.POP -> {
                        if (strong) hit(Drum.KICK, b.toDouble(), 100)
                        if (backbeat) hit(Drum.SNARE, b.toDouble(), if (n % 2 == 0) 95 else 60)
                        hit(Drum.CLOSED_HAT, b.toDouble(), 70); hit(Drum.CLOSED_HAT, b + 0.5, 50)
                    }
                    Style.BALLAD -> {
                        if (b == 0) hit(Drum.KICK, 0.0, 80)
                        if (backbeat) hit(Drum.RIM, b.toDouble(), 55)
                        hit(Drum.CLOSED_HAT, b.toDouble(), 40)
                    }
                    Style.LOFI -> {
                        if (b == 0) hit(Drum.KICK, 0.0, 95)
                        if (n % 2 == 0 && b == n / 2 - 1) hit(Drum.KICK, b + 0.5, 80)
                        if (backbeat) hit(Drum.SNARE, b.toDouble(), 75)
                        hit(Drum.CLOSED_HAT, b.toDouble(), 50); hit(Drum.CLOSED_HAT, b + 0.5, 38)
                    }
                    Style.ROCK -> {
                        if (strong) hit(Drum.KICK, b.toDouble(), 110)
                        if (strong && b == n / 2) hit(Drum.KICK, b + 0.5, 95)
                        if (backbeat) hit(Drum.SNARE, b.toDouble(), 110)
                        hit(if (b == n - 1) Drum.OPEN_HAT else Drum.CLOSED_HAT, b.toDouble(), 80); hit(Drum.CLOSED_HAT, b + 0.5, 65)
                    }
                }
            }
        }
        return out.sortedBy { it.time }
    }
}
