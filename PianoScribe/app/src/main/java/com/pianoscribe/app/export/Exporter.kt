package com.pianoscribe.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.pianoscribe.core.analysis.AnalysisRow
import com.pianoscribe.core.analysis.AnalysisTable
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.midi.MusicXmlWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.Pitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    MIDI("MIDI file (.mid)", "mid", "audio/midi"),
    MUSICXML("MusicXML for MuseScore (.musicxml)", "musicxml", "application/vnd.recordare.musicxml+xml"),
    PNG("Piano roll image (.png)", "png", "image/png"),
    NOTES_PDF("Note list (.pdf)", "pdf", "application/pdf"),
    NOTES_TXT("Note list (.txt)", "txt", "text/plain"),
    ANALYSIS_CSV("Analysis table (.csv)", "csv", "text/csv"),
    ANALYSIS_PDF("Analysis table (.pdf)", "pdf", "application/pdf"),
}

/** Writes exports into cache/exports and hands them to the share sheet or a chosen location. */
class Exporter(private val context: Context) {
    data class Input(val title: String, val notes: List<Note>, val grid: TimeGrid, val key: KeyDetector.Key?, val saPitchClass: Int)

    suspend fun export(format: ExportFormat, input: Input): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val suffix = when (format) { ExportFormat.ANALYSIS_CSV, ExportFormat.ANALYSIS_PDF -> "-analysis"; ExportFormat.NOTES_PDF, ExportFormat.NOTES_TXT -> "-notes"; else -> "" }
        val file = File(dir, safeName(input.title) + suffix + "." + format.ext)
        val rows by lazy { AnalysisTable.rows(input.notes, input.grid, input.saPitchClass, input.key) }
        when (format) {
            ExportFormat.MIDI -> file.writeBytes(MidiWriter.write(input.notes, input.grid, input.title))
            ExportFormat.MUSICXML -> file.writeText(MusicXmlWriter.write(input.notes, input.grid, input.title, input.key?.fifths ?: 0))
            ExportFormat.PNG -> writePianoRollPng(input.notes, input.grid, file)
            ExportFormat.NOTES_TXT -> file.writeText(AnalysisTable.text(rows, input.title, input.grid, input.key?.name))
            ExportFormat.ANALYSIS_CSV -> file.writeText(AnalysisTable.csv(rows))
            ExportFormat.NOTES_PDF -> writePdf(file, input, rows, analysis = false)
            ExportFormat.ANALYSIS_PDF -> writePdf(file, input, rows, analysis = true)
        }
        file
    }

    fun share(file: File, format: ExportFormat) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies an export to a location the user picked with the system "Save as" dialog. */
    suspend fun saveTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Couldn't write to the chosen location")
    }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _\\-]"), "_").trim().ifEmpty { "PianoScribe" }.take(60)

    private fun writePianoRollPng(notes: List<Note>, grid: TimeGrid, file: File) {
        val end = (notes.maxOfOrNull { it.end } ?: 1.0) + 0.5
        val lo = (notes.minOfOrNull { it.pitch } ?: 48) - 2
        val hi = (notes.maxOfOrNull { it.pitch } ?: 72) + 2
        val rowH = 10
        val keyW = 48
        val pxPerSec = (12000.0 / end).coerceIn(10.0, 120.0)
        val w = (keyW + end * pxPerSec).toInt().coerceAtMost(16000)
        val h = (hi - lo + 1) * rowH + 24
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(250, 250, 252))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun y(pitch: Int) = 24f + (hi - pitch) * rowH
        for (pitch in lo..hi) {
            p.color = if (Pitch.isBlackKey(pitch)) Color.rgb(236, 237, 242) else Color.rgb(250, 250, 252)
            c.drawRect(keyW.toFloat(), y(pitch), w.toFloat(), y(pitch) + rowH, p)
        }
        // Bar lines.
        p.textSize = 12f
        var bar = 0
        while (true) {
            val t = grid.beatsToTime(bar * grid.beatsPerBar.toDouble())
            if (t > end) break
            val x = (keyW + t * pxPerSec).toFloat()
            p.color = Color.rgb(200, 202, 210)
            c.drawLine(x, 20f, x, h.toFloat(), p)
            p.color = Color.DKGRAY
            c.drawText("${bar + 1}", x + 2, 14f, p)
            bar++
        }
        for (n in notes) {
            p.color = if (n.hand == Hand.RIGHT) Color.rgb(66, 103, 210) else Color.rgb(230, 126, 34)
            p.alpha = (110 + 145 * n.confidence).toInt().coerceIn(60, 255)
            val x0 = (keyW + n.start * pxPerSec).toFloat()
            val x1 = (keyW + n.end * pxPerSec).toFloat()
            c.drawRoundRect(x0, y(n.pitch) + 1, maxOf(x0 + 2, x1), y(n.pitch) + rowH - 1, 3f, 3f, p)
        }
        p.alpha = 255
        for (pitch in lo..hi) {
            p.color = if (Pitch.isBlackKey(pitch)) Color.rgb(40, 40, 48) else Color.WHITE
            c.drawRect(0f, y(pitch), keyW.toFloat(), y(pitch) + rowH, p)
            if (Pitch.pitchClass(pitch) == 0) {
                p.color = Color.DKGRAY
                p.textSize = 9f
                c.drawText(Pitch.name(pitch), 4f, y(pitch) + rowH - 1, p)
            }
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun writePdf(file: File, input: Input, rows: List<AnalysisRow>, analysis: Boolean) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 36f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.MONOSPACE }
        val warn = Paint(body).apply { color = Color.rgb(200, 90, 0) }
        val cols: List<Pair<String, Float>> = if (analysis) listOf(
            "#" to 26f, "Note" to 34f, "Sargam" to 46f, "Time" to 44f, "Bar:Beat" to 48f, "Dur" to 38f, "Value" to 78f,
            "Chord" to 56f, "Hand" to 28f, "Fing." to 26f, "Cents" to 32f, "Conf." to 30f,
        ) else listOf("#" to 30f, "Note" to 44f, "Sargam" to 60f, "Time (s)" to 60f, "Bar:Beat" to 60f, "Dur (s)" to 54f, "Value" to 100f, "Hand" to 40f, "Conf." to 40f)
        val lineH = 12f
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val c = page!!.canvas
            y = margin
            if (pageNo == 1) {
                c.drawText(input.title + if (analysis) " - note-by-note analysis" else " - notes", margin, y + 12, title)
                y += 24
                val meta = String.format(Locale.US, "%d notes  |  %.1f BPM  %d/%d  |  key %s  |  PianoScribe (offline)",
                    rows.size, input.grid.bpm, input.grid.beatsPerBar, input.grid.beatUnit, input.key?.name ?: "-")
                c.drawText(meta, margin, y + 8, body)
                y += 18
            }
            var x = margin
            for ((h, w) in cols) { c.drawText(h, x, y + 9, head); x += w }
            y += lineH + 2
        }
        newPage()
        for (r in rows) {
            if (y > pageH - margin) newPage()
            val c = page!!.canvas
            val values = if (analysis) listOf(
                "${r.index}", r.note, r.sargam, "%.2f".format(Locale.US, r.time), r.barBeat, "%.2f".format(Locale.US, r.duration), r.noteValue,
                r.chord, if (r.hand == Hand.RIGHT) "RH" else "LH", r.finger?.toString() ?: "", "%+d".format(Math.round(r.cents)), "${Math.round(r.confidence * 100)}%",
            ) else listOf(
                "${r.index}", r.note, r.sargam, "%.3f".format(Locale.US, r.time), r.barBeat, "%.3f".format(Locale.US, r.duration), r.noteValue,
                if (r.hand == Hand.RIGHT) "RH" else "LH", "${Math.round(r.confidence * 100)}%",
            )
            val paint = if (r.confidence < 0.5f) warn else body
            var x = margin
            for ((i, v) in values.withIndex()) {
                c.drawText(v.take(18), x, y + 9, paint)
                x += cols[i].second
            }
            y += lineH
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
