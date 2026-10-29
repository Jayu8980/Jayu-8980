package com.pianoscribe.core.midi

import com.pianoscribe.core.analysis.ChordTrack
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.score.Spelling
import com.pianoscribe.core.theory.KeyDetector

/**
 * MusicXML 3.1 partwise writer: one piano part with treble and bass staves, spelled like the score
 * view (key-aware letters, accidentals only where the measure needs them), with staccato and,
 * when given, chord symbols (`<harmony>`) above the treble staff.
 */
object MusicXmlWriter {
    fun write(
        notes: List<Note>,
        grid: TimeGrid,
        title: String,
        fifths: Int = 0,
        key: KeyDetector.Key? = null,
        chords: List<ChordTrack.Label> = emptyList(),
    ): String {
        val measures = ScoreBuilder.build(notes, grid)
        val k = key ?: KeyDetector.Key(Math.floorMod(7 * fifths, 12), minor = false, score = 0.0)
        val divPerBeat = ScoreBuilder.DIVISIONS * 4 / grid.beatUnit
        val measureDiv = grid.beatsPerBar * divPerBeat
        val chordAt = chords.map { Math.round(grid.timeToBeats(it.time) * divPerBeat).toInt() to it.name }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""").append('\n')
        sb.append("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 3.1 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""").append('\n')
        sb.append("<score-partwise version=\"3.1\">\n")
        sb.append("  <work><work-title>").append(esc(title)).append("</work-title></work>\n")
        sb.append("  <identification><encoding><software>PianoScribe</software></encoding></identification>\n")
        sb.append("  <part-list>\n    <score-part id=\"P1\">\n      <part-name>Piano</part-name>\n")
        sb.append("      <score-instrument id=\"P1-I1\"><instrument-name>Acoustic Grand Piano</instrument-name></score-instrument>\n")
        sb.append("      <midi-instrument id=\"P1-I1\"><midi-channel>1</midi-channel><midi-program>1</midi-program></midi-instrument>\n")
        sb.append("    </score-part>\n  </part-list>\n  <part id=\"P1\">\n")
        for (m in measures) {
            sb.append("    <measure number=\"").append(m.index + 1).append("\">\n")
            if (m.index == 0) {
                sb.append("      <attributes>\n        <divisions>").append(ScoreBuilder.DIVISIONS).append("</divisions>\n")
                sb.append("        <key><fifths>").append(fifths).append("</fifths></key>\n")
                sb.append("        <time><beats>").append(grid.beatsPerBar).append("</beats><beat-type>").append(grid.beatUnit).append("</beat-type></time>\n")
                sb.append("        <staves>2</staves>\n")
                sb.append("        <clef number=\"1\"><sign>G</sign><line>2</line></clef>\n")
                sb.append("        <clef number=\"2\"><sign>F</sign><line>4</line></clef>\n")
                sb.append("      </attributes>\n")
                sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>")
                    .append(Math.round(grid.bpm)).append("</per-minute></metronome></direction-type><sound tempo=\"")
                    .append(Math.round(grid.bpm)).append("\"/></direction>\n")
            }
            val start = m.index * measureDiv
            val inBar = chordAt.filter { it.first >= start && it.first < start + measureDiv }
            writeVoice(sb, m.treble, 1, 1, k, fifths, inBar)
            sb.append("      <backup><duration>").append(measureDiv).append("</duration></backup>\n")
            writeVoice(sb, m.bass, 2, 5, k, fifths)
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n</score-partwise>\n")
        return sb.toString()
    }

    private fun writeVoice(
        sb: StringBuilder,
        events: List<ScoreBuilder.Event>,
        staff: Int,
        voice: Int,
        key: KeyDetector.Key,
        fifths: Int,
        chords: List<Pair<Int, String>> = emptyList(),
    ) {
        val sig = Spelling.signature(fifths)
        // Accidentals carry through the measure per letter and octave, as in the score view.
        val state = HashMap<Int, Int>()
        var ci = 0
        for (e in events) {
            // Chord symbols starting during this event, before it (offset if after its start).
            while (ci < chords.size && chords[ci].first < e.startDiv + e.durDiv) {
                harmony(sb, chords[ci].second, maxOf(0, chords[ci].first - e.startDiv))
                ci++
            }
            if (e.isRest) {
                sb.append("      <note><rest/><duration>").append(e.durDiv).append("</duration><voice>").append(voice)
                    .append("</voice><type>").append(e.type).append("</type>")
                if (e.dotted) sb.append("<dot/>")
                sb.append("<staff>").append(staff).append("</staff></note>\n")
                continue
            }
            for ((i, p) in e.pitches.withIndex()) {
                val sp = Spelling.spell(p, key)
                val step = sp.letter.toString()
                val alter = sp.alter
                val octave = sp.octave
                val current = state[sp.diatonic] ?: sig[sp.step]
                state[sp.diatonic] = alter
                val showAccidental = alter != current && !e.tieStop
                sb.append("      <note>")
                if (i > 0) sb.append("<chord/>")
                sb.append("<pitch><step>").append(step).append("</step>")
                if (alter != 0) sb.append("<alter>").append(alter).append("</alter>")
                sb.append("<octave>").append(octave).append("</octave></pitch>")
                sb.append("<duration>").append(e.durDiv).append("</duration>")
                if (e.tieStop) sb.append("<tie type=\"stop\"/>")
                if (e.tieStart) sb.append("<tie type=\"start\"/>")
                sb.append("<voice>").append(voice).append("</voice><type>").append(e.type).append("</type>")
                if (e.dotted) sb.append("<dot/>")
                if (showAccidental) sb.append("<accidental>").append(when (alter) { 1 -> "sharp"; -1 -> "flat"; else -> "natural" }).append("</accidental>")
                sb.append("<staff>").append(staff).append("</staff>")
                if (e.tieStart || e.tieStop || e.staccato) {
                    sb.append("<notations>")
                    if (e.tieStop) sb.append("<tied type=\"stop\"/>")
                    if (e.tieStart) sb.append("<tied type=\"start\"/>")
                    if (e.staccato && i == 0) sb.append("<articulations><staccato/></articulations>")
                    sb.append("</notations>")
                }
                sb.append("</note>\n")
            }
        }
    }

    /** MusicXML `kind` for each chord-name suffix ChordNamer gives. */
    private val KINDS = mapOf(
        "" to "major", "m" to "minor", "dim" to "diminished", "aug" to "augmented",
        "sus4" to "suspended-fourth", "sus2" to "suspended-second", "7" to "dominant",
        "maj7" to "major-seventh", "m7" to "minor-seventh", "m7b5" to "half-diminished",
        "dim7" to "diminished-seventh", "mMaj7" to "major-minor", "6" to "major-sixth",
        "m6" to "minor-sixth", "7sus4" to "suspended-fourth", "add9" to "major", "madd9" to "minor",
        "9" to "dominant-ninth", "maj9" to "major-ninth", "m9" to "minor-ninth", "5" to "power",
    )

    /** A chord name split into root, suffix and bass: "F#m7/C#" -> (F#, m7, C#). */
    internal data class ChordParts(val rootStep: Char, val rootAlter: Int, val suffix: String, val bass: Pair<Char, Int>?)

    internal fun parseChord(name: String): ChordParts? {
        val slash = name.indexOf('/')
        val main = if (slash >= 0) name.substring(0, slash) else name
        fun note(s: String): Pair<Pair<Char, Int>, Int>? {
            val l = s.firstOrNull()?.takeIf { it in 'A'..'G' } ?: return null
            val a = when (s.getOrNull(1)) { '#' -> 1; 'b' -> -1; else -> 0 }
            return (l to a) to (if (a != 0) 2 else 1)
        }
        val (root, len) = note(main) ?: return null
        val bass = if (slash >= 0) note(name.substring(slash + 1))?.first else null
        return ChordParts(root.first, root.second, main.substring(len), bass)
    }

    private fun harmony(sb: StringBuilder, name: String, offset: Int) {
        val c = parseChord(name) ?: return
        sb.append("      <harmony placement=\"above\"><root><root-step>").append(c.rootStep).append("</root-step>")
        if (c.rootAlter != 0) sb.append("<root-alter>").append(c.rootAlter).append("</root-alter>")
        sb.append("</root><kind text=\"").append(esc(c.suffix)).append("\">").append(KINDS[c.suffix] ?: "other").append("</kind>")
        if (c.bass != null) {
            sb.append("<bass><bass-step>").append(c.bass.first).append("</bass-step>")
            if (c.bass.second != 0) sb.append("<bass-alter>").append(c.bass.second).append("</bass-alter>")
            sb.append("</bass>")
        }
        if (offset > 0) sb.append("<offset>").append(offset).append("</offset>")
        sb.append("<staff>1</staff></harmony>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
