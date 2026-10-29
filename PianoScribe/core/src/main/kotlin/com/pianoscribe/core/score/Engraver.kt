package com.pianoscribe.core.score

import com.pianoscribe.core.midi.ScoreBuilder
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.KeyDetector
import kotlin.math.abs
import kotlin.math.sqrt

/** SMuFL code points (Bravura) and the advance widths the layout needs, in staff spaces. */
object Smufl {
    const val BRACE = ''
    const val G_CLEF = ''
    const val F_CLEF = ''
    const val TIME_SIG_0 = ''
    const val HEAD_WHOLE = ''
    const val HEAD_HALF = ''
    const val HEAD_BLACK = ''
    const val DOT = ''
    const val MET_QUARTER = ''
    const val FLAG_8_UP = ''
    const val FLAG_8_DOWN = ''
    const val FLAG_16_UP = ''
    const val FLAG_16_DOWN = ''
    const val FLAT = ''
    const val NATURAL = ''
    const val SHARP = ''
    const val STACCATO_ABOVE = ''
    const val STACCATO_BELOW = ''
    const val REST_WHOLE = ''
    const val REST_HALF = ''
    const val REST_QUARTER = ''
    const val REST_8 = ''
    const val REST_16 = ''
    const val DYN_P = ''
    const val DYN_M = ''
    const val DYN_F = ''

    const val HEAD_W = 1.18f
    const val WHOLE_W = 1.688f

    fun accidental(alter: Int) = when (alter) { -1 -> FLAT; 1 -> SHARP; else -> NATURAL }
    fun accidentalWidth(alter: Int) = when (alter) { -1 -> 0.904f; 1 -> 0.996f; else -> 0.672f }
    fun timeDigitWidth(d: Int) = when (d) { 1 -> 1.3f; 2 -> 1.784f; 3 -> 1.684f; 5 -> 1.7f; 6, 9 -> 1.736f; 7 -> 1.7f; 8 -> 1.744f; else -> 1.88f }
    fun dynamicWidth(c: Char) = when (c) { DYN_P -> 1.46f; DYN_M -> 1.748f; else -> 1.456f }
}

/** Something to draw, in staff spaces (y grows downwards). Glyphs are SMuFL, 1 em = 4 spaces. */
sealed class Mark {
    data class Glyph(val code: Char, val x: Float, val y: Float, val scaleY: Float = 1f, val scaleX: Float = 1f) : Mark()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float) : Mark()
    /** A beam whose edge at the stem tips runs (x1, y1)-(x2, y2); it is [thickness] thick towards [inward] (+1 down, -1 up). */
    data class Beam(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float, val inward: Int) : Mark()
    data class Tie(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val above: Boolean) : Mark()
    data class Text(val text: String, val x: Float, val y: Float, val size: Float, val bold: Boolean = false, val italic: Boolean = false, val center: Boolean = false) : Mark()
}

/** Where a chord is drawn, for tapping it. */
data class Hit(val noteIds: List<Long>, val x: Float, val y: Float, val w: Float, val h: Float, val time: Double)

class EngravedSystem(
    val top: Float,
    val bottom: Float,
    val marks: List<Mark>,
    val hits: List<Hit>,
    /** (x, song time) of every note column, left to right: for the playhead. */
    val columns: List<Pair<Float, Double>>,
    val startTime: Double,
    val endTime: Double,
)

class EngravedScore(val width: Float, val header: List<Mark>, val headerBottom: Float, val systems: List<EngravedSystem>) {
    val height: Float get() = systems.lastOrNull()?.bottom ?: headerBottom
}

/**
 * Engraves a piano score: a grand staff (treble for the right hand, bass for the left), broken
 * into systems that fill [width] staff spaces, with clefs, key and time signatures, accidentals as
 * the key and the measure require, stems, flags and beams by beat, rests, ties, ledger lines,
 * dots, staccato and dynamics, using the SMuFL font Bravura's glyphs and metrics.
 */
object Engraver {
    data class Options(
        val title: String? = null,
        /** Shown left above the first system ("♩ = 120"). */
        val showTempo: Boolean = true,
        val dynamics: Boolean = true,
        /** Chord symbols written above the treble staff where each chord begins. */
        val chords: List<com.pianoscribe.core.analysis.ChordTrack.Label> = emptyList(),
    )

    private const val STAFF_GAP = 7f // between treble bottom line and bass top line
    private const val SYSTEM_GAP = 3.5f
    private const val LEAD = 1.2f // after a barline

    fun engrave(notes: List<Note>, grid: TimeGrid, key: KeyDetector.Key?, width: Float, options: Options = Options()): EngravedScore {
        val measures = ScoreBuilder.build(notes, grid)
        val fifths = key?.fifths ?: 0
        val divPerBeat = ScoreBuilder.DIVISIONS * 4 / grid.beatUnit
        val measureDiv = grid.beatsPerBar * divPerBeat
        val group = ScoreBuilder.groupDiv(grid)
        val laid = measures.map { prepare(it, key, fifths, measureDiv, group) }

        val header = ArrayList<Mark>()
        var y = 0f
        options.title?.let { header += Mark.Text(it, width / 2, 3.2f, 3.0f, bold = true, center = true); y = 5f }
        if (options.showTempo) {
            header += Mark.Glyph(Smufl.MET_QUARTER, 0.5f, y + 2.2f)
            header += Mark.Text("= ${Math.round(grid.bpm)}", 2.0f, y + 2.2f, 1.6f)
            y += 2.5f
        }
        val headerBottom = y

        // Systems: as many measures as fit, then stretched to the full width.
        val keyW = Spelling.signatureSteps(fifths).size * 1.05f
        val timeW = timeWidth(grid) + 1f
        val systems = ArrayList<EngravedSystem>()
        var m = 0
        var top = headerBottom
        val ties = PendingTies()
        val dyn = DynamicTracker()
        while (m < laid.size) {
            val first = m == 0
            val start = 0.8f + 3.0f + keyW + (if (first) timeW else 0f) + 0.8f
            var used = start
            val chosen = ArrayList<Prepared>()
            while (m < laid.size && (chosen.isEmpty() || used + laid[m].width <= width)) { chosen += laid[m]; used += laid[m].width; m++ }
            val last = m >= laid.size
            val natural = used - start
            val target = width - start
            // A short last system keeps its natural spacing.
            val stretch = if (last && natural < target * 0.7f) 1f else target / natural
            val sys = drawSystem(chosen, top, start, stretch, first, last, grid, fifths, measureDiv, divPerBeat, ties, dyn, options)
            systems += sys
            top = sys.bottom + SYSTEM_GAP
        }
        return EngravedScore(width, header, headerBottom, systems)
    }

    private fun timeWidth(grid: TimeGrid) =
        maxOf(grid.beatsPerBar.toString().sumOf { Smufl.timeDigitWidth(it - '0').toDouble() }, grid.beatUnit.toString().sumOf { Smufl.timeDigitWidth(it - '0').toDouble() }).toFloat()

    // ------------------------------------------------------------------ per measure

    /** A note or rest with what the layout needs to draw it. */
    private class Item(
        val ev: ScoreBuilder.Event,
        val treble: Boolean,
        /** Staff positions in half spaces above the middle line, low to high. */
        val pos: List<Int>,
        /** Accidental to show per note (null = none). */
        val acc: List<Int?>,
        var stemUp: Boolean = true,
        var beamed: Boolean = false,
    ) {
        val type get() = ev.type
        val flags get() = when (ev.durDiv) { 1 -> 2; 2, 3 -> 1; else -> 0 }
    }

    private class Column(val div: Int, val items: MutableList<Item>, var x: Float = 0f, var advance: Float = 0f, var leftExtra: Float = 0f)

    private class Prepared(val index: Int, val columns: List<Column>, val width: Float, val wholeRests: List<Item>, val beams: List<List<Item>>, val measureStartDiv: Int)

    private fun prepare(meas: ScoreBuilder.Measure, key: KeyDetector.Key?, fifths: Int, measureDiv: Int, group: Int): Prepared {
        val sig = Spelling.signature(fifths)
        fun items(events: List<ScoreBuilder.Event>, treble: Boolean): List<Item> {
            // Accidentals carry through the measure, per staff, per letter and octave.
            val state = HashMap<Int, Int>()
            return events.map { e ->
                val spelled = e.pitches.map { Spelling.spell(it, key) }
                val mid = if (treble) 34 else 22 // B4, D3
                val acc = spelled.map { s ->
                    val current = state[s.diatonic] ?: sig[s.step]
                    state[s.diatonic] = s.alter
                    if (s.alter != current && !e.tieStop) s.alter else null
                }
                val order = spelled.indices.sortedBy { spelled[it].diatonic }
                Item(e, treble, order.map { spelled[it].diatonic - mid }, order.map { acc[it] })
            }
        }
        val all = items(meas.treble, true) + items(meas.bass, false)
        val wholeRests = all.filter { it.ev.isRest && it.ev.durDiv == measureDiv }
        val placed = all - wholeRests.toSet()

        // Beams: eighths and 16ths in the same beat group, with no rest between them.
        val beams = ArrayList<List<Item>>()
        for (treble in listOf(true, false)) {
            var run = ArrayList<Item>()
            fun flush() { if (run.size >= 2) beams += run; run = ArrayList() }
            for (it in placed.filter { i -> i.treble == treble }.sortedBy { i -> i.ev.startDiv }) {
                val beamable = !it.ev.isRest && it.flags > 0
                val g = (it.ev.startDiv % measureDiv) / group
                if (!beamable) { flush(); continue }
                if (run.isNotEmpty() && (run.last().ev.startDiv % measureDiv) / group != g) flush()
                run += it
            }
            flush()
        }
        for (b in beams) {
            // Stems up when the group sits mostly below the middle line (a group on it goes down).
            val up = b.sumOf { i -> i.pos.first() + i.pos.last() } < 0
            for (i in b) { i.beamed = true; i.stemUp = up }
        }
        // The note furthest from the middle line decides; on the middle line, stems go down.
        for (i in placed) if (!i.beamed && !i.ev.isRest) i.stemUp = i.pos.first() + i.pos.last() < 0

        val byDiv = placed.groupBy { it.ev.startDiv }.toSortedMap()
        val columns = byDiv.map { (d, its) -> Column(d, its.toMutableList()) }
        val mStart = meas.index * measureDiv
        for ((ci, c) in columns.withIndex()) {
            val accCount = c.items.maxOfOrNull { it.acc.count { a -> a != null } } ?: 0
            c.leftExtra = if (accCount == 0) 0f else 1.15f * minOf(accCount, 3)
            val right = c.items.maxOf { rightWidth(it) }
            val nextDiv = if (ci + 1 < columns.size) columns[ci + 1].div else mStart + measureDiv
            val d = nextDiv - c.div
            val ideal = 1.6f + 1.9f * sqrt(d / 4f)
            val nextLeft = if (ci + 1 < columns.size) columns[ci + 1].leftExtra else 0f
            c.advance = maxOf(ideal, right + nextLeft + 0.5f)
        }
        val width = LEAD + (columns.firstOrNull()?.leftExtra ?: 0f) + columns.sumOf { it.advance.toDouble() }.toFloat() +
            if (columns.isEmpty()) 6f else 0f
        return Prepared(meas.index, columns, width, wholeRests, beams, mStart)
    }

    private fun rightWidth(i: Item): Float {
        if (i.ev.isRest) return 1.3f + if (i.ev.dotted) 0.8f else 0f
        val head = if (i.type == "whole") Smufl.WHOLE_W else Smufl.HEAD_W
        val second = i.pos.zipWithNext().any { (a, b) -> b - a == 1 }
        val flag = if (!i.beamed && i.flags > 0 && i.stemUp) 1.0f else 0f
        return head + (if (second) Smufl.HEAD_W else 0f) + (if (i.ev.dotted) 0.8f else 0f) + flag
    }

    // ------------------------------------------------------------------ drawing

    private class OpenTie(val x: Float, val y: Float, val above: Boolean, val pos: Int)

    private class PendingTies {
        /** (treble?, pitch) -> a tie waiting for the note it ends on. */
        val open = HashMap<Pair<Boolean, Int>, OpenTie>()
    }

    /** Writes a dynamic when the level changes and holds for a few chords. */
    private class DynamicTracker {
        var shown: Int = -1
        var candidate: Int = -1
        var count = 0
        fun level(v: Int) = when { v < 36 -> 0; v < 52 -> 1; v < 66 -> 2; v < 82 -> 3; v < 98 -> 4; else -> 5 }
        /** Returns the level to write at this chord, or -1. */
        fun next(velocity: Int): Int {
            val l = level(velocity)
            if (shown < 0) { shown = l; return l }
            if (abs(l - shown) < 2 && !(l != shown && l == candidate && count >= 2)) {
                if (l == candidate) count++ else { candidate = l; count = 1 }
                if (l != shown && count >= 3) { shown = l; candidate = -1; count = 0; return l }
                return -1
            }
            if (l == candidate) count++ else { candidate = l; count = 1 }
            if (count >= 2) { shown = l; candidate = -1; count = 0; return l }
            return -1
        }
        fun glyphs(level: Int): List<Char> = when (level) {
            0 -> listOf(Smufl.DYN_P, Smufl.DYN_P); 1 -> listOf(Smufl.DYN_P); 2 -> listOf(Smufl.DYN_M, Smufl.DYN_P)
            3 -> listOf(Smufl.DYN_M, Smufl.DYN_F); 4 -> listOf(Smufl.DYN_F); else -> listOf(Smufl.DYN_F, Smufl.DYN_F)
        }
    }

    private fun drawSystem(
        measures: List<Prepared>, top: Float, start: Float, stretch: Float, first: Boolean, last: Boolean,
        grid: TimeGrid, fifths: Int, measureDiv: Int, divPerBeat: Int, ties: PendingTies, dyn: DynamicTracker, options: Options,
    ): EngravedSystem {
        val marks = ArrayList<Mark>()
        val hits = ArrayList<Hit>()
        val cols = ArrayList<Pair<Float, Double>>()
        fun time(div: Int) = grid.beatsToTime(div.toDouble() / divPerBeat)

        // Room above and below for ledger lines and stems.
        val allItems = measures.flatMap { m -> m.columns.flatMap { it.items } }
        val trebleHigh = allItems.filter { it.treble && !it.ev.isRest }.maxOfOrNull { it.pos.last() } ?: 0
        val bassLow = allItems.filter { !it.treble && !it.ev.isRest }.minOfOrNull { it.pos.first() } ?: 0
        // Room for chord symbols above the notes.
        val chordRoom = if (options.chords.isNotEmpty()) 3.5f else 0f
        val trebleTop = top + maxOf(2.5f, (trebleHigh - 4) * 0.5f + 2.5f) + chordRoom
        val bassTop = trebleTop + 4 + STAFF_GAP
        val bassBottom = bassTop + 4
        val bottom = bassBottom + maxOf(2.5f, (-4 - bassLow) * 0.5f + 2.5f)
        val tMid = trebleTop + 2
        val bMid = bassTop + 2
        fun yOf(treble: Boolean, p: Int) = (if (treble) tMid else bMid) - p * 0.5f
        val right = start + measures.sumOf { it.width.toDouble() }.toFloat() * stretch

        // Staves, brace, system barline.
        for (st in listOf(trebleTop, bassTop)) for (l in 0..4) marks += Mark.Line(0f, st + l, right, st + l, 0.13f)
        val braceScale = (bassBottom - trebleTop) / 4f
        marks += Mark.Glyph(Smufl.BRACE, -0.6f - 0.336f * braceScale * 0.55f, bassBottom, braceScale, braceScale * 0.55f)
        marks += Mark.Line(0f, trebleTop, 0f, bassBottom, 0.16f)
        // Clefs, key signature, time signature.
        marks += Mark.Glyph(Smufl.G_CLEF, 0.8f, trebleTop + 3)
        marks += Mark.Glyph(Smufl.F_CLEF, 0.8f, bassTop + 1)
        var x = 3.8f
        val sharpsTreble = intArrayOf(4, 1, 5, 2, -1, 3, 0)
        val flatsTreble = intArrayOf(0, 3, -1, 2, -2, 1, -3)
        val nKey = minOf(abs(fifths), 7)
        for (k in 0 until nKey) {
            val p = if (fifths > 0) sharpsTreble[k] else flatsTreble[k]
            val g = if (fifths > 0) Smufl.SHARP else Smufl.FLAT
            marks += Mark.Glyph(g, x, yOf(true, p)); marks += Mark.Glyph(g, x, yOf(false, p - 2))
            x += 1.05f
        }
        if (first) {
            x += 0.4f
            val w = timeWidth(grid)
            fun digits(n: Int, yy: Float) {
                val s = n.toString()
                var dx = x + (w - s.sumOf { Smufl.timeDigitWidth(it - '0').toDouble() }.toFloat()) / 2
                for (ch in s) { marks += Mark.Glyph((Smufl.TIME_SIG_0.code + (ch - '0')).toChar(), dx, yy); dx += Smufl.timeDigitWidth(ch - '0') }
            }
            for (st in listOf(trebleTop, bassTop)) { digits(grid.beatsPerBar, st + 1); digits(grid.beatUnit, st + 3) }
        }
        // Measure number at the start of each system after the first.
        measures.firstOrNull()?.let { if (it.index > 0) marks += Mark.Text("${it.index + 1}", 0f, trebleTop - 1.4f, 1.3f, italic = true) }
        // Ties left open by the previous system continue from here.
        for (k in ties.open.keys.toList()) {
            val t = ties.open.getValue(k)
            ties.open[k] = OpenTie(start - 1.2f, yOf(k.first, t.pos), t.above, t.pos)
        }

        var mx = start
        for ((mi, meas) in measures.withIndex()) {
            val mw = meas.width * stretch
            var cx = mx + LEAD * stretch + (meas.columns.firstOrNull()?.leftExtra ?: 0f)
            for (c in meas.columns) { c.x = cx; cx += c.advance * stretch }
            // Whole-bar rests, centred.
            for (r in meas.wholeRests) marks += Mark.Glyph(Smufl.REST_WHOLE, mx + mw / 2 - 0.56f, (if (r.treble) trebleTop else bassTop) + 1)
            val stemTip = HashMap<Item, Float>()
            // Beams first: they decide the stem lengths of their notes.
            for (b in meas.beams) drawBeam(b, meas, marks, stemTip, ::yOf)
            for (c in meas.columns) {
                cols += c.x to time(c.div)
                for (it in c.items) drawItem(it, c.x, marks, hits, stemTip[it], ::yOf, time(c.div), ties)
                // Dynamics between the staves, from the treble chord (or the bass one when the treble rests).
                if (options.dynamics) {
                    val src = c.items.filter { !it.ev.isRest && !it.ev.tieStop }.maxByOrNull { if (it.treble) 1000 + it.ev.velocity else it.ev.velocity }
                    if (src != null) {
                        val l = dyn.next(src.ev.velocity)
                        if (l >= 0) {
                            var dx = c.x
                            for (g in dyn.glyphs(l)) { marks += Mark.Glyph(g, dx, trebleTop + 4 + STAFF_GAP / 2 + 0.6f); dx += Smufl.dynamicWidth(g) * 0.8f }
                        }
                    }
                }
            }
            mx += mw
            val final = last && mi == measures.size - 1
            if (final) {
                marks += Mark.Line(mx - 0.9f, trebleTop, mx - 0.9f, bassBottom, 0.16f)
                marks += Mark.Line(mx - 0.25f, trebleTop, mx - 0.25f, bassBottom, 0.5f)
            } else marks += Mark.Line(mx, trebleTop, mx, bassBottom, 0.16f)
        }
        // Chord symbols at the first note column of each chord, above the highest note, never overlapping.
        if (options.chords.isNotEmpty() && cols.isNotEmpty()) {
            val t0 = cols.first().second
            val tEnd = time(measures.last().measureStartDiv + measureDiv)
            // Clear of an up-stem (3.5 spaces) on the highest note.
            val y = minOf(trebleTop - 1.6f, yOf(true, trebleHigh) - 4.5f).coerceAtLeast(top + 1.4f)
            var lastEnd = Float.NEGATIVE_INFINITY
            for (ch in options.chords) {
                if (ch.time < t0 - 0.02 || ch.time >= tEnd - 0.02) continue
                val x = cols.firstOrNull { it.second >= ch.time - 0.02 }?.first ?: continue
                if (x < lastEnd + 0.8f) continue
                marks += Mark.Text(ch.name, x, y, 1.7f, bold = true)
                lastEnd = x + ch.name.length * 0.95f
            }
        }
        // Ties that run on into the next system: draw to the end of this one.
        for (t in ties.open.values) marks += Mark.Tie(t.x, t.y, right - 0.3f, t.y, t.above)
        val startDiv = measures.first().measureStartDiv
        val endDiv = measures.last().measureStartDiv + measureDiv
        return EngravedSystem(top, bottom, marks, hits, cols, time(startDiv), time(endDiv))
    }

    private fun drawBeam(b: List<Item>, meas: Prepared, marks: MutableList<Mark>, tips: MutableMap<Item, Float>, yOf: (Boolean, Int) -> Float) {
        val col = meas.columns.associateBy { it.div }
        val up = b.first().stemUp
        val xs = b.map { stemX(col.getValue(it.ev.startDiv).x, up) }
        // The note nearest the beam on each stem.
        val near = b.map { yOf(it.treble, if (up) it.pos.last() else it.pos.first()) }
        val dir = if (up) -1f else 1f
        val y0 = near.first() + dir * 3.5f
        val y1 = near.last() + dir * 3.5f
        val dx = (xs.last() - xs.first()).coerceAtLeast(0.01f)
        val slope = ((y1 - y0) / dx).coerceIn(-1f / dx, 1f / dx).coerceIn(-0.25f, 0.25f)
        var base = y0
        // Every stem at least 3 spaces long (2.5 + room for a second beam on 16ths).
        val minLen = if (b.any { it.flags == 2 }) 3.25f else 3.0f
        for ((i, n) in near.withIndex()) {
            val beamY = base + slope * (xs[i] - xs.first())
            val len = (n - beamY) * -dir
            if (len < minLen) base += dir * (minLen - len)
        }
        fun yAt(x: Float) = base + slope * (x - xs.first())
        marks += Mark.Beam(xs.first(), yAt(xs.first()), xs.last(), yAt(xs.last()), 0.5f, -dir.toInt())
        // Second beams for 16ths: between neighbours, or a short stub.
        for (i in b.indices) {
            if (b[i].flags < 2) continue
            val off = -dir * 0.75f
            val prev = b.getOrNull(i - 1)?.flags == 2
            val next = b.getOrNull(i + 1)?.flags == 2
            if (next) marks += Mark.Beam(xs[i], yAt(xs[i]) + off, xs[i + 1], yAt(xs[i + 1]) + off, 0.5f, -dir.toInt())
            else if (!prev) {
                val (a, z) = if (i == b.size - 1) xs[i] - 1.1f to xs[i] else xs[i] to xs[i] + 1.1f
                marks += Mark.Beam(a, yAt(a) + off, z, yAt(z) + off, 0.5f, -dir.toInt())
            }
        }
        for ((i, it) in b.withIndex()) tips[it] = yAt(xs[i])
    }

    private fun stemX(noteX: Float, up: Boolean) = if (up) noteX + Smufl.HEAD_W - 0.06f else noteX + 0.06f

    private fun drawItem(it: Item, x: Float, marks: MutableList<Mark>, hits: MutableList<Hit>, beamTip: Float?, yOf: (Boolean, Int) -> Float, time: Double, ties: PendingTies) {
        val e = it.ev
        val mid = yOf(it.treble, 0)
        if (e.isRest) {
            val (g, y) = when (e.type) {
                "whole" -> Smufl.REST_WHOLE to mid - 1
                "half" -> Smufl.REST_HALF to mid
                "quarter" -> Smufl.REST_QUARTER to mid
                "eighth" -> Smufl.REST_8 to mid
                else -> Smufl.REST_16 to mid
            }
            marks += Mark.Glyph(g, x, y)
            if (e.dotted) marks += Mark.Glyph(Smufl.DOT, x + 1.5f, mid - 0.5f)
            return
        }
        val up = it.stemUp
        val head = when (e.type) { "whole" -> Smufl.HEAD_WHOLE; "half" -> Smufl.HEAD_HALF; else -> Smufl.HEAD_BLACK }
        val headW = if (e.type == "whole") Smufl.WHOLE_W else Smufl.HEAD_W
        // Noteheads; of two a second apart, one moves to the other side of the stem.
        val xs = FloatArray(it.pos.size) { x }
        if (up) { for (i in 1 until it.pos.size) if (it.pos[i] - it.pos[i - 1] == 1 && xs[i - 1] == x) xs[i] = x + headW - 0.12f }
        else { for (i in it.pos.size - 2 downTo 0) if (it.pos[i + 1] - it.pos[i] == 1 && xs[i + 1] == x) xs[i] = x - headW + 0.12f }
        // Accidentals, stacked leftwards when several are close.
        var accX = x - 0.25f
        var lastAccPos = Int.MAX_VALUE
        for (i in it.pos.indices.reversed()) {
            val a = it.acc[i] ?: continue
            if (lastAccPos - it.pos[i] < 6) accX -= 1.1f else accX = x - 0.25f
            marks += Mark.Glyph(Smufl.accidental(a), accX - Smufl.accidentalWidth(a), yOf(it.treble, it.pos[i]))
            lastAccPos = it.pos[i]
        }
        for ((i, p) in it.pos.withIndex()) {
            val y = yOf(it.treble, p)
            marks += Mark.Glyph(head, xs[i], y)
            // Ledger lines above and below the staff.
            if (p >= 6) for (l in 6..p step 2) marks += Mark.Line(xs[i] - 0.4f, yOf(it.treble, l), xs[i] + headW + 0.4f, yOf(it.treble, l), 0.16f)
            if (p <= -6) for (l in -6 downTo p step 2) marks += Mark.Line(xs[i] - 0.4f, yOf(it.treble, l), xs[i] + headW + 0.4f, yOf(it.treble, l), 0.16f)
            if (e.dotted) {
                val dy = if (p % 2 == 0) yOf(it.treble, p + 1) else y
                marks += Mark.Glyph(Smufl.DOT, x + headW + (if (xs.any { v -> v > x }) headW else 0f) + 0.35f, dy)
            }
        }
        val yHigh = yOf(it.treble, it.pos.last())
        val yLow = yOf(it.treble, it.pos.first())
        // Stem and flags.
        if (e.type != "whole") {
            val sx = stemX(x, up)
            val tip = beamTip ?: if (up) minOf(yHigh - 3.5f, mid) else maxOf(yLow + 3.5f, mid)
            marks += if (up) Mark.Line(sx, yLow - 0.17f, sx, tip, 0.12f) else Mark.Line(sx, yHigh + 0.17f, sx, tip, 0.12f)
            if (beamTip == null && it.flags > 0) {
                val g = if (up) (if (it.flags == 2) Smufl.FLAG_16_UP else Smufl.FLAG_8_UP) else (if (it.flags == 2) Smufl.FLAG_16_DOWN else Smufl.FLAG_8_DOWN)
                marks += Mark.Glyph(g, sx - 0.06f, tip)
            }
        }
        // Staccato on the notehead side, away from the stem.
        if (e.staccato) {
            val p = if (up) it.pos.first() - 2 else it.pos.last() + 2
            val pp = if (p % 2 == 0) p + (if (up) -1 else 1) else p
            marks += Mark.Glyph(if (up) Smufl.STACCATO_BELOW else Smufl.STACCATO_ABOVE, x + headW / 2 - 0.15f, yOf(it.treble, pp))
        }
        // Ties: close the ones this note continues, open the ones it starts.
        for ((i, p) in it.pos.withIndex()) {
            val pitch = e.pitches[i]
            val k = it.treble to pitch
            val y = yOf(it.treble, p)
            if (e.tieStop) ties.open.remove(k)?.let { t -> marks += Mark.Tie(t.x, t.y, x - 0.15f, y, t.above) }
            if (e.tieStart) ties.open[k] = OpenTie(x + headW + 0.15f, y, !up, p)
        }
        val h = maxOf(yLow - yHigh + 1.2f, 2f)
        hits += Hit(e.noteIds, x - 0.4f, (yHigh + yLow) / 2 - h / 2, headW * 2 + 0.4f, h, time)
    }
}
