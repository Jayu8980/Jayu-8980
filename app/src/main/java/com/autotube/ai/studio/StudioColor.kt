package com.autotube.ai.studio

import androidx.media3.effect.RgbMatrix
import com.autotube.ai.pipeline.render.effects.EffectMath

/**
 * Filter presets and manual adjustments as one affine colour transform (out = M * rgb + offset),
 * exported to Media3 as a constant [RgbMatrix].
 */
object StudioColor {

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /** Affine colour op: 3x3 row-major matrix plus offset. */
    private class Op(val m: FloatArray, val o: FloatArray) {
        /** this ∘ first: applies [first] then this. */
        fun after(first: Op): Op {
            val m2 = EffectMath.mul3(m, first.m)
            val mo = EffectMath.mul3Vec(m, first.o)
            return Op(m2, floatArrayOf(mo[0] + o[0], mo[1] + o[1], mo[2] + o[2]))
        }

        fun lerpFromIdentity(t: Float): Op {
            val id = identity()
            return Op(
                FloatArray(9) { id.m[it] + (m[it] - id.m[it]) * t },
                FloatArray(3) { o[it] * t },
            )
        }
    }

    private fun identity() = Op(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f))

    private fun saturation(s: Float) = Op(
        floatArrayOf(
            LR + (1 - LR) * s, LG - LG * s, LB - LB * s,
            LR - LR * s, LG + (1 - LG) * s, LB - LB * s,
            LR - LR * s, LG - LG * s, LB + (1 - LB) * s,
        ),
        floatArrayOf(0f, 0f, 0f),
    )

    private fun contrast(c: Float, pivot: Float = 0.5f): Op {
        val off = pivot * (1f - c)
        return Op(floatArrayOf(c, 0f, 0f, 0f, c, 0f, 0f, 0f, c), floatArrayOf(off, off, off))
    }

    private fun gains(r: Float, g: Float, b: Float, or: Float = 0f, og: Float = 0f, ob: Float = 0f) =
        Op(floatArrayOf(r, 0f, 0f, 0f, g, 0f, 0f, 0f, b), floatArrayOf(or, og, ob))

    private fun sepia() = Op(
        floatArrayOf(
            0.393f, 0.769f, 0.189f,
            0.349f, 0.686f, 0.168f,
            0.272f, 0.534f, 0.131f,
        ),
        floatArrayOf(0f, 0f, 0f),
    )

    private fun chain(vararg ops: Op): Op = ops.fold(identity()) { acc, op -> op.after(acc) }

    private fun preset(filter: FilterPreset): Op = when (filter) {
        FilterPreset.NONE -> identity()
        FilterPreset.VIVID -> chain(saturation(1.35f), contrast(1.1f))
        FilterPreset.WARM -> chain(gains(1.08f, 1.0f, 0.88f, 0.02f, 0.01f, 0f), saturation(1.05f))
        FilterPreset.COOL -> chain(gains(0.9f, 1.0f, 1.1f, 0f, 0.01f, 0.03f))
        FilterPreset.CINEMATIC -> chain(saturation(0.85f), contrast(1.12f, 0.45f), gains(1.04f, 1.0f, 0.96f, 0f, 0.012f, 0.03f))
        FilterPreset.VINTAGE -> chain(sepia().lerpFromIdentity(0.55f), contrast(0.9f), gains(1f, 1f, 1f, 0.05f, 0.04f, 0.02f))
        FilterPreset.FADE -> chain(saturation(0.8f), contrast(0.8f), gains(1f, 1f, 1f, 0.06f, 0.06f, 0.07f))
        FilterPreset.DRAMA -> chain(saturation(0.75f), contrast(1.35f, 0.45f))
        FilterPreset.MONO -> chain(saturation(0f))
        FilterPreset.NOIR -> chain(saturation(0f), contrast(1.4f, 0.45f))
        FilterPreset.SUNSET -> chain(gains(1.12f, 0.98f, 0.82f, 0.03f, 0f, 0f), saturation(1.15f))
        FilterPreset.FOREST -> chain(gains(0.95f, 1.08f, 0.95f), saturation(1.1f))
    }

    private fun adjustments(a: Adjustments): Op = chain(
        gains(1f, 1f, 1f, a.brightness * 0.25f, a.brightness * 0.25f, a.brightness * 0.25f),
        contrast(1f + a.contrast * 0.6f),
        saturation(1f + a.saturation),
        gains(1f + 0.12f * a.warmth, 1f - 0.1f * a.tint, 1f - 0.12f * a.warmth),
    )

    /** Column-major 4x4 matrix for Media3, or null when the clip has no colour change. */
    fun glMatrix(filter: FilterPreset, strength: Float, adjust: Adjustments): FloatArray? {
        if ((filter == FilterPreset.NONE || strength <= 0f) && adjust.isIdentity) return null
        val op = adjustments(adjust).after(preset(filter).lerpFromIdentity(strength.coerceIn(0f, 1f)))
        return EffectMath.toGlColorMatrix(op.m, op.o)
    }

    fun effect(clip: StudioClip): RgbMatrix? {
        val m = glMatrix(clip.filter, clip.filterStrength, clip.adjust) ?: return null
        return RgbMatrix { _, _ -> m }
    }

    /**
     * Same transform as a 4x5 row-major Android ColorMatrix (offsets in 0..255), for filter thumbnails
     * drawn in Compose.
     */
    fun androidColorMatrix(filter: FilterPreset, strength: Float = 1f, adjust: Adjustments = Adjustments()): FloatArray {
        val op = adjustments(adjust).after(preset(filter).lerpFromIdentity(strength.coerceIn(0f, 1f)))
        val m = op.m
        val o = op.o
        return floatArrayOf(
            m[0], m[1], m[2], 0f, o[0] * 255f,
            m[3], m[4], m[5], 0f, o[1] * 255f,
            m[6], m[7], m[8], 0f, o[2] * 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}
