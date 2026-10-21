package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.model.CutType
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.Shot
import com.autotube.ai.core.model.TransitionType
import kotlin.math.max

/**
 * Built-in editor used when no LLM is available or the LLM plan fails. It decides the rhythm
 * (jittered shot lengths per style/emotion/intensity, cuts snapped to pauses and word gaps),
 * section-change transitions and J/L cuts. Assets, camera motion, punch-ins, text pops, SFX and
 * music cues are filled in deterministically by [EdlNormalizer], which runs on every plan.
 */
internal object HeuristicEditPlanner {

    fun plan(ctx: EditContext): EditDecisionList {
        val tracks = ctx.tracks
        val shots = ArrayList<Shot>()
        var previousFancy = false
        for ((k, track) in tracks.withIndex()) {
            val start = if (k == 0) 0L else approxBoundary(tracks[k - 1], track)
            val end = if (k == tracks.lastIndex) ctx.totalMs else approxBoundary(track, tracks[k + 1])
            if (end <= start) continue

            var transition = TransitionType.CUT
            var transitionMs = 0L
            var cutType = CutType.STRAIGHT
            if (k > 0) {
                val p = when (track.sectionChangeStrength) {
                    2 -> 0.75
                    1 -> 0.5
                    else -> 0.0
                }
                if (!previousFancy && DetRandom.chance(p, ctx.seed, track.number, 21)) {
                    transition = EditHeuristics.transitionFor(ctx, track)
                    transitionMs = EditHeuristics.transitionMs(ctx, track.number)
                } else {
                    val r = DetRandom.unit(ctx.seed, track.number, 22)
                    cutType = when {
                        r < 0.35 -> CutType.L_CUT
                        r < 0.65 -> CutType.J_CUT
                        else -> CutType.STRAIGHT
                    }
                }
            }
            previousFancy = transition != TransitionType.CUT

            val cuts = sceneCuts(ctx, track, start, end, factor = 1.0)
            val points = ArrayList<Long>(cuts.size + 2)
            points += start
            points += cuts
            points += end
            for (i in 0 until points.size - 1) {
                val first = i == 0
                shots += Shot(
                    index = shots.size,
                    sceneNumber = track.number,
                    startMs = points[i],
                    endMs = points[i + 1],
                    assetId = "",
                    transitionIn = if (first) transition else TransitionType.CUT,
                    transitionMs = if (first) transitionMs else 0L,
                    cutType = if (first) cutType else CutType.STRAIGHT,
                    reason = if (first) openingReason(ctx, track, transition, cutType) else cutReason(ctx, track, points[i]),
                )
            }
        }
        return EditDecisionList(
            style = ctx.style,
            totalMs = ctx.totalMs,
            shots = shots,
            notes = "Built-in editor",
        )
    }

    /**
     * Interior cut points for one scene span: jittered lengths around the pacing target (scaled by
     * [factor]), each snapped to the best natural cut point nearby.
     */
    fun sceneCuts(ctx: EditContext, track: SceneTrack, start: Long, end: Long, factor: Double, salt: Int = 0): List<Long> {
        val pacing = ctx.pacing
        val scene = track.scene
        val minShot = pacing.minShotMs(scene)
        val maxShot = (pacing.maxShotMs * 1.1).toLong()
        val target = (pacing.targetShotMs(scene) * factor).toLong().coerceIn(minShot, max(minShot, maxShot))
        val window = if (ctx.isShorts) 450L else 700L
        val cuts = ArrayList<Long>()
        var cursor = start
        var i = 0
        while (end - cursor > target * 1.35 && end - cursor >= 2 * minShot && i < 200) {
            var jitter = DetRandom.between(0.72, 1.28, ctx.seed, track.number, i, 5 + salt)
            // Cut a little faster right before a key moment so the reveal lands on a fresh shot.
            if (ctx.keyMoments.any { it.atMs in cursor..(cursor + target) }) jitter *= 0.85
            val len = (target * jitter).toLong().coerceIn(minShot, max(minShot, maxShot))
            val lo = cursor + minShot
            val hi = end - minShot
            if (hi < lo) break
            val cut = ctx.snapCut(cursor + len, lo, hi, window)
            if (cut <= cursor || cut >= end) break
            cuts += cut
            cursor = cut
            i++
        }
        return cuts
    }

    private fun approxBoundary(a: SceneTrack, b: SceneTrack): Long {
        val gapStart = a.timing.audioEndMs
        val gapEnd = max(b.timing.audioStartMs, gapStart)
        return (gapStart + gapEnd) / 2
    }

    private fun openingReason(@Suppress("UNUSED_PARAMETER") ctx: EditContext, track: SceneTrack, transition: TransitionType, cutType: CutType): String {
        val scene = track.scene
        val mood = scene.emotion.wireName
        return when {
            track.position == 0 -> "Open on the hook with a strong first image (${mood})"
            transition != TransitionType.CUT -> "${transition.name.lowercase().replace('_', ' ')} marks the new section (${scene.role.name.lowercase()}, $mood)"
            cutType == CutType.L_CUT -> "L-cut: the previous line finishes over the new $mood picture"
            cutType == CutType.J_CUT -> "J-cut: scene ${track.number} audio leads into its picture"
            else -> "Straight cut in the breath between scenes"
        }
    }

    private fun cutReason(ctx: EditContext, track: SceneTrack, at: Long): String {
        val seconds = ctx.pacing.targetShotMs(track.scene) / 100 / 10.0
        return "Cut on ${describeCut(ctx, at)} to keep a ~${seconds}s rhythm for the ${track.scene.emotion.wireName} part"
    }

    /** Human description of the cut point at [at] (used in shot reasons). */
    fun describeCut(ctx: EditContext, at: Long): String {
        if (at <= 0L) return "the video start"
        if (ctx.pauses.any { at >= it.first && at <= it.second }) return "a breath pause"
        val before = ctx.wordsBetween(at - 4_000L, at).lastOrNull { it.endMs <= at }
        return when {
            before == null -> "a pause"
            WordText.endsSentence(before.text) -> "a sentence end"
            WordText.endsClause(before.text) -> "a clause break"
            else -> "a word boundary"
        }
    }
}
