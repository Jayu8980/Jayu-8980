package com.autotube.ai.pipeline.render.effects

import android.graphics.Paint
import android.graphics.Typeface
import com.autotube.ai.core.model.CaptionStyle
import com.autotube.ai.core.model.VideoFormat
import java.util.Locale

/** What a piece of on-screen text is, which decides its safe zone and size. */
enum class TextFitKind { CAPTION, TEXT_POP }

/**
 * Size and safe-zone rules for on-screen text, shared by the overlays that draw it and by QC (which
 * calls [measureOverflow]). Everything is relative to the output frame so 720p fallbacks scale too.
 */
data class TextFitSpec(
    val maxWidthPx: Float,
    val baseSizePx: Float,
    val minSizePx: Float,
    val maxLines: Int = 2,
    /** Horizontal centre of the text block. */
    val centerX: Float,
    /** Preferred vertical centre of the text block. */
    val anchorY: Float,
    /** The block must stay between these y values. */
    val safeTop: Float,
    val safeBottom: Float,
    /** Left edge for left-aligned blocks (long-form text pops). */
    val left: Float,
    val leftAligned: Boolean = false,
)

/** One measurable word: [text] gets the outline, [emoji] (optional) is drawn after it without one. */
data class FitToken(val text: String, val emoji: String? = null)

/** Result of fitting tokens into lines. */
data class FitResult(
    val textSize: Float,
    /** Token indices per line. */
    val lines: List<List<Int>>,
    val fits: Boolean,
)

object TextFit {

    /** Headroom for the popped (scaled-up) active word and the outline. */
    const val ACTIVE_WORD_SCALE = 1.12f

    fun spec(width: Int, height: Int, vertical: Boolean, kind: TextFitKind, style: CaptionStyle = CaptionStyle.BOLD_POP): TextFitSpec {
        val w = width.toFloat()
        val h = height.toFloat()
        val styleScale = if (style == CaptionStyle.MINIMAL) 0.8f else 1f
        return when (kind) {
            TextFitKind.CAPTION -> if (vertical) {
                // Shorts: avoid top ~12 %, bottom ~25-30 % (title/buttons) and the right ~15 % rail.
                val left = 0.06f * w
                val right = 0.85f * w
                TextFitSpec(
                    maxWidthPx = right - left,
                    baseSizePx = 0.075f * w * styleScale,
                    minSizePx = 0.045f * w * styleScale,
                    centerX = (left + right) / 2f,
                    anchorY = 0.62f * h,
                    safeTop = 0.20f * h,
                    safeBottom = 0.70f * h,
                    left = left,
                )
            } else {
                TextFitSpec(
                    maxWidthPx = 0.80f * w,
                    baseSizePx = 0.058f * h * styleScale,
                    minSizePx = 0.036f * h * styleScale,
                    centerX = w / 2f,
                    anchorY = 0.82f * h,
                    safeTop = 0.10f * h,
                    safeBottom = 0.93f * h,
                    left = 0.10f * w,
                )
            }
            TextFitKind.TEXT_POP -> if (vertical) {
                val left = 0.08f * w
                val right = 0.83f * w
                TextFitSpec(
                    maxWidthPx = right - left - 0.06f * w,
                    baseSizePx = 0.064f * w,
                    minSizePx = 0.040f * w,
                    centerX = (left + right) / 2f,
                    anchorY = 0.28f * h,
                    safeTop = 0.13f * h,
                    safeBottom = 0.45f * h,
                    left = left,
                )
            } else {
                TextFitSpec(
                    maxWidthPx = 0.38f * w,
                    baseSizePx = 0.050f * h,
                    minSizePx = 0.032f * h,
                    centerX = 0.25f * w,
                    anchorY = 0.20f * h,
                    safeTop = 0.07f * h,
                    safeBottom = 0.36f * h,
                    left = 0.07f * w,
                    leftAligned = true,
                )
            }
        }
    }

    /** Heavy sans-serif; Devanagari and emoji fall back to the system Noto fonts automatically. */
    fun boldTypeface(): Typeface =
        Typeface.create("sans-serif-black", Typeface.BOLD) ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    fun newPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = boldTypeface()
        textAlign = Paint.Align.LEFT
    }

    /** Uppercase for the bold style (no-op for scripts without case, such as Devanagari). */
    fun displayText(text: String, style: CaptionStyle): String =
        if (style == CaptionStyle.BOLD_POP) text.uppercase(Locale.ROOT) else text

    fun tokenWidth(paint: Paint, token: FitToken): Float {
        var w = paint.measureText(token.text)
        if (token.emoji != null) w += paint.measureText(" ") + paint.measureText(token.emoji)
        return w
    }

    /**
     * Greedy line breaking at decreasing font sizes until the tokens fit [spec].maxLines lines of
     * [spec].maxWidthPx (with [headroom] for scaling/outline). Returns the layout at the minimum size
     * with fits=false when nothing works.
     */
    fun fit(tokens: List<FitToken>, spec: TextFitSpec, paint: Paint, headroom: Float = ACTIVE_WORD_SCALE): FitResult {
        if (tokens.isEmpty()) return FitResult(spec.baseSizePx, emptyList(), true)
        var size = spec.baseSizePx
        while (true) {
            paint.textSize = size
            val lines = breakLines(tokens, paint, spec.maxWidthPx / headroom)
            if (lines != null && lines.size <= spec.maxLines) return FitResult(size, lines, true)
            if (size <= spec.minSizePx) {
                return FitResult(spec.minSizePx, lines ?: listOf(tokens.indices.toList()), false)
            }
            size = (size * 0.93f).coerceAtLeast(spec.minSizePx)
        }
    }

    /** Null when one token alone is wider than [maxWidth]. */
    private fun breakLines(tokens: List<FitToken>, paint: Paint, maxWidth: Float): List<List<Int>>? {
        val space = paint.measureText(" ")
        val lines = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        var width = 0f
        for (i in tokens.indices) {
            val tw = tokenWidth(paint, tokens[i]) + paint.textSize * 0.16f // outline
            if (tw > maxWidth) return null
            val needed = if (current.isEmpty()) tw else width + space + tw
            if (needed <= maxWidth) {
                current.add(i)
                width = needed
            } else {
                lines.add(current)
                current = mutableListOf(i)
                width = tw
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    fun tokensOf(text: String): List<FitToken> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { FitToken(it) }

    /**
     * True when [text] cannot fit in 2 lines at the minimum font size for [kind] on [format]
     * (worst case: uppercase bold captions).
     */
    fun measureOverflow(text: String, format: VideoFormat, kind: TextFitKind): Boolean {
        val shown = if (kind == TextFitKind.CAPTION) displayText(text, CaptionStyle.BOLD_POP) else text
        val tokens = tokensOf(shown)
        if (tokens.isEmpty()) return false
        val spec = spec(format.width, format.height, format.isVertical, kind)
        val headroom = if (kind == TextFitKind.CAPTION) ACTIVE_WORD_SCALE else 1f
        return !fit(tokens, spec, newPaint(), headroom).fits
    }
}

/** Top-level shortcut used by QC. */
fun measureOverflow(text: String, format: VideoFormat, kind: TextFitKind): Boolean =
    TextFit.measureOverflow(text, format, kind)
