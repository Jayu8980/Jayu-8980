package com.autotube.ai.pipeline.length

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.pipeline.timeline.VoiceGapModel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Speaking-rate and duration math shared by the script writer and the length manager.
 * English ~150 wpm, Hindi ~130 wpm; Shorts are delivered ~10 % faster.
 */
internal object DurationModel {

    const val SHORTS_HARD_MAX_MS = 59_000L

    /** Silence the voice post-processor keeps inside every scene file (80 ms lead + 150 ms tail). */
    const val SCENE_FILE_PADDING_MS = 230L

    fun wordsPerMinute(language: AppLanguage, isShorts: Boolean): Double {
        val base = when (language) {
            AppLanguage.ENGLISH -> 150.0
            AppLanguage.HINDI -> 130.0
        }
        return if (isShorts) base * 1.1 else base
    }

    fun speechMs(words: Int, language: AppLanguage, isShorts: Boolean, speed: Float = 1f): Long {
        val s = speed.coerceIn(0.5f, 2f)
        return (words / wordsPerMinute(language, isShorts) * 60_000.0 / s).roundToLong()
    }

    /** Estimated final video length (voice + in-file padding + timeline gaps, lead-in and tail). */
    fun estimateMs(script: Script, isShorts: Boolean, speed: Float = 1f): Long {
        if (script.scenes.isEmpty()) return 0
        val speech = script.scenes.sumOf { speechMs(it.wordCount, script.language, isShorts, speed) }
        return speech + script.scenes.size * SCENE_FILE_PADDING_MS + VoiceGapModel.totalSilenceMs(script, isShorts)
    }

    /** Scripts have no format field; the writer marks Shorts with the fast style. */
    fun looksLikeShorts(script: Script): Boolean = script.style == EditStyle.FAST_SHORTS

    fun allowedRange(request: VideoRequest): LongRange {
        val target = request.targetDurationMs.coerceAtLeast(5_000L)
        return if (request.isShorts) {
            val hi = min((target * 1.05).roundToLong(), SHORTS_HARD_MAX_MS)
            val lo = (min(target, 60_000L) * 0.85).roundToLong().coerceAtMost(hi)
            lo..hi
        } else {
            (target * 0.95).roundToLong()..(target * 1.05).roundToLong()
        }
    }

    fun centreMs(request: VideoRequest): Long {
        val r = allowedRange(request)
        return (r.first + r.last) / 2
    }

    /** Recommended scene count: Shorts 4-8; long-form about one scene per 25-40 s. */
    fun recommendedSceneCount(request: VideoRequest): Int {
        val sec = request.targetDurationSec.coerceAtLeast(10)
        return if (request.isShorts) (sec / 9.0).roundToInt().coerceIn(4, 8) else (sec / 32.0).roundToInt().coerceIn(5, 40)
    }

    /** Words needed so the finished video lasts about [targetTotalMs]. */
    fun targetWords(request: VideoRequest, targetTotalMs: Long, sceneCount: Int): Int {
        val overhead = sceneCount * SCENE_FILE_PADDING_MS + VoiceGapModel.typicalOverheadMs(sceneCount, request.isShorts)
        val speechMs = (targetTotalMs - overhead).coerceAtLeast(3_000L)
        return (speechMs / 60_000.0 * wordsPerMinute(request.language, request.isShorts)).roundToInt().coerceAtLeast(8)
    }
}
