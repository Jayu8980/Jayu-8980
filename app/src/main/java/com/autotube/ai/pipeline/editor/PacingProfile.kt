package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.core.model.VideoRequest
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Shot-length and rhythm rules for one [EditStyle] + [VideoFormat]. Shared by the visual sourcer
 * (how many distinct shots a scene needs) and the editor (where to cut), so both agree.
 *
 * - Fast Shorts: typical shots 2-3.5 s (1.5 s allowed in high-intensity Shorts moments), max 4 s.
 * - Documentary: 3.5-6 s.
 * - Calm educational: 4.5-6 s.
 * High intensity / energetic emotions sit at the short end, calm/sad scenes at the long end.
 */
class PacingProfile private constructor(
    val style: EditStyle,
    val format: VideoFormat,
    private val typicalMinMs: Long,
    private val typicalMaxMs: Long,
    private val floorMs: Long,
    private val fastFloorMs: Long,
    /** Longest shot the editor aims for (exceeded only when a scene has too few visuals). */
    val maxShotMs: Long,
    /** Pattern interrupt spacing: Shorts 5-10 s, long-form 20-40 s. */
    val patternInterruptMinMs: Long,
    val patternInterruptMaxMs: Long,
) {
    val isShorts: Boolean get() = format == VideoFormat.SHORTS

    /** Minimum spacing between two punch-ins. */
    val punchInSpacingMs: Long get() = PUNCH_IN_SPACING_MS

    /** -1 (very calm) .. 1 (very energetic). */
    fun energy(scene: Scene): Double {
        val intensityPart = (scene.intensity.coerceIn(1, 10) - 5.5) / 4.5 * 0.7
        val emotionPart = when (scene.emotion) {
            Emotion.EXCITING, Emotion.DRAMATIC -> 0.3
            Emotion.SUSPENSE, Emotion.FUNNY -> 0.2
            Emotion.HAPPY, Emotion.INSPIRING -> 0.05
            Emotion.CALM -> -0.3
            Emotion.SAD -> -0.25
        }
        return (intensityPart + emotionPart).coerceIn(-1.0, 1.0)
    }

    /** True for the parts where an editor cuts fastest. */
    fun isFast(scene: Scene): Boolean =
        scene.intensity >= 7 || (scene.emotion.isHighEnergy && scene.intensity >= 6)

    /** Typical shot length for [scene]: shorter when exciting, longer when calm. */
    fun targetShotMs(scene: Scene): Long {
        val e = energy(scene)
        val mid = (typicalMinMs + typicalMaxMs) / 2.0
        val half = (typicalMaxMs - typicalMinMs) / 2.0
        val target = (mid - e * half).roundToLong()
        return target.coerceIn(minShotMs(scene), maxShotMs)
    }

    /** Shortest shot allowed in [scene]. */
    fun minShotMs(scene: Scene): Long = if (isFast(scene)) fastFloorMs else floorMs

    /** ceil(span / typical shot), clamped to 1..[maxShots]. */
    fun shotsFor(scene: Scene, spanMs: Long, maxShots: Int = MAX_SHOTS_PER_SCENE): Int {
        if (spanMs <= 0L) return 1
        return ceil(spanMs.toDouble() / targetShotMs(scene)).toInt().coerceIn(1, maxShots.coerceAtLeast(1))
    }

    companion object {
        const val MAX_SHOTS_PER_SCENE = 6
        private const val PUNCH_IN_SPACING_MS = 4_000L

        fun of(style: EditStyle, format: VideoFormat): PacingProfile {
            val shorts = format == VideoFormat.SHORTS
            val piMin = if (shorts) 5_000L else 20_000L
            val piMax = if (shorts) 10_000L else 40_000L
            return when (style) {
                EditStyle.FAST_SHORTS -> PacingProfile(
                    style = style, format = format,
                    typicalMinMs = 2_000, typicalMaxMs = 3_500,
                    floorMs = 2_000, fastFloorMs = if (shorts) 1_500 else 2_000,
                    maxShotMs = if (shorts) 4_000 else 5_000,
                    patternInterruptMinMs = piMin, patternInterruptMaxMs = piMax,
                )
                EditStyle.DOCUMENTARY -> PacingProfile(
                    style = style, format = format,
                    typicalMinMs = 3_500, typicalMaxMs = 6_000,
                    floorMs = 2_000, fastFloorMs = if (shorts) 1_500 else 2_000,
                    maxShotMs = if (shorts) 4_500 else 6_000,
                    patternInterruptMinMs = piMin, patternInterruptMaxMs = piMax,
                )
                EditStyle.CALM_EDUCATIONAL -> PacingProfile(
                    style = style, format = format,
                    typicalMinMs = 4_500, typicalMaxMs = 6_000,
                    floorMs = if (shorts) 2_000 else 2_500, fastFloorMs = 2_000,
                    maxShotMs = if (shorts) 5_000 else 6_000,
                    patternInterruptMinMs = piMin, patternInterruptMaxMs = piMax,
                )
            }
        }

        /**
         * Shorts -> FAST_SHORTS unless the topic is calm/educational; long-form keeps the style the
         * script writer suggested.
         */
        fun selectStyle(request: VideoRequest, script: Script): EditStyle {
            if (!request.isShorts) return script.style
            return if (isCalmTopic(request, script)) EditStyle.CALM_EDUCATIONAL else EditStyle.FAST_SHORTS
        }

        fun forScript(request: VideoRequest, script: Script): PacingProfile =
            of(selectStyle(request, script), request.format)

        private fun isCalmTopic(request: VideoRequest, script: Script): Boolean {
            if (script.style == EditStyle.CALM_EDUCATIONAL) return true
            val scenes = script.scenes
            if (scenes.isEmpty()) return false
            val avgIntensity = scenes.map { it.intensity.coerceIn(1, 10) }.average()
            val calmShare = scenes.count { it.emotion == Emotion.CALM || it.emotion == Emotion.SAD }.toDouble() / scenes.size
            val text = (request.niche + " " + script.title + " " + request.notes).lowercase()
            val calmWords = CALM_TOPIC_WORDS.any { text.contains(it) }
            return (calmWords && avgIntensity <= 5.0) || (avgIntensity <= 3.5 && calmShare >= 0.6)
        }

        private val CALM_TOPIC_WORDS = listOf(
            "meditation", "meditate", "sleep", "relax", "calm", "study", "lesson", "tutorial",
            "explained", "learn", "yoga", "mindful", "asmr", "breathing", "how to",
            "ध्यान", // dhyan (meditation)
            "नींद", // neend (sleep)
            "सीख", // seekh (learn)
            "योग", // yog
            "शिक्षा", // shiksha (education)
        )
    }
}
