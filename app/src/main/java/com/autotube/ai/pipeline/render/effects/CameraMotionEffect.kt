package com.autotube.ai.pipeline.render.effects

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation
import com.autotube.ai.core.model.CameraMotion
import com.autotube.ai.core.model.MotionType
import com.autotube.ai.core.model.Shot
import kotlin.math.abs
import kotlin.math.max

/**
 * Eased camera move for one shot (or one segment of a shot): Ken Burns zoom, pans, parallax,
 * punch-ins with ease-in-out envelopes and a very light deterministic handheld shake.
 *
 * Works in normalized device coordinates (-1..1). The frame is always scaled enough that no black
 * border is revealed by the pan, shake or rotation.
 *
 * @param segmentOffsetMs where this media item starts inside [shot] (non-zero when a shot is split).
 * @param segmentDurationMs duration of this media item.
 * @param aspect output width / height, used to keep rotation isotropic.
 */
class CameraMotionEffect(
    private val shot: Shot,
    private val segmentOffsetMs: Long,
    private val segmentDurationMs: Long,
    private val aspect: Float,
    handheldShake: Boolean,
) : MatrixTransformation {

    private val clock = LatchedClock()
    private val motion: CameraMotion = normalizedMotion(shot.motion)
    private val shakeAmp: Float = if (handheldShake) motion.shake.coerceIn(0f, 1f) else 0f

    /** NDC translation reserved for the shake (0.6 % of frame width = 0.012 NDC at full amplitude). */
    private val shakeTx = MAX_SHAKE_TRANSLATE_NDC * shakeAmp
    private val shakeRotDeg = MAX_SHAKE_ROTATION_DEG * shakeAmp
    /** Extra scale so that a rotated frame still covers the corners. */
    private val rotationReserve = (shakeRotDeg * Math.PI.toFloat() / 180f) * (1f + aspect)

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val localMs = clock.elapsedMs(presentationTimeUs).coerceAtMost(segmentDurationMs.toFloat())
        val shotMs = segmentOffsetMs + localMs
        val shotDur = max(1L, shot.durationMs).toFloat()
        val p = EffectMath.ease(motion.easing, shotMs / shotDur)

        val baseZoom = EffectMath.lerp(motion.zoomStart, motion.zoomEnd, p).coerceIn(1f, 1.6f)
        var punch = 1f
        for (pi in shot.punchIns) {
            val startInShot = (pi.atMs - shot.startMs).toFloat()
            val env = EffectMath.envelope(shotMs, startInShot, pi.durationMs.toFloat(), riseFraction = 0.3f)
            if (env > 0f) punch = max(punch, 1f + (pi.scale.coerceIn(1f, 1.2f) - 1f) * env)
        }
        val reserve = shakeTx + rotationReserve
        val scale = baseZoom * punch + reserve

        val panX = EffectMath.lerp(motion.panStartX, motion.panEndX, p).coerceIn(-1f, 1f)
        val panY = EffectMath.lerp(motion.panStartY, motion.panEndY, p).coerceIn(-1f, 1f)
        val panMargin = (scale - 1f - reserve).coerceAtLeast(0f)
        var tx = panX * panMargin
        var ty = panY * panMargin
        var rot = 0f
        if (shakeAmp > 0f) {
            val seconds = (shot.startMs + shotMs) / 1000f
            tx += shakeTx * EffectMath.smoothNoise(seconds, motion.shakeSeed, 0)
            ty += shakeTx * EffectMath.smoothNoise(seconds, motion.shakeSeed, 1)
            rot = shakeRotDeg * EffectMath.smoothNoise(seconds * 0.8f, motion.shakeSeed, 2)
        }

        return Matrix().apply {
            setScale(scale, scale)
            if (rot != 0f) {
                postScale(aspect, 1f)
                postRotate(rot)
                postScale(1f / aspect, 1f)
            }
            postTranslate(tx, ty)
        }
    }

    private companion object {
        const val MAX_SHAKE_TRANSLATE_NDC = 0.012f
        const val MAX_SHAKE_ROTATION_DEG = 0.15f
        const val DEFAULT_PAN_ZOOM = 1.12f

        /** Fills in sensible values when the EDL names a motion type but leaves the numbers at default. */
        fun normalizedMotion(m: CameraMotion): CameraMotion {
            val untouched = m.zoomStart == 1f && m.zoomEnd == 1f &&
                m.panStartX == 0f && m.panEndX == 0f && m.panStartY == 0f && m.panEndY == 0f
            val filled = if (!untouched) m else when (m.type) {
                MotionType.STATIC -> m
                MotionType.KEN_BURNS_IN -> m.copy(zoomStart = 1.0f, zoomEnd = 1.12f)
                MotionType.KEN_BURNS_OUT -> m.copy(zoomStart = 1.12f, zoomEnd = 1.0f)
                MotionType.PAN_LEFT -> m.copy(zoomStart = DEFAULT_PAN_ZOOM, zoomEnd = DEFAULT_PAN_ZOOM, panStartX = -0.8f, panEndX = 0.8f)
                MotionType.PAN_RIGHT -> m.copy(zoomStart = DEFAULT_PAN_ZOOM, zoomEnd = DEFAULT_PAN_ZOOM, panStartX = 0.8f, panEndX = -0.8f)
                MotionType.PAN_UP -> m.copy(zoomStart = DEFAULT_PAN_ZOOM, zoomEnd = DEFAULT_PAN_ZOOM, panStartY = 0.8f, panEndY = -0.8f)
                MotionType.PAN_DOWN -> m.copy(zoomStart = DEFAULT_PAN_ZOOM, zoomEnd = DEFAULT_PAN_ZOOM, panStartY = -0.8f, panEndY = 0.8f)
                MotionType.PARALLAX -> m.copy(zoomStart = 1.04f, zoomEnd = 1.16f, panStartX = -0.5f, panEndX = 0.5f)
            }
            // A pan needs some zoom margin to move within; give it one if the plan forgot.
            val pans = abs(filled.panStartX) + abs(filled.panEndX) + abs(filled.panStartY) + abs(filled.panEndY) > 0f
            val minZoom = if (pans) 1.06f else 1f
            return filled.copy(
                zoomStart = max(filled.zoomStart, minZoom),
                zoomEnd = max(filled.zoomEnd, minZoom),
            )
        }
    }
}
