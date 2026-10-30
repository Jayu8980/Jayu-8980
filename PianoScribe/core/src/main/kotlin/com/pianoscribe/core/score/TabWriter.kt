package com.pianoscribe.core.score

import com.pianoscribe.core.midi.ScoreBuilder
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid

/**
 * Guitar tablature from [Tablature]'s fingering, quantised like the score (ScoreBuilder): as plain
 * text (the usual ASCII tab, which Guitar Pro and most tab sites read) and as MusicXML with a TAB
 * staff and string/fret for every note (Guitar Pro, MuseScore).
 */
object TabWriter {
    private val STRING_NAMES = listOf("e", "B", "G", "D", "A", "E")

    /** The tab's notes as one voice for ScoreBuilder, and string/fret by note id. */
    private fun measures(tab: List<TabNote>, grid: TimeGrid): Pair<List<ScoreBuilder.Measure>, Map<Long, TabNote>> {
        val notes = tab.map { Note(it.noteId, it.pitch, it.start, it.duration, 80, 1f, Hand.RIGHT) }
        return ScoreBuilder.build(notes, grid) to tab.associateBy { it.noteId }
    }

    /** A fret number to draw: [at] is the position in the bar (0..1), [string] 1 = high E. */
    data class TabItem(val at: Float, val string: Int, val fret: Int, val noteId: Long, val time: Double)
    data class TabBar(val index: Int, val start: Double, val end: Double, val items: List<TabItem>)

    /** The tab as bars of fret numbers, quantised like the score, for drawing. */
    fun bars(tab: List<TabNote>, grid: TimeGrid): List<TabBar> {
        val (ms, byId) = measures(tab, grid)
        val divPerBeat = ScoreBuilder.DIVISIONS * 4 / grid.beatUnit
        val measureDiv = grid.beatsPerBar * divPerBeat
        fun time(div: Int) = grid.beatsToTime(div.toDouble() / divPerBeat)
        return ms.map { m ->
            val start = m.index * measureDiv
            TabBar(m.index, time(start), time(start + measureDiv), m.treble.filter { !it.isRest && !it.tieStop }.flatMap { e ->
                e.noteIds.mapNotNull { id -> byId[id]?.let { t -> TabItem((e.startDiv - start).toFloat() / measureDiv, t.string, t.fret, id, time(e.startDiv)) } }
            })
        }
    }

    fun ascii(tab: List<TabNote>, grid: TimeGrid, title: String, barsPerLine: Int = 4): String {
        val (ms, byId) = measures(tab, grid)
        val measureDiv = grid.beatsPerBar * ScoreBuilder.DIVISIONS * 4 / grid.beatUnit
        val sb = StringBuilder()
        sb.append(title).append('\n')
        sb.append("Standard tuning (E A D G B E) · ").append(Math.round(grid.bpm)).append(" BPM · ")
            .append(grid.beatsPerBar).append('/').append(grid.beatUnit).append("\n\n")
        for (line in ms.chunked(barsPerLine)) {
            val rows = Array(6) { StringBuilder(STRING_NAMES[it]).append('|') }
            for (m in line) {
                // Three characters per 16th: room for two-digit frets.
                val cells = Array(6) { CharArray(measureDiv * 3) { '-' } }
                for (e in m.treble) {
                    if (e.isRest || e.tieStop) continue
                    val slot = (e.startDiv - m.index * measureDiv) * 3
                    for (id in e.noteIds) {
                        val t = byId[id] ?: continue
                        val s = t.fret.toString()
                        for ((k, ch) in s.withIndex()) cells[t.string - 1][slot + k] = ch
                    }
                }
                for (r in 0 until 6) rows[r].append(cells[r]).append('|')
            }
            for (r in rows) sb.append(r).append('\n')
            sb.append('\n')
        }
        sb.append("Made with PianoScribe\n")
        return sb.toString()
    }

    fun musicXml(tab: List<TabNote>, grid: TimeGrid, title: String): String {
        val (ms, byId) = measures(tab, grid)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""").append('\n')
        sb.append("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 3.1 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""").append('\n')
        sb.append("<score-partwise version=\"3.1\">\n")
        sb.append("  <work><work-title>").append(esc(title)).append("</work-title></work>\n")
        sb.append("  <identification><encoding><software>PianoScribe</software></encoding></identification>\n")
        sb.append("  <part-list>\n    <score-part id=\"P1\"><part-name>Guitar</part-name>\n")
        sb.append("      <midi-instrument id=\"P1-I1\"><midi-channel>1</midi-channel><midi-program>26</midi-program></midi-instrument>\n")
        sb.append("    </score-part>\n  </part-list>\n  <part id=\"P1\">\n")
        for (m in ms) {
            sb.append("    <measure number=\"").append(m.index + 1).append("\">\n")
            if (m.index == 0) {
                sb.append("      <attributes>\n        <divisions>").append(ScoreBuilder.DIVISIONS).append("</divisions>\n")
                sb.append("        <key><fifths>0</fifths></key>\n")
                sb.append("        <time><beats>").append(grid.beatsPerBar).append("</beats><beat-type>").append(grid.beatUnit).append("</beat-type></time>\n")
                sb.append("        <clef><sign>TAB</sign><line>5</line></clef>\n")
                sb.append("        <staff-details><staff-lines>6</staff-lines>\n")
                // Tuning lines from the bottom: E2 A2 D3 G3 B3 E4.
                for ((line, midi) in Tablature.TUNING.reversed().withIndex()) {
                    val sp = Spelling.spell(midi, null)
                    sb.append("          <staff-tuning line=\"").append(line + 1).append("\"><tuning-step>").append(sp.letter)
                        .append("</tuning-step><tuning-octave>").append(sp.octave).append("</tuning-octave></staff-tuning>\n")
                }
                sb.append("        </staff-details>\n")
                // Guitar music is written an octave above how it sounds.
                sb.append("        <transpose><diatonic>0</diatonic><chromatic>0</chromatic><octave-change>-1</octave-change></transpose>\n")
                sb.append("      </attributes>\n")
                sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>")
                    .append(Math.round(grid.bpm)).append("</per-minute></metronome></direction-type><sound tempo=\"").append(Math.round(grid.bpm)).append("\"/></direction>\n")
            }
            for (e in m.treble) {
                if (e.isRest) {
                    sb.append("      <note><rest/><duration>").append(e.durDiv).append("</duration><voice>1</voice><type>").append(e.type).append("</type>")
                    if (e.dotted) sb.append("<dot/>")
                    sb.append("</note>\n")
                    continue
                }
                for ((i, id) in e.noteIds.withIndex()) {
                    val t = byId[id] ?: continue
                    val sp = Spelling.spell(t.pitch + 12, null)
                    sb.append("      <note>")
                    if (i > 0) sb.append("<chord/>")
                    sb.append("<pitch><step>").append(sp.letter).append("</step>")
                    if (sp.alter != 0) sb.append("<alter>").append(sp.alter).append("</alter>")
                    sb.append("<octave>").append(sp.octave).append("</octave></pitch><duration>").append(e.durDiv).append("</duration>")
                    if (e.tieStop) sb.append("<tie type=\"stop\"/>")
                    if (e.tieStart) sb.append("<tie type=\"start\"/>")
                    sb.append("<voice>1</voice><type>").append(e.type).append("</type>")
                    if (e.dotted) sb.append("<dot/>")
                    sb.append("<notations><technical><string>").append(t.string).append("</string><fret>").append(t.fret).append("</fret></technical>")
                    if (e.tieStop) sb.append("<tied type=\"stop\"/>")
                    if (e.tieStart) sb.append("<tied type=\"start\"/>")
                    sb.append("</notations></note>\n")
                }
            }
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n</score-partwise>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
