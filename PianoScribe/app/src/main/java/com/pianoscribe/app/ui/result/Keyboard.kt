package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import com.pianoscribe.core.theory.Pitch

/** Horizontal key layout for a range of MIDI pitches, starting and ending on white keys. */
class KeyLayout(lo: Int, hi: Int, val width: Float) {
    val lo: Int = if (Pitch.isBlackKey(lo)) lo - 1 else lo
    val hi: Int = if (Pitch.isBlackKey(hi)) hi + 1 else hi
    private val whiteBefore = IntArray(this.hi - this.lo + 2)
    val whiteCount: Int
    init {
        var c = 0
        for (m in this.lo..this.hi) {
            whiteBefore[m - this.lo] = c
            if (!Pitch.isBlackKey(m)) c++
        }
        whiteCount = c
    }
    val whiteW: Float get() = width / whiteCount
    val blackW: Float get() = whiteW * 0.62f

    fun contains(m: Int) = m in lo..hi

    /** Left edge and width of a key. */
    fun keyX(m: Int): Pair<Float, Float> {
        val i = whiteBefore[(m - lo).coerceIn(0, hi - lo)]
        return if (Pitch.isBlackKey(m)) (i * whiteW - blackW / 2) to blackW else (i * whiteW) to whiteW
    }

    fun centerX(m: Int): Float = keyX(m).let { it.first + it.second / 2 }

    /** Key under x at height fraction yFrac (0 top..1 bottom); black keys only cover the top 62%. */
    fun keyAt(x: Float, yFrac: Float): Int? {
        if (yFrac < 0.62f) {
            for (m in lo..hi) if (Pitch.isBlackKey(m)) {
                val (kx, kw) = keyX(m)
                if (x >= kx && x < kx + kw) return m
            }
        }
        for (m in lo..hi) if (!Pitch.isBlackKey(m)) {
            val (kx, kw) = keyX(m)
            if (x >= kx && x < kx + kw) return m
        }
        return null
    }
}

/** Draws a keyboard into the given rectangle; keys in [lit] are filled with their colour. */
fun DrawScope.drawKeyboard(
    layout: KeyLayout,
    top: Float,
    height: Float,
    lit: Map<Int, Color>,
    labels: Map<Int, String> = emptyMap(),
    outline: Color = Color(0xFF555555),
    labelC: Boolean = true,
) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    for (m in layout.lo..layout.hi) if (!Pitch.isBlackKey(m)) {
        val (x, w) = layout.keyX(m)
        drawRect(lit[m] ?: Color.White, Offset(x, top), Size(w, height))
        drawRect(outline, Offset(x, top), Size(w, height), style = Stroke(1f))
        val label = labels[m]
        if (label != null) {
            paint.textSize = minOf(w * 0.75f, height * 0.25f).coerceAtLeast(9f)
            paint.color = android.graphics.Color.BLACK
            paint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText(label, x + w / 2, top + height - paint.textSize * 0.6f, paint)
        } else if (labelC && Pitch.pitchClass(m) == 0 && w > 9f) {
            paint.textSize = minOf(w * 0.55f, 26f)
            paint.color = android.graphics.Color.GRAY
            paint.isFakeBoldText = false
            drawContext.canvas.nativeCanvas.drawText(Pitch.name(m), x + w / 2, top + height - 4f, paint)
        }
    }
    val bh = height * 0.62f
    for (m in layout.lo..layout.hi) if (Pitch.isBlackKey(m)) {
        val (x, w) = layout.keyX(m)
        drawRoundRect(lit[m] ?: Color(0xFF222226), Offset(x, top), Size(w, bh), CornerRadius(2f, 2f))
        val label = labels[m]
        if (label != null) {
            paint.textSize = minOf(w * 0.85f, bh * 0.3f).coerceAtLeast(9f)
            paint.color = (if (lit[m] != null) Color.Black else Color.White).toArgb()
            paint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText(label, x + w / 2, top + bh - paint.textSize * 0.4f, paint)
        }
    }
}

@Composable
fun PianoKeyboard(
    lo: Int,
    hi: Int,
    lit: Map<Int, Color>,
    modifier: Modifier = Modifier,
    labels: Map<Int, String> = emptyMap(),
    onKey: ((Int) -> Unit)? = null,
) {
    var layoutRef: KeyLayout? = null
    Canvas(modifier.pointerInput(lo, hi, onKey) {
        if (onKey != null) detectTapGestures { p ->
            val l = layoutRef ?: return@detectTapGestures
            l.keyAt(p.x, p.y / size.height)?.let(onKey)
        }
    }) {
        val l = KeyLayout(lo, hi, size.width)
        layoutRef = l
        drawKeyboard(l, 0f, size.height, lit, labels)
    }
}
