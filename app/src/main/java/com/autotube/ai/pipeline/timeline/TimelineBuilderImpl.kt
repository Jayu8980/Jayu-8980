package com.autotube.ai.pipeline.timeline

import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.SceneTiming
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.TimelineBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Places scene voice files back to back: a short lead-in, natural varied gaps (longer before the CTA,
 * section changes and after big reveals, shorter in Shorts) and a music-outro tail.
 *
 * For Shorts the layout never exceeds [SHORTS_HARD_MAX_MS]: when it would, gaps, lead-in and tail are
 * tightened proportionally (down to small floors) before anything else is touched.
 */
@Singleton
class TimelineBuilderImpl @Inject constructor() : TimelineBuilder {

    override fun build(request: VideoRequest, script: Script, audios: List<SceneAudio>): VoiceTimeline {
        val byScene = audios.associateBy { it.sceneNumber }
        // Script order wins; scenes without audio are skipped; audio for unknown scenes is appended.
        val ordered = script.scenes.mapNotNull { scene -> byScene[scene.sceneNumber]?.let { scene to it } }
        val known = ordered.map { it.first.sceneNumber }.toSet()
        val extra = audios.filter { it.sceneNumber !in known }.sortedBy { it.sceneNumber }
        val sceneScript = script.copy(scenes = ordered.map { it.first })
        val clips = ordered.map { it.second } + extra
        if (clips.isEmpty()) return VoiceTimeline(emptyList(), 0, 0, 0)

        val isShorts = request.isShorts
        var lead = VoiceGapModel.leadInMs(sceneScript, isShorts)
        var tail = VoiceGapModel.tailMs(isShorts)
        val gaps = LongArray(clips.size) { i ->
            when {
                i == clips.size - 1 -> 0L
                i < sceneScript.scenes.size - 1 -> VoiceGapModel.gapAfterMs(sceneScript, i, isShorts)
                else -> 350L
            }
        }
        val speech = clips.sumOf { it.durationMs.coerceAtLeast(0) }

        if (isShorts) {
            val total = lead + speech + gaps.sum() + tail
            val excess = total - SHORTS_HARD_MAX_MS
            if (excess > 0) {
                val reducible = gaps.sumOf { (it - MIN_GAP_MS).coerceAtLeast(0) } +
                    (tail - MIN_TAIL_MS).coerceAtLeast(0) + (lead - MIN_LEAD_MS).coerceAtLeast(0)
                if (reducible > 0) {
                    val f = (excess.toDouble() / reducible).coerceAtMost(1.0)
                    for (i in gaps.indices) {
                        if (gaps[i] > MIN_GAP_MS) gaps[i] -= ((gaps[i] - MIN_GAP_MS) * f).roundToLong()
                    }
                    if (tail > MIN_TAIL_MS) tail -= ((tail - MIN_TAIL_MS) * f).roundToLong()
                    if (lead > MIN_LEAD_MS) lead -= ((lead - MIN_LEAD_MS) * f).roundToLong()
                }
            }
        }

        var cursor = lead
        val timings = clips.mapIndexed { i, audio ->
            val start = cursor
            val end = start + audio.durationMs.coerceAtLeast(0)
            cursor = end + gaps[i]
            SceneTiming(sceneNumber = audio.sceneNumber, audioStartMs = start, audioEndMs = end, gapAfterMs = gaps[i])
        }
        val lastEnd = timings.last().audioEndMs
        return VoiceTimeline(scenes = timings, leadInMs = lead, tailMs = tail, totalMs = lastEnd + tail)
    }

    private companion object {
        const val SHORTS_HARD_MAX_MS = 59_000L
        const val MIN_GAP_MS = 150L
        const val MIN_TAIL_MS = 600L
        const val MIN_LEAD_MS = 200L
    }
}
