package com.pianoscribe.core.midi

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid

/** MusicXML 3.1 partwise writer: one piano part with treble and bass staves. */
object MusicXmlWriter {
    private val SHARP_STEPS = arrayOf("C" to 0, "C" to 1, "D" to 0, "D" to 1, "E" to 0, "F" to 0, "F" to 1, "G" to 0, "G" to 1, "A" to 0, "A" to 1, "B" to 0)
    private val FLAT_STEPS = arrayOf("C" to 0, "D" to -1, "D" to 0, "E" to -1, "E" to 0, "F" to 0, "G" to -1, "G" to 0, "A" to -1, "A" to 0, "B" to -1, "B" to 0)

    fun write(notes: List<Note>, grid: TimeGrid, title: String, fifths: Int = 0): String {
        val measures = ScoreBuilder.build(notes, grid)
        val preferFlats = fifths < 0
        val measureDiv = grid.beatsPerBar * ScoreBuilder.DIVISIONS * 4 / grid.beatUnit
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
            writeVoice(sb, m.treble, 1, 1, preferFlats)
            sb.append("      <backup><duration>").append(measureDiv).append("</duration></backup>\n")
            writeVoice(sb, m.bass, 2, 5, preferFlats)
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n</score-partwise>\n")
        return sb.toString()
    }

    private fun writeVoice(sb: StringBuilder, events: List<ScoreBuilder.Event>, staff: Int, voice: Int, preferFlats: Boolean) {
        for (e in events) {
            if (e.isRest) {
                sb.append("      <note><rest/><duration>").append(e.durDiv).append("</duration><voice>").append(voice)
                    .append("</voice><type>").append(e.type).append("</type>")
                if (e.dotted) sb.append("<dot/>")
                sb.append("<staff>").append(staff).append("</staff></note>\n")
                continue
            }
            for ((i, p) in e.pitches.withIndex()) {
                val (step, alter) = (if (preferFlats) FLAT_STEPS else SHARP_STEPS)[Math.floorMod(p, 12)]
                val octave = Math.floorDiv(p, 12) - 1
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
                if (alter != 0) sb.append("<accidental>").append(if (alter > 0) "sharp" else "flat").append("</accidental>")
                sb.append("<staff>").append(staff).append("</staff>")
                if (e.tieStart || e.tieStop) {
                    sb.append("<notations>")
                    if (e.tieStop) sb.append("<tied type=\"stop\"/>")
                    if (e.tieStart) sb.append("<tied type=\"start\"/>")
                    sb.append("</notations>")
                }
                sb.append("</note>\n")
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
