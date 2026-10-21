package com.autotube.ai.pipeline.render.effects

import androidx.media3.effect.RgbMatrix
import com.autotube.ai.core.model.Emotion

/**
 * One consistent base grade for every clip (mild contrast and saturation normalisation, a gentle
 * S-curve approximated as a contrast lift around mid-grey with protected blacks) plus a mood tint:
 * warm for happy/inspiring/funny, cool teal for suspense/dramatic/sad, neutral otherwise.
 *
 * The matrix is constant, so it is computed once.
 */
class ColorGradeEffect(mood: Emotion?) : RgbMatrix {

    private val matrix: FloatArray = buildMatrix(mood)

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix

    companion object {
        private const val LR = 0.2126f
        private const val LG = 0.7152f
        private const val LB = 0.0722f

        fun buildMatrix(mood: Emotion?): FloatArray {
            // Saturation around Rec.709 luma.
            val sat = 1.08f
            val s = floatArrayOf(
                LR + (1 - LR) * sat, LG - LG * sat, LB - LB * sat,
                LR - LR * sat, LG + (1 - LG) * sat, LB - LB * sat,
                LR - LR * sat, LG - LG * sat, LB + (1 - LB) * sat,
            )
            // Contrast around a slightly low pivot: deepens shadows a touch, opens highlights (S-curve-ish).
            val contrast = 1.07f
            val pivot = 0.45f
            val c = floatArrayOf(
                contrast, 0f, 0f,
                0f, contrast, 0f,
                0f, 0f, contrast,
            )
            val cOffset = pivot * (1f - contrast)

            val (tint, tintOffset) = when {
                mood == null -> identity() to floatArrayOf(0f, 0f, 0f)
                mood.isWarm -> floatArrayOf(
                    1.05f, 0f, 0f,
                    0f, 1.01f, 0f,
                    0f, 0f, 0.93f,
                ) to floatArrayOf(0.008f, 0.004f, 0f)
                mood.isCool -> floatArrayOf(
                    0.94f, 0f, 0f,
                    0f, 1.01f, 0f,
                    0f, 0f, 1.06f,
                ) to floatArrayOf(0f, 0.006f, 0.012f)
                else -> identity() to floatArrayOf(0f, 0f, 0f)
            }

            // out = tint * (c * (s * rgb) + cOffset) + tintOffset
            val cs = EffectMath.mul3(c, s)
            val total = EffectMath.mul3(tint, cs)
            val offsetAfterContrast = floatArrayOf(cOffset, cOffset, cOffset)
            val tinted = EffectMath.mul3Vec(tint, offsetAfterContrast)
            val offset = floatArrayOf(
                tinted[0] + tintOffset[0],
                tinted[1] + tintOffset[1],
                tinted[2] + tintOffset[2],
            )
            return EffectMath.toGlColorMatrix(total, offset)
        }

        private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
