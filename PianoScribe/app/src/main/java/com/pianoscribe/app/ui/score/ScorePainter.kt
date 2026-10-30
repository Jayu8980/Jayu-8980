package com.pianoscribe.app.ui.score

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.pianoscribe.core.score.Mark

/**
 * Draws engraved score marks (core/score) on an Android canvas: the screen and PDF pages use the
 * same code. Coordinates are staff spaces; [scale] is pixels (or PDF points) per staff space.
 */
class ScorePainter(private val music: Typeface, private val ink: Int) {
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = music; color = ink }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = ink }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ink }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val path = Path()

    fun draw(canvas: Canvas, marks: List<Mark>, scale: Float) {
        glyph.textSize = 4 * scale // SMuFL: one em is four staff spaces
        for (m in marks) when (m) {
            is Mark.Glyph -> {
                canvas.save()
                canvas.translate(m.x * scale, m.y * scale)
                if (m.scaleX != 1f || m.scaleY != 1f) canvas.scale(m.scaleX, m.scaleY)
                canvas.drawText(m.code.toString(), 0f, 0f, glyph)
                canvas.restore()
            }
            is Mark.Line -> {
                line.strokeWidth = m.thickness * scale
                canvas.drawLine(m.x1 * scale, m.y1 * scale, m.x2 * scale, m.y2 * scale, line)
            }
            is Mark.Beam -> {
                path.reset()
                path.moveTo(m.x1 * scale, m.y1 * scale)
                path.lineTo(m.x2 * scale, m.y2 * scale)
                path.lineTo(m.x2 * scale, (m.y2 + m.thickness * m.inward) * scale)
                path.lineTo(m.x1 * scale, (m.y1 + m.thickness * m.inward) * scale)
                path.close()
                canvas.drawPath(path, fill)
            }
            is Mark.Tie -> {
                // A crescent: thin at the ends, about a fifth of a space thick in the middle.
                val d = if (m.above) -1f else 1f
                val len = m.x2 - m.x1
                val bow = (0.5f + 0.06f * len).coerceAtMost(1.6f)
                val y1 = m.y1 + d * 0.4f
                val y2 = m.y2 + d * 0.4f
                path.reset()
                path.moveTo(m.x1 * scale, y1 * scale)
                path.cubicTo((m.x1 + len * 0.25f) * scale, (y1 + d * bow) * scale, (m.x2 - len * 0.25f) * scale, (y2 + d * bow) * scale, m.x2 * scale, y2 * scale)
                path.cubicTo((m.x2 - len * 0.25f) * scale, (y2 + d * (bow - 0.22f)) * scale, (m.x1 + len * 0.25f) * scale, (y1 + d * (bow - 0.22f)) * scale, m.x1 * scale, y1 * scale)
                path.close()
                canvas.drawPath(path, fill)
            }
            is Mark.Text -> {
                text.textSize = m.size * scale
                text.typeface = Typeface.create(Typeface.SERIF, (if (m.bold) Typeface.BOLD else 0) or (if (m.italic) Typeface.ITALIC else 0))
                text.textAlign = if (m.center) Paint.Align.CENTER else Paint.Align.LEFT
                canvas.drawText(m.text, m.x * scale, m.y * scale, text)
            }
        }
    }

    companion object {
        @Volatile private var bravura: Typeface? = null

        /** The bundled SMuFL font Bravura (SIL Open Font License). */
        fun music(context: Context): Typeface =
            bravura ?: Typeface.createFromAsset(context.applicationContext.assets, "fonts/bravura.otf").also { bravura = it }
    }
}
