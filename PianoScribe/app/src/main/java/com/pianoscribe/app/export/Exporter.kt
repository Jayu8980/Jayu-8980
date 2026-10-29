package com.pianoscribe.app.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.pianoscribe.core.analysis.AnalysisRow
import com.pianoscribe.core.analysis.AnalysisTable
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.midi.MusicXmlWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.score.Engraver
import com.pianoscribe.app.ui.score.ScorePainter
import com.pianoscribe.core.synth.OfflineRenderer
import com.pianoscribe.core.synth.Sf2Preset
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.Pitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    MIDI("MIDI file (.mid)", "mid", "audio/midi"),
    MIDI_PERFORMED("MIDI as performed: instrument + human feel (.mid)", "mid", "audio/midi"),
    SCORE_PDF("Sheet music, printable (.pdf)", "pdf", "application/pdf"),
    WAV("Audio of the performance (.wav)", "wav", "audio/wav"),
    MUSICXML("MusicXML for MuseScore (.musicxml)", "musicxml", "application/vnd.recordare.musicxml+xml"),
    PNG("Piano roll image (.png)", "png", "image/png"),
    NOTES_PDF("Note list (.pdf)", "pdf", "application/pdf"),
    NOTES_TXT("Note list (.txt)", "txt", "text/plain"),
    ANALYSIS_CSV("Analysis table (.csv)", "csv", "text/csv"),
    ANALYSIS_PDF("Analysis table (.pdf)", "pdf", "application/pdf"),
}

/** Writes exports into cache/exports and hands them to Downloads, the share sheet, another app or a chosen location. */
class Exporter(private val context: Context) {
    /**
     * @param performance the notes as played on [instrument] (MIDI_PERFORMED and WAV use it)
     * @param preset SoundFont preset for [instrument], or null for the built-in synth model
     */
    data class Input(
        val title: String,
        val notes: List<Note>,
        val grid: TimeGrid,
        val key: KeyDetector.Key?,
        val saPitchClass: Int,
        val performance: List<PlayedNote> = notes.map(PlayedNote::of),
        val instrument: Instrument = Instrument.PIANO,
        val preset: Sf2Preset? = null,
        val expression: Float = 0.6f,
    )

    suspend fun export(format: ExportFormat, input: Input): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(input.title, format, input.instrument))
        val rows by lazy { AnalysisTable.rows(input.notes, input.grid, input.saPitchClass, input.key) }
        when (format) {
            ExportFormat.MIDI -> file.writeBytes(MidiWriter.write(input.notes, input.grid, input.title))
            ExportFormat.MIDI_PERFORMED -> {
                val played = input.performance.mapIndexed { i, p -> Note(i + 1L, p.pitch, p.start, p.duration, p.velocity, 1f, p.hand) }
                file.writeBytes(MidiWriter.write(played, input.grid, "${input.title} (${input.instrument.label})", input.instrument.gmProgram))
            }
            ExportFormat.WAV -> BufferedOutputStream(FileOutputStream(file), 1 shl 16).use {
                OfflineRenderer.render(input.performance, input.instrument, input.preset, WAV_RATE, it, input.expression)
            }
            ExportFormat.MUSICXML -> file.writeText(MusicXmlWriter.write(input.notes, input.grid, input.title, input.key?.fifths ?: 0, input.key))
            ExportFormat.SCORE_PDF -> writeScorePdf(file, input)
            ExportFormat.PNG -> writePianoRollPng(input.notes, input.grid, file)
            ExportFormat.NOTES_TXT -> file.writeText(AnalysisTable.text(rows, input.title, input.grid, input.key?.name))
            ExportFormat.ANALYSIS_CSV -> file.writeText(AnalysisTable.csv(rows))
            ExportFormat.NOTES_PDF -> writePdf(file, input, rows, analysis = false)
            ExportFormat.ANALYSIS_PDF -> writePdf(file, input, rows, analysis = true)
        }
        file
    }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun share(file: File, format: ExportFormat) {
        val uri = contentUri(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            // ClipData carries the read grant through the chooser to whichever app is picked;
            // EXTRA_STREAM alone is not always granted.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share ${file.name}").apply {
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Opens the file in an app that plays it (for MIDI: a MIDI player or DAW). Returns false if none is installed. */
    fun open(file: File, format: ExportFormat): Boolean {
        val uri = contentUri(file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, format.mime)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Copies an export into the public Downloads/PianoScribe folder (Android 10+), where every file
     * manager and MIDI app can find it. Returns a human-readable location, or null when the
     * platform has no MediaStore Downloads collection (Android 8-9: use [saveTo] instead).
     */
    suspend fun saveToDownloads(file: File, format: ExportFormat): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PianoScribe")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = resolver.insert(collection, values) ?: error("Couldn't create the file in Downloads")
        try {
            resolver.openOutputStream(item)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't write to Downloads")
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw e
        }
        // MediaStore may rename duplicates ("Song (1).mid"), so report the name it actually used.
        val name = resolver.query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: file.name
        "Downloads/PianoScribe/$name"
    }

    /** Copies an export to a location the user picked with the system "Save as" dialog. */
    suspend fun saveTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Couldn't write to the chosen location")
    }

    companion object {
        const val WAV_RATE = 44100

        /** "Song.mid", "Song-notes.pdf", "Song-flute-performed.mid", "Song-flute.wav". */
        fun fileName(title: String?, format: ExportFormat, instrument: Instrument = Instrument.PIANO): String {
            val inst = instrument.label.lowercase().replace(' ', '-')
            val suffix = when (format) {
                ExportFormat.ANALYSIS_CSV, ExportFormat.ANALYSIS_PDF -> "-analysis"
                ExportFormat.NOTES_PDF, ExportFormat.NOTES_TXT -> "-notes"
                ExportFormat.SCORE_PDF -> "-score"
                ExportFormat.MIDI_PERFORMED -> "-$inst-performed"
                ExportFormat.WAV -> "-$inst"
                else -> ""
            }
            return safeName(title ?: "PianoScribe") + suffix + "." + format.ext
        }

        private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _\\-]"), "_").trim().ifEmpty { "PianoScribe" }.take(60)
    }

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

    /**
     * The engraved piano score on A4 pages (the same engraving as the score view), systems moved
     * whole to the next page when they don't fit, with page numbers.
     */
    private fun writeScorePdf(file: File, input: Input) {
        val pageW = 595
        val pageH = 842
        val margin = 40f
        val space = 5.2f // points per staff space: a 7 mm staff, the usual size for piano music
        val left = margin + 2.5f * space // room for the brace
        val widthSpaces = (pageW - left - margin) / space
        val score = Engraver.engrave(input.notes, input.grid, input.key, widthSpaces, Engraver.Options(title = input.title))
        val painter = ScorePainter(ScorePainter.music(context), Color.BLACK)
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.DKGRAY; textAlign = Paint.Align.CENTER }
        val doc = PdfDocument()
        val usable = (pageH - 2 * margin - 14f) / space
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var cursor = 0f // staff spaces used on this page
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()).also {
                it.canvas.drawText("${input.title} · page $pageNo · PianoScribe", pageW / 2f, pageH - margin / 2, footer)
            }
            cursor = 0f
        }
        newPage()
        page!!.canvas.apply { save(); translate(left, margin); painter.draw(this, score.header, space); restore() }
        cursor = score.headerBottom
        for (s in score.systems) {
            val h = s.bottom - s.top
            if (cursor + h > usable && cursor > 0f) newPage()
            page!!.canvas.apply {
                save()
                translate(left, margin + (cursor - s.top) * space)
                painter.draw(this, s.marks, space)
                restore()
            }
            cursor += h + 2f
        }
        page?.let { doc.finishPage(it) }
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
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
